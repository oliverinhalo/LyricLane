package dev.lyriclane.lyrics

import android.util.Log

/**
 * Lyrics for a track: cache first, network second.
 *
 * Cache hits are the common case once a song has been played, and they must be
 * instant and work with no connection — a car is frequently offline.
 */
class LyricsRepository(
    private val dao: LyricsDao,
    private val client: LrclibClient,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    suspend fun lyricsFor(identity: TrackIdentity): LyricsResult {
        val key = identity.cacheKey

        dao.find(key)?.let { cached ->
            if (!cached.isStale(nowMs())) return cached.toResult(fromCache = true)
        }

        return try {
            val candidate = resolve(identity)
            if (candidate == null) {
                dao.put(negativeEntry(identity))
                LyricsResult.NotFound
            } else {
                dao.put(candidate.toCacheEntry(identity, nowMs()))
                candidate.toResult(fromCache = false)
            }
        } catch (e: LrclibClient.LookupFailure) {
            Log.w(TAG, "lyrics lookup failed for ${identity.title}", e)
            // Serve a stale entry rather than nothing when we cannot reach the
            // network — stale lyrics are still the right lyrics.
            dao.find(key)?.toResult(fromCache = true)
                ?: LyricsResult.Error(e.message ?: "Could not reach LRCLIB")
        }
    }

    /**
     * The user's manual choice from the picker. Stored against this track's key
     * so it survives and wins over whatever the automatic match would pick.
     */
    suspend fun applyChoice(identity: TrackIdentity, candidate: LyricsCandidate): LyricsResult {
        dao.put(candidate.toCacheEntry(identity, nowMs()))
        return candidate.toResult(fromCache = false)
    }

    suspend fun search(query: String, artist: String? = null): List<LyricsCandidate> =
        try {
            client.search(query, artist)
        } catch (e: LrclibClient.LookupFailure) {
            Log.w(TAG, "lyrics search failed", e)
            emptyList()
        }

    suspend fun offsetFor(identity: TrackIdentity, fallbackMs: Long): Long =
        dao.offsetFor(identity.cacheKey) ?: fallbackMs

    suspend fun setOffset(identity: TrackIdentity, offsetMs: Long) =
        dao.putOffset(TrackOffset(identity.cacheKey, offsetMs))

    suspend fun forget(identity: TrackIdentity) = dao.forget(identity.cacheKey)

    suspend fun clearCache() {
        dao.clear()
        dao.clearOffsets()
    }

    suspend fun cachedCount(): Int = dao.count()

    // --- internals ------------------------------------------------------------------

    private suspend fun resolve(identity: TrackIdentity): LyricsCandidate? {
        // /api/get is lenient about duration and can return a different cut, so
        // its answer is scored like any other candidate.
        val direct = client.get(identity)
        if (direct != null && LyricsMatcher.score(identity, direct) != null) return direct

        // Reaching here means /api/get gave us nothing plausible, so there is
        // nothing to protect by catching a search failure: let it propagate and
        // be reported as "offline" (with any stale cache entry served instead).
        val searched = client.search(identity.title, identity.artist)

        // Deliberately no fall back to an implausible direct hit. If /api/get
        // answered with a different cut of the song its timings are wrong, and
        // wrong lyrics scrolling past in a car are worse than none: the user
        // gets "No lyrics found" and the manual picker instead.
        return LyricsMatcher.bestMatch(identity, searched)
    }

    private fun negativeEntry(identity: TrackIdentity) = CachedLyrics(
        cacheKey = identity.cacheKey,
        trackName = identity.title,
        artistName = identity.artist,
        syncedLyrics = null,
        plainLyrics = null,
        instrumental = false,
        notFound = true,
        fetchedAtMs = nowMs(),
    )

    private fun LyricsCandidate.toCacheEntry(identity: TrackIdentity, now: Long) = CachedLyrics(
        cacheKey = identity.cacheKey,
        trackName = trackName,
        artistName = artistName,
        syncedLyrics = syncedLyrics,
        plainLyrics = plainLyrics,
        instrumental = instrumental,
        notFound = false,
        fetchedAtMs = now,
    )

    private fun LyricsCandidate.toResult(fromCache: Boolean): LyricsResult =
        resolveResult(syncedLyrics, plainLyrics, instrumental, fromCache)

    private fun CachedLyrics.toResult(fromCache: Boolean): LyricsResult = when {
        notFound -> LyricsResult.NotFound
        else -> resolveResult(syncedLyrics, plainLyrics, instrumental, fromCache)
    }

    /**
     * Shared by the cache and the network path so they can never disagree.
     *
     * Note the parsed-but-empty case: a file can carry timestamps and still
     * yield no usable lines, and treating that as "synced" would leave the car
     * showing a rest for the whole song.
     */
    private fun resolveResult(
        syncedLyrics: String?,
        plainLyrics: String?,
        instrumental: Boolean,
        fromCache: Boolean,
    ): LyricsResult {
        if (LrcParser.hasTimestamps(syncedLyrics)) {
            val lines = LrcParser.parse(syncedLyrics!!).lines
            if (lines.any { it.text.isNotBlank() }) {
                return LyricsResult.Synced(lines, fromCache)
            }
        }
        if (!plainLyrics.isNullOrBlank()) return LyricsResult.PlainOnly(plainLyrics, fromCache)
        if (instrumental) return LyricsResult.Instrumental
        return LyricsResult.NotFound
    }

    /**
     * Positive hits never expire — lyrics do not change. Misses are retried
     * after a week, because LRCLIB is community-contributed and grows.
     */
    private fun CachedLyrics.isStale(now: Long): Boolean =
        notFound && now - fetchedAtMs > NEGATIVE_TTL_MS

    companion object {
        private const val TAG = "LyricLane"
        val NEGATIVE_TTL_MS = 7L * 24 * 60 * 60 * 1000
    }
}
