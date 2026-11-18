package com.setupbox.karaoke;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Grouping and ordering for the on-TV song browser (language -> artist -> song). No Android classes: unit-tested.
 * Languages come in a fixed, familiar order (Cantopop, Mandarin, English, JPop, KPop), any other language after them by name;
 * artists are sorted by name; songs newest first (unknown year last), then by title, like the phone page's artist list.
 */
public final class BrowseModel {
    private BrowseModel() { }

    /** What the browser needs to know about one song. */
    public static final class Entry {
        public final String id, language, artist, title;
        public final int year;       // 0 = unknown
        public Entry(String id, String language, String artist, String title, int year) {
            this.id = id; this.language = language; this.artist = artist; this.title = title; this.year = year;
        }
    }

    /** One line of a list: the value to drill into (language / artist) and how many songs it holds. */
    public static final class Row {
        public final String value;
        public final int count;
        Row(String value, int count) { this.value = value; this.count = count; }
    }

    private static final String[] LANGUAGE_ORDER = {"Cantopop", "Mandarin", "English", "JPop", "KPop"};

    private static int languageRank(String lang) {
        for (int i = 0; i < LANGUAGE_ORDER.length; i++) if (LANGUAGE_ORDER[i].equalsIgnoreCase(lang)) return i;
        return LANGUAGE_ORDER.length;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    /** The languages present, in display order, with their song counts. */
    public static List<Row> languages(List<Entry> all) {
        Map<String, Integer> n = new HashMap<>();
        for (Entry e : all) n.put(nz(e.language), n.containsKey(nz(e.language)) ? n.get(nz(e.language)) + 1 : 1);
        List<Row> out = new ArrayList<>();
        for (Map.Entry<String, Integer> m : n.entrySet()) out.add(new Row(m.getKey(), m.getValue()));
        Collections.sort(out, new Comparator<Row>() {
            @Override public int compare(Row a, Row b) {
                int ra = languageRank(a.value), rb = languageRank(b.value);
                return ra != rb ? Integer.compare(ra, rb) : a.value.compareToIgnoreCase(b.value);
            }
        });
        return out;
    }

    /** The artists of one language, sorted by name (case-insensitive), with their song counts. */
    public static List<Row> artists(List<Entry> all, String language) {
        Map<String, Integer> n = new HashMap<>();
        for (Entry e : all) {
            if (!nz(e.language).equals(nz(language))) continue;
            String a = nz(e.artist);
            n.put(a, n.containsKey(a) ? n.get(a) + 1 : 1);
        }
        List<Row> out = new ArrayList<>();
        for (Map.Entry<String, Integer> m : n.entrySet()) out.add(new Row(m.getKey(), m.getValue()));
        Collections.sort(out, new Comparator<Row>() {
            @Override public int compare(Row a, Row b) { return a.value.compareToIgnoreCase(b.value); }
        });
        return out;
    }

    /** The songs of one artist in one language: newest year first (unknown year last), then by title. */
    public static List<Entry> songs(List<Entry> all, String language, String artist) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : all) if (nz(e.language).equals(nz(language)) && nz(e.artist).equals(nz(artist))) out.add(e);
        Collections.sort(out, new Comparator<Entry>() {
            @Override public int compare(Entry a, Entry b) {
                int ya = a.year > 0 ? a.year : -1, yb = b.year > 0 ? b.year : -1;
                if (ya != yb) return Integer.compare(yb, ya);
                return nz(a.title).compareToIgnoreCase(nz(b.title));
            }
        });
        return out;
    }

    /** Text of a list row, e.g. "Cantopop  (62)". */
    public static String label(Row r) {
        return (r.value.isEmpty() ? "(unknown)" : r.value) + "  (" + r.count + ")";
    }

    /** Text of a song row: the title, then the year when known, and a marker when the song is already queued. */
    public static String songLabel(Entry e, boolean queued) {
        return (queued ? "[queued]  " : "") + nz(e.title) + (e.year > 0 ? "   " + e.year : "");
    }
}
