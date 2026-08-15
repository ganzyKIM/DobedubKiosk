'use strict';
// SQLite 저장소 — 기기 인벤토리 + 배포 버전 이력 + 영상 자료실/배포 큐.
// better-sqlite3 는 동기 API라 소규모 트래픽에 단순하고 안정적이다.

const path = require('path');
const fs = require('fs');
const Database = require('better-sqlite3');

// 데이터 위치는 env 로 덮어쓸 수 있어야 한다. 로컬은 server/data 로 충분하지만,
// 컨테이너(ECS)로 옮기면 컨테이너 파일시스템은 재배포마다 초기화되므로 반드시 외부
// 볼륨이나 마운트 경로를 가리켜야 한다. 자세한 이전 절차는 README "보고팡 인프라 이전" 참조.
const DATA_DIR = process.env.DATA_DIR || path.join(__dirname, 'data');
fs.mkdirSync(DATA_DIR, { recursive: true });

const db = new Database(path.join(DATA_DIR, 'fleet.db'));
db.pragma('journal_mode = WAL');

db.exec(`
CREATE TABLE IF NOT EXISTS devices (
  device_id     TEXT PRIMARY KEY,     -- 기기 고유 ID (Android ANDROID_ID)
  model         TEXT,
  serial        TEXT,
  version_code  INTEGER,
  version_name  TEXT,
  battery       INTEGER,              -- 0~100, 없으면 NULL
  kiosk_locked  INTEGER,              -- 1/0
  start_url     TEXT,
  app_label     TEXT,                 -- 기관/도서관 이름 등(관리자가 기기에 설정한 라벨)
  ip            TEXT,                  -- 체크인 시 원격 IP
  videos        TEXT,                  -- 기기에 든 영상 인벤토리 JSON: [{name,size}]
  update_prompt INTEGER NOT NULL DEFAULT 0, -- 1이면 다음 체크인 때 "업데이트 하시겠어요?" 확인창을 띄우라고 지시
  contact       TEXT,                  -- 기기가 실제로 쓰고 있다고 보고한 문의 연락처
  contact_override TEXT,               -- 관리자가 이 기기에 지정한 연락처(NULL이면 미지정=기기 기본값 사용)
  pin_reset     INTEGER NOT NULL DEFAULT 0, -- 1이면 다음 체크인 때 관리자 PIN을 0000으로 되돌리라고 지시
  checkin_interval_ms INTEGER,        -- 기기가 보고한 자기 체크인 주기. "접속 중" 판정을 기기별로 하기 위한 값
  ap_ssid       TEXT,                  -- 접속 중인 Wi-Fi AP 이름 (설치 장소 식별의 1차 근거)
  ap_bssid      TEXT,                  -- 그 AP의 MAC — 같은 SSID가 여러 곳에 있어도 구분된다
  lat           REAL,                  -- 좌표(NLP 켜진 기기에서만 나온다. 대개 null)
  lng           REAL,                  -- 경도
  loc_accuracy  REAL,                  -- 반경 오차(m)
  located_at    INTEGER,               -- 그 좌표를 잡은 시각(epoch ms) — 체크인 시각과 다를 수 있다
  first_seen    INTEGER NOT NULL,     -- epoch ms
  last_seen     INTEGER NOT NULL,     -- epoch ms
  checkin_count INTEGER NOT NULL DEFAULT 0
);

-- 기기별 영상 원격 삭제 대기열. 체크인 응답으로 내려보내고, 기기가 실제로 삭제해
-- 다음 체크인에서 목록에 없으면 확정 처리한다.
CREATE TABLE IF NOT EXISTS video_deletes (
  device_id TEXT NOT NULL,
  filename  TEXT NOT NULL,
  queued_at INTEGER NOT NULL,
  PRIMARY KEY (device_id, filename)
);

-- 배포 버전 이력. 업로드마다 새 행 — 기존엔 단일 행이라 재업로드 시 이전 APK 파일이
-- 덮어써져 롤백이 불가능했다. active=1인 행이 현재 배포판(=/download/app.apk 로 나가는 것)이고,
-- 한 번에 하나만 active 일 수 있다.
CREATE TABLE IF NOT EXISTS releases (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  version_code INTEGER NOT NULL,
  version_name TEXT NOT NULL,
  filename     TEXT NOT NULL,         -- data/apk/ 하위 파일명(행마다 고유 — app-<id>.apk)
  sha256       TEXT NOT NULL,
  size         INTEGER NOT NULL,
  notes        TEXT,
  uploaded_at  INTEGER NOT NULL,
  active       INTEGER NOT NULL DEFAULT 0
);

-- 영상 자료실: 관리자가 올려둔 원본 파일. 여러 기기에 반복 배포할 수 있도록 기기 상태와
-- 분리해서 보관한다.
CREATE TABLE IF NOT EXISTS media_library (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  filename      TEXT NOT NULL,        -- data/videos/ 하위 저장 파일명(행마다 고유)
  original_name TEXT NOT NULL,        -- 기기에 실제로 내려가는 파일명(VideoRepository 가 이 이름으로 저장)
  size          INTEGER NOT NULL,
  sha256        TEXT NOT NULL,
  uploaded_at   INTEGER NOT NULL,
  thumb         TEXT                  -- data/videos/ 하위 썸네일 파일명(없으면 NULL) — 기기 동영상 목록에 표시
);

-- 기기별 영상 배포(푸시) 대기열. 체크인 응답으로 다운로드 지시를 내려보내고, 기기 인벤토리에
-- 해당 파일이 나타나면(=다운로드 완료) 확정 처리해 대기열에서 지운다.
CREATE TABLE IF NOT EXISTS video_pushes (
  device_id TEXT NOT NULL,
  media_id  INTEGER NOT NULL,
  queued_at INTEGER NOT NULL,
  PRIMARY KEY (device_id, media_id)
);

-- 기기별 홈 화면 이용안내 이미지. 한 기기의 행 전체가 "그 기기의 이용안내 세트"이고,
-- seq 순서대로 세로로 이어 붙여 보여준다. 행이 하나도 없으면 앱 내장 이미지를 쓴다.
-- 전체 교체든 낱장 교체든 결국 이 세트를 편집하는 같은 동작이다.
CREATE TABLE IF NOT EXISTS manual_images (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id     TEXT NOT NULL,
  seq           INTEGER NOT NULL,      -- 표시 순서(0부터)
  filename      TEXT NOT NULL,         -- data/manuals/ 하위 저장 파일명
  original_name TEXT NOT NULL,         -- 관리자가 올린 원본 파일명(화면 표시용)
  size          INTEGER NOT NULL,
  sha256        TEXT NOT NULL,
  uploaded_at   INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_manual_device ON manual_images(device_id, seq);

-- 기기별 체크인 이력(최근 접속 추이 파악용, 과도한 적재 방지 위해 필요 시 정리)
CREATE TABLE IF NOT EXISTS checkins (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id    TEXT NOT NULL,
  version_code INTEGER,
  battery      INTEGER,
  at           INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_checkins_device ON checkins(device_id, at);
`);

// 기존 DB에 없을 수 있는 컬럼을 안전하게 추가(이미 있으면 무시).
for (const stmt of [
  `ALTER TABLE devices ADD COLUMN videos TEXT`,
  `ALTER TABLE devices ADD COLUMN update_prompt INTEGER NOT NULL DEFAULT 0`,
  `ALTER TABLE devices ADD COLUMN contact TEXT`,
  `ALTER TABLE devices ADD COLUMN contact_override TEXT`,
  `ALTER TABLE devices ADD COLUMN pin_reset INTEGER NOT NULL DEFAULT 0`,
  `ALTER TABLE devices ADD COLUMN ap_ssid TEXT`,
  `ALTER TABLE devices ADD COLUMN ap_bssid TEXT`,
  `ALTER TABLE devices ADD COLUMN lat REAL`,
  `ALTER TABLE devices ADD COLUMN lng REAL`,
  `ALTER TABLE devices ADD COLUMN loc_accuracy REAL`,
  `ALTER TABLE devices ADD COLUMN located_at INTEGER`,
  `ALTER TABLE media_library ADD COLUMN thumb TEXT`,
  `ALTER TABLE devices ADD COLUMN checkin_interval_ms INTEGER`,
  // 영상 배포 방식: 'force' = 기기가 받는 즉시 다운로드, 'ask' = 기기 화면에서 동의 후 다운로드
  `ALTER TABLE video_pushes ADD COLUMN mode TEXT NOT NULL DEFAULT 'force'`,
  // 1이면 다음 체크인 때 사용 여부와 무관하게 즉시 설치(관리자의 명시적 강제 업데이트)
  `ALTER TABLE devices ADD COLUMN force_update INTEGER NOT NULL DEFAULT 0`,
  // 1이면 다음 체크인 응답에 재부팅 지시를 1회 싣고 즉시 내린다(fire-and-forget — 아래 주석)
  `ALTER TABLE devices ADD COLUMN reboot_requested INTEGER NOT NULL DEFAULT 0`,
  // 관리자가 지정한 함대 서버 주소(원격 이전용). 기기 보고값(fleet_url)과 다르면 지시 전송
  `ALTER TABLE devices ADD COLUMN fleet_url_override TEXT`,
  // 기기가 체크인마다 보고하는 "현재 쓰는" 함대 서버 주소(완료 판정 근거)
  `ALTER TABLE devices ADD COLUMN fleet_url TEXT`
]) {
  try { db.exec(stmt); } catch (e) { /* already exists */ }
}

// 예전 단일 행 release_info 가 있고 releases 가 비어 있으면 1회 이전한다(과거 배포 이력 보존).
try {
  const hasOld = db.prepare(
    `SELECT name FROM sqlite_master WHERE type='table' AND name='release_info'`
  ).get();
  if (hasOld) {
    const releasesEmpty = db.prepare(`SELECT COUNT(*) AS n FROM releases`).get().n === 0;
    if (releasesEmpty) {
      const old = db.prepare(`SELECT * FROM release_info WHERE id = 1`).get();
      if (old) {
        db.prepare(`
          INSERT INTO releases (version_code, version_name, filename, sha256, size, notes, uploaded_at, active)
          VALUES (?, ?, ?, ?, ?, ?, ?, 1)
        `).run(old.version_code, old.version_name, old.filename, old.sha256, old.size, old.notes, old.uploaded_at);
        console.log('[db] release_info(단일 행) → releases(이력) 1회 이전 완료');
      }
    }
  }
} catch (e) { console.error('release_info 이전 중 오류(무시하고 계속)', e); }

const stmtUpsert = db.prepare(`
INSERT INTO devices (device_id, model, serial, version_code, version_name, battery,
                     kiosk_locked, start_url, app_label, ip, videos, contact,
                     ap_ssid, ap_bssid, lat, lng, loc_accuracy, located_at,
                     checkin_interval_ms, fleet_url, first_seen, last_seen, checkin_count)
VALUES (@device_id, @model, @serial, @version_code, @version_name, @battery,
        @kiosk_locked, @start_url, @app_label, @ip, @videos, @contact,
        @ap_ssid, @ap_bssid, @lat, @lng, @loc_accuracy, @located_at,
        @checkin_interval_ms, @fleet_url, @now, @now, 1)
ON CONFLICT(device_id) DO UPDATE SET
  model        = excluded.model,
  serial       = excluded.serial,
  version_code = excluded.version_code,
  version_name = excluded.version_name,
  battery      = excluded.battery,
  kiosk_locked = excluded.kiosk_locked,
  start_url    = excluded.start_url,
  app_label    = COALESCE(excluded.app_label, devices.app_label),
  ip           = excluded.ip,
  videos       = excluded.videos,
  contact      = excluded.contact,
  fleet_url    = COALESCE(excluded.fleet_url, devices.fleet_url),
  -- 위치 관련 값은 있을 때만 갱신한다. 위치를 못 잡은 체크인이 한 번 끼었다고 마지막으로
  -- 알던 설치 위치까지 지워버리면, 오히려 정보가 줄어든다.
  ap_ssid      = COALESCE(excluded.ap_ssid, devices.ap_ssid),
  ap_bssid     = COALESCE(excluded.ap_bssid, devices.ap_bssid),
  lat          = COALESCE(excluded.lat, devices.lat),
  lng          = COALESCE(excluded.lng, devices.lng),
  loc_accuracy = COALESCE(excluded.loc_accuracy, devices.loc_accuracy),
  located_at   = COALESCE(excluded.located_at, devices.located_at),
  checkin_interval_ms = COALESCE(excluded.checkin_interval_ms, devices.checkin_interval_ms),
  last_seen    = excluded.last_seen,
  checkin_count = devices.checkin_count + 1
`);

// 영상 삭제 대기열
const stmtQueueDelete = db.prepare(
  `INSERT OR IGNORE INTO video_deletes (device_id, filename, queued_at) VALUES (?, ?, ?)`
);
const stmtPendingDeletes = db.prepare(
  `SELECT filename FROM video_deletes WHERE device_id = ?`
);
const stmtClearDelete = db.prepare(
  `DELETE FROM video_deletes WHERE device_id = ? AND filename = ?`
);
function queueVideoDelete(deviceId, filename) { stmtQueueDelete.run(deviceId, filename, Date.now()); }
function pendingVideoDeletes(deviceId) { return stmtPendingDeletes.all(deviceId).map(r => r.filename); }

// 영상 배포(푸시) 대기열. 같은 영상을 다시 보내며 방식만 바꾸는 경우가 있어(대기 중
// "물어보기"를 "강제"로 승격 등) 중복 삽입은 무시하되 mode 는 최신 값으로 덮어쓴다.
const stmtQueuePush = db.prepare(
  `INSERT INTO video_pushes (device_id, media_id, queued_at, mode) VALUES (?, ?, ?, ?)
   ON CONFLICT(device_id, media_id) DO UPDATE SET mode = excluded.mode`
);
const stmtPendingPushes = db.prepare(`
  SELECT vp.media_id AS media_id, vp.mode AS mode, m.original_name AS original_name,
         m.filename AS filename, m.size AS size, m.sha256 AS sha256
  FROM video_pushes vp JOIN media_library m ON m.id = vp.media_id
  WHERE vp.device_id = ?
`);
const stmtClearPush = db.prepare(`DELETE FROM video_pushes WHERE device_id = ? AND media_id = ?`);
const stmtClearPushesForMedia = db.prepare(`DELETE FROM video_pushes WHERE media_id = ?`);
function queueVideoPush(deviceId, mediaId, mode) {
  stmtQueuePush.run(deviceId, mediaId, Date.now(), mode === 'ask' ? 'ask' : 'force');
}
function pendingVideoPushes(deviceId) { return stmtPendingPushes.all(deviceId); }
function clearVideoPush(deviceId, mediaId) { stmtClearPush.run(deviceId, mediaId); }

const stmtInsertCheckin = db.prepare(
  `INSERT INTO checkins (device_id, version_code, battery, at) VALUES (?, ?, ?, ?)`
);

const stmtClearPrompt = db.prepare(`UPDATE devices SET update_prompt = 0 WHERE device_id = ?`);
const stmtClearForce = db.prepare(`UPDATE devices SET force_update = 0 WHERE device_id = ?`);
const stmtClearPinReset = db.prepare(`UPDATE devices SET pin_reset = 0 WHERE device_id = ?`);

function recordCheckin(d) {
  const now = Date.now();
  const videos = Array.isArray(d.videos) ? d.videos : [];
  stmtUpsert.run({
    device_id: d.deviceId,
    model: d.model || null,
    serial: d.serial || null,
    version_code: Number.isFinite(d.versionCode) ? d.versionCode : null,
    version_name: d.versionName || null,
    battery: Number.isFinite(d.battery) ? d.battery : null,
    kiosk_locked: d.kioskLocked ? 1 : 0,
    start_url: d.startUrl || null,
    app_label: d.appLabel || null,
    ip: d.ip || null,
    videos: JSON.stringify(videos),
    contact: d.contact || null,
    ap_ssid: d.apSsid || null,
    ap_bssid: d.apBssid || null,
    lat: Number.isFinite(d.lat) ? d.lat : null,
    lng: Number.isFinite(d.lng) ? d.lng : null,
    loc_accuracy: Number.isFinite(d.locAccuracy) ? d.locAccuracy : null,
    located_at: Number.isFinite(d.locatedAt) ? d.locatedAt : null,
    checkin_interval_ms: Number.isFinite(d.checkinIntervalMs) && d.checkinIntervalMs > 0
      ? d.checkinIntervalMs : null,
    fleet_url: d.fleetUrl || null,
    now
  });

  // PIN 초기화 확정 처리: 기기가 "이제 사용자 지정 PIN 없음"이라고 보고하면 지시가 먹힌 것.
  // 지시를 내려보낼 때가 아니라 이 보고를 받았을 때 내려야, 응답이 유실된 경우 다시 보낸다.
  if (d.hasCustomPin === false) stmtClearPinReset.run(d.deviceId);
  stmtInsertCheckin.run(d.deviceId, Number.isFinite(d.versionCode) ? d.versionCode : null,
    Number.isFinite(d.battery) ? d.battery : null, now);

  // 삭제 확정 처리: 대기열에 있던 파일이 이제 기기 목록에 없으면 실제 삭제된 것 → 대기열에서 제거.
  const names = new Set(videos.map(v => v && v.name));
  for (const fn of pendingVideoDeletes(d.deviceId)) {
    if (!names.has(fn)) stmtClearDelete.run(d.deviceId, fn);
  }

  // 배포 확정 처리: 대기열에 있던 미디어가 이제 기기 목록에 나타났으면 다운로드 완료 → 대기열에서 제거.
  for (const p of pendingVideoPushes(d.deviceId)) {
    if (names.has(p.original_name)) stmtClearPush.run(d.deviceId, p.media_id);
  }

  // 업데이트 확인창/강제 지시 자동 해제: 신고된 버전이 이미 현재 배포판 이상이면 끝난 것.
  const active = getRelease();
  if (active && Number.isFinite(d.versionCode) && d.versionCode >= active.version_code) {
    stmtClearPrompt.run(d.deviceId);
    stmtClearForce.run(d.deviceId);
  }
}

const stmtAllDevices = db.prepare(`SELECT * FROM devices ORDER BY last_seen DESC`);
function allDevices() { return stmtAllDevices.all(); }

// ---------- 배포 버전 이력 ----------

const stmtActiveRelease = db.prepare(`SELECT * FROM releases WHERE active = 1 ORDER BY id DESC LIMIT 1`);
function getRelease() { return stmtActiveRelease.get() || null; }

const stmtListReleases = db.prepare(`SELECT * FROM releases ORDER BY id DESC`);
function listReleases() { return stmtListReleases.all(); }

const stmtGetReleaseById = db.prepare(`SELECT * FROM releases WHERE id = ?`);
function getReleaseById(id) { return stmtGetReleaseById.get(id) || null; }

const stmtInsertRelease = db.prepare(`
  INSERT INTO releases (version_code, version_name, filename, sha256, size, notes, uploaded_at, active)
  VALUES (@version_code, @version_name, @filename, @sha256, @size, @notes, @uploaded_at, 0)
`);
const stmtDeactivateAllReleases = db.prepare(`UPDATE releases SET active = 0`);
const stmtActivateRelease = db.prepare(`UPDATE releases SET active = 1 WHERE id = ?`);
const stmtSetReleaseFilename = db.prepare(`UPDATE releases SET filename = ? WHERE id = ?`);

/** 새 릴리스를 이력에 추가하고 즉시 활성화(=배포)한다. 반환값은 새로 생긴 행의 id. */
function insertRelease(r) {
  const info = stmtInsertRelease.run(r);
  activateRelease(info.lastInsertRowid);
  return info.lastInsertRowid;
}
/** 업로드 파일을 파일명이 곧 릴리스 id인 고유 경로로 옮긴 뒤 DB에도 반영(server.js 에서 rename 후 호출). */
function setReleaseFilename(id, filename) { stmtSetReleaseFilename.run(filename, id); }
/** 기존 이력 중 하나를 다시 배포판으로 지정(롤백). 파일은 그대로 있으므로 재업로드가 필요 없다. */
function activateRelease(id) {
  const tx = db.transaction((releaseId) => {
    stmtDeactivateAllReleases.run();
    stmtActivateRelease.run(releaseId);
  });
  tx(id);
}

// 라벨(기관명) 수동 지정/수정
const stmtSetLabel = db.prepare(`UPDATE devices SET app_label = ? WHERE device_id = ?`);
function setLabel(deviceId, label) { stmtSetLabel.run(label, deviceId); }

const stmtDeleteDevice = db.prepare(`DELETE FROM devices WHERE device_id = ?`);
const stmtDeleteCheckins = db.prepare(`DELETE FROM checkins WHERE device_id = ?`);
const stmtDeleteVideoQueue = db.prepare(`DELETE FROM video_deletes WHERE device_id = ?`);
const stmtDeletePushQueueForDevice = db.prepare(`DELETE FROM video_pushes WHERE device_id = ?`);
const stmtDeleteManualForDevice = db.prepare(`DELETE FROM manual_images WHERE device_id = ?`);
function deleteDevice(deviceId) {
  stmtDeleteCheckins.run(deviceId);
  stmtDeleteVideoQueue.run(deviceId);
  stmtDeletePushQueueForDevice.run(deviceId);
  stmtDeleteManualForDevice.run(deviceId);   // 파일 삭제는 server.js 담당(경로를 아는 쪽)
  stmtDeleteDevice.run(deviceId);
}

// ---------- 업데이트 알림(강제 확인창) ----------

const stmtRequestPrompt = db.prepare(`UPDATE devices SET update_prompt = 1 WHERE device_id = ?`);
/** 이 기기 하나에 "업데이트 하시겠어요?" 확인창을 다음 체크인 때 띄우라고 지시한다. */
function requestUpdatePrompt(deviceId) { stmtRequestPrompt.run(deviceId); }

const stmtRequestForce = db.prepare(`UPDATE devices SET force_update = 1 WHERE device_id = ?`);
/** 이 기기를 사용(재생) 중이어도 다음 체크인 때 즉시 설치하라고 지시한다(관리자 명시 선택). */
function requestForceUpdate(deviceId) { stmtRequestForce.run(deviceId); }

// 원격 재부팅. **fire-and-forget 예외** — 다른 지시는 "기기 보고 기반 완료 판정"이 규약이지만
// 재부팅은 반복 실행되면 안 되는 지시라(응답 유실 시 다음 체크인마다 재부팅 루프),
// 응답에 1회 싣는 순간 플래그를 내린다. 유실되면 관리자가 다시 누르면 된다.
const stmtRequestReboot = db.prepare(`UPDATE devices SET reboot_requested = 1 WHERE device_id = ?`);
const stmtClearReboot = db.prepare(`UPDATE devices SET reboot_requested = 0 WHERE device_id = ?`);
function requestReboot(deviceId) { stmtRequestReboot.run(deviceId); }
function consumeReboot(deviceId) { stmtClearReboot.run(deviceId); }

// 함대 서버 주소 원격 변경(NetBird 이전, 향후 AWS 이전용). 완료 판정은 기기 보고 기반 —
// override 와 기기가 보고한 fleet_url 이 같아지면 자동으로 안 보낸다(setContact 와 동일 규약).
const stmtSetFleetUrlOverride = db.prepare(`UPDATE devices SET fleet_url_override = ? WHERE device_id = ?`);
function setFleetUrlOverride(deviceId, url) { stmtSetFleetUrlOverride.run(url || null, deviceId); }

const stmtOutdatedDeviceIds = db.prepare(
  `SELECT device_id FROM devices WHERE version_code IS NULL OR version_code < ?`
);
/** 현재 배포판보다 낮은 버전을 쓰는 모든 기기에 확인창 지시를 건다. 반환값은 대상 기기 수. */
function requestUpdatePromptForOutdated() {
  const active = getRelease();
  if (!active) return 0;
  const ids = stmtOutdatedDeviceIds.all(active.version_code).map(r => r.device_id);
  const tx = db.transaction((list) => { for (const id of list) stmtRequestPrompt.run(id); });
  tx(ids);
  return ids.length;
}

// ---------- 기기별 원격 설정(연락처 / PIN 초기화) ----------

const stmtSetContactOverride = db.prepare(`UPDATE devices SET contact_override = ? WHERE device_id = ?`);
/**
 * 이 기기의 문의 연락처를 지정한다. 빈 문자열이면 지정 해제(기기 기본값 02-334-2227 유지).
 * 별도 "적용 완료" 플래그를 두지 않는다 — 기기가 체크인마다 현재 값을 보고하므로,
 * override 와 보고값이 같아질 때까지 지시가 자동으로 재전송된다.
 */
function setContactOverride(deviceId, text) {
  stmtSetContactOverride.run(text && text.trim() ? text.trim() : null, deviceId);
}

const stmtRequestPinReset = db.prepare(`UPDATE devices SET pin_reset = 1 WHERE device_id = ?`);
/** 다음 체크인 때 이 기기의 관리자 PIN을 0000으로 되돌리라고 지시한다(비밀번호 분실 대비). */
function requestPinReset(deviceId) { stmtRequestPinReset.run(deviceId); }

// ---------- 기기별 이용안내 이미지 ----------

const stmtListManual = db.prepare(
  `SELECT * FROM manual_images WHERE device_id = ? ORDER BY seq, id`
);
const stmtGetManual = db.prepare(`SELECT * FROM manual_images WHERE id = ?`);
const stmtMaxManualSeq = db.prepare(
  `SELECT COALESCE(MAX(seq), -1) AS m FROM manual_images WHERE device_id = ?`
);
const stmtInsertManual = db.prepare(`
  INSERT INTO manual_images (device_id, seq, filename, original_name, size, sha256, uploaded_at)
  VALUES (@device_id, @seq, @filename, @original_name, @size, @sha256, @uploaded_at)
`);
const stmtDeleteManual = db.prepare(`DELETE FROM manual_images WHERE id = ?`);
const stmtSetManualSeq = db.prepare(`UPDATE manual_images SET seq = ? WHERE id = ?`);

function listManual(deviceId) { return stmtListManual.all(deviceId); }
function getManual(id) { return stmtGetManual.get(id) || null; }

/** 세트 맨 뒤에 덧붙인다. 반환값은 새 행 id. */
function appendManual(m) {
  const seq = stmtMaxManualSeq.get(m.device_id).m + 1;
  return stmtInsertManual.run({ ...m, seq }).lastInsertRowid;
}

function deleteManual(id) { stmtDeleteManual.run(id); }

/**
 * 한 장을 위/아래로 한 칸 옮긴다. 옆 행과 seq 를 맞바꾸는 대신 목록 전체를 다시 번호
 * 매긴다 — 중간에 삭제가 있었어도 seq 에 구멍이 남지 않아 순서가 항상 0..n-1 로 정규화된다.
 */
function moveManual(id, dir) {
  const row = getManual(id);
  if (!row) return;
  const list = listManual(row.device_id);
  const i = list.findIndex(r => r.id === id);
  const j = i + (dir < 0 ? -1 : 1);
  if (i < 0 || j < 0 || j >= list.length) return;
  [list[i], list[j]] = [list[j], list[i]];
  const tx = db.transaction(rows => rows.forEach((r, k) => stmtSetManualSeq.run(k, r.id)));
  tx(list);
}

// ---------- 영상 자료실 ----------

const stmtInsertMedia = db.prepare(`
  INSERT INTO media_library (filename, original_name, size, sha256, uploaded_at)
  VALUES (@filename, @original_name, @size, @sha256, @uploaded_at)
`);
function insertMedia(m) { return stmtInsertMedia.run(m).lastInsertRowid; }

const stmtSetMediaFilename = db.prepare(`UPDATE media_library SET filename = ? WHERE id = ?`);
function setMediaFilename(id, filename) { stmtSetMediaFilename.run(filename, id); }

const stmtSetMediaThumb = db.prepare(`UPDATE media_library SET thumb = ? WHERE id = ?`);
function setMediaThumb(id, thumb) { stmtSetMediaThumb.run(thumb, id); }

const stmtListMedia = db.prepare(`SELECT * FROM media_library ORDER BY id DESC`);
function listMedia() { return stmtListMedia.all(); }

const stmtGetMedia = db.prepare(`SELECT * FROM media_library WHERE id = ?`);
function getMedia(id) { return stmtGetMedia.get(id) || null; }

const stmtDeleteMedia = db.prepare(`DELETE FROM media_library WHERE id = ?`);
/** 자료실에서 삭제. 대기 중이던 배포 큐도 함께 정리한다(파일 자체 삭제는 server.js 담당). */
function deleteMedia(id) {
  stmtClearPushesForMedia.run(id);
  stmtDeleteMedia.run(id);
}

module.exports = {
  db, DATA_DIR,
  recordCheckin, allDevices, setLabel, deleteDevice,
  queueVideoDelete, pendingVideoDeletes,
  queueVideoPush, pendingVideoPushes, clearVideoPush,
  getRelease, listReleases, getReleaseById, insertRelease, setReleaseFilename, activateRelease,
  requestUpdatePrompt, requestUpdatePromptForOutdated, requestForceUpdate,
  requestReboot, consumeReboot, setFleetUrlOverride,
  setContactOverride, requestPinReset,
  insertMedia, setMediaFilename, setMediaThumb, listMedia, getMedia, deleteMedia,
  listManual, getManual, appendManual, deleteManual, moveManual
};
