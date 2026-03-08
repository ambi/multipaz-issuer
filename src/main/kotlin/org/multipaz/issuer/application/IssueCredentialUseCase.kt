package org.multipaz.issuer.application

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import org.multipaz.crypto.EcPublicKey
import org.multipaz.issuer.domain.credential.CredentialIssuanceService
import org.multipaz.issuer.domain.credential.IssuanceSession
import org.multipaz.issuer.domain.credential.PhotoIdCredential
import org.multipaz.issuer.domain.identity.EntraUser
import org.multipaz.issuer.infrastructure.multipaz.PhotoIdBuilder
import java.time.ZoneOffset
import java.util.UUID

/**
 * 証明書発行のユースケース。ドメインサービスとインフラ層を橋渡しする。
 *
 * - [startIssuance]: ユーザーがブラウザで「発行」ボタンを押したときに呼ぶ。
 *   EntraUser の情報と入力された生年月日から [IssuanceSession] を生成する。
 *
 * - [issueCredential]: OID4VCI /credential エンドポイントから呼ぶ。
 *   Wallet が提示した proof JWT を検証し、署名済み mdoc を返す。
 */
class IssueCredentialUseCase(
    private val issuanceService: CredentialIssuanceService,
    private val photoIdBuilder: PhotoIdBuilder,
    private val issuingCountry: String,
    private val issuingAuthority: String,
    private val validityDays: Int,
) {
    suspend fun startIssuance(
        user: EntraUser,
        birthDate: LocalDate,
        familyName: String,
        givenName: String,
    ): IssuanceSession {
        val javaToday = java.time.LocalDate.now(ZoneOffset.UTC)
        val today = LocalDate(javaToday.year, javaToday.monthValue, javaToday.dayOfMonth)
        val credential = PhotoIdCredential(
            familyName = familyName,
            givenName = givenName,
            birthDate = birthDate,
            portrait = user.photo,
            documentNumber = UUID.randomUUID().toString().replace("-", "").take(12).uppercase(),
            issuingCountry = issuingCountry,
            issuingAuthority = issuingAuthority,
            issueDate = today,
            expiryDate = today.plus(validityDays, DateTimeUnit.DAY),
        )
        return issuanceService.createSession(credential)
    }

    /**
     * Wallet の proof JWT から公開鍵を抽出し、署名済み mdoc を返す。
     * @param session TOKEN_ISSUED 状態のセッション
     * @param holderPublicKey proof JWT から取得した holder の EC 公開鍵
     * @return base64url エンコードされた IssuerSigned CBOR
     */
    suspend fun issueCredential(
        session: IssuanceSession,
        holderPublicKey: EcPublicKey,
    ): String {
        val credential = photoIdBuilder.buildCredential(session.credential, holderPublicKey)
        issuanceService.markCredentialIssued(session)
        return credential
    }
}
