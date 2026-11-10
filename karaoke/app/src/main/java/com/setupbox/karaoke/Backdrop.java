package com.setupbox.karaoke;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.SystemClock;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Background for songs that have no music video: a generated, animated colour scene (a dark gradient with large soft colour
 * blobs drifting slowly) in a NEW RANDOM PALETTE for every song. Nothing is downloaded and nothing is bundled, so there is no
 * licensing question and it works offline. If the user has put pictures of their own in the photo folder they are shown as a
 * slideshow over the colours.
 */
public final class Backdrop {
    private static final long SLIDE_MS = 15000;
    private static final long FRAME_MS = 66;          // ~15 fps is plenty for slow drifting and keeps the weak box GPU free for the video

    private final ImageView view;
    private final File photoDir;
    private final Handler ui;
    private final Scene scene = new Scene();
    private boolean running = false;
    private final List<File> photos = new ArrayList<>();
    private boolean loaded = false;
    private int next = 0;

    public Backdrop(ImageView view, File photoDir, Handler ui) {
        this.view = view;
        this.photoDir = photoDir;
        this.ui = ui;
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        view.setBackground(scene);
    }

    /** @param withPhotos true = also run the slideshow of the user's own pictures (if there are any) over the colours */
    public void start(boolean withPhotos) {
        if (running) return;
        running = true;
        scene.randomize(new Random());
        ui.post(frame);
        if (withPhotos) ui.post(slide);
    }

    public void stop() {
        running = false;
        ui.removeCallbacks(slide);
        ui.removeCallbacks(frame);
        view.setImageDrawable(null);
    }

    private final Runnable frame = new Runnable() {
        @Override public void run() {
            if (!running) return;
            scene.invalidateSelf();
            ui.postDelayed(this, FRAME_MS);
        }
    };

    // ---------- the generated colour scene ----------

    private static final class Scene extends Drawable {
        private static final int BLOBS = 5;
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint[] blob = new Paint[BLOBS];
        private final RadialGradient[] shader = new RadialGradient[BLOBS];
        private final float[] fx = new float[BLOBS], fy = new float[BLOBS], px = new float[BLOBS], py = new float[BLOBS];
        private final float[] cx = new float[BLOBS], cy = new float[BLOBS], ax = new float[BLOBS], ay = new float[BLOBS], rad = new float[BLOBS];
        private final Matrix m = new Matrix();
        private int w = 0, h = 0;
        private int c1 = 0xFF141E3C, c2 = 0xFF3A1C4A;
        private final int[] blobColor = new int[BLOBS];
        private final long t0 = SystemClock.uptimeMillis();

        Scene() {
            for (int i = 0; i < BLOBS; i++) {
                blob[i] = new Paint(Paint.ANTI_ALIAS_FLAG);
                blobColor[i] = 0xFF3A5A9A;
            }
            randomize(new Random(7));
        }

        /** A fresh random palette and fresh motion for every song. */
        void randomize(Random r) {
            float hue = r.nextFloat() * 360f;
            c1 = Color.HSVToColor(new float[]{hue, 0.62f, 0.36f});
            c2 = Color.HSVToColor(new float[]{(hue + 25f + r.nextFloat() * 30f) % 360f, 0.68f, 0.50f});
            for (int i = 0; i < BLOBS; i++) {
                float bh = (hue + (r.nextFloat() - 0.5f) * 110f + 360f) % 360f;
                blobColor[i] = Color.HSVToColor(new float[]{bh, 0.55f + r.nextFloat() * 0.3f, 0.85f + r.nextFloat() * 0.15f});
                cx[i] = 0.1f + r.nextFloat() * 0.8f;               // centre of its drift, as a share of the screen
                cy[i] = 0.1f + r.nextFloat() * 0.8f;
                ax[i] = 0.10f + r.nextFloat() * 0.20f;             // how far it drifts
                ay[i] = 0.08f + r.nextFloat() * 0.18f;
                fx[i] = (float) (2 * Math.PI / (22000 + r.nextInt(26000)));   // one swing takes 22-48 s
                fy[i] = (float) (2 * Math.PI / (22000 + r.nextInt(26000)));
                px[i] = r.nextFloat() * 6.28f;
                py[i] = r.nextFloat() * 6.28f;
                rad[i] = 0.28f + r.nextFloat() * 0.22f;            // radius as a share of the screen width
            }
            w = 0;                                                  // shaders are rebuilt for the new colours on the next draw
        }

        private void build() {
            bg.setShader(new LinearGradient(0, 0, w, h, c1, c2, Shader.TileMode.CLAMP));
            for (int i = 0; i < BLOBS; i++) {
                float r = Math.max(1f, rad[i] * w);
                int mid = (blobColor[i] & 0x00FFFFFF) | 0x88000000;           // 40 % opaque in the middle, fading to nothing
                shader[i] = new RadialGradient(0, 0, r, new int[]{mid, (blobColor[i] & 0x00FFFFFF) | 0x2A000000, 0x00000000}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
                blob[i].setShader(shader[i]);
            }
        }

        @Override public void draw(Canvas c) {
            int cw = c.getWidth(), ch = c.getHeight();
            if (cw == 0 || ch == 0) return;
            if (cw != w || ch != h || bg.getShader() == null || w == 0) { w = cw; h = ch; build(); }
            c.drawRect(0, 0, w, h, bg);
            float t = SystemClock.uptimeMillis() - t0;
            for (int i = 0; i < BLOBS; i++) {
                float x = (cx[i] + ax[i] * (float) Math.sin(t * fx[i] + px[i])) * w;
                float y = (cy[i] + ay[i] * (float) Math.sin(t * fy[i] + py[i])) * h;
                m.setTranslate(x, y);
                shader[i].setLocalMatrix(m);
                c.drawCircle(x, y, rad[i] * w, blob[i]);
            }
        }

        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(ColorFilter f) { }
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }

    // ---------- the user's own pictures (optional) ----------

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
                if (!loaded) { loadPhotos(); loaded = true; }
                if (photos.isEmpty()) return;                      // no pictures of their own: the colour scene stays
                final Bitmap bmp = decode(photos.get(next++ % photos.size()));
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

    private void loadPhotos() {
        File[] fs = photoDir == null ? null : photoDir.listFiles();
        if (fs != null) {
            for (File f : fs) {
                String n = f.getName().toLowerCase();
                if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")) photos.add(f);
            }
        }
        Collections.shuffle(photos);
    }

    private static Bitmap decode(File f) {
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
            in.close();
            byte[] data = out.toByteArray();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            o.inSampleSize = Math.max(1, o.outWidth / 1280);
            o.inJustDecodeBounds = false;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Throwable t) { return null; }
    }
}
