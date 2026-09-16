# Install Spatial Launcher Audio (virtual speaker) - elevated
# Stages the signed Virtual Audio Driver, creates ROOT\VirtualAudioDriver,
# renames the Windows Sound endpoint to "Spatial Launcher Audio".

param(
    [switch]$SkipRename
)

$ErrorActionPreference = "Continue"
$ScriptDir = if ($PSScriptRoot) { $PSScriptRoot } else { Split-Path -Parent $MyInvocation.MyCommand.Path }

$LogDir = Join-Path $env:LOCALAPPDATA "SpatialLauncherDesktop\logs"
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
$LogFile = Join-Path $LogDir "audio_driver_install.log"
function Log([string]$Message) {
    $line = "[{0:yyyy-MM-dd HH:mm:ss}] {1}" -f (Get-Date), $Message
    try { Add-Content -Path $LogFile -Value $line -ErrorAction SilentlyContinue } catch { }
    Write-Host $Message
}
Log "=== Spatial Launcher Audio install bootstrap ==="
Log "Script=$PSCommandPath ScriptDir=$ScriptDir"

# Prefer package beside this script (LocalAppData copy), else repo layout.
$DistCandidates = @(
    (Join-Path $ScriptDir "."),
    (Join-Path $ScriptDir "dist\x64"),
    (Join-Path (Split-Path -Parent $ScriptDir) "desktop\audio-driver\dist\x64"),
    (Join-Path $env:LOCALAPPDATA "SpatialLauncherDesktop\audio-driver")
)
$Dist = $null
foreach ($c in $DistCandidates) {
    if (Test-Path (Join-Path $c "VirtualAudioDriver.inf")) { $Dist = (Resolve-Path $c).Path; break }
}
if (-not $Dist) {
    $Root = Split-Path -Parent $ScriptDir
    if ((Split-Path -Leaf $ScriptDir) -eq "tools") {
        $cand = Join-Path $Root "desktop\audio-driver\dist\x64"
        if (Test-Path (Join-Path $cand "VirtualAudioDriver.inf")) { $Dist = $cand }
    }
}
$Inf = if ($Dist) { Join-Path $Dist "VirtualAudioDriver.inf" } else { $null }
$HardwareId = "ROOT\VirtualAudioDriver"
$TargetName = "Spatial Launcher Audio"
Log "Inf=$Inf Dist=$Dist"

function Write-Step([string]$Message) {
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
    Log $Message
}

function Assert-Admin {
    $id = [Security.Principal.WindowsIdentity]::GetCurrent()
    $p = New-Object Security.Principal.WindowsPrincipal($id)
    if (-not $p.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        Log "Not admin - requesting UAC elevation"
        $argList = "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`""
        if ($SkipRename) { $argList += " -SkipRename" }
        try {
            $proc = Start-Process -FilePath "powershell.exe" -Verb RunAs -ArgumentList $argList -Wait -PassThru
            $code = if ($null -ne $proc) { $proc.ExitCode } else { 1 }
            Log "Elevated process exit=$code"
            exit $code
        } catch {
            Log "UAC elevation failed/cancelled: $($_.Exception.Message)"
            exit 1
        }
    }
    Log "Running elevated"
}

Assert-Admin

if ([string]::IsNullOrEmpty($Inf) -or -not (Test-Path $Inf)) {
    Log "Driver package missing (VirtualAudioDriver.inf)."
    exit 1
}

Write-Step "Staging driver package (signed Virtual Audio Driver)"
$pnputil = Join-Path $env:SystemRoot "System32\pnputil.exe"
& $pnputil /add-driver $Inf /install
# Non-zero is OK if already staged; continue to device create.

Write-Step "Creating root device $HardwareId"
$helperCs = Join-Path $Dist "SlaDriverInstall.cs"
if (-not (Test-Path $helperCs)) {
    $helperCs = Join-Path $ScriptDir "SlaDriverInstall.cs"
}
if (-not (Test-Path $helperCs)) {
    Log "Missing SlaDriverInstall.cs next to the driver package."
    exit 1
}
if (-not ("SlaDriverInstall" -as [type])) {
    $csText = Get-Content -LiteralPath $helperCs -Raw -Encoding UTF8
    Add-Type -TypeDefinition $csText -Language CSharp -ErrorAction Stop
    Log "Compiled SlaDriverInstall helper from $helperCs"
}

$result = [SlaDriverInstall]::Install($Inf, $HardwareId, $TargetName)
Log "Device create: $result"
Write-Host "  Device create: $result"
if ($result -notlike "OK*") {
    Log "Install failed."
    Write-Host "Install failed." -ForegroundColor Red
    exit 1
}

Start-Sleep -Seconds 2

if (-not $SkipRename) {
    Write-Step "Renaming Sound endpoints to '$TargetName'"
    $renamed = 0
    $renderRoot = "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\MMDevices\Audio\Render"
    if (Test-Path $renderRoot) {
        Get-ChildItem $renderRoot | ForEach-Object {
            $props = Join-Path $_.PSPath "Properties"
            if (-not (Test-Path $props)) { return }
            # Device interface friendly name shown in Sound output list
            $fk = "{b3f8fa53-0004-438e-9003-51a46e139bfc},6"
            $dk = "{a45c254e-df1c-4efd-8020-67d146a850e0},2"
            try {
                $cur = (Get-ItemProperty -Path $props -Name $fk -ErrorAction SilentlyContinue).$fk
            } catch { $cur = $null }
            if ([string]::IsNullOrEmpty($cur)) { return }
            if ($cur -match "Virtual Audio Driver|MTT|Spatial Launcher Audio") {
                Set-ItemProperty -Path $props -Name $fk -Value $TargetName -Type String -ErrorAction SilentlyContinue
                try {
                    $desc = (Get-ItemProperty -Path $props -Name $dk -ErrorAction SilentlyContinue).$dk
                    if ($desc -match "Virtual Audio Driver|MTT|Spatial Launcher") {
                        Set-ItemProperty -Path $props -Name $dk -Value $TargetName -Type String -ErrorAction SilentlyContinue
                    }
                } catch { }
                $renamed++
                Write-Host "  Renamed: $cur -> $TargetName"
            }
        }
    }
    # Capture side (optional mic from same package)
    $capRoot = "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\MMDevices\Audio\Capture"
    if (Test-Path $capRoot) {
        Get-ChildItem $capRoot | ForEach-Object {
            $props = Join-Path $_.PSPath "Properties"
            if (-not (Test-Path $props)) { return }
            $fk = "{b3f8fa53-0004-438e-9003-51a46e139bfc},6"
            try {
                $cur = (Get-ItemProperty -Path $props -Name $fk -ErrorAction SilentlyContinue).$fk
            } catch { $cur = $null }
            if ($cur -match "Virtual Mic Driver|MTT") {
                Set-ItemProperty -Path $props -Name $fk -Value "Spatial Launcher Mic" -Type String -ErrorAction SilentlyContinue
                Write-Host "  Renamed mic: $cur -> Spatial Launcher Mic"
            }
        }
    }
    Write-Host "  Endpoints touched: $renamed"
    Write-Step "Restarting Windows Audio service"
    Restart-Service -Name Audiosrv -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 1
}

Write-Step "Verify"
$check = Get-PnpDevice -Class MEDIA -ErrorAction SilentlyContinue |
    Where-Object {
        $_.HardwareID -like "*VirtualAudioDriver*" -or
        $_.InstanceId -like "*VirtualAudioDriver*" -or
        $_.FriendlyName -like "*Spatial Launcher*" -or
        $_.FriendlyName -like "*Virtual Audio*"
    }
$ok = $false
$blocked = $false
if ($check) {
    $check | ForEach-Object {
        Write-Host ("  {0} [{1}] {2}" -f $_.FriendlyName, $_.Status, $_.ConfigManagerErrorCode)
        if ($_.Status -eq "OK") { $ok = $true }
        if ("$($_.ConfigManagerErrorCode)" -match "UNSIGNED|52") { $blocked = $true }
        if ($_.Problem -eq "CM_PROB_UNSIGNED_DRIVER") { $blocked = $true }
    }
} else {
    Write-Host "  PnP MEDIA node not listed yet." -ForegroundColor Yellow
}

Write-Host ""
if ($ok) {
    Write-Host "DONE: Spatial Launcher Audio is running." -ForegroundColor Green
    Write-Host "Open Windows Sound output - you should see '$TargetName' (or Virtual Audio Driver)." -ForegroundColor DarkGray
    Write-Host "Then in Spatial Launcher Desktop -> Sound -> Headset." -ForegroundColor DarkGray
    exit 0
}

if ($blocked) {
    Write-Host "BLOCKED: Windows Code 52 (unsigned / no Microsoft kernel attestation)." -ForegroundColor Red
    Write-Host "SignPath Authenticode is not enough for Secure Boot PCs - the .sys never loads," -ForegroundColor Yellow
    Write-Host "so it will not appear under Sound. Upstream package needs Microsoft attestation signing." -ForegroundColor Yellow
    Write-Host ""
    Write-Host "Workaround for Headset mode now: use Steam Streaming Speakers or Virtual Desktop Audio" -ForegroundColor Cyan
    Write-Host "(Desktop Sound tab already prefers those if Spatial Launcher Audio is missing)." -ForegroundColor Cyan
    exit 52
}

Write-Host "Install finished but the speaker endpoint is not OK yet - check Device Manager." -ForegroundColor Yellow
exit 1
