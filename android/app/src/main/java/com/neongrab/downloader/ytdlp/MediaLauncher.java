package com.neongrab.downloader.ytdlp;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.webkit.MimeTypeMap;
import androidx.core.content.FileProvider;
import java.io.File;

public final class MediaLauncher {

    private MediaLauncher() {}

    public static void open(Context context, String openUri, String mimeType) throws Exception {
        if (openUri == null || openUri.isEmpty()) {
            throw new Exception("No file URI to open");
        }

        Uri uri;
        String mime = mimeType;

        if (openUri.startsWith("content://") || openUri.startsWith("file://")) {
            uri = Uri.parse(openUri);
        } else {
            File file = new File(openUri);
            if (!file.exists()) {
                throw new Exception("File not found");
            }
            String authority = context.getPackageName() + ".fileprovider";
            uri = FileProvider.getUriForFile(context, authority, file);
            if (mime == null || mime.isEmpty()) {
                mime = guessMime(file.getName());
            }
        }

        if (mime == null || mime.isEmpty()) {
            mime = "*/*";
        }

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, mime);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try {
            context.startActivity(Intent.createChooser(intent, null));
        } catch (ActivityNotFoundException e) {
            throw new Exception("No app found to open this file type");
        }
    }

    private static String guessMime(String fileName) {
        String ext = MimeTypeMap.getFileExtensionFromUrl(fileName);
        if (ext != null) {
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (mime != null) return mime;
        }
        if (fileName.endsWith(".mp4") || fileName.endsWith(".m4v")) return "video/mp4";
        if (fileName.endsWith(".webm")) return "video/webm";
        if (fileName.endsWith(".mkv")) return "video/x-matroska";
        if (fileName.endsWith(".mp3") || fileName.endsWith(".m4a")) return "audio/mpeg";
        return "application/octet-stream";
    }
}
