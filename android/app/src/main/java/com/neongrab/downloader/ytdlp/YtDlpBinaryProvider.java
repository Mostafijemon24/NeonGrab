package com.neongrab.downloader.ytdlp;

import android.content.Context;
import android.content.res.AssetManager;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Locates yt-dlp binary: app files dir first, then copies from assets/bin/yt-dlp if present.
 */
public final class YtDlpBinaryProvider {

    private static final String ASSET_PATH = "bin/yt-dlp";
    private static final String BIN_DIR = "bin";
    private static final String BIN_NAME = "yt-dlp";

    private YtDlpBinaryProvider() {}

    public static File getBinaryFile(Context context) {
        return new File(context.getFilesDir(), BIN_DIR + File.separator + BIN_NAME);
    }

    public static boolean isInstalled(Context context) {
        File bin = getBinaryFile(context);
        return bin.exists() && bin.canExecute() && bin.length() > 0;
    }

    public static String getVersion(Context context) {
        if (!isInstalled(context)) return null;
        try {
            Process p =
                    new ProcessBuilder(getBinaryFile(context).getAbsolutePath(), "--version")
                            .redirectErrorStream(true)
                            .start();
            String line = readStream(p.getInputStream()).trim().split("\n")[0];
            p.waitFor();
            if (p.exitValue() != 0) return null;
            return line.isEmpty() ? null : line;
        } catch (Exception e) {
            return null;
        }
    }

    private static String readStream(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = in.read(chunk)) > 0) {
            buf.write(chunk, 0, n);
        }
        return buf.toString("UTF-8");
    }

    /** Copy assets/bin/yt-dlp into internal storage and mark executable. */
    public static InstallResult installFromAssets(Context context) {
        AssetManager assets = context.getAssets();
        try (InputStream in = assets.open(ASSET_PATH)) {
            File dir = new File(context.getFilesDir(), BIN_DIR);
            if (!dir.exists() && !dir.mkdirs()) {
                return InstallResult.fail("Could not create bin directory");
            }
            File outFile = getBinaryFile(context);
            try (OutputStream out = new FileOutputStream(outFile)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
            //noinspection ResultOfMethodCallIgnored
            outFile.setExecutable(true, false);
            if (!outFile.canExecute()) {
                return InstallResult.fail("Binary is not executable after install");
            }
            return InstallResult.ok(outFile.getAbsolutePath());
        } catch (Exception e) {
            return InstallResult.fail(
                    "Place a ARM64 yt-dlp binary at assets/bin/yt-dlp — " + e.getMessage());
        }
    }

    public static final class InstallResult {
        public final boolean ok;
        public final String message;
        public final String path;

        private InstallResult(boolean ok, String message, String path) {
            this.ok = ok;
            this.message = message;
            this.path = path;
        }

        static InstallResult ok(String path) {
            return new InstallResult(true, null, path);
        }

        static InstallResult fail(String message) {
            return new InstallResult(false, message, null);
        }
    }
}
