package dev.lyriclane.lyrics

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Monotonic clock, injected so the engine can be driven by virtual time in tests. */
fun interface TimeSource {
    fun elapsedRealtimeMs(): Long
}

/**
 * Where playback is, as of a moment on [TimeSource]'s clock.
 *
 * Kept separate from the Spotify-specific type so this module stays free of
 * any dependency on how playback is observed.
 */
data class PlaybackSnapshot(
    val isPlaying: Boolean,
    val positionMs: Long,
    val speed: Float,
    val atElapsedRealtimeMs: Long,
) {
    fun positionMsAt(nowMs: Long): Long {
        if (!isPlaying) return positionMs
        val elapsed = nowMs - atElapsedRealtimeMs
        if (elapsed <= 0L) return positionMs
        return positionMs + (elapsed * speed).toLong()
    }
}

/**
 * Decides which lyric line is current, and wakes up exactly when that changes.
 *
 * It does not poll. After emitting a line it sleeps until the timestamp of the
 * *next* line, scaled by playback speed, then recomputes from the clock rather
 * than assuming it woke on time. When playback is paused it emits once and
 * schedules nothing at all, so a paused phone does no work — which is what
 * keeps this off the battery.
 *
 * Any new [setPlayback] — a seek, a pause, a resume, a position jump — cancels
 * the pending wake-up and recomputes, so there is no separate resync path to
 * get wrong.
 */
class LyricSyncEngine(
    private val scope: CoroutineScope,
    private val timeSource: TimeSource,
) {

    data class Frame(
        val lineIndex: Int = NO_LINE,
        val current: LyricLine? = null,
        val next: LyricLine? = null,
        val lineCount: Int = 0,
    ) {
        val hasLyrics: Boolean get() = lineCount > 0
    }

    private val _frame = MutableStateFlow(Frame())
    val frame: StateFlow<Frame> = _frame.asStateFlow()

    private var timeline: List<LyricLine> = emptyList()
    private var playback: PlaybackSnapshot? = null
    private var offsetMs: Long = 0L
    private var job: Job? = null

    fun setTimeline(lines: List<LyricLine>) {
        timeline = lines
        restart()
    }

    /** Positive values show lyrics sooner; see README. */
    fun setOffsetMs(offsetMs: Long) {
        if (this.offsetMs == offsetMs) return
        this.offsetMs = offsetMs
        restart()
    }

    fun setPlayback(snapshot: PlaybackSnapshot?) {
        playback = snapshot
        restart()
    }

    fun release() {
        job?.cancel()
        job = null
    }

    /** Position the engine believes playback is at, for the UI's progress. */
    fun currentPositionMs(): Long =
        playback?.positionMsAt(timeSource.elapsedRealtimeMs()) ?: 0L

    private fun restart() {
        job?.cancel()
        val lines = timeline
        val snapshot = playback
        if (lines.isEmpty() || snapshot == null) {
            _frame.value = Frame(lineCount = lines.size)
            return
        }
        job = scope.launch {
            while (isActive) {
                val now = timeSource.elapsedRealtimeMs()
                val position = snapshot.positionMsAt(now) + offsetMs
                val index = LyricTimeline.indexAt(lines, position)
                _frame.value = Frame(
                    lineIndex = index,
                    current = lines.getOrNull(index),
                    next = lines.getOrNull(index + 1),
                    lineCount = lines.size,
                )
                if (!snapshot.isPlaying) return@launch
                val next = lines.getOrNull(index + 1) ?: return@launch
                val speed = if (snapshot.speed > 0f) snapshot.speed else 1f
                val waitMs = ((next.timeMs - position) / speed).toLong()
                delay(waitMs.coerceAtLeast(MIN_WAIT_MS))
            }
        }
    }

    companion object {
        const val NO_LINE = -1

        /**
         * Floor on the sleep between lines. Lyric files sometimes carry two
         * lines on the same timestamp; without this the loop would spin.
         */
        const val MIN_WAIT_MS = 16L
    }
}
