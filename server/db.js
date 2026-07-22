'use strict';
// SQLite 저장소 — 기기 인벤토리 + 현재 배포 버전(릴리스) 메타데이터.
// better-sqlite3 는 동기 API라 소규모 트래픽에 단순하고 안정적이다.

const path = require('path');
const fs = require('fs');
const Database = require('better-sqlite3');

const DATA_DIR = path.join(__dirname, 'data');
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
  first_seen    INTEGER NOT NULL,     -- epoch ms
  last_seen     INTEGER NOT NULL,     -- epoch ms
  checkin_count INTEGER NOT NULL DEFAULT 0
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

const stmtUpsert = db.prepare(`
INSERT INTO devices (device_id, model, serial, version_code, version_name, battery,
                     kiosk_locked, start_url, app_label, ip, first_seen, last_seen, checkin_count)
VALUES (@device_id, @model, @serial, @version_code, @version_name, @battery,
        @kiosk_locked, @start_url, @app_label, @ip, @now, @now, 1)
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
  last_seen    = excluded.last_seen,
  checkin_count = devices.checkin_count + 1
`);

const stmtInsertCheckin = db.prepare(
  `INSERT INTO checkins (device_id, version_code, battery, at) VALUES (?, ?, ?, ?)`
);

function recordCheckin(d) {
  const now = Date.now();
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
    now
  });
  stmtInsertCheckin.run(d.deviceId, Number.isFinite(d.versionCode) ? d.versionCode : null,
    Number.isFinite(d.battery) ? d.battery : null, now);
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
function deleteDevice(deviceId) { stmtDeleteCheckins.run(deviceId); stmtDeleteDevice.run(deviceId); }

module.exports = {
  db, recordCheckin, allDevices, getRelease, setRelease, setLabel, deleteDevice, DATA_DIR
};
