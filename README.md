# 두비덥 도서관 키오스크 (DobedubKiosk)

Lenovo TB-J606F 태블릿을 도서관 납품용 키오스크로 잠그는 Android 앱.
기획 배경/디자인 근거/리스크는 [../기획문서.md](../기획문서.md) 참고.

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

1. 태블릿 공장초기화 → 설정 마법사에서 **계정 로그인 화면을 건너뛴 상태**로 둔다 (Wi-Fi 건너뛰기 또는 비행기 모드).
2. USB로 PC와 연결, USB 디버깅 허용.
3. 저장소 루트에서 실행:

   ```powershell
   ./provision-kiosk.ps1
   ```

   내부적으로 다음을 수행한다: APK 설치 → `adb shell dpm set-device-owner com.dobedub.kiosk/.kiosk.AdminReceiver` → 샘플 동영상 push.

4. 앱 최초 실행 → 홈 화면 로고 5회 탭 → 기본 PIN `0000` 으로 관리자 진입 → PIN 변경, 시작 URL/허용 도메인 확인.
5. "키오스크 관리 > 키오스크 모드 재진입" 없이도 최초 실행 시 자동으로 Lock Task가 걸린다.

Device Owner 지정은 **기기에 구글 계정이 하나도 없는 상태에서만** 성공한다. 이미 계정이 등록된 기기는 공장초기화부터 다시 해야 한다.

## 현재 설정값

- 웹사이트 시작 URL: `https://splib.dobedub.com/home`
- 허용 도메인: `splib.dobedub.com` (서브도메인 자동 허용)
- 동영상 폴더: `/sdcard/Android/data/com.dobedub.kiosk/files/videos/` — 지원 포맷 mp4/m4v/mkv/webm (avi 미지원)
- 관리자 기본 PIN: `0000` (최초 진입 시 변경 권장)

## 알려진 제약 / TODO

- Pretendard 폰트 파일 미포함 — 현재 시스템 SansSerif로 대체 렌더링됨. `app/src/main/res/font/`에 폰트 추가 후 `ui/theme/Type.kt`의 `PretendardFamily` 교체 필요.
- 런처 아이콘은 브랜드 라임 그린 단색 placeholder — 실제 로고 에셋 필요.
- 백오피스 연동(원격 동영상 배포, 설정 원격 변경)은 후속 범위.
