package com.setupbox.karaoke;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LRC parser. Handles plain lines "[mm:ss.xx]text" (several tags per line allowed, [offset:ms] honoured)
 * and enhanced LRC, where words/characters inside the line carry their own time:
 *   [00:13.68]<00:13.68>我<00:14.10>唱<00:14.52>得<00:14.90>
 * A trailing tag (nothing after it) marks the end of the line.
 * For enhanced lines {@link Line#charMs} holds the start time of every character; otherwise it is null.
 */
public final class Lrc {
    public static final class Line {
        public final long ms;          // when the line starts
        public final String text;
        public final long[] charMs;    // start time per character of text, or null if the line has no word timing
        public final long endMs;       // explicit end of the line (trailing tag), or -1 if unknown
        Line(long ms, String text, long[] charMs, long endMs) { this.ms = ms; this.text = text; this.charMs = charMs; this.endMs = endMs; }
    }

    private static final Pattern TAG = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");
    private static final Pattern WORD = Pattern.compile("<(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?>");
    private static final Pattern OFFSET = Pattern.compile("\\[offset:\\s*([+-]?\\d+)\\s*\\]", Pattern.CASE_INSENSITIVE);

    public final List<Line> lines = new ArrayList<>();

    public static Lrc load(File f) {
        try {
            String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            if (s.startsWith("﻿")) s = s.substring(1);
            return parse(s);
        } catch (Exception e) { return null; }
    }

    private static long ms(Matcher m) {
        long ms = Long.parseLong(m.group(1)) * 60000L + Long.parseLong(m.group(2)) * 1000L;
        String f = m.group(3);
        if (f != null) ms += f.length() == 1 ? Long.parseLong(f) * 100 : f.length() == 2 ? Long.parseLong(f) * 10 : Long.parseLong(f);
        return ms;
    }

    public static Lrc parse(String s) {
        Lrc lrc = new Lrc();
        long offset = 0;
        Matcher om = OFFSET.matcher(s);
        if (om.find()) offset = -Long.parseLong(om.group(1));   // per the LRC spec a positive offset shows lyrics earlier
        for (String raw : s.split("\r?\n")) {
            Matcher m = TAG.matcher(raw);
            List<Long> times = new ArrayList<>();
            int end = 0;
            while (m.find() && m.start() == end) {
                times.add(ms(m));
                end = m.end();
            }
            if (times.isEmpty()) continue;
            String body = raw.substring(end);
            long first = times.get(0);
            for (long t : times) {
                Line line = makeLine(t, body, t - first);
                lrc.lines.add(new Line(line.ms + offset, line.text, shift(line.charMs, offset), line.endMs < 0 ? -1 : line.endMs + offset));
            }
        }
        Collections.sort(lrc.lines, new Comparator<Line>() {
            @Override public int compare(Line a, Line b) { return Long.compare(a.ms, b.ms); } });
        return lrc;
    }

    private static long[] shift(long[] a, long by) {
        if (a == null) return null;
        long[] r = new long[a.length];
        for (int i = 0; i < a.length; i++) r[i] = a[i] + by;
        return r;
    }

    /** Builds one line; {@code delta} moves the word times when the same text is repeated at a later time. */
    private static Line makeLine(long lineMs, String body, long delta) {
        Matcher w = WORD.matcher(body);
        if (!w.find()) return new Line(lineMs, body.trim(), null, -1);
        w.reset();

        List<Long> pieceTime = new ArrayList<>();
        List<String> pieceText = new ArrayList<>();
        int pos = 0;
        long curTime = lineMs;
        boolean haveCur = false;
        String lead = "";
        while (w.find()) {
            String piece = body.substring(pos, w.start());
            if (!haveCur) lead = piece;                         // text before the first word tag starts with the line
            else { pieceTime.add(curTime); pieceText.add(piece); }
            curTime = ms(w) + delta;
            haveCur = true;
            pos = w.end();
        }
        String tail = body.substring(pos);                       // text after the last tag
        long endMs = -1;
        if (tail.trim().isEmpty()) {
            endMs = curTime;                                     // trailing tag = end of line
        } else {
            pieceTime.add(curTime); pieceText.add(tail);
        }
        if (!lead.isEmpty()) { pieceTime.add(0, lineMs); pieceText.add(0, lead); }

        StringBuilder text = new StringBuilder();
        List<Long> cm = new ArrayList<>();
        for (int i = 0; i < pieceText.size(); i++) {
            String p = pieceText.get(i);
            long t0 = pieceTime.get(i);
            long t1 = i + 1 < pieceTime.size() ? pieceTime.get(i + 1) : (endMs >= 0 ? endMs : t0 + 350L * Math.max(1, p.length()));
            for (int k = 0; k < p.length(); k++) {
                cm.add(t0 + (t1 - t0) * k / p.length());          // a multi-character word is spread evenly
            }
            text.append(p);
        }
        // trim like plain lines do, keeping the time array in step
        int a = 0, b = text.length();
        while (a < b && Character.isWhitespace(text.charAt(a))) a++;
        while (b > a && Character.isWhitespace(text.charAt(b - 1))) b--;
        long[] arr = new long[b - a];
        for (int i = a; i < b; i++) arr[i - a] = cm.get(i);
        return new Line(lineMs, text.substring(a, b), arr.length == 0 ? null : arr, endMs);
    }

    /** When line {@code idx} is over: its end tag, else a little after its last word, else an estimate from its length. */
    public long endOf(int idx) {
        Line l = lines.get(idx);
        long next = idx + 1 < lines.size() ? lines.get(idx + 1).ms : Long.MAX_VALUE;
        if (l.endMs >= 0) return Math.min(l.endMs, next);
        int total = 0;
        for (int j = 0; j < l.text.length(); j++) if (!Character.isWhitespace(l.text.charAt(j))) total++;
        if (l.charMs != null && l.charMs.length > 0) return Math.min(next, l.charMs[l.charMs.length - 1] + 800);
        return Math.min(next, l.ms + Math.max(1000, total * 450L));
    }

    /** Index of the line active at positionMs, or -1 before the first line. */
    public int indexAt(long positionMs) {
        int lo = 0, hi = lines.size() - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lines.get(mid).ms <= positionMs) { ans = mid; lo = mid + 1; } else hi = mid - 1;
        }
        return ans;
    }

    /**
     * Index (among the non-space characters) of the character being sung at positionMs; -1 = none yet,
     * and the character count itself = the whole line has been sung.
     * Uses the real word times when the line has them, otherwise shares the line's time evenly
     * (up to the next line, at most ~0.45 s per character).
     */
    public int currentChar(int lineIndex, long positionMs) {
        Line l = lines.get(lineIndex);
        int total = 0;
        for (int j = 0; j < l.text.length(); j++) if (!Character.isWhitespace(l.text.charAt(j))) total++;
        if (total == 0) return -1;
        long next = lineIndex + 1 < lines.size() ? lines.get(lineIndex + 1).ms : Long.MAX_VALUE;

        if (l.charMs != null) {
            long lastStart = 0;
            int started = 0;
            for (int j = 0; j < l.text.length(); j++) {
                if (Character.isWhitespace(l.text.charAt(j))) continue;
                lastStart = l.charMs[j];
                if (l.charMs[j] <= positionMs) started++;
            }
            long end = l.endMs >= 0 ? l.endMs : Math.min(next, lastStart + 800);
            return positionMs >= end ? total : started - 1;
        }
        long dur = Math.max(1000, Math.min(next - l.ms, total * 450L));
        long el = positionMs - l.ms;
        return el >= dur ? total : (int) (el * total / dur);
    }
}
