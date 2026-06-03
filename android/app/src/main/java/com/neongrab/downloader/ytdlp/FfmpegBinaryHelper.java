package com.neongrab.downloader.ytdlp;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.lang.reflect.Field;

/**
 * Prepares ffmpeg/ffprobe/quickjs wrappers for yt-dlp. Binaries live in {@code code_cache} and
 * are launched via {@code linker64} when not in the APK {@code nativeLibraryDir}.
 */
final class FfmpegBinaryHelper {

    private static final String BIN_DIR = "neongrab_bin";
    /** Bump when wrapper script format changes so existing installs regenerate. */
    private static final int WRAPPER_REVISION = 2;

    private static volatile File toolBinDir;
    private static volatile int appliedRevision = -1;

    private FfmpegBinaryHelper() {}

    static void prepare(Context context) throws Exception {
        if (toolBinDir != null && appliedRevision == WRAPPER_REVISION) return;
        Context app = context.getApplicationContext();
        File engineBin = EngineNativeLoader.resolveBinDir(context);
        if (engineBin == null) {
            throw new Exception("Engine native libraries not available");
        }

        File srcFfmpeg = new File(engineBin, "libffmpeg.so");
        File srcFfprobe = new File(engineBin, "libffprobe.so");
        File srcQjs = new File(engineBin, "libqjs.so");
        if (!srcFfmpeg.isFile() || srcFfmpeg.length() < 1024) {
            throw new Exception("ffmpeg library missing from engine pack");
        }

        File binDir = new File(app.getCodeCacheDir(), BIN_DIR);
        if (!binDir.exists() && !binDir.mkdirs()) {
            throw new Exception("Could not create tool bin directory");
        }

        File ffmpegBin = new File(binDir, "ffmpeg.bin");
        File ffprobeBin = new File(binDir, "ffprobe.bin");
        copyIfChanged(srcFfmpeg, ffmpegBin);
        chmod(ffmpegBin);
        if (srcFfprobe.isFile()) {
            copyIfChanged(srcFfprobe, ffprobeBin);
            chmod(ffprobeBin);
        }

        String ldLibraryPath = buildLdLibraryPath(app, engineBin);
        writeWrapper(new File(binDir, "ffmpeg"), ffmpegBin, ldLibraryPath, app);
        if (ffprobeBin.isFile()) {
            writeWrapper(new File(binDir, "ffprobe"), ffprobeBin, ldLibraryPath, app);
        }
        if (srcQjs.isFile()) {
            writeWrapper(new File(binDir, "quickjs"), srcQjs, ldLibraryPath, app);
        }

        deleteLegacyDir(new File(app.getFilesDir(), BIN_DIR));
        patchYoutubeDlFfmpegPath(binDir);
        toolBinDir = binDir;
        appliedRevision = WRAPPER_REVISION;
    }

    static File binDirectory(Context context) throws Exception {
        prepare(context);
        return toolBinDir;
    }

    static File quickJsExecutable(Context context) throws Exception {
        prepare(context);
        File qjs = new File(toolBinDir, "quickjs");
        if (!qjs.isFile()) {
            throw new Exception("QuickJS wrapper missing");
        }
        return qjs;
    }

    /** Library resets ffmpeg path on init; re-apply before every yt-dlp run. */
    static void ensurePatched(Context context) throws Exception {
        prepare(context);
        patchYoutubeDlFfmpegPath(toolBinDir);
    }

    static void reset() {
        appliedRevision = -1;
        toolBinDir = null;
    }

    private static String buildLdLibraryPath(Context app, File engineBin) {
        File base = new File(app.getNoBackupFilesDir(), "youtubedl-android");
        File packages = new File(base, "packages");
        File ffmpegLib = new File(new File(packages, "ffmpeg"), "usr/lib");
        File pythonLib = new File(new File(packages, "python"), "usr/lib");
        StringBuilder sb = new StringBuilder();
        appendPath(sb, ffmpegLib);
        appendPath(sb, pythonLib);
        appendPath(sb, engineBin);
        return sb.toString();
    }

    private static void appendPath(StringBuilder sb, File dir) {
        if (dir.isDirectory()) {
            if (sb.length() > 0) sb.append(':');
            sb.append(dir.getAbsolutePath());
        }
    }

    private static void writeWrapper(File wrapper, File binary, String ldLibraryPath, Context app)
            throws Exception {
        boolean linker = EngineNativeLoader.needsLinkerExec(binary, app);
        try (FileWriter w = new FileWriter(wrapper, false)) {
            w.write("#!/system/bin/sh\n");
            w.write("export LD_LIBRARY_PATH=\"" + ldLibraryPath + "\"\n");
            if (linker) {
                w.write(
                        "exec "
                                + EngineNativeLoader.linkerPath()
                                + " \""
                                + binary.getAbsolutePath()
                                + "\" \"$@\"\n");
            } else {
                w.write("exec \"" + binary.getAbsolutePath() + "\" \"$@\"\n");
            }
        }
        chmod(wrapper);
    }

    private static void chmod(File file) {
        if (file == null || !file.isFile()) return;
        file.setReadable(true, false);
        file.setExecutable(true, false);
        try {
            Os.chmod(file.getAbsolutePath(), 0755);
        } catch (ErrnoException ignored) {
            /* ignore */
        }
    }

    private static void copyIfChanged(File src, File dst) throws Exception {
        if (dst.isFile()
                && dst.length() == src.length()
                && dst.lastModified() >= src.lastModified()) {
            return;
        }
        try (FileInputStream in = new FileInputStream(src);
                FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
        }
    }

    private static void deleteLegacyDir(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] kids = dir.listFiles();
        if (kids != null) {
            for (File k : kids) {
                if (k.isDirectory()) deleteLegacyDir(k);
                else k.delete();
            }
        }
        dir.delete();
    }

    /** yt-dlp accepts a directory containing ffmpeg/ffprobe or the binary path. */
    private static void patchYoutubeDlFfmpegPath(File binDir) throws Exception {
        Class<?> ytdlpClass = Class.forName("com.yausername.youtubedl_android.YoutubeDL");
        Object instance = ytdlpClass.getMethod("getInstance").invoke(null);
        Field ffmpegPathField = null;
        for (Field field : ytdlpClass.getDeclaredFields()) {
            if (File.class.equals(field.getType())) {
                field.setAccessible(true);
                Object current = field.get(instance);
                if (current == null || field.getName().toLowerCase().contains("ffmpeg")) {
                    ffmpegPathField = field;
                    break;
                }
            }
        }
        if (ffmpegPathField == null) {
            ffmpegPathField = ytdlpClass.getDeclaredField("ffmpegPath");
            ffmpegPathField.setAccessible(true);
        }
        ffmpegPathField.set(instance, binDir);
    }
}
