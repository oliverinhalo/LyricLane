package dev.lyriclane.spotify

import android.graphics.Bitmap

/** What Spotify says it is playing. */
data class SpotifyTrack(
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long,
    val artwork: Bitmap?,
    /** Spotify flags its own audio ads; we must not go looking for their lyrics. */
    val isAdvertisement: Boolean,
) {
    /**
     * Identity for "is this still the same song". Deliberately excludes artwork,
     * which Spotify often delivers a moment after the text.
     */
    val signature: String get() = "$title|$artist|$album|$durationMs"

    /**
     * Podcasts and audiobooks have no useful lyrics and would waste LRCLIB
     * lookups. Anything over 15 minutes is treated as spoken word.
     */
    val looksLikeSpokenWord: Boolean get() = durationMs > 15 * 60 * 1000L

    /**
     * Without both a title and an artist there is nothing to search with.
     * Spotify leaves the artist empty during ads and on some podcast episodes.
     */
    val canLookUpLyrics: Boolean
        get() = title.isNotBlank() && artist.isNotBlank() &&
            !isAdvertisement && !looksLikeSpokenWord && durationMs > 0L
}

/**
 * A playback snapshot. Position is only valid at [updatedAtElapsedRealtime], so
 * always read it through [positionMsAt] rather than using [positionMs] directly.
 */
data class SpotifyPlayback(
    val isPlaying: Boolean,
    val positionMs: Long,
    val speed: Float,
    val updatedAtElapsedRealtime: Long,
) {
    fun positionMsAt(elapsedRealtimeMs: Long): Long {
        if (!isPlaying) return positionMs
        val elapsed = elapsedRealtimeMs - updatedAtElapsedRealtime
        if (elapsed <= 0L) return positionMs
        return positionMs + (elapsed * speed).toLong()
    }
}

/**
 * Everything the rest of the app needs to know about Spotify.
 *
 * [permissionGranted] false means notification-listener access is missing, so
 * we are blind and the car should say so rather than show a stale lyric.
 */
data class SpotifyState(
    val permissionGranted: Boolean = false,
    val spotifyRunning: Boolean = false,
    val track: SpotifyTrack? = null,
    val playback: SpotifyPlayback? = null,
    /**
     * True when this state was assembled from a microphone identification
     * rather than read from Spotify's session — the radio, someone else's
     * playlist, anything LyricLane cannot see directly.
     *
     * It changes what the car is told when things go wrong, and it means the
     * position is an estimate that drifts rather than a figure Spotify keeps
     * correcting.
     */
    val fromMicrophone: Boolean = false,
) {
    val isPlaying: Boolean get() = playback?.isPlaying == true

    /** Transport controls only mean something when Spotify is the source. */
    val canControlPlayback: Boolean get() = !fromMicrophone && spotifyRunning
}
