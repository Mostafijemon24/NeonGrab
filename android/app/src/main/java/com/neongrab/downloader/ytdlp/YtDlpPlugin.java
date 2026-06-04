package com.neongrab.downloader.ytdlp;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import androidx.activity.result.ActivityResult;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "YtDlp")
public class YtDlpPlugin extends Plugin {

    private static final String PICK_FOLDER_CALLBACK = "pickDownloadFolder";

    private YtDlpExecutor executor;

    @Override
    public void load() {
        super.load();
        YtDlpEngineHelper.setProgressListener(
                (percent, message) -> emitEngineSetupProgress(percent, message));
        warmUpEngineIfPackOnDisk();
        executor =
                new YtDlpExecutor(
                        getContext(),
                        new YtDlpExecutor.EventEmitter() {
                            @Override
                            public void emitProgress(
                                    String jobId,
                                    int progress,
                                    long downloaded,
                                    long total,
                                    long speedBps) {
                                JSObject data = new JSObject();
                                data.put("jobId", jobId);
                                data.put("progress", progress);
                                data.put("downloadedBytes", downloaded);
                                data.put("totalBytes", total);
                                data.put("speedBps", speedBps);
                                notifyListeners("downloadProgress", data);
                            }

                            @Override
                            public void emitComplete(
                                    String jobId,
                                    String filePath,
                                    String openUri,
                                    String mimeType,
                                    String title,
                                    long totalBytes) {
                                JSObject data = new JSObject();
                                data.put("jobId", jobId);
                                data.put("filePath", filePath);
                                data.put("openUri", openUri);
                                data.put("mimeType", mimeType);
                                data.put("title", title);
                                data.put("totalBytes", totalBytes);
                                notifyListeners("downloadComplete", data);
                            }

                            @Override
                            public void emitFailed(String jobId, String message) {
                                JSObject data = new JSObject();
                                data.put("jobId", jobId);
                                data.put("message", message);
                                notifyListeners("downloadFailed", data);
                            }
                        });
    }

    @Override
    protected void handleOnDestroy() {
        YtDlpEngineHelper.setProgressListener(null);
        if (executor != null) executor.shutdown();
        super.handleOnDestroy();
    }

    private void emitEngineSetupProgress(int progress, String message) {
        JSObject data = new JSObject();
        data.put("progress", progress);
        data.put("message", message);
        notifyListeners("engineSetupProgress", data);
    }

    private void emitEngineSetupComplete() {
        JSObject done = new JSObject();
        done.put("ready", true);
        notifyListeners("engineSetupComplete", done);
    }

    private void emitEngineSetupFailed(String message) {
        JSObject fail = new JSObject();
        fail.put("message", message);
        notifyListeners("engineSetupFailed", fail);
    }

    @PluginMethod
    public void isAvailable(PluginCall call) {
        Context ctx = getContext();
        boolean installed = YtDlpBinaryProvider.isInstalled(ctx);
        boolean packOnDisk =
                EngineNativeLoader.apkBundledNativesPresent(ctx)
                        || EnginePackDownloader.isInstalled(ctx);
        JSObject ret = new JSObject();
        ret.put("available", installed);
        ret.put("packInstalled", packOnDisk);
        ret.put("initializing", YtDlpEngineHelper.isSetupInProgress());
        if (installed) {
            ret.put("version", YtDlpBinaryProvider.getVersion(ctx));
            ret.put("binaryPath", "bundled");
        } else {
            String err = YtDlpEngineHelper.getLastError();
            if (YtDlpEngineHelper.isSetupInProgress()) {
                ret.put(
                        "message",
                        packOnDisk
                                ? "Loading download engine…"
                                : "Installing download engine…");
            } else if (err != null && !err.isEmpty()) {
                ret.put("message", err);
            } else if (packOnDisk) {
                ret.put("message", "Download engine needs a moment to load");
            } else {
                ret.put("message", "Tap Setup engine below (needs Wi‑Fi or mobile data)");
            }
        }
        call.resolve(ret);
    }

    @PluginMethod
    public void installBinaryFromAssets(PluginCall call) {
        YtDlpBinaryProvider.InstallResult result =
                YtDlpBinaryProvider.installFromAssets(getContext());
        JSObject ret = new JSObject();
        ret.put("ok", result.ok);
        if (!result.ok) ret.put("message", result.message);
        else ret.put("path", result.path);
        call.resolve(ret);
    }

    @PluginMethod
    public void ensureEngine(PluginCall call) {
        boolean retry = Boolean.TRUE.equals(call.getBoolean("retry", false));
        poolExecute(
                call,
                () -> {
                    YtDlpBinaryProvider.InstallResult result =
                            retry
                                    ? YtDlpBinaryProvider.retryInstall(getContext())
                                    : YtDlpBinaryProvider.ensureInstalled(getContext());
                    JSObject ret = new JSObject();
                    ret.put("ok", result.ok);
                    if (!result.ok) {
                        ret.put("message", result.message);
                        emitEngineSetupFailed(
                                result.message != null
                                        ? result.message
                                        : "Engine setup failed");
                    } else {
                        ret.put("path", result.path);
                        ret.put("version", YtDlpBinaryProvider.getVersion(getContext()));
                        emitEngineSetupProgress(100, "Download engine ready");
                        emitEngineSetupComplete();
                    }
                    call.resolve(ret);
                });
    }

    private final java.util.concurrent.ExecutorService setupPool =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    /** If a previous session downloaded the pack, init before the WebView setup UI runs. */
    private void warmUpEngineIfPackOnDisk() {
        setupPool.execute(
                () -> {
                    try {
                        Context ctx = getContext();
                        if (YtDlpEngineHelper.isReady(ctx)) return;
                        if (!EnginePackDownloader.isInstalled(ctx)
                                && !EngineNativeLoader.apkBundledNativesPresent(ctx)) {
                            return;
                        }
                        YtDlpBinaryProvider.ensureInstalled(ctx);
                    } catch (Throwable ignored) {
                        /* UI layer will retry with progress */
                    }
                });
    }

    private void poolExecute(PluginCall call, Runnable task) {
        setupPool.execute(
                () -> {
                    try {
                        task.run();
                    } catch (Throwable e) {
                        String msg = e.getMessage();
                        call.reject(
                                msg != null ? msg : "Engine setup failed",
                                "ENGINE_SETUP_FAILED");
                    }
                });
    }

    @PluginMethod
    public void getDownloadFolder(PluginCall call) {
        JSObject ret = new JSObject();
        if (!DownloadFolderStore.isConfigured(getContext())) {
            ret.put("configured", false);
            ret.put("displayName", "");
            ret.put("uri", "");
            call.resolve(ret);
            return;
        }
        ret.put("configured", true);
        ret.put("displayName", DownloadFolderStore.getDisplayName(getContext()));
        ret.put("uri", DownloadFolderStore.getTreeUri(getContext()).toString());
        call.resolve(ret);
    }

    @PluginMethod
    public void pickDownloadFolder(PluginCall call) {
        if (getActivity() == null) {
            call.reject("Cannot open folder picker", "NO_ACTIVITY");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(call, intent, PICK_FOLDER_CALLBACK);
    }

    @ActivityCallback
    private void pickDownloadFolder(PluginCall call, ActivityResult result) {
        if (call == null) return;
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
            call.reject("Folder not selected", "CANCELLED");
            return;
        }
        Uri treeUri = result.getData().getData();
        if (treeUri == null) {
            call.reject("No folder URI returned", "NO_URI");
            return;
        }

        SafFileHelper.takePersistablePermission(getContext(), treeUri);
        String displayName = SafFileHelper.resolveDisplayName(getContext(), treeUri);
        DownloadFolderStore.save(getContext(), treeUri, displayName);

        JSObject ret = new JSObject();
        ret.put("configured", true);
        ret.put("displayName", displayName);
        ret.put("uri", treeUri.toString());
        call.resolve(ret);
    }

    @PluginMethod
    public void probe(PluginCall call) {
        String url = call.getString("url");
        if (url == null || url.isEmpty()) {
            call.reject("url is required", "INVALID_URL");
            return;
        }
        boolean flat = Boolean.TRUE.equals(call.getBoolean("flatPlaylist", false));
        executor.probe(call, url, flat);
    }

    @PluginMethod
    public void extractViaWebView(PluginCall call) {
        String url = call.getString("url");
        if (url == null || url.isEmpty()) {
            call.reject("url is required", "INVALID_URL");
            return;
        }

        WebViewExtractor.extract(getContext(), url, new WebViewExtractor.Callback() {
            @Override
            public void onSuccess(String streamUrl, String title) {
                JSObject ret = new JSObject();
                ret.put("streamUrl", streamUrl);
                ret.put("title", title);
                call.resolve(ret);
            }

            @Override
            public void onError(String error) {
                call.reject(error, "WEBVIEW_EXTRACT_ERROR");
            }
        });
    }

    @PluginMethod
    public void download(PluginCall call) {
        String jobId = call.getString("jobId");
        String url = call.getString("url");
        String title = call.getString("title", "media");
        String quality = call.getString("quality", "auto");
        String kind = call.getString("kind", "video");
        Integer maxThreads = call.getInt("maxThreads", 4);

        if (jobId == null || url == null) {
            call.reject("jobId and url are required", "INVALID_ARGS");
            return;
        }

        boolean audioOnly = "audio".equals(kind);
        executor.startDownload(call, jobId, url, title, quality, audioOnly, maxThreads);
    }

    @PluginMethod
    public void pause(PluginCall call) {
        String jobId = call.getString("jobId");
        if (jobId != null) executor.pause(jobId);
        call.resolve();
    }

    @PluginMethod
    public void resume(PluginCall call) {
        String jobId = call.getString("jobId");
        if (jobId != null) executor.resume(jobId);
        call.resolve();
    }

    @PluginMethod
    public void cancel(PluginCall call) {
        String jobId = call.getString("jobId");
        if (jobId != null) executor.cancel(jobId);
        call.resolve();
    }

    @PluginMethod
    public void openMediaFile(PluginCall call) {
        String openUri = call.getString("openUri");
        String filePath = call.getString("filePath");
        String mimeType = call.getString("mimeType", "");

        String target = openUri != null && !openUri.isEmpty() ? openUri : filePath;
        if (target == null || target.isEmpty()) {
            call.reject("No file to open", "NO_FILE");
            return;
        }

        try {
            android.content.Context ctx = getActivity() != null ? getActivity() : getContext();
            MediaLauncher.open(ctx, target, mimeType);
            call.resolve();
        } catch (Exception e) {
            call.reject(
                    e.getMessage() != null ? e.getMessage() : "Could not open file",
                    "OPEN_FAILED",
                    e);
        }
    }
}
