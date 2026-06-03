package com.neongrab.downloader.ytdlp;

import android.app.Activity;
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
        if (executor != null) executor.shutdown();
        super.handleOnDestroy();
    }

    @PluginMethod
    public void isAvailable(PluginCall call) {
        boolean installed = YtDlpBinaryProvider.isInstalled(getContext());
        JSObject ret = new JSObject();
        ret.put("available", installed);
        if (installed) {
            ret.put("version", YtDlpBinaryProvider.getVersion(getContext()));
            ret.put("binaryPath", "bundled");
        } else {
            ret.put("message", "Download engine will install on first download");
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
        poolExecute(
                call,
                () -> {
                    YtDlpBinaryProvider.InstallResult result =
                            YtDlpBinaryProvider.ensureInstalled(getContext());
                    JSObject ret = new JSObject();
                    ret.put("ok", result.ok);
                    if (!result.ok) ret.put("message", result.message);
                    else {
                        ret.put("path", result.path);
                        ret.put("version", YtDlpBinaryProvider.getVersion(getContext()));
                    }
                    call.resolve(ret);
                });
    }

    private final java.util.concurrent.ExecutorService setupPool =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    private void poolExecute(PluginCall call, Runnable task) {
        setupPool.execute(
                () -> {
                    try {
                        task.run();
                    } catch (Exception e) {
                        call.reject("ENGINE_SETUP_FAILED", e.getMessage(), e);
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
            call.reject("NO_ACTIVITY", "Cannot open folder picker");
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
            call.reject("CANCELLED", "Folder not selected");
            return;
        }
        Uri treeUri = result.getData().getData();
        if (treeUri == null) {
            call.reject("NO_URI", "No folder URI returned");
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
            call.reject("INVALID_URL", "url is required");
            return;
        }
        boolean flat = Boolean.TRUE.equals(call.getBoolean("flatPlaylist", false));
        executor.probe(call, url, flat);
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
            call.reject("INVALID_ARGS", "jobId and url are required");
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
            call.reject("NO_FILE", "No file to open");
            return;
        }

        try {
            android.content.Context ctx = getActivity() != null ? getActivity() : getContext();
            MediaLauncher.open(ctx, target, mimeType);
            call.resolve();
        } catch (Exception e) {
            call.reject("OPEN_FAILED", e.getMessage(), e);
        }
    }
}
