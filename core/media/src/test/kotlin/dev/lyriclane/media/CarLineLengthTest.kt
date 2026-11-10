package dev.lyriclane.media

import com.google.common.truth.Truth.assertThat
import dev.lyriclane.lyrics.LyricLine
import dev.lyriclane.lyrics.LyricSyncEngine
import dev.lyriclane.lyrics.LyricTimeline
import dev.lyriclane.lyrics.LyricsResult
import dev.lyriclane.spotify.SpotifyPlayback
import dev.lyriclane.spotify.SpotifyState
import dev.lyriclane.spotify.SpotifyTrack
import org.junit.Test

/**
 * Line length drives two things at once: where the timeline splits a long line,
 * and where the formatter truncates whatever survives. They have to agree, or
 * the car gets a line the head unit then wraps and clips.
 */
class CarLineLengthTest {

    private val longLine =
        "a line of placeholder words that runs on well past the width of a dashboard card"

    private val track = SpotifyTrack(
        title = "A Song",
        artist = "An Artist",
        album = null,
        durationMs = 200_000,
        artwork = null,
        isAdvertisement = false,
    )

    private val playing = SpotifyState(
        permissionGranted = true,
        spotifyRunning = true,
        track = track,
        playback = SpotifyPlayback(true, 0, 1f, 0),
    )

    @Test
    fun `lengths are ordered and the default fits a dashboard card`() {
        assertThat(CarLineLength.entries.map { it.maxChars }).isInOrder()
        // Wide enough to be readable, narrow enough not to wrap on the 1/3 card.
        assertThat(CarLineLength.DEFAULT.maxChars).isAtMost(40)
        assertThat(CarLineLength.DEFAULT.maxChars).isAtLeast(30)
    }

    @Test
    fun `cycling the length comes back round`() {
        var length = CarLineLength.DEFAULT
        repeat(CarLineLength.entries.size) { length = length.next() }

        assertThat(length).isEqualTo(CarLineLength.DEFAULT)
    }

    @Test
    fun `a shorter length splits a line into more pieces`() {
        val lines = listOf(LyricLine(0, longLine), LyricLine(30_000, "next"))

        val short = LyricTimeline.prepare(lines, CarLineLength.SHORT.maxChars, 60_000)
        val full = LyricTimeline.prepare(lines, CarLineLength.FULL.maxChars, 60_000)

        assertThat(short.size).isGreaterThan(full.size)
        // Splitting loses nothing: the pieces reassemble into the line.
        assertThat(short.filter { it.text != "next" }.joinToString(" ") { it.text })
            .isEqualTo(longLine)
    }

    @Test
    fun `every split piece fits the chosen length`() {
        CarLineLength.entries.forEach { length ->
            val prepared = LyricTimeline.prepare(
                lines = listOf(LyricLine(0, longLine), LyricLine(60_000, "next")),
                maxChars = length.maxChars,
                trackDurationMs = 120_000,
            )

            prepared.forEach { line ->
                assertThat(line.text.length).isAtMost(length.maxChars)
            }
        }
    }

    @Test
    fun `the formatter never hands the car more than the chosen length`() {
        CarLineLength.entries.forEach { length ->
            val display = CarDisplayFormatter.format(
                spotify = playing,
                lyrics = LyricsResult.Synced(emptyList(), fromCache = false),
                frame = LyricSyncEngine.Frame(
                    lineIndex = 0,
                    current = LyricLine(0, longLine),
                    next = LyricLine(1, longLine),
                    lineCount = 2,
                ),
                maxChars = length.maxChars,
            )

            assertThat(display.primary.length).isAtMost(length.maxChars)
            assertThat(display.secondary.length).isAtMost(length.maxChars)
        }
    }

    @Test
    fun `a split timeline stays in step with the index the engine reports`() {
        // The phone renders the prepared timeline, not the raw lyrics, because
        // the frame's index counts split pieces. This pins that the index is
        // meaningful against the prepared list at every position.
        val prepared = LyricTimeline.prepare(
            lines = listOf(
                LyricLine(0, longLine),
                LyricLine(20_000, "a short one"),
                LyricLine(30_000, longLine),
            ),
            maxChars = CarLineLength.SHORT.maxChars,
            trackDurationMs = 60_000,
        )

        assertThat(prepared.size).isGreaterThan(3)
        prepared.forEachIndexed { index, line ->
            assertThat(LyricTimeline.indexAt(prepared, line.timeMs)).isEqualTo(index)
        }
    }

    @Test
    fun `short lines are left alone at every length`() {
        val lines = listOf(LyricLine(0, "short"), LyricLine(5_000, "also short"))

        CarLineLength.entries.forEach { length ->
            assertThat(LyricTimeline.prepare(lines, length.maxChars, 10_000)).isEqualTo(lines)
        }
    }
}
