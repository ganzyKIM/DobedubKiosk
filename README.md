# 두비덥 도서관 키오스크 (DobedubKiosk)

Lenovo TB-J606F 태블릿을 도서관 납품용 키오스크로 잠그는 Android 앱 + 원격 관리 서버.
기획 배경/디자인 근거/리스크는 [기획문서.md](기획문서.md) 참고. 현재 버전 **1.8**
(`versionCode=13`). 버전별 변경 이력은 CLAUDE.md "현재 상태" 참고.
프로젝트 전체 컨텍스트·최근 변경사항은 [CLAUDE.md](CLAUDE.md) 참고.

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

**원클릭 세팅**: 태블릿을 USB로 연결하고 **윈도우는 `태블릿-세팅.bat`, 맥은 `태블릿-세팅.command`
를 더블클릭**하면 adb 탐색 → 연결 대기 → 계정 점검 → WebView 업데이트 → APK 설치 →
Device Owner 지정 → 샘플 동영상 투입 → 실행/검증까지 자동으로 진행된다.

납품 담당자용 상세 절차·문제 해결은 **[납품_매뉴얼.md](납품_매뉴얼.md)** 참고.
맥에서 개발할 때의 도구 설치·스크립트 대응표는 **[DEV_SETUP.md](DEV_SETUP.md) §11**.

수동 실행(옵션 제어)이 필요하면:

```powershell
powershell -ExecutionPolicy Bypass -File .\setup-tablet.ps1            # 전체
powershell -ExecutionPolicy Bypass -File .\setup-tablet.ps1 -SkipVideo # 영상 생략
```

```bash
./setup-tablet.sh                # 맥/리눅스 — 전체
./setup-tablet.sh --skip-video   # 영상 생략
```

> 핵심 제약: Device Owner 지정은 **기기에 계정이 하나도 없는 상태에서만** 성공한다.
> 이미 계정이 등록된 기기는 계정을 모두 삭제하거나 공장초기화(초기설정에서 로그인 건너뛰기)해야 한다.
> 스크립트가 계정을 자동 감지해 안내한다. (구 `provision-kiosk.ps1` 은 `setup-tablet.ps1` 로 통합됨)

## 원격 관리 / 앱 자동 업데이트 (함대 관리)

배포된 태블릿을 현장 방문 없이 일괄 업데이트·영상 배포하고 접속·버전 현황을 백오피스에서 본다.
- 앱: 30분마다 서버에 체크인 → 새 버전이면 **Device Owner 무인 설치**(`app/.../update/AppUpdater.kt`).
  관리자가 요청한 기기는 조용히 설치하지 않고 홈 화면에 확인창을 띄운다.
- 서버/백오피스: `server/` (Node.js + SQLite), 대시보드 `/dashboard` — 배포 버전 이력/롤백,
  영상 자료실(업로드 후 기기별 배포), 강제 업데이트 알림.
- 같은 와이파이면 관리자 화면의 **"서버 자동 찾기"** 가 서브넷을 스캔해 주소를 자동 입력한다.
  다른 네트워크(도서관)에 있는 태블릿은 **공인 HTTPS 주소**가 필요하다 —
  `server/start-tunnel.ps1` (윈도우) / `server/start-tunnel.sh` (맥).
- 전체 개요·서명 키 주의사항: **[원격관리_업데이트.md](원격관리_업데이트.md)**, 서버 운영: **[server/README.md](server/README.md)**

> ⚠ **서명 키**: 업데이트는 같은 키로 서명된 APK만 가능하다. `release-keystore.jks` + `keystore.properties`
> (git 제외)를 **반드시 백업**하고 빌드 PC마다 복사할 것. 분실 시 기존 기기 자동 업데이트 불가.
> 운영 빌드는 `gradlew assembleRelease`(키스토어 보유 PC)로 만든다.

## 현재 설정값

- 웹사이트 시작 URL: `https://splib.dobedub.com/home`
- 허용 도메인: `splib.dobedub.com` (서브도메인 자동 허용)
- 동영상 폴더: `/sdcard/Android/data/com.dobedub.kiosk/files/videos/` — 지원 포맷 mp4/m4v/mkv/webm (avi 미지원)
- 관리자 기본 PIN: `0000` (최초 진입 시 변경 권장)

## 제한 웹뷰 — 알려진 WebView 버그 우회

`RestrictedWebViewScreen.kt`는 보이스툰 사이트를 **데스크탑 UA**로 접속시킨다(모바일 UA로
접속하면 사이트가 태블릿에서도 좁은 모바일 레이아웃을 내려줌). 그 위에 이 기기의 WebView가
가진 두 가지 실측 버그를 `READER_HEIGHT_FIX_JS`로 우회한다 — **`dvh` 단위가 0으로 계산되는
버그**(웹툰 리더가 백지로 보임)와 **Tailwind `translate`가 `transform`과 합산되는 버그**
(마이보이스 카운트다운 숫자가 중심에서 밀림). 상세 원인·CDP 실측값은 `CLAUDE.md`와 해당
파일 상단 주석 참고.

**건드리면 안 되는 것**: 리더 폭(460px)을 CSS로 넓히려는 시도는 이미 해봤고 실패했다 —
사이트가 스크롤-오디오 싱크를 460 기준으로 하드코딩해서 폭만 넓히면 싱크가 깨진다.
근본 해결은 사이트 쪽에서 460 캡을 제거해야 한다(자세한 내용은 `CLAUDE.md` "건드리면
안 되는 것" 참조).

## 알려진 제약 / TODO

- 런처 아이콘은 브랜드 라임 그린 단색 placeholder — 실제 로고 에셋 필요.
- 함대 서버가 아직 고정 공인 주소로 상시 운영되지 않음(§원격 관리 참조) — 임시로
  Cloudflare Quick Tunnel을 쓰는 중이며, 재시작마다 주소가 바뀌어 실배포 전 전환 필요.
