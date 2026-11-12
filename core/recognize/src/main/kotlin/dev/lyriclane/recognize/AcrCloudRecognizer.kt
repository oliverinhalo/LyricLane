package dev.lyriclane.recognize

import java.io.IOException
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject

/** Credentials for an ACRCloud audio-recognition project. */
data class AcrCloudConfig(
    val host: String,
    val accessKey: String,
    val accessSecret: String,
) {
    val isComplete: Boolean
        get() = host.isNotBlank() && accessKey.isNotBlank() && accessSecret.isNotBlank()

    companion object {
        val EMPTY = AcrCloudConfig("", "", "")
    }
}

/**
 * Identifies a song from a recording using ACRCloud.
 *
 * Chosen over the alternatives because it returns `play_offset_ms` — how far
 * into the track the sample was taken. Knowing *what* is playing is only half
 * of what synced lyrics need; without a position there is nothing to sync to.
 *
 * Credentials are the user's own. There is no free, unmetered recognition
 * service, and shipping a shared key in a sideloaded app would be neither
 * legitimate nor durable.
 */
class AcrCloudRecognizer(
    private val client: OkHttpClient,
    private val configProvider: () -> AcrCloudConfig,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) : SongRecognizer {

    override val isConfigured: Boolean get() = configProvider().isComplete

    override suspend fun identify(sample: AudioSample): RecognitionResult =
        withContext(Dispatchers.IO) {
            val config = configProvider()
            if (!config.isComplete) return@withContext RecognitionResult.Failed(
                RecognitionFailure.NotConfigured
            )

            val timestamp = nowSeconds().toString()
            val signature = AcrSignature.sign(
                accessKey = config.accessKey,
                accessSecret = config.accessSecret,
                timestamp = timestamp,
            )

            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "sample",
                    "sample.wav",
                    sample.wav.toRequestBody("audio/wav".toMediaType()),
                )
                .addFormDataPart("access_key", config.accessKey)
                .addFormDataPart("sample_bytes", sample.wav.size.toString())
                .addFormDataPart("timestamp", timestamp)
                .addFormDataPart("signature", signature)
                .addFormDataPart("data_type", DATA_TYPE)
                .addFormDataPart("signature_version", SIGNATURE_VERSION)
                .build()

            val request = Request.Builder()
                .url("https://${config.host.trim().removeSuffix("/")}$PATH")
                .post(body)
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext RecognitionResult.Failed(
                            RecognitionFailure.ServiceError("Service replied ${response.code}")
                        )
                    }
                    parse(response.body?.string().orEmpty())
                }
            } catch (e: IOException) {
                RecognitionResult.Failed(
                    RecognitionFailure.ServiceError(e.message ?: "Could not reach the service")
                )
            }
        }

    companion object {
        const val PATH = "/v1/identify"
        const val DATA_TYPE = "audio"
        const val SIGNATURE_VERSION = "1"

        /** Clips of about this length identify reliably without a slow upload. */
        const val RECOMMENDED_SAMPLE_MS = 8_000L

        /** `status.code` zero means a match; anything else is a message to show. */
        private const val STATUS_SUCCESS = 0

        fun parse(json: String): RecognitionResult {
            if (json.isBlank()) {
                return RecognitionResult.Failed(RecognitionFailure.ServiceError("Empty reply"))
            }
            return try {
                val root = JSONObject(json)
                val status = root.optJSONObject("status")
                val code = status?.optInt("code", -1) ?: -1
                if (code != STATUS_SUCCESS) {
                    val message = status?.optString("msg").orEmpty()
                    return when {
                        // "No result" is the service working correctly and
                        // hearing nothing it knows, not a fault to report.
                        message.contains("no result", ignoreCase = true) ->
                            RecognitionResult.Failed(RecognitionFailure.NoMatch)

                        message.isNotBlank() ->
                            RecognitionResult.Failed(RecognitionFailure.ServiceError(message))

                        else -> RecognitionResult.Failed(RecognitionFailure.NoMatch)
                    }
                }

                val music = root.optJSONObject("metadata")?.optJSONArray("music")
                val best = (0 until (music?.length() ?: 0))
                    .mapNotNull { music?.optJSONObject(it) }
                    .maxByOrNull { it.optInt("score", 0) }
                    ?: return RecognitionResult.Failed(RecognitionFailure.NoMatch)

                val title = best.optString("title").takeIf { it.isNotBlank() }
                    ?: return RecognitionResult.Failed(RecognitionFailure.NoMatch)

                val artists = best.optJSONArray("artists")
                val artist = (0 until (artists?.length() ?: 0))
                    .mapNotNull { artists?.optJSONObject(it)?.optString("name") }
                    .filter { it.isNotBlank() }
                    .joinToString(", ")

                RecognitionResult.Identified(
                    Recognition(
                        title = title,
                        artist = artist,
                        album = best.optJSONObject("album")?.optString("name")
                            ?.takeIf { it.isNotBlank() },
                        durationMs = best.optLong("duration_ms", 0L),
                        playOffsetMs = best.optLong("play_offset_ms", 0L),
                        score = best.optInt("score", -1).takeIf { it >= 0 },
                    )
                )
            } catch (e: JSONException) {
                RecognitionResult.Failed(
                    RecognitionFailure.ServiceError("Could not read the reply")
                )
            }
        }
    }
}

/**
 * ACRCloud request signing.
 *
 * The string to sign is six values joined by newlines, in this order, and the
 * signature is base64 of its HMAC-SHA1 under the access secret.
 */
object AcrSignature {

    fun stringToSign(
        accessKey: String,
        timestamp: String,
        httpMethod: String = "POST",
        path: String = AcrCloudRecognizer.PATH,
        dataType: String = AcrCloudRecognizer.DATA_TYPE,
        signatureVersion: String = AcrCloudRecognizer.SIGNATURE_VERSION,
    ): String = listOf(
        httpMethod,
        path,
        accessKey,
        dataType,
        signatureVersion,
        timestamp,
    ).joinToString("\n")

    fun sign(accessKey: String, accessSecret: String, timestamp: String): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(accessSecret.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        val raw = mac.doFinal(stringToSign(accessKey, timestamp).toByteArray(Charsets.UTF_8))
        // java.util.Base64 rather than android.util.Base64: it exists from API
        // 26, produces the same standard encoding, and works in unit tests.
        return Base64.getEncoder().encodeToString(raw)
    }
}
