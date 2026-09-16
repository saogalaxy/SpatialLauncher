# Uninstall Spatial Launcher Audio / Virtual Audio Driver package — elevated

$ErrorActionPreference = "Continue"
$Root = Split-Path -Parent $PSScriptRoot
$Dist = Join-Path $Root "desktop\audio-driver\dist\x64"
$Inf = Join-Path $Dist "VirtualAudioDriver.inf"

function Assert-Admin {
    $id = [Security.Principal.WindowsIdentity]::GetCurrent()
    $p = New-Object Security.Principal.WindowsPrincipal($id)
    if (-not $p.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        Write-Host "Re-launching elevated..." -ForegroundColor Yellow
        Start-Process -FilePath "powershell.exe" -Verb RunAs -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`"" -Wait
        exit $LASTEXITCODE
    }
}

Assert-Admin
Write-Host "==> Removing Spatial Launcher Audio / Virtual Audio Driver" -ForegroundColor Cyan

# Remove matching OEM packages from driver store
$pnputil = Join-Path $env:SystemRoot "System32\pnputil.exe"
$listed = & $pnputil /enum-drivers 2>$null
$oem = $null
$lines = $listed -split "`r?`n"
for ($i = 0; $i -lt $lines.Count; $i++) {
    if ($lines[$i] -match "Published Name\s*:\s*(oem\d+\.inf)") {
        $name = $Matches[1]
        $block = ($lines[$i..([Math]::Min($i + 8, $lines.Count - 1))] -join "`n")
        if ($block -match "VirtualAudioDriver|Virtual Audio Driver|MikeTheTech") {
            Write-Host "  Deleting $name"
            & $pnputil /delete-driver $name /uninstall /force
        }
    }
}

# Remove ROOT device if still present
Get-PnpDevice -ErrorAction SilentlyContinue |
    Where-Object { $_.InstanceId -like "*VirtualAudioDriver*" } |
    ForEach-Object {
        Write-Host "  Removing device $($_.InstanceId)"
        try {
            $_ | Disable-PnpDevice -Confirm:$false -ErrorAction SilentlyContinue
            & $pnputil /remove-device $_.InstanceId 2>$null
        } catch { }
    }

Restart-Service -Name Audiosrv -Force -ErrorAction SilentlyContinue
Write-Host "DONE." -ForegroundColor Green
exit 0
