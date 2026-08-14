# 두비덥 키오스크 함대 관리 서버

배포된 태블릿들의 **접속 현황을 수집하고, 앱·영상을 원격 배포**하는 Node.js + SQLite 서버.
기기는 주기적으로 이 서버에 체크인하고, 응답으로 받은 지시(업데이트/영상 배포/삭제)를 스스로 수행한다.

```
기기(앱) ──POST /api/checkin──▶ 서버 ──(SQLite에 기록)
기기(앱) ◀──매니페스트+지시(업데이트/영상 배포·삭제)── 서버
기기(앱) ──GET /download/app.apk──▶ 서버 (새 버전이면 내려받아 무인 설치)
기기(앱) ──GET /media/:id/download──▶ 서버 (배포 지시된 영상 다운로드)
관리자 ──▶ /dashboard (기기 목록·버전 이력/롤백·영상 자료실·강제 업데이트 알림)
```

기능 요약:
- **APK 배포 + 이력/롤백** — 업로드마다 이력에 남고, 예전 버전으로 재업로드 없이 되돌릴 수 있다.
- **강제 업데이트 알림** — 업데이트 안 된 기기(개별 또는 전체)에 확인창 표시를 지시하면, 기기에서
  사용자가 동의하는 즉시 설치된다(평소엔 홈 화면 유휴 시 조용히 자동 설치).
- **영상 자료실 + 개별 배포** — 영상을 한 번 올려두고 기기별로 골라 내려보낸다. 대용량(수 GB) 대비
  체크인 때마다 재확인하는 큐 방식이라 중간에 끊겨도 다음 체크인에서 이어 확인된다.

## 구성 요소

| 파일 | 역할 |
|---|---|
| `server.js` | HTTP 라우팅 (체크인 API, APK/영상 배포, 대시보드) |
| `db.js` | SQLite 저장소 (기기 인벤토리, 배포 버전 이력, 영상 자료실/배포 큐) |
| `auth.js` | 공유 비밀번호 로그인 + HMAC 서명 쿠키 |
| `views.js` | 대시보드/로그인 HTML |
| `start-tunnel.ps1` / `start-tunnel.sh` | 공인 HTTPS 원격 접속 경로 실행(Cloudflare Quick Tunnel). 앞은 윈도우, 뒤는 맥/리눅스 |
| `data/` | 런타임 데이터 (SQLite DB + 업로드된 APK·영상) — git 제외 |

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

원클릭 실행(백그라운드 기동 + 대시보드 열기 + 태블릿용 LAN 주소 표시)은
윈도우 `관리자-실행.bat` / 맥 `관리자-실행.command`, 종료는 `관리자-종료.*`.

브라우저에서 `http://localhost:8090/dashboard` → 비밀번호 로그인.

### 환경변수

| 변수 | 기본값 | 설명 |
|---|---|---|
| `ADMIN_PASSWORD` | (없음) | 대시보드 로그인 비밀번호. **소스에 기본값을 두지 않는다** — 이 저장소는 공개다. 미설정 시 `data/admin-password.txt` 를 읽고, 그 파일도 없으면 임의 생성해 저장하며 콘솔에 1회 출력한다. 바꾸려면 그 파일을 고치고 서버 재시작. |
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
5. (선택) 빨리 반영하고 싶으면 대시보드 "업데이트 필요 기기에 알림 보내기"로 확인창을 강제로 띄운다.
   기기 쪽에서 확인을 누르면 그 즉시 설치되고, 홈 화면에 있지 않으면 유휴 상태가 될 때까지 대기한다.

문제가 생기면 "배포 이력" 카드에서 이전 버전 행의 **"이 버전으로 롤백"**을 누른다. 예전 APK 파일이
그대로 있으므로 재업로드가 필요 없다(파일이 지워졌다면 재업로드부터 다시 해야 한다).

> **체크인 주기는 앱과 서버 양쪽에 있다.** 앱 `MainActivity.UPDATE_CHECK_INTERVAL_MS` 와
> 서버 `server.js` 의 `CHECKIN_INTERVAL_MS` 는 항상 같은 값이어야 한다. 대시보드의
> 온라인/오프라인 판정이 서버 쪽 값에서 파생되므로, 어긋나면 멀쩡한 기기가 "대기"로 표시된다.

> APK 무결성은 서버가 계산한 sha256 을 기기가 다운로드 후 검증한다.
> versionCode 가 기기의 현재 값보다 커야만 업데이트가 트리거된다.

## 영상 자료실 = `data/videos/` 폴더

**폴더에 영상을 복사해 넣으면 자료실에 자동 등록된다.** 대시보드 업로드 폼도 그대로 쓸 수
있지만(원격 작업용), 수 GB짜리를 브라우저로 올리는 게 번거로워서 폴더를 1차 경로로 둔다.

폴더가 진실이고 DB는 색인이다. 대시보드를 열 때마다(그리고 서버 시작 시) 폴더를 훑어서:

| 상태 | 처리 |
|---|---|
| 폴더에 있는데 DB에 없음 | 등록 (sha256 계산, 파일명 그대로 유지) |
| DB에 있는데 파일이 없음 | 행 제거 + 배포 대기열 정리 |

- **복사가 끝난 뒤 새로고침**해야 뜬다. 수정된 지 10초 안 된 파일은 아직 복사 중일 수 있어
  건너뛴다(반쯤 쓰인 파일을 해싱하면 sha256이 틀려 기기가 다운로드를 거부한다).
- 해싱은 응답을 막지 않는다. 1GB 파일이면 첫 새로고침엔 안 보이고 다음 새로고침에 뜬다.
- 폴더를 못 읽으면 **아무것도 하지 않는다.** 여기서 "파일이 하나도 없다"고 판단해 정리로
  넘어가면 경로가 잘못 잡힌 순간 자료실 전체가 날아간다.
- 업로드 폼으로 올린 파일은 `media-<id>.mp4` 로 저장되고, 폴더에 직접 넣은 파일은 이름이
  그대로 유지된다. 어느 쪽이든 기기에는 `original_name` 으로 내려간다.

> 태블릿 세팅 스크립트(`태블릿-세팅.bat`) 7단계의 영상 투입과는 **다른 경로**다. 그쪽은
> `DobedubKiosk` 상위 폴더의 mp4 를 USB 로 직접 밀어넣는 초기 세팅용이고 자료실과 무관하다.

## API 요약

- `POST /api/checkin` — body `{deviceId, model, versionCode, versionName, battery, kioskLocked,
  startUrl, appLabel, videos, contactInfo, hasCustomPin, apSsid, apBssid, lat?, lng?,
  locAccuracy?, locatedAt?}`.
  - `apSsid`/`apBssid` — 접속 중인 Wi-Fi AP. **설치 장소 식별의 1차 근거다.** 좌표는 납품
    태블릿 대부분에서 안 잡힌다(Google 위치 정확도가 꺼진 채로 나감 → `network` provider
    비활성 → 실내에서 GPS만 남아 fix 불가). AP는 항상 잡힌다.
  - `lat`/`lng` 등 좌표는 NLP가 켜진 기기에서만 온다. 없으면 서버가 기존 값을 유지한다.
  응답 `{update, versionCode, versionName, apkUrl, sha256, size, promptUpdate, deleteVideos,
  pushVideos, setContact?, resetPin?}`.
  - `promptUpdate: true` — 관리자가 이 기기에 확인창 요청을 걸어뒀다는 뜻(§강제 업데이트 알림).
  - `deleteVideos: string[]` — 기기가 삭제해야 할 영상 파일명 목록.
  - `pushVideos: {name,url,sha256,size}[]` — 기기가 내려받아야 할 영상 목록.
  - `setContact: string` — 이 기기의 문의 연락처를 이 값으로 바꾸라는 지시.
  - `resetPin: true` — 관리자 PIN을 공장 기본값 0000 으로 되돌리라는 지시(PIN 분실 복구).
- `GET /api/latest` — 현재 매니페스트(디버그용, 기기 컨텍스트가 없어 `promptUpdate`는 항상 false).
- `GET /download/app.apk` — 현재 활성 배포 APK.
- `GET /media/:id/download` — 영상 자료실 파일(공개, Range 지원 — 대용량 이어받기 가능).

대시보드 폼이 호출하는 관리용 라우트(로그인 필요): `/release/upload`, `/release/rollback`,
`/release/notify-outdated`, `/device/update-prompt`, `/device/label`, `/device/contact`,
`/device/pin-reset`, `/device/delete`, `/device/video/delete`, `/media/upload`, `/media/push`,
`/media/push/cancel`, `/media/delete`.

### 지시가 먹혔는지 확인하는 방식

`setContact`/`resetPin` 은 **보냈다고 완료 처리하지 않는다.** 응답이 유실되면 지시가
조용히 사라지는데, PIN 초기화는 현장에서 유일한 복구 수단이라 그러면 안 된다.
대신 기기가 체크인마다 자기 현재 상태(`contactInfo`, `hasCustomPin`)를 같이 보고하고,
서버는 그 보고를 보고 판단한다.

| 지시 | 재전송 조건 | 완료 판정 |
|---|---|---|
| `setContact` | `contact_override` ≠ 기기가 보고한 `contact` | 두 값이 같아지면 자동으로 안 보냄 |
| `resetPin` | `pin_reset = 1` | 기기가 `hasCustomPin: false` 를 보고하면 플래그 해제 |

`hasCustomPin` 을 안 보내는 구버전 앱(v1.9 이하)의 체크인은 완료 판정에 쓰지 않는다 —
값 없음을 false 로 오해하면 지시가 실행되지도 않은 채 지워진다.

## 관리자 PC에서 24시간 운영하기 (현재 방식)

당분간은 상시 켜져 있는 관리자 PC(웍스메일 자동화가 도는 PC)에서 돌린다.

### 태블릿이 이 PC에 닿게 하기

**같은 와이파이**: 그냥 된다. 앱 관리자 화면의 **"서버 자동 찾기"** 가 서브넷을 스캔해
(`/health` 가 `ok` 인 호스트) 주소를 자동으로 채운다 — 아무것도 칠 필요가 없다.
직접 칠 때도 `5` 처럼 마지막 자리만 넣으면 `http://<서브넷>.5:8090` 으로 펼쳐진다.
(v1.6 부터. 그 전엔 앱이 사설 IP 로의 평문 HTTP 를 차단해 아예 불가능했다.)

**다른 네트워크(도서관)**: 공인 HTTPS 주소가 필요하다.
공인 IP·포트포워딩 없이 해결하려면 **Cloudflare Tunnel**이 가장 간단하다(무료).
설치는 윈도우 `winget install --id Cloudflare.cloudflared`, 맥 `brew install cloudflared`.

```powershell
powershell -ExecutionPolicy Bypass -File .\start-tunnel.ps1
# 또는 직접: cloudflared tunnel --url http://localhost:8090
```

```bash
./start-tunnel.sh    # 맥/리눅스. 서버가 안 떠 있으면 502 만 나오므로 먼저 /health 를 확인한다
```

출력에 뜨는 `https://<임의문자열>.trycloudflare.com` 주소를 태블릿마다 관리자 화면
(로고 5번 탭 → PIN → 원격 관리/업데이트)에서 서버 주소에 입력한다.
**앱을 다시 설치할 필요는 없다** — 서버 주소는 런타임 설정값이다. 실기기(HA1EHJC2)로
재부팅 → 자동 체크인 → 강제 업데이트 확인창 표시까지 검증 완료.

> ⚠ 이 명령이 만드는 것은 **빠른 터널(quick tunnel)**이라 cloudflared를 재시작할 때마다
> 주소가 바뀐다. 태블릿이 이미 현장에 나간 뒤 주소가 바뀌면 원격으로 고칠 방법이 없다.
> 실제 배포 전엔 반드시 **named tunnel**(고정 서브도메인) 또는 `kiosk.dobedub.com` DNS를
> 이 서버로 향하게 하는 방식으로 전환할 것 — 후자는 앱 기본값이 이미 이 주소라 태블릿
> 쪽 설정을 아예 안 건드려도 된다.

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

2. **APK/영상 저장·배포 → S3(+CloudFront).** 지금은 컨테이너 로컬(`data/apk/`, `data/videos/`)에
   두고 앱 서버가 직접 전송한다. 운영 ECS는 **vCPU 2 / RAM 8GB 한 대로 보고팡 API 전체**를
   돌리므로, 46MB APK는 물론 수 GB짜리 영상을 태블릿 여러 대가 동시에 받으면 그 대역폭을
   서비스와 나눠 쓴다. 영상은 특히 크기 때문에 영향이 더 크다. S3에 올리고 presigned URL을
   `apkUrl`/`pushVideos[].url`로 내려보내면 앱 서버를 거치지 않는다.

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
