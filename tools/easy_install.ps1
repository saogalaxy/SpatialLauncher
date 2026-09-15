# Spatial Launcher Easy Installer (Quest)
# Checks JDK / Node / headset, builds the debug APK if needed (full offline model pack
# via downloadOfflineModels — same assets as Meta Store), installs with metavr.
# Compatible with Windows PowerShell 5.1+

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

function Write-Step {
    param([string]$Message)
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Fail {
    param([string]$Message)
    Write-Host ""
    Write-Host "READY TO GO: NO" -ForegroundColor Red
    Write-Host $Message -ForegroundColor Yellow
    exit 1
}

function Test-Java {
    if ($env:JAVA_HOME) {
        $javaHomeBin = Join-Path $env:JAVA_HOME "bin\java.exe"
        if (Test-Path $javaHomeBin) {
            return $true
        }
    }
    $javaCmd = Get-Command java -ErrorAction SilentlyContinue
    return ($null -ne $javaCmd)
}

function Invoke-NpxMetavr {
    param([string[]]$MetavrArgs)
    $all = @("-y", "metavr") + $MetavrArgs
    & npx @all
    return $LASTEXITCODE
}

Write-Host ""
Write-Host "Spatial Launcher Easy Installer" -ForegroundColor White
Write-Host "Looks for what is needed, builds if needed, installs to Quest." -ForegroundColor DarkGray
Write-Host ""
Write-Host "STORAGE" -ForegroundColor Yellow
Write-Host "  Sideloads to the Quest headset (not a Windows Program Files install)." -ForegroundColor DarkGray
Write-Host "  Headset needs ~2.5+ GB free (debug APK includes offline models)." -ForegroundColor DarkGray
Write-Host "  PC keeps build outputs under this repo folder only." -ForegroundColor DarkGray

Write-Step "Checking JDK"
if (-not (Test-Java)) {
    Fail "JDK not found. Install JDK 17+ and set JAVA_HOME, or add java to PATH. https://adoptium.net/"
}
Write-Host "  JDK OK"

Write-Step "Checking Node / npx (for metavr)"
$npxCmd = Get-Command npx -ErrorAction SilentlyContinue
if ($null -eq $npxCmd) {
    Fail "npx not found. Install Node.js 20+ from https://nodejs.org/ then run this again."
}
Write-Host ("  npx OK (" + $npxCmd.Source + ")")

Write-Step "Checking Quest connection"
$deviceList = & npx -y metavr device list 2>&1 | Out-String
Write-Host $deviceList
if ($deviceList -notmatch "device\s+") {
    Fail "No Quest in 'device' state. Enable Developer Mode, plug USB (or metavr device connect <ip>), accept the debugging prompt."
}

if ($Device -ne "") {
    Write-Host ("  Using device " + $Device)
} else {
    Write-Host "  Using default connected device"
}

if (-not $SkipBuild) {
    Write-Step "Building debug APK (first time can take several minutes)"
    $gradlew = Join-Path $Root "gradlew.bat"
    if (-not (Test-Path $gradlew)) {
        Fail "gradlew.bat missing from repo root. Prefer 'git clone' over the GitHub ZIP if files are missing."
    }
    & $gradlew ":app:assembleDebug" "--no-daemon"
    if ($LASTEXITCODE -ne 0) {
        Fail "Gradle build failed. Scroll up for the error (often JDK/SDK or packaging size)."
    }
} else {
    Write-Step "SkipBuild - using existing APK"
}

if (-not (Test-Path $ApkPath)) {
    Fail ("APK not found at:`n  " + $ApkPath + "`nRun without -SkipBuild so it can build.")
}
Write-Host ("  APK: " + $ApkPath)

Write-Step "Installing on Quest (replace + grant permissions)"
$installCmd = @(
    "app", "install", $ApkPath,
    "--replace", "--grant-permissions"
)
if ($Device -ne "") {
    $installCmd += @("--device", $Device)
}
$installCode = Invoke-NpxMetavr -MetavrArgs $installCmd
if ($installCode -ne 0) {
    Fail "Install failed. Unplug/replug USB, unlock the headset, accept debugging, try again."
}

if (-not $NoLaunch) {
    Write-Step "Launching Spatial Launcher"
    $launchCmd = @("app", "launch", $PackageId)
    if ($Device -ne "") {
        $launchCmd += @("--device", $Device)
    }
    $launchCode = Invoke-NpxMetavr -MetavrArgs $launchCmd
    if ($launchCode -ne 0) {
        Write-Host "  Install OK but launch failed - open Spatial Launcher from the Quest library." -ForegroundColor Yellow
    }
}

Write-Host ""
Write-Host "READY TO GO: YES" -ForegroundColor Green
Write-Host "Spatial Launcher is on the headset." -ForegroundColor Green
Write-Host "In-headset help: tap the ? button. Docs: docs\HELP.md and docs\SCREENSHOTS.md"
exit 0
