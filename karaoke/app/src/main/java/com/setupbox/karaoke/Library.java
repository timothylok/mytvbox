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

/** Index of the Karaoke folder: &lt;root&gt;/&lt;Language&gt;/&lt;Artist&gt;/&lt;Title&gt;.mp3|mp4 (+ optional .lrc), read through a Storage. */
public final class Library {
    public static final class Song {
        public String id, language, artist, title, file;   // file = path relative to the Karaoke root (also what the id is made from)
        public String video = "";                           // companion music video (relative path), or "" if none
        public boolean hasLrc;
        String key;                                         // normalized search text
        public String audioH = "", videoH = "", lrcH = "";  // Storage handles of the audio, video and lyric files ("" = none)

        JSONObject toJson() throws Exception {
            return new JSONObject().put("id", id).put("language", language).put("artist", artist)
                    .put("title", title).put("file", file).put("lrc", hasLrc).put("video", !video.isEmpty());
        }

        /** For the on-disk cache: same as the API form, but with the video's path and the storage handles. */
        JSONObject toCacheJson() throws Exception {
            return toJson().put("videoFile", video).put("audioH", audioH).put("videoH", videoH).put("lrcH", lrcH);
        }

        static Song fromJson(JSONObject o) {
            Song s = new Song();
            s.id = o.optString("id"); s.language = o.optString("language"); s.artist = o.optString("artist");
            s.title = o.optString("title"); s.file = o.optString("file"); s.hasLrc = o.optBoolean("lrc");
            s.video = o.optString("videoFile", "");
            s.audioH = o.optString("audioH", ""); s.videoH = o.optString("videoH", ""); s.lrcH = o.optString("lrcH", "");
            s.key = normalize(s.title + " " + s.artist + " " + s.language);
            return s;
        }
    }

    public final Storage storage;
    public final List<Song> songs = new ArrayList<>();

    private Library(Storage storage) { this.storage = storage; }

    /** A library with no songs, used while no folder has been picked (or it cannot be read). */
    public static Library empty() { return new Library(null); }

    // ---------- scanning ----------

    /**
     * Title overrides for messy file names. Optional text files (UTF-8), one entry per line, '#' starts a comment:
     *     <file name without extension> = Title to show
     *     <Artist>/<file name without extension> = Title to show
     *     <Language>/<Artist>/<file name without extension> = Title to show
     * Read from titles.txt in the Karaoke folder (it travels with the songs) and from the app's private copy (this wins).
     */
    static java.util.Map<String, String> loadOverrides(String[] texts) {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        for (String t : texts) if (t != null) parseOverrides(t, m);
        return m;
    }

    static void parseOverrides(String text, java.util.Map<String, String> into) {
        for (String line : text.split("\r?\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq).trim(), val = line.substring(eq + 1).trim();
            if (!key.isEmpty() && !val.isEmpty()) into.put(key, val);
        }
    }

    /**
     * static_videos.txt (one file name without extension per line, '#' = comment): music videos that are not worth showing
     * (a still picture, or a video that does not fit the audio). Those songs play as audio-only (photo / gradient backdrop).
     */
    static java.util.Set<String> parseStaticVideos(String[] texts) {
        java.util.Set<String> set = new java.util.HashSet<>();
        for (String text : texts) {
            if (text == null) continue;
            for (String line : text.split("\r?\n")) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) set.add(line);
            }
        }
        return set;
    }

    static String titleOverride(java.util.Map<String, String> o, String lang, String artist, String stem) {
        if (o.isEmpty()) return null;
        String v = o.get(lang + "/" + artist + "/" + stem);
        if (v == null) v = o.get(artist + "/" + stem);
        if (v == null) v = o.get(stem);
        return v;
    }

    /**
     * Scans the Karaoke folder. extraTitles / extraStatic are the app's private copies of titles.txt and static_videos.txt
     * (may be null); the copies inside the Karaoke folder are read too.
     */
    public static Library scan(Storage st, String extraTitles, String extraStatic) {
        Library lib = new Library(st);
        String root = st.root();
        java.util.List<Storage.Entry> top = st.list(root);
        Storage.Entry titlesFile = null, staticFile = null;
        for (Storage.Entry e : top) {
            if (e.dir) continue;
            if (e.name.equals("titles.txt")) titlesFile = e;
            else if (e.name.equals("static_videos.txt")) staticFile = e;
        }
        java.util.Map<String, String> overrides = loadOverrides(new String[]{titlesFile == null ? null : Storage.readText(st, titlesFile.handle), extraTitles});
        java.util.Set<String> staticVideos = parseStaticVideos(new String[]{staticFile == null ? null : Storage.readText(st, staticFile.handle), extraStatic});
        for (Storage.Entry lang : top) {
            if (!lang.dir) continue;
            for (Storage.Entry artist : st.list(lang.handle)) {
                if (!artist.dir) continue;
                // A song is one title: an .mp3 (karaoke audio) and an .mp4 with the same name (the music video,
                // shown muted behind the lyrics) belong together. A lone .mp4 is a song with its own audio.
                java.util.Map<String, Storage.Entry> audio = new java.util.TreeMap<>(), video = new java.util.TreeMap<>(), lrcs = new java.util.HashMap<>();
                for (Storage.Entry f : st.list(artist.handle)) {
                    String n = f.name;
                    String low = n.toLowerCase(Locale.ROOT);
                    if (f.dir || n.contains(".part.") || low.endsWith(".part")) continue;
                    String stem = n.substring(0, Math.max(0, n.lastIndexOf('.')));
                    if (low.endsWith(".mp3")) audio.put(stem, f);
                    else if (low.endsWith(".mp4")) video.put(stem, f);
                    else if (low.endsWith(".lrc")) lrcs.put(stem, f);
                }
                java.util.TreeSet<String> stems = new java.util.TreeSet<>(audio.keySet());
                stems.addAll(video.keySet());
                for (String stem : stems) {
                    Song s = new Song();
                    s.language = lang.name;
                    s.artist = artist.name;
                    s.title = cleanTitle(stem);
                    String shown = titleOverride(overrides, lang.name, artist.name, stem);
                    String autoTitle = s.title;
                    if (shown != null) s.title = shown;
                    String prefix = lang.name + "/" + artist.name + "/";
                    if (audio.containsKey(stem)) {
                        s.file = prefix + audio.get(stem).name;
                        s.audioH = audio.get(stem).handle;
                        if (video.containsKey(stem) && !staticVideos.contains(stem)) {
                            s.video = prefix + video.get(stem).name;
                            s.videoH = video.get(stem).handle;
                        }
                    } else {
                        s.file = prefix + video.get(stem).name;
                        s.audioH = video.get(stem).handle;
                    }
                    Storage.Entry l = lrcs.get(stem);
                    s.hasLrc = l != null;
                    s.lrcH = l == null ? "" : l.handle;
                    s.id = md5(s.file);
                    s.key = normalize(s.title + " " + autoTitle + " " + s.artist + " " + s.language);   // search finds the old name too
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
            for (Song s : songs) arr.put(s.toCacheJson());
            File dir = cache.getParentFile();
            if (dir != null) dir.mkdirs();
            Files.write(cache.toPath(), arr.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }

    /** The cached list from the last scan (handles included); null if there is none or it predates the folder picker. */
    public static Library load(Storage st, File cache) {
        try {
            JSONArray arr = new JSONArray(new String(Files.readAllBytes(cache.toPath()), StandardCharsets.UTF_8));
            Library lib = new Library(st);
            for (int i = 0; i < arr.length(); i++) {
                Song s = Song.fromJson(arr.getJSONObject(i));
                if (s.audioH.isEmpty()) return null;          // old cache without storage handles: scan again
                lib.songs.add(s);
            }
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
        String t = toSimplified(s);
        t = Normalizer.normalize(t, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "");
        t = t.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ");
        return SPACES.matcher(t).replaceAll(" ").trim();
    }

    // Traditional -> Simplified Chinese, so 浮誇 and 浮夸 match each other. Uses Android's built-in ICU transliterator
    // (API 29+, the box is Android 10); on older systems search simply stays exact.
    private static Object t2s;
    private static boolean t2sTried = false;

    static synchronized String toSimplified(String s) {
        if (!t2sTried) {
            t2sTried = true;
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                try { t2s = android.icu.text.Transliterator.getInstance("Traditional-Simplified"); } catch (Throwable ignored) { }
            }
        }
        if (t2s == null) return s;
        try { return ((android.icu.text.Transliterator) t2s).transliterate(s); } catch (Throwable e) { return s; }
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
