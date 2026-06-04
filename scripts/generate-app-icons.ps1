# Regenerate Android launcher + public web icons from public/app-icon-source.png
$ErrorActionPreference = "Stop"
$Root = Split-Path $PSScriptRoot -Parent
$srcFull = Join-Path $Root "public\app-icon-source.png"
if (-not (Test-Path $srcFull)) {
    Write-Error "Missing $srcFull — place the master icon there first."
}

Add-Type -AssemblyName System.Drawing
function Resize-Png($inPath, $outPath, $size) {
    $bmp = [System.Drawing.Image]::FromFile($inPath)
    $out = New-Object System.Drawing.Bitmap $size, $size
    $g = [System.Drawing.Graphics]::FromImage($out)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.Clear([System.Drawing.Color]::White)
    $pad = [int]($size * 0.08)
    $inner = $size - 2 * $pad
    $g.DrawImage($bmp, $pad, $pad, $inner, $inner)
    $out.Save($outPath, [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose(); $out.Dispose(); $bmp.Dispose()
}

$res = Join-Path $Root "android\app\src\main\res"
$launcher = @{ "mipmap-mdpi" = 48; "mipmap-hdpi" = 72; "mipmap-xhdpi" = 96; "mipmap-xxhdpi" = 144; "mipmap-xxxhdpi" = 192 }
$foreground = @{ "mipmap-mdpi" = 108; "mipmap-hdpi" = 162; "mipmap-xhdpi" = 216; "mipmap-xxhdpi" = 324; "mipmap-xxxhdpi" = 432 }
foreach ($kv in $launcher.GetEnumerator()) {
    $dir = Join-Path $res $kv.Key
    Resize-Png $srcFull (Join-Path $dir "ic_launcher.png") $kv.Value
    Resize-Png $srcFull (Join-Path $dir "ic_launcher_round.png") $kv.Value
}
foreach ($kv in $foreground.GetEnumerator()) {
    $dir = Join-Path $res $kv.Key
    Resize-Png $srcFull (Join-Path $dir "ic_launcher_foreground.png") $kv.Value
}
Resize-Png $srcFull (Join-Path $Root "public\favicon.png") 64
Resize-Png $srcFull (Join-Path $Root "public\icon-192.png") 192
Resize-Png $srcFull (Join-Path $Root "public\icon-512.png") 512
Write-Host "Icons generated from app-icon-source.png"
