package app.shengdu.audio.metadata;

import android.service.notification.NotificationListenerService;

/**
 * Empty notification listener service. Its enabled ComponentName is what grants the app
 * access to {@code MediaSessionManager.getActiveSessions(...)}; the service itself never
 * reads or processes any notification. All session watching lives in
 * {@link MediaSessionMonitor} and only runs while the UI is visible or a stream is active.
 */
public final class MediaSessionListenerService extends NotificationListenerService {
}
