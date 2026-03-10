package org.vdcapps.verifier.domain.verification

import java.time.Instant

/**
 * mdoc 検証成功時に抽出したクレーム。
 *
 * @param docType 検証した mdoc の docType（例: "org.iso.23220.photoid.1"）
 * @param claims 名前空間 → 要素名 → 値 のマップ。値は文字列に変換済み。
 * @param issuerCertificateSubject 発行者 X.509 証明書の Subject DN
 * @param validFrom MSO の validFrom
 * @param validUntil MSO の validUntil
 */
data class VerificationResult(
    val docType: String,
    val claims: Map<String, Map<String, String>>,
    val issuerCertificateSubject: String,
    val validFrom: Instant,
    val validUntil: Instant,
)
