# 두비덥 도서관 키오스크 (DobedubKiosk)

Lenovo TB-J606F 태블릿을 도서관 납품용 키오스크로 잠그는 Android 앱.
기획 배경/디자인 근거/리스크는 [기획문서.md](기획문서.md) 참고.

## 빌드

```
./gradlew assembleDebug
```

JDK 17 필요. Android Studio 내장 JDK를 쓰려면:

```
JAVA_HOME="/path/to/Android Studio/jbr" ./gradlew assembleDebug
```

## 단위 테스트

```
./gradlew testDebugUnitTest
```

- `PinHasherTest` — 관리자 PIN 해시/검증 로직
- `DomainWhitelistTest` — 제한 웹뷰 화이트리스트 판정 로직 (서브도메인 허용, 우회 시도 차단, http/https 외 스킴 차단)

## 태블릿 프로비저닝 (소량 납품 기준)

**원클릭 세팅**: 태블릿을 USB로 연결하고 **`태블릿-세팅.bat` 을 더블클릭**하면
adb 탐색 → 연결 대기 → 계정 점검 → WebView 업데이트 → APK 설치 → Device Owner 지정 →
샘플 동영상 투입 → 실행/검증까지 자동으로 진행된다.

납품 담당자용 상세 절차·문제 해결은 **[납품_매뉴얼.md](납품_매뉴얼.md)** 참고.

수동 실행(옵션 제어)이 필요하면:

```powershell
powershell -ExecutionPolicy Bypass -File .\setup-tablet.ps1            # 전체
powershell -ExecutionPolicy Bypass -File .\setup-tablet.ps1 -SkipVideo # 영상 생략
```

> 핵심 제약: Device Owner 지정은 **기기에 계정이 하나도 없는 상태에서만** 성공한다.
> 이미 계정이 등록된 기기는 계정을 모두 삭제하거나 공장초기화(초기설정에서 로그인 건너뛰기)해야 한다.
> 스크립트가 계정을 자동 감지해 안내한다. (구 `provision-kiosk.ps1` 은 `setup-tablet.ps1` 로 통합됨)

## 현재 설정값

- 웹사이트 시작 URL: `https://splib.dobedub.com/home`
- 허용 도메인: `splib.dobedub.com` (서브도메인 자동 허용)
- 동영상 폴더: `/sdcard/Android/data/com.dobedub.kiosk/files/videos/` — 지원 포맷 mp4/m4v/mkv/webm (avi 미지원)
- 관리자 기본 PIN: `0000` (최초 진입 시 변경 권장)

## 제한 웹뷰 — 웹툰 리더 데스크탑 렌더링/싱크 보정

`RestrictedWebViewScreen.kt`는 보이스툰 사이트를 **데스크탑 UA**로 접속시킨다(모바일 UA로
접속하면 사이트가 태블릿에서도 좁은 모바일 레이아웃을 내려줌). 다만 데스크탑 UA로 받아도
웹툰 리더는 그림 컬럼을 460px로 캡하고 중앙정렬해서 넓은 화면 가운데 좁게 나오므로,
`TOON_FIT_JS`가 리더 페이지에 진입할 때 다음 두 가지를 자동 보정한다(태블릿 실기기 CDP로
직접 검증됨):

1. **좌우 꽉 채우기**: 컬럼 체인(`.viewer-layout`, `.toon-scroll-layer`, `.toon-image` 등)의
   460 폭 캡·중앙정렬을 CSS로 풀어 실제 레이아웃 폭을 화면 폭(`100vw`)으로 넓힌다.
   (처음엔 `transform: scale`로 시도했으나 WebView가 overflow-scroll 요소의 확대를 실제
   합성 픽셀에 반영하지 않아 좌우가 잘리는 문제가 있어 폐기 — 실제 레이아웃 폭 변경 방식으로 교체.)
2. **오디오-스크롤 싱크 보정**: 컬럼을 넓히면 콘텐츠 실제 높이가 `S = 화면폭/460` 배 길어지는데,
   사이트는 여전히 모바일 기준(460) 좌표계로 스크롤량을 계산해서 그대로 두면 스크롤이 S배
   부족하게 움직인다. `.toon-scroll-layer`의 `scrollTop`/`scrollHeight`/`clientHeight`/`scrollTo`를
   가로채, 사이트에게는 항상 460 좌표계 값(÷S)을 보여주고 실제 픽셀에는 ×S 해서 적용하는
   어댑터를 설치해 해결했다.

리더가 아닌 페이지에서는 대상 셀렉터가 없어 무효과. 자세한 배경은 파일 상단 주석 참고.
**미해결**: 이 보정은 현재 관찰되는 사이트 DOM 구조(`.toon-scroll-layer`, 460 기준)에
의존하므로, 사이트 쪽 프론트엔드가 리뉴얼되면 다시 확인 필요.

## 알려진 제약 / TODO

- Pretendard 폰트 파일 미포함 — 현재 시스템 SansSerif로 대체 렌더링됨. `app/src/main/res/font/`에 폰트 추가 후 `ui/theme/Type.kt`의 `PretendardFamily` 교체 필요.
- 런처 아이콘은 브랜드 라임 그린 단색 placeholder — 실제 로고 에셋 필요.
- 백오피스 연동(원격 동영상 배포, 설정 원격 변경)은 후속 범위.
