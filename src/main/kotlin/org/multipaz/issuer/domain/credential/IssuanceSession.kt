package org.multipaz.issuer.domain.credential

import java.time.Instant

/**
 * OID4VCI Pre-Authorized Code フローの発行セッション。
 *
 * 状態遷移: PENDING → TOKEN_ISSUED → CREDENTIAL_ISSUED
 *
 * - PENDING: ブラウザで QR コード表示中。Wallet が /token を呼ぶのを待機。
 * - TOKEN_ISSUED: access_token と c_nonce を発行済み。Wallet が /credential を呼ぶのを待機。
 * - CREDENTIAL_ISSUED: 証明書発行完了。
 */
data class IssuanceSession(
    val id: String,
    val preAuthorizedCode: String,
    val credential: PhotoIdCredential,
    val accessToken: String? = null,
    val cNonce: String,
    val cNonceExpiresAt: Instant,
    val state: State = State.PENDING,
    val createdAt: Instant,
    val expiresAt: Instant,
) {
    enum class State { PENDING, TOKEN_ISSUED, CREDENTIAL_ISSUED }
}
