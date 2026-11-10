package com.setupbox.karaoke;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

/**
 * crops.txt (made by tools/scripts/detect_crops.py): black bars burned into a music video, to be zoomed away.
 *   <video file name without extension> = x:y:w:h:W:H     (picture area x,y,w,h inside a W x H frame)
 * '#' starts a comment. Later files win over earlier ones. A damaged line is ignored.
 */
public final class Crops {
    private Crops() { }

    public static Map<String, int[]> parse(String text) {
        Map<String, int[]> out = new HashMap<>();
        for (String raw : text.split("\r?\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.lastIndexOf('=');
            if (eq <= 0) continue;
            String[] p = line.substring(eq + 1).trim().split(":");
            if (p.length != 6) continue;
            try {
                int[] v = new int[6];
                for (int i = 0; i < 6; i++) v[i] = Integer.parseInt(p[i].trim());
                if (v[2] <= 0 || v[3] <= 0 || v[0] < 0 || v[1] < 0 || v[0] + v[2] > v[4] || v[1] + v[3] > v[5]) continue;
                out.put(line.substring(0, eq).trim(), v);
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    public static Map<String, int[]> load(File... files) {
        Map<String, int[]> all = new HashMap<>();
        for (File f : files) {
            if (f == null || !f.isFile()) continue;
            try {
                StringBuilder sb = new StringBuilder();
                BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
                for (String l; (l = r.readLine()) != null; ) sb.append(l).append('\n');
                r.close();
                all.putAll(parse(sb.toString()));
            } catch (Exception ignored) { }
        }
        return all;
    }
}
