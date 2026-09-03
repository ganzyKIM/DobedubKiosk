#!/usr/bin/env bash
# 운영 패키지(DobedubKiosk-Operations-v<버전>.zip) 조립기.
#
# 회사 관리자 PC 에 주는 꾸러미는 리포 체크아웃이 아니라 "스크립트 + 문서 + 서버 + APK" 를
# 골라 담은 폴더다. 이 스크립트가 담을 파일 목록의 단일 출처이고, 세팅 스크립트가 기대하는
# 규약(APK 이름·위치, .bat/.ps1 CRLF)을 조립 시점에 검증한다.
#
# 2026-09-03 사고: 손으로 꾸린 zip 은 APK 를 최상위 dobedub-kiosk-v2.5.6.apk 로 넣었는데
# setup-tablet.ps1 은 app/build/outputs/... 만 봐서 회사 PC 첫 세팅이 4단계에서 멈췄다.
# → 스크립트 쌍은 최상위 dobedub-kiosk-*.apk 를 1순위로 찾도록 고쳤고, 이 조립기가 그 이름으로
#   넣는다. 두 규약을 함께 유지할 것 (setup-tablet.ps1 / setup-tablet.sh 4단계 주석 참조).
#
# 사용:
#   ./make-package.sh --apk app/build/outputs/apk/release/app-release.apk
#       → dist/DobedubKiosk-Operations-v2.5.6.zip   (버전은 app/build.gradle.kts 의 versionName)
#   ./make-package.sh --apk <apk> --adbkeyboard <ADBKeyboard.apk> --suffix r2
#       → dist/DobedubKiosk-Operations-v2.5.6-r2.zip (같은 앱 버전의 패키지 개정판)
#   ./make-package.sh --no-apk --suffix r2
#       → dist/DobedubKiosk-Operations-v2.5.6-r2-noapk.zip
#         골격(패치용): APK·ADBKeyboard 없이 스크립트·문서·서버만. 폴더 이름이 같으므로
#         회사 PC 의 기존 DobedubKiosk-Operations 위에 풀면 텍스트만 갱신되고
#         APK·platform-tools·넷버드 준비물·server\data 는 그대로 남는다.
#
# 옵션:
#   --apk <path>          설치용 릴리스 APK (키스토어 서명본). 이름은 dobedub-kiosk-v<버전>.apk 로 바뀐다.
#   --adbkeyboard <path>  ADBKeyboard.apk. 생략하면 리포 루트/상위 폴더에서 찾는다.
#   --no-apk              APK 없이 골격만 (--apk 와 배타). 파일명에 -noapk 가 붙는다.
#   --suffix <s>          zip/폴더 버전 뒤에 붙일 개정 표시 (예: r2). 앱 버전은 그대로.
#   --out <dir>           출력 폴더 (기본 dist/)
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APK=""; ADBKB=""; NOAPK=0; SUFFIX=""; OUT="$REPO/dist"
while [ $# -gt 0 ]; do
  case "$1" in
    --apk)          APK="$2"; shift ;;
    --adbkeyboard)  ADBKB="$2"; shift ;;
    --no-apk)       NOAPK=1 ;;
    --suffix)       SUFFIX="$2"; shift ;;
    --out)          OUT="$2"; shift ;;
    -h|--help)      sed -n '2,32p' "$0"; exit 0 ;;
    *) echo "알 수 없는 옵션: $1" >&2; exit 2 ;;
  esac
  shift
done

die()  { printf '  [실패] %s\n' "$1" >&2; exit 1; }
ok()   { printf '  [OK]  %s\n' "$1"; }
info() { printf '  ->    %s\n' "$1"; }
warn() { printf '  [!]   %s\n' "$1"; }

# ---------- 버전 ----------
VERSION="$(sed -nE 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' "$REPO/app/build.gradle.kts" | head -1)"
[ -n "$VERSION" ] || die "app/build.gradle.kts 에서 versionName 을 읽지 못했습니다"
VCODE="$(sed -nE 's/^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*([0-9]+).*/\1/p' "$REPO/app/build.gradle.kts" | head -1)"
TAG="v$VERSION${SUFFIX:+-$SUFFIX}"
PKG="DobedubKiosk-Operations"
NOAPK_TAG=""; [ "$NOAPK" -eq 1 ] && NOAPK_TAG="-noapk"
ZIP="$OUT/$PKG-$TAG$NOAPK_TAG.zip"
info "앱 버전 $VERSION (versionCode $VCODE) → $ZIP"

# ---------- 입력 검증 ----------
if [ "$NOAPK" -eq 1 ]; then
  [ -z "$APK" ] || die "--no-apk 와 --apk 는 함께 쓸 수 없습니다"
else
  [ -n "$APK" ] || die "--apk <release apk> 가 필요합니다 (골격만 만들려면 --no-apk)"
  [ -f "$APK" ] || die "APK 파일이 없습니다: $APK"
  case "$(basename "$APK")" in
    app-debug.apk) die "디버그 APK 는 운영 패키지에 넣지 않습니다 (자동 업데이트 서명 비호환). assembleRelease 산출물을 주세요" ;;
  esac
  if [ -z "$ADBKB" ]; then
    for c in "$REPO/ADBKeyboard.apk" "$REPO/../ADBKeyboard.apk"; do
      [ -f "$c" ] && { ADBKB="$c"; break; }
    done
  fi
  [ -n "$ADBKB" ] && [ -f "$ADBKB" ] || die "ADBKeyboard.apk 를 찾지 못했습니다 (--adbkeyboard <path>). 넷버드 자동 등록에 필요합니다"
fi

# ---------- 담을 것 (단일 출처) ----------
# 회사_인수인계_가이드.md 부록 A 와 일치해야 한다. 여기를 바꾸면 부록 A 도 고칠 것.
ROOT_FILES=(
  "서버-준비.bat" "관리자-실행.bat" "관리자-종료.bat" "태블릿-세팅.bat"
  "setup-tablet.ps1" "start-admin.ps1" "provision-kiosk.ps1"
  "준비물-여기에-넣으세요.txt"
  "회사_인수인계_가이드.md" "PC관리자_사용법.md" "납품_매뉴얼.md"
  "원격관리_NetBird_도입.md" "원격관리_업데이트.md" "README.md"
)
# server/ 는 폴더째. 단, 런타임 산출물은 제외(관리자 PC 에서 서버-준비.bat 이 만든다).
SERVER_EXCLUDE=( "node_modules" "data" "*.tmp" "*.log" )
# app/ 은 대시보드가 기본 이용안내 이미지를 미리보기로 읽는 경로만 (server.js 참조).
MANUAL_DIR="app/src/main/res/drawable-nodpi"

# ---------- 스테이징 ----------
STAGE="$(mktemp -d)"; trap 'rm -rf "$STAGE"' EXIT
DEST="$STAGE/$PKG"; mkdir -p "$DEST"

for f in "${ROOT_FILES[@]}"; do
  [ -f "$REPO/$f" ] || die "리포에 없는 파일: $f (목록 ROOT_FILES 를 확인)"
  cp "$REPO/$f" "$DEST/$f"
done
ok "루트 파일 ${#ROOT_FILES[@]}개"

mkdir -p "$DEST/server"
TAR_EX=(); for e in "${SERVER_EXCLUDE[@]}"; do TAR_EX+=(--exclude "$e"); done
# rsync 없는 환경도 있어 tar 파이프로 복사한다(맥/리눅스 공통)
( cd "$REPO/server" && tar "${TAR_EX[@]}" -cf - . ) | ( cd "$DEST/server" && tar -xf - )
ok "server/ ($(find "$DEST/server" -type f | wc -l | tr -d ' ')개 파일, node_modules·data 제외)"

mkdir -p "$DEST/$MANUAL_DIR"
cp "$REPO/$MANUAL_DIR"/user_manual_*.png "$DEST/$MANUAL_DIR/"
ok "이용안내 이미지 $(ls "$DEST/$MANUAL_DIR" | wc -l | tr -d ' ')장 ($MANUAL_DIR)"

if [ "$NOAPK" -eq 0 ]; then
  cp "$APK"   "$DEST/dobedub-kiosk-v$VERSION.apk"
  cp "$ADBKB" "$DEST/ADBKeyboard.apk"
  ok "APK → dobedub-kiosk-v$VERSION.apk ($(du -h "$APK" | cut -f1)), ADBKeyboard.apk"
else
  warn "골격 모드: dobedub-kiosk-v$VERSION.apk / ADBKeyboard.apk 는 넣지 않았습니다"
fi

# ---------- 규약 검증 ----------
# 1) 윈도우 배치·PS1 은 CRLF. cmd.exe 는 LF 배치의 괄호 블록을 잘못 파싱한다(.gitattributes 참조).
#    체크아웃 상태에 기대지 않고 여기서 한 번 더 강제한다.
find "$DEST" -maxdepth 1 \( -name '*.bat' -o -name '*.ps1' \) -print0 |
  while IFS= read -r -d '' f; do perl -pi -e 's/\r?\n/\r\n/' "$f"; done
find "$DEST/server" -name '*.ps1' -print0 |
  while IFS= read -r -d '' f; do perl -pi -e 's/\r?\n/\r\n/' "$f"; done
for f in "$DEST"/*.bat "$DEST"/*.ps1; do
  grep -q $'\r' "$f" || die "CRLF 변환 실패: $(basename "$f")"
done
ok "CRLF: *.bat / *.ps1"

# 2) 세팅 스크립트가 찾는 이름으로 APK 가 있는가 (setup-tablet.ps1 4단계 규약)
if [ "$NOAPK" -eq 0 ]; then
  ls "$DEST"/dobedub-kiosk-*.apk >/dev/null 2>&1 || die "규약 위반: 최상위에 dobedub-kiosk-*.apk 가 없습니다"
  grep -q 'dobedub-kiosk-\*\.apk' "$DEST/setup-tablet.ps1" || die "setup-tablet.ps1 이 dobedub-kiosk-*.apk 를 찾지 않습니다 — 4단계 규약이 깨졌습니다"
  ok "APK 위치 규약 (setup-tablet.ps1 ↔ 패키지 최상위)"
fi

# 3) 비밀은 절대 담지 않는다
for bad in netbird-setup-key.txt keystore.properties '*.jks' '*.keystore'; do
  found="$(find "$DEST" -name "$bad" | head -1)"
  [ -z "$found" ] || die "비밀 파일이 패키지에 들어갔습니다: $found"
done
ok "비밀 파일 없음 (setup key / keystore)"

# 4) 부록 A 의 APK 파일명이 이번 버전과 맞는가 (문서 표기 어긋남 방지)
if ! grep -q "dobedub-kiosk-v$VERSION.apk" "$DEST/회사_인수인계_가이드.md"; then
  warn "회사_인수인계_가이드.md 부록 A 의 APK 파일명이 v$VERSION 이 아닙니다 — 문서를 갱신하세요"
fi

# ---------- zip ----------
mkdir -p "$OUT"; rm -f "$ZIP"
( cd "$STAGE" && zip -qr -X "$ZIP" "$PKG" )
ok "생성: $ZIP ($(du -h "$ZIP" | cut -f1))"
echo
info "내용 요약:"
unzip -Z1 "$ZIP" | sed "s#^$PKG/##" | grep -vE '^(server|app)/.+/|/$|^$' | sort | sed 's/^/        /' | head -40
[ "$NOAPK" -eq 1 ] && { echo; warn "이 zip 은 골격입니다. 회사 PC 의 기존 폴더 위에 풀거나, APK·ADBKeyboard.apk 를 채워서 전달하세요."; }
exit 0
