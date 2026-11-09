package dev.lyriclane.media

import com.google.common.truth.Truth.assertThat
import dev.lyriclane.lyrics.LyricLine
import dev.lyriclane.lyrics.LyricSyncEngine
import dev.lyriclane.lyrics.LyricsResult
import dev.lyriclane.spotify.SpotifyPlayback
import dev.lyriclane.spotify.SpotifyState
import dev.lyriclane.spotify.SpotifyTrack
import org.junit.Test

/**
 * Every state the car can be in has to say something sensible. A driver
 * glancing at the screen must never see the previous song's lyric, or a blank
 * card with no explanation.
 */
class CarDisplayFormatterTest {

    private val track = SpotifyTrack(
        title = "Bohemian Rhapsody",
        artist = "Queen",
        album = "A Night at the Opera",
        durationMs = 354_000,
        artwork = null,
        isAdvertisement = false,
    )

    private val playing = SpotifyState(
        permissionGranted = true,
        spotifyRunning = true,
        track = track,
        playback = SpotifyPlayback(
            isPlaying = true,
            positionMs = 0,
            speed = 1f,
            updatedAtElapsedRealtime = 0,
        ),
    )

    private fun frame(
        current: String?,
        next: String?,
        afterNext: String? = null,
        previous: String? = null,
        inGap: Boolean = false,
    ) = LyricSyncEngine.Frame(
        lineIndex = if (current == null) -1 else 3,
        current = current?.let { LyricLine(1_000, it) },
        next = next?.let { LyricLine(2_000, it) },
        afterNext = afterNext?.let { LyricLine(3_000, it) },
        previous = previous?.let { LyricLine(500, it) },
        lineCount = 10,
        inGap = inGap,
    )

    private val synced = LyricsResult.Synced(emptyList(), fromCache = false)

    @Test
    fun `missing permission is stated plainly`() {
        val display = CarDisplayFormatter.format(
            spotify = SpotifyState(permissionGranted = false),
            lyrics = LyricsResult.Idle,
            frame = frame(null, null),
        )

        assertThat(display.primary).contains("Notification access")
        assertThat(display.secondary).contains("phone")
    }

    @Test
    fun `nothing playing is stated plainly`() {
        val display = CarDisplayFormatter.format(
            spotify = SpotifyState(permissionGranted = true, spotifyRunning = false),
            lyrics = LyricsResult.Idle,
            frame = frame(null, null),
        )

        assertThat(display.primary).contains("Nothing playing")
    }

    @Test
    fun `a synced line goes in the title and the next line underneath`() {
        val display = CarDisplayFormatter.format(
            spotify = playing,
            lyrics = synced,
            frame = frame("the current line", "the line after it"),
        )

        assertThat(display.primary).isEqualTo("the current line")
        assertThat(display.secondary).isEqualTo("the line after it")
    }

    @Test
    fun `song info can replace the next line`() {
        val display = CarDisplayFormatter.format(
            spotify = playing,
            lyrics = synced,
            frame = frame("the current line", "the line after it"),
            secondaryLine = SecondaryLine.SONG_INFO,
        )

        assertThat(display.primary).isEqualTo("the current line")
        assertThat(display.secondary).contains("Bohemian Rhapsody")
        assertThat(display.secondary).contains("Queen")
    }

    @Test
    fun `before the first line the big text previews what is coming`() {
        val display = CarDisplayFormatter.format(playing, synced, frame(null, "first line"))

        // Not a bare rest symbol: a driver glancing up gets a head start.
        assertThat(display.primary).isEqualTo("first line")
    }

    @Test
    fun `during an instrumental break the big text previews the next line`() {
        val display = CarDisplayFormatter.format(
            playing,
            synced,
            frame(
                current = "a line that has finished",
                next = "what comes next",
                afterNext = "and then this",
                inGap = true,
            ),
        )

        assertThat(display.primary).isEqualTo("what comes next")
        assertThat(display.secondary).isEqualTo("and then this")
    }

    @Test
    fun `a blank gap marker is skipped rather than shown`() {
        val display = CarDisplayFormatter.format(playing, synced, frame("", "next"))

        assertThat(display.primary).isEqualTo("next")
    }

    @Test
    fun `the smaller line can show the previous line instead`() {
        val display = CarDisplayFormatter.format(
            spotify = playing,
            lyrics = synced,
            frame = frame("current", "next", previous = "what came before"),
            secondaryLine = SecondaryLine.PREVIOUS_LINE,
        )

        assertThat(display.primary).isEqualTo("current")
        assertThat(display.secondary).isEqualTo("what came before")
    }

    @Test
    fun `packing two lines puts both in the big slot`() {
        val display = CarDisplayFormatter.format(
            spotify = playing,
            lyrics = synced,
            frame = frame("current", "next"),
            packTwoLines = true,
        )

        assertThat(display.primary).isEqualTo("current\nnext")
    }

    @Test
    fun `a blank next line falls back to song info`() {
        val display = CarDisplayFormatter.format(playing, synced, frame("a line", ""))

        assertThat(display.secondary).contains("Bohemian Rhapsody")
    }

    @Test
    fun `the last line of a song falls back to song info`() {
        val display = CarDisplayFormatter.format(playing, synced, frame("final line", null))

        assertThat(display.primary).isEqualTo("final line")
        assertThat(display.secondary).contains("Bohemian Rhapsody")
    }

    @Test
    fun `each lyric state has its own message`() {
        val f = frame(null, null)

        assertThat(CarDisplayFormatter.format(playing, LyricsResult.Loading, f).secondary)
            .contains("Finding lyrics")
        assertThat(CarDisplayFormatter.format(playing, LyricsResult.NotFound, f).secondary)
            .contains("No lyrics found")
        assertThat(CarDisplayFormatter.format(playing, LyricsResult.Error("x"), f).secondary)
            .contains("offline")
        assertThat(CarDisplayFormatter.format(playing, LyricsResult.Instrumental, f).primary)
            .contains("Instrumental")
        assertThat(
            CarDisplayFormatter.format(playing, LyricsResult.PlainOnly("words", false), f).primary
        ).isEqualTo("Lyrics not synced")
    }

    @Test
    fun `an ad says so rather than showing a stale lyric`() {
        val adState = playing.copy(track = track.copy(isAdvertisement = true))

        val display = CarDisplayFormatter.format(adState, synced, frame("old line", "older"))

        assertThat(display.primary).isEqualTo("Advertisement")
        assertThat(display.primary).doesNotContain("old line")
    }

    @Test
    fun `lyrics off mirrors the song instead`() {
        val display = CarDisplayFormatter.format(
            spotify = playing,
            lyrics = synced,
            frame = frame("a lyric", "next"),
            lyricsEnabled = false,
        )

        assertThat(display.primary).isEqualTo("Bohemian Rhapsody")
        assertThat(display.secondary).isEqualTo("Queen")
    }

    @Test
    fun `long lines are truncated at a word boundary`() {
        val long = "a line of placeholder text long enough that it has to be cut short"

        val truncated = CarDisplayFormatter.truncate(long, maxChars = 30)

        assertThat(truncated.length).isAtMost(30)
        assertThat(truncated).endsWith("…")
        // Cut between words, not through one.
        assertThat(long).contains(truncated.removeSuffix("…").trim())
    }

    @Test
    fun `short lines are left alone`() {
        assertThat(CarDisplayFormatter.truncate("short", maxChars = 30)).isEqualTo("short")
    }

    @Test
    fun `a single over-long word is cut rather than dropped`() {
        val truncated = CarDisplayFormatter.truncate("supercalifragilisticexpialidocious", maxChars = 10)

        assertThat(truncated.length).isAtMost(10)
        assertThat(truncated).endsWith("…")
    }

    @Test
    fun `an artist-less track still produces a subtitle`() {
        val state = playing.copy(track = track.copy(artist = ""))

        val display = CarDisplayFormatter.format(state, synced, frame("a line", null))

        assertThat(display.secondary).isEqualTo("Bohemian Rhapsody")
    }
}
