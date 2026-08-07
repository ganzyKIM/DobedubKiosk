# 함대 서버를 공인 HTTPS 주소로 노출하는 Cloudflare Tunnel 실행 스크립트.
#
# 왜 필요한가: 도서관에 나가 있는 태블릿은 사내 LAN 밖에 있다. PC의 사설 IP
# (192.168.x.x / 172.x.x.x)로는 닿지 않고, 애초에 앱의 network_security_config 가
# 평문 HTTP 를 dobedub.com 과 localhost 에만 허용하므로 사설 IP + HTTP 는 차단된다.
# 따라서 원격 관리를 하려면 반드시 "공인 HTTPS 주소"가 있어야 한다.
#
# 사용법:
#   powershell -ExecutionPolicy Bypass -File start-tunnel.ps1
#
# ⚠ 이 스크립트가 만드는 것은 "빠른 터널(quick tunnel)"이라 주소가 매번 바뀐다.
#   테스트용으로만 쓰고, 실제 배포 전에는 반드시 고정 주소(named tunnel)로 전환할 것.
#   자세한 내용은 README "원격 관리(공인 주소) 구성" 참조.

$ErrorActionPreference = 'Stop'

$cloudflared = 'C:\Program Files (x86)\cloudflared\cloudflared.exe'
if (-not (Test-Path $cloudflared)) {
    $cloudflared = 'C:\Program Files\cloudflared\cloudflared.exe'
}
if (-not (Test-Path $cloudflared)) {
    Write-Error "cloudflared 를 찾을 수 없습니다. 먼저 설치하세요: winget install --id Cloudflare.cloudflared"
}

$port = if ($env:PORT) { $env:PORT } else { '8090' }

# 함대 서버가 떠 있는지 먼저 확인 — 서버가 없는데 터널만 띄우면 502 만 반환한다.
try {
    $health = Invoke-WebRequest -Uri "http://localhost:$port/health" -UseBasicParsing -TimeoutSec 5
    if ($health.Content.Trim() -ne 'ok') { throw "예상치 못한 응답: $($health.Content)" }
} catch {
    Write-Error "함대 서버(localhost:$port)가 응답하지 않습니다. 먼저 'npm start' 로 서버를 띄우세요."
}

Write-Host "함대 서버 정상 — 터널을 시작합니다. 아래 출력에서 https://*.trycloudflare.com 주소를 확인하세요." -ForegroundColor Green
& $cloudflared tunnel --url "http://localhost:$port" --no-autoupdate
