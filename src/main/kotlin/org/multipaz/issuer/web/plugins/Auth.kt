package org.multipaz.issuer.web.plugins

import io.ktor.client.HttpClient
import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.OAuthServerSettings
import io.ktor.server.auth.oauth
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import kotlinx.serialization.Serializable

/** ブラウザセッションに保存するユーザー情報。 */
@Serializable
data class UserSession(
    val userId: String,
    val displayName: String,
    val givenName: String,
    val familyName: String,
    val email: String?,
    /** Graph API から取得した写真が存在するか */
    val hasPhoto: Boolean,
    /** MS Graph API 呼び出しに使用する Entra ID アクセストークン */
    val entraAccessToken: String,
)

fun Application.configureAuth(
    httpClient: HttpClient,
    tenantId: String,
    clientId: String,
    clientSecret: String,
    redirectUri: String,
) {
    install(Sessions) {
        cookie<UserSession>("user_session") {
            cookie.path = "/"
            cookie.httpOnly = true
            cookie.secure = redirectUri.startsWith("https")
        }
    }

    install(Authentication) {
        oauth("entra-id") {
            urlProvider = { redirectUri }
            providerLookup = {
                OAuthServerSettings.OAuth2ServerSettings(
                    name = "entra-id",
                    authorizeUrl = "https://login.microsoftonline.com/$tenantId/oauth2/v2.0/authorize",
                    accessTokenUrl = "https://login.microsoftonline.com/$tenantId/oauth2/v2.0/token",
                    requestMethod = HttpMethod.Post,
                    clientId = clientId,
                    clientSecret = clientSecret,
                    defaultScopes = listOf(
                        "openid",
                        "profile",
                        "email",
                        "https://graph.microsoft.com/User.Read",
                        "https://graph.microsoft.com/User.ReadBasic.All",
                    ),
                )
            }
            client = httpClient
        }
    }
}
