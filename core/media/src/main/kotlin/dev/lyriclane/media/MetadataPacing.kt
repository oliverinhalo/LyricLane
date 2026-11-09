package dev.lyriclane.media

import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow

object MetadataPacing {

    /**
     * Default floor between metadata pushes. Fast rap verses can change line
     * several times a second; a head unit that coalesces those would show a
     * flicker of half-rendered lines, and we would be spending Binder
     * transactions to no effect.
     */
    const val DEFAULT_MIN_INTERVAL_MS = 500L

    /**
     * Rate-limits a stream of displays without ever showing a stale one.
     *
     * [conflate] is the important part: while we are waiting out the minimum
     * interval, newer values *replace* the pending one instead of queueing. So
     * if three lyric lines pass in 300 ms, the car is shown the third — never
     * the first, late.
     */
    fun <T> Flow<T>.pace(
        minIntervalMs: Long,
        nowMs: () -> Long = SystemClock::elapsedRealtime,
    ): Flow<T> = flow {
        // Null rather than a sentinel: `now - Long.MIN_VALUE` overflows, which
        // would turn the very first push into an effectively infinite wait.
        var lastEmitMs: Long? = null
        conflate().collect { value ->
            val previous = lastEmitMs
            if (previous != null) {
                val waitMs = minIntervalMs - (nowMs() - previous)
                if (waitMs > 0) delay(waitMs)
            }
            lastEmitMs = nowMs()
            emit(value)
        }
    }
}
