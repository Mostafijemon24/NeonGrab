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
                                        (progress, etaInSeconds) -> {
                                            if (job.cancelled.get() || job.paused.get()) {
                                                return;
                                            }
                                            int pct = Math.min(99, Math.round(progress));
                                            long downloaded = (totalEstimate * pct) / 100;
                                            emitter.emitProgress(
                                                    jobId, pct, downloaded, totalEstimate, 0);
                                        });

                        log.append(response.getOut()).append('\n').append(response.getErr());

                        jobs.remove(jobId);
                        if (job.cancelled.get()) return;

                        if (response.getExitCode() != 0) {
                            emitter.emitFailed(
                                    jobId,
                                    tailLog(log, "yt-dlp failed (code " + response.getExitCode() + ")"));
                            return;
                        }

                        parseProgressFromLog(log, jobId, totalEstimate);

                        File saved = findLatestMedia(tempDir);
                        if (saved == null || saved.length() == 0) {
                            emitter.emitFailed(jobId, tailLog(log, "Downloaded file not found"));
                            return;
                        }

                        long size = saved.length();
                        SavedFileResult savedResult;
                        if (treeUri != null) {
                            try {
                                savedResult = SafFileHelper.exportToTree(context, treeUri, saved);
                            } catch (Exception ex) {
                                emitter.emitFailed(
                                        jobId,
                                        ex.getMessage() != null
                                                ? ex.getMessage()
                                                : "Could not save to selected folder");
                                return;
                            }
                        } else {
                            File fallback =
                                    YtDlpDownloadPathResolver.defaultNeonGrabDir(context);
                            File dest = new File(fallback, saved.getName());
                            copyFile(saved, dest);
                            size = dest.length();
                            try {
                                savedResult = SafFileHelper.fromLocalFile(context, dest);
                            } catch (Exception ex) {
                                emitter.emitFailed(
                                        jobId,
                                        ex.getMessage() != null
                                                ? ex.getMessage()
                                                : "Could not prepare file for opening");
                                return;
                            }
                        }

                        deleteRecursive(tempDir);
                        emitter.emitComplete(
                                jobId,
                                savedResult.displayPath,
                                savedResult.openUri,
                                savedResult.mimeType,
                                title,
                                size);
                    } catch (Exception e) {
                        jobs.remove(jobId);
                        emitter.emitFailed(
                                jobId,
                                tailLog(log, e.getMessage() != null ? e.getMessage() : "Download failed"));
                    }
                });

        JSObject ok = new JSObject();
        ok.put("ok", true);
        call.resolve(ok);
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

    private static String tailLog(StringBuilder log, String fallback) {
        if (log.length() == 0) return fallback;
        String text = log.toString().trim();
        if (text.length() > 400) text = text.substring(text.length() - 400);
        return fallback + ": " + text;
    }

    private static File findLatestMedia(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        File best = null;
        long bestTime = 0;
        for (File f : files) {
            if (f.isDirectory()) {
                File nested = findLatestMedia(f);
                if (nested != null && nested.lastModified() >= bestTime) {
                    bestTime = nested.lastModified();
                    best = nested;
                }
                continue;
            }
            if (!f.isFile() || f.length() == 0) continue;
            String name = f.getName().toLowerCase(Locale.US);
            if (name.endsWith(".part") || name.endsWith(".ytdl") || name.endsWith(".temp")) {
                continue;
            }
            String ext = extensionOf(name);
            if (!MEDIA_EXT.contains(ext)) continue;
            if (f.lastModified() >= bestTime) {
                bestTime = f.lastModified();
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
