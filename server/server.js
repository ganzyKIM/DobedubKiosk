'use strict';
// 두비덥 키오스크 함대 관리 서버
//   - 기기 체크인 수집(POST /api/checkin) + 응답에 최신 버전 매니페스트 포함
//   - APK 배포(GET /download/app.apk), 매니페스트(GET /api/latest)
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
const APK_DIR = path.join(store.DATA_DIR, 'apk');
fs.mkdirSync(APK_DIR, { recursive: true });

const app = express();
app.set('trust proxy', true); // 리버스 프록시(nginx/caddy) 뒤에서 실제 IP/프로토콜 인식
app.use(express.json({ limit: '1mb' }));
app.use(express.urlencoded({ extended: false }));

const upload = multer({
  storage: multer.diskStorage({
    destination: (req, file, cb) => cb(null, APK_DIR),
    filename: (req, file, cb) => cb(null, `upload-${Date.now()}.apk.tmp`)
  }),
  limits: { fileSize: 300 * 1024 * 1024 } // 300MB
});

function sha256File(p) {
  return new Promise((resolve, reject) => {
    const h = crypto.createHash('sha256');
    fs.createReadStream(p).on('data', d => h.update(d)).on('end', () => resolve(h.digest('hex'))).on('error', reject);
  });
}

function manifestFor(req) {
  const rel = store.getRelease();
  if (!rel) return { update: false };
  const base = process.env.BASE_URL || `${req.protocol}://${req.get('host')}`;
  return {
    update: true,
    versionCode: rel.version_code,
    versionName: rel.version_name,
    apkPath: '/download/app.apk',
    apkUrl: `${base.replace(/\/$/, '')}/download/app.apk`,
    sha256: rel.sha256,
    size: rel.size,
    notes: rel.notes || ''
  };
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
  try {
    store.recordCheckin({
      deviceId: b.deviceId.slice(0, 128),
      model: b.model, serial: b.serial,
      versionCode: Number(b.versionCode),
      versionName: b.versionName,
      battery: Number(b.battery),
      kioskLocked: !!b.kioskLocked,
      startUrl: b.startUrl,
      appLabel: b.appLabel,
      ip: req.ip
    });
  } catch (e) {
    console.error('checkin error', e);
    return res.status(500).json({ error: 'server error' });
  }
  return res.json(manifestFor(req));
});

// 매니페스트 단독 조회(디버그/수동 확인용)
app.get('/api/latest', (req, res) => res.json(manifestFor(req)));

// APK 배포
app.get('/download/app.apk', (req, res) => {
  const rel = store.getRelease();
  if (!rel) return res.status(404).send('no release');
  const p = path.join(APK_DIR, rel.filename);
  if (!fs.existsSync(p)) return res.status(404).send('apk missing');
  res.setHeader('Content-Type', 'application/vnd.android.package-archive');
  res.setHeader('Content-Disposition', `attachment; filename="app-${rel.version_code}.apk"`);
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
  const ONLINE = 15 * 60 * 1000, STALE = 24 * 60 * 60 * 1000;
  const distMap = new Map();
  let online = 0, offline = 0, onLatest = 0;
  for (const d of devices) {
    const age = now - d.last_seen;
    if (age <= ONLINE) online++;
    if (age > STALE) offline++;
    if (release && d.version_code === release.version_code) onLatest++;
    const key = `${d.version_code}`;
    if (!distMap.has(key)) distMap.set(key, { version_code: d.version_code, version_name: d.version_name, count: 0 });
    distMap.get(key).count++;
  }
  const versionDist = [...distMap.values()].sort((a, b) => (b.version_code || 0) - (a.version_code || 0));
  res.type('html').send(views.dashboardPage({
    devices, release,
    stats: { total: devices.length, online, offline, onLatest, versionDist }
  }));
});

app.post('/release/upload', auth.requireAuth, upload.single('apk'), async (req, res) => {
  try {
    if (!req.file) return res.status(400).send('APK 파일이 필요합니다.');
    const versionCode = Number(req.body.versionCode);
    const versionName = String(req.body.versionName || '').trim();
    if (!Number.isInteger(versionCode) || versionCode < 1 || !versionName) {
      fs.unlinkSync(req.file.path);
      return res.status(400).send('versionCode(정수)와 versionName 을 올바르게 입력하세요.');
    }
    const sha = await sha256File(req.file.path);
    const filename = `app-${versionCode}.apk`;
    const dest = path.join(APK_DIR, filename);
    fs.renameSync(req.file.path, dest);
    store.setRelease({
      version_code: versionCode,
      version_name: versionName,
      filename,
      sha256: sha,
      size: fs.statSync(dest).size,
      notes: String(req.body.notes || '').slice(0, 500),
      uploaded_at: Date.now()
    });
    res.redirect('/dashboard');
  } catch (e) {
    console.error('upload error', e);
    res.status(500).send('업로드 처리 중 오류: ' + e.message);
  }
});

app.post('/device/label', auth.requireAuth, (req, res) => {
  if (req.body.deviceId) store.setLabel(req.body.deviceId, String(req.body.label || '').slice(0, 100));
  res.redirect('/dashboard');
});

app.post('/device/delete', auth.requireAuth, (req, res) => {
  if (req.body.deviceId) store.deleteDevice(req.body.deviceId);
  res.redirect('/dashboard');
});

app.listen(PORT, () => {
  console.log(`[두비덥 함대관리] http://localhost:${PORT}  (대시보드: /dashboard)`);
  if (!process.env.ADMIN_PASSWORD) console.log('  ⚠ ADMIN_PASSWORD 미설정 — 기본값 "dobedub" 사용 중. 운영 시 반드시 설정하세요.');
  if (!process.env.SESSION_SECRET) console.log('  ⚠ SESSION_SECRET 미설정 — 재시작 시 로그인 세션이 만료됩니다.');
});
