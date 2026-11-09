package dev.lyriclane.lyrics

/**
 * Turns raw parsed lines into the sequence that is safe to show in a car.
 *
 * A single lyric line can be far too long for a head unit — Android Auto gives
 * us one line of text and truncates the rest with an ellipsis, so a long line
 * would lose its ending. Where a line is long *and* has enough time before the
 * next one, it is split at a word boundary into timed chunks, each of which gets
 * its own slot. Where there is not enough time to read two chunks, the line is
 * left alone and the renderer truncates it.
 */
object LyricTimeline {

    /** Shortest time a chunk may hold the screen. Below this it is unreadable. */
    const val MIN_CHUNK_MS = 1_200L

    fun prepare(
        lines: List<LyricLine>,
        maxChars: Int,
        trackDurationMs: Long,
    ): List<LyricLine> {
        if (lines.isEmpty()) return lines
        val out = ArrayList<LyricLine>(lines.size)

        for (index in lines.indices) {
            val line = lines[index]
            val endMs = lines.getOrNull(index + 1)?.timeMs
                ?: maxOf(trackDurationMs, line.timeMs + MIN_CHUNK_MS)
            val window = (endMs - line.timeMs).coerceAtLeast(0L)

            if (line.isGap || line.text.length <= maxChars) {
                out += line
                continue
            }

            val chunks = splitAtWords(line.text, maxChars)
            // Only split when every chunk gets a readable slot.
            if (chunks.size < 2 || window < chunks.size * MIN_CHUNK_MS) {
                out += line
                continue
            }
            val step = window / chunks.size
            chunks.forEachIndexed { chunkIndex, chunk ->
                val chunkStart = line.timeMs + step * chunkIndex
                out += LyricLine(
                    timeMs = chunkStart,
                    text = chunk,
                    // Each chunk ends where the next begins; the last inherits
                    // the original line's end, so gap detection still works.
                    endMs = if (chunkIndex == chunks.lastIndex) {
                        line.endMs
                    } else {
                        chunkStart + step
                    },
                )
            }
        }
        return out
    }

    /**
     * Greedy wrap at word boundaries. A single word longer than [maxChars] is
     * emitted whole rather than cut mid-word; the renderer truncates it.
     */
    fun splitAtWords(text: String, maxChars: Int): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val words = text.split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return listOf(text)

        val chunks = ArrayList<String>()
        val current = StringBuilder()
        for (word in words) {
            when {
                current.isEmpty() -> current.append(word)
                current.length + 1 + word.length <= maxChars -> current.append(' ').append(word)
                else -> {
                    chunks += current.toString()
                    current.setLength(0)
                    current.append(word)
                }
            }
        }
        if (current.isNotEmpty()) chunks += current.toString()
        return chunks
    }

    /**
     * The next line with words in it, skipping blank gap markers. [skip] jumps
     * further ahead, so `skip = 1` gives the line after next.
     *
     * Display wants sung lines; a blank marker on screen tells the driver
     * nothing.
     */
    fun nextSung(lines: List<LyricLine>, fromIndex: Int, skip: Int = 0): LyricLine? {
        var remaining = skip
        for (index in (fromIndex + 1)..lines.lastIndex) {
            val line = lines[index]
            if (line.isGap) continue
            if (remaining == 0) return line
            remaining--
        }
        return null
    }

    /** The most recent line with words in it, at or before [fromIndex] - 1. */
    fun previousSung(lines: List<LyricLine>, fromIndex: Int): LyricLine? {
        for (index in (fromIndex - 1) downTo 0) {
            val line = lines[index]
            if (!line.isGap) return line
        }
        return null
    }

    /**
     * Index of the line showing at [positionMs], or -1 before the first line.
     * Binary search: lyric files run to several hundred lines and this is
     * called on every resync.
     */
    fun indexAt(lines: List<LyricLine>, positionMs: Long): Int {
        var low = 0
        var high = lines.size - 1
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].timeMs <= positionMs) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }
}
