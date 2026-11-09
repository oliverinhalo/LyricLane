package dev.lyriclane.lyrics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Splitting a line too long for a car screen.
 *
 * Without word timings the pieces can only share the window evenly, which
 * drifts whenever the singer does not. With them, each piece appears on the
 * exact beat its first word is sung — the closest thing to the per-word sync a
 * paid lyrics service shows, built from data LRCLIB already hands us.
 */
class WordTimedSplitTest {

    private fun words(vararg pairs: Pair<Long, String>) =
        pairs.map { (time, text) -> LyricWord(time, text) }

    private val unevenlySung = LyricLine(
        timeMs = 1_000,
        text = "one two three four five six",
        endMs = 9_000,
        // The first three words come quickly, then a long held note.
        words = words(
            1_000L to "one",
            1_100L to "two",
            1_200L to "three",
            7_000L to "four",
            7_200L to "five",
            7_400L to "six",
        ),
    )

    @Test
    fun `pieces start on the word that begins them`() {
        val pieces = LyricTimeline.splitOnWordTimes(unevenlySung, maxChars = 14)

        assertThat(pieces).isNotNull()
        assertThat(pieces!!.first().timeMs).isEqualTo(1_000)
        // Not the midpoint of the window: the real moment "four" is sung.
        assertThat(pieces[1].timeMs).isEqualTo(7_000)
    }

    @Test
    fun `an even split would have been wrong by seconds`() {
        val evenly = LyricTimeline.prepare(
            lines = listOf(unevenlySung.copy(words = emptyList()), LyricLine(9_000, "next")),
            maxChars = 14,
            trackDurationMs = 60_000,
        )
        val timed = LyricTimeline.prepare(
            lines = listOf(unevenlySung, LyricLine(9_000, "next")),
            maxChars = 14,
            trackDurationMs = 60_000,
        )

        val evenSecondPiece = evenly[1].timeMs
        val timedSecondPiece = timed[1].timeMs

        assertThat(timedSecondPiece).isEqualTo(7_000)
        // The naive split lands in the middle of the held note instead.
        assertThat(evenSecondPiece).isLessThan(6_000)
    }

    @Test
    fun `no piece is longer than the screen allows`() {
        val pieces = LyricTimeline.splitOnWordTimes(unevenlySung, maxChars = 14)!!

        pieces.forEach { assertThat(it.text.length).isAtMost(14) }
    }

    @Test
    fun `pieces reassemble into the original line`() {
        val pieces = LyricTimeline.splitOnWordTimes(unevenlySung, maxChars = 14)!!

        assertThat(pieces.joinToString(" ") { it.text }).isEqualTo(unevenlySung.text)
    }

    @Test
    fun `each piece ends where the next begins`() {
        val pieces = LyricTimeline.splitOnWordTimes(unevenlySung, maxChars = 14)!!

        pieces.zipWithNext().forEach { (a, b) -> assertThat(a.endMs).isEqualTo(b.timeMs) }
        // The last piece inherits the line's own end, so gap detection survives.
        assertThat(pieces.last().endMs).isEqualTo(9_000)
    }

    @Test
    fun `a line without word timings falls back to an even share`() {
        val plain = unevenlySung.copy(words = emptyList())

        assertThat(LyricTimeline.splitOnWordTimes(plain, maxChars = 14)).isNull()
    }

    @Test
    fun `a line that fits is not split`() {
        val short = LyricLine(0, "short", endMs = 1_000, words = words(0L to "short"))

        assertThat(LyricTimeline.splitOnWordTimes(short, maxChars = 40)).isNull()
    }

    @Test
    fun `word timings that run backwards are rejected rather than trusted`() {
        val broken = unevenlySung.copy(
            words = words(
                1_000L to "one",
                1_100L to "two",
                1_200L to "three",
                500L to "four",
                600L to "five",
                700L to "six",
            ),
        )

        assertThat(LyricTimeline.splitOnWordTimes(broken, maxChars = 14)).isNull()
    }

    @Test
    fun `prepare keeps word timings out of short lines`() {
        val lines = listOf(
            LyricLine(0, "fits fine", endMs = 2_000, words = words(0L to "fits", 500L to "fine")),
            LyricLine(3_000, "also fine"),
        )

        val prepared = LyricTimeline.prepare(lines, maxChars = 40, trackDurationMs = 10_000)

        assertThat(prepared).isEqualTo(lines)
    }
}
