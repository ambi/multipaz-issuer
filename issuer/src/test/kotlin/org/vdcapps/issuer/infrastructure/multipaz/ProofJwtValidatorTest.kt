package org.vdcapps.issuer.infrastructure.multipaz

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.mockk.mockk
import org.vdcapps.issuer.domain.credential.NonceStore
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * ProofJwtValidator のセキュリティ要件を検証する単体テスト。
 * アルゴリズム混同・nonce 偽造・aud 不一致などの攻撃シナリオを網羅する。
 */
class ProofJwtValidatorTest {
    private val baseUrl = "http://localhost:8080"
    private val nonceStore = NonceStore()
    private val photoIdBuilder = mockk<PhotoIdBuilder>(relaxed = true)
    private val validator = ProofJwtValidator(baseUrl, photoIdBuilder, nonceStore)

    // テスト用 P-256 EC 鍵ペア（正常系で使用）
    private val ecKey = ECKeyGenerator(com.nimbusds.jose.jwk.Curve.P_256).generate()

    @Test
    fun `alg が ES256 以外の場合は拒否される`() {
        val rsaKey = RSAKeyGenerator(2048).generate()
        val jwt =
            SignedJWT(
                JWSHeader.Builder(JWSAlgorithm.RS256).build(),
                buildClaims(nonceStore.generate()),
            )
        jwt.sign(RSASSASigner(rsaKey))
        assertFailsWith<IllegalArgumentException>("RS256 は拒否されるべき") {
            validator.validate(jwt.serialize())
        }
    }

    @Test
    fun `header に JWK が含まれない場合は拒否される`() {
        val jwt =
            SignedJWT(
                // jwk を省略した JWS ヘッダー
                JWSHeader.Builder(JWSAlgorithm.ES256).build(),
                buildClaims(nonceStore.generate()),
            )
        jwt.sign(ECDSASigner(ecKey))
        assertFailsWith<IllegalArgumentException>("JWK なしは拒否されるべき") {
            validator.validate(jwt.serialize())
        }
    }

    @Test
    fun `署名が不正な場合は拒否される`() {
        val anotherKey = ECKeyGenerator(com.nimbusds.jose.jwk.Curve.P_256).generate()
        // ヘッダーの JWK は ecKey だが、署名は anotherKey で実施 → 検証失敗
        val jwt =
            SignedJWT(
                JWSHeader.Builder(JWSAlgorithm.ES256).jwk(ecKey.toPublicJWK()).build(),
                buildClaims(nonceStore.generate()),
            )
        jwt.sign(ECDSASigner(anotherKey)) // 意図的に別の鍵で署名
        assertFailsWith<IllegalArgumentException>("不正な署名は拒否されるべき") {
            validator.validate(jwt.serialize())
        }
    }

    @Test
    fun `aud が baseUrl と一致しない場合は拒否される`() {
        val jwt = buildValidJwt(aud = "https://attacker.example.com", nonce = nonceStore.generate())
        assertFailsWith<IllegalArgumentException>("不正な aud は拒否されるべき") {
            validator.validate(jwt.serialize())
        }
    }

    @Test
    fun `nonce が無効な場合は拒否される`() {
        val jwt = buildValidJwt(nonce = "invalid-nonce-value")
        assertFailsWith<IllegalArgumentException>("無効な nonce は拒否されるべき") {
            validator.validate(jwt.serialize())
        }
    }

    @Test
    fun `nonce が別の NonceStore で生成されたものは拒否される`() {
        // 別キーの NonceStore で生成した nonce → HMAC 不一致
        val otherStore = NonceStore()
        val jwt = buildValidJwt(nonce = otherStore.generate())
        assertFailsWith<IllegalArgumentException>("他インスタンス生成 nonce は拒否されるべき") {
            validator.validate(jwt.serialize())
        }
    }

    @Test
    fun `iat が 5 分以上前の場合は拒否される`() {
        val sixMinutesAgo = Date(System.currentTimeMillis() - 6 * 60 * 1000)
        val jwt = buildValidJwt(iat = sixMinutesAgo, nonce = nonceStore.generate())
        assertFailsWith<IllegalArgumentException>("古い iat は拒否されるべき") {
            validator.validate(jwt.serialize())
        }
    }

    @Test
    fun `nonce が空文字列の場合は拒否される`() {
        val jwt = buildValidJwt(nonce = "")
        assertFailsWith<IllegalArgumentException>("空 nonce は拒否されるべき") {
            validator.validate(jwt.serialize())
        }
    }

    // ---- ヘルパー ----

    private fun buildValidJwt(
        aud: String = baseUrl,
        nonce: String = nonceStore.generate(),
        iat: Date = Date(),
    ): SignedJWT {
        val jwt =
            SignedJWT(
                JWSHeader.Builder(JWSAlgorithm.ES256).jwk(ecKey.toPublicJWK()).build(),
                buildClaims(nonce, aud, iat),
            )
        jwt.sign(ECDSASigner(ecKey))
        return jwt
    }

    private fun buildClaims(
        nonce: String,
        aud: String = baseUrl,
        iat: Date = Date(),
    ): JWTClaimsSet =
        JWTClaimsSet
            .Builder()
            .audience(aud)
            .claim("nonce", nonce)
            .issueTime(iat)
            .build()
}
