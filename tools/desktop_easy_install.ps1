# Spatial Launcher Desktop Easy Installer (Windows)
# Builds/publishes the .NET 8 WPF app and copies it to LocalAppData + Start Menu shortcut.

param(
    [switch]$SkipBuild,
    [switch]$NoLaunch
)

$ErrorActionPreference = "Continue"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root
$DesktopDir = Join-Path $Root "desktop"
$Sln = Join-Path $DesktopDir "SpatialLauncher.Desktop.sln"
$PublishDir = Join-Path $DesktopDir "installer\publish"
$InstallDir = Join-Path $env:LOCALAPPDATA "SpatialLauncherDesktop\app"
$ExeName = "SpatialLauncher.Desktop.exe"

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

Write-Host ""
Write-Host "Spatial Launcher Desktop Easy Installer" -ForegroundColor White
Write-Host "Build + install PC app (Owl-style session + Quest Link stream)." -ForegroundColor DarkGray
Write-Host ""
Write-Host "STORAGE" -ForegroundColor Yellow
Write-Host "  No drive picker - installs on your Windows user-profile drive (usually C:)." -ForegroundColor DarkGray
Write-Host ("  Folder: {0}\SpatialLauncherDesktop\" -f $env:LOCALAPPDATA) -ForegroundColor DarkGray
Write-Host "  Expect ~450 MB for the app + ~470 MB for depth models (~900 MB total)." -ForegroundColor DarkGray
Write-Host "  Uninstall via Settings / Apps / Spatial Launcher Desktop (models kept by default)." -ForegroundColor DarkGray

Write-Step "Checking .NET 8 SDK"
$dotnet = Get-Command dotnet -ErrorAction SilentlyContinue
if (-not $dotnet) {
    Fail ".NET SDK not found. Install .NET 8 SDK from https://dotnet.microsoft.com/download/dotnet/8.0"
}
$sdks = & dotnet --list-sdks 2>$null
if ($sdks -notmatch "^8\.") {
    Fail "Need .NET 8 SDK. Installed:`n$sdks"
}
Write-Host "  .NET 8 SDK OK"

if (-not (Test-Path $Sln)) {
    Fail "Solution not found: $Sln"
}

if (-not $SkipBuild) {
    Write-Step "Publishing Release self-contained win-x64"
    New-Item -ItemType Directory -Force -Path $PublishDir | Out-Null
    & dotnet publish (Join-Path $DesktopDir "src\SpatialLauncher.Desktop\SpatialLauncher.Desktop.csproj") `
        -c Release -r win-x64 --self-contained true `
        -p:PublishSingleFile=false `
        -o $PublishDir
    if ($LASTEXITCODE -ne 0) {
        Fail "dotnet publish failed (exit $LASTEXITCODE)"
    }
    Write-Host "  Publish OK -> $PublishDir"
} else {
    Write-Step "SkipBuild - using existing publish folder"
    if (-not (Test-Path (Join-Path $PublishDir $ExeName))) {
        Fail "No published exe at $PublishDir\$ExeName"
    }
}

Write-Step "Installing to $InstallDir"
# Unlock install dir if a previous run is still alive (otherwise Copy-Item silently skips locked files).
Get-Process -Name "SpatialLauncher.Desktop" -ErrorAction SilentlyContinue | ForEach-Object {
    Write-Host "  Stopping running instance pid=$($_.Id)"
    Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue
}
Start-Sleep -Milliseconds 400
New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
try {
    Copy-Item -Path (Join-Path $PublishDir "*") -Destination $InstallDir -Recurse -Force -ErrorAction Stop
} catch {
    Fail "Copy to install folder failed: $($_.Exception.Message)"
}

$desktopExe = Join-Path $InstallDir $ExeName
if (-not (Test-Path -LiteralPath $desktopExe)) {
    $desktopExe = Get-ChildItem -Path $InstallDir -Filter $ExeName -File -Recurse -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
}
if ([string]::IsNullOrWhiteSpace($desktopExe) -or -not (Test-Path -LiteralPath $desktopExe)) {
    Fail "Install copy failed - missing $ExeName under $InstallDir"
}
Write-Host "  Exe: $desktopExe  ($((Get-Item -LiteralPath $desktopExe).LastWriteTime))"

Write-Step "Start Menu shortcut"
$startMenu = Join-Path $env:APPDATA "Microsoft\Windows\Start Menu\Programs"
New-Item -ItemType Directory -Force -Path $startMenu | Out-Null
$lnkPath = Join-Path $startMenu "Spatial Launcher Desktop.lnk"
$wsh = New-Object -ComObject WScript.Shell
$lnk = $wsh.CreateShortcut($lnkPath)
$lnk.TargetPath = $desktopExe
$lnk.WorkingDirectory = $InstallDir
$lnk.Description = "Spatial Launcher Desktop"
$lnk.Save()
Write-Host "  Shortcut: $lnkPath"

Write-Step "Register Apps and Features uninstall entry"
$uninstallPs1 = Join-Path $InstallDir "uninstall.ps1"
$uninstallCmd = Join-Path $InstallDir "uninstall.cmd"
Copy-Item -Path (Join-Path $PSScriptRoot "desktop_uninstall.ps1") -Destination $uninstallPs1 -Force
Copy-Item -Path (Join-Path $PSScriptRoot "desktop_uninstall.cmd") -Destination $uninstallCmd -Force
# Fix cmd to call local uninstall.ps1 next to itself
@(
    '@echo off',
    'setlocal',
    'title Uninstall Spatial Launcher Desktop',
    'powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0uninstall.ps1"',
    'endlocal',
    'exit /b %ERRORLEVEL%'
) | Set-Content -Path $uninstallCmd -Encoding ASCII

$uninstallKey = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\SpatialLauncherDesktop"
New-Item -Path $uninstallKey -Force | Out-Null
$displayIcon = $desktopExe
$icoBeside = Join-Path $InstallDir "Assets\app.ico"
if (Test-Path -LiteralPath $icoBeside) { $displayIcon = $icoBeside }
$version = "0.1.0"
try {
    $vi = [System.Diagnostics.FileVersionInfo]::GetVersionInfo($desktopExe)
    if ($vi.ProductVersion) { $version = $vi.ProductVersion }
} catch { }

$estimated = 0
Get-ChildItem -Path $InstallDir -Recurse -File -ErrorAction SilentlyContinue | ForEach-Object { $estimated += $_.Length }
$estimatedKb = [int]([math]::Max(1, [math]::Ceiling($estimated / 1KB)))

Set-ItemProperty -Path $uninstallKey -Name "DisplayName" -Value "Spatial Launcher Desktop"
Set-ItemProperty -Path $uninstallKey -Name "DisplayVersion" -Value $version
Set-ItemProperty -Path $uninstallKey -Name "Publisher" -Value "saogalaxy"
Set-ItemProperty -Path $uninstallKey -Name "InstallLocation" -Value $InstallDir
Set-ItemProperty -Path $uninstallKey -Name "DisplayIcon" -Value $displayIcon
Set-ItemProperty -Path $uninstallKey -Name "UninstallString" -Value ("powershell.exe -NoProfile -ExecutionPolicy Bypass -File `"$uninstallPs1`"")
Set-ItemProperty -Path $uninstallKey -Name "QuietUninstallString" -Value ("powershell.exe -NoProfile -ExecutionPolicy Bypass -File `"$uninstallPs1`" -Silent")
Set-ItemProperty -Path $uninstallKey -Name "NoModify" -Value 1 -Type DWord
Set-ItemProperty -Path $uninstallKey -Name "NoRepair" -Value 1 -Type DWord
Set-ItemProperty -Path $uninstallKey -Name "EstimatedSize" -Value $estimatedKb -Type DWord
Write-Host "  Listed under Settings > Apps as Spatial Launcher Desktop"

# URL ACL so Quest on LAN can connect (may need elevation once)
Write-Step "HTTP URL reservation for Quest Link (port 8765)"
$urlacl = "http://+:8765/"
$existing = netsh http show urlacl url=$urlacl 2>$null
if ("$existing" -notmatch "8765") {
    Write-Host "  Trying netsh urlacl (may prompt for admin)..."
    Start-Process -FilePath "netsh" -ArgumentList @(
        "http", "add", "urlacl", "url=$urlacl", "user=Everyone"
    ) -Verb RunAs -Wait -ErrorAction SilentlyContinue
} else {
    Write-Host "  URL ACL already present"
}

Write-Step "Spatial Launcher Audio driver (virtual speaker)"
$audioInstall = Join-Path $PSScriptRoot "install_spatial_audio_driver.ps1"
$audioDist = Join-Path $DesktopDir "audio-driver\dist\x64"
$audioInstallDir = Join-Path $env:LOCALAPPDATA "SpatialLauncherDesktop\audio-driver"
if (Test-Path $audioDist) {
    New-Item -ItemType Directory -Force -Path $audioInstallDir | Out-Null
    Copy-Item -Path (Join-Path $audioDist "*") -Destination $audioInstallDir -Force -ErrorAction SilentlyContinue
    Copy-Item -Path $audioInstall -Destination (Join-Path $audioInstallDir "install_spatial_audio_driver.ps1") -Force -ErrorAction SilentlyContinue
    Write-Host "  Installing / refreshing Spatial Launcher Audio (UAC prompt)..."
    Start-Process -FilePath "powershell.exe" -Verb RunAs -ArgumentList @(
        "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "`"$audioInstall`""
    ) -Wait -ErrorAction SilentlyContinue
    Write-Host "  If Sound still lacks 'Spatial Launcher Audio', run tools\install_spatial_audio_driver.ps1 as Admin."
} else {
    Write-Host "  Skipped - package missing at $audioDist" -ForegroundColor Yellow
}

Write-Step "Model folder + depth ONNX fetch"
$modelRoot = Join-Path $env:LOCALAPPDATA "SpatialLauncherDesktop\models"
New-Item -ItemType Directory -Force -Path $modelRoot | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $modelRoot "piper") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $modelRoot "translate\jaen") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $modelRoot "translate\zhen") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $modelRoot "translate\koen") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $modelRoot "asr") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $modelRoot "paddleocr") | Out-Null

function Get-ModelFile([string]$Url, [string]$Dest, [string]$Label) {
    if (Test-Path -LiteralPath $Dest) {
        Write-Host "  OK (cached): $Label"
        return
    }
    Write-Host "  Downloading $Label ..."
    try {
        Invoke-WebRequest -Uri $Url -OutFile $Dest -UseBasicParsing -TimeoutSec 600
        if ((Test-Path -LiteralPath $Dest) -and ((Get-Item -LiteralPath $Dest).Length -gt 100000)) {
            Write-Host "  Saved: $Dest"
        } else {
            Write-Host "  WARN: download looked empty for $Label" -ForegroundColor Yellow
        }
    } catch {
        Write-Host "  WARN: could not fetch $Label - $($_.Exception.Message)" -ForegroundColor Yellow
        Write-Host "  Place ONNX manually under $modelRoot" -ForegroundColor Yellow
    }
}

# Depth models:
#  Gaming  - Depth Anything V2 Small
#  Movies  - Depth Anything 3 Base (preferred), DA3 Small, then DA-V2 Base fallback
Get-ModelFile `
    "https://huggingface.co/onnx-community/depth-anything-v2-small/resolve/main/onnx/model.onnx" `
    (Join-Path $modelRoot "depth_anything_v2_vits.onnx") `
    "Depth Anything V2 Small (Gaming)"
Get-ModelFile `
    "https://huggingface.co/zerochocobo/DepthAnything3_ONNX/resolve/main/da3_base.onnx" `
    (Join-Path $modelRoot "da3_base.onnx") `
    "Depth Anything 3 Base (Movies)"
Get-ModelFile `
    "https://huggingface.co/zerochocobo/DepthAnything3_ONNX/resolve/main/da3_small.onnx" `
    (Join-Path $modelRoot "da3_small.onnx") `
    "Depth Anything 3 Small (Movies fallback)"
Get-ModelFile `
    "https://huggingface.co/onnx-community/depth-anything-v2-base/resolve/main/onnx/model.onnx" `
    (Join-Path $modelRoot "depth_anything_v2_vitb.onnx") `
    "Depth Anything V2 Base (legacy Movies fallback)"

Write-Host "  Models root: $modelRoot"
Write-Host "  Optional: piper/en_US-lessac-high.onnx + piper.exe, OPUS jaen/zhen/koen, SenseVoice under asr/, PaddleOCR CLI under paddleocr/"

if (-not $NoLaunch) {
    Write-Step "Launching"
    if ([string]::IsNullOrWhiteSpace($desktopExe)) {
        Fail "Cannot launch - exe path is empty"
    }
    Start-Process -FilePath $desktopExe -WorkingDirectory $InstallDir
}

Write-Host ""
Write-Host "READY TO GO: YES" -ForegroundColor Green
Write-Host "Installed: $desktopExe"
$appBytes = 0L
Get-ChildItem -Path $InstallDir -Recurse -File -ErrorAction SilentlyContinue | ForEach-Object { $appBytes += $_.Length }
$modelBytes = 0L
Get-ChildItem -Path $modelRoot -Recurse -File -ErrorAction SilentlyContinue | ForEach-Object { $modelBytes += $_.Length }
$appMb = [math]::Round($appBytes / 1MB, 1)
$modelMb = [math]::Round($modelBytes / 1MB, 1)
Write-Host ("PC storage used: app {0} MB + models {1} MB = ~{2} MB under {3}" -f $appMb, $modelMb, ($appMb + $modelMb), (Split-Path $InstallDir -Parent))
Write-Host "Quest: tap Desktop Link (monitor) - auto-finds this PC on LAN. Gaming/Movies depth presets on PC."
exit 0
