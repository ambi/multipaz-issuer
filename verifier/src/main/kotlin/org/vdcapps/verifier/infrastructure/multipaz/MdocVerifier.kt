package org.vdcapps.verifier.infrastructure.multipaz

import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.CborArray
import org.multipaz.cbor.CborMap
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.Uint
import org.multipaz.cbor.buildCborArray
import org.multipaz.crypto.EcPublicKeyDoubleCoordinate
import org.multipaz.mdoc.mso.MobileSecurityObject
import org.slf4j.LoggerFactory
import org.vdcapps.verifier.domain.verification.VerificationResult
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.time.Instant

/**
 * OID4VP の vp_token（base64url デコード済み DeviceResponse CBOR バイト列）を検証し
 * [VerificationResult] を返す。
 *
 * @param trustedCertificates 信頼する発行者証明書のリスト。
 *   空の場合は開発モードとして警告を出力して証明書検証をスキップする。
 *   本番環境では TRUSTED_ISSUER_CERT で必ず設定すること。
 * @param responseUri OID4VP Authorization Request の response_uri（= client_id）。
 *   DeviceSigned の SessionTranscript 再構築に使用する。空文字の場合は DeviceSigned 検証をスキップする。
 */
@OptIn(kotlin.time.ExperimentalTime::class)
class MdocVerifier(
    private val trustedCertificates: List<X509Certificate> = emptyList(),
    private val responseUri: String = "",
) {
    private val logger = LoggerFactory.getLogger(MdocVerifier::class.java)

    fun verify(
        deviceResponseBytes: ByteArray,
        expectedNonce: String? = null,
    ): VerificationResult {
        val deviceResponse = Cbor.decode(deviceResponseBytes).asMap()

        val documents =
            deviceResponse.getByString("documents")?.asArray()
                ?: throw IllegalArgumentException("DeviceResponse に documents がありません")
        if (documents.items.isEmpty()) throw IllegalArgumentException("DeviceResponse に Document が含まれていません")

        val document = documents.items[0].asMap()
        val docType =
            document.getByString("docType")?.asString()
                ?: throw IllegalArgumentException("Document に docType がありません")
        val issuerSigned =
            document.getByString("issuerSigned")?.asMap()
                ?: throw IllegalArgumentException("Document に issuerSigned がありません")

        // IssuerAuth は RawCbor として保存されているため encode → decode で COSE_Sign1 配列を取得
        val issuerAuthItem =
            issuerSigned.getByString("issuerAuth")
                ?: throw IllegalArgumentException("IssuerSigned に issuerAuth がありません")
        val coseSign1 = Cbor.decode(Cbor.encode(issuerAuthItem)).asArray()
        if (coseSign1.items.size < 4) throw IllegalArgumentException("COSE_Sign1 の要素数が不正: ${coseSign1.items.size}")

        val protectedHeaderBytes = coseSign1.items[0].asByteArray()
        val unprotectedHeaders = coseSign1.items[1].asMap()
        val payloadBytes = coseSign1.items[2].asByteArray()
        val signatureBytes = coseSign1.items[3].asByteArray()

        val issuerCert =
            parseX5Chain(unprotectedHeaders.getByULong(33UL))
                ?: throw IllegalArgumentException("x5chain から発行者証明書を取得できませんでした")
        logger.debug("発行者証明書: ${issuerCert.subjectX500Principal.name}")

        validateIssuerCertificate(issuerCert)
        verifyCoseSign1(protectedHeaderBytes, payloadBytes, signatureBytes, issuerCert)

        // payload = Tagged(24, Bstr(cbor_encoded_mso))
        val msoBytes = extractTagged24Bytes(Cbor.decode(payloadBytes))
        val mso = MobileSecurityObject.fromDataItem(Cbor.decode(msoBytes))
        logger.debug("MSO docType=${mso.docType}, validFrom=${mso.validFrom}, validUntil=${mso.validUntil}")

        if (mso.docType != docType) throw IllegalArgumentException("MSO docType 不一致: ${mso.docType} != $docType")

        val now = Instant.now()
        val validFrom = Instant.ofEpochSecond(mso.validFrom.epochSeconds)
        val validUntil = Instant.ofEpochSecond(mso.validUntil.epochSeconds)
        if (now.isBefore(validFrom)) throw IllegalArgumentException("mdoc が有効期間前です (validFrom=$validFrom)")
        if (now.isAfter(validUntil)) throw IllegalArgumentException("mdoc の有効期限切れ (validUntil=$validUntil)")

        val nameSpacesMap =
            issuerSigned.getByString("nameSpaces")?.asMap()
                ?: throw IllegalArgumentException("IssuerSigned に nameSpaces がありません")

        // DeviceSigned の署名検証（nonce の OID4VP バインディング確認）
        val deviceSigned = document.getByString("deviceSigned")?.asMap()
        if (deviceSigned != null) {
            verifyDeviceSigned(deviceSigned, docType, mso, expectedNonce)
        } else if (expectedNonce != null) {
            logger.warn(
                "SECURITY WARNING: DeviceResponse に deviceSigned が含まれていません。" +
                    "nonce=${expectedNonce.take(8)}... の検証をスキップします。" +
                    "本番環境では deviceSigned を含む Wallet を使用してください。",
            )
        }

        return VerificationResult(
            docType = docType,
            claims = extractClaims(nameSpacesMap),
            issuerCertificateSubject = issuerCert.subjectX500Principal.name,
            validFrom = validFrom,
            validUntil = validUntil,
        )
    }

    // ---- DeviceSigned 検証 ----

    /**
     * DeviceSigned を検証する。
     *
     * ISO 18013-5:2021 §9.1.3 に基づき以下を確認する:
     * 1. deviceSignature（COSE_Sign1）の署名が MSO の deviceKey で正しく検証できること
     * 2. DeviceAuthentication に埋め込まれた SessionTranscript の nonce が expectedNonce と一致すること
     *
     * SessionTranscript は ISO 18013-7:2024 §B.4.4 に定義された OID4VP Handover 形式で再構築する:
     * ```
     * SessionTranscript = [null, null, OID4VPHandover]
     * OID4VPHandover = [OID4VPNonce, nonce]
     * OID4VPNonce = SHA-256(clientId || nonce || responseUri)   ; clientId == responseUri の場合
     * ```
     *
     * @param deviceSigned DeviceResponse から抽出した deviceSigned CBOR マップ
     * @param docType Document の docType 文字列
     * @param mso IssuerAuth から検証済みの MSO（deviceKey を含む）
     * @param expectedNonce OID4VP Authorization Request で発行した nonce
     */
    private fun verifyDeviceSigned(
        deviceSigned: CborMap,
        docType: String,
        mso: MobileSecurityObject,
        expectedNonce: String?,
    ) {
        val nameSpacesItem =
            deviceSigned.getByString("nameSpaces")
                ?: throw IllegalArgumentException("deviceSigned に nameSpaces がありません")

        val deviceAuth =
            deviceSigned.getByString("deviceAuth")?.asMap()
                ?: throw IllegalArgumentException("deviceSigned に deviceAuth がありません")

        val deviceSignatureArray =
            deviceAuth.getByString("deviceSignature")?.asArray()
                ?: throw IllegalArgumentException("deviceAuth に deviceSignature がありません")

        if (deviceSignatureArray.items.size < 4) {
            throw IllegalArgumentException("deviceSignature COSE_Sign1 の要素数が不正: ${deviceSignatureArray.items.size}")
        }

        val protectedHeaderBytes = deviceSignatureArray.items[0].asByteArray()
        // items[2] は nil（デタッチドペイロード）
        val signatureBytes = deviceSignatureArray.items[3].asByteArray()

        if (expectedNonce == null) {
            logger.warn("deviceSigned が存在しますが expectedNonce が null のため nonce 検証をスキップします")
            return
        }

        if (responseUri.isBlank()) {
            logger.warn(
                "SECURITY WARNING: responseUri が設定されていないため DeviceSigned の nonce 検証をスキップします。" +
                    "MdocVerifier に responseUri を設定してください。",
            )
            return
        }

        // OID4VP SessionTranscript を再構築する（ISO 18013-7:2024 §B.4.4）
        val sessionTranscriptBytes = buildOid4vpSessionTranscript(expectedNonce)
        val sessionTranscriptItem = Cbor.decode(sessionTranscriptBytes)

        // DeviceAuthentication = ["DeviceAuthentication", SessionTranscript, DocType, DeviceNameSpacesBytes]
        val deviceAuthenticationBytes =
            Cbor.encode(
                buildCborArray {
                    add(Tstr("DeviceAuthentication"))
                    add(sessionTranscriptItem)
                    add(Tstr(docType))
                    add(nameSpacesItem) // Tagged(24, bstr) のまま埋め込む
                },
            )

        // Sig_Structure（デタッチドペイロード）: ["Signature1", protected_bytes, b"", bstr(DeviceAuthentication)]
        val sigStructureBytes =
            Cbor.encode(
                buildCborArray {
                    add(Tstr("Signature1"))
                    add(Bstr(protectedHeaderBytes))
                    add(Bstr(ByteArray(0))) // empty external AAD
                    add(Bstr(deviceAuthenticationBytes)) // DeviceAuthentication を payload として提供
                },
            )

        val devicePublicKey = deviceKeyToJavaPublicKey(mso.deviceKey)
        val derSig = rawEcdsaSignatureToDer(signatureBytes)

        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initVerify(devicePublicKey)
        sig.update(sigStructureBytes)

        if (!sig.verify(derSig)) {
            throw SecurityException(
                "DeviceSigned の署名検証に失敗しました。" +
                    "nonce またはレスポンス URI が一致しない可能性があります（expectedNonce=${expectedNonce.take(8)}...）",
            )
        }

        logger.info("DeviceSigned 署名検証 OK: nonce=${expectedNonce.take(8)}...")
    }

    /**
     * OID4VP SessionTranscript を CBOR バイト列として構築する。
     *
     * ISO 18013-7:2024 §B.4.4（OID4VP Handover、`request_uri` パターン）に基づく:
     * ```
     * SessionTranscript = [null, null, OID4VPHandover]
     * OID4VPHandover    = [OID4VPNonce, nonce]
     * OID4VPNonce       = SHA-256(clientId || nonce || responseUri)
     * ```
     * `client_id_scheme = "redirect_uri"` の場合 clientId == responseUri。
     *
     * NOTE: フォーマットは Multipaz Compose Wallet 0.97.0 + OID4VP draft 20 + ISO 18013-7:2024 を想定。
     *       実際の Wallet との相互運用性は E2E テストで確認すること。
     */
    private fun buildOid4vpSessionTranscript(nonce: String): ByteArray {
        // client_id_scheme = redirect_uri の場合、clientId == responseUri
        val clientId = responseUri
        val handoverInput = (clientId + nonce + responseUri).toByteArray(Charsets.UTF_8)
        val oid4vpNonce = MessageDigest.getInstance("SHA-256").digest(handoverInput)

        // OID4VPHandover = [OID4VPNonce (bstr), nonce (tstr)]
        val handoverBytes =
            Cbor.encode(
                buildCborArray {
                    add(Bstr(oid4vpNonce))
                    add(Tstr(nonce))
                },
            )

        // SessionTranscript = [null, null, OID4VPHandover]
        // CBOR: 0x83 (array 3) | 0xF6 (null) | 0xF6 (null) | <handover bytes>
        return byteArrayOf(0x83.toByte(), 0xF6.toByte(), 0xF6.toByte()) + handoverBytes
    }

    /**
     * Multipaz [org.multipaz.crypto.EcPublicKey] を Java [java.security.PublicKey] に変換する。
     * P-256（secp256r1）のみ対応。MSO の deviceKey 検証用。
     */
    private fun deviceKeyToJavaPublicKey(key: org.multipaz.crypto.EcPublicKey): java.security.PublicKey {
        val coord =
            key as? EcPublicKeyDoubleCoordinate
                ?: throw IllegalArgumentException("サポートされていない EcPublicKey の型: ${key::class.simpleName}")
        val params = AlgorithmParameters.getInstance("EC")
        params.init(ECGenParameterSpec("secp256r1"))
        val ecParams = params.getParameterSpec(ECParameterSpec::class.java)
        val ecPoint = ECPoint(BigInteger(1, coord.x), BigInteger(1, coord.y))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ecPoint, ecParams))
    }

    // ---- 発行者証明書信頼検証 ----

    /**
     * 発行者証明書を信頼済みリストと照合する。
     *
     * - [trustedCertificates] が空の場合: SECURITY WARNING を出力して開発モードとして続行。
     *   本番環境では TRUSTED_ISSUER_CERT を設定して必ず有効化すること。
     * - [trustedCertificates] が設定済みの場合: 証明書が信頼リストに直接含まれるか、
     *   信頼済み証明書によって署名されているかを検証する。
     */
    private fun validateIssuerCertificate(cert: X509Certificate) {
        if (trustedCertificates.isEmpty()) {
            logger.warn(
                "SECURITY WARNING: 信頼済み発行者証明書が設定されていません。" +
                    "いかなる自己署名証明書も受け入れます。" +
                    "本番環境では TRUSTED_ISSUER_CERT 環境変数を設定してください。" +
                    " 発行者: ${cert.subjectX500Principal.name}",
            )
            return
        }

        for (trusted in trustedCertificates) {
            try {
                if (cert == trusted) {
                    logger.debug("発行者証明書が信頼済みリストに直接一致: ${cert.subjectX500Principal.name}")
                    return
                }
                cert.verify(trusted.publicKey)
                logger.debug("発行者証明書が信頼済み CA によって署名されています: ${trusted.subjectX500Principal.name}")
                return
            } catch (_: Exception) {
                // この trusted cert では検証できなかった。次を試す。
            }
        }
        throw SecurityException(
            "発行者証明書が信頼済みリストに含まれていません: ${cert.subjectX500Principal.name}",
        )
    }

    // ---- CBOR ヘルパー ----

    private fun DataItem.asMap() =
        this as? CborMap
            ?: throw IllegalArgumentException("CborMap が期待されましたが ${this::class.simpleName}")

    private fun DataItem.asArray() =
        this as? CborArray
            ?: throw IllegalArgumentException("CborArray が期待されましたが ${this::class.simpleName}")

    private fun DataItem.asByteArray() =
        (this as? Bstr)?.value
            ?: throw IllegalArgumentException("Bstr が期待されましたが ${this::class.simpleName}")

    private fun DataItem.asString() =
        (this as? Tstr)?.value
            ?: throw IllegalArgumentException("Tstr が期待されましたが ${this::class.simpleName}")

    private fun CborMap.getByString(key: String): DataItem? = items.entries.find { it.key is Tstr && (it.key as Tstr).value == key }?.value

    private fun CborMap.getByULong(key: ULong): DataItem? = items.entries.find { it.key is Uint && (it.key as Uint).value == key }?.value

    private fun extractTagged24Bytes(item: DataItem): ByteArray =
        when (item) {
            is Tagged -> {
                (item.taggedItem as? Bstr)?.value
                    ?: throw IllegalArgumentException("Tagged(24) の内部が Bstr ではありません")
            }

            is Bstr -> {
                item.value
            }

            else -> {
                throw IllegalArgumentException("payload が想定外の型: ${item::class.simpleName}")
            }
        }

    // ---- 証明書パース ----

    private fun parseX5Chain(item: DataItem?): X509Certificate? {
        val cf = CertificateFactory.getInstance("X.509")
        val der: ByteArray =
            when (item) {
                is Bstr -> item.value
                is CborArray -> (item.items.firstOrNull() as? Bstr)?.value ?: return null
                else -> return null
            }
        return cf.generateCertificate(der.inputStream()) as X509Certificate
    }

    // ---- 署名検証 ----

    /**
     * IssuerAuth の Sig_Structure = ["Signature1", protected_bytes, external_aad(空), payload_bytes]
     */
    private fun verifyCoseSign1(
        protectedHeaderBytes: ByteArray,
        payloadBytes: ByteArray,
        signatureBytes: ByteArray,
        cert: X509Certificate,
    ) {
        val sigStructureBytes =
            Cbor.encode(
                buildCborArray {
                    add(Tstr("Signature1"))
                    add(Bstr(protectedHeaderBytes))
                    add(Bstr(ByteArray(0)))
                    add(Bstr(payloadBytes))
                },
            )
        // COSE ES256 の署名は raw 形式（r || s、各 32 バイト）。
        // Java の SHA256withECDSA は DER/ASN.1 形式を要求するため変換する。
        val derSig = rawEcdsaSignatureToDer(signatureBytes)
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initVerify(cert.publicKey)
        sig.update(sigStructureBytes)
        if (!sig.verify(derSig)) throw IllegalArgumentException("COSE_Sign1 署名の検証に失敗しました")
        logger.debug("COSE_Sign1 署名検証 OK")
    }

    /**
     * COSE raw ECDSA 署名（r || s、各 32 バイト）を Java Signature API が受け入れる
     * DER/ASN.1 形式（SEQUENCE { INTEGER r, INTEGER s }）に変換する。
     */
    private fun rawEcdsaSignatureToDer(rawSig: ByteArray): ByteArray {
        require(rawSig.size == 64) { "P-256 raw 署名は 64 バイトのはずですが ${rawSig.size} バイトです" }

        fun encodeAsn1Integer(bytes: ByteArray): ByteArray {
            // 先頭の 0x00 を除去（ただし 1 バイト以上残す）
            var start = 0
            while (start < bytes.size - 1 && bytes[start] == 0.toByte()) start++
            val trimmed = bytes.copyOfRange(start, bytes.size)
            // 最上位ビットが立っている場合は正の整数を示す 0x00 を前置する
            val padded = if (trimmed[0].toInt() and 0x80 != 0) byteArrayOf(0x00) + trimmed else trimmed
            return byteArrayOf(0x02.toByte(), padded.size.toByte()) + padded
        }

        val r = encodeAsn1Integer(rawSig.copyOfRange(0, 32))
        val s = encodeAsn1Integer(rawSig.copyOfRange(32, 64))
        val content = r + s
        return byteArrayOf(0x30.toByte(), content.size.toByte()) + content
    }

    // ---- クレーム抽出 ----

    private fun extractClaims(nameSpacesMap: CborMap): Map<String, Map<String, String>> {
        val result = mutableMapOf<String, MutableMap<String, String>>()
        for ((nsKey, nsValue) in nameSpacesMap.items) {
            val namespace = (nsKey as? Tstr)?.value ?: continue
            val nsItems = nsValue as? CborArray ?: continue
            val claims = mutableMapOf<String, String>()
            for (item in nsItems.items) {
                try {
                    val innerBstr: Bstr =
                        when (item) {
                            is Tagged -> item.taggedItem as? Bstr ?: continue
                            is Bstr -> item
                            else -> continue
                        }
                    val signedItem = Cbor.decode(innerBstr.value) as? CborMap ?: continue
                    val id = signedItem.getByString("elementIdentifier")?.let { (it as? Tstr)?.value } ?: continue
                    val value = signedItem.getByString("elementValue") ?: continue
                    claims[id] = dataItemToString(value)
                } catch (e: Exception) {
                    logger.debug("IssuerSignedItem デコード失敗: ${e.message}")
                }
            }
            if (claims.isNotEmpty()) result[namespace] = claims
        }
        return result
    }

    private fun dataItemToString(item: DataItem): String =
        when (item) {
            is Tstr -> {
                item.value
            }

            is Bstr -> {
                "[binary ${item.value.size} bytes]"
            }

            is Tagged -> {
                val inner = item.taggedItem
                if (inner is Tstr) inner.value else dataItemToString(inner)
            }

            else -> {
                item.toString()
            }
        }
}
