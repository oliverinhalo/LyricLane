package dev.lyriclane.lyrics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LyricsMatcherTest {

    private val identity = TrackIdentity(
        title = "Bohemian Rhapsody",
        artist = "Queen",
        album = "A Night at the Opera",
        durationMs = 354_000,
    )

    private fun candidate(
        title: String = "Bohemian Rhapsody",
        artist: String = "Queen",
        album: String? = "A Night at the Opera",
        seconds: Double = 354.0,
        synced: Boolean = true,
        instrumental: Boolean = false,
    ) = LyricsCandidate(
        id = 1,
        trackName = title,
        artistName = artist,
        albumName = album,
        durationSeconds = seconds,
        hasSynced = synced,
        instrumental = instrumental,
        syncedLyrics = if (synced) "[00:01.00] x" else null,
        plainLyrics = "x",
    )

    @Test
    fun `a different recording length is rejected outright`() {
        val liveVersion = candidate(seconds = 420.0)

        assertThat(LyricsMatcher.score(identity, liveVersion)).isNull()
    }

    @Test
    fun `a wrong song with the same length is rejected`() {
        val impostor = candidate(title = "Something Completely Different", artist = "Nobody")

        assertThat(LyricsMatcher.score(identity, impostor)).isNull()
    }

    @Test
    fun `synced beats plain when both match`() {
        val best = LyricsMatcher.bestMatch(
            identity,
            listOf(candidate(synced = false), candidate(synced = true)),
        )

        assertThat(best?.hasSynced).isTrue()
    }

    @Test
    fun `closest duration wins among synced candidates`() {
        val best = LyricsMatcher.bestMatch(
            identity,
            listOf(
                candidate(seconds = 360.0),
                candidate(seconds = 354.0),
                candidate(seconds = 349.0),
            ),
        )

        assertThat(best?.durationSeconds).isEqualTo(354.0)
    }

    @Test
    fun `within tolerance all durations score equally so text decides`() {
        val exactButWrongAlbum = candidate(seconds = 354.0, album = "Greatest Hits")
        val slightlyOffRightAlbum = candidate(seconds = 355.5)

        val exact = LyricsMatcher.score(identity, exactButWrongAlbum)!!
        val slightly = LyricsMatcher.score(identity, slightlyOffRightAlbum)!!

        assertThat(slightly).isGreaterThan(exact)
    }

    @Test
    fun `remaster decoration does not stop a match`() {
        val spotifyStyle = identity.copy(title = "Bohemian Rhapsody - Remastered 2011")

        val best = LyricsMatcher.bestMatch(spotifyStyle, listOf(candidate()))

        assertThat(best).isNotNull()
    }

    @Test
    fun `featured artists do not stop a match`() {
        val withFeature = TrackIdentity(
            title = "Stay (feat. Justin Bieber)",
            artist = "The Kid LAROI, Justin Bieber",
            album = null,
            durationMs = 141_000,
        )
        val lrclibStyle = LyricsCandidate(
            id = 2,
            trackName = "Stay",
            artistName = "The Kid LAROI",
            albumName = null,
            durationSeconds = 141.0,
            hasSynced = true,
            instrumental = false,
            syncedLyrics = "[00:01.00] x",
            plainLyrics = null,
        )

        assertThat(LyricsMatcher.score(withFeature, lrclibStyle)).isNotNull()
    }

    @Test
    fun `instrumental is a last resort`() {
        val best = LyricsMatcher.bestMatch(
            identity,
            listOf(candidate(instrumental = true), candidate(instrumental = false)),
        )

        assertThat(best?.instrumental).isFalse()
    }

    @Test
    fun `no plausible candidate yields null`() {
        assertThat(LyricsMatcher.bestMatch(identity, emptyList())).isNull()
        assertThat(
            LyricsMatcher.bestMatch(identity, listOf(candidate(seconds = 600.0)))
        ).isNull()
    }
}
