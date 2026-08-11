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
하이패스 90Hz → 프레즌스 +3dB@3kHz → 로우패스 8kHz → 노이즈게이트(-50dB)
  → 프리게인 +18dB → 레벨링 컴프(-20dB/3.5:1/attack 10ms/release 350ms)
  → 메이크업 +6dB → 안전 리미터(-2dB)
```

**튜닝하며 배운 것(값 바꿀 때 반드시 참고):**
- 생게인만 올리면(30dB 시도) 리미터에 계속 처박혀 **지직거린다**. 크기는 생게인이 아니라
  **컴프레서로 평균 레벨을 올려서** 만들 것.
- 리미터 threshold를 낮게(-6dB) 두면 증폭분을 전부 압축비가 먹어 **게인이 안 산다**. -2dB.
- 컴프 attack을 1ms로 조이면 파형이 일그러진다. 10ms 이상.
- 컴프가 말 끊긴 구간에서 게인을 올려 히스가 **"취익" 하고 부푼다(펌핑)**.
  → 노이즈 게이트 + 로우패스 8kHz + release 늘리기로 잡는다.
- Web Audio에 게이트 노드가 없어 "분석기로 레벨 측정 → GainNode 조절"로 직접 만들었다.

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

## 현재 상태 (2026-08-11) — v1.7 / `versionCode=12`

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
- v1.3~v1.7 마이크 처리 체인(위 "마이크가 작고 지직/취익거림" 참조). 값은 전부 상수.
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
