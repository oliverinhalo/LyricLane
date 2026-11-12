package dev.lyriclane.recognize

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The signature is the one part of this integration that fails silently: a
 * wrong one comes back as a plain authentication error with no hint which of
 * the six fields is out of place. The expected value below was produced by an
 * independent HMAC-SHA1 implementation, so this pins the layout rather than
 * agreeing with itself.
 */
class AcrSignatureTest {

    private val accessKey = "test-access-key"
    private val accessSecret = "test-access-secret"
    private val timestamp = "1700000000"

    @Test
    fun `string to sign is the six fields joined by newlines, in order`() {
        val signed = AcrSignature.stringToSign(accessKey, timestamp)

        assertThat(signed).isEqualTo("POST\n/v1/identify\ntest-access-key\naudio\n1\n1700000000")
        assertThat(signed.lines()).hasSize(6)
    }

    @Test
    fun `signature matches an independent HMAC-SHA1 of that string`() {
        val signature = AcrSignature.sign(accessKey, accessSecret, timestamp)

        assertThat(signature).isEqualTo("Mg53J3jzxfq7QUv54h6/9j8aZ20=")
    }

    @Test
    fun `base64 is unwrapped, because a newline would corrupt the form field`() {
        val signature = AcrSignature.sign(accessKey, accessSecret, timestamp)

        assertThat(signature).doesNotContain("\n")
        assertThat(signature).doesNotContain("\r")
    }

    @Test
    fun `a different timestamp gives a different signature`() {
        val first = AcrSignature.sign(accessKey, accessSecret, timestamp)
        val second = AcrSignature.sign(accessKey, accessSecret, "1700000001")

        assertThat(first).isNotEqualTo(second)
    }

    @Test
    fun `a different secret gives a different signature`() {
        val first = AcrSignature.sign(accessKey, accessSecret, timestamp)
        val second = AcrSignature.sign(accessKey, "other-secret", timestamp)

        assertThat(first).isNotEqualTo(second)
    }
}
