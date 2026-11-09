package dev.lyriclane.lyrics

/**
 * Parser for LRCLIB's structured lyrics document (`lyricsfile`, version 1.0).
 *
 * Why bother when `syncedLyrics` already exists: plain LRC says only when each
 * line *starts*. This format gives `end_ms` too, so we know when a line stops
 * being sung — which is what lets the car put the upcoming line on screen
 * during an instrumental break instead of leaving the last one sitting there.
 * It also carries per-word timings, which we skip: a car gets whole lines.
 *
 * It is YAML, but a machine-generated subset of it, so this reads the shape
 * directly rather than pulling in a YAML library. Anything unexpected makes the
 * parse fail cleanly and the caller falls back to `syncedLyrics`.
 *
 * ```
 * lines:
 * - text: I close my eyes and I can see
 *   start_ms: 13960
 *   end_ms: 16560
 *   words:
 *   - text: 'I '
 *     start_ms: 13960
 * ```
 *
 * Indentation is what separates a line from a word: line entries start at
 * column 0, their properties at column 2, and everything under `words:` is
 * deeper still.
 */
object LyricsFileParser {

    private const val LINE_ENTRY_PREFIX = "- text:"

    /** Null when the document is not in this format, or carries no usable lines. */
    fun parse(document: String): List<LyricLine>? {
        if (!document.contains("lines:")) return null

        val lines = ArrayList<LyricLine>()
        var text: String? = null
        var startMs: Long? = null
        var endMs: Long? = null
        var inWords = false

        fun flush() {
            val currentText = text
            val currentStart = startMs
            if (currentText != null && currentStart != null) {
                lines += LyricLine(
                    timeMs = currentStart,
                    text = currentText.trim(),
                    endMs = endMs?.takeIf { it > currentStart },
                )
            }
            text = null
            startMs = null
            endMs = null
        }

        for (raw in document.lineSequence()) {
            val indent = raw.indexOfFirst { !it.isWhitespace() }
            if (indent < 0) continue
            val content = raw.substring(indent)

            when {
                // A new line entry: column 0, "- text: ...".
                indent == 0 && content.startsWith(LINE_ENTRY_PREFIX) -> {
                    flush()
                    inWords = false
                    text = unquote(content.removePrefix(LINE_ENTRY_PREFIX).trim())
                }

                // Properties of the current line sit at column 2.
                indent == 2 && content.startsWith("words:") -> inWords = true

                indent == 2 && !inWords && content.startsWith("start_ms:") ->
                    startMs = content.removePrefix("start_ms:").trim().toLongOrNull()

                indent == 2 && !inWords && content.startsWith("end_ms:") ->
                    endMs = content.removePrefix("end_ms:").trim().toLongOrNull()

                // Anything deeper belongs to a word; ignore it.
                else -> Unit
            }
        }
        flush()

        if (lines.isEmpty()) return null
        if (lines.none { it.text.isNotBlank() }) return null
        return lines.sortedBy { it.timeMs }
    }

    /**
     * YAML single-quoted scalars: wrapped in `'`, with an embedded quote
     * written `''`. Values without special characters arrive unquoted.
     */
    private fun unquote(value: String): String {
        if (value.length >= 2 && value.startsWith('\'') && value.endsWith('\'')) {
            return value.substring(1, value.length - 1).replace("''", "'")
        }
        if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) {
            return value.substring(1, value.length - 1).replace("\\\"", "\"")
        }
        return value
    }
}
