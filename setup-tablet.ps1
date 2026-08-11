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
    5.5 화면 회전 고정 — 자동회전/스마트회전 끄고 세로 고정
    6. 기본 앱(블로트웨어) 정리 — 키오스크에 불필요한 소비자 앱만 제거
    7. 샘플 동영상 투입 (이미 있으면 건너뜀)
    8. 앱 실행 + 최종 검증

  핵심 제약(회피 불가): Device Owner 지정은 기기에 계정이 하나도 없어야 성공한다.
  계정이 남아있으면 이 스크립트가 감지해 안내하며, 계정 삭제 또는 공장초기화가 필요하다.

.EXAMPLE
  # 보통은 태블릿-세팅.bat 을 더블클릭하면 된다. 수동 실행 시:
  powershell -ExecutionPolicy Bypass -File .\setup-tablet.ps1
  .\setup-tablet.ps1 -SkipVideo          # 영상 투입 생략
  .\setup-tablet.ps1 -SkipWebView        # WebView 업데이트 생략
  .\setup-tablet.ps1 -SkipDebloat        # 기본 앱 정리 생략
  .\setup-tablet.ps1 -ForceVideo         # 같은 영상이 있어도 다시 push
#>

param(
    [string]$ApkPath       = "",   # 비우면 릴리스 APK 우선, 없으면 디버그 APK 자동 선택
    [string]$WebViewApk    = "",                       # 비우면 상위 폴더에서 자동 탐색
    [string]$VideoDir      = "$PSScriptRoot\..",       # 이 폴더(하위 포함)에서 *.mp4 검색
    [string]$PackageName   = "com.dobedub.kiosk",
    [string]$AdminReceiver = "com.dobedub.kiosk/.kiosk.AdminReceiver",
    [string]$LibrarySubdomain = "",   # 예: splib  → https://splib.dobedub.com/home (기기마다 다름)
    [string]$StartUrl      = "",       # 전체 URL 직접 지정(있으면 서브도메인보다 우선)
    [switch]$SkipVideo,
    [switch]$SkipWebView,
    [switch]$SkipDebloat,
    [switch]$ForceVideo
)

# ─────────────────────────────────────────────────────────────────────────────
# 기본 탑재(블로트웨어) 중 "제거해도 키오스크 동작에 지장 없는 소비자 앱"만 큐레이션한 목록.
# pm uninstall --user 0 로 현재 사용자에서만 제거한다(루트 불필요, 공장초기화로 복구 가능).
#
# 절대 넣지 말 것(키오스크/시스템 필수): com.google.android.webview(웹툰 리더),
#   com.google.android.inputmethod.latin(키보드), com.android.systemui, com.android.settings,
#   *.launcher / launcher.provider(홈 폴백), com.google.android.gms/gsf, com.android.vending,
#   packageinstaller, permissioncontroller, networkstack, dolby.*(오디오) 등.
# 불확실한 OEM 시스템성 앱(com.lenovo.smartnavigation, com.tblenovo.landscapevision 등)도 제외.
# 이 목록은 Lenovo TB-J606F(Android 11) 실제 패키지 기준으로 작성됨.
# ─────────────────────────────────────────────────────────────────────────────
$BloatPackages = @(
    # --- Google 소비자 앱 ---
    "com.android.chrome",                               # 크롬(브라우저) — 키오스크는 자체 WebView 사용
    "com.google.android.googlequicksearchbox",          # Google 앱/검색
    "com.google.android.apps.googleassistant",          # 어시스턴트
    "com.google.android.apps.maps",                     # 지도
    "com.google.android.apps.photos",                   # 포토
    "com.google.android.apps.docs",                     # 드라이브/문서
    "com.google.android.apps.books",                    # Play 도서
    "com.google.android.videos",                        # Play 영화
    "com.google.android.youtube",                       # 유튜브
    "com.google.android.apps.youtube.music",            # 유튜브 뮤직
    "com.google.android.apps.youtube.music.setupwizard",
    "com.google.android.gm",                            # 지메일
    "com.google.android.calendar",                      # 캘린더
    "com.google.android.contacts",                      # 연락처
    "com.google.android.deskclock",                     # 시계
    "com.google.android.calculator",                    # 계산기
    "com.google.android.keep",                          # Keep 메모
    "com.google.android.apps.nbu.files",                # Files by Google
    "com.google.android.apps.tachyon",                  # Meet(듀오)
    "com.google.android.apps.subscriptions.red",        # Google One
    "com.google.android.apps.kids.home",                # 키즈 스페이스
    "com.google.android.apps.wellbeing",                # 디지털 웰빙
    "com.google.android.apps.mediahome.launcher",
    # --- Microsoft / Netflix ---
    "com.microsoft.office.officehubrow",                # Office
    "com.microsoft.office.onenote",                     # OneNote
    "com.microsoft.bing.wallpapers",                    # Bing 배경화면
    "com.netflix.mediaclient",                          # 넷플릭스
    # --- Lenovo/OEM 소비자 앱 & 데모 ---
    "com.motorola.demo",                                # 매장 데모
    "com.tblenovo.center",                              # Lenovo 프로모/스토어 허브
    "com.tblenovo.lenovowhatsnew",                      # What's New
    "com.lenovo.penmenu",                               # 펜 메뉴(스타일러스)
    "com.wacom.bamboopapertab",                         # Wacom 노트
    "com.steadfastinnovation.android.projectpapyrus"    # Squid 노트
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
# adb 는 정상 상황에서도 stderr 를 쓰므로, $ErrorActionPreference=Stop 이 이를 종료 오류로
# 취급하지 않도록 함수 안에서만 Continue 로 낮춘다.
function Adb {
    $old = $ErrorActionPreference; $ErrorActionPreference = "Continue"
    try {
        $out = & $adb @args 2>&1 | ForEach-Object { "$_" }
        return [pscustomobject]@{ Code = $LASTEXITCODE; Text = ($out -join "`n") }
    } finally { $ErrorActionPreference = $old }
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
# 이후 모든 adb 호출을 이 기기로 고정 (stderr 가 종료 오류가 되지 않도록 동일하게 방어)
function Adb {
    $old = $ErrorActionPreference; $ErrorActionPreference = "Continue"
    try {
        $out = & $adb -s $serial @args 2>&1 | ForEach-Object { "$_" }
        return [pscustomobject]@{ Code = $LASTEXITCODE; Text = ($out -join "`n") }
    } finally { $ErrorActionPreference = $old }
}

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

# ---------- 2.5 도서관 주소 입력 ----------
# 도서관마다 서브도메인이 다르므로(예: splib) 이 기기의 시작 주소를 정한다.
Head "2.5 도서관 주소"
if (-not $StartUrl -and -not $LibrarySubdomain) {
    Write-Host "  이 태블릿이 접속할 도서관 주소를 정합니다." -ForegroundColor Gray
    Write-Host "  서브도메인만 입력하면 됩니다.  예) splib  →  https://splib.dobedub.com/home" -ForegroundColor Gray
    $LibrarySubdomain = Read-Host "  도서관 서브도메인 (전체 URL 붙여넣기도 가능, 비우면 앱 기본값 유지)"
}
if (-not $StartUrl -and $LibrarySubdomain) {
    if ($LibrarySubdomain -match '^https?://') { $StartUrl = $LibrarySubdomain.Trim() }
    else { $StartUrl = "https://$($LibrarySubdomain.Trim()).dobedub.com/home" }
}
# 백오피스 식별용 라벨 = 서브도메인(호스트 첫 토큰)
$LibLabel = ""
if ($LibrarySubdomain -and $LibrarySubdomain -notmatch '^https?://') {
    $LibLabel = $LibrarySubdomain.Trim()
} elseif ($StartUrl -match '^https?://([^./]+)\.') {
    $LibLabel = $Matches[1]
}
if ($StartUrl) { Ok "시작 주소: $StartUrl  (기관 라벨: $LibLabel)" }
else { Warn "주소 미입력 — 앱 기본값(https://splib.dobedub.com/home) 유지" }

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
# APK 자동 선택: 릴리스(고정 키 서명, 자동 업데이트 호환) 우선, 없으면 디버그로 폴백.
if (-not $ApkPath) {
    $relApk = Join-Path $PSScriptRoot "app\build\outputs\apk\release\app-release.apk"
    $dbgApk = Join-Path $PSScriptRoot "app\build\outputs\apk\debug\app-debug.apk"
    if (Test-Path -LiteralPath $relApk) {
        $ApkPath = $relApk
    } elseif (Test-Path -LiteralPath $dbgApk) {
        $ApkPath = $dbgApk
        Warn "릴리스 APK가 없어 디버그 APK로 설치합니다. 디버그 서명은 원격 자동 업데이트와 호환되지 않습니다."
        Warn "운영 납품 시에는 'gradlew assembleRelease'(키스토어 보유 PC)로 만든 릴리스 APK를 사용하세요."
    }
}
if (-not $ApkPath -or -not (Test-Path -LiteralPath $ApkPath)) {
    Die "APK 를 찾을 수 없습니다.`n       먼저 빌드하세요:  gradlew assembleRelease  (또는 assembleDebug)"
}
$apkBuilt = (Get-Item -LiteralPath $ApkPath).LastWriteTime
Info "설치: $(Split-Path $ApkPath -Leaf)  (빌드시각 $($apkBuilt.ToString('MM-dd HH:mm')))"
if (((Get-Date) - $apkBuilt).TotalHours -gt 12) {
    Warn "이 APK는 12시간 이상 전에 빌드됐습니다. 최신 코드가 맞는지 확인하세요(필요시 gradlew assembleRelease 재빌드)."
}
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

# ---------- 5.5 화면 회전 고정 (세로) ----------
# 앱 자체는 매니페스트로 portrait 고정이지만, 그것만으로는 홈/설정/시스템UI 가 가로로 돌아간다.
# OS 레벨에서 자동회전과 Lenovo '스마트 회전'을 모두 끄고 0도(세로)로 못박는다.
Head "5.5 화면 회전 고정 (세로)"
$RotationSettings = @(
    @{ Ns = "system"; Key = "accelerometer_rotation"; Val = "0"; Desc = "자동 회전 끄기" },
    @{ Ns = "system"; Key = "user_rotation";          Val = "0"; Desc = "세로(0도) 고정" },
    @{ Ns = "secure"; Key = "smartrotate_is_show";    Val = "0"; Desc = "Lenovo 스마트 회전 끄기" },
    @{ Ns = "secure"; Key = "camera_autorotate";      Val = "0"; Desc = "얼굴인식 자동회전 끄기 (Android 12+)" }
)
foreach ($s in $RotationSettings) {
    Adb shell settings put $s.Ns $s.Key $s.Val | Out-Null
    $now = (Adb shell settings get $s.Ns $s.Key).Text.Trim()
    if ($now -eq $s.Val) { Ok "$($s.Desc)  ($($s.Ns)/$($s.Key)=$now)" }
    else { Warn "$($s.Desc) 적용 안 됨 (현재값: $now) — 이 기종에 없는 설정일 수 있습니다" }
}

# ---------- 6. 기본 앱(블로트웨어) 정리 ----------
if (-not $SkipDebloat) {
    Head "6. 기본 앱 정리 (불필요한 선탑재 앱 제거)"
    Info "키오스크에 불필요한 소비자 앱만 제거합니다 (WebView/키보드/시스템은 유지, 공장초기화로 복구 가능)."
    $removed = 0; $absent = 0; $failed = 0
    foreach ($pkg in $BloatPackages) {
        $res = Adb shell pm uninstall --user 0 $pkg
        if ($res.Text -match "Success") {
            Ok "제거: $pkg"; $removed++
        } elseif ($res.Text -match "not installed for 0|Unknown package|not installed|DELETE_FAILED_INTERNAL_ERROR.*not installed") {
            $absent++   # 이미 없거나 이 사용자에 미설치 — 조용히 통과
        } else {
            Warn "제거 실패(건너뜀): $pkg — $($res.Text.Trim())"; $failed++
        }
    }
    Info "정리 결과 — 제거 $removed / 이미없음 $absent / 실패 $failed (총 $($BloatPackages.Count)개 시도)"
} else {
    Head "6. 기본 앱 정리 (건너뜀: -SkipDebloat)"
}

# ---------- 7. 샘플 동영상 투입 ----------
if (-not $SkipVideo) {
    Head "7. 샘플 동영상 투입"
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
                # 공백 있는 원격 경로는 device 셸에서 단일따옴표로 감싸야 안 쪼개진다.
                # (adb shell 은 인자들을 공백으로 이어 셸에 넘기므로 PowerShell 따옴표는 소실됨)
                $stat = (Adb shell "stat -c %s '$remote'").Text.Trim()
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
    Head "7. 동영상 투입 (건너뜀: -SkipVideo)"
}

# ---------- 8. 실행 + 검증 ----------
Head "8. 앱 실행 및 검증"
if ($StartUrl) {
    # 도서관 주소/기관명을 앱에 전달해 이 기기 설정으로 저장시킨다.
    # 값에 공백이 있어도 안전하도록 device 셸용 단일따옴표로 감싼다(am 인자는 공백에서 쪼개짐).
    Adb shell "am start -n $PackageName/.MainActivity --es kiosk_start_url '$StartUrl' --es kiosk_label '$LibLabel'" | Out-Null
    Ok "도서관 주소를 기기에 설정: $StartUrl"
} else {
    Adb shell am start -n "$PackageName/.MainActivity" | Out-Null
}
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
