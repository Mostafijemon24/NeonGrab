# Pack ARM64 engine native libs for lean APK first-run download.
# Upload the zip to GitHub: release tag engine-0.18.1
$ErrorActionPreference = "Stop"
$Root = Split-Path $PSScriptRoot -Parent
$OutDir = Join-Path $Root "releases"
$ZipName = "neongrab-engine-arm64-v0.18.1.zip"
$ZipPath = Join-Path $OutDir $ZipName
$Tmp = Join-Path $env:TEMP "neongrab-engine-pack"

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
Remove-Item $Tmp -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $Tmp | Out-Null

$libs = @(
    "libpython.so",
    "libpython.zip.so",
    "libffmpeg.so",
    "libffmpeg.zip.so",
    "libffprobe.so",
    "libqjs.so"
)

# Prefer libs from a recent full APK (before jni excludes)
$ApkCandidates = @(
    (Join-Path $OutDir "NeonGrab-1.2.4.apk"),
    (Join-Path $OutDir "NeonGrab-1.2.0.apk"),
    (Join-Path $Root "android\app\build\outputs\apk\release\app-release-unsigned.apk"),
    (Join-Path $Root "android\app\build\outputs\apk\debug\app-debug.apk")
)

$found = $false
foreach ($apk in $ApkCandidates) {
    if (-not (Test-Path $apk)) { continue }
    Write-Host "Extracting from $apk ..."
    foreach ($lib in $libs) {
        $entry = "lib/arm64-v8a/$lib"
        & tar -xf $apk -C $Tmp $entry 2>$null
        $src = Join-Path $Tmp $entry
        if (Test-Path $src) {
            Copy-Item $src (Join-Path $Tmp $lib) -Force
        }
    }
    Remove-Item (Join-Path $Tmp "lib") -Recurse -Force -ErrorAction SilentlyContinue
    if ((Get-ChildItem $Tmp -Filter "*.so").Count -ge 5) {
        $found = $true
        break
    }
}

if (-not $found) {
    Write-Host "No full APK with native libs found. Build once WITHOUT jni excludes, or copy .so files into:"
    Write-Host $Tmp
    exit 1
}

Remove-Item $ZipPath -Force -ErrorAction SilentlyContinue
Compress-Archive -Path (Join-Path $Tmp "*.so") -DestinationPath $ZipPath -Force
$mb = [math]::Round((Get-Item $ZipPath).Length / 1MB, 1)
Write-Host "Created $ZipPath ($mb MB)"
Write-Host ""
Write-Host "Upload to GitHub:"
Write-Host "  gh release create engine-0.18.1 `"$ZipPath`" --title `"NeonGrab engine pack (ARM64)`""
