package org.vdcapps.issuer.infrastructure.multipaz

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jwt.SignedJWT
import org.multipaz.crypto.EcPublicKey
import org.vdcapps.issuer.domain.credential.NonceStore

/**
 * OID4VCI proof JWT を検証し、holder の公開鍵を返すクラス。
 *
 * 検証内容:
 * - ヘッダーの alg が ES256 であること（alg:none 攻撃・アルゴリズム混同攻撃を防止）
 * - ヘッダーの jwk が EC P-256 鍵であること
 * - JWT 署名が jwk で正しく検証できること
 * - aud が baseUrl であること
 * - nonce の HMAC 署名が正しく有効期限内であること
 * - iat が 5 分以内であること
 */
class ProofJwtValidator(
    private val baseUrl: String,
    private val photoIdBuilder: PhotoIdBuilder,
    private val nonceStore: NonceStore,
) {
    fun validate(jwt: String): EcPublicKey {
        val signedJwt = SignedJWT.parse(jwt)
        val header = signedJwt.header
        val claims = signedJwt.jwtClaimsSet

        if (header.algorithm != JWSAlgorithm.ES256) {
            throw IllegalArgumentException("proof JWT の alg が不正です: ${header.algorithm}（ES256 のみ許可）")
        }

        val ecJwk =
            (header.jwk as? ECKey)
                ?: throw IllegalArgumentException("proof JWT header に EC JWK が含まれていません")

        if (!signedJwt.verify(ECDSAVerifier(ecJwk.toECPublicKey()))) {
            throw IllegalArgumentException("proof JWT の署名検証に失敗しました")
        }

        if (claims.audience.none { it == baseUrl }) {
            throw IllegalArgumentException("proof JWT の aud が不正です: ${claims.audience}")
        }

        val nonce =
            claims.getStringClaim("nonce")
                ?: throw IllegalArgumentException("proof JWT に nonce がありません")
        if (!nonceStore.verify(nonce)) {
            throw IllegalArgumentException("proof JWT の nonce が無効または期限切れです")
        }

        val iat = claims.issueTime
        if (iat == null || (System.currentTimeMillis() - iat.time) > 5 * 60 * 1000) {
            throw IllegalArgumentException("proof JWT が古すぎます (iat)")
        }

        val ecPoint = ecJwk.toECPublicKey().w
        return photoIdBuilder.ecPublicKeyFromCoordinates(
            ecPoint.affineX.toByteArray(),
            ecPoint.affineY.toByteArray(),
        )
    }
}
