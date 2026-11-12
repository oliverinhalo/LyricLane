package dev.lyriclane.recognize

import java.io.ByteArrayOutputStream

/**
 * Wraps raw PCM in a WAV container.
 *
 * Recognition services take ordinary audio files rather than raw samples, and
 * a WAV header is 44 bytes of arithmetic — cheaper than pulling in an encoder
 * and lossless, which matters when the input is already a noisy car cabin.
 */
object WavEncoder {

    const val HEADER_BYTES = 44

    fun encode(
        pcm: ByteArray,
        sampleRateHz: Int,
        channels: Int = 1,
        bitsPerSample: Int = 16,
    ): ByteArray {
        val byteRate = sampleRateHz * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val out = ByteArrayOutputStream(HEADER_BYTES + pcm.size)

        out.writeAscii("RIFF")
        out.writeIntLe(36 + pcm.size)          // file size minus the first 8 bytes
        out.writeAscii("WAVE")

        out.writeAscii("fmt ")
        out.writeIntLe(16)                     // PCM subchunk size
        out.writeShortLe(1)                    // format: uncompressed PCM
        out.writeShortLe(channels)
        out.writeIntLe(sampleRateHz)
        out.writeIntLe(byteRate)
        out.writeShortLe(blockAlign)
        out.writeShortLe(bitsPerSample)

        out.writeAscii("data")
        out.writeIntLe(pcm.size)
        out.write(pcm)

        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeAscii(value: String) {
        value.forEach { write(it.code) }
    }

    private fun ByteArrayOutputStream.writeIntLe(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 24) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeShortLe(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }
}
