<#
.SYNOPSIS
  두비덥 도서관 키오스크 태블릿(Lenovo TB-J606F) 납품 프로비저닝 스크립트.

.DESCRIPTION
  공장초기화 직후, 계정 로그인 없이(또는 로그인 화면을 건너뛴 상태로) USB로 PC와 연결된
  태블릿 1대에 대해 아래를 순서대로 수행한다:
    1. adb 연결 확인
    2. 디버그 APK 설치
    3. Device Owner 지정 (adb shell dpm set-device-owner)
    4. (옵션) 샘플 동영상 1편을 앱 전용 videos 폴더로 push

  주의: Device Owner 지정은 기기에 구글 계정이 하나도 없는 상태에서만 성공한다.
  이미 계정이 등록된 기기는 공장초기화부터 다시 해야 한다. (기획문서.md §2.2, §10 참고)

.EXAMPLE
  ./provision-kiosk.ps1
  ./provision-kiosk.ps1 -SkipSampleVideo
#>

param(
    [string]$ApkPath = "$PSScriptRoot\app\build\outputs\apk\debug\app-debug.apk",
    [string]$PackageName = "com.dobedub.kiosk",
    [string]$AdminReceiver = "com.dobedub.kiosk/.kiosk.AdminReceiver",
    [string]$SampleVideoPath = "$PSScriptRoot\..\[두비덥 보이스툰] 그리스 로마 신화 - 아르테미스의 분노와 사랑 1\[두비덥 보이스툰] 그리스 로마 신화 - 아르테미스의 분노와 사랑 1.mp4",
    [switch]$SkipSampleVideo
)

$ErrorActionPreference = "Stop"

function Assert-Success($what) {
    if ($LASTEXITCODE -ne 0) {
        Write-Error "실패: $what (exit code $LASTEXITCODE)"
        exit 1
    }
}

Write-Host "== 1. adb 기기 연결 확인 ==" -ForegroundColor Cyan
adb start-server | Out-Null
$devices = adb devices | Select-String "device$"
if (-not $devices) {
    Write-Error "연결된 기기가 없습니다. USB로 태블릿을 연결하고 USB 디버깅을 허용했는지 확인하세요."
    exit 1
}
adb devices

Write-Host "`n== 2. APK 설치 ($ApkPath) ==" -ForegroundColor Cyan
if (-not (Test-Path $ApkPath)) {
    Write-Error "APK를 찾을 수 없습니다: $ApkPath  먼저 './gradlew assembleDebug' 를 실행하세요."
    exit 1
}
adb install -r "$ApkPath"
Assert-Success "APK 설치"

Write-Host "`n== 3. Device Owner 지정 ==" -ForegroundColor Cyan
Write-Host "기기에 구글 계정이 없는 상태여야 성공합니다 (공장초기화 직후)." -ForegroundColor Yellow
adb shell dpm set-device-owner "$AdminReceiver"
Assert-Success "Device Owner 지정 (계정이 이미 등록되어 있다면 공장초기화 후 재시도하세요)"

if (-not $SkipSampleVideo) {
    Write-Host "`n== 4. 샘플 동영상 투입 ==" -ForegroundColor Cyan
    if (Test-Path $SampleVideoPath) {
        $videosDir = "/sdcard/Android/data/$PackageName/files/videos"
        adb shell mkdir -p "$videosDir"
        $remoteName = (Split-Path $SampleVideoPath -Leaf)
        adb push "$SampleVideoPath" "$videosDir/$remoteName"
        Assert-Success "샘플 동영상 push"
    } else {
        Write-Host "샘플 동영상을 찾을 수 없어 건너뜁니다: $SampleVideoPath" -ForegroundColor Yellow
    }
}

Write-Host "`n완료되었습니다." -ForegroundColor Green
Write-Host "앱을 실행해 관리자 PIN(기본값 0000)으로 진입 후, 최초 PIN 변경 및 콘텐츠 설정(시작 URL/허용 도메인)을 확인하세요."
