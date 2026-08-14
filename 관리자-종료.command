#!/usr/bin/env bash
# 함대 서버 종료 (윈도우의 관리자-종료.bat 대응).
cd "$(dirname "$0")" || exit 1
./start-admin.sh --stop
read -r -p "엔터를 누르면 창을 닫습니다..." _
