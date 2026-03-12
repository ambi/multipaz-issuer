package org.vdcapps.issuer.domain.credential

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * OID4VCI c_nonce をステートレスに発行・検証するクラス。
 *
 * nonce = Base64Url(payload || HMAC-SHA256(payload))
 *   payload = exp(8 bytes, Long) || jti(16 bytes, random)
 *
 * - [generate]: HMAC 署名付き nonce を生成する（インメモリ保存なし）。
 * - [verify]: 署名と有効期限を検証する。
 *
 * one-time use は保証しないが、replay は IssuanceSession の状態遷移
 * (CREDENTIAL_ISSUED) と proof JWT の iat チェックで防止される。
 */
class NonceStore(
    private val ttlSeconds: Long = 600L,
    secretKey: ByteArray = randomBytes(32),
) {
    private val keySpec = SecretKeySpec(secretKey, "HmacSHA256")

    fun generate(): String {
        val exp = Instant.now().plusSeconds(ttlSeconds).epochSecond
        val payload =
            ByteBuffer
                .allocate(PAYLOAD_SIZE)
                .putLong(exp)
                .put(randomBytes(JTI_SIZE))
                .array()
        return base64url(payload + hmac(payload))
    }

    fun verify(nonce: String): Boolean {
        val bytes = runCatching { base64urlDecode(nonce) }.getOrElse { return false }
        if (bytes.size != PAYLOAD_SIZE + SIG_SIZE) return false
        val payload = bytes.copyOfRange(0, PAYLOAD_SIZE)
        val sig = bytes.copyOfRange(PAYLOAD_SIZE, PAYLOAD_SIZE + SIG_SIZE)
        if (!MessageDigest.isEqual(hmac(payload), sig)) return false
        val exp = ByteBuffer.wrap(payload).long
        return Instant.now().epochSecond <= exp
    }

    private fun hmac(data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").apply { init(keySpec) }.doFinal(data)

    companion object {
        private const val EXP_SIZE = 8
        private const val JTI_SIZE = 16
        private const val PAYLOAD_SIZE = EXP_SIZE + JTI_SIZE
        private const val SIG_SIZE = 32 // HMAC-SHA256

        private fun randomBytes(n: Int) = ByteArray(n).also { SecureRandom().nextBytes(it) }

        private fun base64url(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        private fun base64urlDecode(s: String): ByteArray = Base64.getUrlDecoder().decode(s)
    }
}
