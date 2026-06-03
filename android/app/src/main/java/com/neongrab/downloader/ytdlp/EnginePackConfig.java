package com.neongrab.downloader.ytdlp;

import android.content.Context;

/** Remote engine pack (Python + ffmpeg + yt-dlp) downloaded on first launch (lean APK). */
final class EnginePackConfig {

    private static final String RELEASE_TAG = "engine-0.18.1";
    private static final String BASE_URL =
            "https://github.com/Mostafijemon24/NeonGrab/releases/download/" + RELEASE_TAG + "/";

    static final String PACK_VERSION_ARM64 = "0.18.1-arm64";
    static final String PACK_VERSION_X86_64 = "0.18.1-x86_64";
    static final String PACK_FILE_ARM64 = "neongrab-engine-arm64-v0.18.1.zip";
    static final String PACK_FILE_X86_64 = "neongrab-engine-x86_64-v0.18.1.zip";

    /** Expected zip size (~47 MB) — sanity check only. */
    static final long MIN_PACK_BYTES = 35L * 1024 * 1024;

    static final String[] REQUIRED_LIBS = {
        "libpython.so",
        "libpython.zip.so",
        "libffmpeg.so",
        "libffmpeg.zip.so",
        "libffprobe.so",
        "libqjs.so"
    };

    private EnginePackConfig() {}

    static String packVersion(Context context) {
        String primary = EngineAbi.primaryAbi();
        if ("arm64-v8a".equals(primary)) return PACK_VERSION_ARM64;
        if ("x86_64".equals(primary)) return PACK_VERSION_X86_64;
        throw new IllegalStateException(EngineAbi.unsupportedMessage());
    }

    static String downloadUrl(Context context) {
        String primary = EngineAbi.primaryAbi();
        if ("arm64-v8a".equals(primary)) return BASE_URL + PACK_FILE_ARM64;
        if ("x86_64".equals(primary)) return BASE_URL + PACK_FILE_X86_64;
        throw new IllegalStateException(EngineAbi.unsupportedMessage());
    }
}
