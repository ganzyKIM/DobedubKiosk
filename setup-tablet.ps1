<#
.SYNOPSIS
  두비덥 도서관 키오스크 태블릿 원클릭 세팅 스크립트.

.DESCRIPTION
  USB로 연결된 태블릿 1대에 대해 아래를 순서대로, 가능한 한 자동으로 수행한다.
    0. adb 실행파일 자동 탐색 (PATH / SDK / 스크립트 옆 platform-tools)
    1. 태블릿 연결 대기 (케이블 꽂을 때까지 기다림 + USB 디버깅 허용 안내)
    2. 사전 점검 — 기기 정보, 이미 Device Owner인지, 계정 존재 여부
    3. Android System WebView 업데이트 (동봉 APK)
    4. 키오스크 APK 설치
    5. Device Owner 지정
    6. 샘플 동영상 투입 (이미 있으면 건너뜀)
    7. 앱 실행 + 최종 검증

  핵심 제약(회피 불가): Device Owner 지정은 기기에 계정이 하나도 없어야 성공한다.
  계정이 남아있으면 이 스크립트가 감지해 안내하며, 계정 삭제 또는 공장초기화가 필요하다.

.EXAMPLE
  # 보통은 태블릿-세팅.bat 을 더블클릭하면 된다. 수동 실행 시:
  powershell -ExecutionPolicy Bypass -File .\setup-tablet.ps1
  .\setup-tablet.ps1 -SkipVideo          # 영상 투입 생략
  .\setup-tablet.ps1 -SkipWebView        # WebView 업데이트 생략
  .\setup-tablet.ps1 -ForceVideo         # 같은 영상이 있어도 다시 push
#>

param(
    [string]$ApkPath       = "$PSScriptRoot\app\build\outputs\apk\debug\app-debug.apk",
    [string]$WebViewApk    = "",                       # 비우면 상위 폴더에서 자동 탐색
    [string]$VideoDir      = "$PSScriptRoot\..",       # 이 폴더(하위 포함)에서 *.mp4 검색
    [string]$PackageName   = "com.dobedub.kiosk",
    [string]$AdminReceiver = "com.dobedub.kiosk/.kiosk.AdminReceiver",
    [switch]$SkipVideo,
    [switch]$SkipWebView,
    [switch]$ForceVideo
)

$ErrorActionPreference = "Stop"
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}

# ---------- 출력 도우미 ----------
function Head($t) { Write-Host ""; Write-Host "══ $t ══" -ForegroundColor Cyan }
function Ok($t)   { Write-Host "  [OK]  $t" -ForegroundColor Green }
function Info($t) { Write-Host "  ->    $t" -ForegroundColor Gray }
function Warn($t) { Write-Host "  [!]   $t" -ForegroundColor Yellow }
function Pause-End { try { Read-Host "엔터를 누르면 종료합니다" | Out-Null } catch {} }  # 비대화형 실행 시 조용히 통과
function Die($t)  { Write-Host ""; Write-Host "  [실패] $t" -ForegroundColor Red; Write-Host ""; Pause-End; exit 1 }

Write-Host ""
Write-Host "┌───────────────────────────────────────────────┐" -ForegroundColor Cyan
Write-Host "│   두비덥 도서관 키오스크 — 태블릿 원클릭 세팅  │" -ForegroundColor Cyan
Write-Host "└───────────────────────────────────────────────┘" -ForegroundColor Cyan

# ---------- 0. adb 탐색 ----------
Head "0. adb 준비"
function Find-Adb {
    # 환경변수가 비어있을 수 있으므로 Join-Path 전에 걸러낸다(없는 변수로 Join-Path 하면 예외).
    $roots = @(
        $PSScriptRoot,
        $env:LOCALAPPDATA,
        $env:ANDROID_HOME,
        $env:ANDROID_SDK_ROOT
    ) | Where-Object { $_ }
    $subpaths = @("platform-tools\adb.exe", "Android\Sdk\platform-tools\adb.exe")
    foreach ($root in $roots) {
        foreach ($sub in $subpaths) {
            $c = Join-Path $root $sub
            if (Test-Path -LiteralPath $c) { return $c }
        }
    }
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    return $null
}
$adb = Find-Adb
if (-not $adb) {
    Die "adb.exe 를 찾을 수 없습니다.`n       Android SDK platform-tools 를 설치하거나, platform-tools 폴더를 이 스크립트 옆에 두세요.`n       (다운로드: https://developer.android.com/tools/releases/platform-tools )"
}
Ok "adb: $adb"

# adb 를 감싸 호출하고 (표준출력+표준에러) 문자열과 종료코드를 함께 돌려준다.
function Adb {
    $out = & $adb @args 2>&1 | ForEach-Object { "$_" }
    return [pscustomobject]@{ Code = $LASTEXITCODE; Text = ($out -join "`n") }
}

& $adb start-server | Out-Null

# ---------- 1. 기기 연결 대기 ----------
Head "1. 태블릿 연결"
Info "USB 케이블로 태블릿을 PC에 연결하세요."
$serial   = $null
$deadline = (Get-Date).AddMinutes(5)
while (-not $serial) {
    $r = Adb devices
    # "XXWWZZ    device" 형태만 채택 (unauthorized/offline 제외)
    # @() 로 배열 강제: 매칭 줄이 1개뿐이면 스칼라 문자열이 되어 [0]이 첫 '글자'를 집는 버그 방지
    $ready  = @($r.Text -split "`n" | Where-Object { $_ -match "^\S+\s+device$" })
    $unauth = @($r.Text -split "`n" | Where-Object { $_ -match "^\S+\s+unauthorized$" })
    if ($ready) {
        $serial = ($ready[0] -split "\s+")[0]
        break
    }
    if ($unauth) {
        Warn "태블릿 화면에 뜬 'USB 디버깅을 허용하시겠습니까?' 에서 [허용]을 눌러주세요. (항상 허용 체크 권장)"
    }
    if ((Get-Date) -gt $deadline) {
        Die "5분 동안 태블릿을 찾지 못했습니다.`n       - 케이블이 데이터 전송용인지 (충전 전용 아님) 확인`n       - 태블릿: 설정 > 태블릿 정보 > 빌드번호 7회 탭 → 개발자 옵션 > USB 디버깅 켜기"
    }
    Start-Sleep -Seconds 2
}
Ok "연결됨: $serial"
# 이후 모든 adb 호출을 이 기기로 고정
function Adb { $out = & $adb -s $serial @args 2>&1 | ForEach-Object { "$_" }; return [pscustomobject]@{ Code = $LASTEXITCODE; Text = ($out -join "`n") } }

# ---------- 2. 사전 점검 ----------
Head "2. 사전 점검"
$model = (Adb shell getprop ro.product.model).Text.Trim()
$rel   = (Adb shell getprop ro.build.version.release).Text.Trim()
$sdk   = (Adb shell getprop ro.build.version.sdk).Text.Trim()
Info "모델: $model / Android $rel (API $sdk)"

# 이미 Device Owner 인가?
$dpText = (Adb shell dumpsys device_policy).Text
$alreadyOwner = ($dpText -match "Device Owner" -and $dpText -match [regex]::Escape($PackageName))
if ($alreadyOwner) {
    Ok "이미 이 앱이 Device Owner 로 지정되어 있습니다. (Device Owner 단계는 건너뜁니다)"
}

# 계정 존재 여부 (Device Owner 지정을 막는 핵심 요인)
$acctCount = 0
$acctRaw = (Adb shell dumpsys account).Text
if ($acctRaw) {
    $acctCount = ([regex]::Matches($acctRaw, "Account\s*\{")).Count
}
if (-not $alreadyOwner) {
    if ($acctCount -gt 0) {
        Write-Host ""
        Warn "이 태블릿에 계정이 $acctCount 개 등록되어 있습니다."
        Warn "계정이 하나라도 있으면 Device Owner 지정이 불가능합니다 (Android 보안 정책)."
        Write-Host ""
        Write-Host "     해결 방법 (둘 중 하나):" -ForegroundColor Yellow
        Write-Host "       A) 계정만 삭제: 설정 > 계정 > 각 계정 > 계정 삭제  (모두 삭제 후 이 스크립트 재실행)" -ForegroundColor Yellow
        Write-Host "       B) 공장초기화: 설정 > 시스템 > 초기화  (초기 설정에서 Wi-Fi/계정 로그인은 건너뛰기)" -ForegroundColor Yellow
        Write-Host ""
        $open = Read-Host "     지금 태블릿에서 [계정 설정] 화면을 열까요? (Y/N)"
        if ($open -match "^[Yy]") {
            Adb shell am start -a android.settings.SYNC_SETTINGS | Out-Null
            Info "태블릿 화면에서 계정을 모두 삭제한 뒤, 이 창을 닫고 스크립트를 다시 실행하세요."
        }
        Die "계정을 먼저 정리한 후 다시 실행해주세요."
    } else {
        Ok "등록된 계정 없음 — Device Owner 지정 가능"
    }
}

# ---------- 3. WebView 업데이트 ----------
if (-not $SkipWebView) {
    Head "3. Android System WebView 업데이트"
    if (-not $WebViewApk) {
        $wv = Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot "..") -Filter "com.google.android.webview*.apk" -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($wv) { $WebViewApk = $wv.FullName }
    }
    if ($WebViewApk -and (Test-Path -LiteralPath $WebViewApk)) {
        Info "설치: $(Split-Path $WebViewApk -Leaf)"
        $r = Adb install -r -d "$WebViewApk"
        if ($r.Code -eq 0 -or $r.Text -match "Success") {
            Ok "WebView 업데이트 완료"
        } elseif ($r.Text -match "VERSION_DOWNGRADE|INSTALL_FAILED_ALREADY_EXISTS") {
            Ok "이미 동일하거나 더 최신 WebView 가 설치되어 있습니다 (건너뜀)"
        } else {
            Warn "WebView 업데이트 실패 (계속 진행): $($r.Text.Trim())"
        }
    } else {
        Warn "WebView APK 를 찾지 못해 건너뜁니다. (필요 시 상위 폴더에 com.google.android.webview*.apk 배치)"
    }
} else {
    Head "3. WebView 업데이트 (건너뜀: -SkipWebView)"
}

# ---------- 4. 키오스크 APK 설치 ----------
Head "4. 키오스크 앱 설치"
if (-not (Test-Path -LiteralPath $ApkPath)) {
    Die "APK 를 찾을 수 없습니다: $ApkPath`n       먼저 빌드하세요:  gradlew assembleDebug"
}
Info "설치: $(Split-Path $ApkPath -Leaf)"
$r = Adb install -r "$ApkPath"
if ($r.Code -ne 0 -and $r.Text -notmatch "Success") {
    if ($r.Text -match "INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match") {
        Warn "기존 설치본과 서명이 다릅니다 (다른 PC에서 빌드된 앱이 이미 설치됨)."
        Info "기존 앱을 제거하고 재설치를 시도합니다..."
        if ($alreadyOwner) {
            Die "그런데 기존 앱이 Device Owner 라 adb 로 제거할 수 없습니다.`n       태블릿에서: 앱 관리자 메뉴 > 키오스크 완전 해제 실행 후, 다시 이 스크립트를 실행하세요.`n       (해제가 안 되면 공장초기화가 필요합니다)"
        }
        Adb uninstall "$PackageName" | Out-Null
        $r2 = Adb install "$ApkPath"
        if ($r2.Code -ne 0 -and $r2.Text -notmatch "Success") {
            Die "APK 재설치 실패: $($r2.Text.Trim())"
        }
        Ok "재설치 완료"
    } else {
        Die "APK 설치 실패: $($r.Text.Trim())"
    }
} else {
    Ok "앱 설치 완료"
}

# ---------- 5. Device Owner 지정 ----------
Head "5. Device Owner 지정 (키오스크 잠금 권한)"
if ($alreadyOwner) {
    Ok "이미 지정되어 있어 건너뜁니다."
} else {
    $r = Adb shell dpm set-device-owner "$AdminReceiver"
    if ($r.Text -match "Success" -and $r.Code -eq 0) {
        Ok "Device Owner 지정 완료"
    } elseif ($r.Text -match "already some accounts|already an account") {
        Die "계정이 남아있어 실패했습니다. 계정을 모두 삭제하고 다시 실행하세요."
    } elseif ($r.Text -match "already set|already a device owner|already provisioned") {
        Ok "이미 지정되어 있습니다."
    } else {
        Die "Device Owner 지정 실패:`n       $($r.Text.Trim())`n`n       대개 원인은 (1) 계정이 남아있음 (2) 이미 다른 프로필이 설정됨.`n       공장초기화 후 초기 설정에서 계정 로그인을 건너뛴 상태로 다시 시도하세요."
    }
}

# ---------- 6. 샘플 동영상 투입 ----------
if (-not $SkipVideo) {
    Head "6. 샘플 동영상 투입"
    $remoteDir = "/sdcard/Android/data/$PackageName/files/videos"
    $mp4s = @()
    if (Test-Path -LiteralPath $VideoDir) {
        $mp4s = Get-ChildItem -LiteralPath $VideoDir -Recurse -Filter "*.mp4" -ErrorAction SilentlyContinue |
                Where-Object { $_.FullName -notlike "*\DobedubKiosk\*" }
    }
    if (-not $mp4s -or $mp4s.Count -eq 0) {
        Warn "투입할 mp4 를 찾지 못해 건너뜁니다. (검색 위치: $VideoDir)"
    } else {
        Adb shell mkdir -p "$remoteDir" | Out-Null
        $fso = New-Object -ComObject Scripting.FileSystemObject
        foreach ($f in $mp4s) {
            $remote = "$remoteDir/$($f.Name)"
            $localSize = $f.Length
            if (-not $ForceVideo) {
                $stat = (Adb shell stat -c %s "`"$remote`"").Text.Trim()
                if ($stat -match "^\d+$" -and [int64]$stat -eq $localSize) {
                    Ok "이미 있음 (건너뜀): $($f.Name)"
                    continue
                }
            }
            $sizeMB = [math]::Round($localSize/1MB)
            Info "push ($sizeMB MB): $($f.Name)  — 크기에 따라 수 분 걸릴 수 있습니다"
            # 한글/대괄호 로컬 경로 안전화를 위해 8.3 단축경로 사용
            $shortPath = $fso.GetFile($f.FullName).ShortPath
            $r = Adb push "$shortPath" "$remote"
            if ($r.Code -eq 0 -or $r.Text -match "pushed") {
                Ok "완료: $($f.Name)"
            } else {
                Warn "push 실패 (계속): $($f.Name) — $($r.Text.Trim())"
            }
        }
    }
} else {
    Head "6. 동영상 투입 (건너뜀: -SkipVideo)"
}

# ---------- 7. 실행 + 검증 ----------
Head "7. 앱 실행 및 검증"
Adb shell am start -n "$PackageName/.MainActivity" | Out-Null
Start-Sleep -Seconds 2
$dp2 = (Adb shell dumpsys device_policy).Text
$ownerOk = ($dp2 -match "Device Owner" -and $dp2 -match [regex]::Escape($PackageName))
if ($ownerOk) { Ok "Device Owner 확인됨" } else { Warn "Device Owner 검증 실패 — 위 로그를 확인하세요" }

$pkgInstalled = (Adb shell pm list packages "$PackageName").Text -match [regex]::Escape($PackageName)
if ($pkgInstalled) { Ok "앱 설치 확인됨" } else { Warn "앱 설치 검증 실패" }

# ---------- 완료 ----------
Write-Host ""
if ($ownerOk -and $pkgInstalled) {
    Write-Host "┌───────────────────────────────────────────────┐" -ForegroundColor Green
    Write-Host "│   세팅 완료! 태블릿이 키오스크로 잠겼습니다.   │" -ForegroundColor Green
    Write-Host "└───────────────────────────────────────────────┘" -ForegroundColor Green
    Write-Host ""
    Write-Host "  남은 확인(태블릿에서 직접):" -ForegroundColor Cyan
    Write-Host "   1) 홈 화면 로고를 5회 탭 → 기본 PIN 0000 으로 관리자 진입"
    Write-Host "   2) PIN 변경, 시작 URL / 허용 도메인 확인"
    Write-Host "   3) '동영상 보기' 에 방금 넣은 영상이 보이는지 확인"
    Write-Host "   4) '도서관 웹사이트' 진입 → 웹툰 재생 정상 동작 확인"
} else {
    Write-Host "  일부 단계가 완전히 끝나지 않았습니다. 위의 [!] 항목을 확인하세요." -ForegroundColor Yellow
}
Write-Host ""
Pause-End
