'use strict';
// 백오피스 접근 인증 — 공유 비밀번호 1개 + HMAC 서명 세션 쿠키.
// 별도 계정/세션 저장소 없이, 서버 SECRET 으로 서명한 만료시각 쿠키로 로그인 상태를 유지한다.

const crypto = require('crypto');

const COOKIE_NAME = 'kiosk_admin';
const SESSION_MS = 12 * 60 * 60 * 1000; // 12시간

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

function checkPassword(input) {
  const expected = process.env.ADMIN_PASSWORD || 'dobedub';
  return safeEqual(input || '', expected);
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
