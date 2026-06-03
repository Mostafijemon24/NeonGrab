package com.neongrab.downloader.ytdlp;

/** Reports download-engine setup progress to the Capacitor layer. */
public interface YtDlpSetupProgress {
    void onProgress(int percent, String message);
}
