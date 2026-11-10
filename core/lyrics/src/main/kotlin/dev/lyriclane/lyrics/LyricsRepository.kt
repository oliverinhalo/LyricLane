package dev.lyriclane.lyrics

import android.util.Log
import kotlin.math.abs

/**
 * Lyrics for a track: cache first, then each source in turn.
 *
 * Cache hits are the common case once a song has been played, and they must be
 * instant and work with no connection — a car is frequently offline.
 *
 * Sources are consulted in order and stop early once one produces a confident
 * match, so the usual path is a single LRCLIB round trip. A weaker match is
 * kept and compared against what later sources offer, so a second source can
 * still win if it has better lyrics for the recording in hand.
 */
class LyricsRepository(
    private val dao: LyricsDao,
    private val sources: List<LyricsSource>,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    suspend fun lyricsFor(identity: TrackIdentity): LyricsLookup {
        val key = identity.cacheKey

        dao.find(key)?.let { cached ->
            if (!cached.isStale(nowMs())) return LyricsLookup(cached.toResult(fromCache = true))
        }

        val attempts = ArrayList<LookupReport.Attempt>()
        return try {
            val candidate = resolve(identity, attempts)
            val report = LookupReport(attempts)
            Log.i(TAG, "lookup \"${identity.title}\": " + report.summary().replace('\n', '|'))
            if (candidate == null) {
                dao.put(negativeEntry(identity))
                LyricsLookup(LyricsResult.NotFound, report)
            } else {
                dao.put(candidate.toCacheEntry(identity, nowMs()))
                LyricsLookup(candidate.toResult(fromCache = false), report)
            }
        } catch (e: LrclibClient.LookupFailure) {
            Log.w(TAG, "lyrics lookup failed for ${identity.title}", e)
            // Serve a stale entry rather than nothing when the network is gone:
            // stale lyrics are still the right lyrics.
            val stale = dao.find(key)?.toResult(fromCache = true)
            LyricsLookup(
                result = stale ?: LyricsResult.Error(e.message ?: "Could not reach a lyrics source"),
                report = LookupReport(attempts),
            )
        }
    }

    /**
     * The user's manual choice from the picker, stored against this track so it
     * wins over whatever the automatic match would pick next time.
     */
    suspend fun applyChoice(identity: TrackIdentity, candidate: LyricsCandidate): LyricsResult {
        dao.put(candidate.toCacheEntry(identity, nowMs()))
        return candidate.toResult(fromCache = false)
    }

    /** Searches every source, best matches first, for the manual picker. */
    suspend fun search(query: String, artist: String? = null): List<LyricsCandidate> =
        sources.flatMap { source ->
            runCatching { source.search(query, artist) }
                .onFailure { Log.w(TAG, "search failed on ${source.id}", it) }
                .getOrDefault(emptyList())
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

    private suspend fun resolve(
        identity: TrackIdentity,
        attempts: MutableList<LookupReport.Attempt>,
    ): LyricsCandidate? {
        var best: LyricsCandidate? = null
        var bestScore = Double.NEGATIVE_INFINITY
        var lastFailure: LrclibClient.LookupFailure? = null

        for (source in sources) {
            val candidates = try {
                source.lookUp(identity)
            } catch (e: LrclibClient.LookupFailure) {
                // One source being unreachable must not sink the others.
                Log.w(TAG, "lookup failed on ${source.id}", e)
                lastFailure = e
                attempts += LookupReport.Attempt(
                    source = source.id,
                    candidates = 0,
                    accepted = 0,
                    error = e.message ?: "unreachable",
                )
                continue
            }

            var accepted = 0
            var closest: LyricsCandidate? = null
            var closestDelta = Long.MAX_VALUE

            for (candidate in candidates) {
                val delta = abs((candidate.durationSeconds * 1000.0) - identity.durationMs).toLong()
                if (delta < closestDelta) {
                    closestDelta = delta
                    closest = candidate
                }
                val score = LyricsMatcher.score(identity, candidate) ?: continue
                accepted++
                if (score > bestScore) {
                    bestScore = score
                    best = candidate
                }
            }

            attempts += LookupReport.Attempt(
                source = source.id,
                candidates = candidates.size,
                accepted = accepted,
                bestTitle = closest?.trackName,
                closestDurationDeltaMs = closest?.let { closestDelta },
            )
            // Good enough to stop asking: same recording length, timed lyrics.
            val settled = best?.let { LyricsMatcher.isConfident(identity, it) } == true
            if (settled) break
        }

        // Every source failed to answer at all: report it as an error rather
        // than caching a "not found" that would stick for a week.
        if (best == null && lastFailure != null) throw lastFailure
        return best
    }

    private fun negativeEntry(identity: TrackIdentity) = CachedLyrics(
        cacheKey = identity.cacheKey,
        trackName = identity.title,
        artistName = identity.artist,
        syncedLyrics = null,
        plainLyrics = null,
        lyricsFile = null,
        source = null,
        instrumental = false,
        notFound = true,
        fetchedAtMs = nowMs(),
        epoch = CACHE_EPOCH,
    )

    private fun LyricsCandidate.toCacheEntry(identity: TrackIdentity, now: Long) = CachedLyrics(
        cacheKey = identity.cacheKey,
        trackName = trackName,
        artistName = artistName,
        syncedLyrics = syncedLyrics,
        plainLyrics = plainLyrics,
        lyricsFile = lyricsFile,
        source = source.name,
        instrumental = instrumental,
        notFound = false,
        fetchedAtMs = now,
        epoch = CACHE_EPOCH,
    )

    private fun LyricsCandidate.toResult(fromCache: Boolean): LyricsResult =
        resolveResult(lyricsFile, syncedLyrics, plainLyrics, instrumental, source, fromCache)

    private fun CachedLyrics.toResult(fromCache: Boolean): LyricsResult = when {
        notFound -> LyricsResult.NotFound
        else -> resolveResult(
            lyricsFile = lyricsFile,
            syncedLyrics = syncedLyrics,
            plainLyrics = plainLyrics,
            instrumental = instrumental,
            source = source?.let { name ->
                LyricsSourceId.entries.firstOrNull { it.name == name }
            } ?: LyricsSourceId.LRCLIB,
            fromCache = fromCache,
        )
    }

    /**
     * Shared by the cache and the network path so the two can never disagree.
     *
     * The structured document is preferred when present because it carries line
     * end times. Note the parsed-but-empty case: a file can carry timestamps and
     * still yield no usable lines, and treating that as "synced" would leave the
     * car showing a rest for the whole song.
     */
    private fun resolveResult(
        lyricsFile: String?,
        syncedLyrics: String?,
        plainLyrics: String?,
        instrumental: Boolean,
        source: LyricsSourceId,
        fromCache: Boolean,
    ): LyricsResult {
        if (lyricsFile != null) {
            val lines = LyricsFileParser.parse(lyricsFile)
            if (lines != null && lines.any { it.text.isNotBlank() }) {
                return LyricsResult.Synced(lines, fromCache, source)
            }
        }
        if (LrcParser.hasTimestamps(syncedLyrics)) {
            val lines = LrcParser.parse(syncedLyrics!!).lines
            if (lines.any { it.text.isNotBlank() }) {
                return LyricsResult.Synced(lines, fromCache, source)
            }
        }
        if (!plainLyrics.isNullOrBlank()) return LyricsResult.PlainOnly(plainLyrics, fromCache)
        if (instrumental) return LyricsResult.Instrumental
        return LyricsResult.NotFound
    }

    /**
     * Positive hits never expire — lyrics do not change. Misses are retried
     * after a week, because these catalogues are community-contributed and grow.
     */
    private fun CachedLyrics.isStale(now: Long): Boolean = when {
        !notFound -> false
        // A miss recorded by an older generation of the matching logic must not
        // outlive the release that fixed whatever caused it.
        epoch != CACHE_EPOCH -> true
        else -> now - fetchedAtMs > NEGATIVE_TTL_MS
    }

    companion object {
        private const val TAG = "LyricLane"

        /**
         * Bump whenever matching or source selection changes, so misses
         * recorded under the previous behaviour are retried, not trusted.
         */
        const val CACHE_EPOCH = 2

        /** Re-checking a miss costs one request, so re-check often. */
        val NEGATIVE_TTL_MS = 24L * 60 * 60 * 1000
    }
}
