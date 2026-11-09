package dev.lyriclane.spotify

import android.service.notification.NotificationListenerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Exists so the app can be granted notification-listener access; it reads no
 * notifications itself. Being an *enabled* listener is what makes
 * `MediaSessionManager.getActiveSessions` legal for us.
 *
 * [connected] lets the UI distinguish "the user granted access" from "the
 * system has actually bound us", which can lag by a few seconds and is a
 * frequent source of "I granted it and nothing happened".
 */
class SpotifyNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        _connected.value = true
    }

    override fun onListenerDisconnected() {
        _connected.value = false
        super.onListenerDisconnected()
    }

    companion object {
        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected
    }
}
