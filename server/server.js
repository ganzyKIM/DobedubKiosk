'use strict';
// 두비덥 키오스크 함대 관리 서버
//   - 기기 체크인 수집(POST /api/checkin) + 응답에 최신 버전 매니페스트 포함
//   - APK 배포(GET /download/app.apk) + 배포 이력/롤백, 강제 업데이트 알림
//   - 영상 자료실 업로드 + 기기별 배포(푸시)
//   - 백오피스 대시보드(/dashboard, 공유 비밀번호 로그인)
//
// 환경변수(.env 아님 — 프로세스 환경): ADMIN_PASSWORD, SESSION_SECRET, DEVICE_TOKEN,
//   PORT(기본 8090), BASE_URL(APK 절대 URL 구성용, 선택), COOKIE_SECURE(HTTPS 뒤면 1)

const path = require('path');
const fs = require('fs');
const crypto = require('crypto');
const express = require('express');
const multer = require('multer');

const store = require('./db');
const auth = require('./auth');
const views = require('./views');

const PORT = Number(process.env.PORT || 8090);
const DEVICE_TOKEN = process.env.DEVICE_TOKEN || '';   // 설정 시 기기 체크인에 X-Kiosk-Token 요구

// "접속 중" 판정은 **기기가 보고한 자기 체크인 주기**에서 기기별로 파생시킨다.
// 예전엔 서버에 전역 상수를 박아뒀는데, 앱 주기를 바꾸면 아직 업데이트 안 된 구버전
// 기기들이 일제히 "미접속"으로 보였다(앱은 30분인데 서버 임계값은 10분 기준 등).
// 주기를 보고하지 않는 구버전 앱(v2.1 이하)은 그 시절 값인 30분으로 가정한다.
const LEGACY_CHECKIN_INTERVAL_MS = 30 * 60 * 1000;
// 두 주기를 연속으로 놓치면 이상 신호로 본다(+네트워크 지연 여유 10분).
const onlineMsFor = d => (d.checkin_interval_ms || LEGACY_CHECKIN_INTERVAL_MS) * 2 + 10 * 60 * 1000;
const STALE_MS = 24 * 60 * 60 * 1000;
const APK_DIR = path.join(store.DATA_DIR, 'apk');
const VIDEO_DIR = path.join(store.DATA_DIR, 'videos');
const MANUAL_DIR = path.join(store.DATA_DIR, 'manuals');
fs.mkdirSync(APK_DIR, { recursive: true });
fs.mkdirSync(VIDEO_DIR, { recursive: true });
fs.mkdirSync(MANUAL_DIR, { recursive: true });

// 기기(VideoRepository)가 인식하는 확장자와 반드시 같아야 한다 — 다르면 다운로드는
// 되는데 목록에 안 뜨는 상태가 된다.
const VIDEO_EXTENSIONS = new Set(['.mp4', '.m4v', '.mkv', '.webm']);

const app = express();
app.set('trust proxy', true); // 리버스 프록시(nginx/caddy) 뒤에서 실제 IP/프로토콜 인식
app.use(express.json({ limit: '1mb' }));
app.use(express.urlencoded({ extended: false }));

const uploadApk = multer({
  storage: multer.diskStorage({
    destination: (req, file, cb) => cb(null, APK_DIR),
    filename: (req, file, cb) => cb(null, `upload-${Date.now()}.apk.tmp`)
  }),
  limits: { fileSize: 300 * 1024 * 1024 } // 300MB
});

// 보이스툰 영상은 기기당 1편이 2GB를 넘기도 한다(실측) — APK보다 훨씬 큰 한도가 필요하다.
const uploadVideo = multer({
  storage: multer.diskStorage({
    destination: (req, file, cb) => cb(null, VIDEO_DIR),
    filename: (req, file, cb) => cb(null, `upload-${Date.now()}.tmp`)
  }),
  limits: { fileSize: 4 * 1024 * 1024 * 1024 } // 4GB
});

// 썸네일: 몇백 KB짜리 이미지 하나. 어떤 포맷이 와도 `<영상파일명>.jpg` 이름으로 저장한다 —
// 브라우저도 기기(BitmapFactory)도 내용을 보고 디코딩하므로 확장자가 실체와 달라도 무방하고,
// 이름을 영상에 1:1로 묶어두면 별도 매핑 테이블이 필요 없다.
const uploadThumb = multer({
  storage: multer.diskStorage({
    destination: (req, file, cb) => cb(null, VIDEO_DIR),
    filename: (req, file, cb) => cb(null, `thumb-upload-${Date.now()}.tmp`)
  }),
  limits: { fileSize: 10 * 1024 * 1024 } // 10MB
});

// 앱에 내장된 기본 이용안내 이미지(관리 화면 미리보기 전용).
// 저장소에 3.4MB를 복제하지 않으려고 앱 리소스 폴더를 그대로 읽는다 — 서버가 리포지토리
// 안에서 도는 지금 구성에서만 성립하므로, 폴더가 없으면 조용히 "미리보기 없음"이 된다.
const BUILTIN_MANUAL_DIR = process.env.BUILTIN_MANUAL_DIR ||
  path.join(__dirname, '..', 'app', 'src', 'main', 'res', 'drawable-nodpi');

function builtinManualFiles() {
  try {
    return fs.readdirSync(BUILTIN_MANUAL_DIR)
      .filter(n => /^user_manual_\d+\.png$/.test(n))
      .sort();
  } catch (e) {
    return [];
  }
}

// 이용안내 이미지. 여러 장을 한 번에 올릴 수 있게 array 로 받는다.
const uploadManual = multer({
  storage: multer.diskStorage({
    destination: (req, file, cb) => cb(null, MANUAL_DIR),
    filename: (req, file, cb) => cb(null, `manual-upload-${Date.now()}-${Math.random().toString(36).slice(2, 8)}.tmp`)
  }),
  limits: { fileSize: 30 * 1024 * 1024, files: 40 }
});

/**
 * 기기가 내려받을 때 쓸 파일명. **순서 번호를 이름 앞에 붙인다** — 기기는 파일명을
 * 정렬하기만 하면 표시 순서가 나오므로, 순서를 따로 저장할 필요가 없다.
 * 뒤에 내용 해시를 붙여서, 순서만 바뀐 경우 기기가 재다운로드 없이 이름만 바꿔 재사용한다.
 */
function manualDeviceName(row, seq) {
  const ext = (path.extname(row.original_name) || '.png').toLowerCase();
  return `${String(seq).padStart(3, '0')}_${row.sha256.slice(0, 12)}${ext}`;
}

function sha256File(p) {
  return new Promise((resolve, reject) => {
    const h = crypto.createHash('sha256');
    fs.createReadStream(p).on('data', d => h.update(d)).on('end', () => resolve(h.digest('hex'))).on('error', reject);
  });
}

// 원본 파일명에서 경로 구분자를 걷어내고 확장자를 검증한다. 기기 쪽 VideoRepository도
// 정확히 같은 이름으로 저장하므로, 여기서 막지 않으면 그대로 뚫린다.
function sanitizeVideoName(original) {
  const base = path.basename(String(original || '')).trim();
  const ext = path.extname(base).toLowerCase();
  if (!base || base === ext || !VIDEO_EXTENSIONS.has(ext)) return null;
  return base;
}

// ---------- 영상 폴더 자동 인식 ----------
//
// data/videos/ 를 그냥 "영상 저장소"로 쓴다 — 폴더에 파일을 복사해 넣으면 자료실에 뜬다.
// 대시보드 업로드 폼으로 수 GB짜리를 브라우저로 올리는 게 현장에서 제일 번거로웠다.
//
// 폴더가 진실이고 DB는 그 색인이다. 그래서 양방향으로 맞춘다:
//   폴더에 있는데 DB에 없다  → 등록 (sha256 계산)
//   DB에 있는데 폴더에 없다  → 행 제거 (배포 대기열도 함께 정리)
//
// ponytail: fs.watch 대신 "필요할 때 폴더 훑기". watch 는 복사가 끝나기 전에 이벤트가
// 떠서 반쯤 쓰인 파일을 해싱하는 함정이 있고, 그걸 피하려면 결국 여기서 하는 안정성
// 검사를 똑같이 해야 한다. 파일이 수백 개가 되면 그때 watch 를 고려할 것.

/** 이 시간 안에 수정된 파일은 아직 복사 중일 수 있으므로 건너뛴다(다음 훑기에서 잡는다). */
const MEDIA_SETTLE_MS = 10_000;
let mediaSyncRunning = false;

async function syncMediaFolder() {
  if (mediaSyncRunning) return;          // 대시보드를 연달아 열어도 같은 파일을 두 번 해싱하지 않는다
  mediaSyncRunning = true;
  try {
    let entries;
    try {
      entries = fs.readdirSync(VIDEO_DIR, { withFileTypes: true });
    } catch (e) {
      // 폴더를 못 읽으면 아무것도 하지 않는다. 여기서 "파일이 없다"고 판단해 정리로 넘어가면
      // 경로가 잘못 잡힌 순간 자료실 전체가 지워진다.
      console.error('[media] 폴더를 읽지 못해 동기화를 건너뜁니다:', e.message);
      return;
    }

    const onDisk = new Map();  // 파일명 → 크기
    for (const e of entries) {
      if (!e.isFile()) continue;
      if (!VIDEO_EXTENSIONS.has(path.extname(e.name).toLowerCase())) continue;
      const full = path.join(VIDEO_DIR, e.name);
      const st = fs.statSync(full);
      if (Date.now() - st.mtimeMs < MEDIA_SETTLE_MS) continue;   // 아직 복사 중
      onDisk.set(e.name, st.size);
    }

    const known = new Set(store.listMedia().map(m => m.filename));

    for (const [name, size] of onDisk) {
      if (known.has(name)) continue;
      console.log(`[media] 새 영상 발견: ${name} (${(size / 1024 / 1024).toFixed(0)}MB) — 해시 계산 중`);
      const sha = await sha256File(path.join(VIDEO_DIR, name));
      const id = store.insertMedia({
        filename: name,          // 폴더에 넣은 파일은 이름을 바꾸지 않는다 — 그 자체가 식별자다
        original_name: name,
        size, sha256: sha, uploaded_at: Date.now()
      });
      console.log(`[media] 자료실 등록 완료: ${name} (id ${id})`);
    }

    for (const m of store.listMedia()) {
      if (onDisk.has(m.filename)) continue;
      if (fs.existsSync(path.join(VIDEO_DIR, m.filename))) continue;  // 복사 중이라 건너뛴 것일 수 있다
      console.log(`[media] 파일이 사라져 자료실에서 제거: ${m.filename}`);
      if (m.thumb) { try { fs.unlinkSync(path.join(VIDEO_DIR, m.thumb)); } catch (e) { /* */ } }
      store.deleteMedia(m.id);
    }
  } catch (e) {
    console.error('[media] 폴더 동기화 오류', e);
  } finally {
    mediaSyncRunning = false;
  }
}

function manifestFor(req, deviceRow) {
  const rel = store.getRelease();
  if (!rel) return { update: false };
  const base = process.env.BASE_URL || `${req.protocol}://${req.get('host')}`;
  const resp = {
    update: true,
    versionCode: rel.version_code,
    versionName: rel.version_name,
    apkPath: '/download/app.apk',
    apkUrl: `${base.replace(/\/$/, '')}/download/app.apk`,
    sha256: rel.sha256,
    size: rel.size,
    notes: rel.notes || ''
  };
  // 관리자가 이 기기에 "업데이트 하시겠어요?" 확인창을 요청해뒀고, 실제로 더 새 버전이
  // 있을 때만 표시 지시를 내린다(이미 최신이면 굳이 물을 필요 없음).
  resp.promptUpdate = !!(deviceRow && deviceRow.update_prompt);
  return resp;
}

// ---------- 즉시 지시 채널(long-poll) ----------
//
// 앱은 체크인 사이를 delay 대신 이 엔드포인트 long-poll 로 기다린다(서버가 최대
// POKE_HOLD_MS 붙잡음). 관리자가 "지금 바로" 성격의 지시(영상 push, 즉시 업데이트 등)를
// 내리면 wakeDevice() 가 대기 중인 연결에 checkinNow:true 를 응답하고, 앱은 곧바로
// 체크인한다. **지시 자체는 여전히 체크인 응답으로만 내려간다** — 이 채널은 "깨우기"만
// 하므로 응답 유실·구버전 앱(404 폴백)·오프라인 기기 모두 기존 경로로 자연 수렴한다.
const POKE_HOLD_MS = 50 * 1000;
// 대기자 없이 wake 가 온 경우의 "밀린 깨움" 보관 시간. 이보다 오래되면 정규 체크인이
// 이미 지나갔을 것이므로 버린다(스테일 즉시-체크인 방지).
const PENDING_WAKE_TTL_MS = 10 * 60 * 1000;
const pokeWaiters = new Map();   // deviceId → { res, timer }
const pendingWakes = new Map();  // deviceId → wake 시각. 대기자 없을 때 온 깨움을 기억한다.

function wakeDevice(deviceId) {
  const w = pokeWaiters.get(deviceId);
  if (!w) {
    // 기기가 마침 체크인 처리 중이거나(=poke 미접속 틈) 오프라인 — 깨움을 버리면 지시가
    // 다음 정규 주기(10분)까지 밀린다(실기기에서 재현된 레이스). 기억해뒀다가 다음 poke
    // 접속에 즉시 응답한다. 오프라인 기기는 TTL 뒤 소멸하고 정규 체크인 경로가 처리한다.
    pendingWakes.set(deviceId, Date.now());
    return;
  }
  pokeWaiters.delete(deviceId);
  clearTimeout(w.timer);
  try { w.res.json({ checkinNow: true }); } catch (e) { /* 이미 끊긴 연결 */ }
}

app.get('/api/poke', (req, res) => {
  if (DEVICE_TOKEN && req.get('X-Kiosk-Token') !== DEVICE_TOKEN) {
    return res.status(401).json({ error: 'unauthorized' });
  }
  const deviceId = String(req.query.deviceId || '').slice(0, 128);
  if (!deviceId) return res.status(400).json({ error: 'deviceId required' });

  // 접속 공백 중에 밀린 깨움이 있으면 붙잡지 않고 즉시 응답한다(레벨 트리거).
  const pendingAt = pendingWakes.get(deviceId);
  if (pendingAt !== undefined) {
    pendingWakes.delete(deviceId);
    if (Date.now() - pendingAt < PENDING_WAKE_TTL_MS) {
      return res.json({ checkinNow: true });
    }
    // TTL 지난 스테일 깨움은 버리고 평소처럼 대기
  }

  // 같은 기기의 이전 대기가 남아 있으면(앱 재시작 등) 그쪽을 먼저 정리한다.
  const prev = pokeWaiters.get(deviceId);
  if (prev) {
    clearTimeout(prev.timer);
    try { prev.res.json({ checkinNow: false }); } catch (e) { /* */ }
  }
  const timer = setTimeout(() => {
    pokeWaiters.delete(deviceId);
    try { res.json({ checkinNow: false }); } catch (e) { /* */ }
  }, POKE_HOLD_MS);
  pokeWaiters.set(deviceId, { res, timer });
  // 클라이언트가 먼저 끊으면(타임아웃·네트워크 전환) 대기 엔트리를 치워 메모리 누수 방지.
  req.on('close', () => {
    const w = pokeWaiters.get(deviceId);
    if (w && w.res === res) { pokeWaiters.delete(deviceId); clearTimeout(w.timer); }
  });
});

// ---------- 전송 진행률 ----------
//
// 기기가 다운로드 중 주기적으로 보고한다. 휘발성 상태라 메모리에만 둔다(서버 재시작 시
// 사라져도 다음 보고가 다시 채운다). 대시보드는 /api/transfers 를 폴링해 퍼센티지를 그린다.
const transfers = new Map();   // "deviceId|kind|name" → { …, at }

app.post('/api/progress', (req, res) => {
  if (DEVICE_TOKEN && req.get('X-Kiosk-Token') !== DEVICE_TOKEN) {
    return res.status(401).json({ error: 'unauthorized' });
  }
  const b = req.body || {};
  const deviceId = String(b.deviceId || '').slice(0, 128);
  const kind = b.kind === 'apk' ? 'apk' : 'video';
  const name = String(b.name || '').slice(0, 200);
  const status = ['downloading', 'done', 'failed'].includes(b.status) ? b.status : 'downloading';
  if (!deviceId || !name) return res.status(400).json({ error: 'deviceId/name required' });
  transfers.set(`${deviceId}|${kind}|${name}`, {
    deviceId, kind, name, status,
    received: Number.isFinite(Number(b.received)) ? Number(b.received) : 0,
    total: Number.isFinite(Number(b.total)) ? Number(b.total) : 0,
    error: typeof b.error === 'string' ? b.error.slice(0, 200) : null,
    at: Date.now()
  });
  res.json({ ok: true });
});

// 끝난 항목은 잠시 보여준 뒤 치우고, 보고가 끊긴 항목(앱 강제 종료 등)도 정리한다.
setInterval(() => {
  const now = Date.now();
  for (const [k, t] of transfers) {
    const age = now - t.at;
    if ((t.status !== 'downloading' && age > 60 * 1000) || age > 10 * 60 * 1000) transfers.delete(k);
  }
  for (const [id, at] of pendingWakes) {
    if (now - at >= PENDING_WAKE_TTL_MS) pendingWakes.delete(id);
  }
}, 30 * 1000).unref();

app.get('/api/transfers', auth.requireAuth, (req, res) => {
  res.json(Array.from(transfers.values()));
});

// ---------- 기기 API ----------

// 체크인: 기기가 주기적으로 상태를 보고하고, 응답으로 최신 버전 매니페스트를 받는다.
app.post('/api/checkin', (req, res) => {
  if (DEVICE_TOKEN && req.get('X-Kiosk-Token') !== DEVICE_TOKEN) {
    return res.status(401).json({ error: 'unauthorized' });
  }
  const b = req.body || {};
  if (!b.deviceId || typeof b.deviceId !== 'string') {
    return res.status(400).json({ error: 'deviceId required' });
  }
  const deviceId = b.deviceId.slice(0, 128);
  try {
    store.recordCheckin({
      deviceId,
      model: b.model, serial: b.serial,
      versionCode: Number(b.versionCode),
      versionName: b.versionName,
      battery: Number(b.battery),
      kioskLocked: !!b.kioskLocked,
      startUrl: b.startUrl,
      appLabel: b.appLabel,
      videos: Array.isArray(b.videos) ? b.videos.slice(0, 200) : [],
      contact: typeof b.contactInfo === 'string' ? b.contactInfo.slice(0, 100) : null,
      // 구버전 앱은 이 값을 안 보낸다 — undefined 를 false 로 오해해 PIN 초기화를
      // 멋대로 확정 처리하지 않도록, boolean 일 때만 넘긴다.
      hasCustomPin: typeof b.hasCustomPin === 'boolean' ? b.hasCustomPin : undefined,
      checkinIntervalMs: Number(b.checkinIntervalMs),
      // 설치 장소 식별용. AP는 항상 잡히지만 좌표는 NLP 켜진 기기에서만 온다.
      apSsid: typeof b.apSsid === 'string' ? b.apSsid.slice(0, 64) : null,
      apBssid: typeof b.apBssid === 'string' ? b.apBssid.slice(0, 32) : null,
      lat: Number(b.lat), lng: Number(b.lng),
      locAccuracy: Number(b.locAccuracy), locatedAt: Number(b.locatedAt),
      ip: req.ip
    });
  } catch (e) {
    console.error('checkin error', e);
    return res.status(500).json({ error: 'server error' });
  }
  // 매니페스트(업데이트) + 이 기기에 대한 영상 삭제/배포 지시를 함께 응답.
  const deviceRow = store.allDevices().find(d => d.device_id === deviceId) || null;
  const resp = manifestFor(req, deviceRow);

  // 관리자가 지정한 연락처가 기기가 보고한 값과 다르면 덮어쓰라고 내려보낸다.
  // 같아지면 자동으로 안 보내지므로 별도 완료 플래그가 필요 없다.
  if (deviceRow && deviceRow.contact_override && deviceRow.contact_override !== deviceRow.contact) {
    resp.setContact = deviceRow.contact_override;
  }
  if (deviceRow && deviceRow.pin_reset) resp.resetPin = true;

  resp.deleteVideos = store.pendingVideoDeletes(deviceId);
  const base = process.env.BASE_URL || `${req.protocol}://${req.get('host')}`;
  resp.pushVideos = store.pendingVideoPushes(deviceId).map(p => ({
    name: p.original_name,
    url: `${base.replace(/\/$/, '')}/media/${p.media_id}/download`,
    sha256: p.sha256,
    size: p.size,
    // true 면 기기가 바로 받지 않고 화면에서 동의를 구한다. 구버전 앱은 이 필드를
    // 모른 채 즉시 받는다(= 기존 동작) — 해로운 방향의 오동작은 아니다.
    ask: p.mode === 'ask'
  }));
  // 관리자의 명시적 "즉시 업데이트": 재생 중이어도 설치. promptUpdate 보다 우선한다.
  if (deviceRow && deviceRow.force_update) resp.forceUpdate = true;
  // 기기가 보유했다고 방금 보고한 영상 중 썸네일이 등록된 것들. 기기는 없는 것만(또는
  // 크기가 달라진 것만) 내려받는다. 지금 push 로 내려가는 영상의 썸네일은 다음 체크인에
  // 인벤토리에 잡히면서 따라간다 — 경로를 하나로 유지하기 위한 의도적 지연.
  // 이용안내 세트. 순서 번호가 붙은 파일명을 그대로 내려보내므로, 기기는 파일명만
  // 맞추면 순서까지 맞는다. 빈 배열이면 기기가 자기 이미지를 지우고 내장본으로 돌아간다.
  resp.manualImages = store.listManual(deviceId).map((m, i) => ({
    name: manualDeviceName(m, i),
    url: `${base.replace(/\/$/, '')}/manual/${m.id}/download`,
    size: m.size
  }));

  const invNames = new Set((Array.isArray(b.videos) ? b.videos : []).map(v => v && v.name));
  resp.thumbs = store.listMedia()
    .filter(m => m.thumb && invNames.has(m.original_name))
    .map(m => {
      let size = 0;
      try { size = fs.statSync(path.join(VIDEO_DIR, m.thumb)).size; } catch (e) { return null; }
      return { name: m.original_name, url: `${base.replace(/\/$/, '')}/media/${m.id}/thumb`, size };
    })
    .filter(Boolean);
  return res.json(resp);
});

// 헬스체크. 보고팡 백엔드 규약과 동일하게 GET /health 가 200 "ok" 를 반환한다
// (ECS 컨테이너 헬스체크: 30초 간격, timeout 5s, 재시도 3). 인증 없이 열어둔다.
// DB를 실제로 한 번 두드려본다 — 프로세스만 살아있고 저장소가 죽은 상태를
// "정상"으로 보고하면 헬스체크가 거짓말을 하게 된다.
app.get('/health', (req, res) => {
  try {
    store.db.prepare('SELECT 1').get();
    res.type('text').send('ok');
  } catch (e) {
    console.error('health check failed', e);
    res.status(503).type('text').send('db unavailable');
  }
});

// 매니페스트 단독 조회(디버그/수동 확인용)
app.get('/api/latest', (req, res) => res.json(manifestFor(req, null)));

// APK 배포(현재 활성 버전)
app.get('/download/app.apk', (req, res) => {
  const rel = store.getRelease();
  if (!rel) return res.status(404).send('no release');
  const p = path.join(APK_DIR, rel.filename);
  if (!fs.existsSync(p)) return res.status(404).send('apk missing');
  res.setHeader('Content-Type', 'application/vnd.android.package-archive');
  res.setHeader('Content-Disposition', `attachment; filename="app-${rel.version_code}.apk"`);
  res.sendFile(p);
});

// 영상 자료실 다운로드(기기가 체크인 응답의 pushVideos[].url 로 접근). 인증 없이 열어둔다
// — APK 다운로드와 동일한 성격(공개 파일 URL). res.sendFile 은 Range 헤더를 지원하므로
// 대용량 파일도 중간에 끊겨도 이어받기가 가능하다.
// HTTP 헤더는 ISO-8859-1 만 허용된다. 한글 파일명을 filename="..." 에 그대로 넣으면
// Node 가 ERR_INVALID_CHAR 를 던져 500 이 된다 — 실제로 이것 때문에 한글 이름 영상의
// push 가 전부 실패했다(구버전 앱은 진행률 보고가 없어 아무도 몰랐다). RFC 5987 의
// filename*=UTF-8'' 인코딩을 쓰고, 옛 클라이언트용 ASCII 폴백을 같이 싣는다.
function contentDisposition(name) {
  const fallback = name.replace(/[^\x20-\x7e]/g, '_').replace(/"/g, "'");
  return `attachment; filename="${fallback}"; filename*=UTF-8''${encodeURIComponent(name)}`;
}

app.get('/media/:id/download', (req, res) => {
  const media = store.getMedia(Number(req.params.id));
  if (!media) return res.status(404).send('media not found');
  const p = path.join(VIDEO_DIR, media.filename);
  if (!fs.existsSync(p)) return res.status(404).send('file missing');
  res.setHeader('Content-Type', 'video/mp4');
  res.setHeader('Content-Disposition', contentDisposition(media.original_name));
  res.sendFile(p);
});

// 앱 내장 기본 이용안내 미리보기. 파일명을 정규식으로 검증해 경로 조작을 막는다.
app.get('/manual/builtin/:name', (req, res) => {
  const name = String(req.params.name || '');
  if (!/^user_manual_\d+\.png$/.test(name)) return res.status(400).send('bad name');
  const p = path.join(BUILTIN_MANUAL_DIR, name);
  if (!fs.existsSync(p)) return res.status(404).send('not found');
  res.sendFile(p);
});

// 이용안내 이미지 제공(기기·대시보드 미리보기 공용). 인증 없이 연다.
app.get('/manual/:id/download', (req, res) => {
  const row = store.getManual(Number(req.params.id));
  if (!row) return res.status(404).send('manual image not found');
  const p = path.join(MANUAL_DIR, row.filename);
  if (!fs.existsSync(p)) return res.status(404).send('file missing');
  res.sendFile(p);
});

// 썸네일 제공. 영상 다운로드와 같은 이유로 인증 없이 연다(기기가 세션 없이 받아간다).
app.get('/media/:id/thumb', (req, res) => {
  const media = store.getMedia(Number(req.params.id));
  if (!media || !media.thumb) return res.status(404).send('thumb not found');
  const p = path.join(VIDEO_DIR, media.thumb);
  if (!fs.existsSync(p)) return res.status(404).send('file missing');
  res.sendFile(p);
});

// ---------- 백오피스 ----------

// 폼 POST 뒤에는 보고 있던 탭으로 돌려보낸다. 로그인처럼 referer 가 대시보드가 아니면 기본 탭.
function backToReferer(req, res) {
  const ref = req.get('referer');
  res.redirect(ref && ref.includes('/dashboard') ? ref : '/dashboard');
}

app.get('/login', (req, res) => res.type('html').send(views.loginPage(req.query.e ? '비밀번호가 올바르지 않습니다.' : null)));

app.post('/login', (req, res) => {
  if (auth.checkPassword(req.body.password)) {
    auth.setSessionCookie(res);
    return backToReferer(req, res);
  }
  return res.redirect('/login?e=1');
});

app.get('/logout', (req, res) => { auth.clearSessionCookie(res); res.redirect('/login'); });

app.get('/', (req, res) => res.redirect('/dashboard'));

app.get('/dashboard', auth.requireAuth, (req, res) => {
  // 폴더를 훑되 기다리지는 않는다. 1GB 짜리 해싱에 화면이 붙잡히면 안 된다 —
  // 새로 넣은 파일은 다음 새로고침에 뜬다(화면에도 그렇게 안내한다).
  syncMediaFolder();
  const devices = store.allDevices();
  // 배포 이력 페이지네이션 — 릴리스가 쌓이면 화면이 길어져 기기 목록이 밀린다.
  const REL_PER_PAGE = 10;
  const allReleases = store.listReleases();
  const relPages = Math.max(1, Math.ceil(allReleases.length / REL_PER_PAGE));
  const relPageNum = Math.min(relPages, Math.max(1, Number(req.query.relPage) || 1));
  const relPage = allReleases.slice((relPageNum - 1) * REL_PER_PAGE, relPageNum * REL_PER_PAGE);
  const release = store.getRelease();
  const now = Date.now();
  const distMap = new Map();
  let online = 0, offline = 0, onLatest = 0;
  for (const d of devices) {
    const age = now - d.last_seen;
    if (age <= onlineMsFor(d)) online++;
    if (age > STALE_MS) offline++;
    if (release && d.version_code === release.version_code) onLatest++;
    const key = `${d.version_code}`;
    if (!distMap.has(key)) distMap.set(key, { version_code: d.version_code, version_name: d.version_name, count: 0 });
    distMap.get(key).count++;
    // 영상 인벤토리(JSON 파싱) + 삭제/배포 대기 목록을 뷰에 넘긴다.
    try { d.videoList = d.videos ? JSON.parse(d.videos) : []; } catch (e) { d.videoList = []; }
    d.pendingDeletes = store.pendingVideoDeletes(d.device_id);
    d.pendingPushes = store.pendingVideoPushes(d.device_id);
    d.manualImages = store.listManual(d.device_id);
  }
  // "다른 기기에서 가져오기" 선택지 — 이용안내가 등록된 다른 기기들.
  for (const d of devices) {
    d.otherDevices = devices
      .filter(o => o.device_id !== d.device_id && o.manualImages.length > 0)
      .map(o => ({ device_id: o.device_id, app_label: o.app_label, count: o.manualImages.length }));
  }
  const versionDist = [...distMap.values()].sort((a, b) => (b.version_code || 0) - (a.version_code || 0));

  // 손볼 기기를 위로 올린다. DB는 last_seen DESC 라 정작 봐야 할 죽은 기기가 목록
  // 맨 아래로 가라앉았다 — 대수가 늘수록 스크롤 끝까지 가야 문제를 발견하게 된다.
  const rank = d => {
    if (now - d.last_seen > STALE_MS) return 0;                                  // 오프라인
    if (release && d.version_code !== release.version_code) return 1;            // 업데이트 필요
    return 2;                                                                     // 정상
  };
  devices.sort((a, b) => {
    const ra = rank(a), rb = rank(b);
    if (ra !== rb) return ra - rb;
    // 오프라인끼리는 오래 끊긴 순, 나머지는 최근 접속 순
    return ra === 0 ? a.last_seen - b.last_seen : b.last_seen - a.last_seen;
  });

  res.type('html').send(views.dashboardPage({
    tab: String(req.query.tab || 'devices'),
    devices, release,
    releases: relPage,
    relPaging: { page: relPageNum, pages: relPages, total: allReleases.length },
    media: store.listMedia(),
    mediaDir: VIDEO_DIR,
    builtinManual: builtinManualFiles(),
    stats: { total: devices.length, online, offline, onLatest, versionDist },
    thresholds: { onlineMsFor, staleMs: STALE_MS }
  }));
});

app.post('/release/upload', auth.requireAuth, uploadApk.single('apk'), async (req, res) => {
  try {
    if (!req.file) return res.status(400).send('APK 파일이 필요합니다.');
    const versionCode = Number(req.body.versionCode);
    const versionName = String(req.body.versionName || '').trim();
    if (!Number.isInteger(versionCode) || versionCode < 1 || !versionName) {
      fs.unlinkSync(req.file.path);
      return res.status(400).send('versionCode(정수)와 versionName 을 올바르게 입력하세요.');
    }
    const notes = String(req.body.notes || '').slice(0, 500);
    // U+FFFD(�)가 섞였다는 것은 UTF-8이 아닌 바이트가 왔다는 뜻 — 저장해봐야 복구 불가능한
    // 깨진 글자만 남는다(실제로 2.0.2 메모가 그렇게 깨졌다). Windows 콘솔의 curl 이 한글을
    // CP949로 보내는 게 전형적 원인. 조용히 저장하지 말고 업로드 자체를 거부한다.
    if (notes.includes('�')) {
      fs.unlinkSync(req.file.path);
      return res.status(400).send('릴리스 메모의 한글이 깨져서 도착했습니다(� 포함). 대시보드 폼에서 업로드하면 안 깨집니다. (Windows 콘솔 curl 은 한글을 CP949로 보내 이렇게 됩니다)');
    }
    const sha = await sha256File(req.file.path);
    const size = fs.statSync(req.file.path).size;
    // 파일명은 이력 행의 id로 고유하게 정한다 — 예전엔 versionCode로만 정해서, 같은
    // versionCode를 재업로드하면 이전 파일이 덮어써져 이력/롤백이 무의미해졌었다.
    const id = store.insertRelease({
      version_code: versionCode,
      version_name: versionName,
      filename: 'pending', // 아래에서 실제 파일명으로 갱신
      sha256: sha,
      size,
      notes,
      uploaded_at: Date.now()
    });
    const filename = `app-${id}.apk`;
    fs.renameSync(req.file.path, path.join(APK_DIR, filename));
    store.setReleaseFilename(id, filename);
    backToReferer(req, res);
  } catch (e) {
    console.error('upload error', e);
    res.status(500).send('업로드 처리 중 오류: ' + e.message);
  }
});

// 배포 이력 중 하나로 롤백(재배포). 파일이 이미 디스크에 있으므로 재업로드가 필요 없다.
app.post('/release/rollback', auth.requireAuth, (req, res) => {
  const id = Number(req.body.id);
  const rel = store.getReleaseById(id);
  if (!rel) return res.status(404).send('해당 이력을 찾을 수 없습니다.');
  if (!fs.existsSync(path.join(APK_DIR, rel.filename))) {
    return res.status(410).send('이 버전의 APK 파일이 디스크에 없습니다(오래되어 정리됐을 수 있음).');
  }
  store.activateRelease(id);
  backToReferer(req, res);
});

// 업데이트가 안 된 기기 전체에 "업데이트 하시겠어요?" 확인창 지시를 건다.
app.post('/release/notify-outdated', auth.requireAuth, (req, res) => {
  store.requestUpdatePromptForOutdated();
  backToReferer(req, res);
});

// 기기 하나에만 확인창 지시를 건다.
app.post('/device/update-prompt', auth.requireAuth, (req, res) => {
  if (req.body.deviceId) {
    store.requestUpdatePrompt(String(req.body.deviceId));
    wakeDevice(String(req.body.deviceId));   // 접속 중이면 확인창이 몇 초 안에 뜬다
  }
  backToReferer(req, res);
});

// 즉시 업데이트(강제): 사용(재생) 중이어도 바로 설치. 관리자가 확인창을 거쳐 선택한다.
app.post('/device/update-force', auth.requireAuth, (req, res) => {
  if (req.body.deviceId) {
    store.requestForceUpdate(String(req.body.deviceId));
    wakeDevice(String(req.body.deviceId));
  }
  backToReferer(req, res);
});

app.post('/device/label', auth.requireAuth, (req, res) => {
  if (req.body.deviceId) store.setLabel(req.body.deviceId, String(req.body.label || '').slice(0, 100));
  backToReferer(req, res);
});

// 기기별 문의 연락처 지정. 빈 값으로 저장하면 지정 해제(앱 기본값 유지).
app.post('/device/contact', auth.requireAuth, (req, res) => {
  if (req.body.deviceId) {
    store.setContactOverride(String(req.body.deviceId), String(req.body.contact || '').slice(0, 100));
  }
  backToReferer(req, res);
});

// 관리자 PIN 원격 초기화(0000). 현장에서 PIN을 잊었을 때의 유일한 복구 수단이라,
// 지시가 실제로 먹혔는지는 기기가 보고하는 hasCustomPin 으로 확인한다.
app.post('/device/pin-reset', auth.requireAuth, (req, res) => {
  if (req.body.deviceId) store.requestPinReset(String(req.body.deviceId));
  backToReferer(req, res);
});

app.post('/device/delete', auth.requireAuth, (req, res) => {
  if (req.body.deviceId) {
    // DB 행보다 파일을 먼저 지운다 — 순서가 반대면 경로를 잃어 고아 파일이 남는다.
    for (const m of store.listManual(String(req.body.deviceId))) {
      try { fs.unlinkSync(path.join(MANUAL_DIR, m.filename)); } catch (e) { /* 이미 없을 수 있음 */ }
    }
    store.deleteDevice(req.body.deviceId);
  }
  backToReferer(req, res);
});

// ---------- 기기별 이용안내 이미지 ----------
//
// 한 기기의 행 전체가 그 기기의 세트다. 관리자가 이 세트를 편집하면 기기는 다음 접속 때
// 자기 폴더를 세트와 똑같이 맞춘다(없으면 받고, 빠진 건 지운다). 그래서 "전체 교체"와
// "낱장 교체"가 같은 동작이 된다 — 세트를 비우면 앱 내장 이미지로 돌아간다.

app.post('/device/manual/upload', auth.requireAuth, uploadManual.array('images', 40), async (req, res) => {
  const deviceId = String(req.body.deviceId || '');
  const files = req.files || [];
  if (!deviceId || files.length === 0) {
    files.forEach(f => { try { fs.unlinkSync(f.path); } catch (e) {} });
    return res.status(400).send('기기와 이미지 파일이 필요합니다.');
  }
  try {
    // 여러 장을 한 번에 고르면 브라우저가 순서를 보장하지 않는다 — 파일명으로 정렬해
    // 관리자가 의도한 순서(01, 02, ...)를 살린다. 순서는 나중에 화면에서 조정할 수 있다.
    files.sort((a, b) => String(a.originalname).localeCompare(String(b.originalname), 'ko', { numeric: true }));
    for (const f of files) {
      const sha = await sha256File(f.path);
      const original = path.basename(String(f.originalname || 'image.png'));
      const id = store.appendManual({
        device_id: deviceId,
        filename: 'pending',
        original_name: original.slice(0, 200),
        size: fs.statSync(f.path).size,
        sha256: sha,
        uploaded_at: Date.now()
      });
      const stored = `manual-${id}${(path.extname(original) || '.png').toLowerCase()}`;
      fs.renameSync(f.path, path.join(MANUAL_DIR, stored));
      store.db.prepare('UPDATE manual_images SET filename = ? WHERE id = ?').run(stored, id);
    }
    wakeDevice(deviceId);
    backToReferer(req, res);
  } catch (e) {
    console.error('manual upload error', e);
    files.forEach(f => { try { fs.unlinkSync(f.path); } catch (_) {} });
    res.status(500).send('이용안내 이미지 처리 중 오류: ' + e.message);
  }
});

app.post('/device/manual/delete', auth.requireAuth, (req, res) => {
  const row = store.getManual(Number(req.body.id));
  if (row) {
    try { fs.unlinkSync(path.join(MANUAL_DIR, row.filename)); } catch (e) { /* */ }
    store.deleteManual(row.id);
    wakeDevice(row.device_id);   // 폼에는 id 만 오므로 기기는 행에서 역추적
  }
  backToReferer(req, res);
});

app.post('/device/manual/move', auth.requireAuth, (req, res) => {
  const row = store.getManual(Number(req.body.id));
  store.moveManual(Number(req.body.id), req.body.dir === 'up' ? -1 : 1);
  if (row) wakeDevice(row.device_id);
  backToReferer(req, res);
});

// 세트를 통째로 비운다 = 앱 내장 이용안내로 복귀.
app.post('/device/manual/clear', auth.requireAuth, (req, res) => {
  const deviceId = String(req.body.deviceId || '');
  for (const m of store.listManual(deviceId)) {
    try { fs.unlinkSync(path.join(MANUAL_DIR, m.filename)); } catch (e) { /* */ }
    store.deleteManual(m.id);
  }
  if (deviceId) wakeDevice(deviceId);
  backToReferer(req, res);
});

// 다른 기기의 세트를 그대로 복사해온다. 도서관마다 같은 안내를 쓸 때 매번 다시 올리지
// 않게 하는 용도 — 파일도 함께 복제해서 원본 기기의 세트를 지워도 영향받지 않는다.
app.post('/device/manual/copy', auth.requireAuth, (req, res) => {
  const to = String(req.body.deviceId || '');
  const from = String(req.body.fromDeviceId || '');
  if (!to || !from || to === from) return backToReferer(req, res);
  for (const m of store.listManual(to)) {
    try { fs.unlinkSync(path.join(MANUAL_DIR, m.filename)); } catch (e) { /* */ }
    store.deleteManual(m.id);
  }
  for (const m of store.listManual(from)) {
    const id = store.appendManual({
      device_id: to,
      filename: 'pending',
      original_name: m.original_name,
      size: m.size,
      sha256: m.sha256,
      uploaded_at: Date.now()
    });
    const stored = `manual-${id}${(path.extname(m.filename) || '.png').toLowerCase()}`;
    fs.copyFileSync(path.join(MANUAL_DIR, m.filename), path.join(MANUAL_DIR, stored));
    store.db.prepare('UPDATE manual_images SET filename = ? WHERE id = ?').run(stored, id);
  }
  wakeDevice(to);
  backToReferer(req, res);
});

// 원격 영상 삭제 지시 큐잉(다음 체크인 때 기기가 삭제).
app.post('/device/video/delete', auth.requireAuth, (req, res) => {
  const { deviceId, filename } = req.body;
  if (deviceId && filename) {
    store.queueVideoDelete(String(deviceId), String(filename));
    wakeDevice(String(deviceId));
  }
  backToReferer(req, res);
});

// ---------- 영상 자료실 ----------

app.post('/media/upload', auth.requireAuth, uploadVideo.single('video'), async (req, res) => {
  try {
    if (!req.file) return res.status(400).send('영상 파일이 필요합니다.');
    const originalName = sanitizeVideoName(req.file.originalname);
    if (!originalName) {
      fs.unlinkSync(req.file.path);
      return res.status(400).send('지원하지 않는 파일명/형식입니다. (mp4/m4v/mkv/webm)');
    }
    const sha = await sha256File(req.file.path);
    const size = fs.statSync(req.file.path).size;
    const id = store.insertMedia({
      filename: 'pending',
      original_name: originalName,
      size,
      sha256: sha,
      uploaded_at: Date.now()
    });
    const filename = `media-${id}${path.extname(originalName)}`;
    fs.renameSync(req.file.path, path.join(VIDEO_DIR, filename));
    store.setMediaFilename(id, filename);
    backToReferer(req, res);
  } catch (e) {
    console.error('media upload error', e);
    res.status(500).send('업로드 처리 중 오류: ' + e.message);
  }
});

// 자료실의 영상을 특정 기기에 배포(다음 체크인 때 기기가 내려받는다).
// 모달에서 체크박스로 여러 개를 고르므로 mediaId 는 1개(문자열)일 수도, 배열일 수도 있다.
app.post('/media/push', auth.requireAuth, (req, res) => {
  const deviceId = String(req.body.deviceId || '');
  const mode = req.body.mode === 'ask' ? 'ask' : 'force';
  const ids = [].concat(req.body.mediaId || [])
    .map(Number)
    .filter(id => store.getMedia(id));   // 자료실에서 지워진 id 가 섞여 와도 조용히 걸러낸다
  if (!deviceId || ids.length === 0) return res.status(400).send('잘못된 요청입니다.');
  for (const id of ids) store.queueVideoPush(deviceId, id, mode);
  wakeDevice(deviceId);   // 접속 중이면 몇 초 안에 받기 시작(또는 확인창 표시)
  backToReferer(req, res);
});

// 아직 다운로드가 시작되지 않은 배포 지시를 취소.
app.post('/media/push/cancel', auth.requireAuth, (req, res) => {
  const mediaId = Number(req.body.mediaId);
  const deviceId = String(req.body.deviceId || '');
  if (deviceId && mediaId) store.clearVideoPush(deviceId, mediaId);
  backToReferer(req, res);
});

app.post('/media/delete', auth.requireAuth, (req, res) => {
  const id = Number(req.body.id);
  const media = store.getMedia(id);
  if (media) {
    try { fs.unlinkSync(path.join(VIDEO_DIR, media.filename)); } catch (e) { /* 이미 없을 수 있음 */ }
    if (media.thumb) { try { fs.unlinkSync(path.join(VIDEO_DIR, media.thumb)); } catch (e) { /* */ } }
    store.deleteMedia(id);
  }
  backToReferer(req, res);
});

// 썸네일 등록/교체. 어떤 이미지 포맷이든 받아 `<영상파일명>.jpg` 로 저장한다(§uploadThumb 주석).
app.post('/media/thumb', auth.requireAuth, uploadThumb.single('thumb'), (req, res) => {
  const media = store.getMedia(Number(req.body.mediaId));
  if (!req.file) return res.status(400).send('이미지 파일이 필요합니다.');
  if (!media) { fs.unlinkSync(req.file.path); return res.status(404).send('영상을 찾을 수 없습니다.'); }
  const thumbName = `${media.filename}.jpg`;
  fs.renameSync(req.file.path, path.join(VIDEO_DIR, thumbName)); // 교체면 그대로 덮어쓴다
  store.setMediaThumb(media.id, thumbName);
  // 썸네일은 그 영상을 보유한 기기에만 내려간다 — 그 기기들을 깨워 몇 초 안에 반영시킨다.
  // (다음 체크인까지 최대 10분 안 보이던 것이 사용자 입장에선 "적용 안 됨"이었다)
  for (const d of store.allDevices()) {
    try {
      const owned = JSON.parse(d.videos || '[]');
      if (owned.some(v => v && v.name === media.original_name)) wakeDevice(d.device_id);
    } catch (e) { /* videos JSON 이 깨진 행은 건너뜀 */ }
  }
  backToReferer(req, res);
});

const server = app.listen(PORT, () => {
  console.log(`[두비덥 함대관리] http://localhost:${PORT}  (대시보드: /dashboard)`);
  if (!process.env.ADMIN_PASSWORD) console.log('  ℹ ADMIN_PASSWORD 미설정 — data/admin-password.txt 의 값을 사용합니다.');
  if (!process.env.SESSION_SECRET) console.log('  ⚠ SESSION_SECRET 미설정 — 재시작 시 로그인 세션이 만료됩니다.');
  console.log(`  영상 저장소: ${VIDEO_DIR}  (이 폴더에 넣으면 자료실에 자동 등록)`);
  syncMediaFolder();
});

// 종료 신호를 받으면 처리 중인 요청을 끝내고 SQLite 를 정상 종료한다.
// 로컬(관리자 PC 재부팅·서비스 재시작)에서는 WAL 파일 손상을 막고,
// 나중에 ECS 롤링 업데이트로 옮겼을 때는 SIGTERM 후 강제 종료되기 전에
// 진행 중인 체크인/APK 다운로드가 끊기지 않게 한다.
let shuttingDown = false;
function shutdown(signal) {
  if (shuttingDown) return;
  shuttingDown = true;
  console.log(`[두비덥 함대관리] ${signal} 수신 — 종료 중...`);
  // long-poll 대기 연결을 먼저 놓아준다 — 붙잡은 채로는 server.close 가 최대 50초를
  // 기다리다 강제 종료 타이머(10초)에 걸린다.
  for (const [id, w] of pokeWaiters) {
    clearTimeout(w.timer);
    try { w.res.json({ checkinNow: false }); } catch (e) { /* */ }
  }
  pokeWaiters.clear();
  server.close(() => {
    try { store.db.close(); } catch (e) { console.error('db close 실패', e); }
    console.log('[두비덥 함대관리] 정상 종료');
    process.exit(0);
  });
  // 열린 연결이 안 닫혀도 무한 대기하지 않는다.
  setTimeout(() => {
    console.error('[두비덥 함대관리] 정상 종료 시간 초과 — 강제 종료');
    process.exit(1);
  }, 10000).unref();
}
for (const sig of ['SIGTERM', 'SIGINT']) process.on(sig, () => shutdown(sig));
