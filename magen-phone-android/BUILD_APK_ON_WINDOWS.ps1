$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Fail([string]$Message) {
    Write-Host "";
    Write-Host "[Magen] ERROR: $Message" -ForegroundColor Red
    exit 1
}

function Info([string]$Message) {
    Write-Host "[Magen] $Message" -ForegroundColor Cyan
}

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
    # Gradle/AGP in this project is intentionally built with an LTS JDK.
    # Do not blindly honor JAVA_HOME when it points at Java 25+; that caused
    # "Unsupported class file major version 69" on Windows.
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
    foreach ($p in $known) { if (Test-Path $p) { return $p } }

    $found = Get-ChildItem -Path (Join-Path $Sdk 'cmdline-tools') -Filter sdkmanager.bat -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($found) { return $found.FullName }
    return $null
}

$ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ProjectDir

Info 'Windows APK builder v4.2.1-paired-8443'
Info "Project: $ProjectDir"

$JavaHome = Find-JavaHome
if (-not $JavaHome) {
    Fail 'JDK 17 or 21 not found. Install Temurin 21 (recommended) or Android Studio, then run again.'
}
$env:JAVA_HOME = $JavaHome
$env:Path = "$JavaHome\bin;$env:Path"
Info "JAVA_HOME=$JavaHome"

$javaText = (& (Join-Path $JavaHome 'bin\java.exe') -version 2>&1 | Out-String)
Write-Host $javaText.Trim()
$javaVersionMatch = [regex]::Match($javaText, 'version "(?<major>\d+)(?:\.|\")')
if (-not $javaVersionMatch.Success) { Fail 'Could not determine Java version.' }
$javaMajor = [int]$javaVersionMatch.Groups['major'].Value
if ($javaMajor -ne 17 -and $javaMajor -ne 21) { Fail "Java $javaMajor detected. Magen build is pinned to JDK 17/21; Java 25 causes Gradle major-version errors." }

$Sdk = Find-AndroidSdk
if (-not $Sdk) {
    Fail 'Android SDK not found. In Android Studio open SDK Manager and install Android SDK, then run again.'
}
$env:ANDROID_SDK_ROOT = $Sdk
$env:ANDROID_HOME = $Sdk
Info "ANDROID_SDK_ROOT=$Sdk"

$sdkManager = Find-SdkManager $Sdk
$platformOk = Test-Path (Join-Path $Sdk 'platforms\android-36\android.jar')
$buildTools = Join-Path $Sdk 'build-tools\36.0.0'
$buildToolsOk = Test-Path $buildTools

if ((-not $platformOk) -or (-not $buildToolsOk)) {
    if (-not $sdkManager) {
        Fail 'Android API 36/Build Tools 36.0.0 are missing and sdkmanager was not found. In Android Studio > SDK Manager install Android 16 (API 36), Build Tools 36.0.0, and Android SDK Command-line Tools (latest).'
    }

    Info 'Accepting Android SDK licenses...'
    $yesFile = Join-Path $env:TEMP 'magen-sdk-yes.txt'
    ((1..100) | ForEach-Object { 'y' }) | Set-Content -Path $yesFile -Encoding ascii
    try {
        cmd.exe /d /c "type \"$yesFile\" | \"$sdkManager\" --licenses" | Out-Host
    } finally {
        Remove-Item $yesFile -Force -ErrorAction SilentlyContinue
    }

    Info 'Installing Android API 36 and Build Tools 36.0.0...'
    & $sdkManager 'platform-tools' 'platforms;android-36' 'build-tools;36.0.0'
    if ($LASTEXITCODE -ne 0) { Fail "sdkmanager failed with exit code $LASTEXITCODE" }
}

if (-not (Test-Path '.\gradlew.bat')) { Fail 'gradlew.bat is missing.' }
if (-not (Test-Path '.\gradle\wrapper\gradle-wrapper.jar')) { Fail 'gradle-wrapper.jar is missing.' }

# Write local.properties with escaped Windows path for Gradle.
$sdkForGradle = $Sdk.Replace('\\','\\\\')
"sdk.dir=$sdkForGradle" | Set-Content -Path '.\local.properties' -Encoding ascii

$python = Get-Command python.exe -ErrorAction SilentlyContinue
if (-not $python) { $python = Get-Command py.exe -ErrorAction SilentlyContinue }

# ---- Visual AI model (local-only) ----
# Supply-chain strategy:
# 1) fixed upstream release URL;
# 2) when GitHub exposes a release-asset SHA256 digest, verify the downloaded archive;
# 3) persist a local SHA256 lock for the extracted TFLite model (TOFU fallback for old assets);
# 4) inject the model hash into BuildConfig and verify it again at runtime before inference.
$VisualAssets = Join-Path $PSScriptRoot "app\src\main\assets"
$VisualModel = Join-Path $VisualAssets "nsfw_mobilenet_v2_140_224.tflite"
$ModelLock = Join-Path $PSScriptRoot "VISUAL_MODEL_SHA256.lock"
New-Item -ItemType Directory -Force -Path $VisualAssets | Out-Null

if (-not (Test-Path $VisualModel)) {
    Write-Host "[Visual AI] Downloading pinned upstream release 1.1.0..." -ForegroundColor Cyan
    $ModelUrl = "https://github.com/GantMan/nsfw_model/releases/download/1.1.0/nsfw_mobilenet_v2_140_224.zip"
    $MirrorUrl = "https://downloads.sourceforge.net/project/nsfw-detection-ml.mirror/1.1.0/nsfw_mobilenet_v2_140_224.zip"
    $TmpZip = Join-Path $env:TEMP "magen_nsfw_mobilenet_v2_140_224.zip"
    $TmpDir = Join-Path $env:TEMP "magen_nsfw_model_unpack"
    if (Test-Path $TmpDir) { Remove-Item -Recurse -Force $TmpDir }

    $ExpectedArchiveDigest = $null
    try {
        $release = Invoke-RestMethod -Uri "https://api.github.com/repos/GantMan/nsfw_model/releases/tags/1.1.0" -Headers @{ 'User-Agent'='MagenBuilder/4.2.1' }
        $asset = $release.assets | Where-Object { $_.name -eq 'nsfw_mobilenet_v2_140_224.zip' } | Select-Object -First 1
        if ($asset -and $asset.digest -and ([string]$asset.digest).StartsWith('sha256:')) {
            $ExpectedArchiveDigest = ([string]$asset.digest).Substring(7).ToLowerInvariant()
            Info "GitHub published archive digest: $ExpectedArchiveDigest"
        }
    } catch {
        Write-Host "[Visual AI] Could not query GitHub asset digest; local model lock will still protect subsequent builds." -ForegroundColor Yellow
    }

    try {
        Invoke-WebRequest -Uri $ModelUrl -OutFile $TmpZip -UseBasicParsing
    } catch {
        Write-Host "[Visual AI] GitHub download failed; trying SourceForge mirror..." -ForegroundColor Yellow
        Invoke-WebRequest -Uri $MirrorUrl -OutFile $TmpZip -UseBasicParsing
    }

    $ArchiveHash = (Get-FileHash -Algorithm SHA256 $TmpZip).Hash.ToLowerInvariant()
    if ($ExpectedArchiveDigest -and $ArchiveHash -ne $ExpectedArchiveDigest) {
        Remove-Item $TmpZip -Force -ErrorAction SilentlyContinue
        Fail "Visual model archive SHA256 mismatch. Expected $ExpectedArchiveDigest got $ArchiveHash"
    }
    if ($ExpectedArchiveDigest) { Write-Host "[Visual AI] upstream archive SHA256 verified" -ForegroundColor Green }

    Expand-Archive -Path $TmpZip -DestinationPath $TmpDir -Force
    $Candidate = Get-ChildItem -Path $TmpDir -Recurse -File -Filter "saved_model.tflite" | Select-Object -First 1
    if (-not $Candidate) { throw "Visual model archive did not contain saved_model.tflite" }
    Copy-Item -Force $Candidate.FullName $VisualModel
    Remove-Item $TmpZip -Force -ErrorAction SilentlyContinue
    Remove-Item $TmpDir -Recurse -Force -ErrorAction SilentlyContinue
}

$ModelInfo = Get-Item $VisualModel
if ($ModelInfo.Length -lt 1000000) { Fail "Visual model looks invalid/small: $($ModelInfo.Length) bytes" }
$ModelHash = (Get-FileHash -Algorithm SHA256 $VisualModel).Hash.ToLowerInvariant()

if (Test-Path $ModelLock) {
    $LockedHash = (Get-Content $ModelLock -Raw).Trim().ToLowerInvariant()
    if ($LockedHash -notmatch '^[0-9a-f]{64}$') { Fail "Invalid VISUAL_MODEL_SHA256.lock" }
    if ($ModelHash -ne $LockedHash) { Fail "Visual model changed since the trusted build. Expected $LockedHash got $ModelHash" }
    Write-Host "[Visual AI] local model lock verified" -ForegroundColor Green
} else {
    $ModelHash | Set-Content -Path $ModelLock -Encoding ascii
    Write-Host "[Visual AI] Created VISUAL_MODEL_SHA256.lock for subsequent builds (trust-on-first-use fallback)." -ForegroundColor Yellow
}
Write-Host "[Visual AI] model OK: $($ModelInfo.Length) bytes SHA256=$ModelHash" -ForegroundColor Green

if ($python -and (Test-Path '.\verify.py')) {
    Info 'Running Magen strict verifier...'
    if ($python.Name -ieq 'py.exe') {
        & $python.Source -3 '.\verify.py' '--strict'
    } else {
        & $python.Source '.\verify.py' '--strict'
    }
    if ($LASTEXITCODE -ne 0) { Fail 'verify.py failed.' }
} else {
    Write-Host '[Magen] Python not found; skipping verify.py. Gradle tests will still run.' -ForegroundColor Yellow
}

Info 'Running Gradle unit tests...'
& .\gradlew.bat --no-daemon "-PvisualModelSha256=$ModelHash" testDebugUnitTest
if ($LASTEXITCODE -ne 0) { Fail 'Gradle unit tests failed.' }

Info 'Building debug APK...'
& .\gradlew.bat --no-daemon "-PvisualModelSha256=$ModelHash" assembleDebug
if ($LASTEXITCODE -ne 0) { Fail 'Gradle APK build failed.' }

$builtApk = Join-Path $ProjectDir 'app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path $builtApk)) { Fail "Build succeeded but APK was not found at $builtApk" }

$outApk = Join-Path $ProjectDir 'magen-v4.2.1-paired-8443-debug.apk'
Copy-Item $builtApk $outApk -Force
$hash = (Get-FileHash -Path $outApk -Algorithm SHA256).Hash.ToLowerInvariant()
"$hash  magen-v4.2.1-paired-8443-debug.apk" | Set-Content -Path (Join-Path $ProjectDir 'magen-v4.2.1-paired-8443-debug.apk.sha256') -Encoding ascii

$apksigner = Join-Path $buildTools 'apksigner.bat'
if (Test-Path $apksigner) {
    Info 'Verifying APK signature...'
    & $apksigner verify --verbose $outApk
    if ($LASTEXITCODE -ne 0) { Fail 'apksigner verification failed.' }
}

Write-Host ''
Write-Host '============================================' -ForegroundColor Green
Write-Host ' Magen APK build completed successfully' -ForegroundColor Green
Write-Host '============================================' -ForegroundColor Green
Write-Host "APK:    $outApk"
Write-Host "SHA256: $hash"
Write-Host ''
