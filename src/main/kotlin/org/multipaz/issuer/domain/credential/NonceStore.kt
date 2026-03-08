package org.multipaz.issuer.domain.credential

import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * OID4VCI c_nonce を管理するストア。
 *
 * OID4VCI の /nonce エンドポイントは認証なし（OID4VCI §7.3）で呼ばれるため、
 * nonce はセッションと独立して管理する。
 *
 * - [generate]: 新しい nonce を生成して TTL 付きで保存する。
 * - [consume]: nonce を検証して削除する（one-time use）。
 */
class NonceStore(private val ttlSeconds: Long = 600L) {
    private val store = ConcurrentHashMap<String, Instant>()

    fun generate(): String {
        cleanup()
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        store[nonce] = Instant.now().plusSeconds(ttlSeconds)
        return nonce
    }

    fun consume(nonce: String): Boolean {
        val expiry = store.remove(nonce) ?: return false
        return Instant.now().isBefore(expiry)
    }

    private fun cleanup() {
        val now = Instant.now()
        store.entries.removeIf { it.value.isBefore(now) }
    }
}
