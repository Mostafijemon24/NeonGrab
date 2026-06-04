package com.neongrab.downloader.ytdlp;

import android.content.Context;
import android.content.SharedPreferences;
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
    private static final int FORCE_UPDATE_TIMEOUT_SEC = 120;
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
                EnginePackDownloader.clearEngineInstall(app);
                initialized = false;
                initSucceeded = false;
                updateAttempted = false;
                lastError = null;
                EngineNativeLoader.reset();
                FfmpegBinaryHelper.reset();
                return runSetup(app, false);
            }
            throw e;
        }
    }

    /** Re-initialize without deleting a valid on-disk engine pack (avoids re-download loops). */
    public static boolean retrySetup(Context context, int timeoutSeconds) throws Exception {
        Context app = context.getApplicationContext();
        initialized = false;
        initSucceeded = false;
        updateAttempted = false;
        lastError = null;
        EngineNativeLoader.reset();
        FfmpegBinaryHelper.reset();
        return runSetup(app, false);
    }

    static void resetEngineState(Context app) {
        initialized = false;
        initSucceeded = false;
        updateAttempted = false;
        lastError = null;
        app.getSharedPreferences("neongrab_engine", Context.MODE_PRIVATE).edit().clear().apply();
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
            SharedPreferences prefs =
                    app.getSharedPreferences("neongrab_engine", Context.MODE_PRIVATE);
            boolean wasReadyBefore =
                    prefs.getBoolean("ready", false) && EnginePackDownloader.isInstalled(app);

            synchronized (LOCK) {
                if (forceRetry) {
                    initialized = false;
                    initSucceeded = false;
                    updateAttempted = false;
                    lastError = null;
                    EngineNativeLoader.reset();
                    FfmpegBinaryHelper.reset();
                    File persistent = EngineNativeLoader.persistentBinDir(app);
                    if (!EnginePackDownloader.isInstalled(app)
                            || !EngineAbi.validateEngineBinDir(persistent)) {
                        EnginePackDownloader.clearEngineInstall(app);
                    }
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
                if (!wasReadyBefore) {
                    report(68, "Testing engine…");
                    smokeTestEngine(app);
                    report(75, "Engine verified");
                } else {
                    report(75, "Engine ready");
                }

                if (!updateAttempted) {
                    updateAttempted = true;
                    if (shouldCheckForUpdate(app)) {
                        report(78, "Updating yt-dlp (nightly)…");
                        tryUpdateWithTimeout(app, YoutubeDL.UpdateChannel.NIGHTLY.INSTANCE, false);
                        recordUpdateCheck(app);
                    } else {
                        report(85, "Engine ready");
                    }
                }
            }

            report(95, "Finalizing…");
            if (!isReady(app)) {
                throw new Exception(
                        lastError != null ? lastError : "Download engine is not ready");
            }
            markSetupComplete(app);
            report(100, "Download engine ready");
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
            if (EngineNativeLoader.apkBundledNativesPresent(app)) {
                /* Native libs are in the APK — use the library's own well-tested init path. */
                YoutubeDL.getInstance().init(app);
            } else {
                /* Lean APK: engine was downloaded; initialize via reflection. */
                EngineNativeLoader.initEngine(app);
            }
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
        EngineNativeLoader.ensureExecBinDir(app);
    }

    private static void markSetupComplete(Context app) {
        try {
            app.getSharedPreferences("neongrab_engine", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("ready", true)
                    .putString("pack_ver", EnginePackConfig.packVersion(app))
                    .apply();
        } catch (Exception ignored) {
            /* optional persistence */
        }
        lastError = null;
    }

    private static void smokeTestEngine(Context app) throws Exception {
        YoutubeDLRequest request = new YoutubeDLRequest("https://www.youtube.com/watch?v=jNQXAC9IVRw");
        request.addOption("--version");
        request.addOption("--no-download");
        request.addOption("--no-playlist");
        try {
            YoutubeDLResponse response =
                    YtDlpProcessRunner.execute(
                            app, request, "smoke_" + System.nanoTime(), true, null);
            if (response.getExitCode() == 0) return;
            String detail = (response.getErr() + "\n" + response.getOut()).trim();
            throw new Exception(detail.isEmpty() ? "Engine test failed" : detail);
        } catch (Exception e) {
            if (EngineAbi.isAbiMismatchMessage(e.getMessage())) throw e;
            File binDir = EngineNativeLoader.resolveBinDir(app);
            if (EnginePackDownloader.isInstalled(app)
                    && binDir != null
                    && EngineNativeLoader.verifyBinDir(binDir)) {
                return;
            }
            throw e;
        }
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

    private static final long UPDATE_INTERVAL_MS = 2 * 60 * 60 * 1000L; // 2 hours

    /** Returns true if yt-dlp should be updated (first run or > 24 hours since last update). */
    private static boolean shouldCheckForUpdate(Context app) {
        SharedPreferences prefs = app.getSharedPreferences("neongrab_engine", Context.MODE_PRIVATE);
        long lastCheck = prefs.getLong("ytdlp_last_update_check", 0L);
        return System.currentTimeMillis() - lastCheck > UPDATE_INTERVAL_MS;
    }

    private static void recordUpdateCheck(Context app) {
        app.getSharedPreferences("neongrab_engine", Context.MODE_PRIVATE)
                .edit()
                .putLong("ytdlp_last_update_check", System.currentTimeMillis())
                .apply();
    }

    /** Pull latest nightly yt-dlp before adult-site extractors (xHamster changes often). */
    static void ensureFreshYtDlpForSites(Context app) {
        synchronized (LOCK) {
            tryUpdateWithTimeout(app, YoutubeDL.UpdateChannel.NIGHTLY.INSTANCE, true);
        }
    }

    private static void tryUpdateWithTimeout(Context app) {
        tryUpdateWithTimeout(app, YoutubeDL.UpdateChannel.NIGHTLY.INSTANCE, false);
    }

    private static void tryUpdateWithTimeout(
            Context app, YoutubeDL.UpdateChannel channel, boolean force) {
        if (!force && updateAttempted) {
            return;
        }
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
                                                    .updateYoutubeDL(app, channel);
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
            int timeoutSec = force ? FORCE_UPDATE_TIMEOUT_SEC : UPDATE_TIMEOUT_SEC;
            future.get(timeoutSec, TimeUnit.SECONDS);
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
        String probeUrl = cleanDownloadUrl(url);
        boolean adult = isAdultSiteUrl(probeUrl.toLowerCase());
        if (adult) {
            ensureFreshYtDlpForSites(context.getApplicationContext());
        }
        try {
            return executeJsonProbe(context, url, probeUrl, flatPlaylist);
        } catch (Exception first) {
            if (!adult) {
                throw first;
            }
            ensureFreshYtDlpForSites(context.getApplicationContext());
            try {
                return executeJsonProbe(context, url, probeUrl, flatPlaylist);
            } catch (Exception second) {
                String friendly = YtDlpUserMessage.fromYtDlp(second.getMessage());
                throw new Exception(
                        friendly != null ? friendly : trimOutput(second.getMessage(), "Probe failed"));
            }
        }
    }

    private static String executeJsonProbe(
            Context context, String originalUrl, String probeUrl, boolean flatPlaylist)
            throws Exception {
        YoutubeDLRequest request = new YoutubeDLRequest(probeUrl);
        request.addOption("-J");
        request.addOption("--no-warnings");
        request.addOption("--no-cache-dir");
        request.addOption("--socket-timeout");
        request.addOption("60");

        if (flatPlaylist) {
            request.addOption("--flat-playlist");
            request.addOption("--ignore-errors");
            if (isYouTubeUrl(probeUrl.toLowerCase())) {
                request.addOption("--extractor-args", "youtube:player_client=web");
            } else {
                addSiteCompatArgs(request, probeUrl);
            }
        } else {
            request.addOption("--no-playlist");
            addSiteCompatArgs(request, probeUrl);
        }

        YoutubeDLResponse response =
                YtDlpProcessRunner.execute(
                        context,
                        request,
                        "probe_" + Math.abs(originalUrl.hashCode()),
                        true,
                        null);
        if (response.getExitCode() != 0) {
            String combined = (response.getOut() + "\n" + response.getErr()).trim();
            String friendly = YtDlpUserMessage.fromYtDlp(combined);
            throw new Exception(
                    friendly != null ? friendly : trimOutput(combined, "Playlist probe failed"));
        }
        String out = response.getOut().trim();
        if (out.isEmpty()) {
            throw new Exception("yt-dlp returned empty data — playlist may be private or unavailable");
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

        String rawUrl = extraArgs.get(extraArgs.size() - 1);
        String url = cleanDownloadUrl(rawUrl);
        if (isAdultSiteUrl(url.toLowerCase())) {
            ensureFreshYtDlpForSites(app);
        }
        YoutubeDLRequest request = new YoutubeDLRequest(url);
        request.addOption("--no-warnings");
        request.addOption("--no-cache-dir");
        request.addOption("--socket-timeout");
        request.addOption("60");
        addSiteCompatArgs(request, url);

        String lowerForCheck = url.toLowerCase();
        boolean nonYoutube = !isYouTubeUrl(lowerForCheck);
        for (int i = 0; i < extraArgs.size() - 1; i++) {
            String arg = extraArgs.get(i);
            /* For non-YouTube sites allow .part files to avoid in-place write conflicts */
            if (nonYoutube && "--no-part".equals(arg)) {
                // skip --no-part; yt-dlp will use .part files which are safer for CDN downloads
            } else {
                request.addOption(arg);
            }
        }
        /* Force HTTP chunked parallel download for CDN-hosted content.
           Without --http-chunk-size yt-dlp uses a single TCP stream even if
           --concurrent-fragments > 1. Chunking splits the file into N pieces
           each fetched in its own connection, multiplying effective throughput. */
        if (nonYoutube) {
            request.addOption("--http-chunk-size");
            request.addOption("10485760"); // 10 MB per chunk
        }

        /* Merge stderr into stdout for non-YouTube sites so extractFailureReason()
           can find the real yt-dlp ERROR: line even when Python crashes at exit. */
        boolean mergeStreams = !isYouTubeUrl(url.toLowerCase());
        return YtDlpProcessRunner.execute(
                context,
                request,
                processId,
                mergeStreams,
                progressHandler);
    }

    /** Strip tracking/share params that confuse extractors. */
    private static String cleanDownloadUrl(String url) {
        if (url == null) return "";
        String u = url.trim();
        if (u.contains("youtube.com/") || u.contains("youtu.be/")) {
            u = normalizeProbeUrl(u);
        }
        u = normalizeAdultWatchUrl(u);
        try {
            android.net.Uri parsed = android.net.Uri.parse(u);
            String path = parsed.getPath() != null ? parsed.getPath().toLowerCase() : "";
            boolean stripAllQuery =
                    isAdultSiteUrl(u.toLowerCase())
                            && (path.contains("/videos/") || path.contains("/view_video"));
            android.net.Uri.Builder b = parsed.buildUpon();
            if (stripAllQuery) {
                b.clearQuery();
            } else {
                b.clearQuery();
                for (String key : parsed.getQueryParameterNames()) {
                    if (isTrackingQueryKey(key)) {
                        continue;
                    }
                    String val = parsed.getQueryParameter(key);
                    if (val != null) b.appendQueryParameter(key, val);
                }
            }
            b.fragment(null);
            String cleaned = b.build().toString();
            if (cleaned != null && !cleaned.isEmpty()) return cleaned;
        } catch (Exception ignored) {
            /* fall through */
        }
        return u;
    }

    private static boolean isTrackingQueryKey(String key) {
        if (key == null) return true;
        String k = key.toLowerCase();
        return k.startsWith("utm_")
                || k.startsWith("utm")
                || "fbclid".equals(k)
                || "si".equals(k)
                || "feature".equals(k)
                || "ref".equals(k)
                || "referer".equals(k)
                || "referrer".equals(k)
                || "referral".equals(k)
                || "campaign".equals(k)
                || "source".equals(k);
    }

    /** Canonical watch URL — referral query strings break xHamster/PornHub extractors. */
    private static String normalizeAdultWatchUrl(String url) {
        try {
            android.net.Uri parsed = android.net.Uri.parse(url);
            String host = parsed.getHost();
            if (host == null) return url;
            String lowerHost = host.toLowerCase();
            String path = parsed.getPath() != null ? parsed.getPath() : "";
            if (!isAdultSiteUrl((lowerHost + path).toLowerCase())) {
                return url;
            }
            if (lowerHost.contains("xhamster")) {
                host = "www.xhamster.com";
            } else if (lowerHost.contains("pornhub")) {
                host = "www.pornhub.com";
            } else if (!host.startsWith("www.")) {
                host = "www." + host;
            }
            if (path.contains("/videos/") || path.contains("/view_video")) {
                return new android.net.Uri.Builder()
                        .scheme("https")
                        .authority(host)
                        .path(path)
                        .build()
                        .toString();
            }
        } catch (Exception ignored) {
            /* keep original */
        }
        return url;
    }

    private static String normalizeProbeUrl(String url) {
        if (url == null) return "";
        String u = url.trim();
        if (u.startsWith("https://youtube.com/") || u.startsWith("http://youtube.com/")) {
            u = "https://www.youtube.com/" + u.substring(u.indexOf("youtube.com/") + 12);
        } else if (u.startsWith("https://m.youtube.com/") || u.startsWith("http://m.youtube.com/")) {
            u = "https://www.youtube.com/" + u.substring(u.indexOf("m.youtube.com/") + 14);
        }
        int si = u.indexOf("&si=");
        if (si > 0) u = u.substring(0, si);
        int feat = u.indexOf("&feature=");
        if (feat > 0) u = u.substring(0, feat);
        return u;
    }

    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/124.0.0.0 Safari/537.36";

    /** Site-specific compat args — only apply YouTube args to YouTube URLs. */
    private static void addSiteCompatArgs(YoutubeDLRequest request, String url) {
        String lowerUrl = url.toLowerCase();

        if (isYouTubeUrl(lowerUrl)) {
            String mobileUa = "Mozilla/5.0 (Linux; Android "
                    + Build.VERSION.RELEASE
                    + ") AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";
            request.addOption("--user-agent", mobileUa);
            request.addOption(
                    "--extractor-args",
                    "youtube:player_client=android,web_creator,web");
        } else if (isFacebookUrl(lowerUrl)) {
            request.addOption("--user-agent", DESKTOP_UA);
            request.addOption("--referer", "https://www.facebook.com/");
            request.addOption("--age-limit", "99");
        } else if (isXhamsterUrl(lowerUrl)) {
            request.addOption("--user-agent", DESKTOP_UA);
            String referer = extractBaseReferer(url);
            if (referer != null) {
                request.addOption("--referer", referer);
            }
            request.addOption("--age-limit", "99");
            request.addOption("--no-check-certificates");
            request.addOption(
                    "--add-header",
                    "Cookie: age_verified=1; cookies_accepted=1; parental-control=no");
        } else if (isAdultSiteUrl(lowerUrl)) {
            /* PornHub/CDN need site-root Referer (not the watch URL) — see yt-dlp #15827 */
            request.addOption("--user-agent", DESKTOP_UA);
            String referer = extractBaseReferer(url);
            if (referer != null) {
                request.addOption("--referer", referer);
            }
            request.addOption("--age-limit", "99");
            request.addOption("--no-check-certificates");
        } else {
            /* Generic fallback */
            String mobileUa = "Mozilla/5.0 (Linux; Android "
                    + Build.VERSION.RELEASE
                    + ") AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";
            request.addOption("--user-agent", mobileUa);
            request.addOption("--age-limit", "99");
            request.addOption("--no-check-certificates");
        }
    }

    private static boolean isYouTubeUrl(String lowerUrl) {
        return lowerUrl.contains("youtube.com/") || lowerUrl.contains("youtu.be/");
    }

    private static boolean isFacebookUrl(String lowerUrl) {
        return lowerUrl.contains("facebook.com/") || lowerUrl.contains("fb.watch/")
                || lowerUrl.contains("fb.com/");
    }

    private static boolean isXhamsterUrl(String lowerUrl) {
        return lowerUrl != null && lowerUrl.contains("xhamster.");
    }

    static boolean isAdultSiteUrl(String lowerUrl) {
        if (lowerUrl == null) return false;
        return lowerUrl.contains("pornhub.")
                || lowerUrl.contains("pornhubpremium")
                || lowerUrl.contains("xhamster.")
                || lowerUrl.contains("xvideos.")
                || lowerUrl.contains("xnxx.")
                || lowerUrl.contains("redtube.")
                || lowerUrl.contains("youporn.")
                || lowerUrl.contains("tube8.")
                || lowerUrl.contains("spankbang.")
                || lowerUrl.contains("tnaflix.")
                || lowerUrl.contains("drtuber.")
                || lowerUrl.contains("hclips.");
    }

    /** Returns scheme + host as referer, e.g. "https://xhamster.com/" */
    private static String extractBaseReferer(String url) {
        try {
            android.net.Uri u = android.net.Uri.parse(url);
            String host = u.getHost();
            String scheme = u.getScheme();
            if (host != null && scheme != null) {
                return scheme + "://" + host + "/";
            }
        } catch (Exception ignored) { /* */ }
        return null;
    }

    private static void addYoutubeCompatArgs(YoutubeDLRequest request) {
        addSiteCompatArgs(request, "https://www.youtube.com/");
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
        args.add("--concurrent-fragments");
        args.add(String.valueOf(Math.max(2, Math.min(maxThreads, 8))));
        args.add("--no-playlist");
        args.add("--no-embed-metadata");
        args.add("--no-embed-thumbnail");
        args.add("--no-abort-on-error");
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
