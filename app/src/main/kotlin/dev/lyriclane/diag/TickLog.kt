package dev.lyriclane.diag

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Phase 0 instrumentation.
 *
 * The point of the spike is to find out whether Android Auto renders live
 * MediaSession metadata changes, and how fast it will accept them. We cannot
 * read back what the car actually drew, so we record the exact moment we *push*
 * each metadata update. The operator compares that trail (visible here, and in
 * `adb logcat -s LyricLane`) against what the head unit shows.
 *
 * Deliberately a process-wide singleton: the service and the UI live in the
 * same process and Hilt does not arrive until Phase 2.
 */
object TickLog {

    data class Entry(
        val sequence: Long,
        val elapsedRealtimeMs: Long,
        val wallClockMs: Long,
        val title: String,
    )

    private const val MAX_ENTRIES = 200

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    fun record(entry: Entry) {
        _entries.update { current ->
            (listOf(entry) + current).take(MAX_ENTRIES)
        }
    }

    fun clear() {
        _entries.value = emptyList()
    }

    /**
     * Mean interval between the pushes we recorded, or null with fewer than two
     * samples. Lets the UI show the achieved push rate next to the requested one.
     */
    fun measuredIntervalMs(): Long? {
        val snapshot = _entries.value
        if (snapshot.size < 2) return null
        val newest = snapshot.first().elapsedRealtimeMs
        val oldest = snapshot.last().elapsedRealtimeMs
        return (newest - oldest) / (snapshot.size - 1)
    }
}
