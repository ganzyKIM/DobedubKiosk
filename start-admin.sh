#!/usr/bin/env bash
# PC 관리자(함대 서버) 실행 + 대시보드 열기 — 맥/리눅스판.
# 윈도우의 start-admin.ps1 과 같은 일을 한다(동작·출력·옵션을 맞춰두었다).
#
#   ./start-admin.sh                # 서버 띄우고 대시보드 열기
#   ./start-admin.sh --stop         # 서버 종료
#   ./start-admin.sh --no-browser   # 서버만 띄우고 브라우저는 안 엶
#   ./start-admin.sh --port 9000    # 포트 지정 (기본 8090)
#
# 서버는 이 터미널을 닫아도 계속 돈다(nohup). 로그는 server/data/server.log.
#
# 맥 이식 시 바뀐 부분:
#   Get-NetTCPConnection/netstat -ano  →  lsof -iTCP -sTCP:LISTEN
#   Start-Process -WindowStyle Hidden  →  nohup ... &
#   Start-Process <url>                →  open <url>
#   Get-NetIPAddress                   →  기본 경로의 인터페이스에서 ipconfig getifaddr

set -uo pipefail

PORT=8090
DO_STOP=0
NO_BROWSER=0

while [ $# -gt 0 ]; do
  case "$1" in
    --stop)       DO_STOP=1 ;;
    --no-browser) NO_BROWSER=1 ;;
    --port)       PORT="${2:-8090}"; shift ;;
    -h|--help)    sed -n '2,14p' "$0"; exit 0 ;;
    *) echo "알 수 없는 옵션: $1" >&2; exit 2 ;;
  esac
  shift
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER_DIR="$SCRIPT_DIR/server"
DATA_DIR="$SERVER_DIR/data"
LOG_FILE="$DATA_DIR/server.log"
URL="http://localhost:$PORT"

# ---------- 출력 도우미 ----------
if [ -t 1 ]; then
  C_CYAN=$'\033[36m'; C_GREEN=$'\033[32m'; C_GRAY=$'\033[90m'
  C_YELLOW=$'\033[33m'; C_RED=$'\033[31m'; C_OFF=$'\033[0m'
else
  C_CYAN=''; C_GREEN=''; C_GRAY=''; C_YELLOW=''; C_RED=''; C_OFF=''
fi
ok()   { printf '  %s[OK]%s  %s\n'  "$C_GREEN"  "$C_OFF" "$1"; }
info() { printf '  %s->%s    %s\n'  "$C_GRAY"   "$C_OFF" "$1"; }
warn() { printf '  %s[!]%s   %s\n'  "$C_YELLOW" "$C_OFF" "$1"; }
die()  { printf '\n  %s[실패]%s %s\n\n' "$C_RED" "$C_OFF" "$1"; exit 1; }

printf '\n%s┌───────────────────────────────────────────────┐%s\n' "$C_CYAN" "$C_OFF"
printf '%s│   두비덥 키오스크 — PC 관리자 실행            │%s\n'   "$C_CYAN" "$C_OFF"
printf '%s└───────────────────────────────────────────────┘%s\n'   "$C_CYAN" "$C_OFF"

# 포트를 잡고 있는 PID. 없으면 빈 문자열.
server_pid() {
  lsof -nP -iTCP:"$PORT" -sTCP:LISTEN -t 2>/dev/null | head -1
}

# ---------- 종료 모드 ----------
if [ "$DO_STOP" -eq 1 ]; then
  pid="$(server_pid)"
  if [ -n "$pid" ]; then
    kill "$pid" 2>/dev/null
    # SIGTERM 을 받으면 server.js 가 graceful shutdown 을 한다. 그래도 안 죽으면 강제.
    for _ in 1 2 3 4 5 6 7 8 9 10; do
      sleep 0.3
      [ -z "$(server_pid)" ] && break
    done
    if [ -n "$(server_pid)" ]; then
      kill -9 "$pid" 2>/dev/null
      warn "정상 종료에 응답하지 않아 강제 종료했습니다 (PID $pid)"
    else
      ok "서버를 종료했습니다 (PID $pid)"
    fi
  else
    info "실행 중인 서버가 없습니다."
  fi
  exit 0
fi

# ---------- 1. 이미 떠 있나 ----------
pid="$(server_pid)"
if [ -n "$pid" ]; then
  ok "이미 실행 중입니다 (PID $pid)"
else
  # ---------- 2. node 확인 ----------
  NODE="$(command -v node 2>/dev/null)"
  if [ -z "$NODE" ]; then
    # Homebrew 의 keg-only node@22 는 PATH 에 안 걸릴 수 있다(.command 더블클릭 등).
    for c in /opt/homebrew/opt/node@22/bin/node /opt/homebrew/bin/node /usr/local/bin/node; do
      [ -x "$c" ] && { NODE="$c"; break; }
    done
  fi
  [ -n "$NODE" ] || die "node 를 찾을 수 없습니다.
       설치: brew install node@22   (또는 https://nodejs.org/)"

  [ -f "$SERVER_DIR/server.js" ] || \
    die "server/server.js 가 없습니다. 이 스크립트는 DobedubKiosk 폴더 안에 있어야 합니다."
  [ -d "$SERVER_DIR/node_modules" ] || \
    die "server/node_modules 가 없습니다. 먼저 의존성을 설치하세요:
       cd server && npm ci"

  # ---------- 3. 백그라운드 실행 ----------
  mkdir -p "$DATA_DIR"
  info "서버를 시작합니다..."
  ( cd "$SERVER_DIR" && nohup "$NODE" server.js >>"$LOG_FILE" 2>&1 & )

  # ---------- 4. 준비될 때까지 대기 ----------
  # 즉시 브라우저를 열면 연결 거부 화면이 뜬다 — /health 가 ok 를 줄 때까지 기다린다.
  ready=0
  for _ in $(seq 1 30); do
    sleep 0.5
    if [ "$(curl -fsS --max-time 2 "$URL/health" 2>/dev/null)" = "ok" ]; then
      ready=1; break
    fi
  done
  if [ "$ready" -ne 1 ]; then
    warn "서버가 15초 안에 응답하지 않았습니다. 로그를 확인하세요:"
    info "$LOG_FILE"
    [ -f "$LOG_FILE" ] && tail -20 "$LOG_FILE"
    die "서버 시작 실패"
  fi
  ok "서버 준비 완료 (PID $(server_pid))"
fi

# ---------- 5. 접속 정보 ----------
echo
info "주소: $URL/dashboard"
PW_FILE="$DATA_DIR/admin-password.txt"
if [ -f "$PW_FILE" ]; then
  info "비밀번호: $(tr -d '\r\n' < "$PW_FILE")"
else
  # auth.js 의 비밀번호 생성은 "첫 로그인 시도" 때 일어나는 지연 방식이라, 서버만 띄운
  # 시점에는 아직 파일이 없다. server/data/ 는 깃 추적 대상이 아니므로 새 PC(이 맥)에는
  # 기존 비밀번호가 따라오지 않는다 — 여기서 직접 정해주는 편이 헤매지 않는다.
  warn "관리자 비밀번호가 아직 없습니다. 원하는 값으로 정하려면:"
  info "printf 'PASSWORD\\n' > server/data/admin-password.txt && chmod 600 server/data/admin-password.txt"
  info "(그냥 로그인하면 임의 생성되어 위 파일과 server/data/server.log 에 기록됩니다)"
fi

# 같은 와이파이의 태블릿이 이 맥을 가리킬 때 쓸 주소.
# en0 을 가정하지 않고 "기본 경로가 나가는 인터페이스"에서 뽑는다(유선 독/USB 테더링 대비).
lan_ip=""
def_if="$(route -n get default 2>/dev/null | awk '/interface:/{print $2; exit}')"
[ -n "$def_if" ] && lan_ip="$(ipconfig getifaddr "$def_if" 2>/dev/null)"
if [ -z "$lan_ip" ]; then
  for i in en0 en1 en2 en3 en4 en5 en6; do
    lan_ip="$(ipconfig getifaddr "$i" 2>/dev/null)" && [ -n "$lan_ip" ] && break
  done
fi
if [ -n "$lan_ip" ]; then
  info "태블릿용 주소: http://${lan_ip}:$PORT  (태블릿 관리자 화면에 입력)"
else
  warn "LAN IP 를 찾지 못했습니다 — 와이파이에 연결되어 있는지 확인하세요."
fi

if [ "$NO_BROWSER" -ne 1 ]; then
  open "$URL/dashboard" 2>/dev/null && ok "브라우저를 열었습니다."
fi

echo
printf '  %s서버는 이 터미널을 닫아도 계속 실행됩니다.%s\n' "$C_CYAN" "$C_OFF"
printf '  %s종료하려면:  ./start-admin.sh --stop   (또는 관리자-종료.command)%s\n' "$C_CYAN" "$C_OFF"
echo
