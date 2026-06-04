# Downloads latest yt-dlp release and extracts ARM64 binary for Android assets.
$ErrorActionPreference = "Stop"
$Root = Split-Path $PSScriptRoot -Parent
$AssetsBin = Join-Path $Root "android\app\src\main\assets\bin"
$Dest = Join-Path $AssetsBin "yt-dlp"

New-Item -ItemType Directory -Force -Path $AssetsBin | Out-Null

$release = Invoke-RestMethod -Uri "https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest"
$asset = $release.assets | Where-Object { $_.name -match "yt-dlp" -and $_.name -match "\.tar\.xz" } | Select-Object -First 1
if (-not $asset) {
    Write-Host "No tar.xz asset found. Download manually from $($release.html_url)"
    exit 1
}

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
        exit 0
    }
}

Write-Host "Extract failed. Place yt-dlp binary manually at: $Dest"
exit 1
