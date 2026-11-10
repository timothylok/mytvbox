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
    private TextView status, statusBar, lyricsView, idleInfo;
    private LinearLayout idleBox;
    private ImageView qrSmall, backdropView;
    private FrameLayout rootView;
    private SurfaceView surfaceView;
    private android.view.View dimView;
    private TextView countdownView, bannerView;
    private Backdrop backdrop;
    private int lastCount = -1;                 // countdown dots currently shown
    private volatile boolean scanning = false;
    private boolean wantMp3 = false;            // a music video is merged in: its own audio must not be heard
    private long hintUntil = 0;                 // show the key help line until this time
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
        rootView = root;
        backdropView = new ImageView(this);                    // gradient (+ photos when a song has no music video)
        backdropView.setVisibility(android.view.View.GONE);
        root.addView(backdropView, new FrameLayout.LayoutParams(-1, -1));
        // The video surface comes after the backdrop and is sized to the video's own shape, so whatever the
        // video does not cover (side bars of a square video) shows the gradient instead of black.
        surfaceView = new SurfaceView(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        final SurfaceView surface = surfaceView;
        backdrop = new Backdrop(backdropView, new File(android.os.Environment.getExternalStorageDirectory(), "Pictures/Screensaver"), ui);
        dimView = new android.view.View(this);                 // darkens video/photos so the lyrics stay readable
        dimView.setBackgroundColor(0x99000000);
        dimView.setVisibility(android.view.View.GONE);
        root.addView(dimView, new FrameLayout.LayoutParams(-1, -1));
        lyricsView = new TextView(this);
        lyricsView.setGravity(Gravity.CENTER);
        lyricsView.setPadding(60, 0, 60, 160);
        lyricsView.setShadowLayer(10f, 0f, 0f, Color.BLACK);
        root.addView(lyricsView, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        countdownView = new TextView(this);                    // the 3-2-1 dots before the singing starts
        countdownView.setGravity(Gravity.CENTER);
        countdownView.setTextSize(64);
        countdownView.setShadowLayer(10f, 0f, 0f, Color.BLACK);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        clp.setMargins(0, 280, 0, 0);
        root.addView(countdownView, clp);
        bannerView = new TextView(this);                       // "Now singing" / "Next up" banner
        bannerView.setTextColor(Color.WHITE);
        bannerView.setBackgroundColor(0xB3101418);
        bannerView.setTextSize(30);
        bannerView.setPadding(28, 14, 28, 14);
        bannerView.setVisibility(android.view.View.GONE);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        blp.setMargins(24, 0, 0, 130);
        root.addView(bannerView, blp);
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
        root.addView(status, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));
        statusBar = new TextView(this);                        // "Playing | KARAOKE | time" line, bottom-left
        statusBar.setTextColor(Color.WHITE);
        statusBar.setBackgroundColor(0x99000000);
        statusBar.setTextSize(24);
        statusBar.setPadding(24, 12, 24, 12);
        root.addView(statusBar, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START));
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
        player.setVideoScalingMode(androidx.media3.common.C.VIDEO_SCALING_MODE_SCALE_TO_FIT);   // never crop; the surface itself is fitted to the video's aspect ratio
        // a music video has its own (vocal) soundtrack: always prefer the karaoke mp3 merged in next to it
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setPreferredAudioMimeType(androidx.media3.common.MimeTypes.AUDIO_MPEG).build());
        player.addListener(new Player.Listener() {
            @Override public void onVideoSizeChanged(androidx.media3.common.VideoSize vs) { fitVideo(vs); }
            @Override public void onTracksChanged(androidx.media3.common.Tracks tracks) {
                // With a music video merged in there are two audio tracks (the video's own vocal mix, AAC, and our
                // karaoke mp3). Force the mp3; the player stays muted until that is the one selected.
                androidx.media3.common.Tracks.Group mp3 = null;
                for (androidx.media3.common.Tracks.Group g : tracks.getGroups()) {
                    if (g.getType() == androidx.media3.common.C.TRACK_TYPE_AUDIO
                            && androidx.media3.common.MimeTypes.AUDIO_MPEG.equals(g.getTrackFormat(0).sampleMimeType)) mp3 = g;
                }
                if (mp3 != null && wantMp3 && !mp3.isSelected()) {
                    player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                            .setOverrideForType(new androidx.media3.common.TrackSelectionOverride(mp3.getMediaTrackGroup(), 0)).build());
                    return;
                }
                for (androidx.media3.common.Tracks.Group g : tracks.getGroups()) {
                    if (g.getType() == androidx.media3.common.C.TRACK_TYPE_AUDIO && g.isSelected()) {
                        android.util.Log.i("KaraokeAudio", "playing audio track: " + g.getTrackFormat(0).sampleMimeType);
                        if (!wantMp3 || androidx.media3.common.MimeTypes.AUDIO_MPEG.equals(g.getTrackFormat(0).sampleMimeType)) player.setVolume(1f);
                    }
                }
            }
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
        try { KeepAliveService.start(this, "http://" + ip + ":" + PORT); } catch (Exception ignored) { }   // survive Home / other apps
        // Scanning a busy USB drive can take minutes: show the list from the last scan straight away, refresh behind it.
        try {
            File karaokeRoot = Library.findRoot();
            Library cached = karaokeRoot == null ? null : Library.load(karaokeRoot, new File(android.os.Environment.getExternalStorageDirectory(), "Karaoke/index.json"));
            if (cached != null && !cached.songs.isEmpty()) { lib = cached; libraryNote = ""; }
        } catch (Exception ignored) { }
        rescan();
        ui.post(tick);
    }

    /** Re-reads the Karaoke folder off the main thread; starts playing if songs are waiting. */
    private void rescan() {
        if (scanning) return;          // a scan can take long while the USB drive is busy; never pile them up
        scanning = true;
        new Thread(new Runnable() {
            @Override public void run() {
                File root = Library.findRoot();
                final Library l = root == null ? null : Library.scan(root);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        scanning = false;
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
            File mv = s.video.isEmpty() ? null : new File(lib.root, s.video);
            startFile(f, mv != null && mv.isFile() ? mv : null);
            return;
        }
    }

    private void stopPlayback() {
        currentSongId = "";
        player.stop();
        player.clearMediaItems();
        lrc = null;
        lyricsView.setText("");
        countdownView.setText("");
        backdrop.stop();
        backdropView.setVisibility(android.view.View.GONE);
        dimView.setVisibility(android.view.View.GONE);
        surfaceView.setVisibility(android.view.View.INVISIBLE);
        cPlaying = false; cPaused = false;
        refresh();
    }

    private void startFile(File f) { startFile(f, null); }

    /** Plays f (the karaoke audio); if a music video is given it is shown muted behind the lyrics, in sync. */
    private void startFile(File f, File video) {
        songPath = f.getPath();
        String base = songPath.replaceFirst("\\.[^./]+$", "");
        lrc = Lrc.load(new File(base + ".lrc"));
        lastLine = -2;
        lastCount = -1;
        lyricsView.setText("");
        countdownView.setText("");
        loadOffset();
        applyChannel();
        boolean mp4Own = f.getName().toLowerCase().endsWith(".mp4");
        boolean showPhotos = video == null && !mp4Own;           // an audio-only song: photos behind the lyrics
        backdropView.setVisibility(debugSingleFile ? android.view.View.GONE : android.view.View.VISIBLE);
        dimView.setVisibility(debugSingleFile ? android.view.View.GONE : android.view.View.VISIBLE);
        backdrop.stop();
        if (!debugSingleFile) backdrop.start(showPhotos);
        // Only show the video surface when there is a picture to show: an idle surface keeps the previous song's
        // last frame and would cover the photo backdrop (it sits above it).
        surfaceView.setVisibility(showPhotos ? android.view.View.INVISIBLE : android.view.View.VISIBLE);
        wantMp3 = video != null;
        player.setVolume(video != null ? 0f : 1f);          // muted until the karaoke mp3 is the selected track
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_AUDIO).build());
        if (video != null) {
            androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory pf =
                    new androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(new androidx.media3.datasource.DefaultDataSource.Factory(this));
            player.setMediaSource(new androidx.media3.exoplayer.source.MergingMediaSource(
                    pf.createMediaSource(MediaItem.fromUri(android.net.Uri.fromFile(video))),
                    pf.createMediaSource(MediaItem.fromUri(android.net.Uri.fromFile(f)))));
        } else {
            player.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(f)));
        }
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

    private String titleOf(QueueManager.Item it, boolean withArtist) {
        Library.Song s = lib.byId(it.songId);
        String t = s == null ? "(missing song)" : s.title + (!withArtist || s.artist.isEmpty() ? "" : "  (" + s.artist + ")");
        return it.singer.isEmpty() || !withArtist ? t : t + "   Singer: " + it.singer;
    }

    /** prefix + title + suffix on ONE line: the title is cut with "..." if it would not fit left of the small QR code. */
    private String fitLine(String prefix, String title, String suffix) {
        int maxPx = getResources().getDisplayMetrics().widthPixels - 48 /* padding */ - 24 - 200 /* QR + gap */;
        android.graphics.Paint paint = status.getPaint();
        String full = prefix + title + suffix;
        if (paint.measureText(full) <= maxPx) return full;
        String t = title;
        while (t.length() > 1 && paint.measureText(prefix + t + "..." + suffix) > maxPx) t = t.substring(0, t.length() - 1);
        return prefix + t + "..." + suffix;
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
            statusBar.setVisibility(android.view.View.INVISIBLE);
            return;
        }
        statusBar.setVisibility(android.view.View.VISIBLE);
        String st = player.getPlaybackState() == Player.STATE_BUFFERING ? "Buffering" : (cPaused ? "Paused" : "Playing");
        String time = fmt(cPos) + (cDur > 0 ? " / " + fmt(cDur) : "");
        StringBuilder sb = new StringBuilder();
        if (!debugSingleFile) {
            QueueManager.Item cur = queue.current();
            java.util.List<QueueManager.Item> up = queue.upcoming();
            sb.append("Now: ").append(cur == null ? "-" : titleOf(cur, true)).append('\n');
            sb.append(fitLine("Next: ", up.isEmpty() ? "-" : titleOf(up.get(0), false),
                    up.size() > 1 ? "  (+" + (up.size() - 1) + " more)" : ""));
        }
        status.setVisibility(sb.length() > 0 ? android.view.View.VISIBLE : android.view.View.INVISIBLE);
        status.setText(sb.toString());
        sb.setLength(0);
        sb.append(st).append("  |  ").append(vocalOn ? "VOCAL ON" : "KARAOKE").append("  |  ").append(time);
        if (lrc != null) sb.append("  |  Lyrics ").append(lyricOffsetMs >= 0 ? "+" : "-").append(String.format("%.1fs", Math.abs(lyricOffsetMs) / 1000.0));
        if (System.currentTimeMillis() < hintUntil) {
            sb.append("\nOK: pause   hold OK / Menu: vocal   Left/Right: seek or skip   Up/Down: lyrics later/earlier");
        }
        statusBar.setText(sb.toString());
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

    /** Sizes the video surface to the video's true shape, centred and as large as fits the screen (no cropping, no stretching). */
    private void fitVideo(androidx.media3.common.VideoSize vs) {
        if (vs.width == 0 || vs.height == 0) return;
        int rw = rootView.getWidth(), rh = rootView.getHeight();
        if (rw == 0 || rh == 0) { android.util.DisplayMetrics m = getResources().getDisplayMetrics(); rw = m.widthPixels; rh = m.heightPixels; }
        float aspect = vs.width * vs.pixelWidthHeightRatio / vs.height;
        int w, h;
        if (aspect > (float) rw / rh) { w = rw; h = Math.round(rw / aspect); } else { h = rh; w = Math.round(rh * aspect); }
        surfaceView.setLayoutParams(new FrameLayout.LayoutParams(w, h, Gravity.CENTER));
    }

    /** Three dots that count down (3-2-1) in the last 3 s before singing starts after a gap of 5 s or more. */
    private void updateCountdown(long pos) {
        int count = 0, n = -1;
        for (int k = 0; k < lrc.lines.size(); k++) {
            if (!lrc.lines.get(k).text.isEmpty() && lrc.lines.get(k).ms > pos) { n = k; break; }
        }
        if (n >= 0) {
            long until = lrc.lines.get(n).ms - pos;
            long gapStart = 0;                                   // where the previous sung line ended (0 = song start)
            for (int k = n - 1; k >= 0; k--) if (!lrc.lines.get(k).text.isEmpty()) { gapStart = lrc.endOf(k); break; }
            if (until <= 3000 && pos >= gapStart && lrc.lines.get(n).ms - gapStart >= 5000) count = (int) Math.ceil(until / 1000.0);
        }
        if (count == lastCount) return;
        lastCount = count;
        if (count == 0) { countdownView.setText(""); return; }
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        for (int d = 0; d < 3; d++) {
            int st = sb.length();
            sb.append("● ");
            sb.setSpan(new android.text.style.ForegroundColorSpan(d < count ? 0xFFFFE23B : 0x44FFFFFF), st, st + 1, 0);
        }
        countdownView.setText(sb);
    }

    private String describe(QueueManager.Item it) {
        Library.Song s = lib.byId(it.songId);
        String t = s == null ? "(missing song)" : s.title;
        return it.singer.isEmpty() ? t : it.singer + " - " + t;
    }

    /** "Now singing" for the first 6 s of a song, "Next up" for the last 15 s. */
    private void updateBanner() {
        String text = null;
        if (!debugSingleFile && (cPlaying || cPaused)) {
            QueueManager.Item cur = queue.current();
            java.util.List<QueueManager.Item> up = queue.upcoming();
            if (cur != null && cPos < 6000) text = "Now singing:  " + describe(cur);
            else if (!up.isEmpty() && cDur > 25000 && cDur - cPos <= 15000) text = "Next up:  " + describe(up.get(0));
        }
        if (text == null) { if (bannerView.getVisibility() != android.view.View.GONE) bannerView.setVisibility(android.view.View.GONE); return; }
        if (!text.contentEquals(bannerView.getText())) bannerView.setText(text);
        if (bannerView.getVisibility() != android.view.View.VISIBLE) bannerView.setVisibility(android.view.View.VISIBLE);
    }

    private void showLyrics() {
        if (lrc == null || lrc.lines.isEmpty() || !(cPlaying || cPaused)) return;
        long pos = player.getCurrentPosition() - lyricOffsetMs;
        updateCountdown(pos);
        int i = lrc.indexAt(pos);

        // Which character of the current line is being sung? LRC only times whole lines, so the line's
        // time (up to the next line, at most ~0.45 s per character) is shared out evenly over its characters.
        int ci = i >= 0 ? lrc.currentChar(i, pos) : -1;   // real word times if the .lrc has them, else the estimate
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
        @Override public void run() { refresh(); showLyrics(); updateBanner(); ui.postDelayed(this, 250); }
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
        if (!debugSingleFile) KeepAliveService.stop(this);
        if (player != null) player.release();
    }
}
