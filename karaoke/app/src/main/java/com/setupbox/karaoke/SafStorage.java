package com.setupbox.karaoke;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Storage over the folder the user picked with the system folder picker (ACTION_OPEN_DOCUMENT_TREE).
 * Handles are document URIs inside that tree. Listing uses one ContentResolver query per folder (fast), not DocumentFile.
 */
public final class SafStorage implements Storage {
    private final ContentResolver cr;
    private final Uri tree;

    public SafStorage(Context ctx, Uri tree) {
        this.cr = ctx.getApplicationContext().getContentResolver();
        this.tree = tree;
    }

    public Uri tree() { return tree; }

    @Override public String root() {
        return DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)).toString();
    }

    @Override public List<Entry> list(String dirHandle) {
        List<Entry> out = new ArrayList<>();
        try {
            String docId = DocumentsContract.getDocumentId(Uri.parse(dirHandle));
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
            Cursor c = cr.query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null);
            if (c == null) return out;
            try {
                while (c.moveToNext()) {
                    String id = c.getString(0), name = c.getString(1), mime = c.getString(2);
                    if (id == null || name == null) continue;
                    out.add(new Entry(name, DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(),
                            DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)));
                }
            } finally {
                c.close();
            }
        } catch (Exception ignored) { }        // folder gone / drive unplugged: an empty list, the caller shows a note
        return out;
    }

    @Override public InputStream open(String handle) throws IOException {
        InputStream in = cr.openInputStream(Uri.parse(handle));
        if (in == null) throw new IOException("cannot open " + handle);
        return in;
    }

    @Override public String uri(String handle) { return handle; }

    /** True when the picked folder is on the box's own storage ("primary:..."), false for USB drives and SD cards. */
    public static boolean isInternal(Uri tree) {
        try {
            return Storage.isInternalTreeId(DocumentsContract.getTreeDocumentId(tree));
        } catch (Exception e) {
            return false;
        }
    }
}
