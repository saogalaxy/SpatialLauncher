# Creates a Meta Store upload keystore + keystore.properties (both gitignored).
# Back up signing/*.jks and keystore.properties somewhere safe — losing them
# means you cannot update the same Store listing with a matching signature.

param(
    [string]$Alias = "spatiallauncher",
    [string]$OutDir = "signing",
    [string]$StoreFileName = "spatial-launcher-release.jks",
    [int]$ValidityDays = 10000
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$storeDir = Join-Path $root $OutDir
$storePath = Join-Path $storeDir $StoreFileName
$propsPath = Join-Path $root "keystore.properties"

if (Test-Path $storePath) {
    Write-Host "Keystore already exists: $storePath"
    Write-Host "Delete it first if you really want a new key (breaks Store update continuity)."
    exit 1
}

New-Item -ItemType Directory -Force -Path $storeDir | Out-Null

function New-Password([int]$len = 24) {
    $chars = 'abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789!@$%*'
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $bytes = New-Object byte[] $len
    $rng.GetBytes($bytes)
    -join ($bytes | ForEach-Object { $chars[$_ % $chars.Length] })
}

$storePass = New-Password 28
$keyPass = $storePass

$keytool = Join-Path $env:JAVA_HOME "bin\keytool.exe"
if (-not (Test-Path $keytool)) {
    $keytool = (Get-Command keytool -ErrorAction Stop).Source
}

& $keytool -genkeypair `
    -keystore $storePath `
    -alias $Alias `
    -keyalg RSA `
    -keysize 2048 `
    -validity $ValidityDays `
    -storepass $storePass `
    -keypass $keyPass `
    -dname "CN=Spatial Launcher, OU=Mobile, O=saogalaxy, L=Unknown, ST=Unknown, C=US"

$relStore = "$OutDir/$StoreFileName" -replace '\\', '/'
$propsBody = "storeFile=$relStore`nstorePassword=$storePass`nkeyAlias=$Alias`nkeyPassword=$keyPass`n"
[System.IO.File]::WriteAllText($propsPath, $propsBody, (New-Object System.Text.UTF8Encoding $false))

Write-Host ""
Write-Host "Created:"
Write-Host "  $storePath"
Write-Host "  $propsPath"
Write-Host ""
Write-Host "BACK THESE UP OFF-MACHINE (password manager + encrypted drive)."
Write-Host "Build a store APK with:  .\gradlew.bat :app:assembleRelease"
Write-Host "Output: app\build\outputs\apk\release\app-release.apk"
