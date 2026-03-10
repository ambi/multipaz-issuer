package org.vdcapps.verifier.application

import org.vdcapps.verifier.domain.verification.VerificationResult
import org.vdcapps.verifier.domain.verification.VerificationService
import org.vdcapps.verifier.domain.verification.VerificationSession
import org.vdcapps.verifier.infrastructure.multipaz.MdocVerifier
import java.util.Base64

/**
 * 証明書検証のユースケース。
 *
 * - [startVerification]: 検証ページ表示時に呼ぶ。VerificationSession を生成する。
 * - [processVpToken]: Wallet から vp_token を受け取ったとき。MdocVerifier に委譲して結果を保存する。
 */
class VerifyCredentialUseCase(
    private val verificationService: VerificationService,
    private val mdocVerifier: MdocVerifier,
) {
    suspend fun startVerification(): VerificationSession = verificationService.createSession()

    /**
     * OID4VP レスポンスの vp_token を検証する。
     *
     * @param session PENDING 状態のセッション
     * @param vpToken base64url エンコードされた DeviceResponse バイト列
     * @return 検証成功時は [VerificationResult]。失敗時は例外をスローしてセッションを FAILED に遷移させる。
     */
    suspend fun processVpToken(
        session: VerificationSession,
        vpToken: String,
    ): VerificationResult {
        verificationService.markResponseReceived(session)

        return try {
            val deviceResponseBytes =
                Base64.getUrlDecoder().decode(
                    vpToken.trimEnd('='), // パディングが付いている場合も許容
                )
            val result = mdocVerifier.verify(deviceResponseBytes)
            verificationService.markVerified(session, result)
            result
        } catch (e: Exception) {
            verificationService.markFailed(session, e.message ?: "検証に失敗しました")
            throw e
        }
    }
}
