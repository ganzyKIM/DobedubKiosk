# 개발 환경 세팅 (새 PC에서 이어 작업할 때)

다른 PC(집/회사)에서 이 프로젝트를 이어서 빌드·설치하려면 아래를 갖춰야 한다.
README.md는 앱/납품 절차, 이 문서는 **개발 머신 환경 재현**에 초점.

## 1. 필요한 도구

| 도구 | 버전 | 비고 |
|---|---|---|
| JDK | 17 (Temurin/OpenJDK) | Gradle 8.7 + AGP 8.5.1 요구 |
| Android SDK | platform-tools, `platforms;android-34`, `build-tools;34.0.0` | compileSdk/targetSdk 34 |
| Git | 최신 | |

### Windows에서 winget으로 설치 예시

```powershell
winget install --id EclipseAdoptium.Temurin.17.JDK -e --source winget
# Android SDK는 commandlinetools zip을 받아 <SDK>\cmdline-tools\latest\ 에 풀고 sdkmanager로 설치
```

Android SDK 컴포넌트 설치:

```bash
sdkmanager --sdk_root="<SDK경로>" "platform-tools" "platforms;android-34" "build-tools;34.0.0"
```

## 2. 환경 변수 (영구 설정)

```
JAVA_HOME = <Temurin JDK 17 설치 경로>   예: C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot
ANDROID_HOME = <Android SDK 경로>        예: C:\Android\Sdk
```

## 3. local.properties (git 제외 대상 — 머신마다 직접 작성)

프로젝트 루트에 `local.properties` 생성, SDK 경로를 이 머신 기준으로:

```
sdk.dir=C\:\\Android\\Sdk
```

`.gitignore`에 포함되어 있어 커밋되지 않는다. **새 PC마다 직접 만들어야 한다.**

## 4. 회사망(프록시/보안 SSL 검사) 환경에서의 인증서 문제

회사 PC는 보안 소프트웨어가 TLS를 가로채서, Java 기본 truststore로는
`dl.google.com` / `services.gradle.org` 다운로드가
`PKIX path building failed`로 실패한다. Windows 인증서 저장소를 쓰게 하면 해결:

- **sdkmanager**: 환경변수 `SDKMANAGER_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT"`
- **gradle**: 환경변수 `GRADLE_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT"`
  (또는 `gradle.properties`에 `org.gradle.jvmargs=... -Djavax.net.ssl.trustStoreType=Windows-ROOT`)
- **curl 다운로드** 시: `curl --ssl-no-revoke ...`

집 PC(일반 네트워크)에서는 대개 불필요.

## 5. 빌드 / 설치 / 프로비저닝

```bash
./gradlew assembleDebug          # → app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # 단위 테스트
```

태블릿 배포는 README.md의 프로비저닝 절차 참고. 주의점:

- **디버그 서명 불일치**: PC마다 debug keystore가 다르므로, 다른 PC에서 빌드한 APK를
  기존 설치본 위에 덮어설치하면 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`이 난다.
  → 관리자 메뉴에서 **Device Owner 완전 해제** 후 `adb uninstall` → 새 APK 설치 →
  `provision-kiosk.ps1` 재실행으로 처음부터 다시 프로비저닝해야 한다.
  (매번 재프로비저닝을 피하려면 공용 debug keystore를 팀이 공유하는 방법도 있음 — 미도입)
- **provision-kiosk.ps1의 샘플 동영상 자동 push**: Windows PowerShell 5.1이 BOM 없는
  UTF-8 스크립트의 한글 경로를 깨서 영상 파일을 못 찾을 수 있다. 그 경우 수동으로:

  ```bash
  adb shell mkdir -p "/sdcard/Android/data/com.dobedub.kiosk/files/videos"
  adb push "<영상.mp4>" "/sdcard/Android/data/com.dobedub.kiosk/files/videos/<영상.mp4>"
  ```

## 6. 원격 저장소 / 다른 PC에서 이어 작업하기

- 원격: **GitHub private repo** `https://github.com/ganzyKIM/DobedubKiosk` (기본 브랜치 `master`)
- 집 PC에서 처음 받을 때:

  ```bash
  gh auth login            # 또는 git 자격증명으로 clone
  git clone https://github.com/ganzyKIM/DobedubKiosk.git
  # 이후 §1~3(도구/환경변수/local.properties) 세팅
  ```

- 작업 흐름:

  ```bash
  git pull        # 작업 시작 전 최신 받기
  # ... 작업 ...
  git add -A && git commit -m "..." && git push
  ```

- **회사 PC git SSL 문제**: 회사망 보안 SSL 검사 때문에 git push/clone이
  `SSL certificate problem: unable to get local issuer certificate`로 실패한다.
  Git이 Windows 인증서 저장소를 쓰게 하면 해결(회사 PC에서 1회 설정):

  ```bash
  git config --global http.sslBackend schannel
  ```

  집 PC(일반 네트워크)에서는 불필요.

- **월요일 회사 복귀 시**: 회사 PC에서 `git pull` 먼저. (이 회사 PC엔 이미 origin이
  연결돼 있고 schannel 설정도 돼 있음.) 집에서 push한 커밋을 받아 이어서 작업.

## 7. 태블릿(키오스크 잠금 상태) 트러블슈팅

이 테스트 기기(adb 시리얼 `HA1EHGAS`)는 이미 Device Owner + Lock Task로 프로비저닝되어
있어 일반 개발 중에도 아래 상황을 자주 만난다.

- **adb devices가 `unauthorized`로 계속 나옴**: 키오스크(Lock Task) 상태에서는 USB 디버깅
  허용 팝업이 화면에 안 뜬다. 홈 화면 로고 **5회 탭 → PIN(기본 `0000`)** → 관리자 메뉴 →
  **"키오스크 관리 > 키오스크 모드 해제"** 실행 후 팝업이 뜨면 허용. 확인 후 다시 잠가도 무방.
- **`adb shell am force-stop`으로 앱을 껐더니 흰 화면에서 멈춤**: Lock Task가 앱을 즉시
  재기동시키는 과정에서 렌더링이 걸리는 경우가 있었다. `adb reboot`로 재부팅하면 BOOT_COMPLETED
  리시버가 자동으로 키오스크 홈을 정상 복귀시킨다(README §프로비저닝 참고). 앱만 다시 실행하려면
  `am force-stop` 대신 `am start -n com.dobedub.kiosk/.MainActivity`를 먼저 시도.
- **화면이 세로 전체가 아니라 좁게(양옆 검은 여백) 나옴**: 재부팅 직후 일시적 현상이었고
  몇 초 후 정상화됨. 계속되면 `adb shell wm size`로 해상도 확인.
- **웹뷰 디버깅(CDP)**: `adb shell cat /proc/net/unix | grep webview_devtools_remote_`로 소켓
  이름을 찾고 `adb forward tcp:9222 localabstract:<소켓이름>` 후 `http://localhost:9222/json/list`.
  SPA 라우팅이라 페이지 이동 시 page id가 안 바뀌는 경우가 많으니 재진입 시마다 목록을
  다시 확인할 것.

## 8. git 관리 밖에 있는 것들 (USB/보안 채널로 별도 이동)

`git clone`만으로는 부족하다. 아래는 리포에 없으니 직접 옮기거나 새로 만들어야 한다.

| 대상 | 필수? | 어떻게 |
|---|---|---|
| `release-keystore.jks` + `keystore.properties` | **필수** | 기존 PC에서 복사. **분실 시 배포된 태블릿 업데이트 영구 불가** |
| `local.properties` | 필수 | 새 PC에서 직접 작성(§3) — 복사하면 SDK 경로가 안 맞음 |
| 샘플 동영상 (`../[두비덥 보이스툰] ...`, 약 3.4GB) | 재생 테스트 시 | USB 복사. 코드 개발만 할 땐 없어도 됨 |
| Android System WebView APK | 태블릿 세팅 시 | 현재 폴더에 없음. 필요하면 APKMirror에서 다시 받아 리포 **상위 폴더**에 둔다 |
| `userManual.png` 원본 | 매뉴얼 교체 시만 | 이미 타일(`app/src/main/res/drawable-nodpi/user_manual_*.png`)로 리포에 커밋됨 |
| `server/data/` (기기 DB + 업로드 APK) | 운영 서버만 | 로컬 테스트용이면 안 옮겨도 됨(자동 생성) |
| 빌드 산출물(`app/build/`, `*.apk`) | 아니오 | 새 PC에서 다시 빌드 |

## 9. 함대 서버(백오피스) 실행에 필요한 것

`server/`는 Android와 별개로 **Node.js 18+** 만 있으면 된다(파이썬 불필요).

```powershell
winget install -e --id OpenJS.NodeJS.LTS
cd server
npm install
$env:ADMIN_PASSWORD="바꾸세요"; $env:SESSION_SECRET="랜덤문자열"; npm start
# → http://localhost:8090/dashboard
```

`node_modules/`와 `data/`는 gitignore이므로 새 PC에서 `npm install`로 새로 만든다.
운영 배포(공개 HTTPS)는 `server/README.md` 참고.

## 10. 새 PC에서 Claude Code로 작업 이어가기

대화 기록은 PC 간에 옮겨지지 않는다. 대신 **`CLAUDE.md`가 프로젝트 컨텍스트를 대신한다** —
Claude Code가 세션 시작 시 자동으로 읽으므로, 리포를 clone한 폴더에서 `claude`를 실행하면
프로젝트 구조·제약·과거에 실패한 시도까지 파악한 상태로 시작한다.

```powershell
winget install -e --id Anthropic.ClaudeCode   # 또는 npm i -g @anthropic-ai/claude-code
cd <clone한 폴더>
claude
```

첫 지시 예: "CLAUDE.md 읽고 현재 상태 파악해줘. 함대 서버를 실제로 배포하려고 한다."

과거 결정의 근거가 더 필요하면 `git log`를 보면 된다 — 커밋 메시지에 증상·원인·검증 결과를
남겨두었다.
