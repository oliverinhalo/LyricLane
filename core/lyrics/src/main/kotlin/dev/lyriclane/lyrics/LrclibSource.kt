package dev.lyriclane.lyrics

/**
 * LRCLIB as a [LyricsSource]. Tried first: it is the best-curated of the two
 * and some of its entries carry word-level timings, which give the most
 * accurate line changes of anything available without a Spotify login.
 */
class LrclibSource(private val client: LrclibClient) : LyricsSource {

    override val id: LyricsSourceId = LyricsSourceId.LRCLIB

    override suspend fun lookUp(identity: TrackIdentity): List<LyricsCandidate> {
        val direct = client.get(identity)
        val searched = client.search(identity.title, identity.artist)
        return buildList {
            direct?.let(::add)
            // Drop the duplicate the search will usually also return.
            addAll(searched.filterNot { candidate -> candidate.id == direct?.id })
        }
    }

    override suspend fun search(query: String, artist: String?): List<LyricsCandidate> =
        client.search(query, artist)
}
