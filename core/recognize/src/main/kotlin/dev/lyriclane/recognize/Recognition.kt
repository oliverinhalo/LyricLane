package dev.lyriclane.recognize

/** A song identified from a recording of it. */
data class Recognition(
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long,
    /**
     * How far into the track the sample was taken, as the service heard it.
     * This is what makes synced lyrics possible from a microphone: without a
     * position there is nothing to sync to.
     */
    val playOffsetMs: Long,
    /** The service's own confidence, 0-100, where it reports one. */
    val score: Int?,
)

/** Why an identification attempt did not produce a song. */
sealed interface RecognitionFailure {
    /** The service answered, but did not recognise the audio. */
    data object NoMatch : RecognitionFailure

    /** No credentials configured, so nothing was sent. */
    data object NotConfigured : RecognitionFailure

    /** Microphone permission missing, or the recorder would not start. */
    data class CannotRecord(val reason: String) : RecognitionFailure

    /** Network or service problem; worth retrying. */
    data class ServiceError(val message: String) : RecognitionFailure
}

/** Outcome of one identification attempt. */
sealed interface RecognitionResult {
    data class Identified(val recognition: Recognition) : RecognitionResult
    data class Failed(val failure: RecognitionFailure) : RecognitionResult
}

/** A recorded chunk of audio, ready to be identified. */
data class AudioSample(
    /** Complete WAV bytes, header included. */
    val wav: ByteArray,
    /** `SystemClock.elapsedRealtime()` when recording started. */
    val startedAtElapsedRealtimeMs: Long,
    val durationMs: Long,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is AudioSample &&
                wav.contentEquals(other.wav) &&
                startedAtElapsedRealtimeMs == other.startedAtElapsedRealtimeMs &&
                durationMs == other.durationMs)

    override fun hashCode(): Int =
        wav.contentHashCode() * 31 + startedAtElapsedRealtimeMs.hashCode()
}

/** Something that can name the song in a recording. */
interface SongRecognizer {
    val isConfigured: Boolean
    suspend fun identify(sample: AudioSample): RecognitionResult
}
