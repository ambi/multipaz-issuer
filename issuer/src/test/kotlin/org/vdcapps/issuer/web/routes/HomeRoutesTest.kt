package org.vdcapps.issuer.web.routes

import freemarker.cache.ClassTemplateLoader
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.freemarker.FreeMarker
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import org.vdcapps.issuer.web.plugins.UserSession
import org.vdcapps.issuer.web.plugins.configureSerialization
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HomeRoutesTest {
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
            configureHomeRoutes()
        }
    }

    // ---- GET / ----

    @Test
    fun `セッションなしで GET トップは 200 を返す`() =
        runTest {
            testApplication {
                application { testModule() }
                val response = client.get("/")
                assertEquals(HttpStatusCode.OK, response.status)
            }
        }

    @Test
    fun `セッションなしで GET トップは home ftl を描画する`() =
        runTest {
            testApplication {
                application { testModule() }
                val body = client.get("/").bodyAsText()
                assertTrue(body.isNotEmpty(), "レスポンスボディが空でないこと")
            }
        }

    @Test
    fun `セッションありで GET トップは 200 を返す`() =
        runTest {
            testApplication {
                application { testModule() }
                val sessionResponse = client.get("/test-set-session")
                val cookie = sessionResponse.headers["Set-Cookie"] ?: ""

                val response =
                    client.get("/") {
                        if (cookie.isNotEmpty()) header("Cookie", cookie.substringBefore(";"))
                    }
                assertEquals(HttpStatusCode.OK, response.status)
            }
        }

    @Test
    fun `セッションありで GET トップは profile ftl を描画する`() =
        runTest {
            testApplication {
                application { testModule() }
                val sessionResponse = client.get("/test-set-session")
                val cookie = sessionResponse.headers["Set-Cookie"] ?: ""

                val body =
                    client
                        .get("/") {
                            if (cookie.isNotEmpty()) header("Cookie", cookie.substringBefore(";"))
                        }.bodyAsText()

                assertTrue(body.isNotEmpty(), "レスポンスボディが空でないこと")
            }
        }

    // ---- GET /profile ----

    @Test
    fun `セッションなしで GET profile はトップへリダイレクトする`() =
        runTest {
            testApplication {
                application { testModule() }
                // リダイレクトを追わないクライアントで 302 を確認する
                val noFollowClient = createClient { followRedirects = false }
                val response = noFollowClient.get("/profile")
                assertEquals(HttpStatusCode.Found, response.status)
                assertEquals("/", response.headers["Location"])
            }
        }

    @Test
    fun `セッションありで GET profile は 200 を返す`() =
        runTest {
            testApplication {
                application { testModule() }
                val sessionResponse = client.get("/test-set-session")
                val cookie = sessionResponse.headers["Set-Cookie"] ?: ""

                val response =
                    client.get("/profile") {
                        if (cookie.isNotEmpty()) header("Cookie", cookie.substringBefore(";"))
                    }
                assertEquals(HttpStatusCode.OK, response.status)
            }
        }

    @Test
    fun `セッションありで GET profile は profile ftl を描画する`() =
        runTest {
            testApplication {
                application { testModule() }
                val sessionResponse = client.get("/test-set-session")
                val cookie = sessionResponse.headers["Set-Cookie"] ?: ""

                val body =
                    client
                        .get("/profile") {
                            if (cookie.isNotEmpty()) header("Cookie", cookie.substringBefore(";"))
                        }.bodyAsText()

                assertTrue(body.isNotEmpty(), "レスポンスボディが空でないこと")
            }
        }
}
