param(
    [Parameter(Mandatory=$true)][string]$PairingDir
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
function Fail([string]$m){ Write-Host "[Magen] ERROR: $m" -ForegroundColor Red; exit 1 }
$root=Split-Path -Parent $MyInvocation.MyCommand.Path
$dir=(Resolve-Path $PairingDir).Path
$ca=Join-Path $dir 'magen_public_ca.crt'
$props=Join-Path $dir 'android-pairing.properties'
if(-not (Test-Path $ca)){Fail "Missing $ca"}
if(-not (Test-Path $props)){Fail "Missing $props"}

$kv=@{}
foreach($line in Get-Content $props){
  $t=$line.Trim(); if(-not $t -or $t.StartsWith('#')){continue}
  $i=$t.IndexOf('='); if($i -gt 0){$kv[$t.Substring(0,$i).Trim()]=$t.Substring($i+1).Trim()}
}
if(-not $kv['magenServerUrl'] -or -not $kv['magenServerSigningPublicKey']){Fail 'Pairing properties are incomplete.'}
if($kv['magenServerUrl'] -notmatch '^https://[^/]+:8443$'){Fail 'Pairing server URL must be HTTPS on port 8443.'}

Copy-Item -Force $ca (Join-Path $root 'app\src\main\res\raw\magen_server_ca.crt')
$private=Join-Path $root '.magen-private'; New-Item -ItemType Directory -Force -Path $private | Out-Null
@(
  "magenServerUrl=$($kv['magenServerUrl'])",
  "magenServerSigningPublicKey=$($kv['magenServerSigningPublicKey'])",
  "caCertificateSha256=$($kv['caCertificateSha256'])"
) | Set-Content -Path (Join-Path $private 'server-pairing.properties') -Encoding ascii
Write-Host '[Magen] Server pairing imported. BUILD_APK_ON_WINDOWS.bat will use it.' -ForegroundColor Green
