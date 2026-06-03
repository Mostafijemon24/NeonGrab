package com.neongrab.downloader.ytdlp;

import android.content.Context;
import android.os.Environment;
import java.io.File;

/** Resolves writable download directories for preset and custom paths. */
public final class YtDlpDownloadPathResolver {

    public static final String FOLDER_NAME = "NeonGrab";

    private YtDlpDownloadPathResolver() {}

    public static File resolve(Context context, String preset, String customPath) {
        File base;
        if ("custom".equals(preset)) {
            if (customPath == null || customPath.trim().isEmpty()) {
                base = defaultAppDir(context);
            } else {
                base = new File(customPath.trim());
            }
        } else if ("downloads".equals(preset)) {
            base = publicDir(Environment.DIRECTORY_DOWNLOADS, context);
        } else if ("movies".equals(preset)) {
            base = publicDir(Environment.DIRECTORY_MOVIES, context);
        } else if ("music".equals(preset)) {
            base = publicDir(Environment.DIRECTORY_MUSIC, context);
        } else {
            base = defaultAppDir(context);
        }

        File target = new File(base, FOLDER_NAME);
        if (!target.exists() && !target.mkdirs()) {
            return defaultNeonGrabDir(context);
        }
        if (!target.canWrite()) {
            return defaultNeonGrabDir(context);
        }
        return target;
    }

    public static File defaultNeonGrabDir(Context context) {
        File dir = new File(defaultAppDir(context), FOLDER_NAME);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static File defaultAppDir(Context context) {
        File files = context.getExternalFilesDir(null);
        if (files == null) files = context.getFilesDir();
        return files;
    }

    private static File publicDir(String type, Context context) {
        File pub = Environment.getExternalStoragePublicDirectory(type);
        if (pub == null || !Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            return defaultAppDir(context);
        }
        return pub;
    }

    public static boolean isWritable(File dir) {
        if (dir == null) return false;
        if (!dir.exists() && !dir.mkdirs()) return false;
        return dir.canWrite();
    }
}
