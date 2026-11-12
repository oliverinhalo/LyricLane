package dev.lyriclane.recognize

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Records a short clip from the microphone.
 *
 * Mono 16-bit at 16 kHz: plenty for recognition — fingerprinting works on a
 * coarse spectral outline, not fidelity — and it keeps eight seconds well
 * under the service's size limit so the upload is quick on a phone that may be
 * on a weak signal in a moving car.
 *
 * [MediaRecorder.AudioSource.MIC] rather than one of the voice sources: those
 * apply noise suppression and echo cancellation tuned for speech, which is
 * exactly the processing that destroys music.
 */
class MicSampler(
    private val sampleRateHz: Int = SAMPLE_RATE_HZ,
) {

    class RecordingFailed(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * Records for [durationMs]. Requires `RECORD_AUDIO`; the caller checks it,
     * and a missing permission surfaces as [RecordingFailed] rather than a
     * crash deep in the audio stack.
     */
    @SuppressLint("MissingPermission")
    suspend fun record(durationMs: Long): AudioSample = withContext(Dispatchers.IO) {
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            throw RecordingFailed("This device will not record at ${sampleRateHz} Hz")
        }

        val bufferBytes = maxOf(minBuffer, sampleRateHz)  // ~0.5 s of headroom
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRateHz,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
        } catch (e: SecurityException) {
            throw RecordingFailed("Microphone permission is not granted", e)
        } catch (e: IllegalArgumentException) {
            throw RecordingFailed("Could not open the microphone", e)
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw RecordingFailed("The microphone is unavailable, perhaps in use by another app")
        }

        val targetBytes = (sampleRateHz * BYTES_PER_SAMPLE * durationMs / 1000).toInt()
        val pcm = ByteArray(targetBytes)
        var filled = 0
        val startedAt: Long

        try {
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw RecordingFailed("The microphone did not start, perhaps in use by another app")
            }
            startedAt = SystemClock.elapsedRealtime()
            while (filled < targetBytes) {
                coroutineContext.ensureActive()
                val read = recorder.read(pcm, filled, targetBytes - filled)
                if (read <= 0) {
                    throw RecordingFailed("The microphone stopped returning audio")
                }
                filled += read
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }

        AudioSample(
            wav = WavEncoder.encode(pcm.copyOf(filled), sampleRateHz),
            // Anchored to the *start* of the clip, because that is the moment
            // the service's reported play offset refers to.
            startedAtElapsedRealtimeMs = startedAt,
            durationMs = durationMs,
        )
    }

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
        private const val BYTES_PER_SAMPLE = 2
    }
}
