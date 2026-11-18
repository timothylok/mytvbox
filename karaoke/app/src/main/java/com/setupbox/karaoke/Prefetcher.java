package com.setupbox.karaoke;

import java.io.IOException;
import java.io.InputStream;

/**
 * Warms up the NEXT queued song while the current one plays: reads the start (and, for the video, the end, where the index of an mp4 often
 * sits) of its files so the operating system has them cached, and parses its lyric file. When that song starts, the disk has no seek or
 * first-read delay left (measured on a USB drive: 4.5 s -> 2.4 s to the first sound) and the lyrics are ready.
 * One low-priority background thread, bounded reads, fail-soft (a missing or broken file is just skipped). No Android classes: unit-tested.
 */
public final class Prefetcher {
    /** What a finished warm-up leaves behind. */
    public static final class Result {
        public final String songId;
        public final Lrc lrc;          // null when the song has no (readable) lyric file
        public final long bytes;       // bytes read to warm the cache
        public final long ms;          // how long the warm-up took
        Result(String songId, Lrc lrc, long bytes, long ms) { this.songId = songId; this.lrc = lrc; this.bytes = bytes; this.ms = ms; }
    }

    public static final int AUDIO_MAX = 12 * 1024 * 1024;       // a karaoke mp3 is 4-8 MB: read it whole, up to this
    public static final int VIDEO_HEAD = 10 * 1024 * 1024;
    public static final int VIDEO_TAIL = 6 * 1024 * 1024;

    private final Object lock = new Object();
    private String wantedId = "";
    private Result ready;
    private Thread worker;
    private int gen;

    /** The song id being (or last) warmed; "" when none. */
    public String wantedId() { synchronized (lock) { return wantedId; } }

    /** Starts warming this song unless it is the one already requested. A new request makes an older, unfinished one stop. */
    public void request(final Storage st, final String songId, final String audioH, final String videoH, final String lrcH) {
        if (st == null || songId == null || songId.isEmpty()) return;
        final int mine;
        synchronized (lock) {
            if (songId.equals(wantedId)) return;
            wantedId = songId;
            ready = null;
            mine = ++gen;
        }
        Thread t = new Thread(new Runnable() {
            @Override public void run() { work(st, songId, audioH, videoH, lrcH, mine); }
        }, "prefetch");
        t.setPriority(Thread.MIN_PRIORITY);
        t.setDaemon(true);
        synchronized (lock) { worker = t; }
        t.start();
    }

    /** The finished warm-up for this song (once), or null when there is none yet / it was for another song. */
    public Result take(String songId) {
        synchronized (lock) {
            if (ready != null && ready.songId.equals(songId)) {
                Result r = ready;
                ready = null;
                return r;
            }
            return null;
        }
    }

    /** Stops any warm-up and forgets results (onDestroy, storage changed). */
    public void cancel() {
        Thread t;
        synchronized (lock) {
            gen++;
            wantedId = "";
            ready = null;
            t = worker;
            worker = null;
        }
        if (t != null) t.interrupt();
    }

    /** Test hook: waits until the current warm-up has finished (or the timeout passes). */
    public boolean await(long timeoutMs) {
        Thread t;
        synchronized (lock) { t = worker; }
        if (t == null) return true;
        try { t.join(timeoutMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return !t.isAlive();
    }

    private boolean stale(int mine) {
        synchronized (lock) { return mine != gen; }
    }

    private void work(Storage st, String songId, String audioH, String videoH, String lrcH, int mine) {
        long t0 = System.currentTimeMillis();
        long bytes = 0;
        Lrc lrc = null;
        try {
            if (lrcH != null && !lrcH.isEmpty()) {
                try {
                    String text = Storage.readText(st, lrcH);
                    if (text != null) lrc = Lrc.parse(text);
                } catch (RuntimeException ignored) { }          // a broken lyric file: the song plays without lyrics, as before
            }
            if (stale(mine)) return;
            if (audioH != null && !audioH.isEmpty()) bytes += touch(st, audioH, AUDIO_MAX, 0, mine);
            if (videoH != null && !videoH.isEmpty() && !stale(mine)) bytes += touch(st, videoH, VIDEO_HEAD, VIDEO_TAIL, mine);
        } catch (RuntimeException ignored) {
            // fail soft: warming is only an optimisation
        }
        synchronized (lock) {
            if (mine == gen) ready = new Result(songId, lrc, bytes, System.currentTimeMillis() - t0);
        }
    }

    /** Reads up to headMax bytes from the start and tailMax from the end of a file; returns the bytes read. Stops early when stale. */
    private long touch(Storage st, String handle, int headMax, int tailMax, int mine) {
        long total = 0;
        byte[] buf = new byte[64 * 1024];
        try (InputStream in = st.open(handle)) {
            long head = 0;
            for (int n; head < headMax && (n = in.read(buf, 0, (int) Math.min(buf.length, headMax - head))) > 0; ) {
                head += n;
                if (stale(mine) || Thread.currentThread().isInterrupted()) return total + head;
            }
            total += head;
        } catch (IOException | RuntimeException e) {
            return total;
        }
        if (tailMax > 0) {
            try (InputStream in = st.open(handle)) {
                long size = available(in);
                long skip = size > headMax + (long) tailMax ? size - tailMax : -1;      // the tail is only worth reading when it is not the part we read already
                if (skip > 0) {
                    long done = 0;
                    while (done < skip) {
                        long s = in.skip(skip - done);
                        if (s <= 0) return total;                                       // this storage cannot seek cheaply: leave it
                        done += s;
                        if (stale(mine) || Thread.currentThread().isInterrupted()) return total;
                    }
                    for (int n; (n = in.read(buf)) > 0; ) {
                        total += n;
                        if (stale(mine) || Thread.currentThread().isInterrupted()) return total;
                    }
                }
            } catch (IOException | RuntimeException e) {
                return total;
            }
        }
        return total;
    }

    /** Size of the stream when it can tell (files can), else 0 (then no tail is read). */
    private static long available(InputStream in) {
        try {
            if (in instanceof java.io.FileInputStream) return ((java.io.FileInputStream) in).getChannel().size();
        } catch (IOException ignored) { }
        return 0;
    }
}
