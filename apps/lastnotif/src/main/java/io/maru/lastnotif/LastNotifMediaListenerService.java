package io.maru.lastnotif;

import android.service.notification.NotificationListenerService;

/**
 * Service to register the app as a notification listener.
 * This binds the app to Android's media session callbacks,
 * permitting MediaSessionManager to query active system-wide playback controllers.
 */
public class LastNotifMediaListenerService extends NotificationListenerService {
    // Left empty: just used to acquire BIND_NOTIFICATION_LISTENER_SERVICE permission
}
