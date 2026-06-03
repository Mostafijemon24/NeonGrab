package com.neongrab.downloader.ytdlp;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

/** Persists the user-selected Storage Access Framework tree URI. */
public final class DownloadFolderStore {

    private static final String PREFS = "neongrab_download_folder";
    private static final String KEY_URI = "tree_uri";
    private static final String KEY_NAME = "display_name";

    private DownloadFolderStore() {}

    public static boolean isConfigured(Context context) {
        return getTreeUri(context) != null;
    }

    public static Uri getTreeUri(Context context) {
        String raw = prefs(context).getString(KEY_URI, null);
        if (raw == null || raw.isEmpty()) return null;
        return Uri.parse(raw);
    }

    public static String getDisplayName(Context context) {
        return prefs(context).getString(KEY_NAME, "");
    }

    public static void save(Context context, Uri treeUri, String displayName) {
        prefs(context)
                .edit()
                .putString(KEY_URI, treeUri.toString())
                .putString(KEY_NAME, displayName != null ? displayName : "")
                .apply();
    }

    public static void clear(Context context) {
        prefs(context).edit().clear().apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
