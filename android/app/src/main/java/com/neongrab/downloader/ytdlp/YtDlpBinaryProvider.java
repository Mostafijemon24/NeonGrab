package com.neongrab.downloader.ytdlp;

import android.content.Context;
import android.content.res.AssetManager;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Locates yt-dlp binary: internal files dir, assets, or download from GitHub releases.
 */
public final class YtDlpBinaryProvider {

    private static final String ASSET_PATH = "bin/yt-dlp";
    private static final String BIN_DIR = "bin";
    private static final String BIN_NAME = "yt-dlp";
    private static final String DOWNLOAD_URL =
            "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp";

    private YtDlpBinaryProvider() {}

    public static File getBinaryFile(Context context) {
        return new File(context.getFilesDir(), BIN_DIR + File.separator + BIN_NAME);
    }

    public static boolean isInstalled(Context context) {
        File bin = getBinaryFile(context);
        return bin.exists() && bin.length() > 1024 * 1024 && bin.canExecute();
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
            return writeBinary(in, getBinaryFile(context));
        } catch (Exception e) {
            return InstallResult.fail("Asset binary missing: " + e.getMessage());
        }
    }

    /** Download latest yt-dlp executable (Linux ARM64 works on Android). */
    public static InstallResult installFromNetwork(Context context) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(DOWNLOAD_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(120_000);
            conn.setInstanceFollowRedirects(true);
            conn.connect();
            if (conn.getResponseCode() >= 400) {
                return InstallResult.fail("Download failed: HTTP " + conn.getResponseCode());
            }
            try (InputStream in = conn.getInputStream()) {
                return writeBinary(in, getBinaryFile(context));
            }
        } catch (Exception e) {
            return InstallResult.fail("Could not download yt-dlp: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static InstallResult ensureInstalled(Context context) {
        if (isInstalled(context)) {
            return InstallResult.ok(getBinaryFile(context).getAbsolutePath());
        }
        InstallResult assets = installFromAssets(context);
        if (assets.ok && isInstalled(context)) return assets;
        InstallResult network = installFromNetwork(context);
        if (network.ok && isInstalled(context)) return network;
        return InstallResult.fail(
                network.message != null
                        ? network.message
                        : (assets.message != null ? assets.message : "yt-dlp not available"));
    }

    private static InstallResult writeBinary(InputStream in, File outFile) throws Exception {
        File dir = outFile.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            return InstallResult.fail("Could not create bin directory");
        }
        try (OutputStream out = new FileOutputStream(outFile)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        outFile.setExecutable(true, false);
        if (!outFile.canExecute() || outFile.length() < 1024 * 1024) {
            //noinspection ResultOfMethodCallIgnored
            outFile.delete();
            return InstallResult.fail("Downloaded binary is invalid or too small");
        }
        return InstallResult.ok(outFile.getAbsolutePath());
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
