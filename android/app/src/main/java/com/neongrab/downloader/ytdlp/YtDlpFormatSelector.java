package com.neongrab.downloader.ytdlp;

/** Maps UI quality presets to yt-dlp -f format strings (YouTube-friendly, MP4 output). */
public final class YtDlpFormatSelector {

    private YtDlpFormatSelector() {}

    public static String formatForQuality(String quality, boolean audioOnly) {
        if (audioOnly || "audio".equals(quality)) {
            return "bestaudio[ext=m4a]/bestaudio[ext=mp3]/bestaudio";
        }
        switch (quality) {
            case "1080":
                return "best[height<=1080][ext=mp4]/best[height<=1080]/bv*+ba/b";
            case "720":
                return "best[height<=720][ext=mp4]/best[height<=720]/bv*+ba/b";
            case "480":
                return "best[height<=480][ext=mp4]/best[height<=480]/bv*+ba/b";
            case "auto":
            default:
                return "best[ext=mp4]/bestvideo[ext=mp4]+bestaudio[ext=m4a]/bestvideo+bestaudio/best";
        }
    }

    public static String mergeFormat() {
        return "mp4";
    }
}
