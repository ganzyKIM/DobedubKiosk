<#
.SYNOPSIS
  PC 관리자(함대 서버) 실행 + 대시보드 열기 — 원클릭.

.DESCRIPTION
  1. 이미 떠 있으면 다시 띄우지 않고 브라우저만 연다(중복 실행 방지).
  2. 없으면 server.js 를 백그라운드로 띄우고 /health 가 응답할 때까지 기다린다.
  3. 기본 브라우저로 대시보드를 연다.

  서버는 이 창을 닫아도 계속 돈다. 로그는 server/data/server.log 에 쌓인다.
  종료하려면 작업 관리자에서 node.exe 를 끝내거나 -Stop 을 쓴다.

.EXAMPLE
  # 보통은 관리자-실행.bat 을 더블클릭하면 된다.
  .\start-admin.ps1
  .\start-admin.ps1 -Stop      # 서버 종료
  .\start-admin.ps1 -NoBrowser # 서버만 띄우고 브라우저는 안 엶
#>

param(
    [int]$Port = 8090,
    [switch]$Stop,
    [switch]$NoBrowser
)

$ErrorActionPreference = "Stop"
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}

$ServerDir = Join-Path $PSScriptRoot "server"
$DataDir   = Join-Path $ServerDir "data"
$LogFile   = Join-Path $DataDir "server.log"
$Url       = "http://localhost:$Port"

function Ok($t)   { Write-Host "  [OK]  $t" -ForegroundColor Green }
function Info($t) { Write-Host "  ->    $t" -ForegroundColor Gray }
function Warn($t) { Write-Host "  [!]   $t" -ForegroundColor Yellow }
# 여기서 입력을 기다리지 않는다 — 호출부인 관리자-실행.bat 이 errorlevel 1 일 때 pause 한다.
# 양쪽에서 다 기다리면 프롬프트가 두 번 뜨고, 콘솔이 없는 환경에선 영영 멈춘다.
function Die($t)  {
    Write-Host ""; Write-Host "  [실패] $t" -ForegroundColor Red; Write-Host ""
    exit 1
}

Write-Host ""
Write-Host "┌───────────────────────────────────────────────┐" -ForegroundColor Cyan
Write-Host "│   두비덥 키오스크 — PC 관리자 실행             │" -ForegroundColor Cyan
Write-Host "└───────────────────────────────────────────────┘" -ForegroundColor Cyan

# 포트를 잡고 있는 프로세스 ID. 없으면 $null.
# Get-NetTCPConnection 이 없는 환경(구형/축소 설치)을 대비해 netstat 로 폴백한다.
function Get-ServerPid {
    try {
        $c = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction Stop | Select-Object -First 1
        if ($c) { return $c.OwningProcess }
    } catch {
        $line = @(netstat -ano | Select-String ":$Port\s+.*LISTENING") | Select-Object -First 1
        if ($line) { return [int](($line.ToString() -split '\s+')[-1]) }
    }
    return $null
}

# ---------- 종료 모드 ----------
if ($Stop) {
    $procId = Get-ServerPid
    if ($procId) {
        Stop-Process -Id $procId -Force
        Ok "서버를 종료했습니다 (PID $procId)"
    } else {
        Info "실행 중인 서버가 없습니다."
    }
    exit 0
}

# ---------- 1. 이미 떠 있나 ----------
$procId = Get-ServerPid
if ($procId) {
    Ok "이미 실행 중입니다 (PID $procId)"
} else {
    # ---------- 2. node 확인 ----------
    $node = (Get-Command node -ErrorAction SilentlyContinue).Source
    if (-not $node) {
        $c = "C:\Program Files\nodejs\node.exe"
        if (Test-Path -LiteralPath $c) { $node = $c }
    }
    if (-not $node) {
        Die "node.exe 를 찾을 수 없습니다.`n       Node.js 를 설치하세요: https://nodejs.org/"
    }
    if (-not (Test-Path -LiteralPath (Join-Path $ServerDir "server.js"))) {
        Die "server\server.js 가 없습니다. 이 스크립트는 DobedubKiosk 폴더 안에 있어야 합니다."
    }
    if (-not (Test-Path -LiteralPath (Join-Path $ServerDir "node_modules"))) {
        Die "server\node_modules 가 없습니다. 먼저 의존성을 설치하세요:`n       cd server; npm install"
    }

    # ---------- 3. 백그라운드 실행 ----------
    New-Item -ItemType Directory -Force -Path $DataDir | Out-Null
    Info "서버를 시작합니다..."
    Start-Process -FilePath $node -ArgumentList "server.js" `
        -WorkingDirectory $ServerDir -WindowStyle Hidden `
        -RedirectStandardOutput $LogFile -RedirectStandardError "$LogFile.err"

    # ---------- 4. 준비될 때까지 대기 ----------
    # 즉시 브라우저를 열면 연결 거부 화면이 뜬다 — /health 가 ok 를 줄 때까지 기다린다.
    $ready = $false
    foreach ($i in 1..30) {
        Start-Sleep -Milliseconds 500
        try {
            if ((Invoke-WebRequest -Uri "$Url/health" -UseBasicParsing -TimeoutSec 2).Content -eq 'ok') {
                $ready = $true; break
            }
        } catch { }
    }
    if (-not $ready) {
        Warn "서버가 15초 안에 응답하지 않았습니다. 로그를 확인하세요:"
        Info $LogFile
        if (Test-Path -LiteralPath "$LogFile.err") { Get-Content "$LogFile.err" -Tail 20 }
        Die "서버 시작 실패"
    }
    Ok "서버 준비 완료 (PID $(Get-ServerPid))"
}

# ---------- 5. 접속 정보 ----------
Write-Host ""
Info "주소: $Url/dashboard"
$pwFile = Join-Path $DataDir "admin-password.txt"
if (Test-Path -LiteralPath $pwFile) {
    Info "비밀번호: $((Get-Content -LiteralPath $pwFile -Raw).Trim())"
}
# 같은 와이파이의 태블릿이 이 PC를 가리킬 때 쓸 주소.
$lanIp = (Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
          Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' } |
          Select-Object -First 1).IPAddress
if ($lanIp) { Info "태블릿용 주소(같은 와이파이): http://${lanIp}:$Port" }

# NetBird 가 연결돼 있으면 다른 망의 태블릿이 쓸 고정 주소도 함께 보여준다.
# (원격관리_NetBird_도입.md — 태블릿은 어느 망에 있든 이 주소 하나로 체크인)
$netbird = (Get-Command netbird -ErrorAction SilentlyContinue).Source
if (-not $netbird) {
    $c = "C:\Program Files\NetBird\netbird.exe"
    if (Test-Path -LiteralPath $c) { $netbird = $c }
}
if ($netbird) {
    $nbIp = (& $netbird status 2>$null | Select-String 'NetBird IP:') -replace '.*NetBird IP:\s*', '' -replace '/.*', ''
    if ($nbIp) { Info "태블릿용 주소(다른 망, NetBird): http://${nbIp}:$Port  ← 도서관 배포 기기는 이쪽" }
    else { Warn "NetBird 데몬이 연결 안 됨 — 다른 망 태블릿이 접속 못 합니다. 확인: netbird status" }
}

if (-not $NoBrowser) {
    Start-Process "$Url/dashboard"
    Ok "브라우저를 열었습니다."
}

Write-Host ""
Write-Host "  서버는 이 창을 닫아도 계속 실행됩니다." -ForegroundColor Cyan
Write-Host "  종료하려면:  관리자-종료.bat" -ForegroundColor Cyan
Write-Host ""
Start-Sleep -Seconds 3
