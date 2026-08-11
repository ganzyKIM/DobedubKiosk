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
- **서버 주소 찾기**: 같은 와이파이면 관리자 화면의 **"서버 자동 찾기"** 버튼이 서브넷을
  스캔해(`/health`가 `ok`인 호스트) 주소를 자동 입력한다(실측 3초). 직접 칠 때도 `5` 처럼
  마지막 자리만 넣으면 `http://<서브넷>.5:8090` 으로 펼쳐진다.
  다른 망(도서관)에 나간 태블릿은 공인 HTTPS 주소가 필요하다 — `server/start-tunnel.ps1`.

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

### 마이크가 작고 지직/취익거림 → 앱에서 처리 체인 주입

TB-J606F 내장 마이크가 약해 더빙 목소리가 원본 성우보다 훨씬 작게 녹음된다.
`MIC_GAIN_FIX_JS`가 `getUserMedia`를 감싸 스트림에 체인을 끼운다(사이트 수정 불필요):

```
하이패스 90Hz → 프레즌스 +3dB@3kHz → 로우패스 8kHz
  → 프리게인 +22dB → 레벨링 컴프(-18dB/2.5:1/attack 10ms/release 350ms)
  → 메이크업 +4dB → 안전 리미터(-2dB)
```

**튜닝하며 배운 것(값 바꿀 때 반드시 참고):**
- 생게인만 올리면(30dB 시도) 리미터에 계속 처박혀 **지직거린다**. 크기는 생게인이 아니라
  **컴프레서로 평균 레벨을 올려서** 만들 것.
- 리미터 threshold를 낮게(-6dB) 두면 증폭분을 전부 압축비가 먹어 **게인이 안 산다**. -2dB.
- 컴프 attack을 1ms로 조이면 파형이 일그러진다. 10ms 이상.
- 컴프가 말 끊긴 구간에서 게인을 올려 히스가 **"취익" 하고 부푼다(펌핑)**.
  → 로우패스 8kHz + release 늘리기 + **압축비 낮추기**로 잡는다.
- **노이즈 게이트는 넣지 말 것(시도했다가 제거).** Web Audio에 게이트 노드가 없어
  "분석기로 레벨 측정 → GainNode 조절"로 만들었는데, 폴링 간격(30ms)마다 열고 닫혀
  그 전환이 그대로 들렸다 — 목소리가 커졌다 작아졌다 한다. 정밀한 게이트가 필요하면
  AudioWorklet 으로 샘플 단위 구현이 필요하고, 그만한 값어치는 없다.
- 레벨 변동이 거슬리면 **압축비를 낮추고 고정 게인 비중을 올리는 방향**이 안전하다.

**주입은 반드시 `addDocumentStartJavaScript`(androidx.webkit)로.** 사이트가 모듈 로드 때
`getUserMedia` 참조를 미리 바인딩해서 `onPageFinished` 주입은 늦고 iframe도 못 덮는다.
적용 확인: 사이트가 받는 트랙 라벨이 `MediaStreamAudioDestinationNode`면 체인 경유 중.

> ⚠ 마이크 레벨을 **주변 소음으로 측정하지 말 것.** 같은 설정에서도 순간 소음에 따라
> 40dB씩 널뛴다(이걸로 잘못된 결론을 냈다가 철회한 적 있음).

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

## 현재 상태 (2026-08-11) — v2.0.1 / `versionCode=17`

`versionCode`는 절대 되돌리지 말 것(업데이트 트리거 기준). versionName은 v1.0에서 한 번
리셋했고 이후 계속 올라간다.

**v1.0(기준선)**: 아동 교육앱 톤 리디자인(Jua + 학교안심둥근미소 Bold, Tabler 아이콘,
하늘→연두 그라데이션), 드래그 마스코트 "빠삐뿌", 44dp 라운드 통일, 동영상 목록 2:3 썸네일,
상단바 KidActionButton(닫기=X), 원격 관리 한 세트(30분 체크인 / 배포 이력·롤백 /
영상 자료실·기기별 배포 / 강제 업데이트 알림).

**이후 변경분**
- v1.1 이용안내 1000px 이상 스크롤 시 마스코트 페이드 아웃(스크롤량은 `NestedScrollConnection`
  누적 — LazyColumn은 항목 높이가 달라 인덱스로 계산 불가).
- v1.2 마스코트 숨김 자리에서 스크롤이 막히던 버그. Compose 히트테스트는 "그 자리에 포인터
  입력 노드가 있는지"로 대상을 정해, 투명해도 `pointerInput`이 남으면 가로챈다 → 숨김 중 제거.
- v1.3~v1.8 마이크 처리 체인(위 "마이크가 작고 지직/취익거림" 참조). 값은 전부 상수.
- v1.9 관리자 PIN 화면만 `BackTopBar`를 쓰지 않는다. 좌측 상단이 다른 화면의 "뒤로"와
  겹쳐, 로고 5회 탭 직후 그 자리를 눌러 튕겨 나가는 오조작이 잦았다 → 취소를 중앙 확인
  버튼 왼쪽으로. 세팅 스크립트도 자동회전/스마트회전을 끄고 세로 고정(5.5단계).
- v2.0 관리자 메뉴 재편 + 원격 기기 설정 + 위치 보고.
  - 자주 쓰는 동작을 하위 화면에서 꺼내 메뉴 첫 화면 버튼으로: 키오스크 해제/재진입/재부팅/
    완전해제(확인창)/Wi-Fi/업데이트 확인. `AdminKioskScreen`은 통째로 삭제됐다.
  - Wi-Fi 화면이 주변 네트워크를 스캔해 목록으로 띄운다. Android 10+는 **위치 권한 + 위치
    서비스**가 둘 다 켜져야 `scanResults`가 오고, 하나라도 빠지면 예외 없이 빈 목록이 와서
    "AP 없음"과 구분이 안 된다 → Device Owner 권한으로 `setPermissionGrantState` +
    `setLocationEnabled`를 스캔 직전에 직접 갖춘다.
  - 문의 연락처 기본값 `02-334-2227`. 기기에서는 읽기 전용 — 양쪽에서 고칠 수 있게 두면
    백오피스 값이 체크인마다 덮어써서 현장 수정이 계속 되돌아간다. 변경은 PC 관리자에서만.
  - PC 관리자에서 기기별 PIN 0000 원격 초기화(분실 복구). 지시 완료 판정은 **보냈을 때가
    아니라 기기가 `hasCustomPin:false`를 보고했을 때** — 자세한 규약은 `server/README.md`.
  - 설치 장소 보고(어느 도서관에 있는지 확인용). **1차 근거는 좌표가 아니라 접속 AP의
    SSID/BSSID다.** 납품 태블릿은 계정 없이 프로비저닝돼서 **Google 위치 정확도(NLP)가
    꺼진 채로 나가고**, 그러면 `network` provider가 `enabled=false`라 실내에서 좌표가
    거의 안 잡힌다(GPS만 남는데 하늘이 안 보이는 자리면 fix 불가). 실측으로 확인한 사실:
    - `settings put secure location_mode 3` 로는 안 켜진다. NLP 토글은 AOSP 설정이 아니라
      Play 서비스 내부 설정이라 adb/DPM으로 못 건드린다. 사람이 설정 화면에서 켜야 한다.
    - API 30의 `getProviders(true)` 는 **`fused` 를 반환하지 않는다**(API 31에서야 공개 상수).
      처음에 `network`/`gps` 를 하드코딩했다가 요청이 전부 GPS로만 나갔다.
    - **`requestSingleUpdate` 는 쓰지 말 것.** 30초 만에 만료된다(dumpsys 의 `expireIn=+30s0ms`).
      콜드 스타트 GPS 는 ephemeris 수신 때문에 야외에서도 30초~수 분이 걸려서, 잡힐 수 있는
      자리에서도 못 잡게 만든다. `requestLocationUpdates` + 3분 예산 + 첫 fix 시 즉시 해제로
      바꿨고, 체크인마다 요청이 쌓이지 않게 `inFlight` 플래그로 막는다(전에 3개가 겹쳐 있었다).
    - 사무실 태블릿 실측: 27시간 동안 `sv status messages 0`, `CN0 보고 0`, GNSS 소비전력
      `0.0mAh`. 약한 신호조차 없다 = 하늘이 전혀 안 보이는 자리. 다만 **"실내면 무조건 불가"는
      아니다** — 창가면 잡힌다. 자리에 따라 다르다.
    좌표 코드는 남겨뒀다 — NLP가 켜진 기기나 실외에서는 값이 붙는다. 체크인은 **마지막으로
    알던 좌표를 즉시 보내고 갱신은 백그라운드로만** 건다(위치 때문에 체크인이 지연되면 안 된다).
    값이 없는 체크인이 와도 서버는 기존 값을 지우지 않는다(`COALESCE`).
  - ⚠ `WifiHelper`/`DevicePolicyManager` 호출은 전부 **binder IPC** 라 메인 스레드에서
    부르면 화면이 몇 초씩 멈춘다(실제로 Wi-Fi 화면이 ANR 직전까지 갔다). 공개 함수를 전부
    `suspend` + `Dispatchers.IO` 로 바꿔 호출부가 실수할 수 없게 막았다. `setLocationEnabled`
    같은 준비 작업은 프로세스당 1회만(`AtomicBoolean`).
- v1.6 서버 주소 자동 찾기 + 축약 입력, 배포 이력 10개 페이지네이션.
  이때 `network_security_config`를 **평문 HTTP 전면 허용**으로 바꿨다 — 사설 IP 대역
  와일드카드를 지원하지 않아 선별 허용이 불가능. 웹뷰는 코드로 도메인 화이트리스트가
  강제되고 그 사이트들은 HTTPS라 실질 위험은 제한적. 공인 주소 전환 시 재검토할 것.

**운영 현황**: 함대 서버는 관리자 PC 로컬 실행. 태블릿 3대 등록
(voicetoonlib3 1대 = 개발/테스트용, jbegec 2대). 같은 와이파이면 LAN IP로 직접 통신 가능.

> ⚠ **Quick Tunnel 주소는 재시작마다 바뀐다.** 다른 망의 태블릿을 관리하려면 매번 전 기기를
> 다시 설정해야 해서 이게 현재 운영의 가장 큰 마찰이다. 해법은 **Cloudflare 명명 터널 +
> 고정 도메인**(도메인 연 1~2만원, 터널 무료, 대역폭 무제한). 사용자가 도메인 준비 시 진행 예정.
> ngrok 무료는 월 1GB라 영상 배포에 못 쓰고, localtunnel은 태블릿에서 HTTP 511(인증 안내
> 페이지)로 막혀 실패했다.

### 사내 보고팡 인프라 이전 (예정)

사내 백엔드는 **ECS on EC2(arm64) + ECR + RDS MariaDB + S3/CloudFront**, CI는 GitHub Actions,
시크릿은 AWS Secrets Manager. 이전 대비 사전 작업은 이미 해뒀다 —
`GET /health`, `Dockerfile`(node:22-alpine 멀티스테이지), SIGTERM graceful shutdown,
설정 전면 env화(`DATA_DIR` 포함).

**이전 시점에 반드시 바꿔야 하는 것**은 ① SQLite → RDS(컨테이너는 재배포마다 디스크 초기화),
② APK 저장·전송 → S3/CloudFront(운영 ECS가 vCPU2/RAM8GB 한 대라 대용량 전송이 서비스와 경합).
상세 체크리스트와 협조 필요 항목은 `server/README.md` "보고팡 인프라(AWS)로 이전할 때" 참조.
