package dev.lyriclane.recognize

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WavEncoderTest {

    private fun ByteArray.ascii(from: Int, length: Int) =
        String(copyOfRange(from, from + length), Charsets.US_ASCII)

    private fun ByteArray.intLe(at: Int): Int =
        (this[at].toInt() and 0xFF) or
            ((this[at + 1].toInt() and 0xFF) shl 8) or
            ((this[at + 2].toInt() and 0xFF) shl 16) or
            ((this[at + 3].toInt() and 0xFF) shl 24)

    private fun ByteArray.shortLe(at: Int): Int =
        (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8)

    private val pcm = ByteArray(1000) { (it % 256).toByte() }

    @Test
    fun `the container is a well formed WAV`() {
        val wav = WavEncoder.encode(pcm, sampleRateHz = 16_000)

        assertThat(wav.ascii(0, 4)).isEqualTo("RIFF")
        assertThat(wav.ascii(8, 4)).isEqualTo("WAVE")
        assertThat(wav.ascii(12, 4)).isEqualTo("fmt ")
        assertThat(wav.ascii(36, 4)).isEqualTo("data")
    }

    @Test
    fun `the declared sizes match the payload`() {
        val wav = WavEncoder.encode(pcm, sampleRateHz = 16_000)

        assertThat(wav.size).isEqualTo(WavEncoder.HEADER_BYTES + pcm.size)
        // RIFF size counts everything after the first eight bytes.
        assertThat(wav.intLe(4)).isEqualTo(wav.size - 8)
        assertThat(wav.intLe(40)).isEqualTo(pcm.size)
    }

    @Test
    fun `the format block describes 16-bit mono PCM`() {
        val wav = WavEncoder.encode(pcm, sampleRateHz = 16_000)

        assertThat(wav.intLe(16)).isEqualTo(16)        // PCM subchunk size
        assertThat(wav.shortLe(20)).isEqualTo(1)       // uncompressed
        assertThat(wav.shortLe(22)).isEqualTo(1)       // channels
        assertThat(wav.intLe(24)).isEqualTo(16_000)    // sample rate
        assertThat(wav.intLe(28)).isEqualTo(32_000)    // byte rate: 16k * 1 * 2
        assertThat(wav.shortLe(32)).isEqualTo(2)       // block align
        assertThat(wav.shortLe(34)).isEqualTo(16)      // bits per sample
    }

    @Test
    fun `the audio survives unchanged`() {
        val wav = WavEncoder.encode(pcm, sampleRateHz = 16_000)

        assertThat(wav.copyOfRange(WavEncoder.HEADER_BYTES, wav.size)).isEqualTo(pcm)
    }

    @Test
    fun `stereo and other rates are described correctly`() {
        val wav = WavEncoder.encode(pcm, sampleRateHz = 44_100, channels = 2)

        assertThat(wav.shortLe(22)).isEqualTo(2)
        assertThat(wav.intLe(24)).isEqualTo(44_100)
        assertThat(wav.intLe(28)).isEqualTo(44_100 * 2 * 2)
        assertThat(wav.shortLe(32)).isEqualTo(4)
    }

    @Test
    fun `an empty recording still produces a valid header`() {
        val wav = WavEncoder.encode(ByteArray(0), sampleRateHz = 16_000)

        assertThat(wav.size).isEqualTo(WavEncoder.HEADER_BYTES)
        assertThat(wav.intLe(40)).isEqualTo(0)
    }

    @Test
    fun `eight seconds at the capture rate stays well under the upload limit`() {
        val eightSeconds = ByteArray(MicSampler.SAMPLE_RATE_HZ * 2 * 8)

        val wav = WavEncoder.encode(eightSeconds, MicSampler.SAMPLE_RATE_HZ)

        // The service asks for samples under about a megabyte.
        assertThat(wav.size).isLessThan(1_000_000)
    }
}
