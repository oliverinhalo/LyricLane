package dev.lyriclane.lyrics

import kotlin.math.abs

/**
 * Picks the right LRCLIB result for a track.
 *
 * LRCLIB's search endpoint returns up to twenty entries for a popular song:
 * live versions, covers, mislabelled uploads, the same song at four slightly
 * different durations. Duration is by far the strongest signal that we have the
 * same *recording*, so it dominates; title and artist similarity break ties.
 */
object LyricsMatcher {

    /** Durations within this much of Spotify's are treated as the same cut. */
    const val DURATION_TOLERANCE_MS = 2_000L

    /** Beyond this, it is a different recording however well the text matches. */
    private const val DURATION_HARD_LIMIT_MS = 15_000L

    private const val MIN_TEXT_SIMILARITY = 0.34

    fun bestMatch(
        identity: TrackIdentity,
        candidates: List<LyricsCandidate>,
    ): LyricsCandidate? = candidates
        .mapNotNull { candidate -> score(identity, candidate)?.let { candidate to it } }
        .maxByOrNull { it.second }
        ?.first

    /** Null means "not a plausible match at all". Higher is better. */
    fun score(identity: TrackIdentity, candidate: LyricsCandidate): Double? {
        val deltaMs = abs((candidate.durationSeconds * 1000.0) - identity.durationMs)
        if (deltaMs > DURATION_HARD_LIMIT_MS) return null

        val titleSimilarity = TextNormalizer.similarity(identity.title, candidate.trackName)
        val artistSimilarity = TextNormalizer.similarity(
            TextNormalizer.primaryArtist(identity.artist),
            TextNormalizer.primaryArtist(candidate.artistName),
        )
        // A wrong song with an identical runtime is the failure mode that looks
        // worst in the car, so require the text to be in the same ballpark.
        if (titleSimilarity < MIN_TEXT_SIMILARITY) return null

        var score = 0.0
        // Within tolerance: full marks, tapering to zero at the hard limit.
        score += if (deltaMs <= DURATION_TOLERANCE_MS) {
            60.0
        } else {
            60.0 * (1.0 - (deltaMs - DURATION_TOLERANCE_MS) / (DURATION_HARD_LIMIT_MS - DURATION_TOLERANCE_MS))
        }
        score += titleSimilarity * 20.0
        score += artistSimilarity * 15.0
        // Timed lyrics are the entire point; an untimed match is a consolation.
        if (candidate.hasSynced) score += 25.0
        if (candidate.instrumental) score -= 5.0
        if (identity.album != null && candidate.albumName != null) {
            score += TextNormalizer.similarity(identity.album, candidate.albumName) * 5.0
        }
        return score
    }
}
