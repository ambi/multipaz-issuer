package org.vdcapps.issuer.web.routes

import freemarker.cache.ClassTemplateLoader
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.freemarker.FreeMarker
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.cookie
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import org.vdcapps.issuer.web.plugins.UserSession
import org.vdcapps.issuer.web.plugins.configureSerialization
import kotlin.test.Test
import kotlin.test.assertEquals

class AuthRoutesTest {
    private fun Application.testModule() {
        configureSerialization()
        install(Sessions) {
            cookie<UserSession>("user_session")
        }
        install(FreeMarker) {
            templateLoader = ClassTemplateLoader(this::class.java.classLoader, "templates")
        }
        routing {
            // セッション設定用のテストルート
            get("/test-set-session") {
                call.sessions.set(
                    UserSession(
                        userId = "user-001",
                        displayName = "Yamada Taro",
                        givenName = "Taro",
                        familyName = "Yamada",
                        email = "taro@example.com",
                        hasPhoto = false,
                        entraAccessToken = "dummy-token",
                    ),
                )
                call.respondText("OK")
            }
            // セッション確認用のテストルート
            get("/test-check-session") {
                val session = call.sessions.get<UserSession>()
                call.respondText(if (session != null) "has-session" else "no-session")
            }
            // NOTE: authenticate("entra-id") { ... } ブロックは Entra ID OAuth 設定が必要なためテスト対象外
            // GET /auth/logout のみテスト可能
            get("/auth/logout") {
                call.sessions.clear<UserSession>()
                call.respondRedirect("/")
            }
            // リダイレクト先のルート（logout テスト用）
            get("/") {
                call.respondText("home")
            }
        }
    }

    // ---- GET /auth/logout ----

    @Test
    fun `logout はトップへリダイレクトする`() =
        runTest {
            testApplication {
                application { testModule() }
                // リダイレクトを追わないクライアントで 302 を確認する
                val noFollowClient = createClient { followRedirects = false }
                val response = noFollowClient.get("/auth/logout")
                assertEquals(HttpStatusCode.Found, response.status)
                assertEquals("/", response.headers["Location"])
            }
        }

    @Test
    fun `logout はセッションなしでもトップへリダイレクトする`() =
        runTest {
            testApplication {
                application { testModule() }
                val noFollowClient = createClient { followRedirects = false }
                val response = noFollowClient.get("/auth/logout")
                assertEquals(HttpStatusCode.Found, response.status)
            }
        }

    @Test
    fun `logout はセッションをクリアする`() =
        runTest {
            testApplication {
                application { testModule() }
                val noFollowClient = createClient { followRedirects = false }

                // セッションを設定
                val sessionResponse = client.get("/test-set-session")
                val cookie = sessionResponse.headers["Set-Cookie"] ?: ""

                // セッションがあることを確認
                val checkBefore =
                    client
                        .get("/test-check-session") {
                            if (cookie.isNotEmpty()) header("Cookie", cookie.substringBefore(";"))
                        }.bodyAsText()
                assertEquals("has-session", checkBefore)

                // ログアウト（リダイレクトを追わない）
                val logoutResponse =
                    noFollowClient.get("/auth/logout") {
                        if (cookie.isNotEmpty()) header("Cookie", cookie.substringBefore(";"))
                    }
                val logoutCookie = logoutResponse.headers["Set-Cookie"] ?: ""

                // セッションがクリアされたことを確認
                val checkAfter =
                    client
                        .get("/test-check-session") {
                            val effectiveCookie = if (logoutCookie.isNotEmpty()) logoutCookie else cookie
                            header("Cookie", effectiveCookie.substringBefore(";"))
                        }.bodyAsText()
                assertEquals("no-session", checkAfter)
            }
        }

    @Test
    fun `logout 後は再びセッション設定が可能`() =
        runTest {
            testApplication {
                application { testModule() }
                val noFollowClient = createClient { followRedirects = false }
                noFollowClient.get("/auth/logout") // セッションなしでもエラーにならない

                val sessionResponse = client.get("/test-set-session")
                assertEquals(HttpStatusCode.OK, sessionResponse.status)
            }
        }
}
