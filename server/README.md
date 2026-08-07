# 두비덥 키오스크 함대 관리 서버

배포된 태블릿들의 **접속 현황을 수집하고, 앱을 원격 일괄 업데이트**하는 Node.js + SQLite 서버.
기기는 주기적으로 이 서버에 체크인하고, 응답으로 받은 최신 버전 정보를 보고 스스로 업데이트한다.

```
기기(앱) ──POST /api/checkin──▶ 서버 ──(SQLite에 기록)
기기(앱) ◀──최신버전 매니페스트── 서버
기기(앱) ──GET /download/app.apk──▶ 서버 (새 버전이면 내려받아 무인 설치)
관리자 ──▶ /dashboard (기기 목록·버전 현황·APK 업로드)
```

## 구성 요소

| 파일 | 역할 |
|---|---|
| `server.js` | HTTP 라우팅 (체크인 API, APK 배포, 대시보드) |
| `db.js` | SQLite 저장소 (기기 인벤토리, 현재 배포 버전) |
| `auth.js` | 공유 비밀번호 로그인 + HMAC 서명 쿠키 |
| `views.js` | 대시보드/로그인 HTML |
| `data/` | 런타임 데이터 (SQLite DB + 업로드된 APK) — git 제외 |

## 실행

```bash
cd server
npm install
ADMIN_PASSWORD=바꾸세요 SESSION_SECRET=랜덤문자열 PORT=8090 npm start
```

Windows PowerShell:

```powershell
$env:ADMIN_PASSWORD="바꾸세요"; $env:SESSION_SECRET="랜덤문자열"; $env:PORT="8090"; npm start
```

브라우저에서 `http://localhost:8090/dashboard` → 비밀번호 로그인.

### 환경변수

| 변수 | 기본값 | 설명 |
|---|---|---|
| `ADMIN_PASSWORD` | `dobedub` | **대시보드 로그인 비밀번호. 운영 시 반드시 변경.** |
| `SESSION_SECRET` | (임의 생성) | 세션 쿠키 서명 키. 고정하지 않으면 재시작 시 로그인 풀림. |
| `PORT` | `8090` | 수신 포트 |
| `DEVICE_TOKEN` | (없음) | 설정 시 기기 체크인에 `X-Kiosk-Token` 헤더 요구(앱에도 같은 값 설정). |
| `BASE_URL` | (요청 기준 자동) | 프록시 뒤에서 APK 절대 URL 구성용 (예: `https://kiosk.dobedub.com`). |
| `COOKIE_SECURE` | `0` | HTTPS 뒤면 `1` (쿠키 Secure 플래그). |

## 운영 배포 (공개 HTTPS 필수)

기기들이 인터넷에서 접속하므로 **공개 도메인 + TLS**가 필요하다. 서버 자체는 평문 HTTP(8090)로
띄우고 앞단에 리버스 프록시로 TLS를 씌우는 구성을 권장한다.

### Caddy 예시 (자동 HTTPS)

`/etc/caddy/Caddyfile`:

```
kiosk.dobedub.com {
    reverse_proxy 127.0.0.1:8090
}
```

그리고 서버는 `COOKIE_SECURE=1 BASE_URL=https://kiosk.dobedub.com` 로 실행.
DNS에서 `kiosk.dobedub.com` A레코드를 서버 IP로 지정하면 Caddy가 인증서를 자동 발급한다.

### 상시 구동 (systemd 예시)

```ini
# /etc/systemd/system/kiosk-fleet.service
[Unit]
Description=Dobedub Kiosk Fleet Server
After=network.target
[Service]
WorkingDirectory=/opt/kiosk/server
ExecStart=/usr/bin/node server.js
Environment=ADMIN_PASSWORD=바꾸세요
Environment=SESSION_SECRET=랜덤32바이트
Environment=BASE_URL=https://kiosk.dobedub.com
Environment=COOKIE_SECURE=1
Restart=always
[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl enable --now kiosk-fleet
```

Windows 상시 구동은 [NSSM](https://nssm.cc/) 등으로 `node server.js` 를 서비스 등록.

## 새 버전 배포 절차

1. 앱 `app/build.gradle.kts` 의 `versionCode` 를 올리고(+1), `versionName` 갱신.
2. **릴리스 APK 빌드**: `gradlew assembleRelease` (키스토어 보유 PC에서 — 서명이 기존 기기와 같아야 함).
3. 대시보드 → **"새 APK 업로드"** 에 APK + 같은 versionCode/versionName 입력 → 업로드.
4. 기기들이 다음 체크인(최대 30분, 재부팅 시 즉시) 때 자동으로 내려받아 무인 설치.

> **체크인 주기는 앱과 서버 양쪽에 있다.** 앱 `MainActivity.UPDATE_CHECK_INTERVAL_MS` 와
> 서버 `server.js` 의 `CHECKIN_INTERVAL_MS` 는 항상 같은 값이어야 한다. 대시보드의
> 온라인/오프라인 판정이 서버 쪽 값에서 파생되므로, 어긋나면 멀쩡한 기기가 "대기"로 표시된다.

> APK 무결성은 서버가 계산한 sha256 을 기기가 다운로드 후 검증한다.
> versionCode 가 기기의 현재 값보다 커야만 업데이트가 트리거된다.

## API 요약

- `POST /api/checkin` — body `{deviceId, model, versionCode, versionName, battery, kioskLocked, startUrl, appLabel}`.
  응답 `{update, versionCode, versionName, apkUrl, sha256, size}`.
- `GET /api/latest` — 현재 매니페스트(디버그용).
- `GET /download/app.apk` — 현재 배포 APK.

## 주의

- 이 서버는 소규모 함대(수십 대) 기준의 단순 구성이다. 대규모로 확장하거나 원격 초기화/설정
  푸시까지 필요하면 정식 EMM(Android Management API 등) 도입을 검토한다.
- `data/` 폴더(기기 DB·APK)를 정기 백업할 것.
