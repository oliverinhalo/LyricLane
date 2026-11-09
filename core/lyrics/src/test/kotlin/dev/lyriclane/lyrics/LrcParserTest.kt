package dev.lyriclane.lyrics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LrcParserTest {

    @Test
    fun `parses centiseconds`() {
        val parsed = LrcParser.parse("[00:12.34] Hello there")

        assertThat(parsed.lines).containsExactly(LyricLine(12_340, "Hello there"))
    }

    @Test
    fun `parses milliseconds and bare seconds`() {
        val parsed = LrcParser.parse(
            """
            [00:01.5] half
            [00:02] whole
            [00:03.456] precise
            """.trimIndent()
        )

        assertThat(parsed.lines.map { it.timeMs }).containsExactly(1_500L, 2_000L, 3_456L).inOrder()
    }

    @Test
    fun `minutes may exceed 59 for long tracks`() {
        val parsed = LrcParser.parse("[75:30.00] deep in a long mix")

        assertThat(parsed.lines.single().timeMs).isEqualTo(75 * 60_000L + 30_000L)
    }

    @Test
    fun `a repeated chorus with several timestamps becomes several lines`() {
        val parsed = LrcParser.parse("[00:10.00][01:20.50][02:30.00] Chorus line")

        assertThat(parsed.lines).containsExactly(
            LyricLine(10_000, "Chorus line"),
            LyricLine(80_500, "Chorus line"),
            LyricLine(150_000, "Chorus line"),
        ).inOrder()
    }

    @Test
    fun `id tags are skipped`() {
        val parsed = LrcParser.parse(
            """
            [ar:Queen]
            [ti:Bohemian Rhapsody]
            [al:A Night at the Opera]
            [by:someone]
            [length:05:55]
            [00:00.15] Is this the real life
            """.trimIndent()
        )

        assertThat(parsed.lines).containsExactly(LyricLine(150, "Is this the real life"))
    }

    @Test
    fun `positive offset moves lyrics earlier`() {
        val parsed = LrcParser.parse(
            """
            [offset:+500]
            [00:10.00] line
            """.trimIndent()
        )

        assertThat(parsed.offsetMs).isEqualTo(500)
        assertThat(parsed.lines.single().timeMs).isEqualTo(9_500)
    }

    @Test
    fun `negative offset moves lyrics later`() {
        val parsed = LrcParser.parse(
            """
            [offset:-750]
            [00:10.00] line
            """.trimIndent()
        )

        assertThat(parsed.lines.single().timeMs).isEqualTo(10_750)
    }

    @Test
    fun `offset cannot push a line before zero`() {
        val parsed = LrcParser.parse(
            """
            [offset:+5000]
            [00:01.00] line
            """.trimIndent()
        )

        assertThat(parsed.lines.single().timeMs).isEqualTo(0)
    }

    @Test
    fun `blank timed lines are kept as a single gap`() {
        val parsed = LrcParser.parse(
            """
            [00:10.00] before
            [00:12.00]
            [00:14.00]
            [00:16.00]
            [00:20.00] after
            """.trimIndent()
        )

        assertThat(parsed.lines.map { it.text }).containsExactly("before", "", "after").inOrder()
        assertThat(parsed.lines[1].isGap).isTrue()
    }

    @Test
    fun `lines are sorted even when the file is not`() {
        val parsed = LrcParser.parse(
            """
            [00:30.00] third
            [00:10.00] first
            [00:20.00] second
            """.trimIndent()
        )

        assertThat(parsed.lines.map { it.text }).containsExactly("first", "second", "third").inOrder()
    }

    @Test
    fun `untimed junk is ignored`() {
        val parsed = LrcParser.parse(
            """
            this line has no timestamp
            [00:05.00] real line

            """.trimIndent()
        )

        assertThat(parsed.lines).containsExactly(LyricLine(5_000, "real line"))
    }

    @Test
    fun `hasTimestamps distinguishes synced from plain lyrics`() {
        assertThat(LrcParser.hasTimestamps("[00:01.00] words")).isTrue()
        assertThat(LrcParser.hasTimestamps("just words\nmore words")).isFalse()
        assertThat(LrcParser.hasTimestamps("")).isFalse()
        assertThat(LrcParser.hasTimestamps(null)).isFalse()
    }

    @Test
    fun `real lrclib payload parses`() {
        // Trimmed from the live /api/get response for Bohemian Rhapsody.
        val lrc = """
            [00:00.15] Is this the real life? Is this just fantasy?
            [00:07.13] Caught in a landslide, no escape from reality
            [00:14.77] Open your eyes, look up to the skies and see
            [00:25.37] I'm just a poor boy, I need no sympathy
        """.trimIndent()

        val parsed = LrcParser.parse(lrc)

        assertThat(parsed.lines).hasSize(4)
        assertThat(parsed.lines.first().timeMs).isEqualTo(150)
        assertThat(parsed.lines.last().text).isEqualTo("I'm just a poor boy, I need no sympathy")
    }
}
