package dev.lyriclane.lyrics

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The engine is driven entirely by a clock, so these tests run on virtual time:
 * the fake clock reads the test scheduler, which is also what `delay` obeys.
 * No test here sleeps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LyricSyncEngineTest {

    private val lines = listOf(
        LyricLine(0, "zero"),
        LyricLine(1_000, "one"),
        LyricLine(2_000, "two"),
        LyricLine(10_000, "ten"),
    )

    private fun TestScope.engine(): LyricSyncEngine =
        LyricSyncEngine(this, TimeSource { testScheduler.currentTime })

    private fun TestScope.playing(positionMs: Long, speed: Float = 1f) = PlaybackSnapshot(
        isPlaying = true,
        positionMs = positionMs,
        speed = speed,
        atElapsedRealtimeMs = testScheduler.currentTime,
    )

    @Test
    fun `no timeline means no line`() = runTest {
        val engine = engine()
        engine.setPlayback(playing(0))
        runCurrent()

        assertThat(engine.frame.value.lineIndex).isEqualTo(LyricSyncEngine.NO_LINE)
        assertThat(engine.frame.value.hasLyrics).isFalse()
        engine.release()
    }

    @Test
    fun `emits the line for the starting position`() = runTest {
        val engine = engine()
        engine.setTimeline(lines)
        engine.setPlayback(playing(1_500))
        runCurrent()

        assertThat(engine.frame.value.current?.text).isEqualTo("one")
        assertThat(engine.frame.value.next?.text).isEqualTo("two")
        engine.release()
    }

    @Test
    fun `advances exactly when the next line is due`() = runTest {
        val engine = engine()
        engine.setTimeline(lines)
        engine.setPlayback(playing(0))
        runCurrent()
        assertThat(engine.frame.value.current?.text).isEqualTo("zero")

        advanceTimeBy(999)
        runCurrent()
        assertThat(engine.frame.value.current?.text).isEqualTo("zero")

        advanceTimeBy(2)
        runCurrent()
        assertThat(engine.frame.value.current?.text).isEqualTo("one")
        engine.release()
    }

    @Test
    fun `walks the whole timeline without polling`() = runTest {
        val engine = engine()
        engine.setTimeline(lines)
        engine.setPlayback(playing(0))
        runCurrent()

        advanceTimeBy(10_001)
        runCurrent()

        assertThat(engine.frame.value.current?.text).isEqualTo("ten")
        assertThat(engine.frame.value.next).isNull()
        engine.release()
    }

    @Test
    fun `a pause freezes the line and schedules nothing`() = runTest {
        val engine = engine()
        engine.setTimeline(lines)
        engine.setPlayback(playing(1_200))
        runCurrent()
        assertThat(engine.frame.value.current?.text).isEqualTo("one")

        engine.setPlayback(
            PlaybackSnapshot(
                isPlaying = false,
                positionMs = 1_200,
                speed = 1f,
                atElapsedRealtimeMs = testScheduler.currentTime,
            )
        )
        runCurrent()

        // An hour of wall clock passes; a paused engine must not move on.
        advanceTimeBy(3_600_000)
        runCurrent()
        assertThat(engine.frame.value.current?.text).isEqualTo("one")
        engine.release()
    }

    @Test
    fun `a seek resyncs immediately`() = runTest {
        val engine = engine()
        engine.setTimeline(lines)
        engine.setPlayback(playing(0))
        runCurrent()

        engine.setPlayback(playing(9_500))
        runCurrent()

        assertThat(engine.frame.value.current?.text).isEqualTo("two")
        advanceTimeBy(501)
        runCurrent()
        assertThat(engine.frame.value.current?.text).isEqualTo("ten")
        engine.release()
    }

    @Test
    fun `a backward seek goes back`() = runTest {
        val engine = engine()
        engine.setTimeline(lines)
        engine.setPlayback(playing(9_000))
        runCurrent()
        assertThat(engine.frame.value.current?.text).isEqualTo("two")

        engine.setPlayback(playing(0))
        runCurrent()

        assertThat(engine.frame.value.current?.text).isEqualTo("zero")
        engine.release()
    }

    @Test
    fun `playback speed scales the wait`() = runTest {
        val engine = engine()
        engine.setTimeline(lines)
        engine.setPlayback(playing(0, speed = 2f))
        runCurrent()
        assertThat(engine.frame.value.current?.text).isEqualTo("zero")

        // At double speed the 1 s line arrives after 500 ms of wall clock.
        advanceTimeBy(501)
        runCurrent()
        assertThat(engine.frame.value.current?.text).isEqualTo("one")
        engine.release()
    }

    @Test
    fun `a positive offset shows lyrics sooner`() = runTest {
        val engine = engine()
        engine.setTimeline(lines)
        engine.setPlayback(playing(800))
        runCurrent()
        assertThat(engine.frame.value.current?.text).isEqualTo("zero")

        // +300 ms puts the effective position at 1100 ms, past the 1 s line.
        engine.setOffsetMs(300)
        runCurrent()
        assertThat(engine.frame.value.current?.text).isEqualTo("one")
        engine.release()
    }

    @Test
    fun `a negative offset holds lyrics back`() = runTest {
        val engine = engine()
        engine.setTimeline(lines)
        engine.setPlayback(playing(1_100))
        runCurrent()
        assertThat(engine.frame.value.current?.text).isEqualTo("one")

        engine.setOffsetMs(-300)
        runCurrent()
        assertThat(engine.frame.value.current?.text).isEqualTo("zero")
        engine.release()
    }

    @Test
    fun `before the first line there is no current line`() = runTest {
        val engine = engine()
        engine.setTimeline(listOf(LyricLine(5_000, "first")))
        engine.setPlayback(playing(0))
        runCurrent()

        assertThat(engine.frame.value.current).isNull()
        assertThat(engine.frame.value.next?.text).isEqualTo("first")

        advanceTimeBy(5_001)
        runCurrent()
        assertThat(engine.frame.value.current?.text).isEqualTo("first")
        engine.release()
    }

    @Test
    fun `lines sharing a timestamp do not spin the loop`() = runTest {
        val engine = engine()
        engine.setTimeline(
            listOf(
                LyricLine(0, "a"),
                LyricLine(1_000, "b"),
                LyricLine(1_000, "c"),
                LyricLine(2_000, "d"),
            )
        )
        engine.setPlayback(playing(0))
        runCurrent()

        advanceTimeBy(2_500)
        runCurrent()

        assertThat(engine.frame.value.current?.text).isEqualTo("d")
        engine.release()
    }

    @Test
    fun `changing the timeline mid-song resyncs against it`() = runTest {
        val engine = engine()
        engine.setTimeline(lines)
        engine.setPlayback(playing(1_500))
        runCurrent()
        assertThat(engine.frame.value.current?.text).isEqualTo("one")

        engine.setTimeline(listOf(LyricLine(0, "replacement")))
        runCurrent()

        assertThat(engine.frame.value.current?.text).isEqualTo("replacement")
        engine.release()
    }

    @Test
    fun `clearing the timeline clears the line`() = runTest {
        val engine = engine()
        engine.setTimeline(lines)
        engine.setPlayback(playing(1_500))
        runCurrent()

        engine.setTimeline(emptyList())
        runCurrent()

        assertThat(engine.frame.value.current).isNull()
        assertThat(engine.frame.value.lineCount).isEqualTo(0)
        engine.release()
    }
}

/**
 * Gap behaviour, which only exists for lyrics that carry line end times — the
 * LRCLIB word-synced documents. Plain LRC cannot know when a line stops.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LyricSyncEngineGapTest {

    private val timed = listOf(
        LyricLine(timeMs = 1_000, text = "first", endMs = 3_000),
        LyricLine(timeMs = 10_000, text = "second", endMs = 12_000),
    )

    private fun TestScope.engine() =
        LyricSyncEngine(this, TimeSource { testScheduler.currentTime })

    private fun TestScope.playing(positionMs: Long) = PlaybackSnapshot(
        isPlaying = true,
        positionMs = positionMs,
        speed = 1f,
        atElapsedRealtimeMs = testScheduler.currentTime,
    )

    @Test
    fun `a line that is still being sung is not a gap`() = runTest {
        val engine = engine()
        engine.setTimeline(timed)
        engine.setPlayback(playing(2_000))
        runCurrent()

        assertThat(engine.frame.value.current?.text).isEqualTo("first")
        assertThat(engine.frame.value.inGap).isFalse()
        engine.release()
    }

    @Test
    fun `the engine wakes when a line ends, not only when the next starts`() = runTest {
        val engine = engine()
        engine.setTimeline(timed)
        engine.setPlayback(playing(2_000))
        runCurrent()
        assertThat(engine.frame.value.inGap).isFalse()

        // The first line ends at 3 s; the next does not start until 10 s.
        advanceTimeBy(1_100)
        runCurrent()

        assertThat(engine.frame.value.inGap).isTrue()
        assertThat(engine.frame.value.current?.text).isEqualTo("first")
        assertThat(engine.frame.value.next?.text).isEqualTo("second")
        engine.release()
    }

    @Test
    fun `the gap clears when the next line starts`() = runTest {
        val engine = engine()
        engine.setTimeline(timed)
        engine.setPlayback(playing(4_000))
        runCurrent()
        assertThat(engine.frame.value.inGap).isTrue()

        advanceTimeBy(6_100)
        runCurrent()

        assertThat(engine.frame.value.current?.text).isEqualTo("second")
        assertThat(engine.frame.value.inGap).isFalse()
        engine.release()
    }

    @Test
    fun `lyrics without end times never report a gap`() = runTest {
        val engine = engine()
        engine.setTimeline(listOf(LyricLine(1_000, "a"), LyricLine(10_000, "b")))
        engine.setPlayback(playing(5_000))
        runCurrent()

        assertThat(engine.frame.value.current?.text).isEqualTo("a")
        assertThat(engine.frame.value.inGap).isFalse()
        engine.release()
    }

    @Test
    fun `next and previous skip blank gap markers`() = runTest {
        val engine = engine()
        engine.setTimeline(
            listOf(
                LyricLine(0, "one"),
                LyricLine(1_000, "two"),
                LyricLine(2_000, ""),
                LyricLine(3_000, "three"),
                LyricLine(4_000, "four"),
            )
        )
        engine.setPlayback(playing(1_500))
        runCurrent()

        val frame = engine.frame.value
        assertThat(frame.current?.text).isEqualTo("two")
        assertThat(frame.next?.text).isEqualTo("three")
        assertThat(frame.afterNext?.text).isEqualTo("four")
        assertThat(frame.previous?.text).isEqualTo("one")
        engine.release()
    }
}
