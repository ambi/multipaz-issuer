package org.vdcapps.issuer.web.routes

import io.ktor.server.auth.OAuthAccessTokenResponse
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.freemarker.FreeMarkerContent
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import org.slf4j.LoggerFactory
import org.vdcapps.issuer.infrastructure.entra.EntraIdClient
import org.vdcapps.issuer.web.plugins.UserSession

private val logger = LoggerFactory.getLogger("AuthRoutes")

fun Route.configureAuthRoutes(entraIdClient: EntraIdClient) {
    // ログインページ → Entra ID の OIDC フローへリダイレクト
    authenticate("entra-id") {
        get("/auth/login") { /* Ktor OAuth プラグインが自動的にリダイレクト */ }

        get("/auth/callback") {
            val principal = call.principal<OAuthAccessTokenResponse.OAuth2>()
            if (principal == null) {
                call.respond(FreeMarkerContent("error.ftl", mapOf("message" to "認証に失敗しました。")))
                return@get
            }

            try {
                val user = entraIdClient.getUserInfo(principal.accessToken)
                call.sessions.set(
                    UserSession(
                        userId = user.id,
                        displayName = user.displayName,
                        givenName = user.givenName,
                        familyName = user.familyName,
                        email = user.email,
                        hasPhoto = user.photo != null,
                        entraAccessToken = principal.accessToken,
                    ),
                )
                call.respondRedirect("/profile")
            } catch (e: Exception) {
                logger.error("Graph API の呼び出しに失敗しました", e)
                call.respond(
                    FreeMarkerContent(
                        "error.ftl",
                        mapOf("message" to "ユーザー情報の取得に失敗しました: ${e.message}"),
                    ),
                )
            }
        }
    }

    get("/auth/logout") {
        call.sessions.clear<UserSession>()
        call.respondRedirect("/")
    }
}
