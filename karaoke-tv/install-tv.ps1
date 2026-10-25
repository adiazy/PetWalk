# Builds Karaoke TV, installs it on the TV over Wi-Fi and opens it.
# Usage (from the karaoke-tv folder):  .\install-tv.ps1
#        or for another TV:            .\install-tv.ps1 -Tv 192.168.1.50
param(
    [string]$Tv = "192.168.1.69"
)

$ErrorActionPreference = "Stop"
$device = if ($Tv -like "*:*") { $Tv } else { "${Tv}:5555" }

# Find adb (installed with Android Studio).
$platformTools = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools"
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    if (-not (Test-Path (Join-Path $platformTools "adb.exe"))) {
        Write-Host "adb not found. In Android Studio: Settings > Android SDK > SDK Tools > tick 'Android SDK Platform-Tools'." -ForegroundColor Red
        exit 1
    }
    $env:Path += ";$platformTools"
}

Write-Host "1/4 Connecting to the TV at $device ..." -ForegroundColor Cyan
adb connect $device | Out-Host
$state = (adb -s $device get-state 2>&1) -join " "
if ($state -notmatch "device") {
    Write-Host "The TV isn't ready ($state)." -ForegroundColor Red
    Write-Host "Accept the 'Allow USB debugging?' prompt on the TV (tick 'Always allow') and run this script again." -ForegroundColor Yellow
    exit 1
}

Write-Host "2/4 Building the app ..." -ForegroundColor Cyan
& "$PSScriptRoot\gradlew.bat" -p $PSScriptRoot :app:assembleDebug
if ($LASTEXITCODE -ne 0) { Write-Host "Build failed; see the errors above." -ForegroundColor Red; exit 1 }

$apk = Join-Path $PSScriptRoot "app\build\outputs\apk\debug\app-debug.apk"
Write-Host "3/4 Installing on the TV (can take 30 seconds over Wi-Fi) ..." -ForegroundColor Cyan
$result = (adb -s $device install -r $apk 2>&1) -join "`n"
Write-Host $result
if ($result -notmatch "Success") {
    Write-Host "Install failed. Copy the message above and send it to Claude." -ForegroundColor Red
    exit 1
}

# Use the app ID the build actually produced (it may differ from com.karaoketv).
$metadata = Join-Path $PSScriptRoot "app\build\outputs\apk\debug\output-metadata.json"
$package = (Get-Content $metadata -Raw | ConvertFrom-Json).applicationId
Write-Host "4/4 Opening Karaoke TV ($package) ..." -ForegroundColor Cyan

# Ask the TV which screen its launcher opens for this app, then start that one.
$activity = $null
foreach ($category in "android.intent.category.LEANBACK_LAUNCHER", "android.intent.category.LAUNCHER") {
    $resolved = (adb -s $device shell cmd package resolve-activity --brief -c $category $package 2>&1 |
        Select-Object -Last 1).ToString().Trim()
    if ($resolved -match "/") { $activity = $resolved; break }
}
if (-not $activity) {
    Write-Host "The TV has $package installed but found no screen to open." -ForegroundColor Red
    Write-Host "Send Claude the output of:  findstr /n /i ""activity category"" app\src\main\AndroidManifest.xml" -ForegroundColor Yellow
    exit 1
}

$start = (adb -s $device shell am start -n $activity 2>&1) -join "`n"
Write-Host $start
if ($start -match "Error") {
    Write-Host "Couldn't open $activity. Copy the message above and send it to Claude." -ForegroundColor Red
    exit 1
}
Write-Host "Done! Karaoke TV is open on the TV." -ForegroundColor Green
