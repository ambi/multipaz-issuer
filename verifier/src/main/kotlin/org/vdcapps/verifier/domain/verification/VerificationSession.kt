package org.vdcapps.verifier.domain.verification

import java.time.Instant

/**
 * OID4VP 検証セッション。
 *
 * 状態遷移: PENDING → RESPONSE_RECEIVED → VERIFIED / FAILED
 *
 * - PENDING: QR コード表示中。Wallet が /verifier/request を取得して /verifier/response を POST するのを待機。
 * - RESPONSE_RECEIVED: Wallet から vp_token を受け取り、MdocVerifier で検証中または検証済み。
 * - VERIFIED: 署名・有効期限の検証成功。result にクレームが入る。
 * - FAILED: 検証失敗。errorMessage にエラー内容が入る。
 */
data class VerificationSession(
    val id: String,
    val nonce: String,
    val state: State = State.PENDING,
    val result: VerificationResult? = null,
    val errorMessage: String? = null,
    val createdAt: Instant,
    val expiresAt: Instant,
) {
    enum class State { PENDING, RESPONSE_RECEIVED, VERIFIED, FAILED }
}
