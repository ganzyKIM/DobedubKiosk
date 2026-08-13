'use strict';
// 백오피스 접근 인증 — 공유 비밀번호 1개 + HMAC 서명 세션 쿠키.
// 별도 계정/세션 저장소 없이, 서버 SECRET 으로 서명한 만료시각 쿠키로 로그인 상태를 유지한다.

const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const COOKIE_NAME = 'kiosk_admin';
const SESSION_MS = 12 * 60 * 60 * 1000; // 12시간

// 비밀번호 보관 위치. data/ 는 .gitignore 대상이라 공개 저장소에 올라가지 않는다.
// db.js 를 require 하면 순환 참조가 되므로 경로 규칙만 그대로 맞춘다.
const DATA_DIR = process.env.DATA_DIR || path.join(__dirname, 'data');
const PASSWORD_FILE = path.join(DATA_DIR, 'admin-password.txt');

function getSecret() {
  // SESSION_SECRET 미설정 시 임의 생성(재시작하면 기존 세션 무효화됨 — 소규모 운영엔 무방).
  return process.env.SESSION_SECRET || (getSecret._v || (getSecret._v = crypto.randomBytes(32).toString('hex')));
}

function sign(value) {
  return crypto.createHmac('sha256', getSecret()).update(value).digest('base64url');
}

// 타이밍 안전 비교
function safeEqual(a, b) {
  const ba = Buffer.from(String(a));
  const bb = Buffer.from(String(b));
  if (ba.length !== bb.length) return false;
  return crypto.timingSafeEqual(ba, bb);
}

function makeToken() {
  const exp = Date.now() + SESSION_MS;
  const payload = `${exp}`;
  return `${payload}.${sign(payload)}`;
}

function verifyToken(token) {
  if (!token || typeof token !== 'string') return false;
  const dot = token.lastIndexOf('.');
  if (dot < 0) return false;
  const payload = token.slice(0, dot);
  const sig = token.slice(dot + 1);
  if (!safeEqual(sig, sign(payload))) return false;
  const exp = Number(payload);
  return Number.isFinite(exp) && Date.now() < exp;
}

/**
 * 관리자 비밀번호. **소스에 기본값을 두지 않는다** — 이 저장소는 공개라, 코드에 적는 순간
 * 누구나 읽을 수 있다(실제로 예전 기본값이 그렇게 노출됐고, 깃 이력에 영구히 남아
 * 그 값은 더 못 쓴다).
 *
 * 우선순위: 환경변수 → data/admin-password.txt → (없으면) 임의 생성 후 그 파일에 저장.
 * 마지막 경로 덕분에 아무 설정 없이 `node server.js` 만 해도 뜨고, 그때 콘솔에 비밀번호를
 * 한 번 찍어준다. 추측 가능한 값이 코드에 남는 일은 없다.
 */
function adminPassword() {
  if (adminPassword._v) return adminPassword._v;
  if (process.env.ADMIN_PASSWORD) return (adminPassword._v = process.env.ADMIN_PASSWORD);
  try {
    const saved = fs.readFileSync(PASSWORD_FILE, 'utf8').trim();
    if (saved) return (adminPassword._v = saved);
  } catch (e) { /* 아직 없음 — 아래에서 만든다 */ }
  const generated = crypto.randomBytes(9).toString('base64url');
  try {
    fs.mkdirSync(DATA_DIR, { recursive: true });
    fs.writeFileSync(PASSWORD_FILE, generated + '\n', { mode: 0o600 });
    console.log(`\n  ⚠ 관리자 비밀번호를 새로 만들었습니다: ${generated}`);
    console.log(`     저장 위치: ${PASSWORD_FILE}  (깃 추적 안 됨)\n`);
  } catch (e) {
    console.error('  ⚠ 비밀번호 파일을 쓰지 못했습니다. 재시작하면 비밀번호가 바뀝니다:', e.message);
  }
  return (adminPassword._v = generated);
}

function checkPassword(input) {
  return safeEqual(input || '', adminPassword());
}

// 쿠키 파서(작은 의존성 회피)
function parseCookies(req) {
  const header = req.headers.cookie || '';
  const out = {};
  header.split(';').forEach(part => {
    const i = part.indexOf('=');
    if (i > -1) out[part.slice(0, i).trim()] = decodeURIComponent(part.slice(i + 1).trim());
  });
  return out;
}

// 로그인 필요 라우트 보호 미들웨어
function requireAuth(req, res, next) {
  const token = parseCookies(req)[COOKIE_NAME];
  if (verifyToken(token)) return next();
  return res.redirect('/login');
}

function setSessionCookie(res) {
  const token = makeToken();
  const secure = process.env.COOKIE_SECURE === '1' ? '; Secure' : '';
  res.setHeader('Set-Cookie',
    `${COOKIE_NAME}=${encodeURIComponent(token)}; HttpOnly; Path=/; SameSite=Lax; Max-Age=${SESSION_MS / 1000}${secure}`);
}

function clearSessionCookie(res) {
  res.setHeader('Set-Cookie', `${COOKIE_NAME}=; HttpOnly; Path=/; SameSite=Lax; Max-Age=0`);
}

module.exports = { requireAuth, checkPassword, setSessionCookie, clearSessionCookie, COOKIE_NAME };
