<#
.SYNOPSIS
  (구버전 호환용) 태블릿 프로비저닝 스크립트 — 이제 setup-tablet.ps1 로 통합되었습니다.

.DESCRIPTION
  기존 문서/습관에서 provision-kiosk.ps1 을 부르던 것을 깨지 않기 위한 얇은 래퍼입니다.
  실제 로직은 모두 setup-tablet.ps1 에 있고, 더 간편하게는 태블릿-세팅.bat 을 더블클릭하면 됩니다.

.EXAMPLE
  ./provision-kiosk.ps1
  ./provision-kiosk.ps1 -SkipSampleVideo   # (구옵션) -> setup-tablet.ps1 의 -SkipVideo 로 전달
#>

param(
    [switch]$SkipSampleVideo,
    [Parameter(ValueFromRemainingArguments = $true)]
    $Rest
)

Write-Host "provision-kiosk.ps1 은 setup-tablet.ps1 로 통합되었습니다. 이를 대신 실행합니다..." -ForegroundColor Yellow

$forward = @()
if ($SkipSampleVideo) { $forward += "-SkipVideo" }
if ($Rest) { $forward += $Rest }

& "$PSScriptRoot\setup-tablet.ps1" @forward
