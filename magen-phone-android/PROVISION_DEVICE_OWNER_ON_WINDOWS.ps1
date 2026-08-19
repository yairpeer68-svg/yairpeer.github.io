$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Fail([string]$m) { Write-Host "[Magen] ERROR: $m" -ForegroundColor Red; exit 1 }
$adb = Get-Command adb.exe -ErrorAction SilentlyContinue
if (-not $adb) {
    $sdk = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } elseif ($env:LOCALAPPDATA) { "$env:LOCALAPPDATA\Android\Sdk" } else { "" }
    if ($sdk) {
        $candidate = Join-Path $sdk 'platform-tools\adb.exe'
        if (Test-Path $candidate) { $adb = Get-Item $candidate }
    }
}
if (-not $adb) { Fail 'adb.exe not found. Install Android SDK Platform Tools.' }

Write-Host '[Magen] Device Owner provisioning' -ForegroundColor Cyan
Write-Host 'This only works on a freshly provisioned/reset device before normal user accounts are added.' -ForegroundColor Yellow
& $adb.FullName devices

$pkg = 'com.magen.family'
$admin = 'com.magen.family/.admin.MagenDeviceAdmin'
& $adb.FullName shell pm path $pkg | Out-Host
if ($LASTEXITCODE -ne 0) { Fail 'Magen APK is not installed on the connected device.' }

& $adb.FullName shell dpm set-device-owner $admin | Out-Host
if ($LASTEXITCODE -ne 0) {
    Fail 'Android refused Device Owner. Factory-reset/fresh provisioning is normally required; do not try to bypass Android provisioning rules.'
}

Write-Host '[Magen] Device Owner set. Launch Magen once so it applies Always-On VPN + lockdown + restrictions.' -ForegroundColor Green
& $adb.FullName shell monkey -p $pkg 1 | Out-Host
