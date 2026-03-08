package org.multipaz.issuer.domain.credential

import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

/**
 * OID4VCI Pre-Authorized Code フローを管理するドメインサービス。
 *
 * 1. [createSession] - ブラウザ側で証明書発行を開始する。pre-authorized_code を生成してセッションを作成。
 * 2. [exchangePreAuthorizedCode] - Wallet が /token を呼んだとき。access_token を発行。
 * 3. [findSessionByAccessToken] - Wallet が /credential を呼んだとき。セッションを取得。
 * 4. [markCredentialIssued] - 証明書発行後にセッション状態を更新。
 *
 * c_nonce は [NonceStore] で独立して管理する。
 */
class CredentialIssuanceService(
    private val repository: IssuanceSessionRepository,
    private val sessionTtl: Duration = 10.minutes,
    private val tokenTtl: Duration = 5.minutes,
) {
    suspend fun createSession(credential: PhotoIdCredential): IssuanceSession {
        val now = Instant.now()
        val session = IssuanceSession(
            id = UUID.randomUUID().toString(),
            preAuthorizedCode = generateSecureToken(),
            credential = credential,
            createdAt = now,
            expiresAt = now.plus(sessionTtl.toJavaDuration()),
        )
        repository.save(session)
        return session
    }

    /**
     * pre-authorized_code を検証し、access_token を発行する。
     * @return 発行した access_token とセッション。コードが無効・期限切れの場合は null。
     */
    suspend fun exchangePreAuthorizedCode(code: String): Pair<String, IssuanceSession>? {
        val session = repository.findByPreAuthorizedCode(code) ?: return null
        if (session.state != IssuanceSession.State.PENDING) return null
        if (Instant.now().isAfter(session.expiresAt)) {
            repository.delete(session.id)
            return null
        }

        val updated = session.copy(
            accessToken = generateSecureToken(),
            state = IssuanceSession.State.TOKEN_ISSUED,
        )
        repository.update(updated)
        return updated.accessToken!! to updated
    }

    suspend fun findSessionByAccessToken(token: String): IssuanceSession? =
        repository.findByAccessToken(token)

    suspend fun findSessionById(id: String): IssuanceSession? = repository.findById(id)

    suspend fun markCredentialIssued(session: IssuanceSession) {
        repository.update(session.copy(state = IssuanceSession.State.CREDENTIAL_ISSUED))
    }

    private fun generateSecureToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
