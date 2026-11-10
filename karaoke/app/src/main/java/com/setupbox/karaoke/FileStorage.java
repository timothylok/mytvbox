package com.setupbox.karaoke;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Storage over plain files: used by tests and the single-file debug mode, never for the user's real folder. */
public final class FileStorage implements Storage {
    private final File root;

    public FileStorage(File root) { this.root = root; }

    @Override public String root() { return root.getPath(); }

    @Override public List<Entry> list(String dirHandle) {
        List<Entry> out = new ArrayList<>();
        File[] fs = new File(dirHandle).listFiles();
        if (fs == null) return out;
        for (File f : fs) out.add(new Entry(f.getName(), f.getPath(), f.isDirectory()));
        return out;
    }

    @Override public InputStream open(String handle) throws IOException { return new FileInputStream(handle); }

    @Override public String uri(String handle) { return new File(handle).toURI().toString().replace("file:/", "file:///"); }
}
