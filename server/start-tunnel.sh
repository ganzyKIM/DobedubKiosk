#!/usr/bin/env bash
# 함대 서버를 공인 HTTPS 주소로 노출하는 Cloudflare Tunnel 실행 스크립트 — 맥/리눅스판.
# 윈도우의 start-tunnel.ps1 과 같은 일을 한다.
#
# 왜 필요한가: 도서관에 나가 있는 태블릿은 사내 LAN 밖에 있다. 맥의 사설 IP
# (192.168.x.x / 172.x.x.x)로는 닿지 않는다. 따라서 원격 관리를 하려면 공인 HTTPS 주소가 있어야 한다.
# (같은 와이파이에 있는 태블릿이라면 터널 없이 http://<맥 LAN IP>:8090 으로 바로 붙는다.)
#
# 사용법:
#   ./start-tunnel.sh
#
# ⚠ 이 스크립트가 만드는 것은 "빠른 터널(quick tunnel)"이라 주소가 매번 바뀐다.
#   주소가 바뀌면 태블릿을 전부 다시 설정해야 하므로 실제 운영에는 고정 주소(named tunnel)로
#   전환할 것. 자세한 내용은 README "원격 관리(공인 주소) 구성" 참조.

set -o pipefail

PORT="${PORT:-8090}"

# cloudflared 탐색: PATH → Homebrew(Apple Silicon/Intel)
CF="$(command -v cloudflared 2>/dev/null)"
if [ -z "$CF" ]; then
  for c in /opt/homebrew/bin/cloudflared /usr/local/bin/cloudflared; do
    [ -x "$c" ] && { CF="$c"; break; }
  done
fi
if [ -z "$CF" ]; then
  echo "cloudflared 를 찾을 수 없습니다. 먼저 설치하세요: brew install cloudflared" >&2
  exit 1
fi

# 함대 서버가 떠 있는지 먼저 확인 — 서버가 없는데 터널만 띄우면 502 만 반환한다.
health="$(curl -fsS --max-time 5 "http://localhost:$PORT/health" 2>/dev/null)"
if [ "$health" != "ok" ]; then
  echo "함대 서버(localhost:$PORT)가 응답하지 않습니다. 먼저 서버를 띄우세요:" >&2
  echo "  ./start-admin.sh        (또는 관리자-실행.command)" >&2
  exit 1
fi

echo "함대 서버 정상 — 터널을 시작합니다. 아래 출력에서 https://*.trycloudflare.com 주소를 확인하세요."
exec "$CF" tunnel --url "http://localhost:$PORT" --no-autoupdate
