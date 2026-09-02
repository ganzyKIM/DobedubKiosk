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
| `netbird-setup-key.txt` | 태블릿 세팅 시 | NetBird 대시보드(Settings > Setup Keys)에서 발급해 직접 작성. **커밋 금지(gitignore)** |
| `netbird-*.apk` + `ADBKeyboard.apk` | 태블릿 세팅 시 | github netbirdio/android-client 릴리스 / senzhk/ADBKeyBoard. `*.apk` 라 커밋 안 됨 |
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

### 폴더를 통째로 복사해 관리자 PC로 옮길 때

git clone이 아니라 `DobedubKiosk` 폴더를 그대로 복사해 옮기는 경우, 아래만 주의하면 된다.

1. **`server/node_modules`를 지우고 다시 설치한다.** `better-sqlite3`는 네이티브 모듈이라
   Node 버전이나 CPU 아키텍처가 다르면 로드에 실패한다.
   ```powershell
   cd server; Remove-Item -Recurse -Force node_modules; npm ci
   ```
2. **`local.properties`는 다시 쓴다** — 복사본은 이전 PC의 Android SDK 경로를 가리킨다(§3).
3. **`app/build/`는 지워도 된다** — 다시 빌드하면 생긴다.
4. **`release-keystore.jks` / `keystore.properties`는 복사본에 그대로 따라온다.**
   서명키라 편하긴 하지만 그만큼 유출에 주의할 것. 분실 시 배포된 태블릿 업데이트가 영구
   불가라는 점은 그대로다(§8).
5. **`server/data/`도 따라온다** — 기존 기기 이력과 업로드된 APK가 그대로 유지된다.
   새로 시작하고 싶으면 지우면 자동 재생성된다.

관리자 PC에서 24시간 상시 구동(Windows 서비스 등록), 그리고 **도서관에 나가 있는 태블릿이
사내 PC에 접속하게 하는 방법(Cloudflare Tunnel)** 은 `server/README.md`의
"관리자 PC에서 24시간 운영하기"에 정리해두었다. 태블릿은 앱 재설치 없이 관리자 화면에서
서버 주소만 바꾸면 된다.

## 10. 맥에서 개발하기

윈도우 마스터 PC는 그대로 두고 맥에서 개발을 이어갈 때. **납품은 계속 윈도우에서 하므로
`.bat`/`.ps1` 은 지우지 않고 그대로 둔다** — 같은 일을 하는 맥용 스크립트를 나란히 둔 것이다.

### 도구 설치 (Apple Silicon 기준, 실측)

```bash
brew install openjdk@17 node@22 cloudflared
brew install --cask android-commandlinetools android-platform-tools
sdkmanager --sdk_root="$HOME/Library/Android/sdk" "platform-tools" "platforms;android-34" "build-tools;34.0.0"
```

`openjdk@17` 과 `node@22` 는 **keg-only** 라 PATH 에 자동으로 안 걸린다. `~/.zprofile` 에 넣는다:

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17"
export PATH="$JAVA_HOME/bin:/opt/homebrew/opt/node@22/bin:$PATH"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
```

> `~/.zprofile` 은 **로그인 셸에서만** 읽힌다. VS Code 내장 터미널 등 비로그인 셸에서
> `command not found` 가 나므로 `~/.zshrc` 에서 `source ~/.zprofile` 을 한 번 더 해준다.

`local.properties` 는 §3 대로 이 머신 경로로 새로 쓴다(`sdk.dir=/Users/<계정>/Library/Android/sdk`).

### 스크립트 대응표

| 하는 일 | 윈도우 | 맥 |
|---|---|---|
| 태블릿 원클릭 세팅 | `태블릿-세팅.bat` → `setup-tablet.ps1` | `태블릿-세팅.command` → `setup-tablet.sh` |
| 함대 서버 실행 | `관리자-실행.bat` → `start-admin.ps1` | `관리자-실행.command` → `start-admin.sh` |
| 함대 서버 종료 | `관리자-종료.bat` | `관리자-종료.command` (`./start-admin.sh --stop`) |
| 공인 HTTPS 터널 | `server/start-tunnel.ps1` | `server/start-tunnel.sh` |

옵션 이름만 관례에 맞게 바뀌었다(`-SkipVideo` → `--skip-video`). 동작·판정·출력은 같게 맞췄고,
블로트웨어 목록은 양쪽 33개가 동일하다. **한쪽을 고치면 반대쪽도 같이 고칠 것.**

`.command` 는 맥에서 **Finder 더블클릭으로 실행되는 셸 스크립트**다(.bat 더블클릭 UX 대응).
git 에서 실행권한이 빠지면 더블클릭이 안 되므로 `chmod +x *.command *.sh` 로 되살린다.

### 태블릿을 맥에 붙이기

1. USB 연결 → 태블릿 화면의 "USB 디버깅 허용" 승인 (키오스크 잠금 상태면 관리자 메뉴에서
   **키오스크 모드 해제** 후에야 팝업이 뜬다 — §7 참고).
2. `adb devices` 로 `device` 상태 확인.
3. 함대 서버는 `./start-admin.sh` 로 띄운다. 실행하면 **태블릿에 입력할 LAN 주소**
   (`http://<맥 IP>:8090`)를 같이 찍어준다.
4. 같은 와이파이면 태블릿 관리자 화면의 **"서버 자동 찾기"** 가 서브넷을 스캔해 알아서 찾는다.
   서버는 `0.0.0.0` 에 바인딩되므로 LAN 에서 그대로 닿는다.

> 맥 방화벽이 켜져 있으면 태블릿에서 들어오는 접속이 막힐 수 있다.
> `/usr/libexec/ApplicationFirewall/socketfilterfw --getglobalstate` 로 확인한다.

### 맥에서 밟는 함정

- **`gradlew` 실행권한**: 윈도우에서 만들어져 `100644` 로 커밋돼 있었다(현재는 `100755` 로 수정됨).
  다시 빠지면 `chmod +x gradlew`.
- **bash 3.2**: 맥 기본 `/bin/bash` 는 3.2 라 연관배열·`mapfile` 이 없다. 맥용 스크립트는
  이 제약에 맞춰 작성돼 있으니 4.x 문법을 넣지 말 것.
- **`timeout` 명령이 없다**: coreutils 전용. `perl -e 'alarm N; exec @ARGV' <명령>` 으로 대체.
- **`stat`/`date` 옵션이 GNU 와 다르다**: BSD 는 `stat -f %z`, `date -r`. 스크립트에 양쪽을
  시도하는 헬퍼(`file_size`/`file_mtime`/`fmt_time`)를 넣어두었다.
- **디버그 서명 불일치**(§5): 맥에서 빌드한 디버그 APK 는 윈도우 PC 의 debug keystore 와
  달라 덮어설치가 안 된다. 릴리스 키스토어(`release-keystore.jks`)를 USB 로 옮겨와
  `assembleRelease` 로 빌드하면 이 문제가 없다.
