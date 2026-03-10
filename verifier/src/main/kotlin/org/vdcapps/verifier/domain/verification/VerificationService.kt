package org.vdcapps.verifier.domain.verification

import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

/**
 * OID4VP 検証セッションを管理するドメインサービス。
 *
 * 1. [createSession] - 検証ページ表示時にセッションと nonce を生成。
 * 2. [findById] - Wallet が /verifier/request を取得したとき、および結果ポーリングに使用。
 * 3. [markVerified] - MdocVerifier が検証成功したとき。
 * 4. [markFailed] - 検証失敗したとき。
 */
class VerificationService(
    private val repository: VerificationSessionRepository,
    private val sessionTtl: Duration = 10.minutes,
) {
    suspend fun createSession(): VerificationSession {
        val now = Instant.now()
        val session =
            VerificationSession(
                id = UUID.randomUUID().toString(),
                nonce = generateSecureToken(),
                createdAt = now,
                expiresAt = now.plus(sessionTtl.toJavaDuration()),
            )
        repository.save(session)
        return session
    }

    suspend fun findById(id: String): VerificationSession? = repository.findById(id)

    suspend fun markResponseReceived(session: VerificationSession) {
        repository.update(session.copy(state = VerificationSession.State.RESPONSE_RECEIVED))
    }

    suspend fun markVerified(
        session: VerificationSession,
        result: VerificationResult,
    ) {
        repository.update(
            session.copy(
                state = VerificationSession.State.VERIFIED,
                result = result,
            ),
        )
    }

    suspend fun markFailed(
        session: VerificationSession,
        errorMessage: String,
    ) {
        repository.update(
            session.copy(
                state = VerificationSession.State.FAILED,
                errorMessage = errorMessage,
            ),
        )
    }

    private fun generateSecureToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
