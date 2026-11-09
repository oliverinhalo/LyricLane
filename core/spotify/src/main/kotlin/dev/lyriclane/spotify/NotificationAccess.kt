package dev.lyriclane.spotify

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Notification-listener access is the one permission LyricLane cannot work
 * without: `MediaSessionManager.getActiveSessions` is only callable by an
 * enabled listener (the alternative, MEDIA_CONTENT_CONTROL, is privileged and
 * unavailable to a sideloaded app).
 */
object NotificationAccess {

    fun isGranted(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ) ?: return false
        val us = ComponentName(context, SpotifyNotificationListener::class.java)
        return enabled.split(':').any { entry ->
            val component = ComponentName.unflattenFromString(entry)
            component != null &&
                component.packageName == us.packageName &&
                component.className == us.className
        }
    }

    /**
     * The system settings page where the user grants it. Falls back to the
     * generic page on devices that do not honour the per-app extra.
     */
    fun settingsIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
