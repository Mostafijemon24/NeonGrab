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

```powershell
cd "G:\IDM For Phone\android"
.\gradlew.bat assembleDebug
```

APK path: `android/app/build/outputs/apk/debug/app-debug.apk`

## Troubleshooting

| Issue | Fix |
|--------|-----|
| Gradle sync failed | Check internet; File → Invalidate Caches → Restart |
| SDK not found | Set `sdk.dir` in `android/local.properties` |
| White screen on launch | Run `npm run cap:sync` again |
| Downloads fail on first try | Open **Settings**, wait until yt-dlp shows **ready**, pick a download folder |
