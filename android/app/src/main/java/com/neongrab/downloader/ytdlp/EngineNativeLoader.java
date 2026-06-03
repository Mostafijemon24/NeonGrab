package com.neongrab.downloader.ytdlp;

import android.content.Context;
import com.yausername.youtubedl_android.YoutubeDL;
import java.io.File;

/** Resolves native engine binaries from APK or first-run download; custom init for lean APK. */
final class EngineNativeLoader {

    private static volatile File resolvedBinDir;

    private EngineNativeLoader() {}

    static File getDownloadedBinDir(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), "engine/bin");
    }

    static boolean apkBundledNativesPresent(Context context) {
        File lib = new File(context.getApplicationInfo().nativeLibraryDir, "libpython.zip.so");
        return lib.isFile() && lib.length() > 512 * 1024;
    }

    static File resolveBinDir(Context context) {
        if (resolvedBinDir != null && verifyBinDir(resolvedBinDir)) {
            return resolvedBinDir;
        }
        Context app = context.getApplicationContext();
        if (apkBundledNativesPresent(app)) {
            resolvedBinDir = new File(app.getApplicationInfo().nativeLibraryDir);
            return resolvedBinDir;
        }
        File downloaded = getDownloadedBinDir(app);
        if (verifyBinDir(downloaded)) {
            resolvedBinDir = downloaded;
            return resolvedBinDir;
        }
        return null;
    }

    static boolean verifyBinDir(File binDir) {
        if (binDir == null || !binDir.isDirectory()) return false;
        for (String name : EnginePackConfig.REQUIRED_LIBS) {
            File f = new File(binDir, name);
            if (!f.isFile()) return false;
            if (name.endsWith(".zip.so") && f.length() < 512 * 1024) return false;
            if (!name.endsWith(".zip.so") && f.length() < 1024) return false;
        }
        return true;
    }

    static void reset() {
        resolvedBinDir = null;
    }

    /** Initialize youtubedl-android using APK or downloaded native libs. */
    static void initEngine(Context context) throws Exception {
        Context app = context.getApplicationContext();
        File binDir = resolveBinDir(app);
        if (binDir == null || !verifyBinDir(binDir)) {
            throw new Exception("Engine native libraries not found");
        }

        File baseDir = new File(app.getNoBackupFilesDir(), "youtubedl-android");
        if (!baseDir.exists()) baseDir.mkdir();
        File packagesDir = new File(baseDir, "packages");
        File pythonDir = new File(packagesDir, "python");
        File ffmpegDir = new File(packagesDir, "ffmpeg");
        File ytdlpDir = new File(baseDir, YoutubeDL.ytdlpDirName);

        Object ytdlp = YtDlpReflection.youtubeDlInstance();
        Class<?> ytdlpClass = ytdlp.getClass();

        YtDlpReflection.setField(ytdlp, "initialized", false);
        YtDlpReflection.setField(ytdlp, "binDir", binDir);
        YtDlpReflection.setField(ytdlp, "pythonPath", new File(binDir, "libpython.so"));
        YtDlpReflection.setField(ytdlp, "ffmpegPath", new File(binDir, "libffmpeg.so"));
        YtDlpReflection.setField(ytdlp, "quickJsPath", new File(binDir, "libqjs.so"));
        YtDlpReflection.setField(ytdlp, "ytdlpPath", new File(ytdlpDir, YoutubeDL.ytdlpBin));

        String ldPath =
                pythonDir.getAbsolutePath()
                        + "/usr/lib:"
                        + ffmpegDir.getAbsolutePath()
                        + "/usr/lib";
        YtDlpReflection.setField(ytdlp, "ENV_LD_LIBRARY_PATH", ldPath);
        YtDlpReflection.setField(
                ytdlp,
                "ENV_SSL_CERT_FILE",
                pythonDir.getAbsolutePath() + "/usr/etc/tls/cert.pem");
        YtDlpReflection.setField(
                ytdlp, "ENV_PYTHONHOME", pythonDir.getAbsolutePath() + "/usr");
        YtDlpReflection.setField(ytdlp, "TMPDIR", app.getCacheDir().getAbsolutePath());

        YtDlpReflection.invoke(
                ytdlp, "initPython", new Class[] {Context.class, File.class}, app, pythonDir);
        YtDlpReflection.invoke(
                ytdlp, "init_ytdlp", new Class[] {Context.class, File.class}, app, ytdlpDir);
        YtDlpReflection.setField(ytdlp, "initialized", true);

        initFfmpegPackages(app, binDir, ffmpegDir);
        resolvedBinDir = binDir;
    }

    private static void initFfmpegPackages(Context app, File binDir, File ffmpegDir)
            throws Exception {
        Object ffmpeg = YtDlpReflection.ffmpegInstance();
        Class<?> cls = ffmpeg.getClass();
        YtDlpReflection.setField(ffmpeg, "initialized", false);
        YtDlpReflection.setField(ffmpeg, "binDir", binDir);
        YtDlpReflection.invoke(
                ffmpeg,
                "initFFmpeg",
                new Class[] {Context.class, File.class},
                app,
                ffmpegDir);
        YtDlpReflection.setField(ffmpeg, "initialized", true);
    }
}
