package com.setupbox.karaoke;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Path rules for the in-app song-location chooser (used on devices with no system folder chooser). No Android classes, so it is unit-tested.
 * The app may only read songs below its own folders (getExternalFilesDirs: internal storage and any USB drive / SD card). That needs no
 * permission and is allowed on Android 10 and on current Android; browsing anywhere else would need all-files access, which Google Play restricts.
 */
public final class AppFolders {
    private AppFolders() { }

    /** True when f is root itself or lies below it (symlinks and ".." resolved, so a path cannot climb out). */
    public static boolean isUnder(File root, File f) {
        if (root == null || f == null) return false;
        try {
            String r = root.getCanonicalPath(), p = f.getCanonicalPath();
            if (p.equals(r)) return true;
            return p.startsWith(r.endsWith(File.separator) ? r : r + File.separator);
        } catch (IOException e) {
            return false;
        }
    }

    /** True when f is one of the allowed roots or below one of them. */
    public static boolean isAllowed(List<File> roots, File f) {
        if (roots == null) return false;
        for (File r : roots) if (isUnder(r, f)) return true;
        return false;
    }

    /** The root (of the allowed ones) that contains f, or null. */
    public static File rootOf(List<File> roots, File f) {
        if (roots == null) return null;
        for (File r : roots) if (isUnder(r, f)) return r;
        return null;
    }

    /** Sub-folders of dir, sorted by name, without hidden ones; empty when dir cannot be read. */
    public static List<File> subDirs(File dir) {
        List<File> out = new ArrayList<>();
        File[] fs = dir == null ? null : dir.listFiles();
        if (fs == null) return out;
        for (File f : fs) if (f.isDirectory() && !f.getName().startsWith(".")) out.add(f);
        Collections.sort(out, new Comparator<File>() {
            @Override public int compare(File a, File b) { return a.getName().compareToIgnoreCase(b.getName()); }
        });
        return out;
    }

    /** The folder one level up, but never above the root: null when dir is the root (or not inside it). */
    public static File parentWithin(File root, File dir) {
        if (!isUnder(root, dir)) return null;
        try {
            if (root.getCanonicalPath().equals(dir.getCanonicalPath())) return null;
        } catch (IOException e) {
            return null;
        }
        return dir.getParentFile();
    }

    /** A new-folder name is accepted only when it is a single plain name (no slashes, no dots-only, not hidden). */
    public static boolean isPlainName(String name) {
        if (name == null) return false;
        String n = name.trim();
        return !n.isEmpty() && n.length() <= 80 && !n.startsWith(".") && n.indexOf('/') < 0 && n.indexOf('\\') < 0 && n.indexOf('\0') < 0;
    }
}
