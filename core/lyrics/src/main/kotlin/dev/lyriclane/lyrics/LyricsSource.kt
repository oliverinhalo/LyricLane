package dev.lyriclane.lyrics

/**
 * One place lyrics can come from.
 *
 * Sources are tried in order and their candidates scored together, so a later
 * source can win if it has a better match for the recording in hand.
 */
interface LyricsSource {

    val id: LyricsSourceId

    /** Best-effort direct lookup. Null means "nothing for this track". */
    suspend fun lookUp(identity: TrackIdentity): List<LyricsCandidate>

    /** Free-text search for the manual picker. */
    suspend fun search(query: String, artist: String?): List<LyricsCandidate>
}
