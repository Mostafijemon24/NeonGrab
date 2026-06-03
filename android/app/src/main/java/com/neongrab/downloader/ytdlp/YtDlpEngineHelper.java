package com.neongrab.downloader.ytdlp;

import android.content.Context;
import android.os.Build;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.YoutubeDLResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import kotlin.Unit;

/** Wraps youtubedl-android (bundled Python + yt-dlp + ffmpeg) for real Android execution. */
public final class YtDlpEngineHelper {

    private static final Object LOCK = new Object();
    private static volatile boolean initialized;
    private static volatile boolean updateAttempted;
    private static volatile String lastError;

    private YtDlpEngineHelper() {}

    public static void initAsync(Context context) {
        Context app = context.getApplicationContext();
        new Thread(
                        () -> {
                            try {
                                ensureReady(app, 180);
                            } catch (Exception e) {
                                lastError = e.getMessage();
                            }
                        },
                        "ytdlp-init")
                .start();
    }

    public static boolean isReady(Context context) {
        if (!initialized) return false;
        try {
            String v = YoutubeDL.getInstance().version(context.getApplicationContext());
            return v != null && !v.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    public static String getVersion(Context context) {
        try {
            return YoutubeDL.getInstance().version(context.getApplicationContext());
        } catch (Exception e) {
            return null;
        }
    }

    public static String getLastError() {
        return lastError;
    }

    public static boolean ensureReady(Context context, int timeoutSeconds) throws Exception {
        Context app = context.getApplicationContext();
        synchronized (LOCK) {
            if (!initialized) {
                YoutubeDL.getInstance().init(app);
                initialized = true;
            }
            if (!updateAttempted) {
                updateAttempted = true;
                updateYoutubeDl(app);
            }
        }
        if (!isReady(app)) {
            throw new Exception(
                    lastError != null ? lastError : "Download engine is not ready yet");
        }
        return true;
    }

    private static void updateYoutubeDl(Context app) throws Exception {
        try {
            YoutubeDL.UpdateStatus status =
                    YoutubeDL.getInstance()
                            .updateYoutubeDL(
                                    app, YoutubeDL.UpdateChannel.STABLE.INSTANCE);
            if (status == YoutubeDL.UpdateStatus.DONE
                    || status == YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE) {
                return;
            }
            lastError = "Engine update status: " + status;
        } catch (Exception e) {
            lastError = e.getMessage();
            if (!isReady(app)) throw e;
        }
    }

    public static String runJsonProbe(Context context, String url, boolean flatPlaylist)
            throws Exception {
        ensureReady(context, 120);
        YoutubeDLRequest request = new YoutubeDLRequest(url);
        request.addOption("-J");
        request.addOption("--no-warnings");
        request.addOption("--no-cache-dir");
        addYoutubeCompatArgs(request);
        if (flatPlaylist) {
            request.addOption("--flat-playlist");
        } else {
            request.addOption("--no-playlist");
        }

        YoutubeDLResponse response =
                YoutubeDL.getInstance().execute(request, "probe_" + Math.abs(url.hashCode()));
        if (response.getExitCode() != 0) {
            String out = response.getOut() + response.getErr();
            throw new Exception(trimOutput(out, "Probe failed"));
        }
        String out = response.getOut().trim();
        if (out.isEmpty()) {
            throw new Exception("Probe returned empty data");
        }
        return out;
    }

    public static YoutubeDLResponse runDownload(
            Context context,
            String processId,
            List<String> extraArgs,
            YtDlpProgressHandler progressHandler)
            throws Exception {
        ensureReady(context, 120);
        if (extraArgs == null || extraArgs.size() < 2) {
            throw new IllegalArgumentException("Missing download URL");
        }

        String url = extraArgs.get(extraArgs.size() - 1);
        YoutubeDLRequest request = new YoutubeDLRequest(url);
        request.addOption("--no-warnings");
        request.addOption("--no-cache-dir");
        addYoutubeCompatArgs(request);

        for (int i = 0; i < extraArgs.size() - 1; i++) {
            request.addOption(extraArgs.get(i));
        }

        return YoutubeDL.getInstance()
                .execute(
                        request,
                        processId,
                        false,
                        (progress, etaInSeconds, line) -> {
                            if (progressHandler != null) {
                                progressHandler.onProgress(progress, etaInSeconds);
                            }
                            return Unit.INSTANCE;
                        });
    }

    private static void addYoutubeCompatArgs(YoutubeDLRequest request) {
        request.addOption(
                "--extractor-args",
                "youtube:player_client=android,web_creator,web");
        request.addOption(
                "--user-agent",
                "Mozilla/5.0 (Linux; Android "
                        + Build.VERSION.RELEASE
                        + ") AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
    }

    public static List<String> buildDownloadArgs(
            String outputTemplate,
            String format,
            String mergeFormat,
            int maxThreads,
            boolean audioOnly) {
        List<String> args = new ArrayList<>();
        args.add("-f");
        args.add(format);
        if (!audioOnly && mergeFormat != null && !mergeFormat.isEmpty()) {
            args.add("--merge-output-format");
            args.add(mergeFormat);
        }
        args.add("-o");
        args.add(outputTemplate);
        args.add("--newline");
        args.add("--progress");
        args.add("--no-part");
        args.add("--retries");
        args.add("3");
        args.add("--socket-timeout");
        args.add("30");
        args.add("--concurrent-fragments");
        args.add(String.valueOf(Math.max(1, Math.min(maxThreads, 4))));
        args.add("--no-playlist");
        args.add("--embed-metadata");
        return args;
    }

    private static String trimOutput(String text, String fallback) {
        if (text == null || text.trim().isEmpty()) return fallback;
        String t = text.trim();
        if (t.length() > 500) t = t.substring(t.length() - 500);
        return fallback + ": " + t;
    }

    public interface YtDlpProgressHandler {
        void onProgress(float progress, long etaInSeconds);
    }
}
