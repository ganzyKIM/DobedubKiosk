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
- **원격 관리**: `server/` (Node.js + SQLite). 앱이 **30분마다** 체크인 → 서버가 최신 APK
  매니페스트 + 영상 삭제/배포 지시 + (요청 시) 강제 업데이트 알림 지시를 응답.
  백오피스 대시보드 `/dashboard`(공유 비밀번호) — 기기 현황, 배포 버전 이력/롤백,
  영상 자료실(업로드 후 기기별 배포), 기기별/전체 강제 업데이트 알림.
- **무인 업데이트**: Device Owner라 `PackageInstaller`로 사용자 확인 없이 설치 가능.
  평소엔 홈 화면 유휴 상태일 때만 조용히 자동 설치(재생 중 방해 금지). 관리자가
  대시보드에서 "업데이트 알림 보내기"를 누르면 예외적으로 홈 화면에 확인창이 뜨고,
  사용자가 동의해야 설치된다(`AppUpdater.Result.NeedsConfirmation`).
- **원격 접속 경로**: 앱의 `network_security_config.xml`이 평문 HTTP를 `dobedub.com`과
  localhost 에만 허용한다. 즉 함대 서버 주소는 **HTTPS이거나 저 두 도메인**이어야 한다 —
  사설 LAN IP(`http://192.168.x.x:8090`)는 애초에 앱이 접속을 차단한다. 도서관처럼
  이 PC와 다른 망에 나가 있는 태블릿을 관리하려면 공인 HTTPS 주소가 필수다. 당장은
  `server/start-tunnel.ps1`(Cloudflare Quick Tunnel)로 임시 공인 주소를 만들어 쓰고,
  실제 배포 전엔 고정 주소(`kiosk.dobedub.com`, named tunnel 또는 실서버)로 바꿔야 한다.

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

### 마이크 입력이 너무 약함 → 앱에서 증폭·필터 (v1.3)

TB-J606F 내장 마이크가 약해 더빙 목소리가 원본 성우보다 훨씬 작게 녹음된다.
`MIC_GAIN_FIX_JS`가 `getUserMedia`를 감싸 마이크 스트림에 처리 체인을 끼운다(사이트 수정 불필요):
`하이패스 90Hz → 프레즌스 +3.5dB@3kHz → 로우패스 11kHz → 게인 +16dB → 리미터`.
HPF는 증폭 **앞**(럼블까지 커지는 걸 방지), 리미터는 **맨 뒤**(클리핑 방지). 값은 전부 상수.

**주입은 반드시 `addDocumentStartJavaScript`(androidx.webkit)로 할 것.** 사이트가 모듈
로드 시점에 `getUserMedia` 참조를 미리 바인딩해서, `onPageFinished` 주입은 이미 늦고
iframe도 못 덮는다(실기기 CDP로 확인). 적용 확인법: 사이트가 받는 트랙 라벨이
`MediaStreamAudioDestinationNode`면 체인을 타고 있는 것.

> ⚠ 마이크 레벨을 **주변 소음으로 측정하지 말 것.** 같은 설정에서도 순간 소음에 따라
> 40dB씩 널뛴다(이걸로 잘못된 결론을 냈다가 철회한 적 있음). 반드시 동일 음원으로 비교할 것.

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
| `server/start-tunnel.ps1` | 공인 HTTPS 원격 접속 경로(Cloudflare Quick Tunnel) 실행 스크립트 |
| `기획문서.md` | 최초 기획 배경 |

## 현재 상태 (2026-08-11) — v1.3 / `versionCode=8`

`versionCode`는 절대 되돌리지 말 것(업데이트 트리거 기준). versionName은 v1.0에서 한 번
리셋했고 이후 1.1→1.2→1.3으로 올라왔다.

**v1.0 이후 변경분**
- v1.1: 이용안내를 1000px 이상 내리면 마스코트가 서서히 사라지고, 올리면 다시 나타남.
  스크롤량은 `NestedScrollConnection`으로 누적(LazyColumn은 항목 높이가 달라 인덱스로 계산 불가).
- v1.2: 마스코트가 숨겨진 자리에서 이미지 스크롤이 안 먹던 버그 수정. Compose 히트테스트는
  "포인터 입력 노드가 그 자리에 있는지"로 대상을 정하므로, 투명해도 `pointerInput`이 남아
  있으면 스크롤을 가로챈다 → 숨김 중엔 모디파이어 자체를 떼도록 변경.
- v1.3: 마이크 +16dB 증폭 + 음성 명료화 필터 체인(위 "마이크 입력이 너무 약함" 참조).

**아래는 v1.0 시점 내용(그대로 유효)**

**홈/동영상 화면**
- 아동 교육앱 톤 리디자인: Jua 폰트(본문), 학교안심둥근미소 Bold(로고 타이틀·버튼 글자,
  더 굵고 눈에 띄어야 하는 곳만), Tabler 아이콘, 하늘→연두 그라데이션.
- 드래그 가능한 마스코트 "빠삐뿌" — 터치마다 표정/대사 랜덤, 드래그 중 움짤 정지.
  드래그 떨림은 좌표 계산 버그(로컬 좌표를 직접 델타 계산에 써서 이동/취소가 매 프레임
  번갈아 발생)가 근본 원인이었고 `positionChange()`로 교체해 해결(§HomeScreen.kt 주석 참조).
- 이용안내 이미지 스크롤 영역, 버튼 모두 44dp 라운드로 통일.
- 동영상 목록: 2:3 세로 썸네일 꽉 채움, 카드 확대, 제목 전문 노출.
- 상단바(동영상/웹뷰): Material 기본 대신 큰 색상 원형 버튼(KidActionButton), 닫기는 X 아이콘 통일.

**원격 관리(함대 서버 + 앱)**
- 체크인 주기 **30분**(대시보드 온라인/오프라인 임계값이 이 값에서 파생 —
  `MainActivity.UPDATE_CHECK_INTERVAL_MS` 와 `server/server.js`의 `CHECKIN_INTERVAL_MS`는
  항상 같이 바꿀 것).
- 배포 버전은 이력으로 쌓이고(`releases` 테이블) 예전 버전으로 재업로드 없이 롤백 가능.
- 영상 자료실: 업로드 후 기기별로 골라 배포, 대용량이라 체크인마다 재확인하는 큐 방식.
- 강제 업데이트 알림: 관리자가 요청한 기기만 홈 화면에 확인창이 뜨고 동의해야 설치.
- **원격 접속 경로가 실제로 해결됨**: 사설 IP는 앱의 network_security_config에 막혀
  애초에 안 되고, `server/start-tunnel.ps1`(Cloudflare Quick Tunnel)로 공인 HTTPS 주소를
  만들어 도서관처럼 다른 망에 있는 태블릿도 체크인 가능함을 실기기로 검증함
  (재부팅 → 20초 후 자동 체크인 → 확인창 → 설치까지 전 과정 확인).
- 함대 서버는 아직 상시 배포 전 — 당분간 상시 켜진 관리자 PC(웍스메일 자동화 PC)에서
  로컬 실행 + Cloudflare Tunnel로 운영 예정. 실배포 시 고정 주소(`kiosk.dobedub.com`)로 전환.

> ⚠ **Quick Tunnel 주소는 재시작마다 바뀐다.** 그때마다 태블릿 전부를 손으로 다시
> 설정해야 해서(기기가 늘수록 감당 안 됨) 이게 현재 운영의 가장 큰 마찰이다.
> 고정 주소 전환이 시급하다. 앱 기본값이 이미 `kiosk.dobedub.com`이므로, 그 DNS만
> 서버로 향하게 하면 **태블릿은 주소 칸을 비워두기만 하면 되고 이후 서버를 옮겨도
> 손댈 필요가 없다.** Route 53 레코드 추가라 인프라 담당 협조 필요.

### 사내 보고팡 인프라 이전 (예정)

사내 백엔드는 **ECS on EC2(arm64) + ECR + RDS MariaDB + S3/CloudFront**, CI는 GitHub Actions,
시크릿은 AWS Secrets Manager. 이전 대비 사전 작업은 이미 해뒀다 —
`GET /health`, `Dockerfile`(node:22-alpine 멀티스테이지), SIGTERM graceful shutdown,
설정 전면 env화(`DATA_DIR` 포함).

**이전 시점에 반드시 바꿔야 하는 것**은 ① SQLite → RDS(컨테이너는 재배포마다 디스크 초기화),
② APK 저장·전송 → S3/CloudFront(운영 ECS가 vCPU2/RAM8GB 한 대라 대용량 전송이 서비스와 경합).
상세 체크리스트와 협조 필요 항목은 `server/README.md` "보고팡 인프라(AWS)로 이전할 때" 참조.
