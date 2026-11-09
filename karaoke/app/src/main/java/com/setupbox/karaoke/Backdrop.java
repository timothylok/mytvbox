package com.setupbox.karaoke;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Background for songs that have no music video: a slow colour-shifting gradient, and over it a slideshow
 * of photos - the files in a local folder if there are any, otherwise Bing's daily pictures (the same source
 * the launcher's screensaver uses). If the box is offline the gradient simply stays.
 */
public final class Backdrop {
    private static final long SLIDE_MS = 15000;

    private final ImageView view;
    private final File photoDir;
    private final Handler ui;
    private final GradientDrawable gradient = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF141E3C, 0xFF3A1C4A});
    private ValueAnimator colours;
    private boolean running = false;
    private final List<Object> sources = new ArrayList<>();   // File or String (url)
    private boolean loaded = false;
    private int next = 0;

    public Backdrop(ImageView view, File photoDir, Handler ui) {
        this.view = view;
        this.photoDir = photoDir;
        this.ui = ui;
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        view.setBackground(gradient);
    }

    /** @param photos true = also run the photo slideshow over the gradient */
    public void start(boolean photos) {
        if (running) return;
        running = true;
        colours = ValueAnimator.ofFloat(0f, 1f);
        colours.setDuration(25000);
        colours.setRepeatMode(ValueAnimator.REVERSE);
        colours.setRepeatCount(ValueAnimator.INFINITE);
        colours.setInterpolator(new LinearInterpolator());
        final ArgbEvaluator ev = new ArgbEvaluator();
        colours.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                float f = (Float) a.getAnimatedValue();
                gradient.setColors(new int[]{(Integer) ev.evaluate(f, 0xFF141E3C, 0xFF0F3B3A), (Integer) ev.evaluate(f, 0xFF3A1C4A, 0xFF4A2A14)});
            }
        });
        colours.start();
        if (photos) ui.post(slide);
    }

    public void stop() {
        running = false;
        ui.removeCallbacks(slide);
        if (colours != null) colours.cancel();
        view.setImageDrawable(null);
    }

    private final Runnable slide = new Runnable() {
        @Override public void run() {
            if (!running) return;
            showNext();
            ui.postDelayed(this, SLIDE_MS);
        }
    };

    private void showNext() {
        new Thread(new Runnable() {
            @Override public void run() {
                if (!loaded) { loadSources(); loaded = true; }
                if (sources.isEmpty()) return;
                Object src = sources.get(next++ % sources.size());
                final Bitmap bmp = decode(src);
                if (bmp == null) return;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (!running) return;
                        view.setImageBitmap(bmp);
                        view.setAlpha(0f);
                        view.animate().alpha(1f).setDuration(1200).start();
                    }
                });
            }
        }).start();
    }

    private void loadSources() {
        File[] fs = photoDir == null ? null : photoDir.listFiles();
        if (fs != null) {
            for (File f : fs) {
                String n = f.getName().toLowerCase();
                if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")) sources.add(f);
            }
        }
        if (sources.isEmpty()) {
            try {
                JSONArray imgs = new JSONObject(new String(download("https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=en-NZ"), "UTF-8")).getJSONArray("images");
                for (int i = 0; i < imgs.length(); i++) sources.add("https://www.bing.com" + imgs.getJSONObject(i).getString("url"));
            } catch (Exception ignored) { }     // offline: keep the gradient
        }
        Collections.shuffle(sources);
    }

    private Bitmap decode(Object src) {
        try {
            byte[] data;
            if (src instanceof File) {
                java.io.FileInputStream in = new java.io.FileInputStream((File) src);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
                in.close();
                data = out.toByteArray();
            } else {
                data = download((String) src);
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            o.inSampleSize = Math.max(1, o.outWidth / 1280);
            o.inJustDecodeBounds = false;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Throwable t) { return null; }
    }

    private static byte[] download(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
            return out.toByteArray();
        } finally { c.disconnect(); }
    }
}
