package dev.lyriclane.lyrics

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject

/**
 * NetEase Cloud Music as a second lyrics source.
 *
 * Its public endpoints need no key and its LRCs are often well timed for
 * Western pop as well as Chinese releases, which makes it a useful fallback
 * when LRCLIB has nothing — or nothing timed — for a track.
 *
 * Two requests: search for the song, then fetch the lyrics for the best id.
 * The lyric endpoint is only called for the one candidate worth having, so a
 * miss costs a single request.
 */
class NeteaseSource(
    private val client: OkHttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : LyricsSource {

    override val id: LyricsSourceId = LyricsSourceId.NETEASE

    override suspend fun lookUp(identity: TrackIdentity): List<LyricsCandidate> {
        val hits = searchSongs("${identity.title} ${TextNormalizer.primaryArtist(identity.artist)}")
        // Only fetch lyrics for entries that could plausibly be this recording;
        // otherwise a popular title costs ten pointless requests.
        val plausible = hits
            .sortedBy { kotlin.math.abs(it.durationMs - identity.durationMs) }
            .take(MAX_LYRIC_FETCHES)
        return plausible.mapNotNull { hit -> withLyrics(hit) }
    }

    override suspend fun search(query: String, artist: String?): List<LyricsCandidate> {
        val term = if (artist.isNullOrBlank()) query else "$query ${TextNormalizer.primaryArtist(artist)}"
        return searchSongs(term).take(MAX_LYRIC_FETCHES).mapNotNull { withLyrics(it) }
    }

    private data class Hit(
        val id: Long,
        val name: String,
        val artist: String,
        val album: String?,
        val durationMs: Long,
    )

    private suspend fun searchSongs(term: String): List<Hit> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/search/get".toHttpUrl().newBuilder()
            .addQueryParameter("s", term)
            .addQueryParameter("type", "1")
            .addQueryParameter("limit", "10")
            .build()
        val body = fetch(url.toString()) ?: return@withContext emptyList()
        try {
            val songs = JSONObject(body).optJSONObject("result")?.optJSONArray("songs")
                ?: return@withContext emptyList()
            (0 until songs.length()).mapNotNull { index ->
                val song = songs.optJSONObject(index) ?: return@mapNotNull null
                val artists = song.optJSONArray("artists")
                val artistName = buildList {
                    for (a in 0 until (artists?.length() ?: 0)) {
                        artists?.optJSONObject(a)?.optString("name")?.let(::add)
                    }
                }.filter { it.isNotBlank() }.joinToString(", ")
                Hit(
                    id = song.optLong("id"),
                    name = song.optString("name"),
                    artist = artistName,
                    album = song.optJSONObject("album")?.optString("name")?.takeIf { it.isNotBlank() },
                    durationMs = song.optLong("duration"),
                )
            }.filter { it.id != 0L && it.name.isNotBlank() }
        } catch (e: JSONException) {
            throw LrclibClient.LookupFailure("Could not read the NetEase search reply", e)
        }
    }

    private suspend fun withLyrics(hit: Hit): LyricsCandidate? = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/song/lyric".toHttpUrl().newBuilder()
            .addQueryParameter("id", hit.id.toString())
            .addQueryParameter("lv", "1")
            .addQueryParameter("kv", "1")
            .addQueryParameter("tv", "-1")
            .build()
        val body = fetch(url.toString()) ?: return@withContext null
        val lrc = try {
            JSONObject(body).optJSONObject("lrc")?.optString("lyric")?.takeIf { it.isNotBlank() }
        } catch (e: JSONException) {
            throw LrclibClient.LookupFailure("Could not read the NetEase lyric reply", e)
        } ?: return@withContext null

        val cleaned = stripCredits(lrc)
        if (!LrcParser.hasTimestamps(cleaned)) return@withContext null

        LyricsCandidate(
            id = hit.id,
            trackName = hit.name,
            artistName = hit.artist,
            albumName = hit.album,
            durationSeconds = hit.durationMs / 1000.0,
            hasSynced = true,
            instrumental = false,
            syncedLyrics = cleaned,
            plainLyrics = null,
            source = LyricsSourceId.NETEASE,
        )
    }

    private fun fetch(url: String): String? {
        val request = Request.Builder()
            .url(url)
            // NetEase's public endpoints reject requests that do not look like
            // they came from its own web player.
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Referer", "$baseUrl/")
            .header("Accept", "application/json")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (response.code == 404) return null
                if (!response.isSuccessful) {
                    throw LrclibClient.LookupFailure("NetEase replied ${response.code}")
                }
                return response.body?.string()
            }
        } catch (e: LrclibClient.LookupFailure) {
            throw e
        } catch (e: IOException) {
            throw LrclibClient.LookupFailure("Could not reach NetEase", e)
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://music.163.com"

        private const val MAX_LYRIC_FETCHES = 3

        private const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"

        /**
         * NetEase LRCs usually open with credit lines timed at 00:00 — lyricist,
         * composer, arranger. Left in, they would be the first thing the car
         * shows for every song.
         */
        private val CREDIT_LINE = Regex(
            "^\\s*(?:\u4f5c\u8bcd|\u4f5c\u66f2|\u7f16\u66f2|\u5236\u4f5c\u4eba|\u51fa\u54c1\u4eba|" +
                "\u6df7\u97f3|\u6bcd\u5e26|\u548c\u58f0|\u914d\u5531|\u5409\u4ed6|\u8d1d\u65af|\u9f13|" +
                "\u5f26\u4e50|\u5f55\u97f3|\u7b56\u5212|\u76d1\u5236|\u7edf\u7b79|\u4f01\u5212|\u53d1\u884c|" +
                "lyricist|lyrics by|composer|composed by|written by|arranged by|arranger|" +
                "produced by|producer)\\s*[:\uff1a]",
            RegexOption.IGNORE_CASE,
        )

        fun stripCredits(lrc: String): String =
            lrc.lineSequence()
                .filterNot { line ->
                    val text = line.substringAfterLast(']').trim()
                    text.isNotEmpty() && CREDIT_LINE.containsMatchIn(text)
                }
                .joinToString("\n")
    }
}
