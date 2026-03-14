package org.vdcapps.issuer.web.routes

import freemarker.cache.ClassTemplateLoader
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
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
import kotlinx.datetime.LocalDate
import org.vdcapps.issuer.application.IssueCredentialUseCase
import org.vdcapps.issuer.domain.credential.CredentialIssuanceService
import org.vdcapps.issuer.domain.credential.InMemoryIssuanceSessionRepository
import org.vdcapps.issuer.domain.credential.NonceStore
import org.vdcapps.issuer.infrastructure.multipaz.IssuerKeyStore
import org.vdcapps.issuer.infrastructure.multipaz.PhotoIdBuilder
import org.vdcapps.issuer.web.plugins.UserSession
import org.vdcapps.issuer.web.plugins.configureSerialization
import org.vdcapps.issuer.web.util.RateLimiter
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * POST /issue エンドポイントのテスト。
 * CSRF トークン検証・生年月日バリデーション・未ログイン時のリダイレクトを検証する。
 */
class IssueRoutesTest {
    private val csrfToken = "test-csrf-token-abc123"
    private val validSession =
        UserSession(
            userId = "user-001",
            displayName = "Yamada Taro",
            givenName = "Taro",
            familyName = "Yamada",
            email = "taro@example.com",
            hasPhoto = false,
            csrfToken = csrfToken,
        )

    @BeforeTest
    fun setUp() {
        RateLimiter.clearForTesting()
    }

    // ---- 未ログイン時 ----

    @Test
    fun `セッションなしで POST issue はトップへリダイレクトする`() =
        runTest {
            testApplication {
                application { testModule() }
                val noFollow = createClient { followRedirects = false }
                val response =
                    noFollow.submitForm(
                        "/issue",
                        formParameters =
                            Parameters.build {
                                append("_csrf", csrfToken)
                                append("familyName", "Yamada")
                                append("givenName", "Taro")
                                append("birthDate", "1990-01-15")
                            },
                    )
                assertEquals(HttpStatusCode.Found, response.status)
                assertEquals("/", response.headers["Location"])
            }
        }

    // ---- CSRF 検証 ----

    @Test
    fun `CSRF トークンなしで POST issue は 403 を返す`() =
        runTest {
            testApplication {
                application { testModule() }
                val sessionCookie = getSessionCookie()
                val noFollow = createClient { followRedirects = false }
                val response =
                    noFollow.submitForm(
                        "/issue",
                        formParameters =
                            Parameters.build {
                                // _csrf を送らない
                                append("familyName", "Yamada")
                                append("givenName", "Taro")
                                append("birthDate", "1990-01-15")
                            },
                    ) {
                        header("Cookie", sessionCookie)
                    }
                assertEquals(HttpStatusCode.Forbidden, response.status)
            }
        }

    @Test
    fun `不正な CSRF トークンで POST issue は 403 を返す`() =
        runTest {
            testApplication {
                application { testModule() }
                val sessionCookie = getSessionCookie()
                val noFollow = createClient { followRedirects = false }
                val response =
                    noFollow.submitForm(
                        "/issue",
                        formParameters =
                            Parameters.build {
                                append("_csrf", "wrong-csrf-token")
                                append("familyName", "Yamada")
                                append("givenName", "Taro")
                                append("birthDate", "1990-01-15")
                            },
                    ) {
                        header("Cookie", sessionCookie)
                    }
                assertEquals(HttpStatusCode.Forbidden, response.status)
            }
        }

    @Test
    fun `正しい CSRF トークンで POST issue は 403 にならない`() =
        runTest {
            testApplication {
                application { testModule() }
                val sessionCookie = getSessionCookie()
                val noFollow = createClient { followRedirects = false }
                val response =
                    noFollow.submitForm(
                        "/issue",
                        formParameters =
                            Parameters.build {
                                append("_csrf", csrfToken)
                                append("familyName", "Yamada")
                                append("givenName", "Taro")
                                append("birthDate", "1990-01-15")
                            },
                    ) {
                        header("Cookie", sessionCookie)
                    }
                assertFalse(
                    response.status == HttpStatusCode.Forbidden,
                    "正しい CSRF トークンで 403 にならないこと",
                )
            }
        }

    // ---- 入力バリデーション ----

    @Test
    fun `必須項目が空の場合はプロフィールページにエラーメッセージを返す`() =
        runTest {
            testApplication {
                application { testModule() }
                val sessionCookie = getSessionCookie()
                val response =
                    client.submitForm(
                        "/issue",
                        formParameters =
                            Parameters.build {
                                append("_csrf", csrfToken)
                                append("familyName", "")
                                append("givenName", "")
                                append("birthDate", "")
                            },
                    ) {
                        header("Cookie", sessionCookie)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                val body = response.bodyAsText()
                assertTrue(body.contains("全ての項目を入力してください"), "バリデーションエラーメッセージが含まれること")
            }
        }

    @Test
    fun `生年月日が不正な形式の場合はプロフィールページにエラーメッセージを返す`() =
        runTest {
            testApplication {
                application { testModule() }
                val sessionCookie = getSessionCookie()
                val response =
                    client.submitForm(
                        "/issue",
                        formParameters =
                            Parameters.build {
                                append("_csrf", csrfToken)
                                append("familyName", "Yamada")
                                append("givenName", "Taro")
                                append("birthDate", "not-a-date")
                            },
                    ) {
                        header("Cookie", sessionCookie)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                val body = response.bodyAsText()
                assertTrue(body.contains("生年月日の形式が正しくありません"), "日付形式エラーメッセージが含まれること")
            }
        }

    @Test
    fun `生年月日が未来の日付の場合はプロフィールページにエラーメッセージを返す`() =
        runTest {
            testApplication {
                application { testModule() }
                val sessionCookie = getSessionCookie()
                val response =
                    client.submitForm(
                        "/issue",
                        formParameters =
                            Parameters.build {
                                append("_csrf", csrfToken)
                                append("familyName", "Yamada")
                                append("givenName", "Taro")
                                append("birthDate", "2099-01-01")
                            },
                    ) {
                        header("Cookie", sessionCookie)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                val body = response.bodyAsText()
                assertTrue(body.contains("未来の日付にできません"), "未来日付エラーメッセージが含まれること")
            }
        }

    @Test
    fun `生年月日が 150 年以上前の場合はプロフィールページにエラーメッセージを返す`() =
        runTest {
            testApplication {
                application { testModule() }
                val sessionCookie = getSessionCookie()
                val response =
                    client.submitForm(
                        "/issue",
                        formParameters =
                            Parameters.build {
                                append("_csrf", csrfToken)
                                append("familyName", "Yamada")
                                append("givenName", "Taro")
                                append("birthDate", "1800-01-01")
                            },
                    ) {
                        header("Cookie", sessionCookie)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                val body = response.bodyAsText()
                assertTrue(body.contains("生年月日の値が不正です"), "150 年以上前エラーメッセージが含まれること")
            }
        }

    @Test
    fun `有効な入力で POST issue はオファーページへリダイレクトする`() =
        runTest {
            testApplication {
                application { testModule() }
                val sessionCookie = getSessionCookie()
                val noFollow = createClient { followRedirects = false }
                val response =
                    noFollow.submitForm(
                        "/issue",
                        formParameters =
                            Parameters.build {
                                append("_csrf", csrfToken)
                                append("familyName", "Yamada")
                                append("givenName", "Taro")
                                append("birthDate", "1990-01-15")
                            },
                    ) {
                        header("Cookie", sessionCookie)
                    }
                assertEquals(HttpStatusCode.Found, response.status)
                val location = response.headers["Location"] ?: ""
                assertTrue(location.startsWith("/offer/"), "オファーページへリダイレクトすること: $location")
            }
        }

    // ---- プロフィールページにCSRFトークンが含まれること ----

    @Test
    fun `POST issue のバリデーションエラー時に CSRF トークンがレスポンスに含まれる`() =
        runTest {
            testApplication {
                application { testModule() }
                val sessionCookie = getSessionCookie()
                // 空フォームを送信（CSRF は正しく送る）→ バリデーションエラーでプロフィールページが返る
                val body =
                    client
                        .submitForm(
                            "/issue",
                            formParameters =
                                Parameters.build {
                                    append("_csrf", csrfToken)
                                    append("familyName", "")
                                    append("givenName", "")
                                    append("birthDate", "")
                                },
                        ) {
                            header("Cookie", sessionCookie)
                        }.bodyAsText()
                // CSRF トークン値がフォームの hidden input に含まれること
                assertTrue(body.contains(csrfToken), "CSRF トークンがレスポンスに含まれること")
            }
        }

    // ---- テスト用モジュール設定 ----

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.getSessionCookie(): String {
        val sessionResponse = client.get("/test-set-session")
        return sessionResponse.headers["Set-Cookie"]?.substringBefore(";") ?: ""
    }

    private fun Application.testModule() {
        configureSerialization()
        install(Sessions) {
            cookie<UserSession>("user_session")
        }
        install(FreeMarker) {
            templateLoader = ClassTemplateLoader(this::class.java.classLoader, "templates")
        }

        val sessionRepository = InMemoryIssuanceSessionRepository()
        val issuanceService = CredentialIssuanceService(sessionRepository)
        val nonceStore = NonceStore()
        val tempKeyStore =
            java.io.File.createTempFile("test-issuer", ".p12").apply {
                deleteOnExit()
                delete()
            }
        val keyStore = IssuerKeyStore(tempKeyStore.absolutePath, "testpass")
        val photoIdBuilder = PhotoIdBuilder(keyStore)
        val issueUseCase =
            IssueCredentialUseCase(
                issuanceService = issuanceService,
                photoIdBuilder = photoIdBuilder,
                issuingCountry = "JP",
                issuingAuthority = "Test Issuer",
                validityDays = 365,
            )

        routing {
            // セッション設定用のテストルート
            get("/test-set-session") {
                call.sessions.set(validSession)
                call.respondText("OK")
            }
            configureOid4vciRoutes(
                baseUrl = "http://localhost",
                issuanceService = issuanceService,
                issueCredentialUseCase = issueUseCase,
                photoIdBuilder = photoIdBuilder,
                nonceStore = nonceStore,
            )
        }
    }
}

/** テスト用のダミー IssuanceSession を生成するための拡張ヘルパー。 */
private fun buildTestCredential() =
    org.vdcapps.issuer.domain.credential.PhotoIdCredential(
        familyName = "Yamada",
        givenName = "Taro",
        birthDate = LocalDate(1990, 1, 15),
        portrait = null,
        documentNumber = "DOC001234567",
        issuingCountry = "JP",
        issuingAuthority = "Test Issuer",
        issueDate = LocalDate(2025, 1, 1),
        expiryDate = LocalDate(2026, 12, 31),
    )
