package dev.lyriclane.lyrics

import java.text.Normalizer
import java.util.Locale

/**
 * Normalises titles and artists so that cache keys and match scoring are not
 * defeated by the decorations Spotify ships in track names — "(Remastered
 * 2011)", "- 2009 Version", "feat. X", smart quotes, accents.
 */
object TextNormalizer {

    private val NOISE_PARENTHETICAL = Regex(
        """[(\[]\s*(?:remaster(?:ed)?|re-?master(?:ed)?|\d{4}\s*remaster|mono|stereo|deluxe|""" +
            """bonus(?:\s*track)?|album\s*version|single\s*version|radio\s*edit|explicit|""" +
            """clean|live|remix|extended(?:\s*mix)?|instrumental|demo|anniversary(?:\s*edition)?)""" +
            """[^)\]]*[)\]]""",
        RegexOption.IGNORE_CASE,
    )

    private val NOISE_SUFFIX = Regex(
        """\s*-\s*(?:\d{4}\s*)?(?:remaster(?:ed)?|re-?master(?:ed)?|mono|stereo|""" +
            """single\s*version|album\s*version|radio\s*edit|live|remix|version)\b.*$""",
        RegexOption.IGNORE_CASE,
    )

    private val FEATURING = Regex(
        """\s*(?:[(\[])?\s*(?:feat\.?|ft\.?|featuring|with)\s+[^)\]]*(?:[)\]])?""",
        RegexOption.IGNORE_CASE,
    )

    private val NON_ALNUM = Regex("""[^\p{L}\p{N}]+""")

    /** Lowercased, de-accented, decoration-free, single-spaced. */
    fun normalize(raw: String): String {
        var s = raw.lowercase(Locale.ROOT)
        s = s.replace('’', '\'').replace('‘', '\'')
        s = NOISE_PARENTHETICAL.replace(s, " ")
        s = NOISE_SUFFIX.replace(s, " ")
        s = FEATURING.replace(s, " ")
        s = Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")
        s = NON_ALNUM.replace(s, " ")
        return s.trim().replace(Regex(" +"), " ")
    }

    /**
     * The title with Spotify's decorations removed but its own words intact,
     * for use as a search term. Unlike [normalize] this keeps capitalisation
     * and punctuation, because a search engine wants a real title.
     */
    fun withoutDecorations(raw: String): String {
        var s = NOISE_PARENTHETICAL.replace(raw, " ")
        s = NOISE_SUFFIX.replace(s, " ")
        s = FEATURING.replace(s, " ")
        return s.trim().replace(Regex(" +"), " ").trimEnd('-', '\u2013', ',').trim()
    }

    /**
     * The artist as LRCLIB is most likely to hold it: Spotify joins multiple
     * artists with ", " or " & ", LRCLIB usually stores only the first.
     */
    fun primaryArtist(raw: String): String =
        raw.split(',', ';').first()
            .split(" & ", " x ", " X ", ignoreCase = false)
            .first()
            .trim()
            .ifEmpty { raw.trim() }

    /**
     * Similarity in [0,1] over normalised word sets (Jaccard). Good enough to
     * rank LRCLIB search hits and cheap enough to run on every candidate.
     */
    fun similarity(a: String, b: String): Double {
        val left = normalize(a).split(' ').filter { it.isNotEmpty() }.toSet()
        val right = normalize(b).split(' ').filter { it.isNotEmpty() }.toSet()
        if (left.isEmpty() && right.isEmpty()) return 1.0
        if (left.isEmpty() || right.isEmpty()) return 0.0
        val intersection = left.intersect(right).size.toDouble()
        val union = left.union(right).size.toDouble()
        return intersection / union
    }
}
