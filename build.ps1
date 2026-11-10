$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$Project = $PSScriptRoot
# SDK: ANDROID_HOME / ANDROID_SDK_ROOT, else Android Studio's default location.
$Sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
# JDK 17: Android Studio's bundled JBR if present, else JAVA_HOME.
$StudioJbr = 'C:\Program Files\Android\Android Studio\jbr\bin'
$JdkBin = if (Test-Path -LiteralPath (Join-Path $StudioJbr 'javac.exe')) { $StudioJbr } elseif ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin' } else { $StudioJbr }
$AndroidJar = Join-Path $Sdk 'platforms\android-34\android.jar'
$BuildTools = Join-Path $Sdk 'build-tools\34.0.0'
$Aapt2 = Join-Path $BuildTools 'aapt2.exe'
$D8 = Join-Path $BuildTools 'd8.bat'
$Zipalign = Join-Path $BuildTools 'zipalign.exe'
$Apksigner = Join-Path $BuildTools 'apksigner.bat'
$Keytool = Join-Path $JdkBin 'keytool.exe'
$Javac = Join-Path $JdkBin 'javac.exe'
$Work = Join-Path $Project 'build'
$Gen = Join-Path $Work 'gen'
$Classes = Join-Path $Work 'classes'
$Dex = Join-Path $Work 'dex'
$Out = Join-Path $Project 'out'

# Build-tools batch launchers use JAVA_HOME/PATH; pin them to the requested JDK 17.
$env:JAVA_HOME = Split-Path -Parent $JdkBin
$env:PATH = "$JdkBin;$env:PATH"

foreach ($file in @($AndroidJar,$Aapt2,$D8,$Zipalign,$Apksigner,$Keytool,$Javac)) {
    if (-not (Test-Path -LiteralPath $file)) { throw "Required build tool not found: $file" }
}
New-Item -ItemType Directory -Force -Path $Work,$Gen,$Classes,$Dex,$Out | Out-Null
Get-ChildItem -LiteralPath $Gen,$Classes,$Dex -Force -ErrorAction SilentlyContinue | Remove-Item -Recurse -Force

Push-Location $Project
try {
    & $Aapt2 compile --dir (Join-Path $Project 'res') -o (Join-Path $Work 'compiled-res.zip')
    if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed: $LASTEXITCODE" }
    & $Aapt2 link -o (Join-Path $Work 'unsigned.apk') --manifest (Join-Path $Project 'AndroidManifest.xml') -I $AndroidJar -R (Join-Path $Work 'compiled-res.zip') --java $Gen --min-sdk-version 28 --target-sdk-version 28 --version-code 1 --version-name '1.0'
    if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed: $LASTEXITCODE" }
    $sources = @(Get-ChildItem -Path (Join-Path $Project 'src'),$Gen -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
    if ($sources.Count -eq 0) { throw 'No Java source files found.' }
    & $Javac --release 8 -encoding UTF-8 -classpath $AndroidJar -d $Classes $sources
    if ($LASTEXITCODE -ne 0) { throw "javac failed: $LASTEXITCODE" }
    & $D8 --min-api 28 --lib $AndroidJar --output $Dex (Get-ChildItem -Path $Classes -Recurse -Filter '*.class' | ForEach-Object { $_.FullName })
    if ($LASTEXITCODE -ne 0) { throw "d8 failed: $LASTEXITCODE" }
    $apk = Join-Path $Work 'unsigned.apk'
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::Open($apk,[System.IO.Compression.ZipArchiveMode]::Update)
    try {
        $entry = $zip.CreateEntry('classes.dex',[System.IO.Compression.CompressionLevel]::Optimal)
        $stream = $entry.Open()
        $dexStream = [System.IO.File]::OpenRead((Join-Path $Dex 'classes.dex'))
        try { $dexStream.CopyTo($stream) } finally { $dexStream.Dispose(); $stream.Dispose() }
    } finally { $zip.Dispose() }
    $aligned = Join-Path $Work 'aligned.apk'
    & $Zipalign -f 4 $apk $aligned
    if ($LASTEXITCODE -ne 0) { throw "zipalign failed: $LASTEXITCODE" }
    $keystore = Join-Path $Project 'debug.keystore'
    if (-not (Test-Path -LiteralPath $keystore)) {
        & $Keytool -genkeypair -keystore $keystore -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Android Debug,O=Android,C=US'
        if ($LASTEXITCODE -ne 0) { throw "keytool failed: $LASTEXITCODE" }
    }
    $final = Join-Path $Out 'NetflixRemoteHelper.apk'
    & $Apksigner sign --ks $keystore --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out $final $aligned
    if ($LASTEXITCODE -ne 0) { throw "apksigner sign failed: $LASTEXITCODE" }
    & $Apksigner verify --verbose $final
    if ($LASTEXITCODE -ne 0) { throw "apksigner verify failed: $LASTEXITCODE" }
    & $Aapt2 dump badging $final
    if ($LASTEXITCODE -ne 0) { throw "aapt2 dump badging failed: $LASTEXITCODE" }
    Write-Host "Built: $final"
} finally { Pop-Location }
