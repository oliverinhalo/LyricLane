package dev.lyriclane.media

/**
 * Decides which MediaSession field each half of a [CarDisplay] ends up in.
 *
 * Kept apart from the service so the rule is testable: it has to satisfy two
 * screens at once that read different fields, and getting it wrong is invisible
 * until you are in the car.
 */
object CarSlotMapping {

    /** The two fields as they will be handed to the session. */
    data class Fields(val title: String, val subtitle: String)

    /**
     * [lyricInTitle] mirrors the lyric into the title *as well as* leaving it in
     * whichever slot the head unit draws large.
     *
     * Instrument clusters and head-up displays generally read the title, while
     * Android Auto's own card draws the subtitle large. Moving the lyric to
     * satisfy one costs the other, so when both matter the lyric simply goes in
     * both places — the card shows it twice, once large and once small, which is
     * a small price for having it in the driver's line of sight.
     */
    fun map(
        display: CarDisplay,
        slots: CarTextSlots,
        lyricInTitle: Boolean = false,
    ): Fields = when (slots) {
        CarTextSlots.SUBTITLE_IS_BIG -> Fields(
            // The big slot is the subtitle here, so the title is free to carry
            // either the supporting line or a copy of the lyric.
            title = if (lyricInTitle) display.primary else display.secondary,
            subtitle = display.primary,
        )

        // The lyric is already in the title, so the toggle has nothing to add.
        CarTextSlots.TITLE_IS_BIG -> Fields(
            title = display.primary,
            subtitle = display.secondary,
        )
    }
}
