package com.setupbox.karaoke;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;

import java.io.File;

/** Step 2: plays one file (intent extra "path") with a switchable vocal channel. */
public class MainActivity extends Activity {
    private static final String[] MODE_NAMES = {"Both channels (mixed)", "Karaoke - instrumental (left)", "Vocal - original (right)"};
    private ExoPlayer player;
    private final VocalProcessor vocal = new VocalProcessor();
    private TextView status, lyricsView;
    private Lrc lrc;
    private int lastLine = -2;
    private long lyricOffsetMs = 0;   // positive = lyrics appear later
    private boolean longPressed = false;
    private String songPath = "";
    private final android.os.Handler ui = new android.os.Handler();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        SurfaceView surface = new SurfaceView(this);
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1));
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setBackgroundColor(0x99000000);
        status.setTextSize(24);
        status.setPadding(24, 12, 24, 12);
        root.addView(status, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START));
        lyricsView = new TextView(this);
        lyricsView.setGravity(Gravity.CENTER);
        lyricsView.setPadding(60, 0, 60, 160);
        root.addView(lyricsView, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        setContentView(root);

        DefaultRenderersFactory rf = new DefaultRenderersFactory(this) {
            @Override protected AudioSink buildAudioSink(Context c, boolean floatOut, boolean params) {
                return new DefaultAudioSink.Builder(c)
                        .setAudioProcessors(new androidx.media3.common.audio.AudioProcessor[]{vocal})
                        .build();
            }
        };
        player = new ExoPlayer.Builder(this, rf).build();
        player.setVideoSurfaceView(surface);
        String path = getIntent().getStringExtra("path");
        if (getIntent().getBooleanExtra("scan", false)) { debugScan(getIntent().getStringExtra("q")); return; }
        if (path == null) {
            status.setText("No file. Start with: --es path /sdcard/...");
            return;
        }
        String base = path.replaceFirst("\\.[^./]+$", "");
        lrc = Lrc.load(new File(base + ".lrc"));
        songPath = path;
        loadOffset();
        player.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(new File(path))));
        player.prepare();
        player.play();
        player.addListener(new Player.Listener() {
            @Override public void onIsPlayingChanged(boolean p) { showStatus(); }
            @Override public void onPlaybackStateChanged(int st) { showStatus(); }
            @Override public void onPlayerError(androidx.media3.common.PlaybackException e) {
                status.setText("Error: " + e.getMessage());
            }
        });
        vocal.setMode(VocalProcessor.LEFT);
        ui.post(tick);
    }

    private static String fmt(long ms) { long s = Math.max(0, ms) / 1000; return String.format("%d:%02d", s / 60, s % 60); }

    private void showStatus() {
        String st;
        int ps = player.getPlaybackState();
        if (ps == Player.STATE_BUFFERING) st = "Buffering";
        else if (ps == Player.STATE_ENDED) st = "Ended";
        else st = player.getPlayWhenReady() ? "Playing" : "Paused";
        long dur = player.getDuration();
        String time = fmt(player.getCurrentPosition()) + (dur > 0 ? " / " + fmt(dur) : "");
        status.setText(st + "  |  " + MODE_NAMES[vocal.getMode()] + "  |  " + time
                + (lrc != null ? "  |  Lyrics " + (lyricOffsetMs >= 0 ? "+" : "-") + String.format("%.1fs", Math.abs(lyricOffsetMs) / 1000.0) : "")
                + "\n[OK pause | hold OK / Menu: vocal mode | Left/Right seek | Up: lyrics later | Down: lyrics earlier]");
    }

    private void showLyrics() {
        if (lrc == null || lrc.lines.isEmpty()) return;
        int i = lrc.indexAt(player.getCurrentPosition() - lyricOffsetMs);
        if (i == lastLine) return;
        lastLine = i;
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        for (int k = Math.max(0, i - 1); k <= Math.min(lrc.lines.size() - 1, i + 3); k++) {
            String t = lrc.lines.get(k).text;
            if (t.isEmpty()) t = " ";
            int st = sb.length();
            sb.append(t).append("\n\n");
            boolean cur = k == i;
            sb.setSpan(new android.text.style.ForegroundColorSpan(cur ? 0xFFFFE23B : 0x99FFFFFF), st, st + t.length(), 0);
            sb.setSpan(new android.text.style.AbsoluteSizeSpan(cur ? 64 : 40, false), st, st + t.length(), 0);
        }
        lyricsView.setText(sb);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() { if (player != null) { showStatus(); showLyrics(); } ui.postDelayed(this, 250); }
    };

    @Override public boolean onKeyDown(int k, KeyEvent e) {
        if (player == null) return super.onKeyDown(k, e);
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                e.startTracking(); return true; // short press = pause (on key up), long press = vocal mode
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                player.setPlayWhenReady(!player.getPlayWhenReady()); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: player.seekTo(player.getCurrentPosition() + 10000); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: player.seekTo(Math.max(0, player.getCurrentPosition() - 10000)); return true;
            case KeyEvent.KEYCODE_DPAD_UP: nudge(500); return true;     // lyrics appear later
            case KeyEvent.KEYCODE_DPAD_DOWN: nudge(-500); return true;  // lyrics appear earlier
            case KeyEvent.KEYCODE_MENU: case KeyEvent.KEYCODE_V:
                cycleMode(); return true;
        }
        return super.onKeyDown(k, e);
    }

    @Override public boolean onKeyLongPress(int k, KeyEvent e) {
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) { longPressed = true; cycleMode(); return true; }
        return super.onKeyLongPress(k, e);
    }

    @Override public boolean onKeyUp(int k, KeyEvent e) {
        if ((k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) && player != null) {
            if (!longPressed) player.setPlayWhenReady(!player.getPlayWhenReady());
            longPressed = false;
            return true;
        }
        return super.onKeyUp(k, e);
    }

    /** Developer check: adb shell am start ... --ez scan true [--es q "text"] shows the library summary. */
    private void debugScan(final String q) {
        status.setText("Scanning...");
        new Thread(new Runnable() {
            @Override public void run() {
                long t0 = System.currentTimeMillis();
                File root = Library.findRoot();
                if (root == null) { post("Karaoke folder not found (USB drive missing?)"); return; }
                Library lib = Library.scan(root);
                long ms = System.currentTimeMillis() - t0;
                lib.save(new File(android.os.Environment.getExternalStorageDirectory(), "Karaoke/index.json"));
                StringBuilder sb = new StringBuilder("Root: " + root + "\n" + lib.songs.size() + " songs, "
                        + lib.languages() + ", " + lib.artists(null).size() + " artists, scan " + ms + " ms\n");
                java.util.List<Library.Song> hits = lib.search(q == null ? "" : q, null, null);
                sb.append("Search '" + (q == null ? "" : q) + "': " + hits.size() + " hits\n");
                for (int i = 0; i < Math.min(8, hits.size()); i++) {
                    Library.Song s = hits.get(i);
                    sb.append(s.language + " / " + s.artist + " / " + s.title + (s.hasLrc ? "  [lyrics]" : "") + "\n");
                }
                android.util.Log.i("KaraokeScan", sb.toString());
                post(sb.toString());
            }
            private void post(final String t) { runOnUiThread(new Runnable() { @Override public void run() { status.setText(t); } }); }
        }).start();
    }

    private void cycleMode() { vocal.setMode((vocal.getMode() + 1) % 3); showStatus(); }

    private File offsetFile() { return new File(android.os.Environment.getExternalStorageDirectory(), "Karaoke/lyric_offsets.properties"); }

    private void loadOffset() {
        try (java.io.FileInputStream in = new java.io.FileInputStream(offsetFile())) {
            java.util.Properties p = new java.util.Properties();
            p.load(new java.io.InputStreamReader(in, "UTF-8"));
            lyricOffsetMs = Long.parseLong(p.getProperty(songPath, "0"));
        } catch (Exception ignored) { lyricOffsetMs = 0; }
    }

    private void saveOffset() {
        try {
            java.util.Properties p = new java.util.Properties();
            File f = offsetFile();
            if (f.exists()) try (java.io.FileInputStream in = new java.io.FileInputStream(f)) { p.load(new java.io.InputStreamReader(in, "UTF-8")); }
            if (lyricOffsetMs == 0) p.remove(songPath); else p.setProperty(songPath, String.valueOf(lyricOffsetMs));
            f.getParentFile().mkdirs();
            try (java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(new java.io.FileOutputStream(f), "UTF-8")) { p.store(w, null); }
        } catch (Exception ignored) { }
    }

    private void nudge(long deltaMs) {
        lyricOffsetMs += deltaMs;
        lastLine = -2;
        saveOffset();
        showStatus();
        showLyrics();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        if (player != null) player.release();
    }
}
