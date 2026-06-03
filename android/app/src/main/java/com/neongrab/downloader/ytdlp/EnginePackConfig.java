package com.neongrab.downloader.ytdlp;

/** Remote engine pack (Python + ffmpeg + yt-dlp runtime) downloaded on first launch. */
final class EnginePackConfig {

    static final String PACK_VERSION = "0.18.1-arm64";
    static final String PACK_FILE = "neongrab-engine-arm64-v0.18.1.zip";
    static final String DOWNLOAD_URL =
            "https://github.com/Mostafijemon24/NeonGrab/releases/download/engine-0.18.1/"
                    + PACK_FILE;
    /** Expected zip size (~47 MB) — used for sanity check only. */
    static final long MIN_PACK_BYTES = 40L * 1024 * 1024;

    static final String[] REQUIRED_LIBS = {
        "libpython.so",
        "libpython.zip.so",
        "libffmpeg.so",
        "libffmpeg.zip.so",
        "libffprobe.so",
        "libqjs.so"
    };

    private EnginePackConfig() {}
}
