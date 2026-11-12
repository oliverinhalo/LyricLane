package dev.lyriclane.recognize

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Response parsing, against the shape the service documents.
 *
 * `play_offset_ms` is the field that matters most: it is how far into the
 * track the sample was taken, and without it a microphone identification could
 * name the song but never sync to it.
 */
class AcrCloudResponseTest {

    private val success = """
        {
          "status": { "msg": "Success", "version": "1.0", "code": 0 },
          "result_type": 0,
          "metadata": {
            "timestamp_utc": "2026-11-12 09:00:00",
            "music": [
              {
                "acrid": "abc123",
                "title": "A Placeholder Song",
                "duration_ms": 213000,
                "play_offset_ms": 9040,
                "score": 92,
                "album": { "name": "A Placeholder Album" },
                "artists": [ { "name": "First Artist" }, { "name": "Second Artist" } ]
              }
            ]
          }
        }
    """.trimIndent()

    @Test
    fun `a match yields the song and where we are in it`() {
        val result = AcrCloudRecognizer.parse(success)

        assertThat(result).isInstanceOf(RecognitionResult.Identified::class.java)
        val song = (result as RecognitionResult.Identified).recognition
        assertThat(song.title).isEqualTo("A Placeholder Song")
        assertThat(song.artist).isEqualTo("First Artist, Second Artist")
        assertThat(song.album).isEqualTo("A Placeholder Album")
        assertThat(song.durationMs).isEqualTo(213_000)
        assertThat(song.playOffsetMs).isEqualTo(9_040)
        assertThat(song.score).isEqualTo(92)
    }

    @Test
    fun `the highest scoring match wins`() {
        val twoMatches = """
            {
              "status": { "code": 0 },
              "metadata": { "music": [
                { "title": "Weak Match", "score": 40, "artists": [{"name": "A"}] },
                { "title": "Strong Match", "score": 95, "artists": [{"name": "B"}] }
              ] }
            }
        """.trimIndent()

        val result = AcrCloudRecognizer.parse(twoMatches)

        assertThat((result as RecognitionResult.Identified).recognition.title)
            .isEqualTo("Strong Match")
    }

    @Test
    fun `no result is reported as nothing heard, not as a fault`() {
        val noResult = """{"status":{"msg":"No result","version":"1.0","code":1001}}"""

        val result = AcrCloudRecognizer.parse(noResult)

        assertThat((result as RecognitionResult.Failed).failure)
            .isEqualTo(RecognitionFailure.NoMatch)
    }

    @Test
    fun `an authentication problem is surfaced with its message`() {
        val denied = """{"status":{"msg":"Invalid signature","version":"1.0","code":3003}}"""

        val failure = (AcrCloudRecognizer.parse(denied) as RecognitionResult.Failed).failure

        assertThat(failure).isInstanceOf(RecognitionFailure.ServiceError::class.java)
        assertThat((failure as RecognitionFailure.ServiceError).message)
            .isEqualTo("Invalid signature")
    }

    @Test
    fun `a success with an empty match list is no match`() {
        val empty = """{"status":{"code":0},"metadata":{"music":[]}}"""

        assertThat((AcrCloudRecognizer.parse(empty) as RecognitionResult.Failed).failure)
            .isEqualTo(RecognitionFailure.NoMatch)
    }

    @Test
    fun `a match with no title is not trusted`() {
        val untitled = """{"status":{"code":0},"metadata":{"music":[{"score":90}]}}"""

        assertThat((AcrCloudRecognizer.parse(untitled) as RecognitionResult.Failed).failure)
            .isEqualTo(RecognitionFailure.NoMatch)
    }

    @Test
    fun `nonsense is reported rather than thrown`() {
        listOf("", "not json at all", "{", "[]").forEach { body ->
            val result = AcrCloudRecognizer.parse(body)
            assertThat(result).isInstanceOf(RecognitionResult.Failed::class.java)
        }
    }

    @Test
    fun `missing optional fields do not sink a usable match`() {
        val sparse = """
            {"status":{"code":0},"metadata":{"music":[
              {"title":"Bare Minimum","artists":[{"name":"Someone"}]}
            ]}}
        """.trimIndent()

        val song = (AcrCloudRecognizer.parse(sparse) as RecognitionResult.Identified).recognition

        assertThat(song.title).isEqualTo("Bare Minimum")
        assertThat(song.album).isNull()
        assertThat(song.durationMs).isEqualTo(0)
        assertThat(song.playOffsetMs).isEqualTo(0)
    }

    @Test
    fun `credentials must be complete before anything is sent`() {
        assertThat(AcrCloudConfig.EMPTY.isComplete).isFalse()
        assertThat(AcrCloudConfig("host", "key", "").isComplete).isFalse()
        assertThat(AcrCloudConfig("host", "", "secret").isComplete).isFalse()
        assertThat(AcrCloudConfig("host", "key", "secret").isComplete).isTrue()
    }
}
