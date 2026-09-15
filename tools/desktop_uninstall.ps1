# Spatial Launcher Desktop uninstall (per-user)
# Removes app files, Start Menu shortcut, and Apps & Features entry.
# Models under %LocalAppData%\SpatialLauncherDesktop\models are kept unless -RemoveModels.

param(
    [switch]$RemoveModels,
    [switch]$Silent
)

$ErrorActionPreference = "Continue"
Add-Type -AssemblyName System.Windows.Forms | Out-Null
$Root = Join-Path $env:LOCALAPPDATA "SpatialLauncherDesktop"
$InstallDir = Join-Path $Root "app"
$ModelRoot = Join-Path $Root "models"
$StartMenuLnk = Join-Path $env:APPDATA "Microsoft\Windows\Start Menu\Programs\Spatial Launcher Desktop.lnk"
$UninstallKey = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\SpatialLauncherDesktop"

if (-not $Silent) {
    $msg = "Uninstall Spatial Launcher Desktop from:`n$InstallDir"
    if ($RemoveModels) { $msg += "`n`nAlso remove downloaded models." }
    $r = [System.Windows.Forms.MessageBox]::Show(
        $msg, "Uninstall Spatial Launcher Desktop",
        [System.Windows.Forms.MessageBoxButtons]::YesNo,
        [System.Windows.Forms.MessageBoxIcon]::Question)
    if ($r -ne [System.Windows.Forms.DialogResult]::Yes) { exit 0 }
}

Get-Process -Name "SpatialLauncher.Desktop" -ErrorAction SilentlyContinue | ForEach-Object {
    Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue
}
Start-Sleep -Milliseconds 500

if (Test-Path -LiteralPath $StartMenuLnk) {
    Remove-Item -LiteralPath $StartMenuLnk -Force -ErrorAction SilentlyContinue
}

# Copy this script out of the install folder so we can delete the folder.
$tempUninstall = Join-Path $env:TEMP "SpatialLauncherDesktop-uninstall-run.ps1"
if ($PSCommandPath -and (Test-Path -LiteralPath $PSCommandPath)) {
    # Already running from somewhere; just delete install dir below.
}

if (Test-Path -LiteralPath $InstallDir) {
    Remove-Item -LiteralPath $InstallDir -Recurse -Force -ErrorAction SilentlyContinue
}

if ($RemoveModels -and (Test-Path -LiteralPath $ModelRoot)) {
    Remove-Item -LiteralPath $ModelRoot -Recurse -Force -ErrorAction SilentlyContinue
}

# If app folder is gone and models gone (or not removing models), clean empty root.
if ((Test-Path -LiteralPath $Root) -and -not (Get-ChildItem -LiteralPath $Root -Force -ErrorAction SilentlyContinue)) {
    Remove-Item -LiteralPath $Root -Force -ErrorAction SilentlyContinue
}

Remove-Item -Path $UninstallKey -Recurse -Force -ErrorAction SilentlyContinue

if (-not $Silent) {
    Add-Type -AssemblyName System.Windows.Forms -ErrorAction SilentlyContinue
    [System.Windows.Forms.MessageBox]::Show(
        "Spatial Launcher Desktop was removed.",
        "Uninstall complete",
        [System.Windows.Forms.MessageBoxButtons]::OK,
        [System.Windows.Forms.MessageBoxIcon]::Information) | Out-Null
}
exit 0
