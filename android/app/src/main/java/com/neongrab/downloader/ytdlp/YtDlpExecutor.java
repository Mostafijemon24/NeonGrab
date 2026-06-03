package com.neongrab.downloader.ytdlp;

import android.content.Context;
import android.net.Uri;
import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Runs yt-dlp CLI for probe and download. Emits progress via {@link YtDlpEventEmitter}.
 */
public class YtDlpExecutor {

    public interface EventEmitter {
        void emitProgress(String jobId, int progress, long downloaded, long total, long speedBps);

        void emitComplete(String jobId, String filePath, String title, long totalBytes);

        void emitFailed(String jobId, String message);
    }

    private static final Pattern PROGRESS_RE =
            Pattern.compile("\\[download\\]\\s+(\\d+(?:\\.\\d+)?)%");

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
        return YtDlpBinaryProvider.isInstalled(context);
    }

    public void probe(PluginCall call, String url, boolean flatPlaylist) {
        if (!YtDlpBinaryProvider.isInstalled(context)) {
            call.reject("YT_DLP_NOT_INSTALLED", "yt-dlp binary missing. Add assets/bin/yt-dlp");
            return;
        }
        pool.execute(
                () -> {
                    try {
                        List<String> cmd = baseCommand();
                        cmd.add("-J");
                        if (flatPlaylist) {
                            cmd.add("--flat-playlist");
                        }
                        cmd.add(url);
                        String json = runAndCapture(cmd);
                        JSObject result = parseProbeJson(json, url);
                        call.resolve(result);
                    } catch (Exception e) {
                        call.reject("PROBE_FAILED", e.getMessage(), e);
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
        if (!YtDlpBinaryProvider.isInstalled(context)) {
            call.reject("YT_DLP_NOT_INSTALLED", "yt-dlp binary missing");
            return;
        }
        if (jobs.containsKey(jobId)) {
            call.reject("JOB_EXISTS", "Download already running for " + jobId);
            return;
        }

        YtDlpJob job = new YtDlpJob(jobId, url, title);
        jobs.put(jobId, job);

        pool.execute(
                () -> {
                    try {
                        File tempDir = new File(context.getCacheDir(), "ytdlp_jobs");
                        if (!tempDir.exists()) tempDir.mkdirs();
                        String outTemplate =
                                new File(tempDir, sanitize(title) + ".%(ext)s")
                                        .getAbsolutePath();
                        Uri treeUri = DownloadFolderStore.getTreeUri(context);

                        List<String> cmd = baseCommand();
                        cmd.add("-f");
                        cmd.add(YtDlpFormatSelector.formatForQuality(quality, audioOnly));
                        cmd.add("-o");
                        cmd.add(outTemplate);
                        cmd.add("--newline");
                        cmd.add("--progress");
                        cmd.add("--concurrent-fragments");
                        cmd.add(String.valueOf(Math.max(1, Math.min(maxThreads, 8))));
                        cmd.add("--no-playlist");
                        cmd.add(url);

                        ProcessBuilder pb = new ProcessBuilder(cmd);
                        pb.redirectErrorStream(true);
                        Process process = pb.start();
                        job.process = process;

                        long totalEstimate = 100 * 1024 * 1024;
                        try (BufferedReader reader =
                                new BufferedReader(
                                        new InputStreamReader(process.getInputStream()))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (job.cancelled.get()) {
                                    process.destroy();
                                    return;
                                }
                                if (job.paused.get()) {
                                    process.destroy();
                                    return;
                                }
                                Matcher m = PROGRESS_RE.matcher(line);
                                if (m.find()) {
                                    int pct = Math.min(99, Math.round(Float.parseFloat(m.group(1))));
                                    long downloaded = (totalEstimate * pct) / 100;
                                    emitter.emitProgress(
                                            jobId, pct, downloaded, totalEstimate, 0);
                                }
                            }
                        }

                        int code = process.waitFor();
                        jobs.remove(jobId);
                        if (job.cancelled.get()) return;
                        if (code != 0) {
                            emitter.emitFailed(jobId, "yt-dlp exited with code " + code);
                            return;
                        }

                        File saved = findNewestMedia(tempDir, sanitize(title));
                        if (saved == null) {
                            emitter.emitFailed(jobId, "Downloaded file not found");
                            return;
                        }

                        long size = saved.length();
                        String displayPath;
                        if (treeUri != null) {
                            try {
                                displayPath = SafFileHelper.exportToTree(context, treeUri, saved);
                                //noinspection ResultOfMethodCallIgnored
                                saved.delete();
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
                            //noinspection ResultOfMethodCallIgnored
                            saved.renameTo(dest);
                            displayPath = dest.getAbsolutePath();
                            saved = dest;
                            size = saved.length();
                        }
                        emitter.emitComplete(jobId, displayPath, title, size);
                    } catch (Exception e) {
                        jobs.remove(jobId);
                        emitter.emitFailed(jobId, e.getMessage() != null ? e.getMessage() : "Download failed");
                    }
                });

        JSObject ok = new JSObject();
        ok.put("ok", true);
        call.resolve(ok);
    }

    public void pause(String jobId) {
        YtDlpJob job = jobs.get(jobId);
        if (job == null) return;
        job.paused.set(true);
        if (job.process != null) job.process.destroy();
    }

    public void resume(String jobId) {
        YtDlpJob job = jobs.get(jobId);
        if (job == null) return;
        job.paused.set(false);
        // Re-queue handled from JS layer by calling download again with same metadata.
    }

    public void cancel(String jobId) {
        YtDlpJob job = jobs.get(jobId);
        if (job == null) return;
        job.cancelled.set(true);
        if (job.process != null) job.process.destroy();
        jobs.remove(jobId);
    }

    private List<String> baseCommand() {
        List<String> cmd = new ArrayList<>();
        cmd.add(YtDlpBinaryProvider.getBinaryFile(context).getAbsolutePath());
        cmd.add("--no-warnings");
        cmd.add("--no-check-certificates");
        return cmd;
    }

    private String runAndCapture(List<String> cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = readAll(p.getInputStream());
        int code = p.waitFor();
        if (code != 0) {
            throw new Exception("yt-dlp failed: " + output.trim());
        }
        return output;
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
                entries.put(entryFromJson(e, fallbackUrl, i));
            }
            result.put("batch", true);
        } else {
            entries.put(entryFromJson(root, fallbackUrl, 0));
            result.put("batch", entries.length() > 1);
        }

        result.put("ok", entries.length() > 0);
        result.put("entries", entries);
        return result;
    }

    private JSObject entryFromJson(JSONObject e, String fallbackUrl, int index)
            throws Exception {
        JSObject item = new JSObject();
        String id = e.optString("id", "item_" + index);
        String title = e.optString("title", "media_" + (index + 1));
        String webpage =
                e.optString("webpage_url", e.optString("url", e.optString("original_url", fallbackUrl)));
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

    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "_").substring(0, Math.min(64, name.length()));
    }

    private static String readAll(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) > 0) {
            buf.write(chunk, 0, n);
        }
        return buf.toString("UTF-8");
    }

    private static File findNewestMedia(File dir, String prefix) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        File best = null;
        long bestTime = 0;
        for (File f : files) {
            if (!f.isFile()) continue;
            if (!f.getName().startsWith(prefix)) continue;
            if (f.lastModified() >= bestTime) {
                bestTime = f.lastModified();
                best = f;
            }
        }
        return best;
    }
}
