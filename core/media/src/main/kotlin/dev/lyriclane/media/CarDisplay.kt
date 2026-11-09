package dev.lyriclane.media

import dev.lyriclane.lyrics.LyricSyncEngine
import dev.lyriclane.lyrics.LyricsResult
import dev.lyriclane.spotify.SpotifyState

/** Exactly what the car shows: two lines, nothing else. */
data class CarDisplay(
    val title: String,
    val subtitle: String,
)

/** What the second line carries when lyrics are synced. */
enum class SubtitleMode { NEXT_LINE, SONG_INFO }

/**
 * Builds the two lines of text the car renders.
 *
 * Every state has a defined output. A driver glancing at the screen must never
 * see a stale lyric from the previous song, or a blank card with no explanation
 * of what went wrong — so "no permission", "no lyrics", "offline" and "ad
 * break" all say so in plain words.
 */
object CarDisplayFormatter {

    /**
     * Android Auto gives us one line and truncates past it. Measured against
     * the narrowest case — the 1/3-width dashboard card — rather than the
     * full now-playing screen.
     */
    const val MAX_CHARS = 56

    private const val REST = "♪"

    fun format(
        spotify: SpotifyState,
        lyrics: LyricsResult,
        frame: LyricSyncEngine.Frame,
        subtitleMode: SubtitleMode = SubtitleMode.NEXT_LINE,
        lyricsEnabled: Boolean = true,
    ): CarDisplay {
        if (!spotify.permissionGranted) {
            return CarDisplay(
                title = "Notification access needed",
                subtitle = "Open LyricLane on your phone to grant it",
            )
        }
        val track = spotify.track
        if (!spotify.spotifyRunning || track == null) {
            return CarDisplay(
                title = "Nothing playing on Spotify",
                subtitle = "Start a song in Spotify",
            )
        }
        val songInfo = songInfo(track.title, track.artist)
        if (track.isAdvertisement) {
            return CarDisplay(title = "Advertisement", subtitle = "Lyrics resume after the break")
        }
        if (!lyricsEnabled) {
            return CarDisplay(title = truncate(track.title), subtitle = truncate(track.artist))
        }

        return when (lyrics) {
            is LyricsResult.Idle ->
                CarDisplay(truncate(track.title), truncate(track.artist))

            is LyricsResult.Loading ->
                CarDisplay(truncate(track.title), "Finding lyrics…")

            is LyricsResult.NotFound ->
                CarDisplay(truncate(track.title), "No lyrics found · ${truncate(track.artist)}")

            is LyricsResult.Error ->
                CarDisplay(truncate(track.title), "Lyrics unavailable · offline?")

            is LyricsResult.Instrumental ->
                CarDisplay("$REST  Instrumental", truncate(songInfo))

            is LyricsResult.PlainOnly ->
                CarDisplay("Lyrics not synced", truncate(songInfo))

            is LyricsResult.Synced -> syncedDisplay(frame, songInfo, subtitleMode)
        }
    }

    private fun syncedDisplay(
        frame: LyricSyncEngine.Frame,
        songInfo: String,
        subtitleMode: SubtitleMode,
    ): CarDisplay {
        val current = frame.current
        // Before the first timestamp, and during an instrumental gap, show a
        // rest rather than holding the previous line on screen.
        val title = when {
            current == null -> REST
            current.isGap -> REST
            else -> truncate(current.text)
        }
        val next = frame.next
        val subtitle = when {
            subtitleMode == SubtitleMode.SONG_INFO -> truncate(songInfo)
            next == null -> truncate(songInfo)
            next.isGap -> truncate(songInfo)
            else -> truncate(next.text)
        }
        return CarDisplay(title, subtitle)
    }

    private fun songInfo(title: String, artist: String): String =
        if (artist.isBlank()) title else "$title · $artist"

    /**
     * Truncates at a word boundary where one is close to the limit, so the car
     * shows "Is this the real life, is this just…" rather than cutting
     * mid-word.
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
