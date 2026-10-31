package com.fehlenfusion.immichscreensaver;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Exists only so Android lets this app see other apps' media sessions (what is playing, and
 * play/pause/skip), which requires notification-listener access. Notifications themselves are
 * ignored: nothing is read, stored or logged.
 *
 * Enable over adb:
 *   adb shell cmd notification allow_listener \
 *     com.fehlenfusion.immichscreensaver/com.fehlenfusion.immichscreensaver.MediaListenerService
 */
public final class MediaListenerService extends NotificationListenerService {
    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        // intentionally ignored
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // intentionally ignored
    }
}
