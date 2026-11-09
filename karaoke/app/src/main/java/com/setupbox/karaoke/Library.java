package com.setupbox.karaoke;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Index of the Karaoke folder: &lt;root&gt;/&lt;Language&gt;/&lt;Artist&gt;/&lt;Title&gt;.mp3|mp4 (+ optional .lrc). */
public final class Library {
    public static final class Song {
        public String id, language, artist, title, file;   // file = path relative to the Karaoke root
        public boolean hasLrc;
        String key;                                         // normalized search text

        JSONObject toJson() throws Exception {
            return new JSONObject().put("id", id).put("language", language).put("artist", artist)
                    .put("title", title).put("file", file).put("lrc", hasLrc);
        }

        static Song fromJson(JSONObject o) {
            Song s = new Song();
            s.id = o.optString("id"); s.language = o.optString("language"); s.artist = o.optString("artist");
            s.title = o.optString("title"); s.file = o.optString("file"); s.hasLrc = o.optBoolean("lrc");
            s.key = normalize(s.title + " " + s.artist + " " + s.language);
            return s;
        }
    }

    public final File root;
    public final List<Song> songs = new ArrayList<>();

    private Library(File root) { this.root = root; }

    /** A library with no songs, used while the USB drive is missing. */
    public static Library empty() { return new Library(new File("/nonexistent")); }

    public File fileOf(Song s) { return new File(root, s.file); }

    // ---------- finding the folder ----------

    /** First existing "Karaoke" folder on any mounted storage (USB drives live under /storage/XXXX-XXXX). */
    public static File findRoot() {
        File[] vols = new File("/storage").listFiles();
        if (vols != null) {
            for (File v : vols) {
                File k = new File(v, "Karaoke");
                if (k.isDirectory() && !v.getName().equals("emulated") && !v.getName().equals("self")) return k;
            }
        }
        File k = new File(android.os.Environment.getExternalStorageDirectory(), "Karaoke");
        return k.isDirectory() ? k : null;
    }

    // ---------- scanning ----------

    public static Library scan(File root) {
        Library lib = new Library(root);
        File[] langs = root.listFiles();
        if (langs == null) return lib;
        for (File lang : langs) {
            if (!lang.isDirectory()) continue;
            File[] artists = lang.listFiles();
            if (artists == null) continue;
            for (File artist : artists) {
                if (!artist.isDirectory()) continue;
                File[] files = artist.listFiles();
                if (files == null) continue;
                for (File f : files) {
                    String n = f.getName();
                    String low = n.toLowerCase(Locale.ROOT);
                    if (!f.isFile() || n.contains(".part.") || !(low.endsWith(".mp3") || low.endsWith(".mp4"))) continue;
                    Song s = new Song();
                    s.language = lang.getName();
                    s.artist = artist.getName();
                    String stem = n.substring(0, n.lastIndexOf('.'));
                    s.title = cleanTitle(stem);
                    s.file = lang.getName() + "/" + artist.getName() + "/" + n;
                    s.hasLrc = new File(artist, stem + ".lrc").isFile();
                    s.id = md5(s.file);
                    s.key = normalize(s.title + " " + s.artist + " " + s.language);
                    lib.songs.add(s);
                }
            }
        }
        Collections.sort(lib.songs, new Comparator<Song>() {
            @Override public int compare(Song a, Song b) {
                int c = a.language.compareToIgnoreCase(b.language);
                if (c == 0) c = a.artist.compareToIgnoreCase(b.artist);
                return c != 0 ? c : a.title.compareToIgnoreCase(b.title);
            }
        });
        return lib;
    }

    // ---------- cache ----------

    public void save(File cache) {
        try {
            JSONArray arr = new JSONArray();
            for (Song s : songs) arr.put(s.toJson());
            File dir = cache.getParentFile();
            if (dir != null) dir.mkdirs();
            Files.write(cache.toPath(), arr.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }

    public static Library load(File root, File cache) {
        try {
            JSONArray arr = new JSONArray(new String(Files.readAllBytes(cache.toPath()), StandardCharsets.UTF_8));
            Library lib = new Library(root);
            for (int i = 0; i < arr.length(); i++) lib.songs.add(Song.fromJson(arr.getJSONObject(i)));
            return lib;
        } catch (Exception e) { return null; }
    }

    // ---------- queries ----------

    public List<Song> search(String query, String language, String artist) {
        String[] toks = normalize(query == null ? "" : query).split(" ");
        List<Song> out = new ArrayList<>();
        for (Song s : songs) {
            if (language != null && !language.isEmpty() && !s.language.equalsIgnoreCase(language)) continue;
            if (artist != null && !artist.isEmpty() && !s.artist.equalsIgnoreCase(artist)) continue;
            boolean ok = true;
            for (String t : toks) if (!t.isEmpty() && !s.key.contains(t)) { ok = false; break; }
            if (ok) out.add(s);
        }
        return out;
    }

    public List<String> languages() {
        TreeSet<String> set = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Song s : songs) set.add(s.language);
        return new ArrayList<>(set);
    }

    public List<String> artists(String language) {
        TreeSet<String> set = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Song s : songs) if (language == null || language.isEmpty() || s.language.equalsIgnoreCase(language)) set.add(s.artist);
        return new ArrayList<>(set);
    }

    public Song byId(String id) {
        for (Song s : songs) if (s.id.equals(id)) return s;
        return null;
    }

    // ---------- helpers ----------

    private static final Pattern NOISE = Pattern.compile(
            "[\\(\\[（【][^\\)\\]）】]*(official|music video|\\bmv\\b|lyric|audio|\\bhd\\b|4k|1080p|歌詞|歌词|官方|完整版)[^\\)\\]）】]*[\\)\\]）】]",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SPACES = Pattern.compile("\\s+");

    /** "Eason陳奕迅 浮誇[HD]" -> "Eason陳奕迅 浮誇"; leaves everything that isn't clearly noise. */
    static String cleanTitle(String stem) {
        String t = NOISE.matcher(stem).replaceAll(" ");
        t = SPACES.matcher(t).replaceAll(" ").trim();
        t = t.replaceAll("[\\s\\-–—|｜:：]+$", "").trim();
        return t.isEmpty() ? stem : t;
    }

    /** Lowercase, strip accents, turn punctuation into spaces (CJK characters are kept as they are). */
    static String normalize(String s) {
        String t = Normalizer.normalize(s, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "");
        t = t.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ");
        return SPACES.matcher(t).replaceAll(" ").trim();
    }

    private static String md5(String s) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) { return Integer.toHexString(s.hashCode()); }
    }
}
