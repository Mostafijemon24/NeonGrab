package com.neongrab.downloader.ytdlp;

import java.util.concurrent.atomic.AtomicBoolean;

public class YtDlpJob {

    public final String jobId;
    public final String url;
    public final String title;
    public volatile Process process;
    public volatile Thread worker;
    public final AtomicBoolean paused = new AtomicBoolean(false);
    public final AtomicBoolean cancelled = new AtomicBoolean(false);

    public YtDlpJob(String jobId, String url, String title) {
        this.jobId = jobId;
        this.url = url;
        this.title = title;
    }
}
