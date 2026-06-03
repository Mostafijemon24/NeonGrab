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

    private static final String PERSISTENT_DIR = "neongrab_engine";

    private static volatile File resolvedBinDir;

    private EngineNativeLoader() {}

    static File persistentRoot(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), PERSISTENT_DIR);
    }

    static File persistentBinDir(Context context) {
        return new File(persistentRoot(context), "bin");
    }

    /** Exec copy under filesDir — code_cache is cleared on some devices and blocks linker exec. */
    static File execBinDir(Context context) {
        return new File(persistentRoot(context), "exec_bin");
    }

    static File engineRoot(Context context) {
        return persistentRoot(context);
    }

    static File getDownloadedBinDir(Context context) {
        Context app = context.getApplicationContext();
        try {
            return ensureExecBinDir(app);
        } catch (Exception e) {
            File exec = execBinDir(app);
            if (verifyBinDir(exec)) return exec;
            File persistent = persistentBinDir(app);
            if (verifyBinDir(persistent)) return persistent;
            return exec;
        }
    }

    /** Copy persistent engine libs into code_cache for linker64 execution. */
    static File ensureExecBinDir(Context app) throws Exception {
        File persistent = persistentBinDir(app);
        File exec = execBinDir(app);

        if (verifyBinDir(persistent)) {
            if (!verifyBinDir(exec) || execNeedsRefresh(persistent, exec)) {
                syncBinDir(persistent, exec);
            }
            makeExecutable(exec);
            return exec;
        }

        File legacyCache = exec;
        if (verifyBinDir(legacyCache)) {
            syncBinDir(legacyCache, persistent);
            makeExecutable(persistent);
            return legacyCache;
        }

        File legacyFiles = new File(app.getFilesDir(), "engine/bin");
        if (verifyBinDir(legacyFiles)) {
            syncBinDir(legacyFiles, persistent);
            syncBinDir(legacyFiles, exec);
            makeExecutable(exec);
            return exec;
        }

        if (!exec.exists()) exec.mkdirs();
        return exec;
    }

    static void syncBinDir(File from, File to) throws Exception {
        if (!from.isDirectory()) {
            throw new Exception("Engine source folder missing");
        }
        if (!to.exists() && !to.mkdirs()) {
            throw new Exception("Could not create engine exec folder");
        }
        for (String name : EnginePackConfig.REQUIRED_LIBS) {
            File src = new File(from, name);
            if (!src.isFile()) continue;
            copyFile(src, new File(to, name));
        }
        makeExecutable(to);
    }

    private static boolean execNeedsRefresh(File src, File dst) {
        for (String name : EnginePackConfig.REQUIRED_LIBS) {
            File s = new File(src, name);
            File d = new File(dst, name);
            if (!s.isFile()) continue;
            if (!d.isFile() || d.length() != s.length()) return true;
        }
        return false;
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

    static void copyFile(File src, File dst) throws Exception {
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
