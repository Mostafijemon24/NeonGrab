package com.neongrab.downloader.ytdlp;

import android.content.Context;
import android.net.Uri;
import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;
import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.yausername.youtubedl_android.YoutubeDLResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

public class YtDlpExecutor {

    public interface EventEmitter {
        void emitProgress(String jobId, int progress, long downloaded, long total, long speedBps);

        void emitComplete(
                String jobId,
                String filePath,
                String openUri,
                String mimeType,
                String title,
                long totalBytes);

        void emitFailed(String jobId, String message);
    }

    private static final Pattern PROGRESS_RE =
            Pattern.compile("\\[download\\]\\s+(\\d+(?:\\.\\d+)?)%");

    private static final Pattern SPEED_RE =
            Pattern.compile("at\\s+([\\d.]+)\\s*([KMG]?i?B)/s", Pattern.CASE_INSENSITIVE);

    private static final Set<String> MEDIA_EXT =
            new HashSet<>(
                    Arrays.asList(
                            "mp4", "m4v", "webm", "mkv", "mov", "3gp", "m4a", "mp3", "opus", "aac",
                            "flac", "wav", "ogg"));

    private final Context context;
    private final EventEmitter emitter;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final Map<String, YtDlpJob> jobs = new ConcurrentHashMap<>();

    public YtDlpExecutor(Context context, EventEmitter emitter) {
        this.context = context;
        this.emitter = emitter;
    }

    public void shutdown() {
        for (YtDlpJob job : jobs.values()) {
            cancel(job.jobId);
        }
        pool.shutdownNow();
    }

    public boolean isBinaryReady() {
        return YtDlpEngineHelper.isReady(context);
    }

    public void probe(PluginCall call, String url, boolean flatPlaylist) {
        pool.execute(
                () -> {
                    try {
                        String json = YtDlpEngineHelper.runJsonProbe(context, url, flatPlaylist);
                        JSObject result = parseProbeJson(json, url);
                        call.resolve(result);
                    } catch (Exception e) {
                        call.reject(
                                "PROBE_FAILED",
                                e.getMessage() != null ? e.getMessage() : "Probe failed",
                                e);
                    }
                });
    }

    public void startDownload(
            PluginCall call,
            String jobId,
            String url,
            String title,
            String quality,
            boolean audioOnly,
            int maxThreads) {
        if (jobs.containsKey(jobId)) {
            call.reject("JOB_EXISTS", "Download already running for " + jobId);
            return;
        }

        YtDlpJob job = new YtDlpJob(jobId, url, title);
        jobs.put(jobId, job);

        pool.execute(
                () -> {
                    StringBuilder log = new StringBuilder();
                    String resolvedTitle = resolveTitleForDownload(url, title);
                    try {
                        File tempDir = new File(context.getCacheDir(), "ytdlp_jobs/" + jobId);
                        deleteRecursive(tempDir);
                        if (!tempDir.mkdirs()) {
                            emitter.emitFailed(jobId, "Could not create temp folder");
                            return;
                        }

                        String safeId = jobId.replaceAll("[^a-zA-Z0-9_-]", "");
                        String outTemplate =
                                new File(tempDir, safeId + ".%(ext)s").getAbsolutePath();
                        Uri treeUri = DownloadFolderStore.getTreeUri(context);

                        List<String> args =
                                YtDlpEngineHelper.buildDownloadArgs(
                                        outTemplate,
                                        YtDlpFormatSelector.formatForQuality(quality, audioOnly),
                                        YtDlpFormatSelector.mergeFormat(),
                                        maxThreads,
                                        audioOnly);
                        args.add(url);

                        long totalEstimate = 100 * 1024 * 1024;
                        YoutubeDLResponse response =
                                YtDlpEngineHelper.runDownload(
                                        context,
                                        jobId,
                                        args,
                                        (progress, etaInSeconds, line) -> {
                                            if (job.cancelled.get() || job.paused.get()) {
                                                return;
                                            }
                                            int pct = Math.min(99, Math.round(progress));
                                            long downloaded = (totalEstimate * pct) / 100;
                                            long speedBps = parseSpeedBps(line);
                                            if (pct <= 0 && line != null) {
                                                Matcher pm = PROGRESS_RE.matcher(line);
                                                if (pm.find()) {
                                                    pct =
                                                            Math.min(
                                                                    99,
                                                                    Math.round(
                                                                            Float.parseFloat(
                                                                                    pm.group(1))));
                                                    downloaded = (totalEstimate * pct) / 100;
                                                }
                                            }
                                            emitter.emitProgress(
                                                    jobId,
                                                    pct,
                                                    downloaded,
                                                    totalEstimate,
                                                    speedBps);
                                        });

                        log.append(response.getOut()).append('\n').append(response.getErr());

                        jobs.remove(jobId);
                        if (job.cancelled.get()) return;

                        parseProgressFromLog(log, jobId, totalEstimate);

                        File saved = findLatestMedia(tempDir);
                        if (saved == null || saved.length() == 0) {
                            String reason = extractFailureReason(log);
                            emitter.emitFailed(
                                    jobId,
                                    reason != null
                                            ? reason
                                            : tailLog(
                                                    log,
                                                    "Downloaded file not found (code "
                                                            + response.getExitCode()
                                                            + ")"));
                            return;
                        }

                        long size = saved.length();
                        SavedFileResult savedResult;
                        try {
                            savedResult =
                                    persistDownloadedFile(context, treeUri, saved, resolvedTitle);
                        } catch (Exception ex) {
                            emitter.emitFailed(
                                    jobId,
                                    ex.getMessage() != null
                                            ? ex.getMessage()
                                            : "Could not save downloaded file");
                            return;
                        }
                        size = savedResult.sizeBytes > 0 ? savedResult.sizeBytes : saved.length();

                        deleteRecursive(tempDir);
                        emitter.emitProgress(jobId, 100, size, size, 0);
                        emitter.emitComplete(
                                jobId,
                                savedResult.displayPath,
                                savedResult.openUri,
                                savedResult.mimeType,
                                finalDisplayTitle(resolvedTitle, savedResult.displayPath),
                                size);
                    } catch (Exception e) {
                        jobs.remove(jobId);
                        String msg = e.getMessage() != null ? e.getMessage() : "Download failed";
                        if (EngineAbi.isAbiMismatchMessage(msg)) {
                            try {
                                YtDlpEngineHelper.resetEngineState(context);
                                YtDlpEngineHelper.ensureReady(context, 300);
                                msg =
                                        "Engine was wrong for this device and has been reinstalled. "
                                                + "Please tap Start Download again.";
                            } catch (Exception setupErr) {
                                msg = EngineAbi.friendlyError(msg);
                            }
                        } else {
                            msg = EngineAbi.friendlyError(msg);
                            if (msg == null) msg = "Download failed";
                        }
                        emitter.emitFailed(jobId, tailLog(log, msg));
                    }
                });

        JSObject ok = new JSObject();
        ok.put("ok", true);
        call.resolve(ok);
    }

    private static long parseSpeedBps(String line) {
        if (line == null || line.isEmpty()) return 0;
        Matcher m = SPEED_RE.matcher(line);
        if (!m.find()) return 0;
        try {
            double value = Double.parseDouble(m.group(1));
            String unit = m.group(2).toUpperCase(Locale.US);
            if (unit.startsWith("G")) return Math.round(value * 1024 * 1024 * 1024);
            if (unit.startsWith("M")) return Math.round(value * 1024 * 1024);
            if (unit.startsWith("K")) return Math.round(value * 1024);
            return Math.round(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void parseProgressFromLog(StringBuilder log, String jobId, long totalEstimate) {
        Matcher m = PROGRESS_RE.matcher(log.toString());
        int lastPct = 0;
        while (m.find()) {
            lastPct = Math.min(99, Math.round(Float.parseFloat(m.group(1))));
        }
        if (lastPct > 0) {
            long downloaded = (totalEstimate * lastPct) / 100;
            emitter.emitProgress(jobId, lastPct, downloaded, totalEstimate, 0);
        }
    }

    public void pause(String jobId) {
        YtDlpJob job = jobs.get(jobId);
        if (job == null) return;
        job.paused.set(true);
        try {
            com.yausername.youtubedl_android.YoutubeDL.getInstance().destroyProcessById(jobId);
        } catch (Exception ignored) {
            /* ignore */
        }
    }

    public void resume(String jobId) {
        YtDlpJob job = jobs.get(jobId);
        if (job == null) return;
        job.paused.set(false);
    }

    public void cancel(String jobId) {
        YtDlpJob job = jobs.get(jobId);
        if (job == null) return;
        job.cancelled.set(true);
        try {
            com.yausername.youtubedl_android.YoutubeDL.getInstance().destroyProcessById(jobId);
        } catch (Exception ignored) {
            /* ignore */
        }
        jobs.remove(jobId);
    }

    private JSObject parseProbeJson(String json, String fallbackUrl) throws Exception {
        JSONObject root = new JSONObject(json);
        JSObject result = new JSObject();
        JSONArray entries = new JSONArray();

        if (root.has("entries")) {
            JSONArray arr = root.getJSONArray("entries");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject e = arr.optJSONObject(i);
                if (e == null) continue;
                if (isDeadEntry(e)) continue;
                entries.put(entryFromJson(e, fallbackUrl, i));
            }
            result.put("batch", true);
        } else {
            if (!isDeadEntry(root)) {
                entries.put(entryFromJson(root, fallbackUrl, 0));
            }
            result.put("batch", false);
        }

        result.put("ok", entries.length() > 0);
        result.put("entries", entries);
        return result;
    }

    private static boolean isDeadEntry(JSONObject e) {
        String title = e.optString("title", "").toLowerCase(Locale.US);
        return title.contains("[deleted") || title.contains("private video");
    }

    private JSObject entryFromJson(JSONObject e, String fallbackUrl, int index)
            throws Exception {
        JSObject item = new JSObject();
        String id = e.optString("id", "item_" + index);
        String title = e.optString("title", "media_" + (index + 1));
        String webpage =
                e.optString(
                        "webpage_url",
                        e.optString("url", e.optString("original_url", fallbackUrl)));
        if (webpage != null && webpage.startsWith("https://www.youtube.com/watch?v=")) {
            /* direct watch url */
        } else if (id != null && !id.startsWith("item_") && webpage != null && webpage.contains("list=")) {
            webpage = "https://www.youtube.com/watch?v=" + id;
        }
        item.put("id", "job_" + id);
        item.put("title", title);
        item.put("url", webpage);
        item.put("duration", e.optLong("duration", 0));
        item.put("filesize", e.optLong("filesize", e.optLong("filesize_approx", 0)));
        String ext = e.optString("ext", "mp4");
        item.put("kind", isAudioExt(ext) ? "audio" : "video");
        return item;
    }

    private static boolean isAudioExt(String ext) {
        return "m4a".equals(ext) || "mp3".equals(ext) || "opus".equals(ext) || "aac".equals(ext);
    }

    private String resolveTitleForDownload(String url, String fallbackTitle) {
        if (!looksLikeWeakTitle(fallbackTitle)) {
            return fallbackTitle;
        }
        try {
            String json = YtDlpEngineHelper.runJsonProbe(context, url, false);
            JSONObject root = new JSONObject(json);
            if (root.has("entries")) {
                JSONArray arr = root.getJSONArray("entries");
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject e = arr.optJSONObject(i);
                    if (e == null || isDeadEntry(e)) continue;
                    String t = e.optString("title", "").trim();
                    if (!t.isEmpty()) return t;
                }
            } else if (!isDeadEntry(root)) {
                String t = root.optString("title", "").trim();
                if (!t.isEmpty()) return t;
            }
        } catch (Exception ignored) {
            /* use fallback */
        }
        return fallbackTitle != null && !fallbackTitle.isEmpty() ? fallbackTitle : "download";
    }

    private static String finalDisplayTitle(String title, String displayPath) {
        if (!looksLikeWeakTitle(title)) return title;
        if (displayPath == null || displayPath.isEmpty()) return title;
        int slash = displayPath.lastIndexOf('/');
        String fileName = slash >= 0 ? displayPath.substring(slash + 1) : displayPath;
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        base = base.replace('_', ' ').trim();
        if (!base.isEmpty() && !looksLikeWeakTitle(base)) return base;
        return title;
    }

    private static boolean looksLikeWeakTitle(String title) {
        if (title == null) return true;
        String t = title.trim();
        if (t.isEmpty()) return true;
        if ("watch".equals(t) || "shorts".equals(t)) return true;
        if (t.matches("(?i)^media(\\s+#\\d+)?$")) return true;
        return t.matches("^[a-zA-Z0-9_-]{11}$");
    }

    private static String extractFailureReason(StringBuilder log) {
        if (log == null || log.length() == 0) return null;
        String text = log.toString();
        String best = null;
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            String msg = null;
            if (trimmed.startsWith("ERROR:")) {
                msg = trimmed.substring(6).trim();
            } else if (trimmed.startsWith("error:")) {
                msg = trimmed.substring(6).trim();
            }
            if (msg != null && !msg.isEmpty()) {
                best = msg;
            }
        }
        if (best == null) return null;
        if (best.length() > 200) best = best.substring(0, 200) + "…";
        return best;
    }

    private static String tailLog(StringBuilder log, String fallback) {
        if (log.length() == 0) return fallback;
        String text = log.toString().trim();
        if (text.length() > 400) text = text.substring(text.length() - 400);
        return fallback + ": " + text;
    }

    private static SavedFileResult persistDownloadedFile(
            Context context, Uri treeUri, File saved, String title) throws Exception {
        if (treeUri != null) {
            try {
                return SafFileHelper.exportToTree(context, treeUri, saved, title);
            } catch (Exception safErr) {
                File fallback = YtDlpDownloadPathResolver.defaultNeonGrabDir(context);
                File dest = uniqueDestFile(fallback, saved.getName());
                copyFile(saved, dest);
                SavedFileResult local = SafFileHelper.fromLocalFile(context, dest);
                return new SavedFileResult(
                        "NeonGrab/"
                                + dest.getName()
                                + " (folder copy failed: "
                                + safErr.getMessage()
                                + ")",
                        local.openUri,
                        local.mimeType,
                        dest.length());
            }
        }
        File fallback = YtDlpDownloadPathResolver.defaultNeonGrabDir(context);
        File dest = uniqueDestFile(fallback, saved.getName());
        copyFile(saved, dest);
        return SafFileHelper.fromLocalFile(context, dest);
    }

    private static File uniqueDestFile(File dir, String name) {
        File dest = new File(dir, name);
        if (!dest.exists()) return dest;
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        for (int i = 1; i < 100; i++) {
            File candidate = new File(dir, base + "_" + i + ext);
            if (!candidate.exists()) return candidate;
        }
        return new File(dir, base + "_" + System.currentTimeMillis() + ext);
    }

    private static File findLatestMedia(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        File best = null;
        long bestSize = 0;
        for (File f : files) {
            if (f.isDirectory()) {
                File nested = findLatestMedia(f);
                if (nested != null && nested.length() >= bestSize) {
                    bestSize = nested.length();
                    best = nested;
                }
                continue;
            }
            if (!f.isFile() || f.length() < 32 * 1024) continue;
            String name = f.getName().toLowerCase(Locale.US);
            if (name.endsWith(".part")
                    || name.endsWith(".ytdl")
                    || name.endsWith(".temp")
                    || name.endsWith(".json")
                    || name.endsWith(".txt")
                    || name.endsWith(".jpg")
                    || name.endsWith(".png")) {
                continue;
            }
            String ext = extensionOf(name);
            if (!ext.isEmpty() && !MEDIA_EXT.contains(ext)) continue;
            if (f.length() >= bestSize) {
                bestSize = f.length();
                best = f;
            }
        }
        return best;
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1);
    }

    private static void copyFile(File source, File dest) throws Exception {
        if (source.getAbsolutePath().equals(dest.getAbsolutePath())) return;
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        //noinspection ResultOfMethodCallIgnored
        dest.delete();
        if (source.renameTo(dest) && dest.exists()) return;
        try (java.io.FileInputStream in = new java.io.FileInputStream(source);
                java.io.FileOutputStream out = new java.io.FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        source.delete();
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

}
