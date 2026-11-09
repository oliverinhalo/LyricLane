package dev.lyriclane.spotify

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Watches Spotify's own MediaSession and republishes it as a [SpotifyState].
 *
 * This is a read-only mirror plus a transport relay: we never play audio, and
 * every control the car sends is forwarded to Spotify's transport controls.
 *
 * Must be started on the main thread. All callbacks are delivered on the main
 * looper, so the state flow is only written from one thread.
 */
class SpotifyWatcher(
    private val context: Context,
    private val spotifyPackage: String = SPOTIFY_PACKAGE,
) {

    private val handler = Handler(Looper.getMainLooper())
    private val listenerComponent = ComponentName(context, SpotifyNotificationListener::class.java)

    private val sessionManager: MediaSessionManager? =
        context.getSystemService(MediaSessionManager::class.java)

    private val _state = MutableStateFlow(SpotifyState())
    val state: StateFlow<SpotifyState> = _state.asStateFlow()

    private var controller: MediaController? = null
    private var started = false

    private val sessionsChanged =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            bindTo(controllers.orEmpty())
        }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            publish()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            publish()
        }

        override fun onSessionDestroyed() {
            // Spotify tears its session down when it has been idle for a while.
            releaseController()
            refresh()
        }
    }

    fun start() {
        if (started) return
        started = true
        if (!NotificationAccess.isGranted(context)) {
            _state.value = SpotifyState(permissionGranted = false)
            return
        }
        val manager = sessionManager ?: return
        try {
            manager.addOnActiveSessionsChangedListener(sessionsChanged, listenerComponent, handler)
            bindTo(manager.getActiveSessions(listenerComponent))
        } catch (e: SecurityException) {
            // Access was revoked between the check above and the call.
            Log.w(TAG, "notification listener access refused", e)
            _state.value = SpotifyState(permissionGranted = false)
        }
    }

    fun stop() {
        if (!started) return
        started = false
        sessionManager?.removeOnActiveSessionsChangedListener(sessionsChanged)
        releaseController()
    }

    /**
     * Re-reads the active sessions. Call after the user grants notification
     * access, or when resuming the UI: there is no broadcast for "access was
     * granted", so the only way to notice is to look again.
     */
    fun refresh() {
        if (!NotificationAccess.isGranted(context)) {
            _state.value = SpotifyState(permissionGranted = false)
            return
        }
        val manager = sessionManager ?: return
        try {
            if (!started) {
                start()
            } else {
                bindTo(manager.getActiveSessions(listenerComponent))
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "notification listener access refused", e)
            _state.value = SpotifyState(permissionGranted = false)
        }
    }

    // --- transport, forwarded straight to Spotify -----------------------------------

    fun play() {
        controller?.transportControls?.play()
    }

    fun pause() {
        controller?.transportControls?.pause()
    }

    fun skipToNext() {
        controller?.transportControls?.skipToNext()
    }

    fun skipToPrevious() {
        controller?.transportControls?.skipToPrevious()
    }

    fun seekTo(positionMs: Long) {
        controller?.transportControls?.seekTo(positionMs)
    }

    // --- internals ------------------------------------------------------------------

    private fun bindTo(controllers: List<MediaController>) {
        val spotify = controllers.firstOrNull { it.packageName == spotifyPackage }
        if (spotify == null) {
            releaseController()
            _state.value = SpotifyState(permissionGranted = true, spotifyRunning = false)
            return
        }
        // Spotify replaces its session rather than reusing it in some versions,
        // so compare identity and rebind when it changes.
        if (spotify.sessionToken != controller?.sessionToken) {
            releaseController()
            controller = spotify
            spotify.registerCallback(controllerCallback, handler)
        }
        publish()
    }

    private fun releaseController() {
        controller?.unregisterCallback(controllerCallback)
        controller = null
    }

    private fun publish() {
        val active = controller
        if (active == null) {
            _state.value = SpotifyState(permissionGranted = true, spotifyRunning = false)
            return
        }
        _state.value = SpotifyState(
            permissionGranted = true,
            spotifyRunning = true,
            track = active.metadata?.toTrack(),
            playback = active.playbackState?.toPlayback(),
        )
    }

    private fun MediaMetadata.toTrack(): SpotifyTrack? {
        val title = getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
        val artist = (
            getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            )?.trim().orEmpty()
        if (title.isEmpty()) return null
        return SpotifyTrack(
            title = title,
            artist = artist,
            album = getString(MediaMetadata.METADATA_KEY_ALBUM)?.trim()?.takeIf { it.isNotEmpty() },
            durationMs = getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0L),
            artwork = getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
            // The framework MediaMetadata has no advertisement flag (only the
            // compat class does), so this is text-based: Spotify labels its own
            // audio ads in the title.
            isAdvertisement = title.contains("advertisement", ignoreCase = true) ||
                artist.contains("advertisement", ignoreCase = true),
        )
    }

    private fun PlaybackState.toPlayback(): SpotifyPlayback {
        val playing = state == PlaybackState.STATE_PLAYING
        return SpotifyPlayback(
            isPlaying = playing,
            positionMs = position.coerceAtLeast(0L),
            speed = if (playing && playbackSpeed > 0f) playbackSpeed else 1f,
            // Spotify sometimes reports 0 here; treat that as "right now" so the
            // extrapolation does not run away by the whole uptime of the device.
            updatedAtElapsedRealtime = if (lastPositionUpdateTime > 0L) {
                lastPositionUpdateTime
            } else {
                SystemClock.elapsedRealtime()
            },
        )
    }

    companion object {
        private const val TAG = "LyricLane"
        const val SPOTIFY_PACKAGE = "com.spotify.music"
    }
}
