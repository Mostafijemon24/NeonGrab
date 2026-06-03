package com.neongrab.downloader.ytdlp;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.lang.reflect.Field;

/**
 * Prepares ffmpeg/ffprobe for yt-dlp on Android. Copies native libs into app storage, wraps them
 * with shell scripts that set LD_LIBRARY_PATH, and points youtubedl-android at the wrapper dir.
 */
final class FfmpegBinaryHelper {

    private static final String BIN_DIR = "neongrab_bin";
    private static volatile File ffmpegBinDir;
    private static volatile boolean prepared;

    private FfmpegBinaryHelper() {}

    static void prepare(Context context) throws Exception {
        if (prepared && ffmpegBinDir != null) return;
        Context app = context.getApplicationContext();
        File nativeLib = EngineNativeLoader.resolveBinDir(context);
        if (nativeLib == null) {
            throw new Exception("Engine native libraries not available");
        }

        File srcFfmpeg = new File(nativeLib, "libffmpeg.so");
        File srcFfprobe = new File(nativeLib, "libffprobe.so");
        if (!srcFfmpeg.isFile() || srcFfmpeg.length() < 1024) {
            throw new Exception("ffmpeg library missing from engine pack");
        }

        File binDir = new File(app.getFilesDir(), BIN_DIR);
        if (!binDir.exists() && !binDir.mkdirs()) {
            throw new Exception("Could not create ffmpeg bin directory");
        }

        File ffmpegBin = new File(binDir, "ffmpeg.bin");
        File ffprobeBin = new File(binDir, "ffprobe.bin");
        copyIfChanged(srcFfmpeg, ffmpegBin);
        ffmpegBin.setExecutable(true, false);
        ffmpegBin.setReadable(true, false);
        if (srcFfprobe.isFile()) {
            copyIfChanged(srcFfprobe, ffprobeBin);
            ffprobeBin.setExecutable(true, false);
            ffprobeBin.setReadable(true, false);
        }

        String ldLibraryPath = buildLdLibraryPath(app, nativeLib);
        writeWrapper(new File(binDir, "ffmpeg"), ffmpegBin, ldLibraryPath);
        if (ffprobeBin.isFile()) {
            writeWrapper(new File(binDir, "ffprobe"), ffprobeBin, ldLibraryPath);
        }

        patchYoutubeDlFfmpegPath(binDir);
        ffmpegBinDir = binDir;
        prepared = true;
    }

    static File binDirectory(Context context) throws Exception {
        prepare(context);
        return ffmpegBinDir;
    }

    /** Library resets ffmpeg path on init; re-apply before every yt-dlp run. */
    static void ensurePatched(Context context) throws Exception {
        prepare(context);
        patchYoutubeDlFfmpegPath(ffmpegBinDir);
    }

    static void reset() {
        prepared = false;
        ffmpegBinDir = null;
    }

    private static String buildLdLibraryPath(Context app, File nativeLib) {
        File base = new File(app.getNoBackupFilesDir(), "youtubedl-android");
        File packages = new File(base, "packages");
        File ffmpegLib = new File(new File(packages, "ffmpeg"), "usr/lib");
        File pythonLib = new File(new File(packages, "python"), "usr/lib");
        StringBuilder sb = new StringBuilder();
        appendPath(sb, ffmpegLib);
        appendPath(sb, pythonLib);
        appendPath(sb, nativeLib);
        return sb.toString();
    }

    private static void appendPath(StringBuilder sb, File dir) {
        if (dir.isDirectory()) {
            if (sb.length() > 0) sb.append(':');
            sb.append(dir.getAbsolutePath());
        }
    }

    private static void writeWrapper(File wrapper, File binary, String ldLibraryPath)
            throws Exception {
        try (FileWriter w = new FileWriter(wrapper, false)) {
            w.write("#!/system/bin/sh\n");
            w.write("export LD_LIBRARY_PATH=\"" + ldLibraryPath + "\"\n");
            w.write("exec \"" + binary.getAbsolutePath() + "\" \"$@\"\n");
        }
        wrapper.setExecutable(true, false);
        wrapper.setReadable(true, false);
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
