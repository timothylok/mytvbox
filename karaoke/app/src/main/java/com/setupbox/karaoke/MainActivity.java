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
    private TextView status, statusBar, nowView, lyricsView, idleInfo;
    private LinearLayout idleBox;
    private ImageView qrSmall, backdropView;
    private FrameLayout rootView, videoBand;
    private java.util.Map<String, int[]> crops = new java.util.HashMap<>();   // crops.txt: black bars to zoom away
    private int[] videoCrop;                 // crop of the video now playing (or null)
    private SurfaceView surfaceView;
    private android.view.View dimView;
    private TextView countdownView, bannerView;
    private Backdrop backdrop;
    private int lastCount = -1;                 // countdown dots currently shown
    private int cdNext = -1;                    // line that follows the countdown
    private volatile boolean scanning = false;
    private boolean wantMp3 = false;            // a music video is merged in: its own audio must not be heard
    private long hintUntil = 0;                 // show the key help line until this time
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());

    // lyrics
    private Lrc lrc;
    private int lastLine = -2;
    private long lyricOffsetMs = 0;   // positive = lyrics appear later
    private String songPath = "";
    private boolean longPressed = false;

    // sleep mode: after sleepMinutes without a song playing, remote key or phone action the screen goes black
    // (small QR + clock that drift). Any remote key or phone action wakes it. sleep_minutes in /sdcard/Karaoke/config.properties, 0 = never.
    private long sleepMs = 5 * 60000L;
    private long lastActivity = System.currentTimeMillis();
    private long lastConfigRead = 0;
    private boolean sleeping = false;
    private FrameLayout sleepView;
    private LinearLayout sleepBox;
    private TextView sleepClock;
    private long lastDrift = 0;

    // karaoke state
    private volatile Library lib = Library.empty();
    private QueueManager queue;
    private Server server;
    private String ip = "";
    private String libraryNote = "Starting...";
    private boolean debugSingleFile = false;
    private boolean vocalOn = false;                 // false = karaoke (singer sings), true = original vocal
    private String currentSongId = "";
    private String pairToken = "";           // secret in the QR code (see Pairing)
    private History history;                 // play history, last 30 days (history.jsonl)
    private final Prefetcher prefetcher = new Prefetcher();   // warms the next queued song while the current one plays
    // on-TV song browser (an overlay, so playback keeps going behind it): language -> artist -> song
    private FrameLayout browseView;
    private TextView browseTitle, browseHint, browseToast;
    private android.widget.ListView browseList;
    private android.widget.ArrayAdapter<String> browseAdapter;
    private final java.util.List<String> browseLabels = new java.util.ArrayList<>();
    private final java.util.List<String> browseValues = new java.util.ArrayList<>();    // language / artist / song id of each row ("" = a message row)
    private volatile boolean browseShown;
    private int browseLevel;                                   // 0 = languages, 1 = artists, 2 = songs
    private String browseLang = "", browseArtist = "";
    private boolean browseCenterDown, browseLong, wokeKey;
    private final Runnable hideBrowseToast = new Runnable() {
        @Override public void run() { if (browseToast != null) browseToast.setVisibility(android.view.View.GONE); }
    };
    private long lastWarmCheck;
    private volatile Storage storage;        // the Karaoke folder the user picked (null until then); ALL song files go through it
    private android.content.SharedPreferences prefs;
    private FrameLayout setupView;           // first-run screen: choose the Karaoke folder
    private TextView setupText;
    private android.widget.Button setupButton;
    private File appFolder;                  // set when the app's own folder is used because the device has no folder chooser
    private boolean pickerMissing;          // the last pickFolder() found no chooser app on this device
    private volatile boolean setupShown;    // read by the web server thread
    private FrameLayout menuView;            // TV settings: reset phone pairing (Menu key / hold OK while nothing plays)
    private android.widget.Button menuReset, menuClose, menuLocation;
    private TextView menuText;
    private volatile boolean menuShown;
    private boolean resetArmed = false;
    private ImageView qrBigView, sleepQrView;
    private volatile String setupMsg = "";
    private static final int REQ_PICK = 41, REQ_LOC = 42;
    /** The app's own files (queue, history, flips, offsets, index cache) live in private storage, never in the user's folder. */
    private File priv(String name) { return new File(getFilesDir(), name); }
    private long lastPrune = 0;
    private final Set<String> flipped = Collections.synchronizedSet(new HashSet<String>());

    // player state cached for the web server thread (ExoPlayer may only be touched on the main thread)
    private volatile boolean cPlaying, cPaused;
    private volatile long cPos, cDur;

    // ---------- start-up ----------

    // Screen-relative sizing (rule 5): everything is laid out from the real screen, not from dp, density, Android version or the user's font scale.
    // u = screen height / 1080, so a value written for a 1080p TV ("ref") scales on 720p / 4K; text sizes use the old sp numbers at 1.5 px per sp
    // (the 1080p box runs at 240 dpi), so 1080p looks as before. safeX / safeY keep content inside the TV's overscan-safe area (4 % / 5 %).
    private float u = 1f;
    private int safeX, safeY;
    private int px(float ref) { return Math.round(ref * u); }
    private void sizeText(TextView t, float sp) { t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, sp * 1.5f * u); }

    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        android.util.DisplayMetrics dmS = getResources().getDisplayMetrics();
        u = Math.max(0.5f, dmS.heightPixels / 1080f);
        safeX = Math.round(dmS.widthPixels * 0.04f);
        safeY = Math.round(dmS.heightPixels * 0.05f);
        final int barH = px(54);                               // both bottom bars: one fixed height (from the screen), so they line up
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
        videoBand = new FrameLayout(this);                     // the band the video lives in; clips a zoomed-in video
        videoBand.setClipChildren(true);
        videoBand.addView(surfaceView, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        root.addView(videoBand, new FrameLayout.LayoutParams(-1, -1));
        final SurfaceView surface = surfaceView;
        backdrop = new Backdrop(backdropView, new File(getExternalFilesDir(null) == null ? getFilesDir() : getExternalFilesDir(null), "Screensaver"), ui);
        dimView = new android.view.View(this);                 // darkens video/photos so the lyrics stay readable
        dimView.setBackgroundColor(0x99000000);
        dimView.setVisibility(android.view.View.GONE);
        root.addView(dimView, new FrameLayout.LayoutParams(-1, -1));
        lyricsView = new TextView(this);
        lyricsView.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        lyricsView.setPadding(Math.max(px(60), safeX), 0, Math.max(px(60), safeX), safeY + barH + px(12));   // inside the safe area and clear of the bottom bars
        lyricsView.setShadowLayer(10f, 0f, 0f, Color.BLACK);
        // Sizes follow the screen height (not the density or the Android version): 7 % for the line being sung, 68 % of that for the others.
        lyricsView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Math.max(40f, getResources().getDisplayMetrics().heightPixels * 0.07f));
        lyricsView.setTextColor(Color.WHITE);
        // the lyrics live in the lower half of the screen (the picture keeps the upper half)
        FrameLayout.LayoutParams lyp = new FrameLayout.LayoutParams(-1, getResources().getDisplayMetrics().heightPixels / 2, Gravity.BOTTOM);
        root.addView(lyricsView, lyp);
        countdownView = new TextView(this);                    // the 3-2-1 dots before the singing starts
        countdownView.setGravity(Gravity.CENTER);
        sizeText(countdownView, 64);
        countdownView.setShadowLayer(10f, 0f, 0f, Color.BLACK);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        clp.setMargins(0, Math.max(px(280), safeY), 0, 0);
        root.addView(countdownView, clp);
        bannerView = new TextView(this);                       // "Next up" banner
        bannerView.setTextColor(Color.WHITE);
        bannerView.setBackgroundColor(0xB3101418);
        sizeText(bannerView, 30);
        bannerView.setPadding(px(28), px(14), px(28), px(14));
        bannerView.setVisibility(android.view.View.GONE);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        blp.setMargins(safeX, 0, 0, safeY + barH + px(24));
        root.addView(bannerView, blp);
        ip = localIp();
        android.content.SharedPreferences pp = getSharedPreferences("karaoke", MODE_PRIVATE);
        pairToken = pp.getString("pair", null);
        if (pairToken == null) { pairToken = Pairing.newToken(); pp.edit().putString("pair", pairToken).apply(); }   // made once, phones stay paired
        final String url = Pairing.pairUrl(ip, PORT, pairToken);     // what the QR code opens: pairs the phone, no typing
        android.graphics.Bitmap bigQr = qr(url, 440), smallQr = qr(url, 220);

        idleBox = new LinearLayout(this);
        idleBox.setOrientation(LinearLayout.VERTICAL);
        idleBox.setGravity(Gravity.CENTER);
        idleBox.setBackgroundColor(0xFF101418);
        idleBox.setPadding(safeX, safeY, safeX, safeY);
        TextView idleTitle = new TextView(this);
        idleTitle.setText("K歌之王");
        idleTitle.setTextColor(0xFFFFE23B);
        sizeText(idleTitle, 64);
        idleTitle.setGravity(Gravity.CENTER);
        idleBox.addView(idleTitle);
        ImageView qrBig = new ImageView(this);
        qrBigView = qrBig;
        qrBig.setImageBitmap(bigQr);
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(px(440), px(440));
        qlp.setMargins(0, px(24), 0, px(24));
        idleBox.addView(qrBig, qlp);
        TextView idleUrl = new TextView(this);
        idleUrl.setText("Scan the code with your phone's camera");
        idleUrl.setTextColor(Color.WHITE);
        sizeText(idleUrl, 36);
        idleUrl.setGravity(Gravity.CENTER);
        idleBox.addView(idleUrl);
        idleInfo = new TextView(this);
        idleInfo.setTextColor(0xFF9AA0AD);
        sizeText(idleInfo, 26);
        idleInfo.setGravity(Gravity.CENTER);
        idleInfo.setPadding(px(40), px(16), px(40), 0);
        idleBox.addView(idleInfo);
        root.addView(idleBox, new FrameLayout.LayoutParams(-1, -1));

        qrSmall = new ImageView(this);
        qrSmall.setImageBitmap(smallQr);
        qrSmall.setAlpha(0.85f);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(px(160), px(160), Gravity.TOP | Gravity.END);
        slp.setMargins(0, safeY, safeX, 0);
        root.addView(qrSmall, slp);

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setBackgroundColor(0x99000000);
        sizeText(status, 24);
        status.setPadding(px(24), px(12), px(24), px(12));
        FrameLayout.LayoutParams stp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        stp.setMargins(safeX, safeY, 0, 0);
        root.addView(status, stp);
        statusBar = new TextView(this);                        // "Playing | KARAOKE | time" line, bottom-left
        statusBar.setTextColor(Color.WHITE);
        statusBar.setBackgroundColor(0x99000000);
        sizeText(statusBar, 18);
        statusBar.setPadding(px(24), px(5), px(24), px(5));
        statusBar.setSingleLine(true);
        statusBar.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams sbp = new FrameLayout.LayoutParams(-2, barH, Gravity.BOTTOM | Gravity.START);
        sbp.setMargins(safeX, 0, 0, safeY);
        root.addView(statusBar, sbp);
        nowView = new TextView(this);                          // "Now: song (artist) Singer: x", bottom-right, level with the status bar
        nowView.setTextColor(Color.WHITE);
        nowView.setBackgroundColor(0x99000000);
        sizeText(nowView, 18);
        nowView.setPadding(px(24), px(5), px(24), px(5));
        nowView.setSingleLine(true);
        nowView.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams nvp = new FrameLayout.LayoutParams(-2, barH, Gravity.BOTTOM | Gravity.END);
        nvp.setMargins(0, 0, safeX, safeY);
        root.addView(nowView, nvp);

        sleepView = new FrameLayout(this);                     // sleep mode: black, with a dim QR + clock that drift around
        sleepView.setBackgroundColor(0xFF000000);
        sleepView.setVisibility(android.view.View.GONE);
        sleepBox = new LinearLayout(this);
        sleepBox.setOrientation(LinearLayout.VERTICAL);
        sleepBox.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView sleepQr = new ImageView(this);
        sleepQrView = sleepQr;
        sleepQr.setImageBitmap(smallQr);
        sleepQr.setAlpha(0.35f);
        // Sizes follow the screen height, not fixed px / sp: the QR is 15 % of it and the clock is shrunk until "88:88" fits under the QR,
        // so the clock never wraps or overflows on any screen, density or font.
        final int qrPx = Math.round(getResources().getDisplayMetrics().heightPixels * 0.15f);
        sleepBox.addView(sleepQr, new LinearLayout.LayoutParams(qrPx, qrPx));
        sleepClock = new TextView(this);
        sleepClock.setTextColor(0xFF666666);
        sleepClock.setSingleLine(true);
        sleepClock.setGravity(Gravity.CENTER);
        float clockPx = qrPx * 0.40f;
        sleepClock.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, clockPx);
        while (clockPx > 12f && sleepClock.getPaint().measureText("88:88") > qrPx * 0.94f) {
            clockPx *= 0.95f;
            sleepClock.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, clockPx);
        }
        sleepBox.addView(sleepClock, new LinearLayout.LayoutParams(qrPx, -2));
        sleepView.addView(sleepBox, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        root.addView(sleepView, new FrameLayout.LayoutParams(-1, -1));
        setupView = new FrameLayout(this);                     // first run: choose the Karaoke folder (and a warning about USB drives)
        setupView.setBackgroundColor(0xFF101418);
        setupView.setPadding(safeX, safeY, safeX, safeY);
        setupView.setVisibility(android.view.View.GONE);
        LinearLayout setupBox = new LinearLayout(this);
        setupBox.setOrientation(LinearLayout.VERTICAL);
        setupBox.setGravity(Gravity.CENTER_HORIZONTAL);
        setupText = new TextView(this);
        setupText.setTextColor(Color.WHITE);
        sizeText(setupText, 28);
        setupText.setGravity(Gravity.CENTER);
        setupBox.addView(setupText, new LinearLayout.LayoutParams(Math.min(px(1400), Math.round(dmS.widthPixels * 0.9f)), -2));
        setupButton = new android.widget.Button(this);
        setupButton.setText("Choose the Karaoke folder");
        sizeText(setupButton, 26);
        setupButton.setFocusable(true);
        setupButton.setFocusableInTouchMode(true);                // a mouse/touch must not leave the button unfocusable
        setupButton.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) { pickFolder(); }
        });
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2);
        bl.topMargin = px(48);
        setupBox.addView(setupButton, bl);
        setupView.addView(setupBox, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        root.addView(setupView, new FrameLayout.LayoutParams(-1, -1));
        menuView = new FrameLayout(this);                      // TV settings (only reachable with the remote while nothing is playing)
        menuView.setBackgroundColor(0xF0101418);
        menuView.setPadding(safeX, safeY, safeX, safeY);
        menuView.setVisibility(android.view.View.GONE);
        LinearLayout menuBox = new LinearLayout(this);
        menuBox.setOrientation(LinearLayout.VERTICAL);
        menuBox.setGravity(Gravity.CENTER_HORIZONTAL);
        menuText = new TextView(this);
        menuText.setTextColor(Color.WHITE);
        sizeText(menuText, 28);
        menuText.setGravity(Gravity.CENTER);
        menuBox.addView(menuText, new LinearLayout.LayoutParams(Math.min(px(1400), Math.round(dmS.widthPixels * 0.9f)), -2));
        menuReset = new android.widget.Button(this);
        sizeText(menuReset, 26);
        menuReset.setFocusableInTouchMode(true);
        menuReset.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) { resetPairingClicked(); }
        });
        LinearLayout.LayoutParams mb = new LinearLayout.LayoutParams(-2, -2);
        mb.topMargin = px(40);
        menuBox.addView(menuReset, mb);
        menuLocation = new android.widget.Button(this);        // only shown on devices with no system folder chooser
        menuLocation.setText("Song location");
        sizeText(menuLocation, 26);
        menuLocation.setFocusableInTouchMode(true);
        menuLocation.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) { chooseLocation(); }
        });
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-2, -2);
        ml.topMargin = px(24);
        menuBox.addView(menuLocation, ml);
        menuClose = new android.widget.Button(this);
        menuClose.setText("Close");
        sizeText(menuClose, 26);
        menuClose.setFocusableInTouchMode(true);
        menuClose.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) { hideMenu(); }
        });
        LinearLayout.LayoutParams mc = new LinearLayout.LayoutParams(-2, -2);
        mc.topMargin = px(24);
        menuBox.addView(menuClose, mc);
        menuView.addView(menuBox, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        buildBrowse(root);
        root.addView(menuView, new FrameLayout.LayoutParams(-1, -1));
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
            @Override public void onRenderedFirstFrame() { fitVideo(player.getVideoSize()); }   // same size as the last video: no size event, but the crop may differ
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

        prefs = getSharedPreferences("karaoke", MODE_PRIVATE);
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 42);   // Android 13+: the foreground-service notification needs it
        }
        String path = getIntent().getStringExtra("path");
        if (path != null) { debugSingleFile = true; startFile(new FileStorage(new File(path).getParentFile()).uri(path), null, null, "", false); ui.post(tick); return; }

        queue = QueueManager.withFile(priv("state.txt"));
        history = new History(priv("history.jsonl"));
        loadFlips();
        server = new Server(this, this, PORT, pairToken);
        try { server.start(); } catch (Exception e) { libraryNote = "Web server failed: " + e.getMessage(); }
        try { KeepAliveService.start(this, "http://" + ip + ":" + PORT); } catch (Exception ignored) { }   // survive Home / other apps
        openStorage();
        ui.post(tick);
    }

    // ---------- TV settings: reset phone pairing ----------

    // ---------- on-TV song browser ----------

    /** Builds the (hidden) overlay. Sizes follow the screen height, so it reads the same on any TV. */
    private void buildBrowse(FrameLayout root) {
        final float px = Math.max(26f, getResources().getDisplayMetrics().heightPixels * 0.036f);   // (text unit of the browser; px(...) is the layout helper)
        browseView = new FrameLayout(this);
        browseView.setBackgroundColor(0xF2101418);
        browseView.setVisibility(android.view.View.GONE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(px * 1.4f);
        box.setPadding(Math.max(pad, safeX), Math.max(Math.round(px * 0.9f), safeY), Math.max(pad, safeX), Math.max(Math.round(px * 0.6f), safeY));
        browseTitle = new TextView(this);
        browseTitle.setTextColor(Color.WHITE);
        browseTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px * 1.3f);
        browseHint = new TextView(this);
        browseHint.setTextColor(0xFFB8C2D6);
        browseHint.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px * 0.76f);
        browseHint.setPadding(0, Math.round(px * 0.25f), 0, Math.round(px * 0.45f));
        browseList = new android.widget.ListView(this);
        browseList.setDivider(new android.graphics.drawable.ColorDrawable(0x22FFFFFF));
        browseList.setDividerHeight(1);
        browseList.setSelector(new android.graphics.drawable.ColorDrawable(0x55FFFFFF));
        browseList.setItemsCanFocus(false);
        browseList.setFocusable(true);
        browseList.setFocusableInTouchMode(true);
        browseAdapter = new android.widget.ArrayAdapter<String>(this, 0, browseLabels) {
            @Override public android.view.View getView(int pos, android.view.View v, android.view.ViewGroup parent) {
                TextView t = v instanceof TextView ? (TextView) v : new TextView(MainActivity.this);
                t.setTextColor(Color.WHITE);
                t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px);
                t.setSingleLine(true);
                t.setEllipsize(android.text.TextUtils.TruncateAt.END);
                t.setPadding(Math.round(px * 0.5f), Math.round(px * 0.42f), Math.round(px * 0.5f), Math.round(px * 0.42f));
                t.setText(getItem(pos));
                return t;
            }
        };
        browseList.setAdapter(browseAdapter);
        browseToast = new TextView(this);
        browseToast.setTextColor(0xFFFFE23B);
        browseToast.setBackgroundColor(0xCC000000);
        browseToast.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px * 0.9f);
        browseToast.setPadding(Math.round(px * 0.6f), Math.round(px * 0.3f), Math.round(px * 0.6f), Math.round(px * 0.3f));
        browseToast.setSingleLine(true);
        browseToast.setEllipsize(android.text.TextUtils.TruncateAt.END);
        browseToast.setVisibility(android.view.View.GONE);
        box.addView(browseTitle, new LinearLayout.LayoutParams(-1, -2));
        box.addView(browseHint, new LinearLayout.LayoutParams(-1, -2));
        box.addView(browseList, new LinearLayout.LayoutParams(-1, 0, 1f));
        browseView.addView(box, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        tl.bottomMargin = Math.round(px * 1.2f);
        browseView.addView(browseToast, tl);
        root.addView(browseView, new FrameLayout.LayoutParams(-1, -1));
    }

    private java.util.List<BrowseModel.Entry> browseEntries() {
        java.util.List<BrowseModel.Entry> all = new java.util.ArrayList<>();
        for (Library.Song s : lib.songs) {
            if (s.audioH == null || s.audioH.isEmpty()) continue;           // nothing to play
            all.add(new BrowseModel.Entry(s.id, s.language, s.artist, s.title, s.year));
        }
        return all;
    }

    private java.util.Set<String> queuedIds() {
        java.util.Set<String> ids = new java.util.HashSet<>();
        QueueManager.Item cur = queue.current();
        if (cur != null) ids.add(cur.songId);
        for (QueueManager.Item it : queue.upcoming()) ids.add(it.songId);
        return ids;
    }

    private void showBrowse() {
        if (browseShown || setupShown || menuShown || debugSingleFile || storage == null || queue == null) return;
        browseLevel = 0;
        browseLang = "";
        browseArtist = "";
        browseShown = true;
        browseView.setVisibility(android.view.View.VISIBLE);
        browseToast.setVisibility(android.view.View.GONE);
        browseRebuild(0);
    }

    private void hideBrowse() {
        browseShown = false;
        browseCenterDown = false;
        browseLong = false;
        browseView.setVisibility(android.view.View.GONE);
    }

    /** Fills the list for the current level; keepPos = the row to select afterwards. */
    private void browseRebuild(int keepPos) {
        java.util.List<BrowseModel.Entry> all = browseEntries();
        browseLabels.clear();
        browseValues.clear();
        int queued = queue.upcoming().size() + (queue.current() != null ? 1 : 0);
        String where;
        if (browseLevel == 0) {
            where = "Choose a song";
            for (BrowseModel.Row r : BrowseModel.languages(all)) { browseLabels.add(BrowseModel.label(r)); browseValues.add(r.value); }
        } else if (browseLevel == 1) {
            where = browseLang.isEmpty() ? "(unknown)" : browseLang;
            for (BrowseModel.Row r : BrowseModel.artists(all, browseLang)) { browseLabels.add(BrowseModel.label(r)); browseValues.add(r.value); }
        } else {
            where = browseArtist.isEmpty() ? "(unknown)" : browseArtist;
            java.util.Set<String> inQueue = queuedIds();
            for (BrowseModel.Entry e : BrowseModel.songs(all, browseLang, browseArtist)) { browseLabels.add(BrowseModel.songLabel(e, inQueue.contains(e.id))); browseValues.add(e.id); }
        }
        if (browseLabels.isEmpty()) {
            browseLabels.add(all.isEmpty() ? "No songs yet. Copy your songs to the song folder." : "(nothing here)");
            browseValues.add("");
        }
        browseTitle.setText(where);
        browseHint.setText((browseLevel == 2 ? "OK: add to the queue    hold OK: play next    Back: back" : "OK: open    Back: " + (browseLevel == 0 ? "close" : "back"))
                + "    |    " + (queued == 0 ? "queue is empty" : queued + " in the queue"));
        browseAdapter.notifyDataSetChanged();
        browseList.requestFocus();
        browseList.setSelection(Math.max(0, Math.min(keepPos, browseLabels.size() - 1)));
    }

    private void browseBack() {
        if (browseLevel == 0) { hideBrowse(); return; }
        String from = browseLevel == 2 ? browseArtist : browseLang;
        browseLevel--;
        browseRebuild(0);
        for (int i = 0; i < browseValues.size(); i++) if (browseValues.get(i).equals(from)) { browseList.setSelection(i); break; }     // land on the row we came from
    }

    private void browseNotice(String text) {
        browseToast.setText(text);
        browseToast.setVisibility(android.view.View.VISIBLE);
        ui.removeCallbacks(hideBrowseToast);
        ui.postDelayed(hideBrowseToast, 2500);
    }

    /** OK on the selected row: open a language / artist, or put the song in the queue (next = play it after the current song). */
    private void browseActivate(boolean next) {
        int pos = browseList.getSelectedItemPosition();
        if (pos < 0 || pos >= browseValues.size()) return;
        String v = browseValues.get(pos);
        if (v.isEmpty()) return;
        if (browseLevel == 0) { browseLang = v; browseLevel = 1; browseRebuild(0); return; }
        if (browseLevel == 1) { browseArtist = v; browseLevel = 2; browseRebuild(0); return; }
        Library.Song s = lib.byId(v);
        if (s == null || s.audioH.isEmpty()) return;
        boolean wasIdle = !(cPlaying || cPaused) && queue.current() == null && queue.upcoming().isEmpty();
        queue.add(s.id, "", next);
        kick();                                                  // starts playing when nothing was
        if (wasIdle) { hideBrowse(); return; }                   // the first song: go and watch it
        browseNotice((next ? "Playing next:  " : "Added:  ") + s.title);
        browseRebuild(pos);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (!browseShown) return super.dispatchKeyEvent(e);
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_BACK) {                        // act on key-up; the down event must not reach the activity (it would close the app)
            if (e.getAction() == KeyEvent.ACTION_UP && !touch()) browseBack(); else touch();
            return true;
        }
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_BUTTON_A) {
            if (touch()) return true;                            // the key that wakes the screen does nothing else
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() == 0) { browseCenterDown = true; browseLong = false; }
                else if (browseCenterDown && !browseLong && e.getEventTime() - e.getDownTime() >= 600) { browseLong = true; browseActivate(true); }
            } else if (e.getAction() == KeyEvent.ACTION_UP) {
                if (browseCenterDown && !browseLong) browseActivate(false);
                browseCenterDown = false;
                browseLong = false;
            }
            return true;
        }
        touch();
        return super.dispatchKeyEvent(e);                        // Up / Down: the list moves
    }

    private void showMenu() {
        if (setupShown || menuShown) return;
        resetArmed = false;
        menuText.setText("Settings\n\nReset phone pairing makes a new QR code. Phones that scanned the old code stop working until they scan the new one.");
        menuReset.setText("Reset phone pairing");
        boolean loc = appFolder != null;
        menuLocation.setVisibility(loc ? android.view.View.VISIBLE : android.view.View.GONE);
        if (loc) menuText.setText(menuText.getText() + "\n\nSong folder: " + appFolder.getPath());
        menuShown = true;
        menuView.setVisibility(android.view.View.VISIBLE);
        menuReset.requestFocus();
    }

    private void hideMenu() {
        menuShown = false;
        resetArmed = false;
        menuView.setVisibility(android.view.View.GONE);
    }

    /** First press arms it ("Press OK again"), the second press does it: no accidental resets. */
    private void resetPairingClicked() {
        if (!resetArmed) {
            resetArmed = true;
            menuReset.setText("Press OK again to reset");
            return;
        }
        pairToken = Pairing.newToken();
        prefs.edit().putString("pair", pairToken).apply();
        if (server != null) server.setToken(pairToken);
        refreshQr();
        hideMenu();
    }

    /** Rebuilds the QR codes (idle screen, corner, sleep screen) for the current token. */
    private void refreshQr() {
        String url = Pairing.pairUrl(ip, PORT, pairToken);
        android.graphics.Bitmap big = qr(url, 440), small = qr(url, 220);
        qrBigView.setImageBitmap(big);
        qrSmall.setImageBitmap(small);
        sleepQrView.setImageBitmap(small);
    }

    private boolean idleNow() { return !(cPlaying || cPaused) && storage != null && !debugSingleFile; }

    // ---------- the Karaoke folder (folder picker) ----------

    /** Opens the folder the user picked earlier; asks for one when there is none or the permission is gone. */
    private void openStorage() {
        String saved = prefs.getString("tree", null);
        if (saved != null) {
            android.net.Uri tree = android.net.Uri.parse(saved);
            boolean granted = false;
            for (android.content.UriPermission p : getContentResolver().getPersistedUriPermissions()) {
                if (p.getUri().equals(tree) && p.isReadPermission()) granted = true;
            }
            if (granted && SafStorage.isInternal(tree)) {
                appFolder = null;
                prefetcher.cancel();
                storage = new SafStorage(this, tree);
                Library cached = Library.load(storage, priv("index.json"));     // show the list from the last scan at once
                if (cached != null && !cached.songs.isEmpty()) { lib = cached; libraryNote = ""; }
                setupView.setVisibility(android.view.View.GONE);
                setupShown = false;
                rescan();
                return;
            }
        }
        if (saved == null && (prefs.getBoolean("appfolder", false) || !chooserAvailable())) { useAppFolder(); return; }
        showSetup(saved == null ? "" : "The Karaoke folder is not available any more.\n\n");
    }

    /** The app's own folders: internal storage and each USB drive / SD card (no permission needed on Android 10 or 16). */
    private java.util.List<File> appRoots() {
        java.util.List<File> out = new java.util.ArrayList<>();
        File[] ds = getExternalFilesDirs(null);
        if (ds != null) for (File f : ds) if (f != null) out.add(f);
        if (out.isEmpty()) out.add(getFilesDir());
        return out;
    }

    /** Settings > Song location: the in-app chooser (only reachable when the device has no system folder chooser). */
    private void chooseLocation() {
        hideMenu();
        android.content.Intent i = new android.content.Intent(this, FolderChooserActivity.class);
        if (appFolder != null) i.putExtra(FolderChooserActivity.EXTRA_CURRENT, appFolder.getPath());
        try { startActivityForResult(i, REQ_LOC); }
        catch (Exception e) { android.util.Log.w("Karaoke", "song location chooser failed: " + e); }
    }

    private boolean chooserAvailable() {
        try { return new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE).resolveActivity(getPackageManager()) != null; }
        catch (Exception e) { return true; }       // unsure: let the button try
    }

    /**
     * Fallback for devices with no folder chooser: songs live in the app's own folder (Android/data/<package>/files/Karaoke on the
     * internal storage). It needs no permission and works under the current target SDK; copy songs there from a PC (adb push / file transfer).
     */
    private void useAppFolder() {
        File base = getExternalFilesDir(null);
        File dir = new File(base == null ? getFilesDir() : base, "Karaoke");
        String savedPath = prefs.getString("appfolder_path", null);
        if (savedPath != null) {                                // the user chose a location in the in-app chooser (re-checked: it must still be inside the app's folders)
            File f = new File(savedPath);
            if (f.isDirectory() && AppFolders.isAllowed(appRoots(), f)) dir = f;
            else android.util.Log.w("Karaoke", "saved song location is gone or not allowed: " + savedPath);
        }
        if (!dir.isDirectory() && !dir.mkdirs()) android.util.Log.w("Karaoke", "cannot create " + dir);
        prefs.edit().putBoolean("appfolder", true).apply();
        appFolder = dir;
        prefetcher.cancel();                                    // handles of the old folder are meaningless now
        storage = new FileStorage(dir);
        Library cached = Library.load(storage, priv("index.json"));
        if (cached != null && !cached.songs.isEmpty()) { lib = cached; libraryNote = ""; }
        setupView.setVisibility(android.view.View.GONE);
        setupShown = false;
        rescan();
    }

    private void showSetup(String problem) {
        storage = null;
        libraryNote = "Choose the Karaoke folder on the TV";
        setupText.setText(problem
                + "Choose your Karaoke folder\n\n"
                + "Pick the folder that holds your song folders, for example  Internal storage > Karaoke.\n\n"
                + "Do NOT choose a USB drive or SD card. They are slow to read and can disconnect in the middle of a song. "
                + "Copy your songs to the box's own storage first.\n\n"
                + "Press OK to open the folder chooser.");
        setupMsg = setupText.getText().toString();
        setupShown = true;
        setupView.setVisibility(android.view.View.VISIBLE);
        setupButton.requestFocus();
    }

    private void pickFolder() {
        android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION | android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | android.content.Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        i.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,
                android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Karaoke"));
        try { startActivityForResult(i, REQ_PICK); android.util.Log.i("Karaoke", "folder chooser started"); }
        catch (Exception e) {
            android.util.Log.w("Karaoke", "no folder chooser: " + e);
            pickerMissing = true;       // Android also reports a cancel right after this; it must not replace the next screen
            useAppFolder();
        }
    }

    @Override protected void onActivityResult(int req, int result, android.content.Intent data) {
        super.onActivityResult(req, result, data);
        if (req == REQ_LOC) {
            String path = result == RESULT_OK && data != null ? data.getStringExtra(FolderChooserActivity.EXTRA_PATH) : null;
            if (path == null) return;                                       // cancelled: nothing changes
            File f = new File(path);
            if (!f.isDirectory() || !AppFolders.isAllowed(appRoots(), f)) { android.util.Log.w("Karaoke", "rejected song location " + path); return; }
            prefs.edit().putString("appfolder_path", f.getAbsolutePath()).apply();
            priv("index.json").delete();                                    // the list belongs to the old folder
            lib = Library.empty();
            useAppFolder();
            return;
        }
        if (req != REQ_PICK) return;
        if (pickerMissing) { pickerMissing = false; return; }
        if (result != RESULT_OK || data == null || data.getData() == null) {
            android.util.Log.w("Karaoke", "folder chooser returned result=" + result + " data=" + data);
            showSetup(result == RESULT_OK ? "" : "No folder was chosen (the chooser closed without a folder).\n\n");
            return;
        }
        android.net.Uri tree = data.getData();
        if (!SafStorage.isInternal(tree)) {
            showSetup("That folder is on a USB drive or SD card.\nPlease copy your songs to the box's internal storage and choose that folder.\n\n");
            return;
        }
        String oldTree = prefs.getString("tree", null);
        if (oldTree != null && !oldTree.equals(tree.toString())) {
            try { getContentResolver().releasePersistableUriPermission(android.net.Uri.parse(oldTree), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (Exception e) { android.util.Log.w("Karaoke", "could not release old folder grant: " + e); }
        }
        try {
            getContentResolver().takePersistableUriPermission(tree, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception e) {
            showSetup("Android would not keep access to that folder. Please choose it again.\n\n");
            return;
        }
        prefs.edit().putString("tree", tree.toString()).apply();
        priv("index.json").delete();               // the list belongs to the old folder
        lib = Library.empty();
        openStorage();
    }

    /** Maintenance hook (adb / tests only, not reachable from the network): am start ... --ez pickfolder true asks for the folder again. */
    @Override protected void onNewIntent(android.content.Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        if (i != null && i.getBooleanExtra("pickfolder", false) && !debugSingleFile) showSetup("");
    }

    private static String textInRoot(Storage st, String name) {
        Storage.Entry e = Storage.child(st, st.root(), name);
        return e == null ? null : Storage.readText(st, e.handle);
    }

    /** Re-reads the Karaoke folder off the main thread; starts playing if songs are waiting. */
    private void rescan() {
        if (scanning) return;          // a scan can take a while; never pile them up
        final Storage st = storage;
        if (st == null) return;
        scanning = true;
        new Thread(new Runnable() {
            @Override public void run() {
                final Library l = Library.scan(st, null, null);
                final String cropsText = textInRoot(st, "crops.txt");
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        scanning = false;
                        if (st != storage) return;              // the user picked another folder meanwhile
                        crops = cropsText == null ? new java.util.HashMap<String, int[]>() : Crops.parse(cropsText);
                        lib = l;
                        libraryNote = !l.songs.isEmpty() ? ""
                                : appFolder != null ? "No songs yet. This device has no folder chooser: copy your songs (language folders such as Cantopop) to " + appFolder.getPath()
                                : "No songs found in the chosen folder. Choose the folder that holds the language folders (for example Cantopop).";
                        l.save(priv("index.json"));
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
        endHistory();                                   // the song that was playing (ended or skipped)
        for (;;) {
            QueueManager.Item it = queue.advance();
            if (it == null) { stopPlayback(); return; }
            Library.Song s = lib.byId(it.songId);
            Storage st = storage;
            if (s == null || st == null || s.audioH.isEmpty()) continue;     // song gone from the list: skip it
            currentSongId = s.id;
            vocalOn = false;
            String vstem = s.video.isEmpty() ? "" : s.video.substring(s.video.lastIndexOf('/') + 1).replaceFirst("\\.[^.]+$", "");
            startFile(st.uri(s.audioH), s.videoH.isEmpty() ? null : st.uri(s.videoH), null, vstem, s.file.toLowerCase(java.util.Locale.ROOT).endsWith(".mp4"));
            Prefetcher.Result pre = prefetcher.take(s.id);
            if (pre != null && pre.lrc != null) {                 // warmed while the previous song played: lyrics are ready, no read, no parse
                lrc = pre.lrc;
                lastLine = -2;
                lastCount = -1;
                android.util.Log.d("Karaoke", "prefetch hit: " + (pre.bytes / 1024) + " KB warmed in " + pre.ms + " ms");
            } else {
                android.util.Log.d("Karaoke", "prefetch miss");
                loadLyricsAsync(st, s.id, s.lrcH);
            }
            if (history != null) history.started(System.currentTimeMillis(), s.id, s.title, s.artist, s.language, it.singer);
            return;
        }
    }

    /** Reads and parses the lyric file off the main thread; the lyrics appear as soon as they are ready (a few ms normally). */
    private void loadLyricsAsync(final Storage st, final String songId, final String handle) {
        if (handle.isEmpty()) return;
        new Thread(new Runnable() {
            @Override public void run() {
                Lrc parsed = null;
                try {
                    String text = Storage.readText(st, handle);
                    if (text != null) parsed = Lrc.parse(text);
                } catch (Exception e) {
                    android.util.Log.w("Karaoke", "lyrics not loaded: " + e);   // a broken lyric file must not stop the song
                }
                final Lrc result = parsed;
                if (result == null) return;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (!songId.equals(currentSongId)) return;      // the song changed meanwhile
                        lrc = result;
                        lastLine = -2;
                        lastCount = -1;
                    }
                });
            }
        }, "lyrics-loader").start();
    }

    private void stopPlayback() {
        endHistory();
        currentSongId = "";
        player.stop();
        player.clearMediaItems();
        lrc = null;
        lyricsView.setText("");
        countdownView.setText("");
        backdrop.stop();
        backdropView.setVisibility(android.view.View.GONE);
        dimView.setVisibility(android.view.View.GONE);
        videoBand.setVisibility(android.view.View.INVISIBLE);
        cPlaying = false; cPaused = false;
        refresh();
    }

    /** Records how long the song that is ending actually played. */
    private void endHistory() {
        if (history == null || player == null) return;
        long dur = Math.max(0, player.getDuration());
        history.finished(Math.min(player.getCurrentPosition(), dur > 0 ? dur : Long.MAX_VALUE), dur);
    }

    /**
     * Plays the karaoke audio (a URI from the Storage); if a music video URI is given it is shown muted behind the lyrics, in sync.
     * lrcText = the lyric file's text or null; videoStem = the video's file name without extension (key into crops.txt).
     */
    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    private void startFile(String audioUri, String video, String lrcText, String videoStem, boolean audioIsVideo) {
        songPath = currentSongId.isEmpty() ? audioUri : currentSongId;
        lrc = lrcText == null ? null : Lrc.parse(lrcText);
        lastLine = -2;
        lastCount = -1;
        lyricsView.setText("");
        countdownView.setText("");
        loadOffset();
        applyChannel();
        boolean showPhotos = video == null && !audioIsVideo;           // an audio-only song: photos behind the lyrics
        backdropView.setVisibility(debugSingleFile ? android.view.View.GONE : android.view.View.VISIBLE);
        dimView.setVisibility(debugSingleFile ? android.view.View.GONE : android.view.View.VISIBLE);
        backdrop.stop();
        if (!debugSingleFile) backdrop.start(showPhotos);
        // Only show the video surface when there is a picture to show: an idle surface keeps the previous song's
        // last frame and would cover the photo backdrop (it sits above it).
        videoBand.setVisibility(showPhotos ? android.view.View.INVISIBLE : android.view.View.VISIBLE);
        wantMp3 = video != null;
        videoCrop = video == null ? null : crops.get(videoStem);
        player.setVolume(video != null ? 0f : 1f);          // muted until the karaoke mp3 is the selected track
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_AUDIO).build());
        if (video != null) {
            androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory pf =
                    new androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(new androidx.media3.datasource.DefaultDataSource.Factory(this));
            player.setMediaSource(new androidx.media3.exoplayer.source.MergingMediaSource(
                    pf.createMediaSource(MediaItem.fromUri(android.net.Uri.parse(video))),
                    pf.createMediaSource(MediaItem.fromUri(android.net.Uri.parse(audioUri)))));
        } else {
            player.setMediaItem(MediaItem.fromUri(android.net.Uri.parse(audioUri)));
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

    private File flipFile() { return priv("flips.txt"); }

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
    @Override public History history() { return history; }

    @Override public void command(final String cmd) {
        runOnUiThread(new Runnable() {
            @Override public void run() { handle(cmd); }
        });
    }

    // ---------- sleep mode ----------

    private void readSleepConfig() {
        lastConfigRead = System.currentTimeMillis();
        final Storage st = storage;
        if (st == null) return;
        new Thread(new Runnable() {                 // reading through the folder picker's storage: never on the main thread
            @Override public void run() {
                try {
                    String t = textInRoot(st, "config.properties");
                    if (t == null) return;
                    java.util.Properties p = new java.util.Properties();
                    p.load(new java.io.StringReader(t));
                    String v = p.getProperty("sleep_minutes");
                    if (v != null) sleepMs = Math.max(0L, (long) (Double.parseDouble(v.trim()) * 60000L));
                    try {   // how long the bottom-right label shows Now / Next (seconds, at least 1)
                        String a = p.getProperty("now_seconds"), b = p.getProperty("next_seconds");
                        if (a != null) nowMs = Math.max(1000L, (long) (Double.parseDouble(a.trim()) * 1000L));
                        if (b != null) nextMs = Math.max(1000L, (long) (Double.parseDouble(b.trim()) * 1000L));
                    } catch (NumberFormatException ignored) { }
                    String bn = p.getProperty("banner");    // banner=off hides the "Now singing" / "Next up" banners
                    if (bn != null) banners = !bn.trim().equalsIgnoreCase("off");
                } catch (Exception ignored) { }     // no file or bad value: keep the current setting (default 5 minutes)
            }
        }).start();
    }

    /** Called for every remote key and phone action. Returns true if it just woke the screen. */
    private boolean touch() {
        lastActivity = System.currentTimeMillis();
        if (!sleeping) return false;
        sleeping = false;
        sleepView.setVisibility(android.view.View.GONE);
        return true;
    }

    private void sleepTick() {
        long now = System.currentTimeMillis();
        if (now - lastConfigRead > 30000) readSleepConfig();
        if (debugSingleFile) return;
        if (cPlaying && !cPaused) {                       // a song is playing: that is activity
            if (sleeping) touch(); else lastActivity = now;
            return;
        }
        if (!sleeping && sleepMs > 0 && now - lastActivity >= sleepMs) {
            sleeping = true;
            lastDrift = 0;
            sleepView.setVisibility(android.view.View.VISIBLE);
        }
        if (sleeping) {
            sleepClock.setText(new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(new java.util.Date(now)));
            if (now - lastDrift > 60000) {                // move the QR + clock a bit every minute (no burn-in)
                lastDrift = now;
                java.util.Random r = new java.util.Random();
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) sleepBox.getLayoutParams();
                lp.gravity = Gravity.TOP | Gravity.START;
                android.util.DisplayMetrics dm = getResources().getDisplayMetrics();           // stay on the real screen, whatever its size
                int bw = Math.max(sleepBox.getWidth(), 1), bh = Math.max(sleepBox.getHeight(), 1);
                int mx = dm.widthPixels / 20, my = dm.heightPixels / 14;
                lp.setMargins(mx + r.nextInt(Math.max(1, dm.widthPixels - bw - 2 * mx)), my + r.nextInt(Math.max(1, dm.heightPixels - bh - 2 * my)), 0, 0);
                sleepBox.setLayoutParams(lp);
            }
        }
    }

    private void handle(String cmd) {
        touch();
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
            o.put("menu", menuShown);
            o.put("browse", browseShown);                          // the on-TV song browser is open
            if (appFolder != null) o.put("songFolder", appFolder.getPath());
            o.put("setup", setupShown);                            // the TV is asking for the Karaoke folder
            if (setupShown) o.put("setupText", setupMsg);
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

    private static String fmt(long ms) { long s = Math.max(0, ms) / 1000; return String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60); }

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
    // the bottom-right label shows Now for now_seconds, then Next for next_seconds, and so on (config.properties, defaults 8 and 4)
    private volatile long nowMs = 8000, nextMs = 4000;
    private volatile boolean banners = true;
    private static final float NOW_MAX_FRACTION = 0.48f;    // the bottom-right Now line may use at most this share of the width

    private String fitLine(String prefix, String title, String suffix, float widthFraction) {
        int usable = getResources().getDisplayMetrics().widthPixels - 2 * safeX;
        int maxPx = widthFraction >= 1f ? usable - px(48) /* padding */ - px(24) - px(200) /* QR + gap */
                : Math.round(usable * widthFraction) - px(48);
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
                    : lib.songs.size() + " songs  -  press OK to choose one here, or scan the code with your phone";
            idleInfo.setText(info);
            status.setVisibility(android.view.View.INVISIBLE);
            nowView.setVisibility(android.view.View.INVISIBLE);
            statusBar.setVisibility(android.view.View.INVISIBLE);
            return;
        }
        statusBar.setVisibility(android.view.View.VISIBLE);
        String st = player.getPlaybackState() == Player.STATE_BUFFERING ? "Buffering" : (cPaused ? "Paused" : "Playing");
        String time = fmt(cPos) + (cDur > 0 ? " / " + fmt(cDur) : "");
        StringBuilder sb = new StringBuilder();
        String nowText = "";
        if (!debugSingleFile) {
            QueueManager.Item cur = queue.current();
            java.util.List<QueueManager.Item> up = queue.upcoming();
            // one label, bottom-right: "Now: ..." for 5 s, then (if a song is queued) "Next: ..." for 5 s, and so on
            boolean showNext = !up.isEmpty() && System.currentTimeMillis() % (nowMs + nextMs) >= nowMs;
            nowText = showNext
                    ? fitLine("Next: ", titleOf(up.get(0), false), up.size() > 1 ? "  (+" + (up.size() - 1) + " more)" : "", NOW_MAX_FRACTION)
                    : fitLine("Now: ", cur == null ? "-" : titleOf(cur, true), "", NOW_MAX_FRACTION);
        }
        status.setVisibility(android.view.View.INVISIBLE);       // the old top-left Now/Next bar is gone
        status.setText("");
        sb.append(st).append("  |  ").append(vocalOn ? "VOCAL ON" : "KARAOKE").append("  |  ").append(time);
        if (lrc != null) sb.append("  |  Lyrics ").append(lyricOffsetMs >= 0 ? "+" : "-").append(String.format(java.util.Locale.US, "%.1fs", Math.abs(lyricOffsetMs) / 1000.0));
        if (System.currentTimeMillis() < hintUntil) {
            sb.append("\nOK: pause   hold OK / Menu: vocal   Left/Right: seek or skip   Up/Down: lyrics later/earlier   Back: songs");
        }
        statusBar.setText(sb.toString());
        // the key-help hint makes the status bar long for a few seconds: the Now line steps aside so the two never touch
        boolean showNow = !nowText.isEmpty() && System.currentTimeMillis() >= hintUntil;
        nowView.setVisibility(showNow ? android.view.View.VISIBLE : android.view.View.INVISIBLE);
        if (showNow && !nowText.contentEquals(nowView.getText())) nowView.setText(nowText);
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
    // share of the screen height kept free above / below the video (a thin top margin, status bar ~7% of 1080p)
    private static final float VIDEO_TOP_MARGIN = 0.03f, VIDEO_BOTTOM_MARGIN = 0.07f;

    private void fitVideo(androidx.media3.common.VideoSize vs) {
        if (vs.width == 0 || vs.height == 0) return;
        int rw = rootView.getWidth(), rh = rootView.getHeight();
        if (rw == 0 || rh == 0) { android.util.DisplayMetrics m = getResources().getDisplayMetrics(); rw = m.widthPixels; rh = m.heightPixels; }
        float par = vs.pixelWidthHeightRatio;
        // Every video is fitted (never cropped, except for burned-in black bars from crops.txt) into the same band:
        // centred, with a small margin on top (the QR code may overlap the picture: it is drawn above it) and at the bottom for the status bar.
        int top = Math.round(rh * VIDEO_TOP_MARGIN), bottom = Math.round(rh * VIDEO_BOTTOM_MARGIN);
        int bh = rh - top - bottom;
        int cx = 0, cy = 0, cw = vs.width, ch = vs.height;
        int[] c = videoCrop;
        if (c != null && c[4] == vs.width && c[5] == vs.height) { cx = c[0]; cy = c[1]; cw = c[2]; ch = c[3]; }
        float cd = cw * par;                                    // displayed width of the picture area
        float s = Math.min(rw / cd, (float) bh / ch);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-1, bh, Gravity.TOP);
        bp.topMargin = top;
        videoBand.setLayoutParams(bp);
        int sw = Math.round(vs.width * par * s), sh = Math.round(vs.height * s);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(sw, sh, Gravity.TOP | Gravity.START);
        lp.leftMargin = Math.round((rw - cd * s) / 2 - cx * par * s);
        lp.topMargin = Math.round((bh - ch * s) / 2 - cy * s);
        surfaceView.setLayoutParams(lp);
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
        // The dots are drawn INSIDE the lyric block (in the slot of the line being sung, see showLyrics) so they can never
        // overlap the lyrics; here we only note how many are lit and which line comes next.
        lastCount = count;
        cdNext = count > 0 ? n : -1;
    }

    private String describe(QueueManager.Item it) {
        Library.Song s = lib.byId(it.songId);
        String t = s == null ? "(missing song)" : s.title;
        return it.singer.isEmpty() ? t : it.singer + " - " + t;
    }

    /** "Next up" for the last 15 s of a song. (The "Now singing" banner was removed: the bottom bars already show the song and the singer.) */
    private void updateBanner() {
        String text = null;
        if (banners && !debugSingleFile && (cPlaying || cPaused)) {
            java.util.List<QueueManager.Item> up = queue.upcoming();
            if (!up.isEmpty() && cDur > 25000 && cDur - cPos <= 15000) text = "Next up:  " + describe(up.get(0));
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
        // Every word has been sung (the last line with text is over): take the lyrics off the screen instead of leaving the last lines up
        int lastText = -1;
        for (int k = lrc.lines.size() - 1; k >= 0; k--) if (!lrc.lines.get(k).text.isEmpty()) { lastText = k; break; }
        if (lastText >= 0 && i >= lastText && pos >= lrc.endOf(lastText) + 1500) {
            if (lastLine != -9) { lastLine = -9; lyricsView.setText(""); }
            return;
        }
        int ci = i >= 0 ? lrc.currentChar(i, pos) : -1;   // real word times if the .lrc has them, else the estimate
        int cd = Math.max(0, lastCount), next = cdNext;       // 3-2-1 countdown dots before a long instrumental gap
        // a line that has been sung goes off the screen at once (only the line being sung and the ones still to come stay)
        boolean over = i >= 0 && !lrc.lines.get(i).text.isEmpty() && pos >= lrc.endOf(i) + 300;
        int key = i * 1000 + ci + cd * 10000000 + (over ? 500000000 : 0);
        if (key == lastLine) return;
        lastLine = key;

        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        if (cd > 0 && next >= 0) {
            // [the dots, in the big "current" slot] [the next lines, dim]; the line just sung is already gone
            int ds = sb.length();
            for (int d = 0; d < 3; d++) {
                int st = sb.length();
                sb.append("●  ");
                sb.setSpan(new android.text.style.ForegroundColorSpan(d < cd ? 0xFFFFE23B : 0x44FFFFFF), st, st + 1, 0);
            }
            
            sb.append("\n\n");
            for (int k = next; k <= Math.min(lrc.lines.size() - 1, next + 1); k++) {
                String t = shownText(lrc.lines.get(k));
                int st = sb.length();
                sb.append(t).append("\n\n");
                sb.setSpan(new android.text.style.RelativeSizeSpan(0.68f), st, st + t.length(), 0);
                sb.setSpan(new android.text.style.ForegroundColorSpan(0x99FFFFFF), st, st + t.length(), 0);
            }
            trimTrailingBreaks(sb);
            lyricsView.setText(sb, TextView.BufferType.SPANNABLE);
            return;
        }
        int first = over ? i + 1 : Math.max(0, i);              // only two lines on screen: the one being sung and the next
        for (int k = first; k <= Math.min(lrc.lines.size() - 1, first + 1); k++) {
            Lrc.Line ln = lrc.lines.get(k);
            String t = shownText(ln);
            int st = sb.length();
            sb.append(t).append("\n\n");
            boolean cur = k == i;
            if (!cur) sb.setSpan(new android.text.style.RelativeSizeSpan(0.68f), st, st + t.length(), 0);
            if (!cur) {
                sb.setSpan(new android.text.style.ForegroundColorSpan(0x99FFFFFF), st, st + t.length(), 0);
                continue;
            }
            int rank = 0;
            boolean[] turn = ln.text.isEmpty() ? null : ln.turn;     // "(合)" markers: shown as plain text, never highlighted
            for (int j = 0; j < t.length(); j++) {
                if (turn != null && j < turn.length && turn[j]) { sb.setSpan(new android.text.style.ForegroundColorSpan(0xFFDDDDDD), st + j, st + j + 1, 0); continue; }
                if (Character.isWhitespace(t.charAt(j))) { sb.setSpan(new android.text.style.ForegroundColorSpan(0xFFFFFFFF), st + j, st + j + 1, 0); continue; }
                int color = rank < ci ? 0xFFFFE23B            // already sung: yellow
                        : rank == ci ? 0xFFFF7A3B             // being sung now: orange + underline
                        : 0xFFFFFFFF;                          // still to come: white
                sb.setSpan(new android.text.style.ForegroundColorSpan(color), st + j, st + j + 1, 0);
                if (rank == ci) sb.setSpan(new android.text.style.UnderlineSpan(), st + j, st + j + 1, 0);
                rank++;
            }
        }
        trimTrailingBreaks(sb);
        lyricsView.setText(sb, TextView.BufferType.SPANNABLE);
    }

    /** What a lyric line looks like on screen: its text with the singer-turn markers; a line with nothing to sing stays blank. */
    private static String shownText(Lrc.Line l) {
        return l.text.isEmpty() || l.shown.isEmpty() ? " " : l.shown;
    }

    /** The block is bottom-aligned, so the blank lines after the last lyric line must go. */
    private static void trimTrailingBreaks(android.text.SpannableStringBuilder sb) {
        int n = sb.length();
        while (n > 0 && sb.charAt(n - 1) == '\n') n--;
        if (n < sb.length()) sb.delete(n, sb.length());
    }

    /** While a song plays (after its first seconds), reads the start of the next queued song so its start is fast; checked every 2 s. */
    private void warmNextSong(long now) {
        if (now - lastWarmCheck < 2000) return;
        lastWarmCheck = now;
        if (!cPlaying || cPos < 8000 || storage == null || queue == null || debugSingleFile) return;
        java.util.List<QueueManager.Item> up = queue.upcoming();
        if (up.isEmpty()) return;
        Library.Song s = lib.byId(up.get(0).songId);
        if (s == null || s.audioH.isEmpty() || s.id.equals(prefetcher.wantedId())) return;
        prefetcher.request(storage, s.id, s.audioH, s.videoH, s.lrcH);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refresh(); showLyrics(); updateBanner(); sleepTick();
            long now = System.currentTimeMillis();
            warmNextSong(now);
            if (history != null && now - lastPrune > 3600000L) { lastPrune = now; history.prune(now); }   // housekeeping: drop entries older than 30 days
            ui.postDelayed(this, 250);
        }
    };

    // ---------- remote control keys ----------

    // a mouse (move, click, wheel) or a touch also counts as activity and wakes the screen
    @Override public boolean dispatchGenericMotionEvent(android.view.MotionEvent ev) {
        touch();
        return super.dispatchGenericMotionEvent(ev);
    }

    @Override public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        touch();
        return super.dispatchTouchEvent(ev);
    }

    @Override public boolean onKeyDown(int k, KeyEvent e) {
        if (touch()) { wokeKey = true; return true; }    // the key that wakes the screen does nothing else (not even its key-up)
        if (setupView.getVisibility() == android.view.View.VISIBLE) {      // folder screen: OK always opens the chooser, whatever has focus
            if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_BUTTON_A) { pickFolder(); return true; }
            return super.onKeyDown(k, e);
        }
        if (menuShown) {                                  // settings: BACK closes, OK presses the focused button
            if (k == KeyEvent.KEYCODE_BACK) { hideMenu(); return true; }
            if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_BUTTON_A) {
                android.view.View f = getCurrentFocus();
                (f == menuClose ? menuClose : f == menuLocation && menuLocation.getVisibility() == android.view.View.VISIBLE ? menuLocation : menuReset).performClick();
                return true;
            }
            return super.onKeyDown(k, e);
        }
        if (k == KeyEvent.KEYCODE_BACK && (cPlaying || cPaused) && !debugSingleFile) return true;      // Back while a song plays opens the song list (on key-up)
        if (k == KeyEvent.KEYCODE_MENU && idleNow()) { showMenu(); return true; }
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
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) {
            longPressed = true;
            if (idleNow()) showMenu(); else handle("vocal");      // hold OK: vocal while singing, settings while idle
            return true;
        }
        return super.onKeyLongPress(k, e);
    }

    @Override public boolean onKeyUp(int k, KeyEvent e) {
        if (wokeKey) { wokeKey = false; return true; }
        if (k == KeyEvent.KEYCODE_BACK && !browseShown && !menuShown && !setupShown && (cPlaying || cPaused) && !debugSingleFile) {
            showBrowse();
            return true;
        }
        if ((k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) && player != null) {
            if (!longPressed && !menuShown && !setupShown && !browseShown) {
                if (idleNow()) showBrowse(); else togglePause();       // OK on the idle screen: choose a song here
            }
            longPressed = false;
            return true;
        }
        return super.onKeyUp(k, e);
    }

    private void togglePause() { handle(cPaused ? "resume" : "pause"); }

    // ---------- lyric timing nudge (saved per song) ----------

    private File offsetFile() { return priv("lyric_offsets.properties"); }

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
        prefetcher.cancel();
        if (player != null) player.release();
    }
}
