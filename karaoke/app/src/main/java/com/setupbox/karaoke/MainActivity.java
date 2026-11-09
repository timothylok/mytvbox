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
    private TextView status;
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
        if (path == null) {
            status.setText("No file. Start with: --es path /sdcard/...");
            return;
        }
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
        status.setText(st + "  |  " + MODE_NAMES[vocal.getMode()] + "  |  " + time + "\n[OK pause | Left/Right seek 10s | Down change mode]");
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() { if (player != null) showStatus(); ui.postDelayed(this, 1000); }
    };

    @Override public boolean onKeyDown(int k, KeyEvent e) {
        if (player == null) return super.onKeyDown(k, e);
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                player.setPlayWhenReady(!player.getPlayWhenReady()); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: player.seekTo(player.getCurrentPosition() + 10000); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: player.seekTo(Math.max(0, player.getCurrentPosition() - 10000)); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_V:
                vocal.setMode((vocal.getMode() + 1) % 3); showStatus(); return true;
        }
        return super.onKeyDown(k, e);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        if (player != null) player.release();
    }
}
