# NeonGrab — Universal Downloader (Mobile)

Lightweight Android-ready downloader UI based on the NeonGrab design. Paste any supported media or playlist URL — no platform names shown in the interface.

## Features

- **Single link** — one URL at a time
- **Batch / playlist** — expands playlist-style URLs into a queued batch
- **Active queue** — segmented progress, pause / resume / cancel
- **Thermal guard** — limits concurrent threads to reduce CPU/RAM load
- **Vault** — completed downloads list
- **Settings** — language (English default, Bengali optional), quality, Wi‑Fi-only, concurrency

## Development

```bash
cd "G:\IDM For Phone"
npm install
npm run dev
```

Open the Vite URL in a mobile viewport or browser dev tools.

## Download APK

Pre-built debug APK (install on Android):

**[Download NeonGrab-1.0.0.apk](https://github.com/Mostafijemon24/NeonGrab/releases/download/v1.0.0/NeonGrab-1.0.0.apk)** (~4 MB)

Repository: https://github.com/Mostafijemon24/NeonGrab

Enable “Install unknown apps” for your browser or file manager, then open the APK.

## Build APK from source

```bash
npm install
npm run build
npx cap add android   # first time only
npx cap sync
npx cap open android
```

Or assemble from CLI:

```bash
cd android
./gradlew assembleDebug
```

Output: `android/app/build/outputs/apk/debug/app-debug.apk`

### yt-dlp (Android native)

1. Install the ARM64 binary into assets:

   ```powershell
   cd "G:\IDM For Phone"
   .\scripts\fetch-ytdlp-android.ps1
   ```

2. Sync and run on device:

   ```powershell
   npm run cap:sync
   npm run android
   ```

The **YtDlp** Capacitor plugin (`android/.../ytdlp/`) probes URLs and runs downloads. Without the binary, the app falls back to simulated progress (web preview).

| Layer | Path |
|-------|------|
| Plugin (TS) | `src/plugins/yt-dlp/` |
| Bridge | `src/services/nativeYtDlp.ts` |
| Engine | `src/services/downloadEngine.ts` |
| Native | `android/.../ytdlp/YtDlpPlugin.java` |

## Stack

- React 19 + Vite 7 + Tailwind CSS 4
- Capacitor 7 (Clipboard, Preferences, Filesystem)
- English UI by default; Bengali via Settings → Language
