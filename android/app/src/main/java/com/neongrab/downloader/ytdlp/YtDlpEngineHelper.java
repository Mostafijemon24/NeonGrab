package com.neongrab.downloader.ytdlp;

import android.content.Context;
import android.os.Build;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLUpdater;
import java.io.File;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.YoutubeDLResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
/** Wraps youtubedl-android (bundled Python + yt-dlp + ffmpeg) for real Android execution. */
public final class YtDlpEngineHelper {

    private static final int UPDATE_TIMEOUT_SEC = 90;
    private static final Object LOCK = new Object();
    private static volatile boolean initialized;
    private static volatile boolean initSucceeded;
    private static volatile boolean updateAttempted;
    private static volatile boolean setupInProgress;
    private static volatile String lastError;
    private static volatile YtDlpSetupProgress progressListener;

    private YtDlpEngineHelper() {}

    public static void setProgressListener(YtDlpSetupProgress listener) {
        progressListener = listener;
    }

    public static boolean isSetupInProgress() {
        return setupInProgress;
    }

    public static boolean isReady(Context context) {
        // Init success is authoritative; version() is often empty until first yt-dlp run,
        // and bundledEnginePresent() misses lean APK engines in code_cache.
        return initialized && initSucceeded;
    }

    public static String getVersion(Context context) {
        Context app = context.getApplicationContext();
        try {
            String v = YoutubeDL.getInstance().version(app);
            if (v != null && !v.trim().isEmpty()) return v.trim();
            v = YoutubeDL.getInstance().versionName(app);
            if (v != null && !v.trim().isEmpty()) return v.trim();
        } catch (Exception ignored) {
            /* ignore */
        }
        return initSucceeded ? "bundled" : null;
    }

    public static String getLastError() {
        return lastError;
    }

    public static boolean ensureReady(Context context, int timeoutSeconds) throws Exception {
        Context app = context.getApplicationContext();
        try {
            return runSetup(app, false);
        } catch (Exception e) {
            if (EngineAbi.isAbiMismatchMessage(e.getMessage())) {
                resetEngineState(app);
                return runSetup(app, true);
            }
            throw e;
        }
    }

    public static boolean retrySetup(Context context, int timeoutSeconds) throws Exception {
        resetEngineState(context.getApplicationContext());
        return runSetup(context.getApplicationContext(), true);
    }

    static void resetEngineState(Context app) {
        initialized = false;
        initSucceeded = false;
        updateAttempted = false;
        lastError = null;
        EnginePackDownloader.clearEngineInstall(app);
    }

    private static boolean runSetup(Context app, boolean forceRetry) throws Exception {
        if (setupInProgress) {
            if (!waitForOtherSetup(forceRetry)) {
                throw new Exception(
                        lastError != null
                                ? lastError
                                : "Engine setup timed out. Tap Retry.");
            }
            if (isReady(app)) {
                report(100, "Download engine ready");
                return true;
            }
            if (!forceRetry) {
                throw new Exception(
                        lastError != null ? lastError : "Engine setup did not finish");
            }
        }

        setupInProgress = true;
        try {
            report(3, "Preparing download engine…");
            EnginePackDownloader.reconcileEngineForDevice(app);
            synchronized (LOCK) {
                if (forceRetry) {
                    initialized = false;
                    initSucceeded = false;
                    updateAttempted = false;
                    lastError = null;
                    EnginePackDownloader.clearEngineInstall(app);
                }

                if (!EngineNativeLoader.apkBundledNativesPresent(app)
                        && !EnginePackDownloader.isInstalled(app)) {
                    initialized = false;
                    initSucceeded = false;
                    EngineAbi.requireSupportedDevice(app);
                    report(2, "Preparing download engine…");
                    ensureEnginePack(app);
                }

                if (!initialized) {
                    report(48, "Initializing engine…");
                    initBundledLibraries(app);
                    report(65, "Runtime ready");
                }

                report(55, "Verifying engine…");
                if (!initialized || !initSucceeded) {
                    throw new Exception(
                            lastError != null
                                    ? lastError
                                    : "Engine initialization failed");
                }
                if (!verifyEngineReady(app)) {
                    throw new Exception(
                            lastError != null
                                    ? lastError
                                    : "Engine verification failed");
                }
                report(75, "Engine verified");

                if (!updateAttempted) {
                    updateAttempted = true;
                    report(78, "Checking for updates (optional)…");
                    tryUpdateWithTimeout(app);
                }
            }

            report(95, "Finalizing…");
            if (!isReady(app)) {
                throw new Exception(
                        lastError != null ? lastError : "Download engine is not ready");
            }
            report(100, "Download engine ready");
            lastError = null;
            return true;
        } finally {
            setupInProgress = false;
        }
    }

    private static boolean waitForOtherSetup(boolean forceRetry) throws InterruptedException {
        int waited = 0;
        int maxWait = forceRetry ? 5_000 : 120_000;
        while (setupInProgress && waited < maxWait) {
            Thread.sleep(250);
            waited += 250;
        }
        return !setupInProgress;
    }

    private static void initBundledLibraries(Context app) throws Exception {
        try {
            EngineNativeLoader.initEngine(app);
            FfmpegBinaryHelper.prepare(app);
            initialized = true;
            initSucceeded = true;
            lastError = null;
        } catch (Exception e) {
            initialized = false;
            initSucceeded = false;
            lastError = formatError(e);
            throw new Exception(lastError, e);
        }
    }

    private static void ensureEnginePack(Context app) throws Exception {
        EnginePackDownloader.ensureDownloaded(
                app,
                (percent, message) -> report(percent, message));
        EngineNativeLoader.reset();
    }

    private static boolean hasVersion(Context app) {
        try {
            String v = YoutubeDL.getInstance().version(app);
            if (v != null && !v.trim().isEmpty()) return true;
            v = YoutubeDL.getInstance().versionName(app);
            if (v != null && !v.trim().isEmpty()) return true;
            v = YoutubeDLUpdater.INSTANCE.version(app);
            return v != null && !v.trim().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean bundledEnginePresent(Context app) {
        try {
            if (EnginePackDownloader.isInstalled(app)) return true;
            if (EngineNativeLoader.apkBundledNativesPresent(app)) return true;
            File binDir = EngineNativeLoader.resolveBinDir(app);
            if (binDir != null && EngineNativeLoader.verifyBinDir(binDir)) return true;
            File base = new File(app.getFilesDir(), YoutubeDL.ytdlpDirName);
            File bin = new File(base, YoutubeDL.ytdlpBin);
            if (bin.isFile() && bin.length() > 512) return true;
            File py = new File(base, "libpython.zip.so");
            return py.isFile() && py.length() > 1024;
        } catch (Exception e) {
            return false;
        }
    }

    /** Init success is primary; version API often returns empty until first run. */
    private static boolean verifyEngineReady(Context app) {
        if (!initialized || !initSucceeded) return false;
        if (bundledEnginePresent(app)) return true;
        if (hasVersion(app)) return true;
        return true;
    }

    private static void tryUpdateWithTimeout(Context app) {
        AtomicBoolean done = new AtomicBoolean(false);
        Thread ticker =
                new Thread(
                        () -> {
                            int p = 78;
                            while (!done.get() && p < 92) {
                                report(p, "Updating engine (optional)…");
                                try {
                                    Thread.sleep(500);
                                } catch (InterruptedException ignored) {
                                    break;
                                }
                                p += 2;
                            }
                        },
                        "ytdlp-update-ticker");

        ExecutorService pool = Executors.newSingleThreadExecutor();
        ticker.start();
        try {
            Future<?> future =
                    pool.submit(
                            () -> {
                                try {
                                    YoutubeDL.UpdateStatus status =
                                            YoutubeDL.getInstance()
                                                    .updateYoutubeDL(
                                                            app,
                                                            YoutubeDL.UpdateChannel.STABLE
                                                                    .INSTANCE);
                                    if (status == YoutubeDL.UpdateStatus.DONE
                                            || status
                                                    == YoutubeDL.UpdateStatus
                                                            .ALREADY_UP_TO_DATE) {
                                        lastError = null;
                                    }
                                } catch (Exception e) {
                                    lastError = e.getMessage();
                                }
                            });
            future.get(UPDATE_TIMEOUT_SEC, TimeUnit.SECONDS);
        } catch (Exception e) {
            lastError =
                    e instanceof java.util.concurrent.TimeoutException
                            ? "Update skipped (slow network); using bundled engine"
                            : e.getMessage();
        } finally {
            done.set(true);
            ticker.interrupt();
            pool.shutdownNow();
        }
    }

    private static String formatError(Throwable e) {
        if (e == null) return "Failed to initialize download engine";
        String msg = e.getMessage();
        if (msg == null || msg.isEmpty()) msg = e.getClass().getSimpleName();
        String friendly = EngineAbi.friendlyError(msg);
        if (friendly != null && !friendly.equals(msg)) return friendly;
        if (msg.toLowerCase().contains("failed to initialize")) {
            return msg
                    + ". Reinstall the app or tap Retry. Use a real phone or ARM64 emulator.";
        }
        return msg;
    }

    private static void report(int percent, String message) {
        if (progressListener != null) {
            progressListener.onProgress(
                    Math.max(0, Math.min(100, percent)), message);
        }
    }

    public static String runJsonProbe(Context context, String url, boolean flatPlaylist)
            throws Exception {
        ensureReady(context, 300);
        FfmpegBinaryHelper.ensurePatched(context.getApplicationContext());
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
                YtDlpProcessRunner.execute(
                        context,
                        request,
                        "probe_" + Math.abs(url.hashCode()),
                        false,
                        null);
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
        ensureReady(context, 300);
        Context app = context.getApplicationContext();
        FfmpegBinaryHelper.ensurePatched(app);

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

        return YtDlpProcessRunner.execute(
                context,
                request,
                processId,
                false,
                progressHandler);
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
        args.add("--no-embed-metadata");
        args.add("--no-embed-thumbnail");
        args.add("--no-abort-on-error");
        args.add("--ignore-no-formats-error");
        return args;
    }

    private static String trimOutput(String text, String fallback) {
        if (text == null || text.trim().isEmpty()) return fallback;
        String t = text.trim();
        if (t.length() > 500) t = t.substring(t.length() - 500);
        return fallback + ": " + t;
    }

    public interface YtDlpProgressHandler {
        void onProgress(float progress, long etaInSeconds, String line);
    }
}
