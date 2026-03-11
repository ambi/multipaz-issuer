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
import org.multipaz.mdoc.mso.MobileSecurityObject
import org.slf4j.LoggerFactory
import org.vdcapps.verifier.domain.verification.VerificationResult
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant

/**
 * OID4VP の vp_token（base64url デコード済み DeviceResponse CBOR バイト列）を検証し
 * [VerificationResult] を返す。
 */
@OptIn(kotlin.time.ExperimentalTime::class)
class MdocVerifier {
    private val logger = LoggerFactory.getLogger(MdocVerifier::class.java)

    fun verify(deviceResponseBytes: ByteArray): VerificationResult {
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

        return VerificationResult(
            docType = docType,
            claims = extractClaims(nameSpacesMap),
            issuerCertificateSubject = issuerCert.subjectX500Principal.name,
            validFrom = validFrom,
            validUntil = validUntil,
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
     * Sig_Structure = ["Signature1", protected_bytes, external_aad(空), payload_bytes]
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
