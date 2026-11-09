package com.setupbox.karaoke;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Karaoke app: plays the queue, shows lyrics, and runs the phone web server.
 * Debug: starting with --es path /some/file.mp3 plays just that file (no queue, no server).
 */
public class MainActivity extends Activity implements Server.Host {
    private static final int PORT = 8080;

    private ExoPlayer player;
    private final VocalProcessor vocal = new VocalProcessor();
    private TextView status, lyricsView, idleInfo;
    private LinearLayout idleBox;
    private ImageView qrSmall;
    private long hintUntil = 0;       // show the key help line until this time
    private final android.os.Handler ui = new android.os.Handler();

    // lyrics
    private Lrc lrc;
    private int lastLine = -2;
    private long lyricOffsetMs = 0;   // positive = lyrics appear later
    private String songPath = "";
    private boolean longPressed = false;

    // karaoke state
    private volatile Library lib = Library.empty();
    private QueueManager queue;
    private Server server;
    private String ip = "";
    private String libraryNote = "Starting...";
    private boolean debugSingleFile = false;
    private boolean vocalOn = false;                 // false = karaoke (singer sings), true = original vocal
    private String currentSongId = "";
    private final Set<String> flipped = Collections.synchronizedSet(new HashSet<String>());

    // player state cached for the web server thread (ExoPlayer may only be touched on the main thread)
    private volatile boolean cPlaying, cPaused;
    private volatile long cPos, cDur;

    // ---------- start-up ----------

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        SurfaceView surface = new SurfaceView(this);
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1));
        lyricsView = new TextView(this);
        lyricsView.setGravity(Gravity.CENTER);
        lyricsView.setPadding(60, 0, 60, 160);
        root.addView(lyricsView, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        ip = localIp();
        final String url = "http://" + ip + ":" + PORT;
        android.graphics.Bitmap bigQr = qr(url, 440), smallQr = qr(url, 220);

        idleBox = new LinearLayout(this);
        idleBox.setOrientation(LinearLayout.VERTICAL);
        idleBox.setGravity(Gravity.CENTER);
        idleBox.setBackgroundColor(0xFF101418);
        TextView idleTitle = new TextView(this);
        idleTitle.setText("Karaoke");
        idleTitle.setTextColor(0xFFFFE23B);
        idleTitle.setTextSize(64);
        idleTitle.setGravity(Gravity.CENTER);
        idleBox.addView(idleTitle);
        ImageView qrBig = new ImageView(this);
        qrBig.setImageBitmap(bigQr);
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(440, 440);
        qlp.setMargins(0, 24, 0, 24);
        idleBox.addView(qrBig, qlp);
        TextView idleUrl = new TextView(this);
        idleUrl.setText(url);
        idleUrl.setTextColor(Color.WHITE);
        idleUrl.setTextSize(36);
        idleUrl.setGravity(Gravity.CENTER);
        idleBox.addView(idleUrl);
        idleInfo = new TextView(this);
        idleInfo.setTextColor(0xFF9AA0AD);
        idleInfo.setTextSize(26);
        idleInfo.setGravity(Gravity.CENTER);
        idleInfo.setPadding(40, 16, 40, 0);
        idleBox.addView(idleInfo);
        root.addView(idleBox, new FrameLayout.LayoutParams(-1, -1));

        qrSmall = new ImageView(this);
        qrSmall.setImageBitmap(smallQr);
        qrSmall.setAlpha(0.85f);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(160, 160, Gravity.TOP | Gravity.END);
        slp.setMargins(0, 24, 24, 0);
        root.addView(qrSmall, slp);

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setBackgroundColor(0x99000000);
        status.setTextSize(24);
        status.setPadding(24, 12, 24, 12);
        root.addView(status, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START));
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
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int st) {
                if (st == Player.STATE_ENDED && !debugSingleFile) playNext();
                refresh();
            }
            @Override public void onIsPlayingChanged(boolean p) { refresh(); }
            @Override public void onPlayerError(androidx.media3.common.PlaybackException e) {
                if (debugSingleFile) status.setText("Error: " + e.getMessage());
                else playNext();                    // a broken file must not stall the party
            }
        });

        String path = getIntent().getStringExtra("path");
        if (path != null) { debugSingleFile = true; startFile(new File(path)); ui.post(tick); return; }

        queue = QueueManager.withFile(new File(android.os.Environment.getExternalStorageDirectory(), "Karaoke/state.txt"));
        loadFlips();
        server = new Server(this, this, PORT);
        try { server.start(); } catch (Exception e) { libraryNote = "Web server failed: " + e.getMessage(); }
        rescan();
        ui.post(tick);
    }

    /** Re-reads the Karaoke folder off the main thread; starts playing if songs are waiting. */
    private void rescan() {
        new Thread(new Runnable() {
            @Override public void run() {
                File root = Library.findRoot();
                final Library l = root == null ? null : Library.scan(root);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (l == null) { lib = Library.empty(); libraryNote = "Karaoke folder not found - is the USB drive plugged in?"; }
                        else { lib = l; libraryNote = ""; l.save(new File(android.os.Environment.getExternalStorageDirectory(), "Karaoke/index.json")); }
                        kick();
                    }
                });
            }
        }).start();
    }

    // ---------- playing the queue ----------

    /** If nothing is playing and songs are waiting, start the next one. */
    private void kick() {
        if (debugSingleFile || player == null) return;
        if (!cPlaying && !cPaused && player.getPlaybackState() != Player.STATE_BUFFERING && !queue.upcoming().isEmpty()) playNext();
        refresh();
    }

    private void playNext() {
        for (;;) {
            QueueManager.Item it = queue.advance();
            if (it == null) { stopPlayback(); return; }
            Library.Song s = lib.byId(it.songId);
            File f = s == null ? null : lib.fileOf(s);
            if (f == null || !f.isFile()) continue;     // file vanished: skip it
            currentSongId = s.id;
            vocalOn = false;
            startFile(f);
            return;
        }
    }

    private void stopPlayback() {
        currentSongId = "";
        player.stop();
        player.clearMediaItems();
        lrc = null;
        lyricsView.setText("");
        cPlaying = false; cPaused = false;
        refresh();
    }

    private void startFile(File f) {
        songPath = f.getPath();
        String base = songPath.replaceFirst("\\.[^./]+$", "");
        lrc = Lrc.load(new File(base + ".lrc"));
        lastLine = -2;
        lyricsView.setText("");
        loadOffset();
        applyChannel();
        player.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(f)));
        player.prepare();
        player.play();
        cPlaying = true; cPaused = false;
        refresh();
    }

    // ---------- vocal channel ----------

    /** Karaoke channel is LEFT unless this song was flipped; vocal on = the other channel. */
    private void applyChannel() {
        boolean flip = flipped.contains(currentSongId);
        boolean left = vocalOn == flip;      // karaoke+normal -> LEFT, vocal+normal -> RIGHT, flipped swaps
        vocal.setMode(left ? VocalProcessor.LEFT : VocalProcessor.RIGHT);
    }

    private File flipFile() { return new File(android.os.Environment.getExternalStorageDirectory(), "Karaoke/flips.txt"); }

    private void loadFlips() {
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(flipFile()), "UTF-8"));
            for (String l; (l = r.readLine()) != null; ) if (!l.trim().isEmpty()) flipped.add(l.trim());
            r.close();
        } catch (Exception ignored) { }
    }

    private void saveFlips() {
        try {
            File f = flipFile();
            f.getParentFile().mkdirs();
            java.io.Writer w = new java.io.OutputStreamWriter(new java.io.FileOutputStream(f), "UTF-8");
            synchronized (flipped) { for (String s : flipped) w.write(s + "\n"); }
            w.close();
        } catch (Exception ignored) { }
    }

    // ---------- Server.Host (called from the web server threads) ----------

    @Override public Library library() { return lib; }
    @Override public QueueManager queue() { return queue; }

    @Override public void command(final String cmd) {
        runOnUiThread(new Runnable() {
            @Override public void run() { handle(cmd); }
        });
    }

    private void handle(String cmd) {
        switch (cmd) {
            case "kick": kick(); break;
            case "rescan": rescan(); break;
            case "pause": if (cPlaying) { player.pause(); cPaused = true; } break;
            case "resume": if (cPaused) { player.play(); cPaused = false; } break;
            case "skip": if (cPlaying || cPaused) playNext(); break;
            case "restart": if (cPlaying || cPaused) player.seekTo(0); break;
            case "vocal": vocalOn = !vocalOn; applyChannel(); break;
            case "flip":
                if (!currentSongId.isEmpty()) {
                    if (!flipped.remove(currentSongId)) flipped.add(currentSongId);
                    saveFlips();
                    applyChannel();
                }
                break;
        }
        refresh();
    }

    @Override public JSONObject state() {
        JSONObject o = new JSONObject();
        try {
            Library l = lib;
            o.put("songCount", l.songs.size()).put("note", libraryNote).put("url", "http://" + ip + ":" + PORT);
            o.put("version", queue.version());
            QueueManager.Item cur = queue.current();
            o.put("current", cur == null ? JSONObject.NULL : itemJson(cur, l));
            JSONArray up = new JSONArray();
            for (QueueManager.Item it : queue.upcoming()) up.put(itemJson(it, l));
            o.put("upcoming", up);
            o.put("playing", cPlaying && !cPaused).put("paused", cPaused);
            o.put("posMs", cPos).put("durMs", cDur);
            o.put("vocalOn", vocalOn).put("flipped", flipped.contains(currentSongId));
        } catch (Exception ignored) { }
        return o;
    }

    private JSONObject itemJson(QueueManager.Item it, Library l) throws Exception {
        Library.Song s = l.byId(it.songId);
        return new JSONObject().put("id", it.id).put("songId", it.songId).put("singer", it.singer)
                .put("title", s == null ? "(missing song)" : s.title)
                .put("artist", s == null ? "" : s.artist)
                .put("language", s == null ? "" : s.language)
                .put("lrc", s != null && s.hasLrc);
    }

    // ---------- screen ----------

    private static String fmt(long ms) { long s = Math.max(0, ms) / 1000; return String.format("%d:%02d", s / 60, s % 60); }

    private void refresh() {
        if (player == null) return;
        int ps = player.getPlaybackState();
        cPos = player.getCurrentPosition();
        cDur = Math.max(0, player.getDuration());
        if (ps == Player.STATE_ENDED) { cPlaying = false; cPaused = false; }
        showStatus();
    }

    private String titleOf(QueueManager.Item it) {
        Library.Song s = lib.byId(it.songId);
        String t = s == null ? "(missing song)" : s.title + (s.artist.isEmpty() ? "" : "  (" + s.artist + ")");
        return it.singer.isEmpty() ? t : t + "   Singer: " + it.singer;
    }

    private void showStatus() {
        boolean active = cPlaying || cPaused;
        boolean idle = !active && !debugSingleFile;
        idleBox.setVisibility(idle ? android.view.View.VISIBLE : android.view.View.GONE);
        qrSmall.setVisibility(active && !debugSingleFile ? android.view.View.VISIBLE : android.view.View.GONE);
        if (idle) {
            String info = !libraryNote.isEmpty() ? libraryNote
                    : lib.songs.size() + " songs  -  scan the code to pick one";
            idleInfo.setText(info);
            status.setVisibility(android.view.View.INVISIBLE);
            return;
        }
        status.setVisibility(android.view.View.VISIBLE);
        String st = player.getPlaybackState() == Player.STATE_BUFFERING ? "Buffering" : (cPaused ? "Paused" : "Playing");
        String time = fmt(cPos) + (cDur > 0 ? " / " + fmt(cDur) : "");
        StringBuilder sb = new StringBuilder();
        if (!debugSingleFile) {
            QueueManager.Item cur = queue.current();
            java.util.List<QueueManager.Item> up = queue.upcoming();
            sb.append("Now: ").append(cur == null ? "-" : titleOf(cur)).append('\n');
            sb.append("Next: ").append(up.isEmpty() ? "-" : titleOf(up.get(0)))
              .append(up.size() > 1 ? "  (+" + (up.size() - 1) + " more)" : "").append('\n');
        }
        sb.append(st).append("  |  ").append(vocalOn ? "VOCAL ON" : "KARAOKE").append("  |  ").append(time);
        if (lrc != null) sb.append("  |  Lyrics ").append(lyricOffsetMs >= 0 ? "+" : "-").append(String.format("%.1fs", Math.abs(lyricOffsetMs) / 1000.0));
        if (System.currentTimeMillis() < hintUntil) {
            sb.append("\nOK: pause   hold OK / Menu: vocal   Left/Right: seek or skip   Up/Down: lyrics later/earlier");
        }
        status.setText(sb.toString());
    }

    /** QR code for the phone address (dark modules on white, with a quiet zone). */
    private static android.graphics.Bitmap qr(String text, int px) {
        try {
            java.util.Map<com.google.zxing.EncodeHintType, Object> hints = new java.util.HashMap<>();
            hints.put(com.google.zxing.EncodeHintType.MARGIN, 2);
            com.google.zxing.common.BitMatrix m = new com.google.zxing.qrcode.QRCodeWriter()
                    .encode(text, com.google.zxing.BarcodeFormat.QR_CODE, px, px, hints);
            int[] pix = new int[px * px];
            for (int y = 0; y < px; y++) for (int x = 0; x < px; x++) pix[y * px + x] = m.get(x, y) ? Color.BLACK : Color.WHITE;
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888);
            bmp.setPixels(pix, 0, px, 0, 0, px, px);
            return bmp;
        } catch (Exception e) { return null; }
    }

    private void showLyrics() {
        if (lrc == null || lrc.lines.isEmpty() || !(cPlaying || cPaused)) return;
        long pos = player.getCurrentPosition() - lyricOffsetMs;
        int i = lrc.indexAt(pos);

        // Which character of the current line is being sung? LRC only times whole lines, so the line's
        // time (up to the next line, at most ~0.45 s per character) is shared out evenly over its characters.
        int ci = -1, total = 0;
        if (i >= 0) {
            Lrc.Line cl = lrc.lines.get(i);
            for (int j = 0; j < cl.text.length(); j++) if (!Character.isWhitespace(cl.text.charAt(j))) total++;
            if (total > 0) {
                long next = i + 1 < lrc.lines.size() ? lrc.lines.get(i + 1).ms : Long.MAX_VALUE;
                long dur = Math.max(1000, Math.min(next - cl.ms, total * 450L));
                long el = pos - cl.ms;
                ci = el >= dur ? total : (int) (el * total / dur);   // total = whole line already sung
            }
        }
        int key = i * 1000 + ci;
        if (key == lastLine) return;
        lastLine = key;

        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        for (int k = Math.max(0, i - 1); k <= Math.min(lrc.lines.size() - 1, i + 3); k++) {
            String t = lrc.lines.get(k).text;
            if (t.isEmpty()) t = " ";
            int st = sb.length();
            sb.append(t).append("\n\n");
            boolean cur = k == i;
            sb.setSpan(new android.text.style.AbsoluteSizeSpan(cur ? 64 : 40, false), st, st + t.length(), 0);
            if (!cur) {
                sb.setSpan(new android.text.style.ForegroundColorSpan(0x99FFFFFF), st, st + t.length(), 0);
                continue;
            }
            int rank = 0;
            for (int j = 0; j < t.length(); j++) {
                if (Character.isWhitespace(t.charAt(j))) { sb.setSpan(new android.text.style.ForegroundColorSpan(0xFFFFFFFF), st + j, st + j + 1, 0); continue; }
                int color = rank < ci ? 0xFFFFE23B            // already sung: yellow
                        : rank == ci ? 0xFFFF7A3B             // being sung now: orange + underline
                        : 0xFFFFFFFF;                          // still to come: white
                sb.setSpan(new android.text.style.ForegroundColorSpan(color), st + j, st + j + 1, 0);
                if (rank == ci) sb.setSpan(new android.text.style.UnderlineSpan(), st + j, st + j + 1, 0);
                rank++;
            }
        }
        lyricsView.setText(sb);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() { refresh(); showLyrics(); ui.postDelayed(this, 250); }
    };

    // ---------- remote control keys ----------

    @Override public boolean onKeyDown(int k, KeyEvent e) {
        if (player == null) return super.onKeyDown(k, e);
        hintUntil = System.currentTimeMillis() + 6000;
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                e.startTracking(); return true; // short press = pause (on key up), long press = vocal
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: togglePause(); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (debugSingleFile) player.seekTo(player.getCurrentPosition() + 10000); else if (cPlaying || cPaused) playNext();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: player.seekTo(Math.max(0, player.getCurrentPosition() - 10000)); return true;
            case KeyEvent.KEYCODE_DPAD_UP: nudge(500); return true;     // lyrics appear later
            case KeyEvent.KEYCODE_DPAD_DOWN: nudge(-500); return true;  // lyrics appear earlier
            case KeyEvent.KEYCODE_MENU: case KeyEvent.KEYCODE_V: handle("vocal"); return true;
        }
        return super.onKeyDown(k, e);
    }

    @Override public boolean onKeyLongPress(int k, KeyEvent e) {
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) { longPressed = true; handle("vocal"); return true; }
        return super.onKeyLongPress(k, e);
    }

    @Override public boolean onKeyUp(int k, KeyEvent e) {
        if ((k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) && player != null) {
            if (!longPressed) togglePause();
            longPressed = false;
            return true;
        }
        return super.onKeyUp(k, e);
    }

    private void togglePause() { handle(cPaused ? "resume" : "pause"); }

    // ---------- lyric timing nudge (saved per song) ----------

    private File offsetFile() { return new File(android.os.Environment.getExternalStorageDirectory(), "Karaoke/lyric_offsets.properties"); }

    private void loadOffset() {
        lyricOffsetMs = 0;
        try (java.io.FileInputStream in = new java.io.FileInputStream(offsetFile())) {
            java.util.Properties p = new java.util.Properties();
            p.load(new java.io.InputStreamReader(in, "UTF-8"));
            lyricOffsetMs = Long.parseLong(p.getProperty(songPath, "0"));
        } catch (Exception ignored) { }
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
        refresh();
        showLyrics();
    }

    // ---------- misc ----------

    private static String localIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (ni.isLoopback() || !ni.isUp()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        return "?.?.?.?";
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        if (server != null) server.stop();
        if (player != null) player.release();
    }
}
