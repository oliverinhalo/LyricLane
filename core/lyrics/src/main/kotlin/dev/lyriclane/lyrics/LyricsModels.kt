package dev.lyriclane.lyrics

/** The song we want lyrics for, as Spotify described it. */
data class TrackIdentity(
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long,
) {
    /**
     * Cache key. Normalised so that "Song (Remastered 2011)" and "Song" share
     * an entry, and rounded to the nearest second so a millisecond of jitter in
     * Spotify's reported duration does not cause a cache miss.
     */
    val cacheKey: String
        get() = buildString {
            append(TextNormalizer.normalize(artist))
            append('\u0001')
            append(TextNormalizer.normalize(title))
            append('\u0001')
            append(durationMs / 1000)
        }

    val durationSeconds: Int get() = ((durationMs + 500) / 1000).toInt()
}

/** One timed line. [text] is empty for a gap, which the UI renders as a rest. */
data class LyricLine(val timeMs: Long, val text: String) {
    val isGap: Boolean get() = text.isBlank()
}

/** A candidate from LRCLIB, as offered to the user in the manual picker. */
data class LyricsCandidate(
    val id: Long,
    val trackName: String,
    val artistName: String,
    val albumName: String?,
    val durationSeconds: Double,
    val hasSynced: Boolean,
    val instrumental: Boolean,
    val syncedLyrics: String?,
    val plainLyrics: String?,
)

/** Outcome of looking for lyrics. Every branch has a defined car-screen state. */
sealed interface LyricsResult {
    /** Timed lyrics: the only state that produces a scrolling lyric. */
    data class Synced(val lines: List<LyricLine>, val fromCache: Boolean) : LyricsResult

    /** Lyrics exist but carry no timestamps. */
    data class PlainOnly(val text: String, val fromCache: Boolean) : LyricsResult

    /** LRCLIB knows the track and says it has no words. */
    data object Instrumental : LyricsResult

    /** Looked and found nothing. */
    data object NotFound : LyricsResult

    /** Could not look — offline, timeout, server error. Retryable. */
    data class Error(val message: String) : LyricsResult

    /** Not looked at yet. */
    data object Loading : LyricsResult

    /** Nothing to look for (no track, an ad, a podcast). */
    data object Idle : LyricsResult
}
