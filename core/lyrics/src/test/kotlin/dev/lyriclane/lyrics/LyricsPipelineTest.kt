package dev.lyriclane.lyrics

import com.google.common.truth.Truth.assertThat
import kotlin.math.abs
import org.junit.Test

/**
 * End-to-end test of the decision logic against **real LRCLIB responses**,
 * recorded under `src/test/resources/lrclib`.
 *
 * The hand-written tests check each rule in isolation; this one checks that the
 * rules, together, pick the right recording out of the twenty near-duplicates
 * LRCLIB actually returns for a popular song — and that the lyrics of the
 * winner parse into a sane timeline. That is the pipeline behind "press play
 * and see the words", minus the network and the car.
 *
 * Track titles are written as Spotify reports them, decorations and all.
 */
class LyricsPipelineTest {

    private data class Fixture(
        val case: String,
        val identity: TrackIdentity,
        val candidates: List<LyricsCandidate>,
    )

    private fun resource(path: String): String? =
        javaClass.classLoader?.getResourceAsStream("lrclib/$path")?.use {
            it.readBytes().decodeToString()
        }

    private fun fixtures(): List<Fixture> {
        val cases = resource("cases.tsv")
            ?: error("fixtures missing; see src/test/resources/lrclib")
        return cases.trim().lines().drop(1).map { line ->
            val (case, title, artist, album, durationMs) = line.split('\t')
            Fixture(
                case = case,
                identity = TrackIdentity(
                    title = title,
                    artist = artist,
                    album = album.takeIf { it.isNotBlank() },
                    durationMs = durationMs.toLong(),
                ),
                candidates = loadCandidates(case),
            )
        }
    }

    private fun loadCandidates(case: String): List<LyricsCandidate> {
        val tsv = resource("$case/candidates.tsv") ?: return emptyList()
        return tsv.trim().lines().drop(1).filter { it.isNotBlank() }.map { line ->
            val f = line.split('\t')
            val id = f[1]
            val lrc = resource("$case/lrc_$id.lrc")
            LyricsCandidate(
                id = id.toLong(),
                trackName = f[2],
                artistName = f[3],
                albumName = f[4].takeIf { it.isNotBlank() },
                durationSeconds = f[5].toDouble(),
                hasSynced = LrcParser.hasTimestamps(lrc),
                instrumental = f[6].toBoolean(),
                syncedLyrics = lrc,
                plainLyrics = null,
            )
        }
    }

    private fun fixture(case: String): Fixture =
        fixtures().single { it.case == case }

    @Test
    fun `fixtures loaded`() {
        val all = fixtures()

        assertThat(all.map { it.case }).containsAtLeast(
            "bohemian_rhapsody",
            "come_together_remastered",
            "stay_feat",
            "weightless_instrumental",
            "blinding_lights",
            "nonexistent",
        )
    }

    @Test
    fun `picks the right recording out of twenty near-duplicates`() {
        val f = fixture("bohemian_rhapsody")
        // LRCLIB really does return this many entries for this song.
        assertThat(f.candidates.size).isAtLeast(10)

        val best = LyricsMatcher.bestMatch(f.identity, f.candidates)

        assertThat(best).isNotNull()
        assertThat(best!!.hasSynced).isTrue()
        assertThat(TextNormalizer.normalize(best.trackName)).isEqualTo("bohemian rhapsody")
        assertThat(best.durationSeconds * 1000).isWithin(2_000.0).of(f.identity.durationMs.toDouble())
    }

    @Test
    fun `Spotify's remaster suffix does not stop a real match`() {
        val f = fixture("come_together_remastered")

        val best = LyricsMatcher.bestMatch(f.identity, f.candidates)

        assertThat(best).isNotNull()
        assertThat(TextNormalizer.normalize(best!!.trackName)).contains("come together")
    }

    @Test
    fun `a featured-artist title matches the plain LRCLIB entry`() {
        val f = fixture("stay_feat")

        val best = LyricsMatcher.bestMatch(f.identity, f.candidates)

        assertThat(best).isNotNull()
        assertThat(TextNormalizer.normalize(best!!.trackName)).contains("stay")
    }

    @Test
    fun `a real instrumental is recognised`() {
        val f = fixture("weightless_instrumental")

        val best = LyricsMatcher.bestMatch(f.identity, f.candidates)

        assertThat(best).isNotNull()
        assertThat(best!!.instrumental).isTrue()
        assertThat(best.hasSynced).isFalse()
    }

    @Test
    fun `a song LRCLIB does not have yields no match`() {
        val f = fixture("nonexistent")

        assertThat(f.candidates).isEmpty()
        assertThat(LyricsMatcher.bestMatch(f.identity, f.candidates)).isNull()
    }

    @Test
    fun `every picked candidate is the same recording length`() {
        fixtures().filter { it.candidates.isNotEmpty() }.forEach { f ->
            val best = LyricsMatcher.bestMatch(f.identity, f.candidates) ?: return@forEach
            val deltaMs = abs(best.durationSeconds * 1000 - f.identity.durationMs)
            assertThat(deltaMs).isLessThan(LyricsMatcher.DURATION_TOLERANCE_MS.toDouble() + 1)
        }
    }

    @Test
    fun `real lyrics parse into a usable timeline`() {
        val sung = fixtures().filter { f ->
            LyricsMatcher.bestMatch(f.identity, f.candidates)?.hasSynced == true
        }
        // Three of the fixtures are sung tracks with timed lyrics.
        assertThat(sung).isNotEmpty()

        sung.forEach { f ->
            val best = LyricsMatcher.bestMatch(f.identity, f.candidates)!!
            val lines = LrcParser.parse(best.syncedLyrics!!).lines

            assertThat(lines).isNotEmpty()
            assertThat(lines.map { it.timeMs }).isInOrder()
            // The first line of a song arrives early, not minutes in.
            assertThat(lines.first().timeMs).isLessThan(60_000L)
            assertThat(lines.none { it.timeMs < 0 }).isTrue()
            assertThat(lines.any { it.text.isNotBlank() }).isTrue()
        }
    }

    @Test
    fun `the prepared timeline is safe for a car screen`() {
        val f = fixture("bohemian_rhapsody")
        val best = LyricsMatcher.bestMatch(f.identity, f.candidates)!!
        val lines = LrcParser.parse(best.syncedLyrics!!).lines

        val prepared = LyricTimeline.prepare(
            lines = lines,
            maxChars = 56,
            trackDurationMs = f.identity.durationMs,
        )

        assertThat(prepared.map { it.timeMs }).isInOrder()
        // Splitting only ever adds entries; it never drops a line.
        assertThat(prepared.size).isAtLeast(lines.size)
        // Mid-song lookup lands on a line that has already started.
        val index = LyricTimeline.indexAt(prepared, 30_000)
        assertThat(index).isAtLeast(0)
        assertThat(prepared[index].timeMs).isAtMost(30_000)
    }
}
