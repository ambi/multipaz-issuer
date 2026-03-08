package org.multipaz.issuer.domain.identity

/**
 * ユーザー情報（Entra ID の OIDC + Microsoft Graph API から取得）。
 * Photo ID の内容を組み立てるために使用する。
 */
data class EntraUser(
    val id: String,
    val displayName: String,
    val givenName: String,
    val familyName: String,
    val email: String?,
    /** Graph API /me/photo/$value から取得した JPEG バイト列。未設定の場合は null。 */
    val photo: ByteArray?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        return id == (other as EntraUser).id
    }

    override fun hashCode(): Int = id.hashCode()
}
