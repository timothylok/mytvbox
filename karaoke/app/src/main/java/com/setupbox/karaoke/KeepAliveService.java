package com.setupbox.karaoke;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * Keeps the karaoke process at foreground priority while the app is open, so Android does not kill it (and with it
 * the phone web page and the music) when someone presses Home or opens another app on the box.
 * It does no work itself; MainActivity starts it in onCreate and stops it in onDestroy.
 */
public class KeepAliveService extends Service {
    private static final String CHANNEL = "karaoke";

    public static void start(Context c, String url) {
        Intent i = new Intent(c, KeepAliveService.class).putExtra("url", url);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    public static void stop(Context c) { c.stopService(new Intent(c, KeepAliveService.class)); }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String url = intent == null ? "" : intent.getStringExtra("url");
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Karaoke", NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        b.setContentTitle("Karaoke is running")
         .setContentText(url == null || url.isEmpty() ? "Phone page is on" : "Phones: " + url)
         .setSmallIcon(android.R.drawable.ic_media_play)
         .setOngoing(true);
        startForeground(1, b.build());
        return START_NOT_STICKY;      // only meaningful while the activity (which owns the player and server) exists
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
