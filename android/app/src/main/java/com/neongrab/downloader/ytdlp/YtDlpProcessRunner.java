package com.neongrab.downloader.ytdlp;

import android.content.Context;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.YoutubeDLResponse;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Runs yt-dlp via ProcessBuilder; uses linker64 when engine was downloaded (not from APK). */
final class YtDlpProcessRunner {

    private static final Pattern PROGRESS_RE =
            Pattern.compile("\\[download\\]\\s+(\\d+(?:\\.\\d+)?)%");

    private YtDlpProcessRunner() {}

    static YoutubeDLResponse execute(
            Context context,
            YoutubeDLRequest request,
            String processId,
            boolean redirectErrorStream,
            YtDlpEngineHelper.YtDlpProgressHandler progressHandler)
            throws Exception {
        Context app = context.getApplicationContext();
        FfmpegBinaryHelper.ensurePatched(app);
        Object ytdlp = YtDlpReflection.youtubeDlInstance();
        YtDlpReflection.invoke(ytdlp, "assertInit", new Class[0]);

        File binDir = EngineNativeLoader.resolveBinDir(app);
        if (binDir == null) {
            throw new Exception("Engine binaries not ready");
        }

        File pythonPath = YtDlpReflection.getFileField(ytdlp, "pythonPath");
        File ytdlpPath = YtDlpReflection.getFileField(ytdlp, "ytdlpPath");
        File ffmpegPath = YtDlpReflection.getFileField(ytdlp, "ffmpegPath");

        if (pythonPath == null || !pythonPath.isFile()) {
            throw new Exception("Python runtime missing: " + pythonPath);
        }
        if (ytdlpPath == null || !ytdlpPath.isFile()) {
            throw new Exception("yt-dlp binary missing: " + ytdlpPath);
        }

        request.addOption(
                "--js-runtimes",
                "quickjs:" + FfmpegBinaryHelper.quickJsExecutable(app).getAbsolutePath());
        if (ffmpegPath != null && ffmpegPath.exists()) {
            request.addOption(
                    "--ffmpeg-location",
                    ffmpegPath.isDirectory()
                            ? ffmpegPath.getAbsolutePath()
                            : ffmpegPath.getParent());
        }

        List<String> args = request.buildCommand();
        List<String> command = buildCommand(app, binDir, pythonPath, ytdlpPath, args);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(redirectErrorStream);
        applyEnv(pb, ytdlp, binDir);

        if (processId != null) {
            Map<String, Process> map = YtDlpReflection.getProcessMap(ytdlp);
            if (map.containsKey(processId)) {
                throw new Exception("Process ID already exists");
            }
        }

        long start = System.currentTimeMillis();
        Process process;
        try {
            process = pb.start();
        } catch (Exception e) {
            throw new Exception(
                    "Cannot start download engine: "
                            + e.getMessage()
                            + ". Try Retry in engine setup.",
                    e);
        }

        if (processId != null) {
            YtDlpReflection.getProcessMap(ytdlp).put(processId, process);
        }

        StringBuilder outBuf = new StringBuilder();
        StringBuilder errBuf = new StringBuilder();
        Thread outThread =
                new Thread(
                        () ->
                                readStream(
                                        process.getInputStream(),
                                        outBuf,
                                        progressHandler),
                        "ytdlp-stdout");
        Thread errThread =
                redirectErrorStream
                        ? outThread
                        : new Thread(
                                () -> readStream(process.getErrorStream(), errBuf, null),
                                "ytdlp-stderr");
        outThread.start();
        if (!redirectErrorStream) errThread.start();

        int exitCode;
        try {
            outThread.join();
            if (!redirectErrorStream) errThread.join();
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            process.destroy();
            if (processId != null) YtDlpReflection.getProcessMap(ytdlp).remove(processId);
            throw e;
        }

        if (processId != null) YtDlpReflection.getProcessMap(ytdlp).remove(processId);

        String out = outBuf.toString();
        String err = errBuf.toString();
        if (exitCode != 0 && (out == null || out.trim().isEmpty())) {
            String errMsg =
                    err != null && !err.trim().isEmpty() ? err.trim() : "yt-dlp failed";
            if (EngineAbi.isAbiMismatchMessage(errMsg)) {
                YtDlpEngineHelper.resetEngineState(app);
            }
            throw new Exception(EngineAbi.friendlyError(errMsg));
        }

        long elapsed = System.currentTimeMillis() - start;
        return new YoutubeDLResponse(command, exitCode, elapsed, out, err);
    }

    private static List<String> buildCommand(
            Context app, File binDir, File pythonPath, File ytdlpPath, List<String> args) {
        List<String> command = new ArrayList<>();
        if (EngineNativeLoader.useLinker(app, binDir)) {
            command.add(EngineNativeLoader.linkerPath());
        }
        command.add(pythonPath.getAbsolutePath());
        command.add(ytdlpPath.getAbsolutePath());
        command.addAll(args);
        return command;
    }

    private static void applyEnv(ProcessBuilder pb, Object ytdlp, File binDir) throws Exception {
        String ld = YtDlpReflection.getStringField(ytdlp, "ENV_LD_LIBRARY_PATH");
        String ssl = YtDlpReflection.getStringField(ytdlp, "ENV_SSL_CERT_FILE");
        String pythonHome = YtDlpReflection.getStringField(ytdlp, "ENV_PYTHONHOME");
        String tmp = YtDlpReflection.getStringField(ytdlp, "TMPDIR");
        pb.environment()
                .put("LD_LIBRARY_PATH", ld != null ? ld : binDir.getAbsolutePath());
        if (ssl != null) pb.environment().put("SSL_CERT_FILE", ssl);
        if (pythonHome != null) {
            pb.environment().put("PYTHONHOME", pythonHome);
            pb.environment().put("HOME", pythonHome);
        }
        if (tmp != null) pb.environment().put("TMPDIR", tmp);
        String path = System.getenv("PATH");
        pb.environment()
                .put(
                        "PATH",
                        (path != null ? path : "")
                                + ":"
                                + binDir.getAbsolutePath()
                                + ":/system/bin");
    }

    private static void readStream(
            InputStream stream,
            StringBuilder buffer,
            YtDlpEngineHelper.YtDlpProgressHandler handler) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                buffer.append(line).append('\n');
                if (handler == null) continue;
                Matcher m = PROGRESS_RE.matcher(line);
                if (m.find()) {
                    float pct = Float.parseFloat(m.group(1));
                    handler.onProgress(pct, 0, line);
                } else {
                    handler.onProgress(0, 0, line);
                }
            }
        } catch (Exception ignored) {
            /* ignore */
        }
    }
}
