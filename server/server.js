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

// 기기 체크인 주기 — 앱의 MainActivity.UPDATE_CHECK_INTERVAL_MS 와 반드시 같은 값.
// 온라인/오프라인 임계값은 반드시 이 값에서 파생시킬 것. 예전엔 앱은 6시간 주기인데
// 대시보드는 "15분 내 = 온라인"으로 따로 박혀 있어, 멀쩡한 기기도 96% 시간 동안
// 노란 "대기"로 표시되고 온라인 KPI가 항상 0이었다.
const CHECKIN_INTERVAL_MS = 30 * 60 * 1000;
// 두 주기를 연속으로 놓치면 이상 신호로 본다(+네트워크 지연 여유 10분).
const ONLINE_MS = CHECKIN_INTERVAL_MS * 2 + 10 * 60 * 1000;
const STALE_MS = 24 * 60 * 60 * 1000;
const APK_DIR = path.join(store.DATA_DIR, 'apk');
const VIDEO_DIR = path.join(store.DATA_DIR, 'videos');
fs.mkdirSync(APK_DIR, { recursive: true });
fs.mkdirSync(VIDEO_DIR, { recursive: true });

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
      ip: req.ip
    });
  } catch (e) {
    console.error('checkin error', e);
    return res.status(500).json({ error: 'server error' });
  }
  // 매니페스트(업데이트) + 이 기기에 대한 영상 삭제/배포 지시를 함께 응답.
  const deviceRow = store.allDevices().find(d => d.device_id === deviceId) || null;
  const resp = manifestFor(req, deviceRow);
  resp.deleteVideos = store.pendingVideoDeletes(deviceId);
  const base = process.env.BASE_URL || `${req.protocol}://${req.get('host')}`;
  resp.pushVideos = store.pendingVideoPushes(deviceId).map(p => ({
    name: p.original_name,
    url: `${base.replace(/\/$/, '')}/media/${p.media_id}/download`,
    sha256: p.sha256,
    size: p.size
  }));
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
app.get('/media/:id/download', (req, res) => {
  const media = store.getMedia(Number(req.params.id));
  if (!media) return res.status(404).send('media not found');
  const p = path.join(VIDEO_DIR, media.filename);
  if (!fs.existsSync(p)) return res.status(404).send('file missing');
  res.setHeader('Content-Type', 'video/mp4');
  res.setHeader('Content-Disposition', `attachment; filename="${media.original_name}"`);
  res.sendFile(p);
});

// ---------- 백오피스 ----------

app.get('/login', (req, res) => res.type('html').send(views.loginPage(req.query.e ? '비밀번호가 올바르지 않습니다.' : null)));

app.post('/login', (req, res) => {
  if (auth.checkPassword(req.body.password)) {
    auth.setSessionCookie(res);
    return res.redirect('/dashboard');
  }
  return res.redirect('/login?e=1');
});

app.get('/logout', (req, res) => { auth.clearSessionCookie(res); res.redirect('/login'); });

app.get('/', (req, res) => res.redirect('/dashboard'));

app.get('/dashboard', auth.requireAuth, (req, res) => {
  const devices = store.allDevices();
  const release = store.getRelease();
  const now = Date.now();
  const distMap = new Map();
  let online = 0, offline = 0, onLatest = 0;
  for (const d of devices) {
    const age = now - d.last_seen;
    if (age <= ONLINE_MS) online++;
    if (age > STALE_MS) offline++;
    if (release && d.version_code === release.version_code) onLatest++;
    const key = `${d.version_code}`;
    if (!distMap.has(key)) distMap.set(key, { version_code: d.version_code, version_name: d.version_name, count: 0 });
    distMap.get(key).count++;
    // 영상 인벤토리(JSON 파싱) + 삭제/배포 대기 목록을 뷰에 넘긴다.
    try { d.videoList = d.videos ? JSON.parse(d.videos) : []; } catch (e) { d.videoList = []; }
    d.pendingDeletes = store.pendingVideoDeletes(d.device_id);
    d.pendingPushes = store.pendingVideoPushes(d.device_id);
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
    devices, release,
    releases: store.listReleases(),
    media: store.listMedia(),
    stats: { total: devices.length, online, offline, onLatest, versionDist },
    thresholds: { onlineMs: ONLINE_MS, staleMs: STALE_MS }
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
      notes: String(req.body.notes || '').slice(0, 500),
      uploaded_at: Date.now()
    });
    const filename = `app-${id}.apk`;
    fs.renameSync(req.file.path, path.join(APK_DIR, filename));
    store.setReleaseFilename(id, filename);
    res.redirect('/dashboard');
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
  res.redirect('/dashboard');
});

// 업데이트가 안 된 기기 전체에 "업데이트 하시겠어요?" 확인창 지시를 건다.
app.post('/release/notify-outdated', auth.requireAuth, (req, res) => {
  store.requestUpdatePromptForOutdated();
  res.redirect('/dashboard');
});

// 기기 하나에만 확인창 지시를 건다.
app.post('/device/update-prompt', auth.requireAuth, (req, res) => {
  if (req.body.deviceId) store.requestUpdatePrompt(String(req.body.deviceId));
  res.redirect('/dashboard');
});

app.post('/device/label', auth.requireAuth, (req, res) => {
  if (req.body.deviceId) store.setLabel(req.body.deviceId, String(req.body.label || '').slice(0, 100));
  res.redirect('/dashboard');
});

app.post('/device/delete', auth.requireAuth, (req, res) => {
  if (req.body.deviceId) store.deleteDevice(req.body.deviceId);
  res.redirect('/dashboard');
});

// 원격 영상 삭제 지시 큐잉(다음 체크인 때 기기가 삭제).
app.post('/device/video/delete', auth.requireAuth, (req, res) => {
  const { deviceId, filename } = req.body;
  if (deviceId && filename) store.queueVideoDelete(String(deviceId), String(filename));
  res.redirect('/dashboard');
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
    res.redirect('/dashboard');
  } catch (e) {
    console.error('media upload error', e);
    res.status(500).send('업로드 처리 중 오류: ' + e.message);
  }
});

// 자료실의 영상을 특정 기기에 배포(다음 체크인 때 기기가 내려받는다).
app.post('/media/push', auth.requireAuth, (req, res) => {
  const mediaId = Number(req.body.mediaId);
  const deviceId = String(req.body.deviceId || '');
  if (!deviceId || !store.getMedia(mediaId)) return res.status(400).send('잘못된 요청입니다.');
  store.queueVideoPush(deviceId, mediaId);
  res.redirect('/dashboard');
});

// 아직 다운로드가 시작되지 않은 배포 지시를 취소.
app.post('/media/push/cancel', auth.requireAuth, (req, res) => {
  const mediaId = Number(req.body.mediaId);
  const deviceId = String(req.body.deviceId || '');
  if (deviceId && mediaId) store.clearVideoPush(deviceId, mediaId);
  res.redirect('/dashboard');
});

app.post('/media/delete', auth.requireAuth, (req, res) => {
  const id = Number(req.body.id);
  const media = store.getMedia(id);
  if (media) {
    try { fs.unlinkSync(path.join(VIDEO_DIR, media.filename)); } catch (e) { /* 이미 없을 수 있음 */ }
    store.deleteMedia(id);
  }
  res.redirect('/dashboard');
});

const server = app.listen(PORT, () => {
  console.log(`[두비덥 함대관리] http://localhost:${PORT}  (대시보드: /dashboard)`);
  if (!process.env.ADMIN_PASSWORD) console.log('  ⚠ ADMIN_PASSWORD 미설정 — 기본값 "dobedub" 사용 중. 운영 시 반드시 설정하세요.');
  if (!process.env.SESSION_SECRET) console.log('  ⚠ SESSION_SECRET 미설정 — 재시작 시 로그인 세션이 만료됩니다.');
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
