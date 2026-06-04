package com.neongrab.downloader.ytdlp;

import java.util.Locale;

/** Maps UI quality presets to yt-dlp -f format strings (single-file first, no ffmpeg merge). */
public final class YtDlpFormatSelector {

    private YtDlpFormatSelector() {}

    /**
     * Prefer already-muxed MP4/M4A streams so yt-dlp does not need ffmpeg to merge video+audio.
     * Slash-separated fallbacks never use {@code video+audio} merge syntax.
     */
    public static String formatForQuality(String quality, boolean audioOnly) {
        return formatForQuality(quality, audioOnly, null);
    }

    public static String formatForQuality(String quality, boolean audioOnly, String sourceUrl) {
        if (audioOnly || "audio".equals(quality)) {
            return "bestaudio[ext=m4a]/bestaudio[ext=webm]/bestaudio/best[ext=m4a]/best";
        }
        if (sourceUrl != null
                && YtDlpEngineHelper.isAdultSiteUrl(sourceUrl.toLowerCase(Locale.US))) {
            /* Progressive HTTPS first; strict height/mp4 filters often match nothing on tube sites */
            return "best[ext=mp4]/best/bestvideo+bestaudio/best";
        }
        switch (quality) {
            case "1080":
                return "best[height<=1080][ext=mp4]/best[height<=1080]/best[ext=mp4]/best";
            case "720":
                return "best[height<=720][ext=mp4]/best[height<=720]/best[ext=mp4]/best";
            case "480":
                return "best[height<=480][ext=mp4]/best[height<=480]/best[ext=mp4]/best";
            case "auto":
            default:
                return "best[ext=mp4]/best[height<=1080]/best";
        }
    }

    /** Empty = do not pass --merge-output-format (avoids forced remux/merge). */
    public static String mergeFormat() {
        return "";
    }
}
