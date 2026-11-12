package dev.lyriclane.media

import dev.lyriclane.lyrics.LyricSyncEngine
import dev.lyriclane.lyrics.LyricsResult
import dev.lyriclane.spotify.SpotifyState

/**
 * The two pieces of text the car renders.
 *
 * [primary] is whatever the head unit draws large; [secondary] is the smaller,
 * dimmer line. Which MediaSession field each ends up in is a setting
 * ([CarTextSlots]) because head units disagree, and we cannot ask.
 */
data class CarDisplay(
    val primary: String,
    val secondary: String,
)

/**
 * Which MediaSession field the head unit renders large.
 *
 * Observed on Android Auto (Coolwalk, dashboard card): the **subtitle** is the
 * large white line and the title is the small grey one above it — the opposite
 * of what the field names suggest. Other units may differ, so this is a
 * setting rather than an assumption baked into the code.
 */
enum class CarTextSlots(val label: String) {
    /** Android Auto's observed behaviour: big text comes from the subtitle. */
    SUBTITLE_IS_BIG("Subtitle (Android Auto)"),

    /** The arrangement the field names imply, for head units that follow it. */
    TITLE_IS_BIG("Title"),
}

/**
 * How much text may go on one car line before it is split.
 *
 * The dashboard card is a third of the screen wide. A line that fits the full
 * now-playing screen wraps there, and a wrapped line is often clipped — so the
 * driver sees neither half. Rather than truncate, long lines are split into
 * timed pieces, each shown when its words are actually sung.
 */
enum class CarLineLength(val maxChars: Int, val label: String) {
    SHORT(26, "Short"),
    MEDIUM(36, "Medium"),
    LONG(46, "Long"),
    FULL(56, "Full"),
    ;

    fun next(): CarLineLength = entries[(ordinal + 1) % entries.size]

    companion object {
        /** Sized to fit the split-screen dashboard card on one line. */
        val DEFAULT = MEDIUM
    }
}

/** What the smaller line carries while lyrics are playing. */
enum class SecondaryLine(val label: String) {
    NEXT_LINE("Next line"),
    PREVIOUS_LINE("Previous line"),
    SONG_INFO("Song and artist"),
}

/**
 * Builds the two lines of text the car renders.
 *
 * Two rules drive the whole thing:
 *
 *  1. **The big line always carries words.** During an instrumental break, or
 *     before the first line, it shows what is *coming* rather than a bare rest
 *     symbol — a driver glancing up gets a head start on the next line instead
 *     of a musical note.
 *  2. **Every state says what it is.** No stale lyric from the previous song,
 *     no blank card with no explanation.
 */
object CarDisplayFormatter {

    /** Fallback when no length has been chosen. */
    const val MAX_CHARS = 36

    private const val REST = "♪"

    fun format(
        spotify: SpotifyState,
        lyrics: LyricsResult,
        frame: LyricSyncEngine.Frame,
        secondaryLine: SecondaryLine = SecondaryLine.NEXT_LINE,
        lyricsEnabled: Boolean = true,
        packTwoLines: Boolean = false,
        maxChars: Int = MAX_CHARS,
    ): CarDisplay {
        if (!spotify.permissionGranted) {
            return CarDisplay(
                primary = "Notification access needed",
                secondary = "Open LyricLane on your phone to grant it",
            )
        }
        val track = spotify.track
        if (!spotify.spotifyRunning || track == null) {
            return CarDisplay(
                primary = "Nothing playing on Spotify",
                secondary = "Start Spotify, or tap Identify what's playing",
            )
        }
        val songInfo = songInfo(track.title, track.artist)
        if (track.isAdvertisement) {
            return CarDisplay(primary = "Advertisement", secondary = "Lyrics resume after the break")
        }
        if (!lyricsEnabled) {
            return CarDisplay(truncate(track.title, maxChars), truncate(track.artist, maxChars))
        }

        return when (lyrics) {
            is LyricsResult.Idle ->
                CarDisplay(truncate(track.title, maxChars), truncate(track.artist, maxChars))

            is LyricsResult.Loading ->
                CarDisplay(truncate(track.title, maxChars), "Finding lyrics…")

            is LyricsResult.NotFound -> CarDisplay(
                truncate(track.title, maxChars),
                "No lyrics found · ${truncate(track.artist, maxChars)}",
            )

            is LyricsResult.Error ->
                CarDisplay(truncate(track.title, maxChars), "Lyrics unavailable · offline?")

            is LyricsResult.Instrumental ->
                CarDisplay("$REST  Instrumental", truncate(songInfo, maxChars))

            is LyricsResult.PlainOnly ->
                CarDisplay("Lyrics not synced", truncate(songInfo, maxChars))

            is LyricsResult.Synced ->
                syncedDisplay(frame, songInfo, secondaryLine, packTwoLines, maxChars)
        }
    }

    private fun syncedDisplay(
        frame: LyricSyncEngine.Frame,
        songInfo: String,
        secondaryLine: SecondaryLine,
        packTwoLines: Boolean,
        maxChars: Int,
    ): CarDisplay {
        val sung = frame.current?.takeIf { !it.isGap }
        // Nothing is being sung right now — before the first line, or between
        // verses — so look ahead instead of showing a rest.
        val headsUp = sung == null || frame.inGap
        val showingNext = headsUp && frame.next != null

        val lead = if (showingNext) frame.next else sung
        val follow = if (showingNext) frame.afterNext else frame.next

        val leadText = lead?.text?.takeIf { it.isNotBlank() }
        val followText = follow?.text?.takeIf { it.isNotBlank() }

        val primary = when {
            leadText == null -> truncate(songInfo, maxChars)
            packTwoLines && followText != null ->
                "${truncate(leadText, maxChars)}\n${truncate(followText, maxChars)}"

            else -> truncate(leadText, maxChars)
        }

        val secondary = when (secondaryLine) {
            SecondaryLine.SONG_INFO -> truncate(songInfo, maxChars)
            SecondaryLine.PREVIOUS_LINE ->
                frame.previous?.text?.takeIf { it.isNotBlank() }
                    ?.let { truncate(it, maxChars) }
                    ?: truncate(songInfo, maxChars)

            SecondaryLine.NEXT_LINE -> when {
                packTwoLines -> truncate(songInfo, maxChars)
                followText != null -> truncate(followText, maxChars)
                else -> truncate(songInfo, maxChars)
            }
        }
        return CarDisplay(primary, secondary)
    }

    private fun songInfo(title: String, artist: String): String =
        if (artist.isBlank()) title else "$title · $artist"

    /**
     * Truncates at a word boundary where one is close to the limit, so the car
     * shows "a long line of words cut short…" rather than cutting mid-word.
     */
    fun truncate(text: String, maxChars: Int = MAX_CHARS): String {
        val trimmed = text.trim()
        if (trimmed.length <= maxChars) return trimmed
        val hardCut = trimmed.take(maxChars - 1)
        val lastSpace = hardCut.lastIndexOf(' ')
        val body = if (lastSpace >= maxChars / 2) hardCut.take(lastSpace) else hardCut
        return body.trimEnd(' ', ',', ';', '-') + "…"
    }
}
