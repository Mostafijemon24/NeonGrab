# Downloads latest yt-dlp release and extracts ARM64 binary for Android assets.
$ErrorActionPreference = "Stop"
$Root = Split-Path $PSScriptRoot -Parent
$AssetsBin = Join-Path $Root "android\app\src\main\assets\bin"
$Dest = Join-Path $AssetsBin "yt-dlp"
$CapAssets = Join-Path $Root "android\app\src\main\assets"

New-Item -ItemType Directory -Force -Path $AssetsBin | Out-Null

function Get-YtDlpAsset($repo) {
    $release = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/releases/latest"
    return $release.assets | Where-Object {
        $_.name -eq "yt-dlp" -or ($_.name -match "^yt-dlp" -and $_.name -match "\.tar\.xz$")
    } | Select-Object -First 1
}

$asset = Get-YtDlpAsset "yt-dlp/yt-dlp-nightly-builds"
if (-not $asset) { $asset = Get-YtDlpAsset "yt-dlp/yt-dlp" }
if (-not $asset) {
    Write-Host "No yt-dlp tar.xz asset found on nightly or stable releases."
    exit 1
}
Write-Host "Using asset: $($asset.name) from $($asset.browser_download_url)"

$tmp = Join-Path $env:TEMP "yt-dlp-dl"
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
$archive = Join-Path $tmp $asset.name

Write-Host "Downloading $($asset.name)..."
Invoke-WebRequest -Uri $asset.browser_download_url -OutFile $archive

if (Get-Command tar -ErrorAction SilentlyContinue) {
    tar -xJf $archive -C $tmp
    $bin = Get-ChildItem -Path $tmp -Recurse -Filter "yt-dlp" | Where-Object { -not $_.PSIsContainer } | Select-Object -First 1
    if ($bin) {
        Copy-Item $bin.FullName $Dest -Force
        Write-Host "Installed: $Dest"
        Write-Host "Size: $((Get-Item $Dest).Length) bytes"
        Write-Host "Bundled into APK via android/app/src/main/assets/bin/yt-dlp"
        exit 0
    }
}

Write-Host "Extract failed. Place yt-dlp binary manually at: $Dest"
exit 1
