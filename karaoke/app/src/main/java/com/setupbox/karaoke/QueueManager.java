package com.setupbox.karaoke;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The karaoke queue: one "now playing" item plus an ordered list of upcoming items.
 * Every entry has its own id, so the same song can be queued twice. All methods are thread-safe
 * (the web server and the player touch it from different threads) and bump {@link #version()}
 * on every change so phones only redraw when something actually changed.
 * Plain Java on purpose: no Android classes, so it can be unit-tested on a PC.
 */
public final class QueueManager {
    public static final class Item {
        public final String id, songId, singer;
        public Item(String id, String songId, String singer) { this.id = id; this.songId = songId; this.singer = singer; }
    }

    public enum Move { UP, DOWN, TOP, BOTTOM }

    private final List<Item> upcoming = new ArrayList<>();
    private Item current;
    private long seq = 0;
    private long version = 0;
    private File saveFile;               // null = don't persist
    private static final Charset UTF8 = Charset.forName("UTF-8");

    public QueueManager() { }

    /** Queue that restores itself from, and saves itself to, the given file. */
    public static QueueManager withFile(File f) {
        QueueManager q = new QueueManager();
        q.saveFile = f;
        q.loadFrom(f);
        return q;
    }

    // ---------- changes ----------

    /** Adds a song at the end, or right after the current song when {@code next} is true. */
    public synchronized Item add(String songId, String singer, boolean next) {
        Item it = new Item("q" + (++seq), songId, clean(singer));
        if (next) upcoming.add(0, it); else upcoming.add(it);
        changed();
        return it;
    }

    /** Removes an upcoming item. The current song is ended with {@link #advance()} instead. */
    public synchronized boolean remove(String id) {
        int i = indexOf(id);
        if (i < 0) return false;
        upcoming.remove(i);
        changed();
        return true;
    }

    public synchronized boolean move(String id, Move m) {
        int i = indexOf(id);
        if (i < 0) return false;
        int to;
        switch (m) {
            case UP: to = i - 1; break;
            case DOWN: to = i + 1; break;
            case TOP: to = 0; break;
            default: to = upcoming.size() - 1; break;
        }
        if (to < 0 || to >= upcoming.size() || to == i) return false;   // already at the edge
        upcoming.add(to, upcoming.remove(i));
        changed();
        return true;
    }

    /** Shuffles the upcoming songs; the one playing now is not touched. */
    public synchronized void shuffle() {
        if (upcoming.size() < 2) return;
        Collections.shuffle(upcoming);
        changed();
    }

    public synchronized void clear() {
        if (upcoming.isEmpty()) return;
        upcoming.clear();
        changed();
    }

    /** Finishes the current song and starts the next one. Returns it, or null when the queue ran out. */
    public synchronized Item advance() {
        current = upcoming.isEmpty() ? null : upcoming.remove(0);
        changed();
        return current;
    }

    // ---------- reading ----------

    public synchronized Item current() { return current; }
    public synchronized List<Item> upcoming() { return new ArrayList<>(upcoming); }
    public synchronized long version() { return version; }

    // ---------- saving ----------

    /** One line per entry: "C|Q <tab> id <tab> songId <tab> singer"; first line holds the id counter. */
    public synchronized String serialize() {
        StringBuilder sb = new StringBuilder("seq\t").append(seq).append('\n');
        if (current != null) line(sb, "C", current);
        for (Item it : upcoming) line(sb, "Q", it);
        return sb.toString();
    }

    /** Restores a saved queue. A song that was playing goes back to the front so it restarts. */
    public synchronized void restore(String text) {
        upcoming.clear();
        current = null;
        Item cur = null;
        for (String l : text.split("\n")) {
            String[] p = l.split("\t", -1);
            if (p.length >= 2 && p[0].equals("seq")) {
                try { seq = Long.parseLong(p[1].trim()); } catch (NumberFormatException ignored) { }
            } else if (p.length >= 4) {
                Item it = new Item(p[1], p[2], p[3]);
                if (p[0].equals("C")) cur = it; else if (p[0].equals("Q")) upcoming.add(it);
            }
        }
        if (cur != null) upcoming.add(0, cur);
        version++;
    }

    private void loadFrom(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            for (int n; (n = in.read(b)) > 0; ) buf.write(b, 0, n);
            restore(new String(buf.toByteArray(), UTF8));
        } catch (IOException ignored) { }   // no saved queue yet
    }

    private void save() {
        if (saveFile == null) return;
        try {
            File dir = saveFile.getParentFile();
            if (dir != null) dir.mkdirs();
            File tmp = new File(saveFile.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(serialize().getBytes(UTF8)); }
            if (!tmp.renameTo(saveFile)) { saveFile.delete(); tmp.renameTo(saveFile); }
        } catch (IOException ignored) { }
    }

    // ---------- helpers ----------

    private void changed() { version++; save(); }

    private int indexOf(String id) {
        for (int i = 0; i < upcoming.size(); i++) if (upcoming.get(i).id.equals(id)) return i;
        return -1;
    }

    private static void line(StringBuilder sb, String tag, Item it) {
        sb.append(tag).append('\t').append(it.id).append('\t').append(it.songId).append('\t').append(it.singer).append('\n');
    }

    private static String clean(String s) {
        if (s == null) return "";
        s = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
        return s.length() > 40 ? s.substring(0, 40) : s;
    }
}
