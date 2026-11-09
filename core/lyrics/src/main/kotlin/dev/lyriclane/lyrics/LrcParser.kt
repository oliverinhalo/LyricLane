package dev.lyriclane.lyrics

/**
 * Parser for the LRC format as it actually appears in the wild.
 *
 * Handles:
 *  - `[mm:ss]`, `[mm:ss.x]`, `[mm:ss.xx]`, `[mm:ss.xxx]`, with minutes
 *    allowed to run past 59 for very long tracks
 *  - several timestamps on one line (a repeated chorus)
 *  - `[offset:+/-ms]`, applied to every timestamp
 *  - ID tags (`[ar:]`, `[ti:]`, `[al:]`, `[by:]`, `[length:]`), which are skipped
 *  - blank lyric lines, which are kept as gaps so an instrumental break shows
 *    as a rest instead of leaving the previous line on screen
 */
object LrcParser {

    data class Parsed(
        val lines: List<LyricLine>,
        val offsetMs: Long,
    )

    private val TIMESTAMP = Regex("""\[(\d{1,3}):([0-5]?\d)(?:[.:](\d{1,3}))?]""")
    private val ID_TAG = Regex("""^\[(ar|ti|al|au|by|length|re|ve|tool|#)\s*:.*]$""", RegexOption.IGNORE_CASE)
    private val OFFSET_TAG = Regex("""^\[offset\s*:\s*([+-]?\d+)\s*]$""", RegexOption.IGNORE_CASE)

    fun parse(lrc: String): Parsed {
        var offsetMs = 0L
        val collected = ArrayList<LyricLine>()

        for (rawLine in lrc.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            val offsetMatch = OFFSET_TAG.matchEntire(line)
            if (offsetMatch != null) {
                offsetMs = offsetMatch.groupValues[1].toLongOrNull() ?: 0L
                continue
            }
            if (ID_TAG.matches(line)) continue

            val stamps = TIMESTAMP.findAll(line).toList()
            if (stamps.isEmpty()) continue

            // Text is whatever follows the last leading timestamp. Timestamps
            // only ever prefix the text in valid LRC.
            val text = line.substring(stamps.last().range.last + 1).trim()
            for (stamp in stamps) {
                collected += LyricLine(timeMs = stamp.toMillis(), text = text)
            }
        }

        // A positive [offset:] means the lyrics should appear earlier, per the
        // original Winamp spec ("+ shifts time up").
        val shifted = if (offsetMs == 0L) {
            collected
        } else {
            collected.map { it.copy(timeMs = (it.timeMs - offsetMs).coerceAtLeast(0L)) }
        }

        return Parsed(
            lines = shifted.sortedBy { it.timeMs }.dedupeGaps(),
            offsetMs = offsetMs,
        )
    }

    /** True when the text contains at least one usable timestamp. */
    fun hasTimestamps(lrc: String?): Boolean =
        !lrc.isNullOrBlank() && TIMESTAMP.containsMatchIn(lrc)

    private fun MatchResult.toMillis(): Long {
        val minutes = groupValues[1].toLong()
        val seconds = groupValues[2].toLong()
        val fraction = groupValues[3]
        val fractionMs = when (fraction.length) {
            0 -> 0L
            1 -> fraction.toLong() * 100
            2 -> fraction.toLong() * 10
            else -> fraction.take(3).toLong()
        }
        return minutes * 60_000L + seconds * 1_000L + fractionMs
    }

    /**
     * Collapses runs of consecutive gaps to a single marker. Some LRC files pad
     * with many blank timestamped lines, which would otherwise make the lyric
     * view look broken.
     */
    private fun List<LyricLine>.dedupeGaps(): List<LyricLine> {
        if (isEmpty()) return this
        val out = ArrayList<LyricLine>(size)
        for (line in this) {
            val previous = out.lastOrNull()
            if (line.isGap && previous != null && previous.isGap) continue
            out += line
        }
        return out
    }
}
