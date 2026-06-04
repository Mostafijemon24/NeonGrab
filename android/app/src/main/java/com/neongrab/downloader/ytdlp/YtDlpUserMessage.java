package com.neongrab.downloader.ytdlp;

import java.util.Locale;

/** Short, user-facing text from raw yt-dlp stderr. */
public final class YtDlpUserMessage {

    private YtDlpUserMessage() {}

    public static String fromYtDlp(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        if (s.endsWith(" null")) {
            s = s.substring(0, s.length() - 5).trim();
        }
        if (s.startsWith("Playlist probe failed:")) {
            s = s.substring("Playlist probe failed:".length()).trim();
        }
        if (s.startsWith("ERROR:")) {
            s = s.substring(6).trim();
        }
        int report = s.toLowerCase(Locale.US).indexOf("please report this issue");
        if (report > 0) {
            s = s.substring(0, report).trim();
        }
        String lower = s.toLowerCase(Locale.US);
        if (lower.contains("keyerror")
                && (lower.contains("'title'")
                        || lower.contains("'videomodel'")
                        || lower.contains("videomodel"))) {
            return "xHamster/Porn sites often block extractors. Open Settings → Retry engine on Wi‑Fi, "
                    + "paste a direct video link (remove referral/tracking from the URL).";
        }
        if (lower.contains("no video formats")) {
            return "Site returned no downloadable formats (age gate or region block). "
                    + "Settings → Retry engine on Wi‑Fi. Try VPN, or open the video in browser first.";
        }
        if (lower.contains("extractor error")) {
            return "Could not read this video (site blocked or outdated engine). "
                    + "Settings → Retry engine on Wi‑Fi, then try again.";
        }
        if (s.length() > 280) {
            s = s.substring(0, 280) + "…";
        }
        return s;
    }
}
