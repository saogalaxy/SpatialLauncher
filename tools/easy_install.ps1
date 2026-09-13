# Spatial Launcher Easy Installer (Quest)
# Checks JDK / Node / headset, builds the debug APK if needed, installs with metavr.

param(
    [string]$Device = "",
    [switch]$SkipBuild,
    [switch]$NoLaunch
)

$ErrorActionPreference = "Continue"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root
$PackageId = "com.spatiallauncher.app"
$ApkPath = Join-Path $Root "app\build\outputs\apk\debug\app-debug.apk"

function Write-Step([string]$Message) {
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Fail([string]$Message) {
    Write-Host ""
    Write-Host "READY TO GO: NO" -ForegroundColor Red
    Write-Host $Message -ForegroundColor Yellow
    exit 1
}

function Refresh-Path {
    $machine = [System.Environment]::GetEnvironmentVariable("Path", "Machine")
    $user = [System.Environment]::GetEnvironmentVariable("Path", "User")
    $env:Path = "$machine;$user"
}

function Test-Java {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
        return $true
    }
    $java = Get-Command java -ErrorAction SilentlyContinue
    return [bool]$java
}

function Invoke-Metavr {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args)
    & npx -y metavr @Args
    return $LASTEXITCODE
}

Write-Host ""
Write-Host "Spatial Launcher Easy Installer" -ForegroundColor White
Write-Host "Looks for what is needed, builds if needed, installs to Quest." -ForegroundColor DarkGray

Write-Step "Checking JDK"
if (-not (Test-Java)) {
    Fail "JDK not found. Install JDK 17+ and set JAVA_HOME, or add java to PATH. https://adoptium.net/"
}
Write-Host "  JDK OK"

Write-Step "Checking Node / npx (for metavr)"
$npx = Get-Command npx -ErrorAction SilentlyContinue
if (-not $npx) {
    Fail "npx not found. Install Node.js 20+ from https://nodejs.org/ then run this again."
}
Write-Host "  npx OK ($($npx.Source))"

Write-Step "Checking Quest connection"
$deviceList = & npx -y metavr device list 2>&1 | Out-String
Write-Host $deviceList
if ($deviceList -notmatch "device\s+") {
    Fail "No Quest in 'device' state. Enable Developer Mode, plug USB (or metavr device connect <ip>), accept the debugging prompt."
}

$deviceArgs = @()
if ($Device) {
    $deviceArgs = @("--device", $Device)
    Write-Host "  Using device $Device"
} else {
    Write-Host "  Using default connected device"
}

if (-not $SkipBuild) {
    Write-Step "Building debug APK (first time can take several minutes)"
    $gradlew = Join-Path $Root "gradlew.bat"
    if (-not (Test-Path $gradlew)) {
        Fail "gradlew.bat missing from repo root."
    }
    & $gradlew ":app:assembleDebug" "--no-daemon"
    if ($LASTEXITCODE -ne 0) {
        Fail "Gradle build failed. Scroll up for the error (often JDK/SDK or packaging size)."
    }
} else {
    Write-Step "SkipBuild — using existing APK"
}

if (-not (Test-Path $ApkPath)) {
    Fail "APK not found at:`n  $ApkPath`nRun without -SkipBuild so it can build."
}
Write-Host "  APK: $ApkPath"

Write-Step "Installing on Quest (replace + grant permissions)"
$installArgs = @("app", "install", $ApkPath, "--replace", "--grant-permissions") + $deviceArgs
& npx -y metavr @installArgs
if ($LASTEXITCODE -ne 0) {
    Fail "Install failed. Unplug/replug USB, unlock the headset, accept debugging, try again."
}

if (-not $NoLaunch) {
    Write-Step "Launching Spatial Launcher"
    $launchArgs = @("app", "launch", $PackageId) + $deviceArgs
    & npx -y metavr @launchArgs
    if ($LASTEXITCODE -ne 0) {
        Write-Host "  Install OK but launch failed — open Spatial Launcher from the Quest library." -ForegroundColor Yellow
    }
}

Write-Host ""
Write-Host "READY TO GO: YES" -ForegroundColor Green
Write-Host "Spatial Launcher is on the headset." -ForegroundColor Green
Write-Host "In-headset help: tap the ? button. Docs: docs\HELP.md and docs\SCREENSHOTS.md"
exit 0
