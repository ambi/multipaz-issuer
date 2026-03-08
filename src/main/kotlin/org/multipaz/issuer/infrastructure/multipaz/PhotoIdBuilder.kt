package org.multipaz.issuer.infrastructure.multipaz

import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.RawCbor
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.toDataItem
import org.multipaz.cbor.toDataItemFullDate
import org.multipaz.cose.Cose
import org.multipaz.cose.CoseLabel
import org.multipaz.cose.CoseNumberLabel
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.EcPublicKeyDoubleCoordinate
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.crypto.toEcPrivateKey
import org.multipaz.issuer.domain.credential.PhotoIdCredential
import org.multipaz.mdoc.issuersigned.buildIssuerNamespaces
import org.multipaz.mdoc.mso.MobileSecurityObject
import java.security.interfaces.ECPrivateKey

/**
 * Multipaz ライブラリを使って ISO/IEC TS 23220-4 Photo ID の mdoc 証明書を構築・署名する。
 *
 * 出力は OID4VCI credential レスポンスの `credential` フィールドに入る
 * base64url エンコードされた IssuerSigned CBOR 構造。
 */
@OptIn(kotlin.time.ExperimentalTime::class)
class PhotoIdBuilder(private val keyStore: IssuerKeyStore) {

    companion object {
        const val PHOTO_ID_DOCTYPE = "org.iso.23220.photoid.1"
        const val NAMESPACE_23220_2 = "org.iso.23220.2"
        const val NAMESPACE_PHOTO_ID = "org.iso.23220.photoid.1"
    }

    /**
     * Photo ID の mdoc を構築して IssuerSigned CBOR を base64url で返す。
     *
     * @param credential 証明書に含める属性値
     * @param holderPublicKey Wallet が OID4VCI proof JWT で提示した holder の公開鍵（deviceKey として MSO に含める）
     */
    suspend fun buildCredential(
        credential: PhotoIdCredential,
        holderPublicKey: EcPublicKey,
    ): String {
        // MobileSecurityObject は signedAt に秒単位（ナノ秒なし）を要求するため切り捨て
        val now = kotlinx.datetime.Instant.fromEpochSeconds(Clock.System.now().epochSeconds)
        val validFrom = now
        val validUntil = credential.expiryDate.atStartOfDayIn(TimeZone.UTC)

        // --- IssuerNamespaces（各 data element と random salt を含む） ---
        val issuerNamespaces = buildIssuerNamespaces {
            addNamespace(NAMESPACE_23220_2) {
                addDataElement("family_name", credential.familyName.toDataItem())
                addDataElement("given_name", credential.givenName.toDataItem())
                addDataElement("birth_date", credential.birthDate.toDataItemFullDate())
                addDataElement("issue_date", credential.issueDate.toDataItemFullDate())
                addDataElement("expiry_date", credential.expiryDate.toDataItemFullDate())
                addDataElement("issuing_country", credential.issuingCountry.toDataItem())
                addDataElement("issuing_authority_unicode", credential.issuingAuthority.toDataItem())
                credential.portrait?.let {
                    addDataElement("portrait", Bstr(it))
                }
            }
            addNamespace(NAMESPACE_PHOTO_ID) {
                addDataElement("document_number", credential.documentNumber.toDataItem())
                addDataElement("person_id", credential.documentNumber.toDataItem())
            }
        }

        // --- MobileSecurityObject (MSO) ---
        val valueDigests = issuerNamespaces.getValueDigests(Algorithm.SHA256)
        val mso = MobileSecurityObject(
            version = "1.0",
            digestAlgorithm = Algorithm.SHA256,
            valueDigests = valueDigests,
            deviceKey = holderPublicKey,
            docType = PHOTO_ID_DOCTYPE,
            signedAt = now,
            validFrom = validFrom,
            validUntil = validUntil,
            expectedUpdate = null,
        )

        // MSO を CBOR にエンコードして Tagged(24, ...) でラップ（COSE payload）
        val taggedEncodedMso = Cbor.encode(
            Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(mso.toDataItem())))
        )

        // --- COSE_Sign1 署名 (IssuerAuth) ---
        val signingKey = buildMultipazSigningKey()

        val protectedHeaders: Map<CoseLabel, DataItem> = mapOf(
            CoseNumberLabel(Cose.COSE_LABEL_ALG) to
                    Algorithm.ES256.coseAlgorithmIdentifier!!.toDataItem()
        )
        val unprotectedHeaders: Map<CoseLabel, DataItem> = mapOf(
            CoseNumberLabel(Cose.COSE_LABEL_X5CHAIN) to signingKey.certChain.toDataItem()
        )

        val issuerAuth = Cose.coseSign1Sign(
            signingKey,
            taggedEncodedMso,
            includeMessageInPayload = true,
            protectedHeaders = protectedHeaders,
            unprotectedHeaders = unprotectedHeaders,
        )

        val encodedIssuerAuth = Cbor.encode(issuerAuth.toDataItem())

        // --- IssuerSigned 構造 ---
        return Cbor.encode(
            buildCborMap {
                put("nameSpaces", issuerNamespaces.toDataItem())
                put("issuerAuth", RawCbor(encodedIssuerAuth))
            }
        ).toBase64Url()
    }

    /**
     * Java の [java.security.PrivateKey] と [java.security.cert.X509Certificate] を
     * Multipaz の [AsymmetricKey.X509CertifiedExplicit] に変換する。
     */
    private fun buildMultipazSigningKey(): AsymmetricKey.X509CertifiedExplicit {
        val javaPrivKey = keyStore.privateKey as ECPrivateKey
        val javaPublicKey = keyStore.certificate.publicKey
        val multipazPrivKey = javaPrivKey.toEcPrivateKey(javaPublicKey, EcCurve.P256)

        val multipazCert = X509Cert(ByteString(keyStore.certificate.encoded))
        val certChain = X509CertChain(listOf(multipazCert))

        return AsymmetricKey.X509CertifiedExplicit(certChain, multipazPrivKey)
    }

    /**
     * OID4VCI proof JWT のヘッダーにある JWK (EC P-256) を Multipaz [EcPublicKey] に変換する。
     * 呼び出し元は Nimbus JOSE+JWT で JWT を解析し、公開鍵の x・y 座標を渡す。
     */
    fun ecPublicKeyFromCoordinates(xBytes: ByteArray, yBytes: ByteArray): EcPublicKey {
        return EcPublicKeyDoubleCoordinate(EcCurve.P256, normalize32(xBytes), normalize32(yBytes))
    }

    /**
     * 発行者署名鍵の EC 公開鍵を JWK Set JSON として返す（/jwks エンドポイント用）。
     */
    fun buildJwkSetJson(): String {
        val ecPubKey = keyStore.certificate.publicKey as java.security.interfaces.ECPublicKey
        val w = ecPubKey.w
        val xB64 = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(normalize32(w.affineX.toByteArray()))
        val yB64 = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(normalize32(w.affineY.toByteArray()))
        return """{"keys":[{"kty":"EC","crv":"P-256","use":"sig","alg":"ES256","x":"$xB64","y":"$yB64"}]}"""
    }

    private fun normalize32(bytes: ByteArray): ByteArray = when {
        bytes.size == 33 && bytes[0] == 0.toByte() -> bytes.copyOfRange(1, 33)
        bytes.size < 32 -> ByteArray(32 - bytes.size) + bytes
        else -> bytes
    }
}

/** ByteArray を base64url エンコード（パディングなし）する拡張。 */
private fun ByteArray.toBase64Url(): String =
    java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(this)
