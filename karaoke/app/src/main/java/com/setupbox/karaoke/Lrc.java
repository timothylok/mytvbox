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

/** Minimal LRC parser: "[mm:ss.xx]text" lines (several tags per line allowed), [offset:ms] honoured. */
public final class Lrc {
    public static final class Line { public final long ms; public final String text;
        Line(long ms, String text) { this.ms = ms; this.text = text; } }

    private static final Pattern TAG = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");
    private static final Pattern OFFSET = Pattern.compile("\\[offset:\\s*(-?\\d+)\\s*\\]", Pattern.CASE_INSENSITIVE);

    public final List<Line> lines = new ArrayList<>();

    public static Lrc load(File f) {
        try {
            String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            if (s.startsWith("\uFEFF")) s = s.substring(1);
            return parse(s);
        } catch (Exception e) { return null; }
    }

    public static Lrc parse(String s) {
        Lrc lrc = new Lrc();
        long offset = 0;
        Matcher om = OFFSET.matcher(s);
        if (om.find()) offset = Long.parseLong(om.group(1));
        for (String raw : s.split("\r?\n")) {
            Matcher m = TAG.matcher(raw);
            List<Long> times = new ArrayList<>();
            int end = 0;
            while (m.find() && m.start() == end) {
                long ms = Long.parseLong(m.group(1)) * 60000L + Long.parseLong(m.group(2)) * 1000L;
                String f = m.group(3);
                if (f != null) ms += f.length() == 1 ? Long.parseLong(f) * 100 : f.length() == 2 ? Long.parseLong(f) * 10 : Long.parseLong(f);
                times.add(ms);
                end = m.end();
            }
            if (times.isEmpty()) continue;
            String text = raw.substring(end).trim();
            for (long t : times) lrc.lines.add(new Line(t + offset, text));
        }
        Collections.sort(lrc.lines, new Comparator<Line>() {
            @Override public int compare(Line a, Line b) { return Long.compare(a.ms, b.ms); } });
        return lrc;
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
}
