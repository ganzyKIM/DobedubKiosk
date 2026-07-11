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

## 6. git 관리 밖에 있는 것들 (별도로 옮겨야 함)

- 샘플 동영상(약 2.4GB, `../[두비덥 보이스툰] ...` 폴더) — 용량 때문에 git 제외.
  코드만 개발할 땐 없어도 됨(동영상 목록이 비어 보일 뿐). 실제 재생 테스트 시 필요.
- `local.properties`, 빌드 산출물(`app/build/`), APK — 모두 gitignore.
