package org.vdcapps.verifier.infrastructure.multipaz

import kotlinx.datetime.LocalDate
import kotlinx.io.bytestring.ByteString
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.CborArray
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.Uint
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.toDataItem
import org.multipaz.cbor.toDataItemFullDate
import org.multipaz.cose.Cose
import org.multipaz.cose.CoseLabel
import org.multipaz.cose.CoseNumberLabel
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPublicKeyDoubleCoordinate
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.crypto.toEcPrivateKey
import org.multipaz.mdoc.issuersigned.buildIssuerNamespaces
import org.multipaz.mdoc.mso.MobileSecurityObject
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * MdocVerifier の単体テスト。
 *
 * エラーパス: 不正な CBOR / 欠落フィールドを渡したときに適切な例外をスローすること。
 * ハッピーパス: Multipaz + BouncyCastle で構築した有効な DeviceResponse を検証できること。
 */
@OptIn(ExperimentalTime::class)
class MdocVerifierTest {
    private val verifier = MdocVerifier()
    private val testResponseUri = "http://localhost/verifier/response"
    private val verifierWithResponseUri = MdocVerifier(responseUri = testResponseUri)

    // ---- エラーパス: CBOR 構造不正 ----

    @Test
    fun `空のバイト列は例外をスローする`() {
        assertFailsWith<Exception> {
            verifier.verify(ByteArray(0))
        }
    }

    @Test
    fun `無効な CBOR バイト列は例外をスローする`() {
        assertFailsWith<Exception> {
            verifier.verify(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0xFD.toByte()))
        }
    }

    @Test
    fun `documents フィールドがない DeviceResponse は例外をスローする`() {
        // DeviceResponse = { "version": "1.0", "status": 0 } — documents なし
        val bytes =
            Cbor.encode(
                buildCborMap {
                    put("version", "1.0".toDataItem())
                    put("status", Uint(0UL))
                },
            )
        val ex = assertFailsWith<IllegalArgumentException> { verifier.verify(bytes) }
        assertTrue(ex.message?.contains("documents") == true, "メッセージが documents に言及: ${ex.message}")
    }

    @Test
    fun `documents が空配列の DeviceResponse は例外をスローする`() {
        val bytes =
            Cbor.encode(
                buildCborMap {
                    put("version", "1.0".toDataItem())
                    put(
                        "documents",
                        buildCborArray {},
                    )
                    put("status", Uint(0UL))
                },
            )
        assertFailsWith<IllegalArgumentException> { verifier.verify(bytes) }
    }

    @Test
    fun `Document に docType がない場合は例外をスローする`() {
        // Document = { "issuerSigned": {} } のみ
        val bytes =
            Cbor.encode(
                buildCborMap {
                    put(
                        "documents",
                        buildCborArray {
                            add(
                                buildCborMap {
                                    put("issuerSigned", buildCborMap {})
                                },
                            )
                        },
                    )
                },
            )
        val ex = assertFailsWith<IllegalArgumentException> { verifier.verify(bytes) }
        assertTrue(ex.message?.contains("docType") == true, "メッセージが docType に言及: ${ex.message}")
    }

    @Test
    fun `Document に issuerSigned がない場合は例外をスローする`() {
        val bytes =
            Cbor.encode(
                buildCborMap {
                    put(
                        "documents",
                        buildCborArray {
                            add(
                                buildCborMap {
                                    put("docType", "org.iso.23220.photoid.1".toDataItem())
                                },
                            )
                        },
                    )
                },
            )
        val ex = assertFailsWith<IllegalArgumentException> { verifier.verify(bytes) }
        assertTrue(ex.message?.contains("issuerSigned") == true, "メッセージが issuerSigned に言及: ${ex.message}")
    }

    @Test
    fun `issuerSigned に issuerAuth がない場合は例外をスローする`() {
        val bytes =
            Cbor.encode(
                buildCborMap {
                    put(
                        "documents",
                        buildCborArray {
                            add(
                                buildCborMap {
                                    put("docType", "org.iso.23220.photoid.1".toDataItem())
                                    put(
                                        "issuerSigned",
                                        buildCborMap {
                                            put("nameSpaces", buildCborMap {})
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
        val ex = assertFailsWith<IllegalArgumentException> { verifier.verify(bytes) }
        assertTrue(ex.message?.contains("issuerAuth") == true, "メッセージが issuerAuth に言及: ${ex.message}")
    }

    @Test
    fun `COSE_Sign1 の要素数が不正な場合は例外をスローする`() {
        // issuerAuth = [protectedHeader] — 要素数 1（4 未満）
        val issuerAuthBytes =
            Cbor.encode(
                buildCborArray {
                    add(Bstr(byteArrayOf(0x01)))
                },
            )
        val bytes =
            Cbor.encode(
                buildCborMap {
                    put(
                        "documents",
                        buildCborArray {
                            add(
                                buildCborMap {
                                    put("docType", "org.iso.23220.photoid.1".toDataItem())
                                    put(
                                        "issuerSigned",
                                        buildCborMap {
                                            put("issuerAuth", org.multipaz.cbor.RawCbor(issuerAuthBytes))
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
        assertFailsWith<IllegalArgumentException> { verifier.verify(bytes) }
    }

    // ---- ハッピーパス ----

    @Test
    fun `有効な DeviceResponse を検証すると VerificationResult が返る`() {
        val result = verifier.verify(buildValidDeviceResponse())
        assertNotNull(result)
    }

    @Test
    fun `検証結果の docType が正しい`() {
        val result = verifier.verify(buildValidDeviceResponse())
        assertEquals("org.iso.23220.photoid.1", result.docType)
    }

    @Test
    fun `検証結果の claims に family_name が含まれる`() {
        val result = verifier.verify(buildValidDeviceResponse())
        val ns = "org.iso.23220.2"
        assertTrue(result.claims.containsKey(ns), "namespace が含まれること: ${result.claims.keys}")
        assertEquals("Yamada", result.claims[ns]?.get("family_name"), "family_name が Yamada であること")
    }

    @Test
    fun `検証結果の issuerCertificateSubject が空でない`() {
        val result = verifier.verify(buildValidDeviceResponse())
        assertTrue(result.issuerCertificateSubject.isNotEmpty())
    }

    @Test
    fun `検証結果の validFrom と validUntil が設定されている`() {
        val result = verifier.verify(buildValidDeviceResponse())
        assertNotNull(result.validFrom)
        assertNotNull(result.validUntil)
        assertTrue(result.validFrom.isBefore(result.validUntil))
    }

    @Test
    fun `MSO の docType が Document の docType と一致しない場合は例外をスローする`() {
        val bytes = buildValidDeviceResponse(docType = "org.iso.23220.photoid.1", msoDocType = "com.example.other")
        assertFailsWith<IllegalArgumentException> { verifier.verify(bytes) }
    }

    @Test
    fun `有効期限切れの DeviceResponse は例外をスローする`() {
        val bytes = buildValidDeviceResponse(validUntilOffset = -3600L) // 1 時間前に期限切れ
        assertFailsWith<IllegalArgumentException> { verifier.verify(bytes) }
    }

    @Test
    fun `有効期間前の DeviceResponse は例外をスローする`() {
        val bytes = buildValidDeviceResponse(validFromOffset = 3600L) // 1 時間後から有効
        assertFailsWith<IllegalArgumentException> { verifier.verify(bytes) }
    }

    // ---- trustedCertificates 検証 ----

    @Test
    fun `信頼済みリストに一致する証明書で署名された DeviceResponse は検証を通過する`() {
        val issuerKp = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val issuerCert = generateSelfSignedCert(issuerKp.private as ECPrivateKey, issuerKp.public as ECPublicKey)
        val verifierWithTrust = MdocVerifier(trustedCertificates = listOf(issuerCert))

        val bytes = buildValidDeviceResponseWithKeyPair(issuerKp.private as ECPrivateKey, issuerKp.public as ECPublicKey, issuerCert)
        val result = verifierWithTrust.verify(bytes)
        assertNotNull(result)
    }

    @Test
    fun `信頼済みリストに一致しない証明書で署名された DeviceResponse は SecurityException をスローする`() {
        // 発行者の鍵ペアと証明書
        val issuerKp = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val issuerCert = generateSelfSignedCert(issuerKp.private as ECPrivateKey, issuerKp.public as ECPublicKey)

        // 別の（信頼しない）証明書
        val untrustedKp = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val untrustedCert = generateSelfSignedCert(untrustedKp.private as ECPrivateKey, untrustedKp.public as ECPublicKey)

        // untrustedCert のみを信頼するが、issuerCert で署名した DeviceResponse を検証する
        val verifierWithWrongTrust = MdocVerifier(trustedCertificates = listOf(untrustedCert))
        val bytes = buildValidDeviceResponseWithKeyPair(issuerKp.private as ECPrivateKey, issuerKp.public as ECPublicKey, issuerCert)

        assertFailsWith<SecurityException> { verifierWithWrongTrust.verify(bytes) }
    }

    @Test
    fun `信頼済みリストが空の場合はどの証明書でも検証を通過する（開発モード）`() {
        // デフォルト MdocVerifier（信頼済みリスト空）は開発モード
        val result = verifier.verify(buildValidDeviceResponse())
        assertNotNull(result)
    }

    // ---- DeviceSigned 検証 ----

    @Test
    fun `deviceSigned が含まれ nonce が一致する場合は検証を通過する`() {
        val nonce = "test-nonce-12345"
        val bytes = buildValidDeviceResponseWithDeviceSigned(nonce)
        val result = verifierWithResponseUri.verify(bytes, expectedNonce = nonce)
        assertNotNull(result)
    }

    @Test
    fun `deviceSigned の署名キーが不正な場合は SecurityException をスローする`() {
        val nonce = "test-nonce-12345"
        val bytes = buildValidDeviceResponseWithDeviceSigned(nonce, useWrongKey = true)
        assertFailsWith<SecurityException> {
            verifierWithResponseUri.verify(bytes, expectedNonce = nonce)
        }
    }

    @Test
    fun `deviceSigned がなく expectedNonce を渡した場合は WARN を出力して VerificationResult を返す`() {
        val nonce = "test-nonce-12345"
        // buildValidDeviceResponse() は deviceSigned を含まない
        val bytes = buildValidDeviceResponse()
        val result = verifier.verify(bytes, expectedNonce = nonce)
        assertNotNull(result)
    }

    // ---- テスト用 DeviceResponse 構築ヘルパー ----

    /**
     * 有効な DeviceResponse CBOR バイト列を構築するヘルパー。
     *
     * @param docType Document レベルの docType
     * @param msoDocType MSO の docType（null = docType と同じ）
     * @param validFromOffset 現在時刻からの validFrom オフセット（秒）
     * @param validUntilOffset 現在時刻からの validUntil オフセット（秒、デフォルト 1 年後）
     */
    private fun buildValidDeviceResponse(
        docType: String = "org.iso.23220.photoid.1",
        msoDocType: String? = null,
        validFromOffset: Long = -60L,
        validUntilOffset: Long = 365L * 24 * 3600,
    ): ByteArray =
        kotlinx.coroutines.runBlocking { buildValidDeviceResponseSuspend(docType, msoDocType, validFromOffset, validUntilOffset) }

    private suspend fun buildValidDeviceResponseSuspend(
        docType: String = "org.iso.23220.photoid.1",
        msoDocType: String? = null,
        validFromOffset: Long = -60L,
        validUntilOffset: Long = 365L * 24 * 3600,
    ): ByteArray {
        val effectiveMsoDocType = msoDocType ?: docType

        // 発行者の EC P-256 鍵ペアと自己署名証明書を生成
        val issuerKp = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val issuerCert = generateSelfSignedCert(issuerKp.private as ECPrivateKey, issuerKp.public as ECPublicKey)

        // Wallet（Holder）の EC P-256 鍵ペアを生成
        val walletKp = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val walletPub = walletKp.public as ECPublicKey

        fun normalize(bytes: ByteArray): ByteArray =
            when {
                bytes.size == 33 && bytes[0] == 0.toByte() -> bytes.copyOfRange(1, 33)
                bytes.size < 32 -> ByteArray(32 - bytes.size) + bytes
                else -> bytes
            }
        val holderKey =
            EcPublicKeyDoubleCoordinate(
                EcCurve.P256,
                normalize(walletPub.w.affineX.toByteArray()),
                normalize(walletPub.w.affineY.toByteArray()),
            )

        val namespace = "org.iso.23220.2"
        val photoIdNs = "org.iso.23220.photoid.1"
        val credential =
            buildTestCredential()

        // IssuerNamespaces を buildIssuerNamespaces DSL で構築
        val issuerNamespaces =
            buildIssuerNamespaces {
                addNamespace(namespace) {
                    addDataElement("family_name", credential.familyName.toDataItem())
                    addDataElement("given_name", credential.givenName.toDataItem())
                    addDataElement("birth_date", credential.birthDate.toDataItemFullDate())
                    addDataElement("issue_date", credential.issueDate.toDataItemFullDate())
                    addDataElement("expiry_date", credential.expiryDate.toDataItemFullDate())
                    addDataElement("issuing_country", credential.issuingCountry.toDataItem())
                    addDataElement("issuing_authority_unicode", credential.issuingAuthority.toDataItem())
                }
                addNamespace(photoIdNs) {
                    addDataElement("document_number", credential.documentNumber.toDataItem())
                }
            }

        val nowSec = Clock.System.now().epochSeconds
        val now = kotlinx.datetime.Instant.fromEpochSeconds(nowSec)
        val validFrom = kotlinx.datetime.Instant.fromEpochSeconds(nowSec + validFromOffset)
        val validUntil = kotlinx.datetime.Instant.fromEpochSeconds(nowSec + validUntilOffset)

        // MSO を構築
        val valueDigests = issuerNamespaces.getValueDigests(Algorithm.SHA256)
        val mso =
            MobileSecurityObject(
                version = "1.0",
                digestAlgorithm = Algorithm.SHA256,
                valueDigests = valueDigests,
                deviceKey = holderKey,
                docType = effectiveMsoDocType,
                signedAt = now,
                validFrom = validFrom,
                validUntil = validUntil,
                expectedUpdate = null,
            )

        val taggedEncodedMso =
            Cbor.encode(
                Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(mso.toDataItem()))),
            )

        // Multipaz 署名鍵を構築
        val javaPrivKey = issuerKp.private as ECPrivateKey
        val multipazPrivKey = javaPrivKey.toEcPrivateKey(issuerKp.public, EcCurve.P256)
        val multipazCert = X509Cert(ByteString(issuerCert.encoded))
        val certChain = X509CertChain(listOf(multipazCert))
        val signingKey = AsymmetricKey.X509CertifiedExplicit(certChain, multipazPrivKey)

        val protectedHeaders: Map<CoseLabel, org.multipaz.cbor.DataItem> =
            mapOf(
                CoseNumberLabel(Cose.COSE_LABEL_ALG) to
                    Algorithm.ES256.coseAlgorithmIdentifier!!.toDataItem(),
            )
        val unprotectedHeaders: Map<CoseLabel, org.multipaz.cbor.DataItem> =
            mapOf(
                CoseNumberLabel(Cose.COSE_LABEL_X5CHAIN) to signingKey.certChain.toDataItem(),
            )

        val issuerAuth =
            kotlinx.coroutines.runBlocking {
                Cose.coseSign1Sign(
                    signingKey,
                    taggedEncodedMso,
                    includeMessageInPayload = true,
                    protectedHeaders = protectedHeaders,
                    unprotectedHeaders = unprotectedHeaders,
                )
            }

        val encodedIssuerAuth = Cbor.encode(issuerAuth.toDataItem())

        val issuerSignedMap =
            buildCborMap {
                put("nameSpaces", issuerNamespaces.toDataItem())
                put("issuerAuth", org.multipaz.cbor.RawCbor(encodedIssuerAuth))
            }

        val document =
            buildCborMap {
                put("docType", docType.toDataItem())
                put("issuerSigned", issuerSignedMap)
            }

        val deviceResponse =
            buildCborMap {
                put("version", "1.0".toDataItem())
                put(
                    "documents",
                    buildCborArray { add(document) },
                )
                put("status", Uint(0UL))
            }

        return Cbor.encode(deviceResponse)
    }

    /** 発行者鍵ペアと証明書を外部から指定して DeviceResponse を構築するヘルパー。 */
    private fun buildValidDeviceResponseWithKeyPair(
        issuerPrivKey: ECPrivateKey,
        issuerPubKey: ECPublicKey,
        issuerCert: X509Certificate,
    ): ByteArray =
        kotlinx.coroutines.runBlocking {
            buildValidDeviceResponseSuspendWithKeyPair(issuerPrivKey, issuerPubKey, issuerCert)
        }

    private suspend fun buildValidDeviceResponseSuspendWithKeyPair(
        issuerPrivKey: ECPrivateKey,
        issuerPubKey: ECPublicKey,
        issuerCert: X509Certificate,
    ): ByteArray {
        val docType = "org.iso.23220.photoid.1"
        val walletKp = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val walletPub = walletKp.public as ECPublicKey

        fun normalize(bytes: ByteArray): ByteArray =
            when {
                bytes.size == 33 && bytes[0] == 0.toByte() -> bytes.copyOfRange(1, 33)
                bytes.size < 32 -> ByteArray(32 - bytes.size) + bytes
                else -> bytes
            }
        val holderKey =
            EcPublicKeyDoubleCoordinate(
                EcCurve.P256,
                normalize(walletPub.w.affineX.toByteArray()),
                normalize(walletPub.w.affineY.toByteArray()),
            )

        val namespace = "org.iso.23220.2"
        val credential = buildTestCredential()
        val issuerNamespaces =
            buildIssuerNamespaces {
                addNamespace(namespace) {
                    addDataElement("family_name", credential.familyName.toDataItem())
                    addDataElement("given_name", credential.givenName.toDataItem())
                    addDataElement("birth_date", credential.birthDate.toDataItemFullDate())
                    addDataElement("issue_date", credential.issueDate.toDataItemFullDate())
                    addDataElement("expiry_date", credential.expiryDate.toDataItemFullDate())
                    addDataElement("issuing_country", credential.issuingCountry.toDataItem())
                    addDataElement("issuing_authority_unicode", credential.issuingAuthority.toDataItem())
                }
            }

        val nowSec = Clock.System.now().epochSeconds
        val now = kotlinx.datetime.Instant.fromEpochSeconds(nowSec)
        val validFrom = kotlinx.datetime.Instant.fromEpochSeconds(nowSec - 60)
        val validUntil = kotlinx.datetime.Instant.fromEpochSeconds(nowSec + 365L * 24 * 3600)
        val valueDigests = issuerNamespaces.getValueDigests(Algorithm.SHA256)
        val mso =
            MobileSecurityObject(
                version = "1.0",
                digestAlgorithm = Algorithm.SHA256,
                valueDigests = valueDigests,
                deviceKey = holderKey,
                docType = docType,
                signedAt = now,
                validFrom = validFrom,
                validUntil = validUntil,
                expectedUpdate = null,
            )

        val taggedEncodedMso = Cbor.encode(Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(mso.toDataItem()))))
        val multipazPrivKey = issuerPrivKey.toEcPrivateKey(issuerPubKey, EcCurve.P256)
        val multipazCert = X509Cert(ByteString(issuerCert.encoded))
        val certChain = X509CertChain(listOf(multipazCert))
        val signingKey = AsymmetricKey.X509CertifiedExplicit(certChain, multipazPrivKey)

        val protectedHeaders: Map<CoseLabel, org.multipaz.cbor.DataItem> =
            mapOf(CoseNumberLabel(Cose.COSE_LABEL_ALG) to Algorithm.ES256.coseAlgorithmIdentifier!!.toDataItem())
        val unprotectedHeaders: Map<CoseLabel, org.multipaz.cbor.DataItem> =
            mapOf(CoseNumberLabel(Cose.COSE_LABEL_X5CHAIN) to signingKey.certChain.toDataItem())

        val issuerAuth =
            kotlinx.coroutines.runBlocking {
                Cose.coseSign1Sign(
                    signingKey,
                    taggedEncodedMso,
                    includeMessageInPayload = true,
                    protectedHeaders = protectedHeaders,
                    unprotectedHeaders = unprotectedHeaders,
                )
            }

        val issuerSignedMap =
            buildCborMap {
                put("nameSpaces", issuerNamespaces.toDataItem())
                put("issuerAuth", org.multipaz.cbor.RawCbor(Cbor.encode(issuerAuth.toDataItem())))
            }
        val document =
            buildCborMap {
                put("docType", docType.toDataItem())
                put("issuerSigned", issuerSignedMap)
            }
        return Cbor.encode(
            buildCborMap {
                put("version", "1.0".toDataItem())
                put("documents", buildCborArray { add(document) })
                put("status", Uint(0UL))
            },
        )
    }

    /**
     * DeviceSigned を含む有効な DeviceResponse CBOR バイト列を構築するヘルパー。
     *
     * @param nonce OID4VP Authorization Request の nonce（responseUri = testResponseUri を使用）
     * @param useWrongKey true の場合、MSO の deviceKey とは別の鍵で deviceSigned に署名する（検証失敗シナリオ用）
     */
    private fun buildValidDeviceResponseWithDeviceSigned(
        nonce: String,
        useWrongKey: Boolean = false,
    ): ByteArray =
        kotlinx.coroutines.runBlocking {
            buildValidDeviceResponseWithDeviceSignedSuspend(nonce, testResponseUri, useWrongKey)
        }

    private suspend fun buildValidDeviceResponseWithDeviceSignedSuspend(
        nonce: String,
        responseUri: String,
        useWrongKey: Boolean,
    ): ByteArray {
        val docType = "org.iso.23220.photoid.1"

        val issuerKp = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val issuerCert = generateSelfSignedCert(issuerKp.private as ECPrivateKey, issuerKp.public as ECPublicKey)

        val walletKp = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val walletPubKey = walletKp.public as ECPublicKey
        val walletPrivKey = walletKp.private as ECPrivateKey

        fun normalize(bytes: ByteArray): ByteArray =
            when {
                bytes.size == 33 && bytes[0] == 0.toByte() -> bytes.copyOfRange(1, 33)
                bytes.size < 32 -> ByteArray(32 - bytes.size) + bytes
                else -> bytes
            }
        val holderKey =
            EcPublicKeyDoubleCoordinate(
                EcCurve.P256,
                normalize(walletPubKey.w.affineX.toByteArray()),
                normalize(walletPubKey.w.affineY.toByteArray()),
            )

        val namespace = "org.iso.23220.2"
        val credential = buildTestCredential()
        val issuerNamespaces =
            buildIssuerNamespaces {
                addNamespace(namespace) {
                    addDataElement("family_name", credential.familyName.toDataItem())
                    addDataElement("given_name", credential.givenName.toDataItem())
                    addDataElement("birth_date", credential.birthDate.toDataItemFullDate())
                    addDataElement("issue_date", credential.issueDate.toDataItemFullDate())
                    addDataElement("expiry_date", credential.expiryDate.toDataItemFullDate())
                    addDataElement("issuing_country", credential.issuingCountry.toDataItem())
                    addDataElement("issuing_authority_unicode", credential.issuingAuthority.toDataItem())
                }
            }

        val nowSec = Clock.System.now().epochSeconds
        val now = kotlinx.datetime.Instant.fromEpochSeconds(nowSec)
        val validFrom = kotlinx.datetime.Instant.fromEpochSeconds(nowSec - 60)
        val validUntil = kotlinx.datetime.Instant.fromEpochSeconds(nowSec + 365L * 24 * 3600)
        val valueDigests = issuerNamespaces.getValueDigests(Algorithm.SHA256)
        val mso =
            MobileSecurityObject(
                version = "1.0",
                digestAlgorithm = Algorithm.SHA256,
                valueDigests = valueDigests,
                deviceKey = holderKey,
                docType = docType,
                signedAt = now,
                validFrom = validFrom,
                validUntil = validUntil,
                expectedUpdate = null,
            )

        val taggedEncodedMso = Cbor.encode(Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(mso.toDataItem()))))
        val multipazPrivKey = (issuerKp.private as ECPrivateKey).toEcPrivateKey(issuerKp.public, EcCurve.P256)
        val multipazCert = X509Cert(ByteString(issuerCert.encoded))
        val certChain = X509CertChain(listOf(multipazCert))
        val signingKey = AsymmetricKey.X509CertifiedExplicit(certChain, multipazPrivKey)

        val issuerAuth =
            kotlinx.coroutines.runBlocking {
                Cose.coseSign1Sign(
                    signingKey,
                    taggedEncodedMso,
                    includeMessageInPayload = true,
                    protectedHeaders =
                        mapOf(
                            CoseNumberLabel(Cose.COSE_LABEL_ALG) to
                                Algorithm.ES256.coseAlgorithmIdentifier!!.toDataItem(),
                        ),
                    unprotectedHeaders =
                        mapOf(
                            CoseNumberLabel(Cose.COSE_LABEL_X5CHAIN) to signingKey.certChain.toDataItem(),
                        ),
                )
            }

        // DeviceNameSpaces = Tagged(24, Bstr(encoded_empty_map))
        val deviceNameSpacesBytes = Cbor.encode(buildCborMap {})
        val deviceNameSpacesItem = Tagged(Tagged.ENCODED_CBOR, Bstr(deviceNameSpacesBytes))

        // OID4VP SessionTranscript の再構築（MdocVerifier.buildOid4vpSessionTranscript と同じロジック）
        val clientId = responseUri
        val handoverInput = (clientId + nonce + responseUri).toByteArray(Charsets.UTF_8)
        val oid4vpNonce = MessageDigest.getInstance("SHA-256").digest(handoverInput)
        val handoverBytes =
            Cbor.encode(
                buildCborArray {
                    add(Bstr(oid4vpNonce))
                    add(nonce.toDataItem())
                },
            )
        // SessionTranscript = [null, null, OID4VPHandover]
        val sessionTranscriptBytes = byteArrayOf(0x83.toByte(), 0xF6.toByte(), 0xF6.toByte()) + handoverBytes
        val sessionTranscriptItem = Cbor.decode(sessionTranscriptBytes)

        // DeviceAuthentication = ["DeviceAuthentication", SessionTranscript, DocType, DeviceNameSpacesBytes]
        val deviceAuthenticationBytes =
            Cbor.encode(
                buildCborArray {
                    add("DeviceAuthentication".toDataItem())
                    add(sessionTranscriptItem)
                    add(docType.toDataItem())
                    add(deviceNameSpacesItem)
                },
            )

        // Protected header = {1: -7} (ES256)
        val deviceProtectedHeaderBytes = byteArrayOf(0xA1.toByte(), 0x01, 0x26.toByte())

        // Sig_Structure（デタッチドペイロード）
        val sigStructureBytes =
            Cbor.encode(
                buildCborArray {
                    add("Signature1".toDataItem())
                    add(Bstr(deviceProtectedHeaderBytes))
                    add(Bstr(ByteArray(0)))
                    add(Bstr(deviceAuthenticationBytes))
                },
            )

        // Wallet 秘密鍵（または検証失敗シナリオ用の別鍵）で署名
        val signingPrivKey =
            if (useWrongKey) {
                KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair().private as ECPrivateKey
            } else {
                walletPrivKey
            }
        val javaSig = Signature.getInstance("SHA256withECDSA")
        javaSig.initSign(signingPrivKey)
        javaSig.update(sigStructureBytes)
        val rawSig = derEcdsaSignatureToRaw(javaSig.sign())

        // Device COSE_Sign1 配列を構築: [bstr(protected), {}, null, bstr(sig)]
        val deviceCoseSign1 = buildDeviceCoseSign1Array(deviceProtectedHeaderBytes, rawSig)

        val deviceAuthMap = buildCborMap { put("deviceSignature", deviceCoseSign1) }
        val deviceSignedMap =
            buildCborMap {
                put("nameSpaces", deviceNameSpacesItem)
                put("deviceAuth", deviceAuthMap)
            }

        val issuerSignedMap =
            buildCborMap {
                put("nameSpaces", issuerNamespaces.toDataItem())
                put("issuerAuth", org.multipaz.cbor.RawCbor(Cbor.encode(issuerAuth.toDataItem())))
            }
        val document =
            buildCborMap {
                put("docType", docType.toDataItem())
                put("issuerSigned", issuerSignedMap)
                put("deviceSigned", deviceSignedMap)
            }
        return Cbor.encode(
            buildCborMap {
                put("version", "1.0".toDataItem())
                put("documents", buildCborArray { add(document) })
                put("status", Uint(0UL))
            },
        )
    }

    /**
     * Device COSE_Sign1 配列 [bstr(protected), {}, null, bstr(sig)] を構築して CborArray として返す。
     * デタッチドペイロードのため items[2] は CBOR null (0xF6)。
     */
    private fun buildDeviceCoseSign1Array(
        protectedHeaderBytes: ByteArray,
        signatureBytes: ByteArray,
    ): CborArray {
        // [bstr(protected), {}, null, bstr(sig)] を raw CBOR bytes で組み立てる
        val content =
            Cbor.encode(Bstr(protectedHeaderBytes)) +
                byteArrayOf(0xA0.toByte()) + // empty map {}
                byteArrayOf(0xF6.toByte()) + // null
                Cbor.encode(Bstr(signatureBytes))
        // 0x84 = CBOR array(4)
        return Cbor.decode(byteArrayOf(0x84.toByte()) + content) as CborArray
    }

    /**
     * Java Signature API が返す DER 形式の ECDSA 署名を
     * COSE 形式の raw 形式（r || s、各 32 バイト）に変換する。
     */
    private fun derEcdsaSignatureToRaw(der: ByteArray): ByteArray {
        var i = 0
        require(der[i++] == 0x30.toByte()) { "Expected DER SEQUENCE" }
        // Length フィールドをスキップ（1 バイトまたは 2 バイト）
        if (der[i].toInt() and 0x80 != 0) {
            i += 1 + (der[i].toInt() and 0x7F)
        } else {
            i++
        }
        // INTEGER r
        require(der[i++] == 0x02.toByte()) { "Expected INTEGER tag for r" }
        val rLen = der[i++].toInt() and 0xFF
        val r = der.copyOfRange(i, i + rLen)
        i += rLen
        // INTEGER s
        require(der[i++] == 0x02.toByte()) { "Expected INTEGER tag for s" }
        val sLen = der[i++].toInt() and 0xFF
        val s = der.copyOfRange(i, i + sLen)

        fun toFixed32(bytes: ByteArray): ByteArray =
            when {
                bytes.size == 33 && bytes[0] == 0.toByte() -> bytes.copyOfRange(1, 33)
                bytes.size < 32 -> ByteArray(32 - bytes.size) + bytes
                else -> bytes
            }
        return toFixed32(r) + toFixed32(s)
    }

    private data class TestCredential(
        val familyName: String,
        val givenName: String,
        val birthDate: LocalDate,
        val issuingCountry: String,
        val issuingAuthority: String,
        val documentNumber: String,
        val issueDate: LocalDate,
        val expiryDate: LocalDate,
    )

    private fun buildTestCredential() =
        TestCredential(
            familyName = "Yamada",
            givenName = "Taro",
            birthDate = LocalDate(1990, 1, 15),
            issuingCountry = "JP",
            issuingAuthority = "Test Issuer",
            documentNumber = "DOC001234567",
            issueDate = LocalDate(2025, 1, 1),
            expiryDate = LocalDate(2027, 12, 31),
        )

    /**
     * テスト用の自己署名 X.509 証明書を生成する（BouncyCastle を使用）。
     */
    private fun generateSelfSignedCert(
        privateKey: ECPrivateKey,
        publicKey: ECPublicKey,
    ): X509Certificate {
        val now = System.currentTimeMillis()
        val subject = X500Name("CN=Test Issuer, O=Test, C=JP")
        val certHolder =
            X509v3CertificateBuilder(
                subject,
                BigInteger.valueOf(now),
                Date(now - 1000),
                Date(now + 10L * 365 * 24 * 60 * 60 * 1000),
                subject,
                SubjectPublicKeyInfo.getInstance(publicKey.encoded),
            ).apply {
                addExtension(Extension.basicConstraints, true, BasicConstraints(false))
            }.build(JcaContentSignerBuilder("SHA256withECDSA").build(privateKey))

        return JcaX509CertificateConverter().getCertificate(certHolder)
    }
}
