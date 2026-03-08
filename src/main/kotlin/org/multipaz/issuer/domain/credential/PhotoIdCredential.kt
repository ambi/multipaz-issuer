package org.multipaz.issuer.domain.credential

import kotlinx.datetime.LocalDate

/**
 * ISO/IEC TS 23220-4 Photo ID として発行する証明書のドメインモデル。
 * Doctype: org.iso.23220.photoid.1
 */
data class PhotoIdCredential(
    val familyName: String,
    val givenName: String,
    val birthDate: LocalDate,
    /** JPEG 形式の顔写真。Entra ID の Graph API から取得。 */
    val portrait: ByteArray?,
    val documentNumber: String,
    val issuingCountry: String,
    val issuingAuthority: String,
    val issueDate: LocalDate,
    val expiryDate: LocalDate,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        return documentNumber == (other as PhotoIdCredential).documentNumber
    }

    override fun hashCode(): Int = documentNumber.hashCode()
}
