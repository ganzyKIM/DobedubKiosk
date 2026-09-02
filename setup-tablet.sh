#!/usr/bin/env bash
# 두비덥 도서관 키오스크 태블릿 원클릭 세팅 — 맥/리눅스판.
# 윈도우의 setup-tablet.ps1 과 같은 순서·같은 판정으로 동작한다.
#
#   ./setup-tablet.sh                     # 보통은 태블릿-세팅.command 더블클릭
#   ./setup-tablet.sh --skip-video        # 영상 투입 생략
#   ./setup-tablet.sh --skip-webview      # WebView 업데이트 생략
#   ./setup-tablet.sh --skip-debloat      # 기본 앱 정리 생략
#   ./setup-tablet.sh --skip-netbird      # NetBird 설치·등록 생략
#   ./setup-tablet.sh --force-video       # 같은 영상이 있어도 다시 push
#   ./setup-tablet.sh --subdomain splib   # 도서관 주소를 묻지 않고 지정
#   ./setup-tablet.sh --apk <경로>        # 설치할 APK 직접 지정
#
# 수행 순서
#   0. adb 탐색  1. 연결 대기  2. 사전 점검  2.5 도서관 주소  3. WebView 업데이트
#   4. APK 설치  5. Device Owner  5.5 화면 세로 고정  6. 블로트웨어 정리
#   7. 영상 투입  8. 실행 + 검증
#
# 핵심 제약(회피 불가): Device Owner 지정은 기기에 계정이 하나도 없어야 성공한다.
#
# 맥 이식 시 바뀐 부분:
#   adb.exe 탐색(LOCALAPPDATA 등)  →  ~/Library/Android/sdk 등 맥 경로
#   Scripting.FileSystemObject 8.3 단축경로  →  불필요(맥은 한글/대괄호 경로를 그대로 넘겨도 안전)
#   Get-ChildItem -Recurse           →  find
#   Read-Host                        →  read (비대화형이면 건너뜀)
# bash 3.2(맥 기본)에서 돌아야 하므로 연관배열·mapfile 등 4.x 문법은 쓰지 않는다.

set -o pipefail

# ─────────────────────────────────────────────────────────────────────────────
# 기본 탑재(블로트웨어) 중 "제거해도 키오스크 동작에 지장 없는 소비자 앱"만 큐레이션한 목록.
# pm uninstall --user 0 로 현재 사용자에서만 제거한다(루트 불필요, 공장초기화로 복구 가능).
#
# 절대 넣지 말 것(키오스크/시스템 필수): com.google.android.webview(웹툰 리더),
#   com.google.android.inputmethod.latin(키보드), com.android.systemui, com.android.settings,
#   *.launcher / launcher.provider(홈 폴백), com.google.android.gms/gsf, com.android.vending,
#   packageinstaller, permissioncontroller, networkstack, dolby.*(오디오) 등.
# 이 목록은 Lenovo TB-J606F(Android 11) 실제 패키지 기준. ps1 판과 동일하게 유지할 것.
# ─────────────────────────────────────────────────────────────────────────────
BLOAT_PACKAGES="
com.android.chrome
com.google.android.googlequicksearchbox
com.google.android.apps.googleassistant
com.google.android.apps.maps
com.google.android.apps.photos
com.google.android.apps.docs
com.google.android.apps.books
com.google.android.videos
com.google.android.youtube
com.google.android.apps.youtube.music
com.google.android.apps.youtube.music.setupwizard
com.google.android.gm
com.google.android.calendar
com.google.android.contacts
com.google.android.deskclock
com.google.android.calculator
com.google.android.keep
com.google.android.apps.nbu.files
com.google.android.apps.tachyon
com.google.android.apps.subscriptions.red
com.google.android.apps.kids.home
com.google.android.apps.wellbeing
com.google.android.apps.mediahome.launcher
com.microsoft.office.officehubrow
com.microsoft.office.onenote
com.microsoft.bing.wallpapers
com.netflix.mediaclient
com.motorola.demo
com.tblenovo.center
com.tblenovo.lenovowhatsnew
com.lenovo.penmenu
com.wacom.bamboopapertab
com.steadfastinnovation.android.projectpapyrus
"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ---------- 기본값 / 인자 ----------
APK_PATH=""
WEBVIEW_APK=""
VIDEO_DIR="$SCRIPT_DIR/.."
PACKAGE_NAME="com.dobedub.kiosk"
ADMIN_RECEIVER="com.dobedub.kiosk/.kiosk.AdminReceiver"
FLEET_SERVER_URL="${FLEET_SERVER_URL:-}"
LIBRARY_SUBDOMAIN=""
START_URL=""
SKIP_VIDEO=0
SKIP_WEBVIEW=0
SKIP_DEBLOAT=0
SKIP_NETBIRD=0
FORCE_VIDEO=0
# NetBird 원격 관리(원격관리_NetBird_도입.md). 키는 리포 밖 파일/환경변수로만.
NETBIRD_SERVER="${NETBIRD_SERVER:-https://api.netbird.io}"
NETBIRD_SETUP_KEY="${NETBIRD_SETUP_KEY:-}"

while [ $# -gt 0 ]; do
  case "$1" in
    --apk)          APK_PATH="$2"; shift ;;
    --webview-apk)  WEBVIEW_APK="$2"; shift ;;
    --video-dir)    VIDEO_DIR="$2"; shift ;;
    --subdomain)    LIBRARY_SUBDOMAIN="$2"; shift ;;
    --start-url)    START_URL="$2"; shift ;;
    --package)      PACKAGE_NAME="$2"; shift ;;
    --skip-video)   SKIP_VIDEO=1 ;;
    --skip-webview) SKIP_WEBVIEW=1 ;;
    --skip-debloat) SKIP_DEBLOAT=1 ;;
    --skip-netbird) SKIP_NETBIRD=1 ;;
    --force-video)  FORCE_VIDEO=1 ;;
    -h|--help)      sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "알 수 없는 옵션: $1" >&2; exit 2 ;;
  esac
  shift
done

# ---------- 출력 도우미 ----------
if [ -t 1 ]; then
  C_CYAN=$'\033[36m'; C_GREEN=$'\033[32m'; C_GRAY=$'\033[90m'
  C_YELLOW=$'\033[33m'; C_RED=$'\033[31m'; C_OFF=$'\033[0m'
else
  C_CYAN=''; C_GREEN=''; C_GRAY=''; C_YELLOW=''; C_RED=''; C_OFF=''
fi
# stat/date 는 BSD(맥)와 GNU(리눅스)의 옵션이 다르다. 맥을 우선 시도하고 실패하면 GNU 형식.
file_size()  { stat -f %z "$1" 2>/dev/null || stat -c %s "$1" 2>/dev/null; }
file_mtime() { stat -f %m "$1" 2>/dev/null || stat -c %Y "$1" 2>/dev/null; }
fmt_time()   { date -r "$1" '+%m-%d %H:%M' 2>/dev/null || date -d "@$1" '+%m-%d %H:%M' 2>/dev/null; }

head_() { printf '\n%s══ %s ══%s\n' "$C_CYAN" "$1" "$C_OFF"; }
ok()    { printf '  %s[OK]%s  %s\n'  "$C_GREEN"  "$C_OFF" "$1"; }
info()  { printf '  %s->%s    %s\n'  "$C_GRAY"   "$C_OFF" "$1"; }
warn()  { printf '  %s[!]%s   %s\n'  "$C_YELLOW" "$C_OFF" "$1"; }
# 비대화형(파이프/자동화)으로 실행되면 조용히 통과한다.
pause_end() { [ -t 0 ] && read -r -p "엔터를 누르면 종료합니다" _ ; return 0; }
die() {
  printf '\n  %s[실패]%s %s\n\n' "$C_RED" "$C_OFF" "$1"
  pause_end
  exit 1
}

printf '\n%s┌───────────────────────────────────────────────┐%s\n' "$C_CYAN" "$C_OFF"
printf '%s│   두비덥 도서관 키오스크 — 태블릿 원클릭 세팅 │%s\n'   "$C_CYAN" "$C_OFF"
printf '%s└───────────────────────────────────────────────┘%s\n'   "$C_CYAN" "$C_OFF"

# ---------- 0. adb 탐색 ----------
head_ "0. adb 준비"
find_adb() {
  # 1) 스크립트 옆에 platform-tools 를 동봉한 경우
  [ -x "$SCRIPT_DIR/platform-tools/adb" ] && { echo "$SCRIPT_DIR/platform-tools/adb"; return; }
  # 2) 환경변수로 지정된 SDK
  for root in "$ANDROID_HOME" "$ANDROID_SDK_ROOT"; do
    [ -n "$root" ] && [ -x "$root/platform-tools/adb" ] && { echo "$root/platform-tools/adb"; return; }
  done
  # 3) 맥 기본 SDK 위치 / Homebrew cask(android-platform-tools)
  for c in "$HOME/Library/Android/sdk/platform-tools/adb" \
           /opt/homebrew/bin/adb /usr/local/bin/adb; do
    [ -x "$c" ] && { echo "$c"; return; }
  done
  # 4) PATH
  command -v adb 2>/dev/null
}
ADB="$(find_adb)"
[ -n "$ADB" ] || die "adb 를 찾을 수 없습니다.
       설치: brew install --cask android-platform-tools
       (또는 Android SDK platform-tools 를 설치하고 ANDROID_HOME 을 지정)"
ok "adb: $ADB"

# adb 호출 결과를 ADB_OUT(표준출력+표준에러) / ADB_CODE(종료코드) 로 돌려준다.
# adb 는 정상 상황에서도 stderr 를 쓰므로 둘을 합쳐서 판정한다.
SERIAL=""
adb_run() {
  if [ -n "$SERIAL" ]; then
    ADB_OUT="$("$ADB" -s "$SERIAL" "$@" 2>&1)"
  else
    ADB_OUT="$("$ADB" "$@" 2>&1)"
  fi
  ADB_CODE=$?
  return $ADB_CODE
}

"$ADB" start-server >/dev/null 2>&1

# ---------- 1. 기기 연결 대기 ----------
head_ "1. 태블릿 연결"
info "USB 케이블로 태블릿을 맥에 연결하세요."
deadline=$(( $(date +%s) + 300 ))
while [ -z "$SERIAL" ]; do
  adb_run devices
  # "XXWWZZ<탭>device" 형태만 채택 (unauthorized/offline 제외)
  ready="$(printf '%s\n' "$ADB_OUT" | awk '$2 == "device" { print $1 }' | head -1)"
  unauth="$(printf '%s\n' "$ADB_OUT" | awk '$2 == "unauthorized" { print $1 }' | head -1)"
  if [ -n "$ready" ]; then
    SERIAL="$ready"
    break
  fi
  if [ -n "$unauth" ]; then
    warn "태블릿 화면의 'USB 디버깅을 허용하시겠습니까?' 에서 [허용]을 눌러주세요. (항상 허용 체크 권장)"
  fi
  if [ "$(date +%s)" -gt "$deadline" ]; then
    die "5분 동안 태블릿을 찾지 못했습니다.
       - 케이블이 데이터 전송용인지 (충전 전용 아님) 확인
       - 태블릿: 설정 > 태블릿 정보 > 빌드번호 7회 탭 → 개발자 옵션 > USB 디버깅 켜기"
  fi
  sleep 2
done
ok "연결됨: $SERIAL"

# ---------- 2. 사전 점검 ----------
head_ "2. 사전 점검"
adb_run shell getprop ro.product.model;             MODEL="$(echo "$ADB_OUT" | tr -d '\r')"
adb_run shell getprop ro.build.version.release;     REL="$(echo "$ADB_OUT" | tr -d '\r')"
adb_run shell getprop ro.build.version.sdk;         SDKV="$(echo "$ADB_OUT" | tr -d '\r')"
info "모델: $MODEL / Android $REL (API $SDKV)"

# 이미 Device Owner 인가?
adb_run shell dumpsys device_policy
ALREADY_OWNER=0
if printf '%s' "$ADB_OUT" | grep -q "Device Owner" && printf '%s' "$ADB_OUT" | grep -qF "$PACKAGE_NAME"; then
  ALREADY_OWNER=1
  ok "이미 이 앱이 Device Owner 로 지정되어 있습니다. (Device Owner 단계는 건너뜁니다)"
fi

# 계정 존재 여부 (Device Owner 지정을 막는 핵심 요인)
adb_run shell dumpsys account
ACCT_COUNT="$(printf '%s' "$ADB_OUT" | grep -c 'Account *{')"
if [ "$ALREADY_OWNER" -eq 0 ]; then
  if [ "$ACCT_COUNT" -gt 0 ]; then
    echo
    warn "이 태블릿에 계정이 ${ACCT_COUNT}개 등록되어 있습니다."
    warn "계정이 하나라도 있으면 Device Owner 지정이 불가능합니다 (Android 보안 정책)."
    echo
    printf '     %s해결 방법 (둘 중 하나):%s\n' "$C_YELLOW" "$C_OFF"
    printf '     %s  A) 계정만 삭제: 설정 > 계정 > 각 계정 > 계정 삭제  (모두 삭제 후 재실행)%s\n' "$C_YELLOW" "$C_OFF"
    printf '     %s  B) 공장초기화: 설정 > 시스템 > 초기화  (초기 설정에서 Wi-Fi/계정 로그인 건너뛰기)%s\n' "$C_YELLOW" "$C_OFF"
    echo
    if [ -t 0 ]; then
      read -r -p "     지금 태블릿에서 [계정 설정] 화면을 열까요? (Y/N) " open_ans
      case "$open_ans" in
        [Yy]*) adb_run shell am start -a android.settings.SYNC_SETTINGS
               info "태블릿에서 계정을 모두 삭제한 뒤 이 스크립트를 다시 실행하세요." ;;
      esac
    fi
    die "계정을 먼저 정리한 후 다시 실행해주세요."
  else
    ok "등록된 계정 없음 — Device Owner 지정 가능"
  fi
fi

# ---------- 2.5 도서관 주소 입력 ----------
# 도서관마다 서브도메인이 다르므로(예: splib) 이 기기의 시작 주소를 정한다.
head_ "2.5 도서관 주소"
if [ -z "$START_URL" ] && [ -z "$LIBRARY_SUBDOMAIN" ] && [ -t 0 ]; then
  printf '  %s이 태블릿이 접속할 도서관 주소를 정합니다.%s\n' "$C_GRAY" "$C_OFF"
  printf '  %s서브도메인만 입력하면 됩니다.  예) splib  →  https://splib.dobedub.com/home%s\n' "$C_GRAY" "$C_OFF"
  read -r -p "  도서관 서브도메인 (전체 URL 도 가능, 비우면 앱 기본값 유지): " LIBRARY_SUBDOMAIN
fi
if [ -z "$START_URL" ] && [ -n "$LIBRARY_SUBDOMAIN" ]; then
  case "$LIBRARY_SUBDOMAIN" in
    http://*|https://*) START_URL="$LIBRARY_SUBDOMAIN" ;;
    *)                  START_URL="https://${LIBRARY_SUBDOMAIN}.dobedub.com/home" ;;
  esac
fi
# 백오피스 식별용 라벨 = 서브도메인(호스트 첫 토큰)
LIB_LABEL=""
case "$LIBRARY_SUBDOMAIN" in
  ""|http://*|https://*)
    LIB_LABEL="$(printf '%s' "$START_URL" | sed -n 's|^https\{0,1\}://\([^./]*\)\..*|\1|p')" ;;
  *)  LIB_LABEL="$LIBRARY_SUBDOMAIN" ;;
esac
if [ -n "$START_URL" ]; then
  ok "시작 주소: $START_URL  (기관 라벨: $LIB_LABEL)"
else
  warn "주소 미입력 — 앱 기본값(https://splib.dobedub.com/home) 유지"
fi

# ---------- 2.6 함대(관리) 서버 주소 ----------
# 이 태블릿이 체크인할 관리자 PC 를 정한다. APK 기본값은 개발 서버라 운영 태블릿은 여기서 덮어쓴다.
head_ "2.6 관리 서버 주소"
if [ -z "$FLEET_SERVER_URL" ] && [ -t 0 ]; then
  nb_ip_local=""
  if command -v netbird >/dev/null 2>&1; then
    nb_ip_local="$(netbird status 2>/dev/null | awk '/NetBird IP:/{print $3}' | cut -d/ -f1 | head -1)"
  fi
  if [ -n "$nb_ip_local" ]; then
    suggest="http://${nb_ip_local}:8090"
    info "이 PC 의 넷버드 주소를 찾았습니다: $suggest"
    read -r -p "  이 주소로 설정할까요? (엔터=예 / 다른 주소 입력): " ans
    if [ -z "$ans" ]; then FLEET_SERVER_URL="$suggest"; else FLEET_SERVER_URL="$ans"; fi
  else
    warn "이 PC 에서 넷버드 주소를 찾지 못했습니다(넷버드 미설치 또는 미연결)."
    read -r -p "  함대 서버 주소 (비우면 APK 기본값 = 개발 서버): " FLEET_SERVER_URL
  fi
fi
if [ -n "$FLEET_SERVER_URL" ]; then
  # 심기 전에 실제로 응답하는지 확인한다. 오타를 심으면 그 태블릿은 대시보드에 나타나지 않는다.
  if [ "$(curl -s -m 5 "$FLEET_SERVER_URL/health" 2>/dev/null)" = "ok" ]; then
    ok "관리 서버 주소: $FLEET_SERVER_URL  (응답 확인됨)"
  else
    warn "그 주소가 이 PC 에서 응답하지 않습니다: $FLEET_SERVER_URL"
    if [ -t 0 ]; then
      read -r -p "  그래도 이 주소로 진행할까요? (Y/N) " go
      case "$go" in [Yy]*) ;; *) FLEET_SERVER_URL=""; warn "주소 미설정 — APK 기본값(개발 서버)을 쓰게 됩니다" ;; esac
    fi
  fi
else
  warn "주소 미설정 — APK 기본값(개발 서버)을 씁니다. 운영 태블릿이라면 나중에 반드시 바꾸세요."
fi

# ---------- 3. WebView 업데이트 ----------
if [ "$SKIP_WEBVIEW" -eq 0 ]; then
  head_ "3. Android System WebView 업데이트"
  if [ -z "$WEBVIEW_APK" ]; then
    WEBVIEW_APK="$(find "$SCRIPT_DIR/.." -maxdepth 1 -name 'com.google.android.webview*.apk' 2>/dev/null | head -1)"
  fi
  if [ -n "$WEBVIEW_APK" ] && [ -f "$WEBVIEW_APK" ]; then
    info "설치: $(basename "$WEBVIEW_APK")"
    adb_run install -r -d "$WEBVIEW_APK"
    if [ "$ADB_CODE" -eq 0 ] || printf '%s' "$ADB_OUT" | grep -q "Success"; then
      ok "WebView 업데이트 완료"
    elif printf '%s' "$ADB_OUT" | grep -qE "VERSION_DOWNGRADE|INSTALL_FAILED_ALREADY_EXISTS"; then
      ok "이미 동일하거나 더 최신 WebView 가 설치되어 있습니다 (건너뜀)"
    else
      warn "WebView 업데이트 실패 (계속 진행): $ADB_OUT"
    fi
  else
    warn "WebView APK 를 찾지 못해 건너뜁니다. (필요 시 상위 폴더에 com.google.android.webview*.apk 배치)"
  fi
else
  head_ "3. WebView 업데이트 (건너뜀: --skip-webview)"
fi

# ---------- 4. 키오스크 APK 설치 ----------
head_ "4. 키오스크 앱 설치"
# APK 자동 선택: 릴리스(고정 키 서명, 자동 업데이트 호환) 우선, 없으면 디버그로 폴백.
if [ -z "$APK_PATH" ]; then
  rel_apk="$SCRIPT_DIR/app/build/outputs/apk/release/app-release.apk"
  dbg_apk="$SCRIPT_DIR/app/build/outputs/apk/debug/app-debug.apk"
  if [ -f "$rel_apk" ]; then
    APK_PATH="$rel_apk"
  elif [ -f "$dbg_apk" ]; then
    APK_PATH="$dbg_apk"
    warn "릴리스 APK가 없어 디버그 APK로 설치합니다. 디버그 서명은 원격 자동 업데이트와 호환되지 않습니다."
    warn "운영 납품 시에는 './gradlew assembleRelease'(키스토어 보유 PC)로 만든 릴리스 APK를 사용하세요."
  fi
fi
[ -n "$APK_PATH" ] && [ -f "$APK_PATH" ] || die "APK 를 찾을 수 없습니다.
       먼저 빌드하세요:  ./gradlew assembleRelease  (또는 assembleDebug)"

apk_mtime="$(file_mtime "$APK_PATH")"
info "설치: $(basename "$APK_PATH")  (빌드시각 $(fmt_time "$apk_mtime"))"
age_h=$(( ( $(date +%s) - apk_mtime ) / 3600 ))
if [ "$age_h" -gt 12 ]; then
  warn "이 APK는 ${age_h}시간 전에 빌드됐습니다. 최신 코드가 맞는지 확인하세요(필요시 재빌드)."
fi

adb_run install -r "$APK_PATH"
if [ "$ADB_CODE" -ne 0 ] && ! printf '%s' "$ADB_OUT" | grep -q "Success"; then
  if printf '%s' "$ADB_OUT" | grep -qE "INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match"; then
    warn "기존 설치본과 서명이 다릅니다 (다른 PC에서 빌드된 앱이 이미 설치됨)."
    info "기존 앱을 제거하고 재설치를 시도합니다..."
    if [ "$ALREADY_OWNER" -eq 1 ]; then
      die "그런데 기존 앱이 Device Owner 라 adb 로 제거할 수 없습니다.
       태블릿에서: 관리자 메뉴 > 키오스크 완전 해제 실행 후, 다시 이 스크립트를 실행하세요.
       (해제가 안 되면 공장초기화가 필요합니다)"
    fi
    adb_run uninstall "$PACKAGE_NAME"
    adb_run install "$APK_PATH"
    if [ "$ADB_CODE" -ne 0 ] && ! printf '%s' "$ADB_OUT" | grep -q "Success"; then
      die "APK 재설치 실패: $ADB_OUT"
    fi
    ok "재설치 완료"
  else
    die "APK 설치 실패: $ADB_OUT"
  fi
else
  ok "앱 설치 완료"
fi

# ---------- 5. Device Owner 지정 ----------
head_ "5. Device Owner 지정 (키오스크 잠금 권한)"
if [ "$ALREADY_OWNER" -eq 1 ]; then
  ok "이미 지정되어 있어 건너뜁니다."
else
  adb_run shell dpm set-device-owner "$ADMIN_RECEIVER"
  if printf '%s' "$ADB_OUT" | grep -q "Success"; then
    ok "Device Owner 지정 완료"
  elif printf '%s' "$ADB_OUT" | grep -qE "already some accounts|already an account"; then
    die "계정이 남아있어 실패했습니다. 계정을 모두 삭제하고 다시 실행하세요."
  elif printf '%s' "$ADB_OUT" | grep -qE "already set|already a device owner|already provisioned"; then
    ok "이미 지정되어 있습니다."
  else
    die "Device Owner 지정 실패:
       $ADB_OUT

       대개 원인은 (1) 계정이 남아있음 (2) 이미 다른 프로필이 설정됨.
       공장초기화 후 초기 설정에서 계정 로그인을 건너뛴 상태로 다시 시도하세요."
  fi
fi

# ---------- 5.5 화면 회전 고정 (세로) ----------
# 앱 자체는 매니페스트로 portrait 고정이지만, 그것만으로는 홈/설정/시스템UI 가 가로로 돌아간다.
# OS 레벨에서 자동회전과 Lenovo '스마트 회전'을 모두 끄고 0도(세로)로 못박는다.
head_ "5.5 화면 회전 고정 (세로)"
# 형식: 네임스페이스|키|값|설명   (bash 3.2 라 연관배열 대신 구분자 문자열)
ROTATION_SETTINGS="system|accelerometer_rotation|0|자동 회전 끄기
system|user_rotation|0|세로(0도) 고정
secure|smartrotate_is_show|0|Lenovo 스마트 회전 끄기
secure|camera_autorotate|0|얼굴인식 자동회전 끄기 (Android 12+)"
printf '%s\n' "$ROTATION_SETTINGS" | while IFS='|' read -r ns key val desc; do
  [ -n "$ns" ] || continue
  adb_run shell settings put "$ns" "$key" "$val"
  adb_run shell settings get "$ns" "$key"
  now="$(echo "$ADB_OUT" | tr -d '\r')"
  if [ "$now" = "$val" ]; then
    ok "$desc  ($ns/$key=$now)"
  else
    warn "$desc 적용 안 됨 (현재값: $now) — 이 기종에 없는 설정일 수 있습니다"
  fi
done

# ---------- 5.8 NetBird 설치·등록 (원격 관리 고정 주소) ----------
#
# 태블릿이 어느 망에 있든 함대 서버의 넷버드 IP 하나로 체크인하게 한다.
# 절차·좌표는 실기기(TB-J606F, 1200x2000 세로)에서 확립한 것 — 원격관리_NetBird_도입.md §3.
# ⚠ 좌표 기반 UI 자동화라서 반드시 5.5(세로 고정) 이후에 실행해야 한다.
# ⚠ NetBird 앱은 무인 등록(managed config)을 지원하지 않아 이 방법이 유일하다.
if [ "$SKIP_NETBIRD" -eq 0 ]; then
  head_ "5.8 NetBird 설치·등록"

  # 이미 등록돼 있으면(넷버드 IP 보유) 통째로 건너뛴다 — 재실행 안전.
  adb_run shell ip addr
  if printf '%s' "$ADB_OUT" | grep -q "inet 100\."; then
    ok "이미 NetBird 에 등록되어 있습니다 (100.x 주소 보유) — 건너뜁니다"
  else
    adb_run shell "dumpsys activity | grep mLockTaskModeState"
    if printf '%s' "$ADB_OUT" | grep -q "LOCKED"; then
      warn "키오스크 잠금 상태라 NetBird 등록 UI 를 조작할 수 없습니다."
      warn "관리자 메뉴(로고 5탭→PIN)에서 '키오스크 해제' 후 이 스크립트를 다시 실행하세요."
    else
      # 준비물 3개: NetBird APK / ADBKeyboard APK / setup key
      nb_apk="$(find "$SCRIPT_DIR" "$SCRIPT_DIR/.." -maxdepth 1 -name 'netbird-*.apk' 2>/dev/null | head -1)"
      kb_apk="$(find "$SCRIPT_DIR" "$SCRIPT_DIR/.." -maxdepth 1 -name 'ADBKeyboard.apk' 2>/dev/null | head -1)"
      if [ -z "$NETBIRD_SETUP_KEY" ] && [ -f "$SCRIPT_DIR/netbird-setup-key.txt" ]; then
        NETBIRD_SETUP_KEY="$(tr -d ' \r\n' < "$SCRIPT_DIR/netbird-setup-key.txt")"
      fi

      if [ -z "$nb_apk" ] || [ -z "$kb_apk" ] || [ -z "$NETBIRD_SETUP_KEY" ]; then
        warn "NetBird 등록을 건너뜁니다 — 준비물이 없습니다:"
        [ -z "$nb_apk" ] && info "netbird-*.apk 를 스크립트 옆에 두세요 (github netbirdio/android-client 릴리스)"
        [ -z "$kb_apk" ] && info "ADBKeyboard.apk 를 스크립트 옆에 두세요 (한글 IME 우회용)"
        [ -z "$NETBIRD_SETUP_KEY" ] && info "setup key 를 netbird-setup-key.txt 또는 NETBIRD_SETUP_KEY 환경변수로 (대시보드 Settings > Setup Keys)"
      else
        info "NetBird APK 설치: $(basename "$nb_apk")"
        adb_run install -r "$nb_apk"
        adb_run install -r "$kb_apk"
        # 한글 IME 가 켜져 있으면 input text 가 자모로 깨진다 → 브로드캐스트 IME 로 교체
        adb_run shell ime enable com.android.adbkeyboard/.AdbIME
        adb_run shell ime set com.android.adbkeyboard/.AdbIME
        adb_run shell svc power stayon usb   # 자동화 중 화면 꺼짐 방지

        nb_tap() { adb_run shell input tap "$1" "$2"; sleep "${3:-1}"; }
        nb_text() { adb_run shell am broadcast -a ADB_CLEAR_TEXT; adb_run shell am broadcast -a ADB_INPUT_TEXT --es msg "$1"; }

        info "NetBird 등록 UI 자동 조작 중 (약 30초)..."
        adb_run shell monkey -p io.netbird.client -c android.intent.category.LAUNCHER 1
        sleep 4
        nb_tap 600 1101 2      # Continue (첫 실행 안내)
        nb_tap 54 84           # 드로어
        nb_tap 158 618 2       # Change Server
        nb_tap 599 1096 1      # Yes (로컬 설정 초기화 동의)
        nb_tap 399 354         # + Add this device with a setup key
        nb_tap 599 268         # Server 필드
        nb_text "$NETBIRD_SERVER"
        nb_tap 599 425         # Setup key 필드
        nb_text "$NETBIRD_SETUP_KEY"
        sleep 1
        nb_tap 599 694 3       # Change
        nb_tap 599 1128 2      # 확인 (Server was changed)
        adb_run shell monkey -p io.netbird.client -c android.intent.category.LAUNCHER 1   # 메인 화면 재진입
        sleep 3
        nb_tap 599 780 2       # 연결(로고) 버튼
        nb_tap 943 1151 2      # 시스템 VPN 동의창 확인

        # 등록 검증: 넷버드 IP(100.x)가 붙을 때까지 최대 40초
        nb_ok=0
        for _ in $(seq 1 20); do
          sleep 2
          adb_run shell ip addr
          if printf '%s' "$ADB_OUT" | grep -q "inet 100\."; then nb_ok=1; break; fi
        done

        # 뒷정리: doze 예외, 키보드 원복, 화면 유지 해제 (성패와 무관하게)
        adb_run shell dumpsys deviceidle whitelist +io.netbird.client
        adb_run shell ime set com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME
        adb_run shell svc power stayon false

        if [ "$nb_ok" -eq 1 ]; then
          adb_run shell ip addr
          nb_ip="$(printf '%s' "$ADB_OUT" | grep -oE 'inet 100\.[0-9.]+' | head -1 | cut -d' ' -f2)"
          ok "NetBird 등록 완료 — 태블릿 넷버드 IP: ${nb_ip:-확인불가}"
          info "앱 v2.5+ 는 기본 서버 주소가 넷버드 주소라 별도 설정이 필요 없습니다(관리자 화면의 서버 주소는 비워두면 됨)."
          info "(v2.4 이하 구버전 앱만 관리자 화면에서 넷버드 주소(http://100.x.y.z:8090)를 직접 입력)"
        else
          warn "40초 안에 넷버드 IP 가 붙지 않았습니다 — 태블릿 화면에서 NetBird 상태를 확인하세요."
          warn "(UI 좌표가 어긋났을 수 있습니다. 수동 절차: 원격관리_NetBird_도입.md §3)"
        fi
      fi
    fi
  fi
else
  head_ "5.8 NetBird 설치·등록 (건너뜀: --skip-netbird)"
fi

# ---------- 6. 기본 앱(블로트웨어) 정리 ----------
if [ "$SKIP_DEBLOAT" -eq 0 ]; then
  head_ "6. 기본 앱 정리 (불필요한 선탑재 앱 제거)"
  info "키오스크에 불필요한 소비자 앱만 제거합니다 (WebView/키보드/시스템은 유지, 공장초기화로 복구 가능)."
  removed=0; absent=0; failed=0; total=0
  for pkg in $BLOAT_PACKAGES; do
    total=$(( total + 1 ))
    adb_run shell pm uninstall --user 0 "$pkg"
    if printf '%s' "$ADB_OUT" | grep -q "Success"; then
      ok "제거: $pkg"; removed=$(( removed + 1 ))
    elif printf '%s' "$ADB_OUT" | grep -qE "not installed for 0|Unknown package|not installed"; then
      absent=$(( absent + 1 ))   # 이미 없거나 이 사용자에 미설치 — 조용히 통과
    else
      warn "제거 실패(건너뜀): $pkg — $ADB_OUT"; failed=$(( failed + 1 ))
    fi
  done
  info "정리 결과 — 제거 $removed / 이미없음 $absent / 실패 $failed (총 ${total}개 시도)"
else
  head_ "6. 기본 앱 정리 (건너뜀: --skip-debloat)"
fi

# ---------- 7. 샘플 동영상 투입 ----------
if [ "$SKIP_VIDEO" -eq 0 ]; then
  head_ "7. 샘플 동영상 투입"
  remote_dir="/sdcard/Android/data/$PACKAGE_NAME/files/videos"
  repo_name="$(basename "$SCRIPT_DIR")"
  found_any=0
  if [ -d "$VIDEO_DIR" ]; then
    adb_run shell mkdir -p "$remote_dir"
    # 리포 폴더 안(빌드 산출물 등)은 제외하고 상위 폴더에서 mp4 를 찾는다.
    while IFS= read -r -d '' f; do
      found_any=1
      name="$(basename "$f")"
      remote="$remote_dir/$name"
      local_size="$(file_size "$f")"
      if [ "$FORCE_VIDEO" -eq 0 ]; then
        # 공백 있는 원격 경로는 device 셸에서 단일따옴표로 감싸야 안 쪼개진다.
        adb_run shell "stat -c %s '$remote'"
        rsize="$(echo "$ADB_OUT" | tr -d '\r')"
        if [ "$rsize" = "$local_size" ]; then
          ok "이미 있음 (건너뜀): $name"
          continue
        fi
      fi
      info "push ($(( local_size / 1048576 )) MB): $name  — 크기에 따라 수 분 걸릴 수 있습니다"
      # 맥은 한글/대괄호가 들어간 경로도 따옴표만으로 안전하다(윈도우의 8.3 단축경로 우회 불필요).
      adb_run push "$f" "$remote"
      if [ "$ADB_CODE" -eq 0 ] || printf '%s' "$ADB_OUT" | grep -q "pushed"; then
        ok "완료: $name"
      else
        warn "push 실패 (계속): $name — $ADB_OUT"
      fi
    done < <(find "$VIDEO_DIR" -type f -name '*.mp4' -not -path "*/$repo_name/*" -print0 2>/dev/null)
  fi
  [ "$found_any" -eq 0 ] && warn "투입할 mp4 를 찾지 못해 건너뜁니다. (검색 위치: $VIDEO_DIR)"
else
  head_ "7. 동영상 투입 (건너뜀: --skip-video)"
fi

# ---------- 8. 실행 + 검증 ----------
head_ "8. 앱 실행 및 검증"
if [ -n "$START_URL" ]; then
  # 도서관 주소/기관명을 앱에 전달해 이 기기 설정으로 저장시킨다.
  # 값에 공백이 있어도 안전하도록 device 셸용 단일따옴표로 감싼다(am 인자는 공백에서 쪼개짐).
  fleet_extra=""
  [ -n "$FLEET_SERVER_URL" ] && fleet_extra=" --es kiosk_fleet_url '$FLEET_SERVER_URL'"
  adb_run shell "am start -n $PACKAGE_NAME/.MainActivity --es kiosk_start_url '$START_URL' --es kiosk_label '$LIB_LABEL'$fleet_extra"
  ok "도서관 주소를 기기에 설정: $START_URL"
  [ -n "$FLEET_SERVER_URL" ] && ok "관리 서버 주소를 기기에 설정: $FLEET_SERVER_URL"
elif [ -n "$FLEET_SERVER_URL" ]; then
  adb_run shell "am start -n $PACKAGE_NAME/.MainActivity --es kiosk_fleet_url '$FLEET_SERVER_URL'"
  ok "관리 서버 주소를 기기에 설정: $FLEET_SERVER_URL"
else
  adb_run shell am start -n "$PACKAGE_NAME/.MainActivity"
fi
sleep 2

adb_run shell dumpsys device_policy
OWNER_OK=0
if printf '%s' "$ADB_OUT" | grep -q "Device Owner" && printf '%s' "$ADB_OUT" | grep -qF "$PACKAGE_NAME"; then
  OWNER_OK=1; ok "Device Owner 확인됨"
else
  warn "Device Owner 검증 실패 — 위 로그를 확인하세요"
fi

adb_run shell pm list packages "$PACKAGE_NAME"
PKG_OK=0
if printf '%s' "$ADB_OUT" | grep -qF "$PACKAGE_NAME"; then
  PKG_OK=1; ok "앱 설치 확인됨"
else
  warn "앱 설치 검증 실패"
fi

# ---------- 완료 ----------
echo
if [ "$OWNER_OK" -eq 1 ] && [ "$PKG_OK" -eq 1 ]; then
  printf '%s┌───────────────────────────────────────────────┐%s\n' "$C_GREEN" "$C_OFF"
  printf '%s│   세팅 완료! 태블릿이 키오스크로 잠겼습니다.  │%s\n'   "$C_GREEN" "$C_OFF"
  printf '%s└───────────────────────────────────────────────┘%s\n'   "$C_GREEN" "$C_OFF"
  echo
  printf '  %s남은 확인(태블릿에서 직접):%s\n' "$C_CYAN" "$C_OFF"
  echo "   1) 홈 화면 로고를 5회 탭 → 기본 PIN 0000 으로 관리자 진입"
  echo "   2) PIN 변경, 시작 URL / 허용 도메인 확인"
  echo "   3) '동영상 보기' 에 방금 넣은 영상이 보이는지 확인"
  echo "   4) '도서관 웹사이트' 진입 → 웹툰 재생 정상 동작 확인"
else
  printf '  %s일부 단계가 완전히 끝나지 않았습니다. 위의 [!] 항목을 확인하세요.%s\n' "$C_YELLOW" "$C_OFF"
fi
echo
pause_end
