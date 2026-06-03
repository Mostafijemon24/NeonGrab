package com.neongrab.downloader.ytdlp;

/** Maps UI quality presets to yt-dlp -f format strings (YouTube-friendly, MP4 output). */
public final class YtDlpFormatSelector {

    private YtDlpFormatSelector() {}

    public static String formatForQuality(String quality, boolean audioOnly) {
        if (audioOnly || "audio".equals(quality)) {
            return "bestaudio[ext=m4a]/bestaudio/bestaudio";
        }
        switch (quality) {
            case "1080":
                return "bestvideo[height<=1080][ext=mp4]+bestaudio[ext=m4a]/best[height<=1080][ext=mp4]/best[height<=1080]";
            case "720":
                return "bestvideo[height<=720][ext=mp4]+bestaudio[ext=m4a]/best[height<=720][ext=mp4]/best[height<=720]";
            case "480":
                return "bestvideo[height<=480][ext=mp4]+bestaudio[ext=m4a]/best[height<=480][ext=mp4]/best[height<=480]";
            case "auto":
            default:
                return "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/bestvideo+bestaudio/best";
        }
    }

    public static String mergeFormat() {
        return "mp4";
    }
}
