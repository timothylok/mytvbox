package com.setupbox.karaoke;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/**
 * Play history: one JSON object per line in a file (history.jsonl). Entries older than KEEP_DAYS are dropped
 * automatically (when the file is loaded, whenever a song is added, and by prune() which the app calls hourly).
 * Small by design (a few hundred lines), so every change simply rewrites the file.
 */
public final class History {
    public static final long KEEP_DAYS = 30;
    private static final long DAY_MS = 24L * 3600 * 1000;
    private static final int MAX_ENTRIES = 5000;       // safety cap

    public static final class Play {
        public final long start;                       // epoch ms when the song started
        public final String songId, title, artist, language, singer;
        public long durMs, playedMs;                   // song length, and how long it actually played (skipped early = short)
        Play(long start, String songId, String title, String artist, String language, String singer, long durMs, long playedMs) {
            this.start = start; this.songId = songId; this.title = title; this.artist = artist;
            this.language = language; this.singer = singer; this.durMs = durMs; this.playedMs = playedMs;
        }
        JSONObject toJson() throws Exception {
            return new JSONObject().put("t", start).put("songId", songId).put("title", title).put("artist", artist)
                    .put("language", language).put("singer", singer).put("durMs", durMs).put("playedMs", playedMs);
        }
    }

    private final File file;                           // null = in memory only (tests)
    private final List<Play> plays = new ArrayList<>(); // oldest first
    private Play open;                                  // the song being played right now

    public History(File file) {
        this.file = file;
        load();
    }

    private synchronized void load() {
        if (file == null || !file.isFile()) return;
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            for (String l; (l = r.readLine()) != null; ) {
                if (l.trim().isEmpty()) continue;
                try {
                    JSONObject o = new JSONObject(l);
                    plays.add(new Play(o.getLong("t"), o.optString("songId"), o.optString("title"), o.optString("artist"),
                            o.optString("language"), o.optString("singer"), o.optLong("durMs"), o.optLong("playedMs")));
                } catch (Exception ignored) { }        // a damaged line must not lose the rest
            }
            r.close();
        } catch (Exception ignored) { }
        pruneLocked(System.currentTimeMillis());
        save();
    }

    /** A song has started. Closes the previous one (if the app did not) and drops entries older than 30 days. */
    public synchronized void started(long now, String songId, String title, String artist, String language, String singer) {
        open = new Play(now, songId, title, artist, language, singer, 0, 0);
        plays.add(open);
        pruneLocked(now);
        save();
    }

    /** The current song ended or was skipped: record how long it ran. */
    public synchronized void finished(long playedMs, long durMs) {
        if (open == null) return;
        open.playedMs = Math.max(0, playedMs);
        open.durMs = Math.max(0, durMs);
        open = null;
        save();
    }

    /** Drops entries older than KEEP_DAYS; cheap, safe to call often. */
    public synchronized void prune(long now) {
        if (pruneLocked(now)) save();
    }

    private boolean pruneLocked(long now) {
        boolean changed = false;
        long cutoff = now - KEEP_DAYS * DAY_MS;
        while (!plays.isEmpty() && (plays.get(0).start < cutoff || plays.size() > MAX_ENTRIES)) {
            if (plays.get(0) == open) break;
            plays.remove(0);
            changed = true;
        }
        return changed;
    }

    /** Newest first, as {"days":30,"total":n,"plays":[...]}; days is clamped to 1..KEEP_DAYS. */
    public synchronized JSONObject toJson(long now, int days) throws Exception {
        days = (int) Math.max(1, Math.min(KEEP_DAYS, days));
        long cutoff = now - days * DAY_MS;
        JSONArray arr = new JSONArray();
        for (int i = plays.size() - 1; i >= 0; i--) {
            Play p = plays.get(i);
            if (p.start < cutoff) break;
            JSONObject o = p.toJson();
            if (p == open) o.put("playing", true);
            arr.put(o);
        }
        return new JSONObject().put("days", days).put("total", arr.length()).put("plays", arr);
    }

    public synchronized int size() { return plays.size(); }

    private void save() {
        if (file == null) return;
        try {
            File dir = file.getParentFile();
            if (dir != null) dir.mkdirs();
            File tmp = new File(file.getPath() + ".tmp");
            Writer w = new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8");
            for (Play p : plays) w.write(p.toJson().toString() + "\n");
            w.close();
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file); }
        } catch (Exception ignored) { }
    }
}
