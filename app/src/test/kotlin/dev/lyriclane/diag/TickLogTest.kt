package dev.lyriclane.diag

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

class TickLogTest {

    @Before
    fun reset() {
        TickLog.clear()
    }

    private fun entry(sequence: Long, elapsed: Long) = TickLog.Entry(
        sequence = sequence,
        elapsedRealtimeMs = elapsed,
        wallClockMs = 1_700_000_000_000L + elapsed,
        title = "Tick $sequence",
    )

    @Test
    fun `newest entry is first`() {
        TickLog.record(entry(1, 0))
        TickLog.record(entry(2, 2_000))

        assertThat(TickLog.entries.value.map { it.sequence }).containsExactly(2L, 1L).inOrder()
    }

    @Test
    fun `measured interval is null below two samples`() {
        assertThat(TickLog.measuredIntervalMs()).isNull()
        TickLog.record(entry(1, 0))
        assertThat(TickLog.measuredIntervalMs()).isNull()
    }

    @Test
    fun `measured interval averages the gaps`() {
        // Pushes at 0, 2000, 4000, 6000 ms -> three gaps of 2000 ms.
        listOf(0L, 2_000L, 4_000L, 6_000L).forEachIndexed { i, t ->
            TickLog.record(entry(i + 1L, t))
        }

        assertThat(TickLog.measuredIntervalMs()).isEqualTo(2_000L)
    }

    @Test
    fun `measured interval reflects an uneven trail`() {
        // A head unit that coalesces pushes shows up as a longer mean gap than
        // the requested rate; this is the signal Phase 0 is looking for.
        listOf(0L, 250L, 1_900L, 2_150L).forEachIndexed { i, t ->
            TickLog.record(entry(i + 1L, t))
        }

        assertThat(TickLog.measuredIntervalMs()).isEqualTo(716L)
    }

    @Test
    fun `log is bounded so a fast rate cannot grow without limit`() {
        repeat(500) { TickLog.record(entry(it.toLong(), it * 250L)) }

        assertThat(TickLog.entries.value).hasSize(200)
        assertThat(TickLog.entries.value.first().sequence).isEqualTo(499L)
    }
}
