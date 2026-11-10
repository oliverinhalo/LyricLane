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

        // Spotify decorates titles ("- Remastered 2011", "(feat. ...)").
        // LRCLIB usually copes, but when a decorated title finds nothing it is
        // worth one more request with the decorations stripped.
        val fallback = if (searched.isEmpty()) {
            val plainTitle = TextNormalizer.withoutDecorations(identity.title)
            if (plainTitle.isNotBlank() && !plainTitle.equals(identity.title, ignoreCase = true)) {
                client.search(plainTitle, identity.artist)
            } else {
                emptyList()
            }
        } else {
            emptyList()
        }

        val seen = HashSet<Long>()
        return buildList {
            direct?.let {
                seen += it.id
                add(it)
            }
            (searched + fallback).forEach { candidate ->
                if (seen.add(candidate.id)) add(candidate)
            }
        }
    }

    override suspend fun search(query: String, artist: String?): List<LyricsCandidate> =
        client.search(query, artist)
}
