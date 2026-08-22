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
    // 앞선 테스트들의 push 가 남긴 pending wake 를 먼저 비운다 — 안 비우면 아래 fetch 가
    // "대기 없이 즉시 true" 경로로 통과해버려 edge(대기 중 깨우기) 검증이 무의미해진다.
    try {
      const drain = new AbortController();
      const t = setTimeout(() => drain.abort(), 700);
      await fetch(`${BASE}/api/poke?deviceId=${DEV}`, { signal: drain.signal });
      clearTimeout(t);
    } catch (e) { /* pending 없음 — 타임아웃으로 끊김 */ }

    const wait = fetch(`${BASE}/api/poke?deviceId=${DEV}`).then(r => r.json());
    await new Promise(r => setTimeout(r, 300));   // long-poll 이 자리잡을 시간
    await admin('/media/push', { deviceId: DEV, mediaId, mode: 'force' });
    const resp = await Promise.race([
      wait,
      new Promise((_, rej) => setTimeout(() => rej(new Error('3초 안에 안 깨어남')), 3000))
    ]);
    assert.equal(resp.checkinNow, true);
  });

  await test('poke: wake 시점에 대기자가 없어도 다음 접속이 즉시 깨어난다(pending wake)', async () => {
    // 기기가 체크인 처리 중(=long-poll 미접속)에 관리자가 push 를 누르면 그 깨움이
    // 유실되어 지시가 다음 정규 주기(10분)까지 밀린다 — 실기기에서 두 번 재현된 레이스.
    // wakeDevice 는 대기자가 없으면 기억해뒀다가, 다음 poke 접속에 즉시 응답해야 한다.
    await admin('/media/push/cancel', { deviceId: DEV, mediaId });   // 큐 정리(부수효과 제거)
    await admin('/media/push', { deviceId: DEV, mediaId, mode: 'force' });  // 대기자 없는 wake
    const ctrl = new AbortController();
    const t = setTimeout(() => ctrl.abort(), 3000);
    let resp;
    try {
      resp = await (await fetch(`${BASE}/api/poke?deviceId=${DEV}`, { signal: ctrl.signal })).json();
    } catch (e) {
      throw new Error('3초 안에 응답 없음 — pending wake 미구현이면 50초 대기로 빠진다');
    } finally { clearTimeout(t); }
    assert.equal(resp.checkinNow, true);
    await admin('/media/push/cancel', { deviceId: DEV, mediaId });   // 뒷정리
  });

  await test('fleet-rev: 로그인 필요 + 체크인마다 증가(대시보드 자동 새로고침 신호)', async () => {
    const noAuth = await fetch(`${BASE}/api/fleet-rev`, { redirect: 'manual' });
    assert.equal(noAuth.status, 302, '비로그인인데 200 이면 안 됨');
    const r1 = await (await fetch(`${BASE}/api/fleet-rev`, { headers: { Cookie: cookie } })).json();
    assert.ok(Number.isInteger(r1.rev), 'rev 가 정수가 아님');
    await checkin(DEV, 21);
    const r2 = await (await fetch(`${BASE}/api/fleet-rev`, { headers: { Cookie: cookie } })).json();
    assert.ok(r2.rev > r1.rev, `체크인 후 rev 가 안 올랐음 (${r1.rev} → ${r2.rev})`);
  });

  await test('원격 재부팅: 응답에 1회만 실리고 즉시 소진(fire-and-forget)', async () => {
    await admin('/device/reboot', { deviceId: DEV });
    const r1 = await checkin(DEV, 21);
    assert.equal(r1.reboot, true, '첫 체크인에 reboot 지시가 없음');
    const r2 = await checkin(DEV, 21);
    assert.ok(!r2.reboot, '두 번째 체크인에도 reboot 이 실리면 재부팅 루프가 된다');
  });

  await test('always-on VPN 보고가 대시보드에 보인다 (재부팅 생존 사전 경고)', async () => {
    // 미지정 상태로 보고 → 대시보드에 경고가 떠야 한다. 이게 없으면 "재부팅하면 끊기는
    // 기기"를 재부팅해 본 뒤에야 알게 된다(실제로 그렇게 QA 한 사이클을 날렸다).
    await checkin(DEV, 21, { alwaysOnVpn: null });
    const warn = await (await fetch(`${BASE}/dashboard`, { headers: { Cookie: cookie } })).text();
    assert.ok(warn.includes('VPN 미지정'), '미지정 경고가 대시보드에 없음');

    await checkin(DEV, 21, { alwaysOnVpn: 'io.netbird.client' });
    const ok = await (await fetch(`${BASE}/dashboard`, { headers: { Cookie: cookie } })).text();
    assert.ok(!ok.includes('VPN 미지정'), '지정된 뒤에도 경고가 남아 있음');
  });

  await test('서버 주소 변경: 보고가 일치할 때까지 지시, 일치하면 중단', async () => {
    await admin('/device/fleet-url', { deviceId: DEV, fleetUrl: 'http://100.99.99.99:8090' });
    const r1 = await checkin(DEV, 21, { fleetUrl: 'http://old.example:8090' });
    assert.equal(r1.setFleetUrl, 'http://100.99.99.99:8090');
    // 응답 유실 가정 — 같은 옛 주소로 또 보고해도 계속 내려와야 한다
    const r2 = await checkin(DEV, 21, { fleetUrl: 'http://old.example:8090' });
    assert.equal(r2.setFleetUrl, 'http://100.99.99.99:8090');
    // 기기가 새 주소 사용을 보고하면 중단
    const r3 = await checkin(DEV, 21, { fleetUrl: 'http://100.99.99.99:8090' });
    assert.ok(!r3.setFleetUrl, '적용 완료 후에도 지시가 내려오면 안 됨');
    await admin('/device/fleet-url', { deviceId: DEV, fleetUrl: '' });   // 뒷정리(지시 철회)
  });

  await test('한글 이름 영상 다운로드가 200 (Content-Disposition 헤더 회귀 방지)', async () => {
    // HTTP 헤더는 ISO-8859-1 만 허용 — 한글을 filename= 에 그대로 넣으면 500 이 난다.
    // 실기기에서 한글 영상 push 전멸의 원인이었다.
    const r = await fetch(`${BASE}/media/${mediaId}/download`);
    assert.equal(r.status, 200, `다운로드 HTTP ${r.status}`);
    const buf = Buffer.from(await r.arrayBuffer());
    assert.equal(buf.length, 1024, '파일 크기 불일치');
    const cd = r.headers.get('content-disposition') || '';
    assert.ok(cd.includes("filename*=UTF-8''"), 'RFC 5987 인코딩 누락: ' + cd);
  });

  await test('썸네일 등록/교체 → 그 영상을 보유한 기기를 즉시 깨움', async () => {
    // 보유 기기만 깨어나야 하므로, 이 기기가 테스트영상을 보유했다는 보고를 먼저 만든다
    // (앞 테스트들의 체크인이 videos:[] 로 보유 목록을 덮어써 뒀다).
    await checkin(DEV, 21, { videos: [{ name: '테스트영상.mp4', size: 1024 }] });
    try {   // 앞 테스트들이 남긴 pending wake 를 먼저 비운다
      const drain = new AbortController();
      const t = setTimeout(() => drain.abort(), 700);
      await fetch(`${BASE}/api/poke?deviceId=${DEV}`, { signal: drain.signal });
      clearTimeout(t);
    } catch (e) { /* pending 없음 */ }

    const fd = new FormData();
    fd.set('mediaId', String(mediaId));
    fd.set('thumb', new Blob([Buffer.alloc(500, 3)]), 'cover.jpg');
    const up = await fetch(`${BASE}/media/thumb`, {
      method: 'POST', headers: { Cookie: cookie }, body: fd, redirect: 'manual'
    });
    assert.ok(up.status === 302 || up.status === 200, `썸네일 업로드 HTTP ${up.status}`);

    const ctrl = new AbortController();
    const t2 = setTimeout(() => ctrl.abort(), 3000);
    let resp;
    try {
      resp = await (await fetch(`${BASE}/api/poke?deviceId=${DEV}`, { signal: ctrl.signal })).json();
    } catch (e) {
      throw new Error('3초 안에 안 깨어남 — 썸네일 라우트가 wakeDevice 를 안 부른다');
    } finally { clearTimeout(t2); }
    assert.equal(resp.checkinNow, true);
  });

  await test('대시보드 HTML 에 중첩 <form> 없음(취소 버그 회귀 방지)', async () => {
    // 앞 테스트의 정리 순서와 무관하게, "받는 중"(취소 폼 포함) 상태를 직접 만든 뒤 렌더한다.
    await admin('/media/push', { deviceId: DEV, mediaId, mode: 'ask' });
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
  .finally(async () => {
    // Windows 는 다른 프로세스가 열고 있는 파일이 든 디렉터리 삭제를 EPERM 으로 거부한다
    // (mac/리눅스는 열린 파일도 unlink 돼서 이 문제가 안 드러난다). kill 직후 바로 지우면
    // 12개가 전부 통과해도 마지막에 터져 npm test 가 실패로 끝난다 → 종료를 기다린 뒤 지운다.
    if (server) {
      const exited = new Promise(res => server.once('exit', res));
      server.kill('SIGTERM');
      await Promise.race([exited, new Promise(res => setTimeout(res, 5000))]);
    }
    fs.rmSync(DATA_DIR, { recursive: true, force: true, maxRetries: 10, retryDelay: 100 });
    const fails = results.filter(r => r[0] === 'fail').length;
    console.log(`\n  ${results.length}개 중 ${results.length - fails}개 통과${fails ? `, ${fails}개 실패` : ''}`);
    process.exit(fails ? 1 : 0);
  });
