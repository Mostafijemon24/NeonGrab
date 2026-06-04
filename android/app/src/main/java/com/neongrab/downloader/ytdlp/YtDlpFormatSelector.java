package com.neongrab.downloader.ytdlp;

import java.util.Locale;

/** Maps UI quality presets to yt-dlp -f format strings and merge options per site. */
public final class YtDlpFormatSelector {

    public static final class FormatPlan {
        public final String format;
        public final String mergeFormat;

        public FormatPlan(String format, String mergeFormat) {
            this.format = format;
            this.mergeFormat = mergeFormat != null ? mergeFormat : "";
        }
    }

    private YtDlpFormatSelector() {}

    public static String formatForQuality(String quality, boolean audioOnly) {
        return formatForQuality(quality, audioOnly, null);
    }

    public static String formatForQuality(String quality, boolean audioOnly, String sourceUrl) {
        FormatPlan[] plans = downloadPlans(quality, audioOnly, sourceUrl);
        return plans.length > 0 ? plans[0].format : "best";
    }

    public static String mergeFormat() {
        return "";
    }

    public static String mergeFormatForUrl(String sourceUrl) {
        if (sourceUrl == null) return "";
        String lower = sourceUrl.toLowerCase(Locale.US);
        if (YtDlpEngineHelper.isXhamsterUrl(lower) || needsFfmpegMerge(lower)) {
            return "mp4";
        }
        return "";
    }

    /** Ordered attempts: progressive first, then HLS merge, then plain best. */
    public static FormatPlan[] downloadPlans(String quality, boolean audioOnly, String sourceUrl) {
        if (audioOnly || "audio".equals(quality)) {
            return new FormatPlan[] {
                new FormatPlan(
                        "bestaudio[ext=m4a]/bestaudio[ext=webm]/bestaudio/best[ext=m4a]/best", "")
            };
        }
        if (sourceUrl == null) {
            return defaultVideoPlans(quality);
        }
        String lower = sourceUrl.toLowerCase(Locale.US);
        if (YtDlpEngineHelper.isXhamsterUrl(lower)) {
            return xhamsterPlans();
        }
        if (YtDlpEngineHelper.isAdultSiteUrl(lower)) {
            return adultPlans();
        }
        return defaultVideoPlans(quality);
    }

    private static FormatPlan[] xhamsterPlans() {
        return new FormatPlan[] {
            /* Prefer muxed HLS — xHamster progressive URLs often fail decipher in 2026 */
            new FormatPlan(
                    "best[protocol^=m3u8][protocol!=m3u8_dash]/best[protocol*=m3u8]/best", "mp4"),
            new FormatPlan("bestvideo+bestaudio/best[protocol^=m3u8]/best", "mp4"),
            new FormatPlan("best[ext=mp4]/best[protocol^=http]/best", "mp4"),
            new FormatPlan("bestvideo*+bestaudio/best", "mp4"),
            new FormatPlan("best", "mp4"),
            new FormatPlan("worst", ""),
        };
    }

    private static FormatPlan[] adultPlans() {
        return new FormatPlan[] {
            new FormatPlan("best[ext=mp4]/best[protocol^=http]/best", "mp4"),
            new FormatPlan("bestvideo+bestaudio/best", "mp4"),
            new FormatPlan("best", "mp4"),
            new FormatPlan("worst", ""),
        };
    }

    private static FormatPlan[] defaultVideoPlans(String quality) {
        String primary;
        switch (quality) {
            case "1080":
                primary = "best[height<=1080][ext=mp4]/best[height<=1080]/best[ext=mp4]/best";
                break;
            case "720":
                primary = "best[height<=720][ext=mp4]/best[height<=720]/best[ext=mp4]/best";
                break;
            case "480":
                primary = "best[height<=480][ext=mp4]/best[height<=480]/best[ext=mp4]/best";
                break;
            case "auto":
            default:
                primary = "best[ext=mp4]/best[height<=1080]/best";
                break;
        }
        return new FormatPlan[] {
            new FormatPlan(primary, ""),
            new FormatPlan("bestvideo+bestaudio/best", "mp4"),
            new FormatPlan("best", ""),
        };
    }

    private static boolean needsFfmpegMerge(String lowerUrl) {
        return lowerUrl.contains("pornhub.") || lowerUrl.contains("xvideos.");
    }
}
