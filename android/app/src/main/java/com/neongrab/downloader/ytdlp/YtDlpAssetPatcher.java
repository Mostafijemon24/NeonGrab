package com.neongrab.downloader.ytdlp;

import android.content.Context;
import com.yausername.youtubedl_android.YoutubeDL;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** Replaces bundled yt-dlp script with a newer copy from assets when present. */
final class YtDlpAssetPatcher {

    private static final String ASSET_PATH = "bin/yt-dlp";

    private YtDlpAssetPatcher() {}

    static void applyBundledScriptIfPresent(Context app) {
        Context ctx = app.getApplicationContext();
        try (InputStream in = ctx.getAssets().open(ASSET_PATH)) {
            File ytdlpDir = new File(ctx.getNoBackupFilesDir(), YoutubeDL.ytdlpDirName);
            if (!ytdlpDir.exists() && !ytdlpDir.mkdirs()) {
                return;
            }
            File dest = new File(ytdlpDir, YoutubeDL.ytdlpBin);
            File tmp = new File(ytdlpDir, YoutubeDL.ytdlpBin + ".new");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
            if (tmp.length() < 64 * 1024) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return;
            }
            //noinspection ResultOfMethodCallIgnored
            dest.delete();
            if (!tmp.renameTo(dest)) {
                copyFile(tmp, dest);
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (Exception ignored) {
            /* assets/bin/yt-dlp optional */
        }
    }

    private static void copyFile(File src, File dest) throws Exception {
        try (InputStream in = new java.io.FileInputStream(src);
                FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }
}
