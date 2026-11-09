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

    private fun frame(current: String?, next: String?) = LyricSyncEngine.Frame(
        lineIndex = if (current == null) -1 else 3,
        current = current?.let { LyricLine(1_000, it) },
        next = next?.let { LyricLine(2_000, it) },
        lineCount = 10,
    )

    private val synced = LyricsResult.Synced(emptyList(), fromCache = false)

    @Test
    fun `missing permission is stated plainly`() {
        val display = CarDisplayFormatter.format(
            spotify = SpotifyState(permissionGranted = false),
            lyrics = LyricsResult.Idle,
            frame = frame(null, null),
        )

        assertThat(display.title).contains("Notification access")
        assertThat(display.subtitle).contains("phone")
    }

    @Test
    fun `nothing playing is stated plainly`() {
        val display = CarDisplayFormatter.format(
            spotify = SpotifyState(permissionGranted = true, spotifyRunning = false),
            lyrics = LyricsResult.Idle,
            frame = frame(null, null),
        )

        assertThat(display.title).contains("Nothing playing")
    }

    @Test
    fun `a synced line goes in the title and the next line underneath`() {
        val display = CarDisplayFormatter.format(
            spotify = playing,
            lyrics = synced,
            frame = frame("Is this the real life?", "Is this just fantasy?"),
        )

        assertThat(display.title).isEqualTo("Is this the real life?")
        assertThat(display.subtitle).isEqualTo("Is this just fantasy?")
    }

    @Test
    fun `song info can replace the next line`() {
        val display = CarDisplayFormatter.format(
            spotify = playing,
            lyrics = synced,
            frame = frame("Is this the real life?", "Is this just fantasy?"),
            subtitleMode = SubtitleMode.SONG_INFO,
        )

        assertThat(display.title).isEqualTo("Is this the real life?")
        assertThat(display.subtitle).contains("Bohemian Rhapsody")
        assertThat(display.subtitle).contains("Queen")
    }

    @Test
    fun `before the first line, and in a gap, a rest is shown`() {
        val beforeStart = CarDisplayFormatter.format(playing, synced, frame(null, "first line"))
        assertThat(beforeStart.title).isEqualTo("♪")

        val inGap = CarDisplayFormatter.format(playing, synced, frame("", "next"))
        assertThat(inGap.title).isEqualTo("♪")
    }

    @Test
    fun `a gap as the next line falls back to song info`() {
        val display = CarDisplayFormatter.format(playing, synced, frame("a line", ""))

        assertThat(display.subtitle).contains("Bohemian Rhapsody")
    }

    @Test
    fun `the last line of a song falls back to song info`() {
        val display = CarDisplayFormatter.format(playing, synced, frame("final line", null))

        assertThat(display.title).isEqualTo("final line")
        assertThat(display.subtitle).contains("Bohemian Rhapsody")
    }

    @Test
    fun `each lyric state has its own message`() {
        val f = frame(null, null)

        assertThat(CarDisplayFormatter.format(playing, LyricsResult.Loading, f).subtitle)
            .contains("Finding lyrics")
        assertThat(CarDisplayFormatter.format(playing, LyricsResult.NotFound, f).subtitle)
            .contains("No lyrics found")
        assertThat(CarDisplayFormatter.format(playing, LyricsResult.Error("x"), f).subtitle)
            .contains("offline")
        assertThat(CarDisplayFormatter.format(playing, LyricsResult.Instrumental, f).title)
            .contains("Instrumental")
        assertThat(
            CarDisplayFormatter.format(playing, LyricsResult.PlainOnly("words", false), f).title
        ).isEqualTo("Lyrics not synced")
    }

    @Test
    fun `an ad says so rather than showing a stale lyric`() {
        val adState = playing.copy(track = track.copy(isAdvertisement = true))

        val display = CarDisplayFormatter.format(adState, synced, frame("old line", "older"))

        assertThat(display.title).isEqualTo("Advertisement")
        assertThat(display.title).doesNotContain("old line")
    }

    @Test
    fun `lyrics off mirrors the song instead`() {
        val display = CarDisplayFormatter.format(
            spotify = playing,
            lyrics = synced,
            frame = frame("a lyric", "next"),
            lyricsEnabled = false,
        )

        assertThat(display.title).isEqualTo("Bohemian Rhapsody")
        assertThat(display.subtitle).isEqualTo("Queen")
    }

    @Test
    fun `long lines are truncated at a word boundary`() {
        val long = "Is this the real life, is this just fantasy, caught in a landslide forever"

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

        assertThat(display.subtitle).isEqualTo("Bohemian Rhapsody")
    }
}
