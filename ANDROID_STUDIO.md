# Run NeonGrab in Android Studio

## Prerequisites

1. **Android Studio** (Ladybug or newer) with Android SDK 35
2. **JDK 17+** (bundled with Android Studio: `jbr`)
3. **Node.js 20+** and npm

## One-time setup

```powershell
cd "G:\IDM For Phone"
npm install
```

Create `android/local.properties` (Android Studio usually creates this automatically):

```properties
sdk.dir=C\:\\Users\\YOUR_USER\\AppData\\Local\\Android\\Sdk
```

Replace `YOUR_USER` with your Windows username.

## Sync web UI into Android

Before opening or running from Android Studio:

```powershell
cd "G:\IDM For Phone"
npm run build
npx cap sync android
```

Or use the npm shortcut:

```powershell
npm run cap:sync
```

## Open in Android Studio

```powershell
npm run android
```

Or: **File → Open** → select the `android` folder inside this project.

Wait for **Gradle Sync** to finish (first sync downloads `youtubedl-android` + ffmpeg, ~50–80 MB).

## Run on device or emulator

1. Connect a phone with **USB debugging** enabled, or start an **Android 8+** emulator (API 26+).
2. Select the **app** run configuration.
3. Click **Run** (green play).

First launch may take 1–2 minutes while the download engine initializes.

## Environment variables (if Gradle fails)

In PowerShell before opening Studio:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
```

## Build APK from terminal

**Lean release (~4–8 MB APK + first-run engine download):**

```powershell
cd "G:\IDM For Phone\android"
.\gradlew.bat assembleRelease
```

Output: `android/app/build/outputs/apk/release/app-release-unsigned.apk`

Native engine libs are **not** bundled; first launch downloads `neongrab-engine-arm64-v0.18.1.zip` from GitHub (~47 MB). Pack that zip for hosting:

```powershell
cd "G:\IDM For Phone"
.\scripts\pack-engine-arm64.ps1
# Upload releases/neongrab-engine-arm64-v0.18.1.zip to GitHub release engine-0.18.1
```

**Debug:**

```powershell
.\gradlew.bat assembleDebug
```

Output: `android/app/build/outputs/apk/debug/app-debug.apk`

For x86 emulators, use an **ARM64 system image** in Android Studio.

## Troubleshooting

| Issue | Fix |
|--------|-----|
| Gradle sync failed | Check internet; File → Invalidate Caches → Restart |
| SDK not found | Set `sdk.dir` in `android/local.properties` |
| White screen on launch | Run `npm run cap:sync` again |
| Downloads fail on first try | Open **Settings**, wait until yt-dlp shows **ready**, pick a download folder |
