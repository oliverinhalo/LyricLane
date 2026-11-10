package dev.lyriclane.media

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Three screens read these two fields and disagree about which one matters:
 * Android Auto's card draws the subtitle large, a head unit that follows the
 * field names draws the title large, and an instrument cluster or head-up
 * display reads the title regardless.
 */
class CarSlotMappingTest {

    private val display = CarDisplay(primary = "the current line", secondary = "the next line")

    @Test
    fun `Android Auto gets the lyric in the slot it draws large`() {
        val fields = CarSlotMapping.map(display, CarTextSlots.SUBTITLE_IS_BIG)

        assertThat(fields.subtitle).isEqualTo("the current line")
        assertThat(fields.title).isEqualTo("the next line")
    }

    @Test
    fun `the other arrangement puts the lyric in the title`() {
        val fields = CarSlotMapping.map(display, CarTextSlots.TITLE_IS_BIG)

        assertThat(fields.title).isEqualTo("the current line")
        assertThat(fields.subtitle).isEqualTo("the next line")
    }

    @Test
    fun `mirroring into the title keeps the card's large line intact`() {
        val fields = CarSlotMapping.map(
            display = display,
            slots = CarTextSlots.SUBTITLE_IS_BIG,
            lyricInTitle = true,
        )

        // The cluster reads the title, the card still draws the subtitle large,
        // and neither loses the lyric to the other.
        assertThat(fields.title).isEqualTo("the current line")
        assertThat(fields.subtitle).isEqualTo("the current line")
    }

    @Test
    fun `mirroring changes nothing when the lyric is already in the title`() {
        val plain = CarSlotMapping.map(display, CarTextSlots.TITLE_IS_BIG)
        val mirrored = CarSlotMapping.map(display, CarTextSlots.TITLE_IS_BIG, lyricInTitle = true)

        assertThat(mirrored).isEqualTo(plain)
    }

    @Test
    fun `every arrangement puts the lyric somewhere`() {
        CarTextSlots.entries.forEach { slots ->
            listOf(false, true).forEach { mirror ->
                val fields = CarSlotMapping.map(display, slots, mirror)
                assertThat(listOf(fields.title, fields.subtitle))
                    .contains("the current line")
            }
        }
    }

    @Test
    fun `the non-lyric states pass through unchanged`() {
        val notice = CarDisplay("Nothing playing on Spotify", "Start a song in Spotify")

        val fields = CarSlotMapping.map(notice, CarTextSlots.SUBTITLE_IS_BIG, lyricInTitle = true)

        // With the toggle on, a notice is doubled rather than losing its
        // explanation — acceptable, and better than a blank line.
        assertThat(fields.subtitle).isEqualTo("Nothing playing on Spotify")
        assertThat(fields.title).isEqualTo("Nothing playing on Spotify")
    }
}
