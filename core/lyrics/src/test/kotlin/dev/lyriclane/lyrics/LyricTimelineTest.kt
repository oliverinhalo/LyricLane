package dev.lyriclane.lyrics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LyricTimelineTest {

    @Test
    fun `indexAt is minus one before the first line`() {
        val lines = listOf(LyricLine(1_000, "a"), LyricLine(2_000, "b"))

        assertThat(LyricTimeline.indexAt(lines, 0)).isEqualTo(-1)
        assertThat(LyricTimeline.indexAt(lines, 999)).isEqualTo(-1)
    }

    @Test
    fun `indexAt is inclusive of the line's own timestamp`() {
        val lines = listOf(LyricLine(1_000, "a"), LyricLine(2_000, "b"))

        assertThat(LyricTimeline.indexAt(lines, 1_000)).isEqualTo(0)
        assertThat(LyricTimeline.indexAt(lines, 1_999)).isEqualTo(0)
        assertThat(LyricTimeline.indexAt(lines, 2_000)).isEqualTo(1)
    }

    @Test
    fun `indexAt holds the last line past the end`() {
        val lines = listOf(LyricLine(1_000, "a"), LyricLine(2_000, "b"))

        assertThat(LyricTimeline.indexAt(lines, 600_000)).isEqualTo(1)
    }

    @Test
    fun `indexAt on an empty timeline`() {
        assertThat(LyricTimeline.indexAt(emptyList(), 1_000)).isEqualTo(-1)
    }

    @Test
    fun `short lines pass through untouched`() {
        val lines = listOf(LyricLine(0, "short"), LyricLine(5_000, "also short"))

        assertThat(LyricTimeline.prepare(lines, maxChars = 40, trackDurationMs = 10_000))
            .isEqualTo(lines)
    }

    @Test
    fun `a long line with room is split into timed chunks`() {
        val long = "one two three four five six seven eight nine ten eleven twelve"
        val lines = listOf(LyricLine(0, long), LyricLine(10_000, "next"))

        val prepared = LyricTimeline.prepare(lines, maxChars = 24, trackDurationMs = 20_000)

        // The chunks share the ten seconds before the next line.
        val chunks = prepared.filter { it.text != "next" }
        assertThat(chunks.size).isAtLeast(2)
        assertThat(chunks.first().timeMs).isEqualTo(0)
        assertThat(chunks.map { it.timeMs }).isInOrder()
        assertThat(chunks.last().timeMs).isLessThan(10_000)
        chunks.forEach { assertThat(it.text.length).isAtMost(24) }
        assertThat(chunks.joinToString(" ") { it.text }).isEqualTo(long)
    }

    @Test
    fun `a long line without room is left whole for the renderer to truncate`() {
        val long = "one two three four five six seven eight nine ten eleven twelve"
        // Only 500 ms before the next line: splitting would be unreadable.
        val lines = listOf(LyricLine(0, long), LyricLine(500, "next"))

        val prepared = LyricTimeline.prepare(lines, maxChars = 24, trackDurationMs = 20_000)

        assertThat(prepared).isEqualTo(lines)
    }

    @Test
    fun `the final line uses the track duration as its window`() {
        val long = "one two three four five six seven eight nine ten eleven twelve"
        val lines = listOf(LyricLine(0, long))

        val prepared = LyricTimeline.prepare(lines, maxChars = 24, trackDurationMs = 30_000)

        assertThat(prepared.size).isAtLeast(2)
    }

    @Test
    fun `gaps are never split`() {
        val lines = listOf(LyricLine(0, ""), LyricLine(10_000, "next"))

        val prepared = LyricTimeline.prepare(lines, maxChars = 4, trackDurationMs = 20_000)

        assertThat(prepared).isEqualTo(lines)
    }

    @Test
    fun `splitAtWords never cuts mid-word`() {
        val chunks = LyricTimeline.splitAtWords("alpha bravo charlie delta echo", 12)

        chunks.forEach { chunk ->
            assertThat(chunk).doesNotContain("  ")
            assertThat(chunk.trim()).isEqualTo(chunk)
        }
        assertThat(chunks.joinToString(" ")).isEqualTo("alpha bravo charlie delta echo")
    }

    @Test
    fun `a single word longer than the limit is emitted whole`() {
        val chunks = LyricTimeline.splitAtWords("supercalifragilisticexpialidocious", 10)

        assertThat(chunks).containsExactly("supercalifragilisticexpialidocious")
    }
}
