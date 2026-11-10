package dev.lyriclane.lyrics

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Why a lyrics lookup ended the way it did.
 *
 * "No lyrics found" on its own is not actionable: the user cannot tell whether
 * the source had nothing, had the song under a different title, or had the
 * wrong recording length. This records enough to tell those apart, and is shown
 * on the phone and written to logcat.
 */
data class LookupReport(val attempts: List<Attempt>) {

    data class Attempt(
        val source: LyricsSourceId,
        val candidates: Int,
        val accepted: Int,
        val bestTitle: String? = null,
        val closestDurationDeltaMs: Long? = null,
        val error: String? = null,
    )

    /** One short line per source, for the phone screen. */
    fun summary(): String = attempts.joinToString("\n") { attempt ->
        when {
            attempt.error != null -> "${attempt.source.label}: ${attempt.error}"
            attempt.candidates == 0 -> "${attempt.source.label}: nothing for this title"
            attempt.accepted == 0 -> buildString {
                append(attempt.source.label)
                append(": ")
                append(attempt.candidates)
                append(" found, none matched this recording")
                attempt.closestDurationDeltaMs?.let {
                    append(" (closest was ")
                    append(formatDelta(it))
                    append(" off")
                    attempt.bestTitle?.let { title -> append(", “$title”") }
                    append(')')
                }
            }

            else -> "${attempt.source.label}: ${attempt.accepted} of ${attempt.candidates} matched"
        }
    }

    companion object {
        fun formatDelta(deltaMs: Long): String {
            val seconds = abs(deltaMs) / 1000.0
            return if (seconds < 10) "%.1f s".format(seconds) else "${seconds.roundToLong()} s"
        }
    }
}

/** A lookup's outcome together with the trail of how it got there. */
data class LyricsLookup(val result: LyricsResult, val report: LookupReport? = null)
