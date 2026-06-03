package com.neongrab.downloader.ytdlp;

/** Result of saving a download to user storage. */
public final class SavedFileResult {
    public final String displayPath;
    public final String openUri;
    public final String mimeType;

    public SavedFileResult(String displayPath, String openUri, String mimeType) {
        this.displayPath = displayPath;
        this.openUri = openUri;
        this.mimeType = mimeType;
    }
}
