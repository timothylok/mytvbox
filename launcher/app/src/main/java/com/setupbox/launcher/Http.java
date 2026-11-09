package com.setupbox.launcher;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Tiny blocking HTTP + image helpers. Call from background threads only. */
final class Http {
    private Http() { }

    static byte[] bytes(String url, int timeoutMs, int maxBytes) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestProperty("User-Agent", "Mozilla/5.0");
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            if (code >= 400) throw new IOException("HTTP " + code);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > maxBytes) throw new IOException("response too large");
                out.write(buf, 0, n);
            }
            in.close();
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    static String get(String url, int timeoutMs) throws IOException {
        return new String(bytes(url, timeoutMs, 4000000), "UTF-8");
    }

    static Bitmap bitmap(String url, int maxW) throws IOException {
        byte[] b = bytes(url, 15000, 12000000);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(b, 0, b.length, o);
        o.inSampleSize = sample(o.outWidth, maxW);
        o.inJustDecodeBounds = false;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(b, 0, b.length, o);
    }

    static Bitmap decodeFile(String path, int maxW) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
        o.inSampleSize = sample(o.outWidth, maxW);
        o.inJustDecodeBounds = false;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(path, o);
    }

    private static int sample(int w, int maxW) {
        int s = 1;
        while (w / (s * 2) >= maxW) s *= 2;
        return s;
    }
}
