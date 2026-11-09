package com.setupbox.launcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * User settings, stored as JSON at /sdcard/Launcher/config.json so they can be edited
 * (file manager, adb push) without rebuilding the app. Missing keys fall back to defaults.
 */
final class Config {
    static final String DIR = "/sdcard/Launcher";
    static final String PATH = DIR + "/config.json";

    private JSONObject j = new JSONObject();
    private final JSONObject defaults = defaults();

    static Config load() {
        Config c = new Config();
        File f = new File(PATH);
        try {
            if (f.exists()) {
                FileInputStream in = new FileInputStream(f);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                in.close();
                c.j = new JSONObject(new String(out.toByteArray(), "UTF-8"));
            } else {
                c.writeDefaults();
            }
        } catch (Exception ignored) {
            // bad JSON or no storage permission: run on defaults
        }
        return c;
    }

    private void writeDefaults() {
        try {
            new File(DIR).mkdirs();
            FileOutputStream out = new FileOutputStream(new File(PATH));
            out.write(defaults.toString(2).getBytes("UTF-8"));
            out.close();
        } catch (Exception ignored) {
        }
    }

    private static JSONObject defaults() {
        JSONObject d = new JSONObject();
        try {
            d.put("city", "Auckland");
            d.put("lat", -36.8485);
            d.put("lon", 174.7633);
            d.put("tz", "Pacific/Auckland");
            d.put("second_tz", "Asia/Hong_Kong");
            d.put("second_label", "HONG KONG");
            d.put("stocks", new JSONArray(new String[]{"AAPL", "MSFT", "GOOGL", "AMZN", "NVDA", "META", "TSLA"}));
            d.put("currency_base", "NZD");
            d.put("currencies", new JSONArray(new String[]{"USD", "AUD", "CNY", "HKD"}));
            d.put("rss_label", "RNZ");
            d.put("rss_url", "https://www.rnz.co.nz/rss/national.xml");
            d.put("low_storage_percent", 15);
            d.put("hidden", new JSONArray(new String[]{
                    "com.android.bosslauncherv4", "com.dianshiyouhua", "com.hpplay.happyplay.aw",
                    "com.android.traceur", "com.softwinner.update", "com.softwinner.miracastReceiver",
                    "com.moons.browser"}));
            d.put("pinned", new JSONArray(new String[]{
                    "org.jellyfin.androidtv", "org.videolan.vlc", "com.google.android.youtube.tv",
                    "com.android.tv.settings"}));
            d.put("jellyfin_url", "http://192.168.3.95:8096");
            d.put("screensaver_minutes", 5);
            d.put("photos_dir", "/sdcard/Pictures/Screensaver");
            d.put("bing_market", "en-NZ");
        } catch (Exception ignored) {
        }
        return d;
    }

    String str(String k) {
        return j.has(k) ? j.optString(k, defaults.optString(k)) : defaults.optString(k);
    }

    double dbl(String k) {
        return j.has(k) ? j.optDouble(k, defaults.optDouble(k)) : defaults.optDouble(k);
    }

    List<String> list(String k) {
        JSONArray a = j.optJSONArray(k);
        if (a == null) a = defaults.optJSONArray(k);
        List<String> out = new ArrayList<>();
        if (a != null) for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        return out;
    }
}
