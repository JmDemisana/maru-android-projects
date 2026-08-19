package io.maru.lastnotif;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.core.app.NotificationCompat;

public class LastNotifNotificationManager {

    private static final String TAG = "LastNotifNotifications";

    public static final String CHANNEL_ALERTS = "lastnotif_alerts";
    public static final String CHANNEL_KEEPALIVE = "lastnotif_keepalive";
    public static final int ID_ALERT = 13001;
    public static final int ID_KEEPALIVE = 13000;

    private final Context ctx;
    private final NotificationManager nm;

    public LastNotifNotificationManager(Context context) {
        this.ctx = context.getApplicationContext();
        this.nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        createChannels();
    }

    private void createChannels() {
        if (nm == null) {
            Log.w(TAG, "NotificationManager unavailable; channels not created.");
            return;
        }

        NotificationChannel alerts = new NotificationChannel(
            CHANNEL_ALERTS,
            ctx.getString(R.string.notif_channel_alerts),
            NotificationManager.IMPORTANCE_HIGH
        );
        alerts.setDescription(ctx.getString(R.string.notif_channel_alerts_desc));
        alerts.setShowBadge(true);

        NotificationChannel keepalive = new NotificationChannel(
            CHANNEL_KEEPALIVE,
            ctx.getString(R.string.notif_channel_keepalive),
            NotificationManager.IMPORTANCE_MIN
        );
        keepalive.setDescription(ctx.getString(R.string.notif_channel_keepalive_desc));
        keepalive.setShowBadge(false);
        keepalive.setSound(null, null);

        try {
            nm.createNotificationChannel(alerts);
            nm.createNotificationChannel(keepalive);
        } catch (RuntimeException e) {
            Log.w(TAG, "Failed to create notification channels.", e);
        }
    }

    public Notification buildKeepaliveNotification() {
        PendingIntent tapIntent = PendingIntent.getActivity(
            ctx,
            0,
            new Intent(ctx, MainActivity.class),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        return new NotificationCompat.Builder(ctx, CHANNEL_KEEPALIVE)
            .setSmallIcon(R.mipmap.ic_launcher_lastnotif_legacy)
            .setContentTitle(ctx.getString(R.string.notif_keepalive_title))
            .setContentText(ctx.getString(R.string.notif_keepalive_text))
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build();
    }

    public void postSongAlert(String title, String artist, String album,
                              String mainFmt, String subFmt, String pollingMethod) {
        String mainText = applyFormat(mainFmt, title, artist, album, pollingMethod);
        String subText = applyFormat(subFmt, title, artist, album, pollingMethod);
        post(mainText, subText);
    }

    public void postLyricAlert(String lyricLine, String title, String artist) {
        String safeTitle = title != null ? title : "";
        String safeArtist = artist != null ? artist : "";
        String safeLyric = lyricLine != null ? lyricLine : "";
        String subText = safeTitle.isEmpty() ? safeArtist : safeTitle + " - " + safeArtist;
        post(safeLyric.isEmpty() ? "Music" : safeLyric, subText);
    }

    private void post(String main, String sub) {
        if (nm == null) {
            Log.w(TAG, "NotificationManager unavailable; notification skipped.");
            return;
        }

        PendingIntent tapIntent = PendingIntent.getActivity(
            ctx,
            0,
            new Intent(ctx, MainActivity.class),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        Notification n = new NotificationCompat.Builder(ctx, CHANNEL_ALERTS)
            .setSmallIcon(R.mipmap.ic_launcher_lastnotif_legacy)
            .setContentTitle(main != null ? main : "")
            .setContentText(sub != null ? sub : "")
            .setContentIntent(tapIntent)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build();

        try {
            nm.notify(ID_ALERT, n);
        } catch (SecurityException e) {
            Log.w(TAG, "Notification permission denied; notification skipped.", e);
        } catch (RuntimeException e) {
            Log.w(TAG, "Failed to post notification.", e);
        }
    }

    private static String applyFormat(String fmt, String title, String artist, String album, String pollingMethod) {
        String safeTitle = title != null ? title : "";
        String safeArtist = artist != null ? artist : "";
        String safeAlbum = album != null ? album : "";
        String safePollingMethod = pollingMethod != null ? pollingMethod : "";
        if (fmt == null || fmt.isEmpty()) return safeTitle;
        return fmt
            .replace("{song_name}", safeTitle)
            .replace("{artist}", safeArtist)
            .replace("{album}", safeAlbum)
            .replace("{polling_method}", safePollingMethod);
    }
}
