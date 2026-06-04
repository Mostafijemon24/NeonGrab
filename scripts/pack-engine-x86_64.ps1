# Pack x86_64 engine native libs for emulator / x86_64 devices.
$ErrorActionPreference = "Stop"
$Root = Split-Path $PSScriptRoot -Parent
$OutDir = Join-Path $Root "releases"
$ZipName = "neongrab-engine-x86_64-v0.18.1.zip"
$ZipPath = Join-Path $OutDir $ZipName
$Tmp = Join-Path $env:TEMP "neongrab-engine-x86-pack"

$libs = @(
    "libpython.so",
    "libpython.zip.so",
    "libffmpeg.so",
    "libffmpeg.zip.so",
    "libffprobe.so",
    "libqjs.so"
)

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
Remove-Item $Tmp -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $Tmp | Out-Null

$JniDir = Join-Path $Root "android\app\build\ytdlp-jni\x86_64"
$AarLib = $null
foreach ($aar in @(
    (Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\io.github.junkfood02.youtubedl-android\library\0.18.1" -Recurse -Filter "library-0.18.1.aar" -ErrorAction SilentlyContinue | Select-Object -First 1),
    (Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\io.github.junkfood02.youtubedl-android\ffmpeg\0.18.1" -Recurse -Filter "ffmpeg-0.18.1.aar" -ErrorAction SilentlyContinue | Select-Object -First 1)
)) {
    if ($null -eq $aar) { continue }
}

if (Test-Path $JniDir) {
    Write-Host "Using Gradle unpack: $JniDir"
    foreach ($lib in $libs) {
        $src = Join-Path $JniDir $lib
        if (Test-Path $src) { Copy-Item $src (Join-Path $Tmp $lib) -Force }
    }
} else {
    Write-Host "Run: cd android && gradlew unpackYtdlpJniDebug"
    Write-Host "Or extract from youtubedl-android AAR jni/x86_64/"
    exit 1
}

$count = (Get-ChildItem $Tmp -Filter "*.so").Count
if ($count -lt 5) {
    Write-Host "Missing .so files in $Tmp (found $count)"
    exit 1
}

Remove-Item $ZipPath -Force -ErrorAction SilentlyContinue
Compress-Archive -Path (Join-Path $Tmp "*.so") -DestinationPath $ZipPath -Force
$mb = [math]::Round((Get-Item $ZipPath).Length / 1MB, 1)
Write-Host "Created $ZipPath ($mb MB)"
