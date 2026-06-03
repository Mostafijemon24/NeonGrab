package com.neongrab.downloader.ytdlp;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.webkit.MimeTypeMap;
import androidx.core.content.FileProvider;
import androidx.documentfile.provider.DocumentFile;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

public final class SafFileHelper {

    private SafFileHelper() {}

    public static String resolveDisplayName(Context context, Uri treeUri) {
        try {
            Uri docUri =
                    DocumentsContract.buildDocumentUriUsingTree(
                            treeUri, DocumentsContract.getTreeDocumentId(treeUri));
            String[] projection = {DocumentsContract.Document.COLUMN_DISPLAY_NAME};
            try (Cursor c =
                    context.getContentResolver()
                            .query(docUri, projection, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int idx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                    if (idx >= 0) {
                        String name = c.getString(idx);
                        if (name != null && !name.isEmpty()) return name;
                    }
                }
            }
        } catch (Exception ignored) {
            /* fall through */
        }
        return "Selected folder";
    }

    public static void takePersistablePermission(Context context, Uri treeUri) {
        final int flags =
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        try {
            context.getContentResolver().takePersistableUriPermission(treeUri, flags);
        } catch (SecurityException ignored) {
            /* some providers may not support persistable grants */
        }
    }

    public static SavedFileResult exportToTree(Context context, Uri treeUri, File sourceFile)
            throws Exception {
        if (sourceFile == null || !sourceFile.exists() || sourceFile.length() == 0) {
            throw new Exception("Downloaded file not found or empty");
        }

        DocumentFile tree = DocumentFile.fromTreeUri(context, treeUri);
        if (tree == null || !tree.canWrite()) {
            throw new Exception("Selected folder is not writable");
        }

        String fileName = sourceFile.getName();
        String mime = guessMime(fileName);
        String base = baseNameWithoutExtension(fileName);
        String ext = extensionOf(fileName);

        DocumentFile dest = tree.createFile(mime, base);
        if (dest == null) {
            throw new Exception("Could not create file in selected folder");
        }

        ContentResolver resolver = context.getContentResolver();
        try (InputStream in = new FileInputStream(sourceFile);
                OutputStream out = resolver.openOutputStream(dest.getUri())) {
            if (out == null) throw new Exception("Could not open output stream");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
        }

        String savedName = dest.getName();
        if (savedName == null || savedName.isEmpty()) {
            savedName = base + (ext.isEmpty() ? "" : "." + ext);
        } else if (!savedName.contains(".") && !ext.isEmpty()) {
            savedName = savedName + "." + ext;
        }

        String folderName = DownloadFolderStore.getDisplayName(context);
        String displayPath =
                folderName.isEmpty() ? savedName : folderName + "/" + savedName;

        return new SavedFileResult(displayPath, dest.getUri().toString(), mime);
    }

    public static SavedFileResult fromLocalFile(Context context, File file) throws Exception {
        String authority = context.getPackageName() + ".fileprovider";
        Uri uri = FileProvider.getUriForFile(context, authority, file);
        String mime = guessMime(file.getName());
        return new SavedFileResult(file.getAbsolutePath(), uri.toString(), mime);
    }

    private static String baseNameWithoutExtension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot > 0) return name.substring(0, dot);
        return name;
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) return name.substring(dot + 1);
        return "";
    }

    private static String guessMime(String fileName) {
        String ext = extensionOf(fileName);
        if (!ext.isEmpty()) {
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (mime != null) return mime;
        }
        if ("mp4".equalsIgnoreCase(ext) || "m4v".equalsIgnoreCase(ext)) return "video/mp4";
        if ("webm".equalsIgnoreCase(ext)) return "video/webm";
        if ("mkv".equalsIgnoreCase(ext)) return "video/x-matroska";
        if ("m4a".equalsIgnoreCase(ext) || "mp3".equalsIgnoreCase(ext)) return "audio/mpeg";
        return "application/octet-stream";
    }
}
