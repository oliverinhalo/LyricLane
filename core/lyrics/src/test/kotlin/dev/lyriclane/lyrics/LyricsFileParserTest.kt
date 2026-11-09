package dev.lyriclane.lyrics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Shaped exactly like LRCLIB's `/api/get` response, with placeholder words in
 * place of anyone's lyrics: the structure is what this parser exists for.
 */
class LyricsFileParserTest {

    private val document = """
        version: '1.0'
        metadata:
          title: Placeholder Song
          artist: Placeholder Artist
          album: Placeholder Album
          duration_ms: 269000
          instrumental: false
          language: en
        lines:
        - text: first line of the placeholder song
          start_ms: 13960
          end_ms: 16560
          words:
          - text: 'first '
            start_ms: 13960
          - text: 'line '
            start_ms: 14090
          - text: song
            start_ms: 16220
        - text: second line of the placeholder song
          start_ms: 17120
          end_ms: 19690
          words:
          - text: 'second '
            start_ms: 17120
          - text: song
            start_ms: 19350
        - text: third placeholder line
          start_ms: 21020
          end_ms: 22870
          words:
          - text: 'third '
            start_ms: 21020
    """.trimIndent()

    @Test
    fun `reads line starts and ends`() {
        val lines = LyricsFileParser.parse(document)

        assertThat(lines).isNotNull()
        assertThat(lines!!).hasSize(3)
        assertThat(lines[0].text).isEqualTo("first line of the placeholder song")
        assertThat(lines[0].timeMs).isEqualTo(13_960)
        assertThat(lines[0].endMs).isEqualTo(16_560)
        assertThat(lines[2].text).isEqualTo("third placeholder line")
        assertThat(lines[2].endMs).isEqualTo(22_870)
    }

    @Test
    fun `per-word timings are kept on the line`() {
        val lines = LyricsFileParser.parse(document)!!

        assertThat(lines[0].words.map { it.text }).containsExactly("first", "line", "song").inOrder()
        assertThat(lines[0].words.map { it.timeMs })
            .containsExactly(13_960L, 14_090L, 16_220L).inOrder()
        assertThat(lines[2].words.single().text).isEqualTo("third")
    }

    @Test
    fun `word timings do not leak into the line list`() {
        val lines = LyricsFileParser.parse(document)!!

        // Three sung lines, not the dozen words nested under them.
        assertThat(lines).hasSize(3)
        assertThat(lines.map { it.text }).doesNotContain("line ")
        assertThat(lines.map { it.timeMs }).doesNotContain(14_090L)
    }

    @Test
    fun `end times are what make gap detection possible`() {
        val lines = LyricsFileParser.parse(document)!!

        // 16.56 s to 17.12 s is a gap between the first two lines.
        assertThat(lines[0].hasEndedBy(16_000)).isFalse()
        assertThat(lines[0].hasEndedBy(17_000)).isTrue()
    }

    @Test
    fun `single-quoted values are unquoted`() {
        val quoted = """
            lines:
            - text: 'it''s a quoted value'
              start_ms: 1000
              end_ms: 2000
        """.trimIndent()

        val lines = LyricsFileParser.parse(quoted)!!

        assertThat(lines.single().text).isEqualTo("it's a quoted value")
    }

    @Test
    fun `an end before the start is ignored rather than trusted`() {
        val broken = """
            lines:
            - text: a line
              start_ms: 5000
              end_ms: 1000
        """.trimIndent()

        assertThat(LyricsFileParser.parse(broken)!!.single().endMs).isNull()
    }

    @Test
    fun `lines are sorted`() {
        val unsorted = """
            lines:
            - text: second
              start_ms: 2000
            - text: first
              start_ms: 1000
        """.trimIndent()

        assertThat(LyricsFileParser.parse(unsorted)!!.map { it.text })
            .containsExactly("first", "second").inOrder()
    }

    @Test
    fun `anything that is not this format returns null so the LRC is used`() {
        assertThat(LyricsFileParser.parse("")).isNull()
        assertThat(LyricsFileParser.parse("[00:01.00] just an LRC")).isNull()
        assertThat(LyricsFileParser.parse("lines:\n")).isNull()
        assertThat(LyricsFileParser.parse("lines:\n- text:\n  start_ms: 10\n")).isNull()
    }
}
