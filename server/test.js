'use strict';
// 함대 서버 통합 테스트 — 외부 의존성 없이 node 내장만 쓴다.
//
//   cd server && npm test
//
// 임시 DATA_DIR 에 서버를 실제로 띄우고 HTTP 로 검증한다(우리가 고치는 것은 대부분
// 라우팅+DB 의 조합이라, 모듈 단위보다 이 층을 묶어 확인하는 편이 회귀를 잘 잡는다).
// 각 케이스는 독립적이지 않다 — 위에서 아래로 하나의 시나리오를 쌓는다.

const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawn } = require('node:child_process');

const PORT = 8391;                      // 운영(8090)과 겹치지 않게
const BASE = `http://127.0.0.1:${PORT}`;
const DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'kiosk-test-'));
const PASSWORD = 'test-password';

let server;
let cookie = '';
const results = [];

async function test(name, fn) {
  try {
    await fn();
    results.push(['ok', name]);
    console.log(`  ✅ ${name}`);
  } catch (e) {
    results.push(['fail', name]);
    console.error(`  ❌ ${name}\n     ${e.message}`);
  }
}

// ---------- 도우미 ----------

async function checkin(deviceId, versionCode, extra = {}) {
  const r = await fetch(`${BASE}/api/checkin`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ deviceId, model: 'TEST', versionCode, versionName: 'test',
                           battery: 50, kioskLocked: true, videos: [], ...extra })
  });
  assert.equal(r.status, 200, `checkin HTTP ${r.status}`);
  return r.json();
}

async function admin(pathname, body) {
  const r = await fetch(`${BASE}${pathname}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded', Cookie: cookie },
    body: new URLSearchParams(body).toString(),
    redirect: 'manual'
  });
  assert.ok(r.status === 302 || r.status === 200, `${pathname} HTTP ${r.status}`);
  return r;
}

// ---------- 준비 ----------

async function setup() {
  // 자료실에 자동 등록될 더미 영상(내용은 아무거나 — 등록·큐잉 경로만 필요하다).
  // mtime 을 과거로 돌린다 — 서버는 방금 바뀐 파일을 "아직 복사 중"으로 보고 건너뛴다.
  fs.mkdirSync(path.join(DATA_DIR, 'videos'), { recursive: true });
  const dummy = path.join(DATA_DIR, 'videos', '테스트영상.mp4');
  fs.writeFileSync(dummy, Buffer.alloc(1024, 7));
  const old = new Date(Date.now() - 10 * 60 * 1000);
  fs.utimesSync(dummy, old, old);

  server = spawn(process.execPath, ['server.js'], {
    cwd: __dirname,
    env: { ...process.env, PORT: String(PORT), DATA_DIR, ADMIN_PASSWORD: PASSWORD,
           SESSION_SECRET: 'test-secret' },
    stdio: ['ignore', 'pipe', 'pipe']
  });
  server.stderr.on('data', d => process.stderr.write(`[server] ${d}`));

  // /health 가 ok 를 줄 때까지 대기
  for (let i = 0; i < 50; i++) {
    try {
      if ((await (await fetch(`${BASE}/health`)).text()) === 'ok') break;
    } catch (e) { /* 아직 */ }
    await new Promise(r => setTimeout(r, 200));
    if (i === 49) throw new Error('서버가 10초 안에 뜨지 않음');
  }

  const login = await fetch(`${BASE}/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: `password=${encodeURIComponent(PASSWORD)}`,
    redirect: 'manual'
  });
  cookie = (login.headers.get('set-cookie') || '').split(';')[0];
  assert.ok(cookie.includes('kiosk_admin='), '로그인 실패');
}

// ---------- 본편 ----------

async function main() {
  await setup();
  const DEV = 'test-device-1';

  // 자료실 자동 등록 확인 + media id 확보
  await checkin(DEV, 21);
  await fetch(`${BASE}/dashboard`, { headers: { Cookie: cookie } });   // syncMediaFolder 트리거
  const db = require('better-sqlite3')(path.join(DATA_DIR, 'fleet.db'), { readonly: true });
  const mediaId = db.prepare(`SELECT id FROM media_library WHERE original_name = ?`)
    .get('테스트영상.mp4')?.id;
  assert.ok(mediaId, '더미 영상이 자료실에 자동 등록되지 않음');

  await test('push(ask) → 체크인 응답 pushVideos[].ask = true', async () => {
    await admin('/media/push', { deviceId: DEV, mediaId, mode: 'ask' });
    const resp = await checkin(DEV, 21);
    assert.equal(resp.pushVideos.length, 1);
    assert.equal(resp.pushVideos[0].ask, true);
    assert.equal(resp.pushVideos[0].name, '테스트영상.mp4');
  });

  await test('같은 영상 push(force) 재요청 → mode 가 force 로 승격', async () => {
    await admin('/media/push', { deviceId: DEV, mediaId, mode: 'force' });
    const resp = await checkin(DEV, 21);
    assert.equal(resp.pushVideos.length, 1, '중복 큐잉되면 안 됨');
    assert.equal(resp.pushVideos[0].ask, false);
  });

  await test('push 취소 → 대기열에서 빠짐', async () => {
    await admin('/media/push/cancel', { deviceId: DEV, mediaId });
    const resp = await checkin(DEV, 21);
    assert.equal(resp.pushVideos.length, 0);
  });

  await test('영상 보유 보고 → push 대기열 자동 확정(제거)', async () => {
    await admin('/media/push', { deviceId: DEV, mediaId, mode: 'force' });
    const resp = await checkin(DEV, 21, { videos: [{ name: '테스트영상.mp4', size: 1024 }] });
    assert.equal(resp.pushVideos.length, 0, '보유 중인 영상이 다시 내려오면 안 됨');
  });

  await test('update-force → 체크인 응답 forceUpdate = true', async () => {
    await admin('/device/update-force', { deviceId: DEV });
    const resp = await checkin(DEV, 21);
    assert.equal(resp.forceUpdate, true);
  });

  await test('진행률 보고 → /api/transfers 에 반영', async () => {
    const r = await fetch(`${BASE}/api/progress`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ deviceId: DEV, kind: 'video', name: '테스트영상.mp4',
                             received: 512, total: 1024, status: 'downloading' })
    });
    assert.equal(r.status, 200);
    const list = await (await fetch(`${BASE}/api/transfers`, { headers: { Cookie: cookie } })).json();
    const t = list.find(x => x.deviceId === DEV && x.name === '테스트영상.mp4');
    assert.ok(t, '전송 항목 없음');
    assert.equal(t.received, 512);
  });

  await test('진행률 조회는 로그인 필요', async () => {
    const r = await fetch(`${BASE}/api/transfers`, { redirect: 'manual' });
    assert.equal(r.status, 302, '비로그인인데 200 이면 안 됨');
  });

  await test('poke: 대기 중 push 가 오면 즉시 checkinNow:true', async () => {
    const wait = fetch(`${BASE}/api/poke?deviceId=${DEV}`).then(r => r.json());
    await new Promise(r => setTimeout(r, 300));   // long-poll 이 자리잡을 시간
    await admin('/media/push', { deviceId: DEV, mediaId, mode: 'force' });
    const resp = await Promise.race([
      wait,
      new Promise((_, rej) => setTimeout(() => rej(new Error('3초 안에 안 깨어남')), 3000))
    ]);
    assert.equal(resp.checkinNow, true);
  });

  await test('대시보드 HTML 에 중첩 <form> 없음(취소 버그 회귀 방지)', async () => {
    // 대기 항목이 있는 상태의 대시보드를 실제로 렌더시켜 검사한다.
    const html = await (await fetch(`${BASE}/dashboard`, { headers: { Cookie: cookie } })).text();
    let open = false;
    for (const m of html.matchAll(/<(\/?)form\b[^>]*>/g)) {
      if (m[1] === '/') { open = false; continue; }
      assert.ok(!open, `중첩 <form> 발견: …${html.slice(Math.max(0, m.index - 60), m.index + 60)}…`);
      open = true;
    }
    assert.ok(html.includes('/media/push/cancel'), '취소 폼 자체가 없음');
  });

  db.close();
}

main()
  .catch(e => { console.error(e); results.push(['fail', '(테스트 러너 자체 오류)']); })
  .finally(() => {
    if (server) server.kill('SIGTERM');
    fs.rmSync(DATA_DIR, { recursive: true, force: true });
    const fails = results.filter(r => r[0] === 'fail').length;
    console.log(`\n  ${results.length}개 중 ${results.length - fails}개 통과${fails ? `, ${fails}개 실패` : ''}`);
    process.exit(fails ? 1 : 0);
  });
