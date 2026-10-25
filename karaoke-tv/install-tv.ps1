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

Write-Host "4/4 Opening Karaoke TV ..." -ForegroundColor Cyan
adb -s $device shell am start -n com.karaoketv/.MainActivity | Out-Host
Write-Host "Done! Karaoke TV should now be on the TV screen." -ForegroundColor Green
