package com.neongrab.downloader.ytdlp;

import android.content.Context;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Downloads and extracts the yt-dlp engine pack on first launch (lean APK). */
final class EnginePackDownloader {

    interface Progress {
        void onProgress(int percent, String message);
    }

    private EnginePackDownloader() {}

    static boolean isInstalled(Context context) {
        Context app = context.getApplicationContext();
        if (EngineNativeLoader.apkBundledNativesPresent(app)) return true;
        try {
            if (!EngineAbi.isSupported(app)) return false;
            if (!markerMatchesDevice(app)) return false;
        } catch (Exception e) {
            return false;
        }
        File bin = EngineNativeLoader.getDownloadedBinDir(app);
        return markerFile(app).isFile() && EngineAbi.validateEngineBinDir(bin);
    }

    /** Drop cached engine when CPU/arch marker does not match this device. */
    static void reconcileEngineForDevice(Context app) {
        if (EngineNativeLoader.apkBundledNativesPresent(app)) return;
        File bin = EngineNativeLoader.getDownloadedBinDir(app);
        boolean markerOk;
        try {
            markerOk = markerMatchesDevice(app);
        } catch (Exception e) {
            markerOk = false;
        }
        boolean binsOk = EngineAbi.validateEngineBinDir(bin);
        if (!markerOk || !binsOk) {
            clearEngineInstall(app);
        }
    }

    static void clearEngineInstall(Context app) {
        deleteRecursive(EngineNativeLoader.getDownloadedBinDir(app));
        deleteRecursive(EngineNativeLoader.engineRoot(app));
        deleteRecursive(new File(app.getFilesDir(), "engine"));
        deleteRecursive(new File(app.getCodeCacheDir(), "neongrab_bin"));
        File legacyMarker = new File(app.getFilesDir(), "engine/.pack_version");
        legacyMarker.delete();
        EngineNativeLoader.reset();
        FfmpegBinaryHelper.reset();
    }

    static void clearWrongAbiInstall(Context app) {
        reconcileEngineForDevice(app);
    }

    private static boolean markerMatchesDevice(Context app) {
        File marker = markerFile(app);
        if (!marker.isFile()) return false;
        try {
            String expected = EnginePackConfig.packVersion(app);
            String actual = readMarkerText(marker);
            return expected.equals(actual);
        } catch (Exception e) {
            return false;
        }
    }

    private static String readMarkerText(File marker) throws Exception {
        try (FileInputStream in = new FileInputStream(marker)) {
            byte[] buf = new byte[64];
            int n = in.read(buf);
            if (n <= 0) return "";
            return new String(buf, 0, n).trim();
        }
    }

    static File markerFile(Context context) {
        Context app = context.getApplicationContext();
        File marker =
                new File(EngineNativeLoader.engineRoot(app), ".pack_version");
        if (marker.isFile()) return marker;
        File legacy = new File(app.getFilesDir(), "engine/.pack_version");
        if (legacy.isFile()) return legacy;
        return marker;
    }

    static void ensureDownloaded(Context context, Progress progress) throws Exception {
        Context app = context.getApplicationContext();
        clearWrongAbiInstall(app);
        if (isInstalled(app)) {
            progress.onProgress(40, "Engine pack already installed");
            return;
        }
        if (EngineNativeLoader.apkBundledNativesPresent(app)) {
            progress.onProgress(40, "Using bundled engine libraries");
            return;
        }
        EngineAbi.requireSupportedDevice(app);

        File engineRoot = EngineNativeLoader.engineRoot(app);
        File binDir = EngineNativeLoader.getDownloadedBinDir(app);
        File tmpZip = new File(engineRoot, "pack.download");
        File staging = new File(engineRoot, "pack_staging");

        deleteRecursive(staging);
        deleteRecursive(binDir);
        if (!engineRoot.exists() && !engineRoot.mkdirs()) {
            throw new Exception("Could not create engine directory");
        }
        if (!staging.mkdirs()) {
            throw new Exception("Could not create staging directory");
        }

        progress.onProgress(5, "Downloading engine (~47 MB)…");
        Exception downloadErr = null;
        try {
            downloadFile(EnginePackConfig.downloadUrl(app), tmpZip, progress);
        } catch (Exception e) {
            downloadErr = e;
            progress.onProgress(8, "Online engine unavailable, trying bundled pack…");
            if (!installFromAssets(app, tmpZip, progress)) {
                throw downloadErr;
            }
        }

        if (tmpZip.length() < EnginePackConfig.MIN_PACK_BYTES) {
            deleteRecursive(tmpZip);
            if (installFromAssets(app, tmpZip, progress)) {
                /* recovered from debug asset */
            } else {
                throw new Exception(
                        downloadErr != null
                                ? downloadErr.getMessage()
                                : "Engine download incomplete. Check internet and try again.");
            }
        }

        progress.onProgress(38, "Unpacking engine…");
        unzip(tmpZip, staging);
        tmpZip.delete();

        if (!binDir.exists() && !binDir.mkdirs()) {
            throw new Exception("Could not create engine bin directory");
        }
        moveSoFiles(staging, binDir);
        deleteRecursive(staging);

        if (!EngineNativeLoader.verifyBinDir(binDir)) {
            deleteRecursive(binDir);
            throw new Exception("Downloaded engine pack is invalid or incomplete");
        }

        writeMarker(app);
        EngineNativeLoader.makeExecutable(binDir);
        progress.onProgress(42, "Engine pack installed");
    }

    private static boolean installFromAssets(Context app, File dest, Progress progress)
            throws Exception {
        try (InputStream in = app.getAssets().open("engine-pack.zip");
                FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            long done = 0;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                done += n;
            }
            out.flush();
            progress.onProgress(20, "Bundled engine loaded (" + formatMb(done) + ")");
            return dest.length() >= EnginePackConfig.MIN_PACK_BYTES;
        } catch (Exception ignored) {
            if (dest.exists()) dest.delete();
            return false;
        }
    }

    private static void downloadFile(String urlStr, File dest, Progress progress)
            throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(120_000);
            conn.connect();

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new Exception("Engine download failed (HTTP " + code + ")");
            }

            long total = conn.getContentLengthLong();
            try (InputStream in = new BufferedInputStream(conn.getInputStream());
                    FileOutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[8192];
                long done = 0;
                int n;
                while ((n = in.read(buf)) >= 0) {
                    out.write(buf, 0, n);
                    done += n;
                    if (total > 0) {
                        int pct = 5 + (int) ((done * 33) / total);
                        progress.onProgress(
                                Math.min(37, pct),
                                "Downloading engine "
                                        + formatMb(done)
                                        + " / "
                                        + formatMb(total)
                                        + "…");
                    }
                }
                out.flush();
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String formatMb(long bytes) {
        return String.format("%.0f MB", bytes / (1024.0 * 1024.0));
    }

    private static void unzip(File zip, File destDir) throws Exception {
        byte[] buf = new byte[8192];
        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.contains("..")) continue;
                File out = new File(destDir, name);
                if (entry.isDirectory()) {
                    if (!out.mkdirs() && !out.isDirectory()) {
                        throw new Exception("Could not create " + name);
                    }
                } else {
                    File parent = out.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        throw new Exception("Could not create folder for " + name);
                    }
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        int n;
                        while ((n = zin.read(buf)) > 0) {
                            fos.write(buf, 0, n);
                        }
                    }
                    if (name.endsWith(".so")) {
                        out.setReadable(true, false);
                        out.setExecutable(true, false);
                    }
                }
                zin.closeEntry();
            }
        }
    }

    private static void moveSoFiles(File from, File to) throws Exception {
        File[] files = from.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                moveSoFiles(f, to);
                continue;
            }
            if (!f.getName().endsWith(".so")) continue;
            File dest = new File(to, f.getName());
            copyFile(f, dest);
            dest.setExecutable(true, false);
        }
    }

    private static void copyFile(File src, File dst) throws Exception {
        try (java.io.FileInputStream in = new java.io.FileInputStream(src);
                java.io.FileOutputStream out = new java.io.FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
        }
    }

    private static void writeMarker(Context app) throws Exception {
        File marker = markerFile(app);
        File parent = marker.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (FileOutputStream out = new FileOutputStream(marker)) {
            out.write(EnginePackConfig.packVersion(app).getBytes());
        }
    }

    static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) deleteRecursive(k);
            }
        }
        f.delete();
    }
}
