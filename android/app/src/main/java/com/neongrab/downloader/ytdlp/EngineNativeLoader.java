package com.neongrab.downloader.ytdlp;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import com.yausername.youtubedl_android.YoutubeDL;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
/** Resolves native engine binaries from APK or first-run download; custom init for lean APK. */
final class EngineNativeLoader {

    private static volatile File resolvedBinDir;

    private EngineNativeLoader() {}

    static File getDownloadedBinDir(Context context) {
        Context app = context.getApplicationContext();
        File cacheBin = new File(app.getCodeCacheDir(), "engine/bin");
        if (verifyBinDir(cacheBin)) return cacheBin;
        File legacy = new File(app.getFilesDir(), "engine/bin");
        if (verifyBinDir(legacy)) {
            try {
                migrateBinDir(legacy, cacheBin);
            } catch (Exception ignored) {
                return legacy;
            }
            return cacheBin;
        }
        return cacheBin;
    }

    static File engineRoot(Context context) {
        return new File(context.getApplicationContext().getCodeCacheDir(), "engine");
    }

    static boolean useLinker(Context context, File binDir) {
        if (apkBundledNativesPresent(context)) return false;
        File nativeLib = new File(context.getApplicationInfo().nativeLibraryDir);
        return !binDir.getAbsolutePath().equals(nativeLib.getAbsolutePath());
    }

    static String linkerPath() {
        if (new File("/system/bin/linker64").exists()) return "/system/bin/linker64";
        return "/system/bin/linker";
    }

    /** True when an ELF must be started via {@code linker64} (not under APK nativeLibraryDir). */
    static boolean needsLinkerExec(File elf, Context context) {
        if (elf == null) return true;
        String path = elf.getAbsolutePath();
        File nativeLib = new File(context.getApplicationInfo().nativeLibraryDir);
        String prefix = nativeLib.getAbsolutePath() + File.separator;
        return !path.startsWith(prefix);
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
        makeExecutable(binDir);
        resolvedBinDir = binDir;
    }

    static void makeExecutable(File binDir) {
        if (binDir == null || !binDir.isDirectory()) return;
        File[] files = binDir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (!f.isFile()) continue;
            f.setReadable(true, false);
            f.setExecutable(true, false);
            try {
                Os.chmod(f.getAbsolutePath(), 0755);
            } catch (ErrnoException ignored) {
                /* ignore */
            }
        }
    }

    private static void migrateBinDir(File from, File to) throws Exception {
        if (!to.exists() && !to.mkdirs()) {
            throw new Exception("Could not migrate engine to code cache");
        }
        File[] files = from.listFiles();
        if (files != null) {
            for (File f : files) {
                if (!f.isFile()) continue;
                copyFile(f, new File(to, f.getName()));
            }
        }
        makeExecutable(to);
    }

    private static void copyFile(File src, File dst) throws Exception {
        try (FileInputStream in = new FileInputStream(src);
                FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
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
