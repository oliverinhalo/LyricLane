package dev.lyriclane.lyrics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NeteaseCreditsTest {

    @Test
    fun `credit lines are stripped`() {
        // NetEase LRCs open with these, timed at 00:00; left in, they would be
        // the first thing the car shows for every song.
        val lrc = """
            [00:00.00] 作词 : Benj Pasek/Justin Paul
            [00:01.00] 作曲 : Benj Pasek/Justin Paul
            [00:02.00] 编曲 : Someone
            [00:13.99]I close my eyes and I can see
            [00:17.28]The world that's waiting up for me
        """.trimIndent()

        val cleaned = NeteaseSource.stripCredits(lrc)

        assertThat(cleaned).doesNotContain("作词")
        assertThat(cleaned).doesNotContain("编曲")
        assertThat(cleaned).contains("I close my eyes and I can see")
        assertThat(LrcParser.parse(cleaned).lines).hasSize(2)
    }

    @Test
    fun `english credit lines are stripped too`() {
        val lrc = """
            [00:00.00] Produced by : Someone
            [00:01.00] Written by : Someone Else
            [00:10.00] the first real line
        """.trimIndent()

        val lines = LrcParser.parse(NeteaseSource.stripCredits(lrc)).lines

        assertThat(lines.map { it.text }).containsExactly("the first real line")
    }

    @Test
    fun `a lyric that merely contains a colon is kept`() {
        val lrc = """
            [00:10.00] well: here we are
            [00:12.00] a normal line
        """.trimIndent()

        val lines = LrcParser.parse(NeteaseSource.stripCredits(lrc)).lines

        assertThat(lines).hasSize(2)
        assertThat(lines.first().text).isEqualTo("well: here we are")
    }
}
