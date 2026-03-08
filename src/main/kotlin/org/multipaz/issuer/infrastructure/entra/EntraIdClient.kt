package org.multipaz.issuer.infrastructure.entra

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.multipaz.issuer.domain.identity.EntraUser
import org.slf4j.LoggerFactory

/**
 * Microsoft Graph API を通じてログインユーザーの情報を取得するクライアント。
 * Ktor の OAuth プラグインが発行した Entra ID アクセストークンを使用する。
 */
class EntraIdClient(private val httpClient: HttpClient) {

    private val logger = LoggerFactory.getLogger(EntraIdClient::class.java)

    /**
     * Graph API でユーザー情報と顔写真を取得して [EntraUser] を返す。
     * @param accessToken Entra ID が発行した MS Graph 用アクセストークン
     */
    suspend fun getUserInfo(accessToken: String): EntraUser {
        val graphUser = httpClient.get("https://graph.microsoft.com/v1.0/me") {
            bearerAuth(accessToken)
        }.body<GraphApiUser>()

        val photo = fetchPhoto(accessToken)

        return EntraUser(
            id = graphUser.id,
            displayName = graphUser.displayName,
            givenName = graphUser.givenName ?: graphUser.displayName.substringBefore(" "),
            familyName = graphUser.surname ?: graphUser.displayName.substringAfterLast(" "),
            email = graphUser.mail ?: graphUser.userPrincipalName,
            photo = photo,
        )
    }

    private suspend fun fetchPhoto(accessToken: String): ByteArray? = try {
        val response = httpClient.get("https://graph.microsoft.com/v1.0/me/photo/\$value") {
            bearerAuth(accessToken)
        }
        if (response.status.isSuccess()) response.body<ByteArray>() else null
    } catch (e: Exception) {
        logger.debug("ユーザー写真の取得に失敗しました: ${e.message}")
        null
    }
}

@Serializable
data class GraphApiUser(
    val id: String,
    val displayName: String,
    val givenName: String? = null,
    val surname: String? = null,
    val mail: String? = null,
    @SerialName("userPrincipalName") val userPrincipalName: String? = null,
)
