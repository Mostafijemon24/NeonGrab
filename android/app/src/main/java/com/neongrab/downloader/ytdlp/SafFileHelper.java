package com.neongrab.downloader.ytdlp;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.webkit.MimeTypeMap;
import androidx.documentfile.provider.DocumentFile;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

public final class SafFileHelper {

    private SafFileHelper() {}

    public static String resolveDisplayName(Context context, Uri treeUri) {
        try {
            Uri docUri = DocumentsContract.buildDocumentUriUsingTree(
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

    /**
     * Copies a finished download into the user-picked folder. Returns a display path for UI.
     */
    public static String exportToTree(Context context, Uri treeUri, File sourceFile)
            throws Exception {
        if (sourceFile == null || !sourceFile.exists()) {
            throw new Exception("Downloaded file not found");
        }

        DocumentFile tree = DocumentFile.fromTreeUri(context, treeUri);
        if (tree == null || !tree.canWrite()) {
            throw new Exception("Selected folder is not writable");
        }

        String mime = guessMime(sourceFile.getName());
        DocumentFile dest = tree.createFile(mime, stripExtension(sourceFile.getName()));
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

        String folderName = DownloadFolderStore.getDisplayName(context);
        return folderName.isEmpty() ? dest.getName() : folderName + "/" + dest.getName();
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot > 0) return name.substring(0, dot);
        return name;
    }

    private static String guessMime(String fileName) {
        String ext = MimeTypeMap.getFileExtensionFromUrl(fileName);
        if (ext != null) {
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (mime != null) return mime;
        }
        return "application/octet-stream";
    }
}
