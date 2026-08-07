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

## 관리자 PC에서 24시간 운영하기 (현재 방식)

당분간은 상시 켜져 있는 관리자 PC(웍스메일 자동화가 도는 PC)에서 돌린다.

### ⚠ 먼저 해결해야 하는 것 — 태블릿이 이 PC에 닿을 수 있는가

도서관에 나가 있는 태블릿은 **다른 네트워크에 있다.** 사내 LAN에만 있는 PC 주소
(`http://192.168.x.x:8090`)로는 체크인을 보낼 수 없다. 같은 망에 있는 태블릿만 잡히고
나머지는 대시보드에 영원히 안 나타난다.

공인 IP·포트포워딩 없이 해결하려면 **Cloudflare Tunnel**이 가장 간단하다(무료).

```bash
cloudflared tunnel --url http://localhost:8090
```

고정 주소가 필요하면 named tunnel로 만들어 `kiosk.<도메인>` 에 붙인다. 주소가 정해지면
태블릿마다 관리자 화면(로고 5번 탭 → 업데이트)에서 서버 주소를 그 주소로 바꾼다.
**앱을 다시 설치할 필요는 없다** — 서버 주소는 런타임 설정값이다.

### 상시 구동 (Windows 서비스 등록)

[NSSM](https://nssm.cc/)으로 `node server.js`를 서비스로 올리면 부팅 시 자동 시작 +
죽으면 자동 재시작된다.

```powershell
nssm install DobedubKioskFleet "C:\Program Files\nodejs\node.exe" "server.js"
nssm set DobedubKioskFleet AppDirectory "<복사한 경로>\DobedubKiosk\server"
nssm set DobedubKioskFleet AppEnvironmentExtra ADMIN_PASSWORD=<비밀번호> SESSION_SECRET=<랜덤32바이트>
nssm start DobedubKioskFleet
```

> Node는 **Windows에서 SIGTERM을 지원하지 않는다.** 서버에 넣어둔 graceful shutdown
> 핸들러는 리눅스/ECS에서만 동작한다. Windows에서 강제 종료돼도 SQLite는 WAL 모드라
> 크래시 안전이므로 DB가 깨지지는 않는다.

### 백업

`data/` 하나만 챙기면 된다(기기 DB + 업로드된 APK). 정기적으로 다른 드라이브에 복사할 것.

---

## 보고팡 인프라(AWS)로 이전할 때

사내 보고팡 백엔드는 **ECS on EC2(linux/arm64) + ECR + RDS MariaDB + S3/CloudFront**,
CI는 GitHub Actions(`main`/`dev` push 자동 배포), 시크릿은 AWS Secrets Manager를 쓴다.
이전을 염두에 두고 **미리 맞춰둔 것**과 **그때 반드시 바꿔야 하는 것**은 아래와 같다.

### 이미 맞춰둔 것

| 항목 | 내용 |
|---|---|
| 헬스체크 | `GET /health` → 200 `ok` (백엔드 규약과 동일, DB까지 실제로 확인) |
| 컨테이너 | `Dockerfile` — node:22-alpine 멀티스테이지, 비특권 사용자, arm64 빌드 가능 |
| 종료 처리 | SIGTERM 수신 시 처리 중 요청 마무리 후 종료(ECS 롤링 업데이트 대응) |
| 설정 외부화 | 포트·비밀번호·시크릿·`DATA_DIR`·`BASE_URL` 전부 환경변수 |
| 프록시 | `trust proxy` 설정됨 (ALB/CloudFront 뒤에서 실제 IP 인식) |

```bash
# arm64 이미지 빌드 예시
docker build --platform linux/arm64 -t dobedub-kiosk-fleet:local server/
```

### 그때 반드시 바꿔야 하는 것 (지금은 안 해도 됨)

1. **SQLite → RDS MariaDB.** ECS 컨테이너 파일시스템은 재배포마다 초기화된다.
   지금 `data/fleet.db`에 있는 기기 이력이 배포 한 번에 사라진다.
   기존 `vogopang-prod` RDS에 별도 스키마를 쓰는 게 자연스럽다.
   → `db.js` 하나만 갈아끼우면 된다. 나머지 코드는 `db.js`가 노출하는 함수
   (`recordCheckin`/`allDevices`/`getRelease`/…)만 쓰므로 영향이 없다.
   덤으로 `better-sqlite3`(네이티브 모듈, alpine musl 컴파일 필요) 의존성이 사라진다.

2. **APK 저장·배포 → S3(+CloudFront).** 지금은 APK를 컨테이너 로컬(`data/apk/`)에 두고
   앱 서버가 직접 전송한다. 운영 ECS는 **vCPU 2 / RAM 8GB 한 대로 보고팡 API 전체**를
   돌리므로, 46MB짜리 APK를 태블릿 수십 대가 동시에 받으면 그 대역폭을 서비스와 나눠 쓴다.
   S3에 올리고 presigned URL을 매니페스트의 `apkUrl`로 내려보내면 앱 서버를 거치지 않는다.

3. **도메인 규칙 확정.** 앱 기본값은 `https://kiosk.dobedub.com`인데, 사내 관례는
   이용자 화면이 `*.dobedub.com`, API가 `back.vogopang.com` 계열이다. 이 서버는
   API + 대시보드가 섞여 있어 어느 쪽을 따를지 백엔드와 합의가 필요하다.

4. **시크릿 이전.** `ADMIN_PASSWORD`/`SESSION_SECRET`을 AWS Secrets Manager
   (`prod/vogopang/kiosk` 등)로 옮기고 ECS 태스크 IAM 롤에 읽기 권한을 준다.

> 인프라 자원 신설(ECR/RDS 스키마/S3/Route53/ACM/보안그룹)과 GitHub org 이관은
> 백엔드·인프라 담당 협조가 필요하다. 상세는 사내 산출물 문서 `BP_BE_OPS`, `BP_BE_CICD` 참조.

---

## 주의

- 이 서버는 소규모 함대(수십 대) 기준의 단순 구성이다. 대규모로 확장하거나 원격 초기화/설정
  푸시까지 필요하면 정식 EMM(Android Management API 등) 도입을 검토한다.
- `data/` 폴더(기기 DB·APK)를 정기 백업할 것.
- **폴더를 통째로 복사해 다른 PC로 옮겼다면 `server/node_modules`를 그대로 쓰지 말 것.**
  `better-sqlite3`는 네이티브 모듈이라 Node 버전/아키텍처가 다르면 로드에 실패한다.
  옮긴 PC에서 `npm ci`(또는 `npm rebuild`)를 한 번 실행한다.
