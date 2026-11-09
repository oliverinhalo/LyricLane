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

    /**
     * Android Auto gives us one line and truncates past it. Measured against
     * the narrowest case — the 1/3-width dashboard card — rather than the full
     * now-playing screen.
     */
    const val MAX_CHARS = 56

    private const val REST = "♪"

    fun format(
        spotify: SpotifyState,
        lyrics: LyricsResult,
        frame: LyricSyncEngine.Frame,
        secondaryLine: SecondaryLine = SecondaryLine.NEXT_LINE,
        lyricsEnabled: Boolean = true,
        packTwoLines: Boolean = false,
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
                secondary = "Start a song in Spotify",
            )
        }
        val songInfo = songInfo(track.title, track.artist)
        if (track.isAdvertisement) {
            return CarDisplay(primary = "Advertisement", secondary = "Lyrics resume after the break")
        }
        if (!lyricsEnabled) {
            return CarDisplay(truncate(track.title), truncate(track.artist))
        }

        return when (lyrics) {
            is LyricsResult.Idle -> CarDisplay(truncate(track.title), truncate(track.artist))

            is LyricsResult.Loading -> CarDisplay(truncate(track.title), "Finding lyrics…")

            is LyricsResult.NotFound ->
                CarDisplay(truncate(track.title), "No lyrics found · ${truncate(track.artist)}")

            is LyricsResult.Error ->
                CarDisplay(truncate(track.title), "Lyrics unavailable · offline?")

            is LyricsResult.Instrumental ->
                CarDisplay("$REST  Instrumental", truncate(songInfo))

            is LyricsResult.PlainOnly ->
                CarDisplay("Lyrics not synced", truncate(songInfo))

            is LyricsResult.Synced -> syncedDisplay(frame, songInfo, secondaryLine, packTwoLines)
        }
    }

    private fun syncedDisplay(
        frame: LyricSyncEngine.Frame,
        songInfo: String,
        secondaryLine: SecondaryLine,
        packTwoLines: Boolean,
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
            leadText == null -> truncate(songInfo)
            packTwoLines && followText != null -> "${truncate(leadText)}\n${truncate(followText)}"
            else -> truncate(leadText)
        }

        val secondary = when (secondaryLine) {
            SecondaryLine.SONG_INFO -> truncate(songInfo)
            SecondaryLine.PREVIOUS_LINE ->
                frame.previous?.text?.takeIf { it.isNotBlank() }?.let(::truncate)
                    ?: truncate(songInfo)

            SecondaryLine.NEXT_LINE ->
                if (packTwoLines) truncate(songInfo) else followText?.let(::truncate) ?: truncate(songInfo)
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
