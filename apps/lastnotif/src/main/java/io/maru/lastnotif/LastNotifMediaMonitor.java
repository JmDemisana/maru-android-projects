package io.maru.lastnotif;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.provider.Settings;
import android.util.Log;
import java.util.List;

public class LastNotifMediaMonitor {

    public static class TrackInfo {
        public String title;
        public String artist;
        public String album;
        public boolean isPlaying;

        public TrackInfo(String title, String artist, String album, boolean isPlaying) {
            this.title = title != null ? title : "";
            this.artist = artist != null ? artist : "";
            this.album = album != null ? album : "";
            this.isPlaying = isPlaying;
        }

        public String trackKey() {
            return artist + " - " + title;
        }
    }

    public static boolean isNotificationAccessGranted(Context context) {
        String flat = Settings.Secure.getString(context.getContentResolver(), "enabled_notification_listeners");
        if (flat != null) {
            String packageName = context.getPackageName();
            return flat.contains(packageName);
        }
        return false;
    }

    public static TrackInfo getActiveTrack(Context context) {
        MediaSessionManager manager = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        if (manager == null || !isNotificationAccessGranted(context)) {
            return null;
        }
        try {
            ComponentName cn = new ComponentName(context, LastNotifMediaListenerService.class);
            List<MediaController> controllers = manager.getActiveSessions(cn);
            if (controllers == null || controllers.isEmpty()) {
                return null;
            }

            MediaController activeController = getActiveController(controllers);
            if (activeController != null) {
                return getTrackInfoFromController(activeController);
            }
        } catch (SecurityException se) {
            // Access permission revoked
            Log.w("LastNotifMediaMonitor", "Notification access permission revoked", se);
        } catch (Exception e) {
            // Ignore
            Log.w("LastNotifMediaMonitor", "Exception in getActiveTrack", e);
        }
        return null;
    }

    private static MediaController getActiveController(List<MediaController> controllers) {
        if (controllers == null || controllers.isEmpty()) {
            return null;
        }
        // Find the playing controller first
        for (MediaController mc : controllers) {
            PlaybackState state = mc.getPlaybackState();
            if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                return mc;
            }
        }
        // Fallback to the first controller if none is active
        return controllers.get(0);
    }

    private static TrackInfo getTrackInfoFromController(MediaController activeController) {
        MediaMetadata metadata = activeController.getMetadata();
        PlaybackState state = activeController.getPlaybackState();
        boolean isPlaying = state != null && state.getState() == PlaybackState.STATE_PLAYING;

        if (metadata != null) {
            String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
            String artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
            String album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM);

            // Fallbacks to display keys if primary ones are empty
            if (title == null || title.isEmpty()) {
                title = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            }
            if (artist == null || artist.isEmpty()) {
                artist = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
            }

            if ((title != null && !title.isEmpty()) || (artist != null && !artist.isEmpty())) {
                return new TrackInfo(title, artist, album, isPlaying);
            }
        }
        return null;
    }
}
