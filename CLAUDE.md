# 두비덥 도서관 키오스크 — 프로젝트 컨텍스트

Lenovo TB-J606F 태블릿을 **도서관 납품용 키오스크**로 잠그는 Android 앱 + 원격 관리 서버.
새 세션/새 PC에서 작업을 이어받을 때 이 문서를 먼저 읽을 것.

## 무엇을 하는 앱인가

태블릿을 딱 3가지 기능만 되는 키오스크로 만든다 (홈 화면의 가로 3버튼):
1. **동영상 보기** — 기기에 넣어둔 mp4 재생 (ExoPlayer)
2. **도서관 웹사이트** — `https://<서브도메인>.dobedub.com/home` 제한 웹뷰
3. **마이보이스** — 같은 사이트의 `/my-voice` (아이가 직접 더빙)

홈 아래에는 이용안내 이미지(`drawable-nodpi/user_manual_*.png`, 원본을 12타일로 분할)가
스크롤된다. 헤더+3버튼은 고정, 이미지 영역만 스크롤.

숨은 관리자: 홈 제목 **5회 탭 → PIN(기본 `0000`)**.

## 아키텍처 핵심

- **잠금**: Device Owner + Lock Task. `KioskManager`가 담당. `DISALLOW_DEBUGGING_FEATURES`는
  일부러 제외(유지보수용 adb 유지).
- **원격 관리**: `server/` (Node.js + SQLite). 앱이 6시간마다 체크인 → 서버가 최신 APK
  매니페스트 + 영상 삭제 지시를 응답. 백오피스 대시보드 `/dashboard`(공유 비밀번호).
- **무인 업데이트**: Device Owner라 `PackageInstaller`로 사용자 확인 없이 설치 가능.
  홈 화면 유휴 상태일 때만 설치(재생 중 방해 금지).

## 절대 잃으면 안 되는 것

`release-keystore.jks` + `keystore.properties` (git 제외, 별도 백업 필수).
**분실 시 이미 배포된 태블릿을 다시는 업데이트할 수 없다** — 기기마다 공장초기화 + 재설치가
필요해진다. 안드로이드는 같은 키로 서명된 APK만 덮어쓰기를 허용한다.

## 빌드

```bash
# JAVA_HOME은 JDK 17 (Android Studio 내장 jbr도 가능)
./gradlew assembleRelease      # 납품/배포용 (키스토어 필요)
./gradlew testDebugUnitTest    # 단위 테스트
```

`-PfleetServerUrl=http://localhost:8090` 로 함대 서버 주소 재정의 가능(테스트용).
단, `-P` 변경은 Gradle이 입력으로 안 잡아 UP-TO-DATE로 넘길 수 있으니 `clean`을 붙일 것.

## 태블릿 세팅

`태블릿-세팅.bat` 더블클릭 → adb 탐색 → 연결 대기 → 계정 점검 → 도서관 주소 입력 →
WebView 업데이트 → APK 설치 → Device Owner → 블로트웨어 정리 → 영상 투입 → 검증.
상세: `납품_매뉴얼.md`.

**제약**: Device Owner 지정은 기기에 **계정이 하나도 없어야** 성공. 스크립트가 감지해 안내한다.

## 이 WebView(Chromium 150)의 알려진 버그와 우회

`RestrictedWebViewScreen.kt`의 `READER_HEIGHT_FIX_JS`가 처리한다. 실기기 CDP로 실측한 사실:

- **`dvh` 단위가 0으로 계산됨** (`100dvh`=0, `vh`/`svh`/`lvh`는 정상). 사이트 리더 루트
  `.viewer-layout`이 `height:100dvh`라 높이가 붕괴 → 웹툰이 백지. → `vh`로 강제.
- **Tailwind `-translate-x-1/2`가 무효화됨**(computed `transform:none`). 더빙 카운트다운
  숫자(`countdown-N.svg`)가 우측으로 밀림. → `left:50% + translateX(-50%)` 강제.

## 건드리면 안 되는 것 (실패한 시도들)

**보이스툰 뷰어 폭을 CSS로 넓히지 말 것.** 사이트가 리더 폭을 460px로 하드코딩하고
스크롤-오디오 싱크를 그 460 기준으로 계산한다(`getPositionAtTimeV1`). 폭만 넓히면 싱크가
깨지고, `transform:scale`은 WebView가 스크롤 영역 안을 확대 렌더링하지 않아 잘린다.
폭 확대는 **사이트 쪽에서 460 캡을 제거**해야 근본 해결된다(마이보이스는 이미 100%라 정상).

## 자주 밟는 함정

- Device Owner 앱은 `adb shell am force-stop`이 **무시된다**("Ignoring request to force stop
  protected package"). 재시작하려면 `install -r` 또는 재부팅.
- `ANDROID_ID`는 서명키별로 달라서 `adb shell settings get secure android_id` 값과 앱이
  보고하는 deviceId가 다르다. 백오피스는 앱 보고 값을 쓴다.
- git-bash에서 adb에 기기 경로 넘길 땐 `MSYS_NO_PATHCONV=1`. 한글/대괄호 로컬 경로는
  8.3 단축경로(PowerShell `Scripting.FileSystemObject`)로.
- PowerShell에서 매칭 줄이 1개면 스칼라가 되어 `[0]`이 첫 '글자'를 집는다 → `@()`로 배열 강제.

## 문서 지도

| 문서 | 내용 |
|---|---|
| `README.md` | 앱 개요, 프로비저닝, 현재 설정값 |
| `DEV_SETUP.md` | 개발 환경 재현(새 PC 세팅) |
| `납품_매뉴얼.md` | 납품 담당자용 태블릿 세팅 절차 |
| `원격관리_업데이트.md` | 자동 업데이트 + 백오피스 개요, 서명 키 경고 |
| `server/README.md` | 함대 서버 실행·배포·API |
| `기획문서.md` | 최초 기획 배경 |

## 현재 상태 (2026-08-07)

- 앱 `versionCode=3` / `versionName=1.2`
- 홈·동영상 화면은 아동 교육앱 톤으로 리디자인됨(Jua 폰트, Tabler 아이콘,
  하늘→연두 그라데이션, 드래그 가능한 마스코트 "빠삐뿌").
- 기기 체크인 주기 **30분**. 대시보드의 온라인/오프라인 임계값은 이 주기에서 파생되므로
  `MainActivity.UPDATE_CHECK_INTERVAL_MS` 와 `server/server.js` 의 `CHECKIN_INTERVAL_MS` 를
  **항상 같이** 바꿀 것(어긋나면 정상 기기가 "대기"로 표시됨).
- 함대 서버 **아직 미배포**. 당분간 상시 켜진 관리자 PC(웍스메일 자동화 PC)에서 로컬 운영 예정.
  앱 기본 주소는 `https://kiosk.dobedub.com`(미가동)이지만 **서버 주소는 런타임 설정값**이라
  관리자 화면(로고 5번 탭 → 업데이트)에서 바꾸면 되고 재설치가 필요 없다.
  로컬 검증은 `-PfleetServerUrl=http://localhost:8090` + `adb reverse tcp:8090 tcp:8090`.
- 백오피스에는 그 서버로 체크인한 기기만 뜬다.

### 사내 보고팡 인프라 이전 (예정)

사내 백엔드는 **ECS on EC2(arm64) + ECR + RDS MariaDB + S3/CloudFront**, CI는 GitHub Actions,
시크릿은 AWS Secrets Manager. 이전 대비 사전 작업은 이미 해뒀다 —
`GET /health`, `Dockerfile`(node:22-alpine 멀티스테이지), SIGTERM graceful shutdown,
설정 전면 env화(`DATA_DIR` 포함).

**이전 시점에 반드시 바꿔야 하는 것**은 ① SQLite → RDS(컨테이너는 재배포마다 디스크 초기화),
② APK 저장·전송 → S3/CloudFront(운영 ECS가 vCPU2/RAM8GB 한 대라 대용량 전송이 서비스와 경합).
상세 체크리스트와 협조 필요 항목은 `server/README.md` "보고팡 인프라(AWS)로 이전할 때" 참조.
