#!/usr/bin/env bash
# Finder 에서 더블클릭하면 터미널이 열리며 실행된다(윈도우의 태블릿-세팅.bat 대응).
cd "$(dirname "$0")" || exit 1
./setup-tablet.sh "$@"
status=$?
if [ $status -ne 0 ]; then
  echo
  echo "[오류] 세팅이 정상적으로 끝나지 않았습니다. 위 메시지를 확인하세요."
  read -r -p "엔터를 누르면 창을 닫습니다..." _
fi
exit $status
