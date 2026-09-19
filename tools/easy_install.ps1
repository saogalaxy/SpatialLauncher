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
Write-Host "  Headset needs ~1+ GB free (APK packs Piper + OPUS; Qwen/SenseVoice download on first use)." -ForegroundColor DarkGray
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
# Capture output so a debug<->release signature mismatch can auto-recover
# (replace alone cannot cross signatures: INSTALL_FAILED_UPDATE_INCOMPATIBLE).
$installOut = & npx -y metavr @installCmd 2>&1 | Out-String
Write-Host $installOut
$installCode = $LASTEXITCODE
if ($installCode -ne 0 -and $installOut -match "INSTALL_FAILED_UPDATE_INCOMPATIBLE") {
    Write-Host "  Signature mismatch (e.g. release vs debug on headset) - uninstalling, then retrying (app data will be wiped)..." -ForegroundColor Yellow
    $uninstallCmd = @("app", "uninstall", $PackageId)
    if ($Device -ne "") { $uninstallCmd += @("--device", $Device) }
    [void](Invoke-NpxMetavr -MetavrArgs $uninstallCmd)
    $installOut = & npx -y metavr @installCmd 2>&1 | Out-String
    Write-Host $installOut
    $installCode = $LASTEXITCODE
}
if ($installCode -ne 0) {
    Fail "Install failed. Unplug/replug USB, unlock the headset, accept debugging, try again."
}

if (-not $NoLaunch) {
    Write-Step "Launching Spatial Launcher on headset"
    $Activity = "com.spatiallauncher.app.ui.PanelMainActivity"
    # Fresh process so a replaced APK actually comes up.
    $stopCmd = @("app", "stop", $PackageId)
    if ($Device -ne "") { $stopCmd += @("--device", $Device) }
    [void](Invoke-NpxMetavr -MetavrArgs $stopCmd)
    Start-Sleep -Milliseconds 500

    $launched = $false
    $launchCmd = @("app", "launch", $PackageId, "--activity", $Activity)
    if ($Device -ne "") { $launchCmd += @("--device", $Device) }
    if ((Invoke-NpxMetavr -MetavrArgs $launchCmd) -eq 0) {
        $launched = $true
        Write-Host "  Launched: $PackageId/$Activity"
    } else {
        Write-Host "  Activity launch failed — trying package launch…" -ForegroundColor Yellow
        $launchCmd2 = @("app", "launch", $PackageId)
        if ($Device -ne "") { $launchCmd2 += @("--device", $Device) }
        if ((Invoke-NpxMetavr -MetavrArgs $launchCmd2) -eq 0) {
            $launched = $true
            Write-Host "  Launched: $PackageId"
        }
    }
    if (-not $launched) {
        Write-Host "  Install OK but launch failed — open Spatial Launcher from the Quest library." -ForegroundColor Yellow
    }
}

Write-Host ""
Write-Host "READY TO GO: YES" -ForegroundColor Green
Write-Host "Spatial Launcher is on the headset." -ForegroundColor Green
Write-Host "In-headset help: tap the ? button. Docs: docs\HELP.md"
exit 0
