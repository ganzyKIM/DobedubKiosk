#!/usr/bin/env bash
# Finder 에서 더블클릭하면 터미널이 열리며 실행된다(윈도우의 관리자-실행.bat 대응).
# .bat 과 달리 한글을 그대로 써도 된다 — cmd.exe 의 바이트 오프셋 파서 문제가 없다.
cd "$(dirname "$0")" || exit 1
./start-admin.sh "$@"
status=$?
if [ $status -ne 0 ]; then
  echo
  echo "[오류] 관리자 실행이 정상적으로 끝나지 않았습니다. 위 메시지를 확인하세요."
  read -r -p "엔터를 누르면 창을 닫습니다..." _
fi
exit $status
