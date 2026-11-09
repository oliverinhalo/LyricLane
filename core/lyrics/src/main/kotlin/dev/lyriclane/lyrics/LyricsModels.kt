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

/**
 * One timed line. [text] is empty for a gap, which the UI renders as a rest.
 *
 * [endMs] is when the line stops being sung. Plain LRC has no such thing — it
 * only says when each line *starts* — but LRCLIB's richer lyrics file does, and
 * knowing it is what lets the car show the next line during an instrumental
 * break instead of leaving the last one sitting there.
 */
data class LyricLine(val timeMs: Long, val text: String, val endMs: Long? = null) {
    val isGap: Boolean get() = text.isBlank()

    /**
     * True once this line has finished, when we know when that is.
     *
     * Inclusive of the end timestamp itself: the engine wakes exactly at
     * [endMs], and an exclusive test there would report the line as still
     * being sung and skip the gap entirely.
     */
    fun hasEndedBy(positionMs: Long): Boolean = endMs != null && positionMs >= endMs
}

/** Where a set of lyrics came from. Shown in the picker so a choice is explicable. */
enum class LyricsSourceId(val label: String) {
    LRCLIB("LRCLIB"),
    LRCLIB_WORD_SYNCED("LRCLIB (word-timed)"),
    NETEASE("NetEase"),
    ;

    /** Word-timed entries carry per-line end times, so they sync noticeably better. */
    val hasLineEndTimes: Boolean get() = this == LRCLIB_WORD_SYNCED
}

/** A candidate set of lyrics, as offered to the user in the manual picker. */
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
    val source: LyricsSourceId = LyricsSourceId.LRCLIB,
    /**
     * LRCLIB's structured lyrics document, when the entry has one. Preferred
     * over [syncedLyrics] because it carries line end times.
     */
    val lyricsFile: String? = null,
) {
    val hasLineEndTimes: Boolean get() = lyricsFile != null
}

/** Outcome of looking for lyrics. Every branch has a defined car-screen state. */
sealed interface LyricsResult {
    /** Timed lyrics: the only state that produces a scrolling lyric. */
    data class Synced(
        val lines: List<LyricLine>,
        val fromCache: Boolean,
        val source: LyricsSourceId = LyricsSourceId.LRCLIB,
    ) : LyricsResult {
        /** True when the lyrics know where each line ends, not just where it starts. */
        val hasLineEndTimes: Boolean get() = lines.any { it.endMs != null }
    }

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
