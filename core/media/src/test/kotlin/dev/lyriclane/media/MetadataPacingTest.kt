package dev.lyriclane.media

import com.google.common.truth.Truth.assertThat
import dev.lyriclane.media.MetadataPacing.pace
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MetadataPacingTest {

    @Test
    fun `the first value is emitted immediately`() = runTest {
        // Regression: the previous implementation seeded "last emitted" with
        // Long.MIN_VALUE, so `now - last` overflowed and the first lyric of
        // every song waited effectively forever.
        val start = testScheduler.currentTime

        val result = flow { emit("first") }
            .pace(minIntervalMs = 500) { testScheduler.currentTime }
            .toList()

        assertThat(result).containsExactly("first")
        assertThat(testScheduler.currentTime - start).isEqualTo(0)
    }

    @Test
    fun `a burst collapses to the latest value, never a stale one`() = runTest {
        val source = flow {
            emit("line 1")
            emit("line 2")
            emit("line 3")
        }

        val result = source.pace(minIntervalMs = 500) { testScheduler.currentTime }.toList()

        // The first goes out at once; by the time the hold expires, lines 2 and
        // 3 have both arrived and only the current one is worth showing.
        assertThat(result.first()).isEqualTo("line 1")
        assertThat(result.last()).isEqualTo("line 3")
        assertThat(result).doesNotContain("line 2")
    }

    @Test
    fun `values spaced wider than the hold all get through`() = runTest {
        val source = flow {
            emit("a")
            delay(1_000)
            emit("b")
            delay(1_000)
            emit("c")
        }

        val result = source.pace(minIntervalMs = 500) { testScheduler.currentTime }.toList()

        assertThat(result).containsExactly("a", "b", "c").inOrder()
    }

    @Test
    fun `a zero hold passes everything straight through`() = runTest {
        val source = flow {
            emit(1)
            delay(1)
            emit(2)
            delay(1)
            emit(3)
        }

        val result = source.pace(minIntervalMs = 0) { testScheduler.currentTime }.toList()

        assertThat(result).containsExactly(1, 2, 3).inOrder()
    }
}
