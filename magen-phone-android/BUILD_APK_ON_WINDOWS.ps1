$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Fail([string]$Message) {
    Write-Host ""
    Write-Host "[Magen] ERROR: $Message" -ForegroundColor Red
    exit 1
}

function Info([string]$Message) { Write-Host "[Magen] $Message" -ForegroundColor Cyan }

function Get-JavaMajor([string]$Home) {
    try {
        if (-not (Test-Path (Join-Path $Home 'bin\java.exe'))) { return 0 }
        $txt = (& (Join-Path $Home 'bin\java.exe') -version 2>&1 | Out-String)
        $m = [regex]::Match($txt, 'version "(?<major>\d+)(?:\.|\")')
        if ($m.Success) { return [int]$m.Groups['major'].Value }
    } catch {}
    return 0
}

function Find-JavaHome {
    $candidates = @(
        "$env:ProgramFiles\Eclipse Adoptium\jdk-21*",
        "$env:ProgramFiles\Eclipse Adoptium\jdk-17*",
        "$env:ProgramFiles\Java\jdk-21*",
        "$env:ProgramFiles\Java\jdk-17*",
        "$env:ProgramFiles\Android\Android Studio\jbr",
        "$env:ProgramFiles\Android\Android Studio\jre"
    )
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    foreach ($candidate in $candidates) {
        $matches = @(Get-Item $candidate -ErrorAction SilentlyContinue) | Sort-Object FullName -Descending
        foreach ($m in $matches) {
            $major = Get-JavaMajor $m.FullName
            if ($major -eq 21 -or $major -eq 17) { return $m.FullName }
        }
    }
    return $null
}

function Find-AndroidSdk {
    $candidates = @()
    if ($env:ANDROID_SDK_ROOT) { $candidates += $env:ANDROID_SDK_ROOT }
    if ($env:ANDROID_HOME) { $candidates += $env:ANDROID_HOME }
    if ($env:LOCALAPPDATA) { $candidates += "$env:LOCALAPPDATA\Android\Sdk" }
    foreach ($sdk in ($candidates | Select-Object -Unique)) {
        if ($sdk -and (Test-Path $sdk)) { return $sdk }
    }
    return $null
}

function Find-SdkManager([string]$Sdk) {
    $known = @(
        (Join-Path $Sdk 'cmdline-tools\latest\bin\sdkmanager.bat'),
        (Join-Path $Sdk 'cmdline-tools\bin\sdkmanager.bat'),
        (Join-Path $Sdk 'tools\bin\sdkmanager.bat')
    )
    foreach ($x in $known) { if (Test-Path $x) { return $x } }
    $base = Join-Path $Sdk 'cmdline-tools'
    if (Test-Path $base) {
        $found = Get-ChildItem -Path $base -Filter sdkmanager.bat -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($found) { return $found.FullName }
    }
    return $null
}

function New-HexSecret([int]$Bytes = 24) {
    $buf = New-Object byte[] $Bytes
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($buf) } finally { $rng.Dispose() }
    return ([System.BitConverter]::ToString($buf)).Replace('-','').ToLowerInvariant()
}

function Read-SimpleProperties([string]$Path) {
    $h = @{}
    if (-not (Test-Path $Path)) { return $h }
    foreach ($line in Get-Content $Path) {
        $t = $line.Trim()
        if (-not $t -or $t.StartsWith('#')) { continue }
        $idx = $t.IndexOf('=')
        if ($idx -lt 1) { continue }
        $h[$t.Substring(0,$idx).Trim()] = $t.Substring($idx+1).Trim()
    }
    return $h
}

function Initialize-ReleaseSigning([string]$JavaHome, [string]$ProjectDir) {
    $keytool = Join-Path $JavaHome 'bin\keytool.exe'
    if (-not (Test-Path $keytool)) { Fail 'keytool.exe was not found in JAVA_HOME.' }

    # Explicit CI/environment settings win.
    if ($env:MAGEN_KEYSTORE_FILE -and $env:MAGEN_KEYSTORE_PASSWORD -and $env:MAGEN_KEY_ALIAS -and $env:MAGEN_KEY_PASSWORD) {
        $store = $env:MAGEN_KEYSTORE_FILE
        $storePass = $env:MAGEN_KEYSTORE_PASSWORD
        $alias = $env:MAGEN_KEY_ALIAS
        $keyPass = $env:MAGEN_KEY_PASSWORD
        if (-not (Test-Path $store)) { Fail "Configured release keystore does not exist: $store" }
    } else {
        $privateDir = Join-Path $ProjectDir '.magen-private'
        $propsPath = Join-Path $privateDir 'signing.properties'
        New-Item -ItemType Directory -Force -Path $privateDir | Out-Null
        $props = Read-SimpleProperties $propsPath
        $store = Join-Path $privateDir 'magen-release.p12'
        $alias = 'magen-release'

        if (Test-Path $propsPath) {
            foreach ($required in @('storePassword','keyPassword','keyAlias')) {
                if (-not $props.ContainsKey($required) -or -not $props[$required]) {
                    Fail "Invalid $propsPath: missing $required"
                }
            }
            $storePass = $props['storePassword']
            $keyPass = $props['keyPassword']
            $alias = $props['keyAlias']
            if ($props.ContainsKey('storeFile') -and $props['storeFile']) {
                $candidate = $props['storeFile']
                if ([System.IO.Path]::IsPathRooted($candidate)) { $store = $candidate }
                else { $store = Join-Path $ProjectDir $candidate }
            }
            if (-not (Test-Path $store)) {
                Fail "Signing properties exist but keystore is missing: $store. Restore the original .magen-private backup; do not generate a new key for an existing installed app."
            }
        } else {
            $storePass = New-HexSecret 24
            $keyPass = $storePass  # PKCS12 uses one password reliably across JDKs.
            Info 'Creating a new persistent release-signing key (first production build only)...'
            & $keytool -genkeypair -noprompt -alias $alias -keyalg EC -groupname secp256r1 `
                -sigalg SHA256withECDSA -validity 10000 -dname 'CN=Magen Release,O=Magen,C=IL' `
                -storetype PKCS12 -keystore $store -storepass $storePass -keypass $keyPass
            if ($LASTEXITCODE -ne 0 -or -not (Test-Path $store)) { Fail 'keytool failed to create the release keystore.' }
            @(
                '# PRIVATE. Back up this whole .magen-private folder securely.',
                'storeFile=.magen-private/magen-release.p12',
                "storePassword=$storePass",
                "keyAlias=$alias",
                "keyPassword=$keyPass"
            ) | Set-Content -Path $propsPath -Encoding ascii
            try {
                & icacls.exe $privateDir /inheritance:r /grant:r "$env:USERNAME`:(OI)(CI)F" | Out-Null
            } catch { Write-Host '[Magen] Could not tighten ACL automatically; keep .magen-private private.' -ForegroundColor Yellow }
            Write-Host '[Magen] IMPORTANT: back up .magen-private. Losing this key prevents seamless APK updates.' -ForegroundColor Yellow
        }
    }

    $certDer = Join-Path $env:TEMP 'magen-release-cert.der'
    Remove-Item $certDer -Force -ErrorAction SilentlyContinue
    & $keytool -exportcert -alias $alias -keystore $store -storepass $storePass -file $certDer
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $certDer)) { Fail 'Could not export release certificate.' }
    $fingerprint = (Get-FileHash -Algorithm SHA256 $certDer).Hash.ToLowerInvariant()
    Remove-Item $certDer -Force -ErrorAction SilentlyContinue

    $env:MAGEN_KEYSTORE_FILE = $store
    $env:MAGEN_KEYSTORE_PASSWORD = $storePass
    $env:MAGEN_KEY_ALIAS = $alias
    $env:MAGEN_KEY_PASSWORD = $keyPass
    $env:MAGEN_RELEASE_CERT_SHA256 = $fingerprint

    return @{
        Store = $store; StorePassword = $storePass; Alias = $alias; KeyPassword = $keyPass; Fingerprint = $fingerprint
    }
}

$ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ProjectDir
Info 'Windows APK builder v4.5.1-audited-https-inspection-8443'
Info "Project: $ProjectDir"

$JavaHome = Find-JavaHome
if (-not $JavaHome) { Fail 'JDK 17 or 21 not found. Install Temurin 21 (recommended) or Android Studio.' }
$env:JAVA_HOME = $JavaHome
$env:Path = "$JavaHome\bin;$env:Path"
Info "JAVA_HOME=$JavaHome"
$javaText = (& (Join-Path $JavaHome 'bin\java.exe') -version 2>&1 | Out-String)
Write-Host $javaText.Trim()
$javaVersionMatch = [regex]::Match($javaText, 'version "(?<major>\d+)(?:\.|\")')
if (-not $javaVersionMatch.Success) { Fail 'Could not determine Java version.' }
$javaMajor = [int]$javaVersionMatch.Groups['major'].Value
if ($javaMajor -ne 17 -and $javaMajor -ne 21) { Fail "Java $javaMajor detected. Use JDK 17/21." }

$Sdk = Find-AndroidSdk
if (-not $Sdk) { Fail 'Android SDK not found. Install it from Android Studio SDK Manager.' }
$env:ANDROID_SDK_ROOT = $Sdk
$env:ANDROID_HOME = $Sdk
Info "ANDROID_SDK_ROOT=$Sdk"

$sdkManager = Find-SdkManager $Sdk
$platformOk = Test-Path (Join-Path $Sdk 'platforms\android-36\android.jar')
$buildTools = Join-Path $Sdk 'build-tools\36.0.0'
$buildToolsOk = Test-Path $buildTools
if ((-not $platformOk) -or (-not $buildToolsOk)) {
    if (-not $sdkManager) { Fail 'Android API 36 / Build Tools 36.0.0 missing and sdkmanager was not found.' }
    Info 'Accepting Android SDK licenses...'
    $yesFile = Join-Path $env:TEMP 'magen-sdk-yes.txt'
    ((1..100) | ForEach-Object { 'y' }) | Set-Content -Path $yesFile -Encoding ascii
    try { cmd.exe /d /c "type \"$yesFile\" | \"$sdkManager\" --licenses" | Out-Host }
    finally { Remove-Item $yesFile -Force -ErrorAction SilentlyContinue }
    Info 'Installing Android API 36 and Build Tools 36.0.0...'
    & $sdkManager 'platform-tools' 'platforms;android-36' 'build-tools;36.0.0'
    if ($LASTEXITCODE -ne 0) { Fail "sdkmanager failed with exit code $LASTEXITCODE" }
}

if (-not (Test-Path '.\gradlew.bat')) { Fail 'gradlew.bat is missing.' }
if (-not (Test-Path '.\gradle\wrapper\gradle-wrapper.jar')) { Fail 'gradle-wrapper.jar is missing.' }
$sdkForGradle = $Sdk.Replace('\','\\')
"sdk.dir=$sdkForGradle" | Set-Content -Path '.\local.properties' -Encoding ascii

# -------- Visual AI supply-chain bootstrap --------
$VisualAssets = Join-Path $ProjectDir 'app\src\main\assets'
$VisualModel = Join-Path $VisualAssets 'nsfw_mobilenet_v2_140_224.tflite'
$ModelLock = Join-Path $ProjectDir 'VISUAL_MODEL_SHA256.lock'
New-Item -ItemType Directory -Force -Path $VisualAssets | Out-Null
$archiveVerified = $false

if (-not (Test-Path $VisualModel)) {
    Info 'Downloading pinned Visual AI release 1.1.0...'
    $ModelUrl = 'https://github.com/GantMan/nsfw_model/releases/download/1.1.0/nsfw_mobilenet_v2_140_224.zip'
    $MirrorUrl = 'https://downloads.sourceforge.net/project/nsfw-detection-ml.mirror/1.1.0/nsfw_mobilenet_v2_140_224.zip'
    $TmpZip = Join-Path $env:TEMP 'magen_nsfw_primary.zip'
    $TmpMirror = Join-Path $env:TEMP 'magen_nsfw_mirror.zip'
    $TmpDir = Join-Path $env:TEMP 'magen_nsfw_model_unpack'
    Remove-Item $TmpZip,$TmpMirror -Force -ErrorAction SilentlyContinue
    if (Test-Path $TmpDir) { Remove-Item -Recurse -Force $TmpDir }

    $ExpectedArchiveDigest = $null
    try {
        $release = Invoke-RestMethod -Uri 'https://api.github.com/repos/GantMan/nsfw_model/releases/tags/1.1.0' -Headers @{ 'User-Agent'='MagenBuilder/4.5.1' }
        $asset = $release.assets | Where-Object { $_.name -eq 'nsfw_mobilenet_v2_140_224.zip' } | Select-Object -First 1
        if ($asset -and $asset.digest -and ([string]$asset.digest).StartsWith('sha256:')) {
            $ExpectedArchiveDigest = ([string]$asset.digest).Substring(7).ToLowerInvariant()
        }
    } catch { Write-Host '[Visual AI] GitHub digest API unavailable.' -ForegroundColor Yellow }

    Invoke-WebRequest -Uri $ModelUrl -OutFile $TmpZip -UseBasicParsing
    $ArchiveHash = (Get-FileHash -Algorithm SHA256 $TmpZip).Hash.ToLowerInvariant()
    if ($ExpectedArchiveDigest) {
        if ($ArchiveHash -ne $ExpectedArchiveDigest) { Fail "Visual archive SHA256 mismatch: expected $ExpectedArchiveDigest got $ArchiveHash" }
        $archiveVerified = $true
        Write-Host '[Visual AI] GitHub-published archive digest verified.' -ForegroundColor Green
    } elseif (-not (Test-Path $ModelLock)) {
        # Never trust the first download blindly. Bootstrap by requiring the
        # pinned GitHub asset and the SourceForge mirror to be byte-identical.
        Info 'No local model lock yet; verifying first download against mirror...'
        Invoke-WebRequest -Uri $MirrorUrl -OutFile $TmpMirror -UseBasicParsing
        $MirrorHash = (Get-FileHash -Algorithm SHA256 $TmpMirror).Hash.ToLowerInvariant()
        if ($ArchiveHash -ne $MirrorHash) { Fail "Primary/mirror model archives differ ($ArchiveHash vs $MirrorHash). Refusing TOFU bootstrap." }
        $archiveVerified = $true
        Write-Host '[Visual AI] first-build archive verified by primary/mirror consensus.' -ForegroundColor Green
    }

    Expand-Archive -Path $TmpZip -DestinationPath $TmpDir -Force
    $Candidate = Get-ChildItem -Path $TmpDir -Recurse -File -Filter 'saved_model.tflite' | Select-Object -First 1
    if (-not $Candidate) { Fail 'Visual model archive did not contain saved_model.tflite.' }
    Copy-Item -Force $Candidate.FullName $VisualModel
    Remove-Item $TmpZip,$TmpMirror -Force -ErrorAction SilentlyContinue
    Remove-Item $TmpDir -Recurse -Force -ErrorAction SilentlyContinue
}

$ModelInfo = Get-Item $VisualModel
if ($ModelInfo.Length -lt 1000000) { Fail "Visual model looks invalid/small: $($ModelInfo.Length) bytes" }
$ModelHash = (Get-FileHash -Algorithm SHA256 $VisualModel).Hash.ToLowerInvariant()
if (Test-Path $ModelLock) {
    $LockedHash = (Get-Content $ModelLock -Raw).Trim().ToLowerInvariant()
    if ($LockedHash -notmatch '^[0-9a-f]{64}$') { Fail 'Invalid VISUAL_MODEL_SHA256.lock' }
    if ($ModelHash -ne $LockedHash) { Fail "Visual model changed. Expected $LockedHash got $ModelHash" }
    Write-Host '[Visual AI] local model lock verified.' -ForegroundColor Green
} else {
    if (-not $archiveVerified) { Fail 'Refusing to create a first-use model lock without an independently verified archive.' }
    $ModelHash | Set-Content -Path $ModelLock -Encoding ascii
    Write-Host '[Visual AI] verified model lock created.' -ForegroundColor Green
}
Write-Host "[Visual AI] model OK: $($ModelInfo.Length) bytes SHA256=$ModelHash" -ForegroundColor Green

# -------- Release signing --------
$signing = Initialize-ReleaseSigning $JavaHome $ProjectDir
Info "Release certificate SHA256=$($signing.Fingerprint)"

# Optional pairing imported from a freshly generated VPS PKI.
$pairingPropsPath = Join-Path $ProjectDir '.magen-private\server-pairing.properties'
if (Test-Path $pairingPropsPath) {
    $pair = Read-SimpleProperties $pairingPropsPath
    if (-not $pair.ContainsKey('magenServerUrl') -or $pair['magenServerUrl'] -notmatch '^https://[^/]+:8443$') {
        Fail 'Imported server pairing has an invalid URL; Magen production must use HTTPS :8443.'
    }
    if (-not $pair.ContainsKey('magenServerSigningPublicKey') -or -not $pair['magenServerSigningPublicKey']) {
        Fail 'Imported server pairing is missing magenServerSigningPublicKey.'
    }
    $env:MAGEN_SERVER_URL = $pair['magenServerUrl']
    $env:MAGEN_SERVER_SIGNING_PUBLIC_KEY = $pair['magenServerSigningPublicKey']
    # Update manifests use a separate offline signing key. Never reuse the online VPS
    # application-signing key as an update root of trust. If no update key is supplied,
    # UpdateChecker remains fail-closed/disabled until MAGEN_UPDATE_PUBKEY_B64 is configured.
    if (-not $env:MAGEN_UPDATE_PUBKEY_B64) { Write-Warning 'MAGEN_UPDATE_PUBKEY_B64 is not set; signed update checks will remain disabled.' }
    Info "Using imported VPS pairing: $($pair['magenServerUrl'])"
} else {
    Info 'Using bundled live VPS pairing: https://51.20.205.229:8443'
}

$python = Get-Command python.exe -ErrorAction SilentlyContinue
if (-not $python) { $python = Get-Command py.exe -ErrorAction SilentlyContinue }
if ($python -and (Test-Path '.\verify.py')) {
    Info 'Running Magen strict verifier...'
    if ($python.Name -ieq 'py.exe') { & $python.Source -3 '.\verify.py' '--strict' }
    else { & $python.Source '.\verify.py' '--strict' }
    if ($LASTEXITCODE -ne 0) { Fail 'verify.py failed.' }
} else {
    Write-Host '[Magen] Python not found; static verify.py skipped. Gradle tests still run.' -ForegroundColor Yellow
}

$buildId = 'prod-' + (Get-Date -Format 'yyyyMMdd-HHmmss')
$common = @(
    '--no-daemon',
    "-PvisualModelSha256=$ModelHash",
    "-PreleaseCertSha256=$($signing.Fingerprint)",
    "-PbuildId=$buildId"
)

Info 'Running release JVM unit tests...'
& .\gradlew.bat @common testReleaseUnitTest
if ($LASTEXITCODE -ne 0) { Fail 'Release unit tests failed.' }

Info 'Building signed RELEASE APK...'
& .\gradlew.bat @common assembleRelease
if ($LASTEXITCODE -ne 0) { Fail 'Gradle release APK build failed.' }

$builtApk = Join-Path $ProjectDir 'app\build\outputs\apk\release\app-release.apk'
if (-not (Test-Path $builtApk)) { Fail "Build succeeded but APK was not found at $builtApk" }
$outName = 'magen-v4.5.1-audited-https-inspection-8443-release.apk'
$outApk = Join-Path $ProjectDir $outName
Copy-Item $builtApk $outApk -Force

$apksigner = Join-Path $buildTools 'apksigner.bat'
if (-not (Test-Path $apksigner)) { Fail 'apksigner.bat not found in Build Tools 36.0.0.' }
Info 'Verifying APK signature and signer certificate...'
& $apksigner verify --verbose --print-certs $outApk | Out-Host
if ($LASTEXITCODE -ne 0) { Fail 'apksigner verification failed.' }

$hash = (Get-FileHash -Path $outApk -Algorithm SHA256).Hash.ToLowerInvariant()
"$hash  $outName" | Set-Content -Path (Join-Path $ProjectDir "$outName.sha256") -Encoding ascii

Write-Host ''
Write-Host '============================================================' -ForegroundColor Green
Write-Host ' Magen signed RELEASE APK completed successfully' -ForegroundColor Green
Write-Host '============================================================' -ForegroundColor Green
Write-Host "APK:             $outApk"
Write-Host "APK SHA256:      $hash"
Write-Host "Signer SHA256:   $($signing.Fingerprint)"
Write-Host "Build ID:        $buildId"
Write-Host ''
Write-Host 'BACK UP .magen-private securely. Never share that folder.' -ForegroundColor Yellow
