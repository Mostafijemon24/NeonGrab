package com.neongrab.downloader.ytdlp;

import android.content.Context;

/** Engine availability via youtubedl-android (Python + yt-dlp + ffmpeg bundled). */
public final class YtDlpBinaryProvider {

    private YtDlpBinaryProvider() {}

    public static boolean isInstalled(Context context) {
        return YtDlpEngineHelper.isReady(context);
    }

    public static String getVersion(Context context) {
        return YtDlpEngineHelper.getVersion(context);
    }

    public static InstallResult installFromAssets(Context context) {
        try {
            YtDlpEngineHelper.ensureReady(context, 120);
            return InstallResult.ok("bundled");
        } catch (Exception e) {
            return InstallResult.fail(e.getMessage());
        }
    }

    public static InstallResult installFromNetwork(Context context) {
        return installFromAssets(context);
    }

    public static InstallResult ensureInstalled(Context context) {
        try {
            YtDlpEngineHelper.ensureReady(context, 300);
            String version = getVersion(context);
            return InstallResult.ok(version != null ? version : "ready");
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg == null || msg.isEmpty()) msg = YtDlpEngineHelper.getLastError();
            if (msg == null || msg.isEmpty()) msg = "Download engine setup failed";
            return InstallResult.fail(msg);
        }
    }

    public static InstallResult retryInstall(Context context) {
        try {
            YtDlpEngineHelper.retrySetup(context, 300);
            String version = getVersion(context);
            return InstallResult.ok(version != null ? version : "ready");
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg == null || msg.isEmpty()) msg = YtDlpEngineHelper.getLastError();
            if (msg == null || msg.isEmpty()) msg = "Download engine setup failed";
            return InstallResult.fail(msg);
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
