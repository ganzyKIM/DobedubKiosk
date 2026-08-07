'use strict';
// SQLite 저장소 — 기기 인벤토리 + 현재 배포 버전(릴리스) 메타데이터.
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

CREATE TABLE IF NOT EXISTS release_info (
  id           INTEGER PRIMARY KEY CHECK (id = 1),  -- 항상 단일 행
  version_code INTEGER NOT NULL,
  version_name TEXT NOT NULL,
  filename     TEXT NOT NULL,         -- data/apk/ 하위 파일명
  sha256       TEXT NOT NULL,
  size         INTEGER NOT NULL,
  notes        TEXT,
  uploaded_at  INTEGER NOT NULL
);

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

// 기존 DB에 videos 컬럼이 없을 수 있으니 안전하게 추가(이미 있으면 무시).
try { db.exec(`ALTER TABLE devices ADD COLUMN videos TEXT`); } catch (e) { /* already exists */ }

const stmtUpsert = db.prepare(`
INSERT INTO devices (device_id, model, serial, version_code, version_name, battery,
                     kiosk_locked, start_url, app_label, ip, videos, first_seen, last_seen, checkin_count)
VALUES (@device_id, @model, @serial, @version_code, @version_name, @battery,
        @kiosk_locked, @start_url, @app_label, @ip, @videos, @now, @now, 1)
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

const stmtInsertCheckin = db.prepare(
  `INSERT INTO checkins (device_id, version_code, battery, at) VALUES (?, ?, ?, ?)`
);

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
    now
  });
  stmtInsertCheckin.run(d.deviceId, Number.isFinite(d.versionCode) ? d.versionCode : null,
    Number.isFinite(d.battery) ? d.battery : null, now);

  // 삭제 확정 처리: 대기열에 있던 파일이 이제 기기 목록에 없으면 실제 삭제된 것 → 대기열에서 제거.
  const names = new Set(videos.map(v => v && v.name));
  for (const fn of pendingVideoDeletes(d.deviceId)) {
    if (!names.has(fn)) stmtClearDelete.run(d.deviceId, fn);
  }
}

const stmtAllDevices = db.prepare(`SELECT * FROM devices ORDER BY last_seen DESC`);
function allDevices() { return stmtAllDevices.all(); }

const stmtGetRelease = db.prepare(`SELECT * FROM release_info WHERE id = 1`);
function getRelease() { return stmtGetRelease.get() || null; }

const stmtSetRelease = db.prepare(`
INSERT INTO release_info (id, version_code, version_name, filename, sha256, size, notes, uploaded_at)
VALUES (1, @version_code, @version_name, @filename, @sha256, @size, @notes, @uploaded_at)
ON CONFLICT(id) DO UPDATE SET
  version_code = excluded.version_code,
  version_name = excluded.version_name,
  filename     = excluded.filename,
  sha256       = excluded.sha256,
  size         = excluded.size,
  notes        = excluded.notes,
  uploaded_at  = excluded.uploaded_at
`);
function setRelease(r) { stmtSetRelease.run(r); }

// 라벨(기관명) 수동 지정/수정
const stmtSetLabel = db.prepare(`UPDATE devices SET app_label = ? WHERE device_id = ?`);
function setLabel(deviceId, label) { stmtSetLabel.run(label, deviceId); }

const stmtDeleteDevice = db.prepare(`DELETE FROM devices WHERE device_id = ?`);
const stmtDeleteCheckins = db.prepare(`DELETE FROM checkins WHERE device_id = ?`);
const stmtDeleteVideoQueue = db.prepare(`DELETE FROM video_deletes WHERE device_id = ?`);
function deleteDevice(deviceId) { stmtDeleteCheckins.run(deviceId); stmtDeleteVideoQueue.run(deviceId); stmtDeleteDevice.run(deviceId); }

module.exports = {
  db, recordCheckin, allDevices, getRelease, setRelease, setLabel, deleteDevice, DATA_DIR,
  queueVideoDelete, pendingVideoDeletes
};
