package dev.lyriclane.lyrics

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The cache layer, with fakes for the database and the network.
 *
 * A cached **miss** is the one entry that can be wrong: it records that a
 * lookup found nothing, which may simply mean the matching was worse when it
 * ran. These tests pin that it expires quickly and never outlives the release
 * that changed the matching.
 */
class LyricsRepositoryTest {

    private class FakeDao : LyricsDao {
        val rows = HashMap<String, CachedLyrics>()
        val offsets = HashMap<String, Long>()
        var writes = 0

        override suspend fun find(key: String) = rows[key]
        override suspend fun put(entry: CachedLyrics) {
            writes++
            rows[entry.cacheKey] = entry
        }

        override suspend fun forget(key: String) {
            rows.remove(key)
        }

        override suspend fun clear() = rows.clear()
        override suspend fun count() = rows.size
        override suspend fun offsetFor(key: String) = offsets[key]
        override suspend fun putOffset(offset: TrackOffset) {
            offsets[offset.cacheKey] = offset.offsetMs
        }

        override suspend fun clearOffsets() = offsets.clear()
    }

    private class FakeSource(
        override val id: LyricsSourceId = LyricsSourceId.LRCLIB,
        private val candidates: List<LyricsCandidate> = emptyList(),
        private val failWith: String? = null,
    ) : LyricsSource {
        var lookUps = 0

        override suspend fun lookUp(identity: TrackIdentity): List<LyricsCandidate> {
            lookUps++
            failWith?.let { throw LrclibClient.LookupFailure(it) }
            return candidates
        }

        override suspend fun search(query: String, artist: String?) = candidates
    }

    private val identity = TrackIdentity(
        title = "A Song",
        artist = "An Artist",
        album = "An Album",
        durationMs = 200_000,
    )

    private fun candidate(seconds: Double = 200.0, synced: Boolean = true) = LyricsCandidate(
        id = 1,
        trackName = "A Song",
        artistName = "An Artist",
        albumName = "An Album",
        durationSeconds = seconds,
        hasSynced = synced,
        instrumental = false,
        syncedLyrics = if (synced) "[00:01.00] a line\n[00:05.00] another line" else null,
        plainLyrics = "a line",
    )

    @Test
    fun `a hit is served from cache without asking the network again`() = runTest {
        val dao = FakeDao()
        val source = FakeSource(candidates = listOf(candidate()))
        val repository = LyricsRepository(dao, listOf(source))

        val first = repository.lyricsFor(identity)
        val second = repository.lyricsFor(identity)

        assertThat(first.result).isInstanceOf(LyricsResult.Synced::class.java)
        assertThat(second.result).isInstanceOf(LyricsResult.Synced::class.java)
        assertThat((second.result as LyricsResult.Synced).fromCache).isTrue()
        assertThat(source.lookUps).isEqualTo(1)
    }

    @Test
    fun `a miss is cached so a dead end is not retried on every play`() = runTest {
        val dao = FakeDao()
        val source = FakeSource(candidates = emptyList())
        val repository = LyricsRepository(dao, listOf(source))

        repository.lyricsFor(identity)
        val second = repository.lyricsFor(identity)

        assertThat(second.result).isEqualTo(LyricsResult.NotFound)
        assertThat(source.lookUps).isEqualTo(1)
    }

    @Test
    fun `a cached miss is retried once it is a day old`() = runTest {
        val dao = FakeDao()
        val source = FakeSource(candidates = emptyList())
        var now = 1_000_000L
        val repository = LyricsRepository(dao, listOf(source)) { now }

        repository.lyricsFor(identity)
        now += LyricsRepository.NEGATIVE_TTL_MS + 1
        repository.lyricsFor(identity)

        assertThat(source.lookUps).isEqualTo(2)
    }

    @Test
    fun `a miss from an older build is retried immediately`() = runTest {
        val dao = FakeDao()
        // A "not found" left by a previous generation of the matching logic.
        dao.rows[identity.cacheKey] = CachedLyrics(
            cacheKey = identity.cacheKey,
            trackName = identity.title,
            artistName = identity.artist,
            syncedLyrics = null,
            plainLyrics = null,
            lyricsFile = null,
            source = null,
            instrumental = false,
            notFound = true,
            fetchedAtMs = 1_000_000L,
            epoch = LyricsRepository.CACHE_EPOCH - 1,
        )
        val source = FakeSource(candidates = listOf(candidate()))
        val repository = LyricsRepository(dao, listOf(source)) { 1_000_001L }

        val lookup = repository.lyricsFor(identity)

        // The fix released with the new epoch takes effect at once rather than
        // waiting for the stale entry to time out.
        assertThat(source.lookUps).isEqualTo(1)
        assertThat(lookup.result).isInstanceOf(LyricsResult.Synced::class.java)
    }

    @Test
    fun `a later source is asked when the first has nothing usable`() = runTest {
        val dao = FakeDao()
        val first = FakeSource(LyricsSourceId.LRCLIB, candidates = emptyList())
        val second = FakeSource(LyricsSourceId.NETEASE, candidates = listOf(candidate()))
        val repository = LyricsRepository(dao, listOf(first, second))

        val lookup = repository.lyricsFor(identity)

        assertThat(first.lookUps).isEqualTo(1)
        assertThat(second.lookUps).isEqualTo(1)
        assertThat(lookup.result).isInstanceOf(LyricsResult.Synced::class.java)
    }

    @Test
    fun `a confident first match stops the search there`() = runTest {
        val dao = FakeDao()
        val first = FakeSource(LyricsSourceId.LRCLIB, candidates = listOf(candidate()))
        val second = FakeSource(LyricsSourceId.NETEASE, candidates = listOf(candidate()))
        val repository = LyricsRepository(dao, listOf(first, second))

        repository.lyricsFor(identity)

        assertThat(second.lookUps).isEqualTo(0)
    }

    @Test
    fun `an unreachable source is reported, not cached as a miss`() = runTest {
        val dao = FakeDao()
        val source = FakeSource(failWith = "Could not reach LRCLIB")
        val repository = LyricsRepository(dao, listOf(source))

        val lookup = repository.lyricsFor(identity)

        assertThat(lookup.result).isInstanceOf(LyricsResult.Error::class.java)
        // Nothing written: an outage must not become a week of "no lyrics".
        assertThat(dao.writes).isEqualTo(0)
        assertThat(lookup.report?.summary()).contains("Could not reach LRCLIB")
    }

    @Test
    fun `stale lyrics beat no lyrics when the network is gone`() = runTest {
        val dao = FakeDao()
        val working = FakeSource(candidates = listOf(candidate()))
        LyricsRepository(dao, listOf(working)).lyricsFor(identity)

        val offline = LyricsRepository(dao, listOf(FakeSource(failWith = "offline")))
        val lookup = offline.lyricsFor(identity)

        assertThat(lookup.result).isInstanceOf(LyricsResult.Synced::class.java)
    }

    @Test
    fun `the report explains a near miss`() = runTest {
        val dao = FakeDao()
        // Right title, wrong recording: three minutes too long.
        val source = FakeSource(candidates = listOf(candidate(seconds = 380.0)))
        val repository = LyricsRepository(dao, listOf(source))

        val lookup = repository.lyricsFor(identity)
        val summary = lookup.report!!.summary()

        assertThat(lookup.result).isEqualTo(LyricsResult.NotFound)
        assertThat(summary).contains("none matched this recording")
        assertThat(summary).contains("180")
    }

    @Test
    fun `a manual choice overrides whatever the match would have been`() = runTest {
        val dao = FakeDao()
        val repository = LyricsRepository(dao, listOf(FakeSource(candidates = emptyList())))
        repository.lyricsFor(identity)

        repository.applyChoice(identity, candidate())
        val afterwards = repository.lyricsFor(identity)

        assertThat(afterwards.result).isInstanceOf(LyricsResult.Synced::class.java)
    }

    @Test
    fun `offsets survive independently of the lyrics cache`() = runTest {
        val dao = FakeDao()
        val repository = LyricsRepository(dao, listOf(FakeSource()))

        repository.setOffset(identity, 500)

        assertThat(repository.offsetFor(identity, fallbackMs = 0)).isEqualTo(500)
        assertThat(repository.offsetFor(TrackIdentity("x", "y", null, 1), 250)).isEqualTo(250)
    }
}
