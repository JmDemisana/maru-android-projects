package io.maru.lastnotif

import android.service.notification.NotificationListenerService

/**
 * Service to register the app as a notification listener.
 * This binds the app to Android's media session callbacks.
 */
class LastNotifMediaListenerService : NotificationListenerService()
