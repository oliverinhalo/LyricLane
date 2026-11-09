package dev.lyriclane.lyrics

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * LRCLIB client. Two endpoints and eleven flat fields, so there is no
 * serialization library here: Android ships `org.json`, and using it keeps one
 * fewer dependency (and one fewer Kotlin-metadata version) in the build.
 *
 * `/api/get` is an exact-ish lookup and is tried first. It is lenient about
 * duration and can hand back a different cut of the same song, so its answer is
 * still scored against the track before it is trusted. `/api/search` is the
 * fallback and returns complete records — lyrics included — so a search hit
 * needs no second request.
 */
class LrclibClient(
    private val client: OkHttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val userAgent: String = DEFAULT_USER_AGENT,
) {

    /** Thrown for anything a retry might fix. */
    class LookupFailure(message: String, cause: Throwable? = null) : IOException(message, cause)

    /** Null means LRCLIB answered 404 — a definite "nothing for this track". */
    suspend fun get(identity: TrackIdentity): LyricsCandidate? = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/get".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", identity.title)
            .addQueryParameter("artist_name", TextNormalizer.primaryArtist(identity.artist))
            .apply { identity.album?.let { addQueryParameter("album_name", it) } }
            .addQueryParameter("duration", identity.durationSeconds.toString())
            .build()

        val body = fetch(url.toString()) ?: return@withContext null
        try {
            JSONObject(body).toCandidate()
        } catch (e: JSONException) {
            throw LookupFailure("Could not read the LRCLIB reply", e)
        }
    }

    /** Free-text search, in LRCLIB's own ranking. */
    suspend fun search(
        trackName: String,
        artistName: String?,
    ): List<LyricsCandidate> = withContext(Dispatchers.IO) {
        val builder = "$baseUrl/api/search".toHttpUrl().newBuilder()
        if (artistName.isNullOrBlank()) {
            builder.addQueryParameter("q", trackName)
        } else {
            builder.addQueryParameter("track_name", trackName)
            builder.addQueryParameter("artist_name", TextNormalizer.primaryArtist(artistName))
        }

        val body = fetch(builder.build().toString()) ?: return@withContext emptyList()
        try {
            val array = JSONArray(body)
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.toCandidate()
            }
        } catch (e: JSONException) {
            throw LookupFailure("Could not read the LRCLIB search reply", e)
        }
    }

    private fun JSONObject.toCandidate(): LyricsCandidate {
        val synced = optStringOrNull("syncedLyrics")
        val plain = optStringOrNull("plainLyrics")
        return LyricsCandidate(
            id = optLong("id", 0L),
            trackName = optStringOrNull("trackName") ?: optStringOrNull("name").orEmpty(),
            artistName = optStringOrNull("artistName").orEmpty(),
            albumName = optStringOrNull("albumName"),
            durationSeconds = optDouble("duration", 0.0).takeIf { !it.isNaN() } ?: 0.0,
            hasSynced = LrcParser.hasTimestamps(synced),
            instrumental = optBoolean("instrumental", false),
            syncedLyrics = synced,
            plainLyrics = plain,
        )
    }

    /** `optString` turns JSON null into "null"; this does not. */
    private fun JSONObject.optStringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).takeIf { it.isNotBlank() }
    }

    /** Null on 404; throws [LookupFailure] on anything else that went wrong. */
    private fun fetch(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (response.code == 404) return null
                if (!response.isSuccessful) {
                    throw LookupFailure("LRCLIB replied ${response.code}")
                }
                return response.body?.string()
            }
        } catch (e: LookupFailure) {
            throw e
        } catch (e: IOException) {
            throw LookupFailure("Could not reach LRCLIB", e)
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://lrclib.net"

        /**
         * LRCLIB asks clients to identify themselves and link back rather than
         * send a browser-like agent.
         */
        const val DEFAULT_USER_AGENT =
            "LyricLane/0.2.0 (https://github.com/oliverinhalo/LyricLane)"
    }
}
