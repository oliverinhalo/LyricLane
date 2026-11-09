package dev.lyriclane.diag

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TickerRateTest {

    @Test
    fun `next wraps around so the car button can cycle forever`() {
        val visited = mutableListOf<TickerRate>()
        var rate = TickerRate.DEFAULT
        repeat(TickerRate.entries.size) {
            visited += rate
            rate = rate.next()
        }

        assertThat(visited).containsExactlyElementsIn(TickerRate.entries)
        assertThat(rate).isEqualTo(TickerRate.DEFAULT)
    }

    @Test
    fun `rates are ordered from slowest to fastest`() {
        val intervals = TickerRate.entries.map { it.intervalMs }
        assertThat(intervals).isInOrder(Comparator.reverseOrder<Long>())
    }

    @Test
    fun `default is the two second rate the build plan calls for`() {
        assertThat(TickerRate.DEFAULT.intervalMs).isEqualTo(2_000L)
    }
}
