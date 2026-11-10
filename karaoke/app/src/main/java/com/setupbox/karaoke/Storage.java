package com.setupbox.karaoke;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Read-only view of the user's Karaoke folder. The app never touches file paths directly: with the Storage Access
 * Framework (SafStorage) the folder is the one the user picked once with the system folder picker; FileStorage is the
 * same thing over java.io.File (tests, and the single-file debug mode).
 * A "handle" is an opaque string that identifies a folder or file inside the storage (a document URI or a path).
 */
public interface Storage {
    final class Entry {
        public final String name, handle;
        public final boolean dir;
        public Entry(String name, String handle, boolean dir) { this.name = name; this.handle = handle; this.dir = dir; }
    }

    /** Handle of the top folder (the one that holds the language folders). */
    String root();

    /** Children of a folder; an empty list when the folder cannot be read. Never null. */
    List<Entry> list(String dirHandle);

    InputStream open(String handle) throws IOException;

    /** A URI string the media player can open for this handle (content:// or file://). */
    String uri(String handle);

    /**
     * True when a tree document id (e.g. "primary:Karaoke") is on the box's own storage; "1234-ABCD:Songs" (USB drive / SD card)
     * and anything unknown is false. Kept here, free of Android classes, so it can be unit-tested.
     */
    static boolean isInternalTreeId(String treeDocId) {
        return treeDocId != null && (treeDocId.equals("primary") || treeDocId.startsWith("primary:"));
    }

    /** Reads a whole UTF-8 text file (BOM removed); null when it cannot be read. */
    static String readText(Storage s, String handle) {
        if (handle == null) return null;
        try (InputStream in = s.open(handle)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192];
            for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
            String t = new String(out.toByteArray(), "UTF-8");
            return t.startsWith("﻿") ? t.substring(1) : t;
        } catch (Exception e) {
            return null;
        }
    }

    /** The child of dirHandle with this exact name, or null. */
    static Entry child(Storage s, String dirHandle, String name) {
        for (Entry e : s.list(dirHandle)) if (e.name.equals(name)) return e;
        return null;
    }
}
