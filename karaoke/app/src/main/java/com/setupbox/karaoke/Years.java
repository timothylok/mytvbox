package com.setupbox.karaoke;

import java.util.HashMap;
import java.util.Map;

/**
 * years.txt in the Karaoke folder (UTF-8, '#' = comment), one release year per line, same keys as titles.txt:
 *     <file name without extension> = 2008
 *     <Artist>/<file name without extension> = 2008
 *     <Language>/<Artist>/<file name without extension> = 2008
 * The most specific key wins. Songs without a year simply show none. Pure Java (no Android classes), so it is unit-tested.
 */
public final class Years {
    private Years() { }

    public static final int MIN = 1900, MAX = 2100;

    /** key -> year; lines with a missing / non-numeric / out-of-range year are ignored. */
    public static Map<String, Integer> parse(String text) {
        Map<String, Integer> m = new HashMap<>();
        if (text == null) return m;
        for (String line : text.split("\r?\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.lastIndexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq).trim(), val = line.substring(eq + 1).trim();
            if (key.isEmpty()) continue;
            try {
                int y = Integer.parseInt(val);
                if (y >= MIN && y <= MAX) m.put(key, y);
            } catch (NumberFormatException ignored) { }
        }
        return m;
    }

    /** The year for a song, or 0 when unknown. */
    public static int lookup(Map<String, Integer> m, String lang, String artist, String stem) {
        if (m == null || m.isEmpty()) return 0;
        Integer y = m.get(lang + "/" + artist + "/" + stem);
        if (y == null) y = m.get(artist + "/" + stem);
        if (y == null) y = m.get(stem);
        return y == null ? 0 : y;
    }
}
