package org.vdcapps.verifier.web

import freemarker.cache.ClassTemplateLoader
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.freemarker.FreeMarker
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.vdcapps.verifier.application.VerifyCredentialUseCase
import org.vdcapps.verifier.domain.verification.InMemoryVerificationSessionRepository
import org.vdcapps.verifier.domain.verification.VerificationResult
import org.vdcapps.verifier.domain.verification.VerificationService
import org.vdcapps.verifier.infrastructure.multipaz.MdocVerifier
import org.vdcapps.verifier.web.plugins.configureSerialization
import org.vdcapps.verifier.web.routes.configureVerifierRoutes
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VerifierIntegrationTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun Application.testModule(
        verificationService: VerificationService,
        mdocVerifier: MdocVerifier,
    ) {
        install(FreeMarker) {
            templateLoader = ClassTemplateLoader(this::class.java.classLoader, "templates")
        }
        configureSerialization()
        routing {
            configureVerifierRoutes(
                "http://localhost",
                verificationService,
                VerifyCredentialUseCase(verificationService, mdocVerifier),
            )
        }
    }

    private fun sampleResult() =
        VerificationResult(
            docType = "org.iso.23220.photoid.1",
            claims = mapOf("org.iso.23220.2" to mapOf("family_name" to "Yamada", "given_name" to "Taro")),
            issuerCertificateSubject = "CN=Test Issuer",
            validFrom = Instant.now().minusSeconds(60),
            validUntil = Instant.now().plusSeconds(3600),
        )

    // ====== GET /verifier ======

    @Test
    fun `GET verifier は 200 を返し QR コード画像を含む HTML を返す`() =
        runTest {
            val svc = VerificationService(InMemoryVerificationSessionRepository())
            testApplication {
                application { testModule(svc, mockk()) }
                val response = client.get("/verifier")
                assertEquals(HttpStatusCode.OK, response.status)
                val body = response.bodyAsText()
                assertTrue(body.contains("img"), "QR コード img タグが含まれるはず")
                assertTrue(body.contains("openid4vp://"), "OID4VP URI が含まれるはず")
            }
        }

    // ====== GET /verifier/request/{sessionId} ======

    @Test
    fun `GET verifier request 有効なセッション ID で 200 と Authorization Request JSON を返す`() =
        runTest {
            val svc = VerificationService(InMemoryVerificationSessionRepository())
            val session = svc.createSession()

            testApplication {
                application { testModule(svc, mockk()) }
                val response = client.get("/verifier/request/${session.id}")
                assertEquals(HttpStatusCode.OK, response.status)

                val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals("vp_token", body["response_type"]?.jsonPrimitive?.content)
                assertNotNull(body["dcql_query"], "dcql_query が含まれるはず")
                assertNotNull(body["nonce"])
                assertNotNull(body["response_uri"])
                assertEquals("direct_post", body["response_mode"]?.jsonPrimitive?.content)
            }
        }

    @Test
    fun `GET verifier request の dcql_query に photo ID の doctype_value が含まれる`() =
        runTest {
            val svc = VerificationService(InMemoryVerificationSessionRepository())
            val session = svc.createSession()

            testApplication {
                application { testModule(svc, mockk()) }
                val body = json.parseToJsonElement(client.get("/verifier/request/${session.id}").bodyAsText()).jsonObject
                val dcql = body["dcql_query"]!!.jsonObject
                val credentials = dcql["credentials"]!!.toString()
                assertTrue(credentials.contains("org.iso.23220.photoid.1"))
            }
        }

    @Test
    fun `GET verifier request 存在しないセッション ID で 404 を返す`() =
        runTest {
            testApplication {
                application { testModule(VerificationService(InMemoryVerificationSessionRepository()), mockk()) }
                val response = client.get("/verifier/request/no-such-id")
                assertEquals(HttpStatusCode.NotFound, response.status)
            }
        }

    @Test
    fun `GET verifier request PENDING でないセッションは 400 を返す`() =
        runTest {
            val svc = VerificationService(InMemoryVerificationSessionRepository())
            val session = svc.createSession()
            svc.markResponseReceived(session)

            testApplication {
                application { testModule(svc, mockk()) }
                val response = client.get("/verifier/request/${session.id}")
                assertEquals(HttpStatusCode.BadRequest, response.status)
                val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals("invalid_request", body["error"]?.jsonPrimitive?.content)
            }
        }

    // ====== POST /verifier/response ======

    @Test
    fun `POST verifier response vp_token なしで 400 を返す`() =
        runTest {
            testApplication {
                application { testModule(VerificationService(InMemoryVerificationSessionRepository()), mockk()) }
                val response =
                    client.post("/verifier/response") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"state":"some-id"}""")
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
                val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals("invalid_request", body["error"]?.jsonPrimitive?.content)
            }
        }

    @Test
    fun `POST verifier response 不明な state で 400 を返す`() =
        runTest {
            testApplication {
                application { testModule(VerificationService(InMemoryVerificationSessionRepository()), mockk()) }
                val response =
                    client.post("/verifier/response") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"vp_token":"dGVzdA","state":"ghost-id"}""")
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
                val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals("invalid_request", body["error"]?.jsonPrimitive?.content)
            }
        }

    @Test
    fun `POST verifier response 成功時に redirect_uri を返す`() =
        runTest {
            val svc = VerificationService(InMemoryVerificationSessionRepository())
            val session = svc.createSession()
            val mdocVerifier = mockk<MdocVerifier>()
            every { mdocVerifier.verify(any()) } returns sampleResult()

            val validToken = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32))

            testApplication {
                application { testModule(svc, mdocVerifier) }
                val response =
                    client.post("/verifier/response") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"vp_token":"$validToken","state":"${session.id}"}""")
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
                assertNotNull(body["redirect_uri"])
                assertTrue(body["redirect_uri"]!!.jsonPrimitive.content.contains(session.id))
            }
        }

    @Test
    fun `POST verifier response MdocVerifier が例外を投げると 400 を返す`() =
        runTest {
            val svc = VerificationService(InMemoryVerificationSessionRepository())
            val session = svc.createSession()
            val mdocVerifier = mockk<MdocVerifier>()
            every { mdocVerifier.verify(any()) } throws IllegalArgumentException("署名検証失敗")

            val validToken = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32))

            testApplication {
                application { testModule(svc, mdocVerifier) }
                val response =
                    client.post("/verifier/response") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"vp_token":"$validToken","state":"${session.id}"}""")
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
                val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals("invalid_vp_token", body["error"]?.jsonPrimitive?.content)
            }
        }

    @Test
    fun `POST verifier response form-encoded でも正しく処理される`() =
        runTest {
            val svc = VerificationService(InMemoryVerificationSessionRepository())
            val session = svc.createSession()
            val mdocVerifier = mockk<MdocVerifier>()
            every { mdocVerifier.verify(any()) } returns sampleResult()

            val validToken = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32))

            testApplication {
                application { testModule(svc, mdocVerifier) }
                val response =
                    client.post("/verifier/response") {
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody("vp_token=$validToken&state=${session.id}")
                    }
                assertEquals(HttpStatusCode.OK, response.status)
            }
        }

    @Test
    fun `POST verifier response DCQL 形式の vp_token（JSON オブジェクト）を処理できる`() =
        runTest {
            val svc = VerificationService(InMemoryVerificationSessionRepository())
            val session = svc.createSession()
            val mdocVerifier = mockk<MdocVerifier>()
            every { mdocVerifier.verify(any()) } returns sampleResult()

            val innerToken = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32))

            testApplication {
                application { testModule(svc, mdocVerifier) }
                // vp_token が DCQL JSON オブジェクト {"photo-id": "<base64>"}
                val body = """{"vp_token":{"photo-id":"$innerToken"},"state":"${session.id}"}"""
                val response =
                    client.post("/verifier/response") {
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
            }
        }

    @Test
    fun `POST verifier response DCQL 形式の vp_token（配列値）を処理できる`() =
        runTest {
            val svc = VerificationService(InMemoryVerificationSessionRepository())
            val session = svc.createSession()
            val mdocVerifier = mockk<MdocVerifier>()
            every { mdocVerifier.verify(any()) } returns sampleResult()

            val innerToken = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32))

            testApplication {
                application { testModule(svc, mdocVerifier) }
                // vp_token が DCQL JSON オブジェクト {"photo-id": ["<base64>"]}
                val body = """{"vp_token":{"photo-id":["$innerToken"]},"state":"${session.id}"}"""
                val response =
                    client.post("/verifier/response") {
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
            }
        }

    // ====== GET /verifier/result/{sessionId} ======

    @Test
    fun `GET verifier result PENDING セッションは JSON で status pending を返す`() =
        runTest {
            val svc = VerificationService(InMemoryVerificationSessionRepository())
            val session = svc.createSession()

            testApplication {
                application { testModule(svc, mockk()) }
                val response =
                    client.get("/verifier/result/${session.id}") {
                        accept(ContentType.Application.Json)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals("pending", body["status"]?.jsonPrimitive?.content)
            }
        }

    @Test
    fun `GET verifier result RESPONSE_RECEIVED セッションは JSON で status pending を返す`() =
        runTest {
            val svc = VerificationService(InMemoryVerificationSessionRepository())
            val session = svc.createSession()
            svc.markResponseReceived(session)

            testApplication {
                application { testModule(svc, mockk()) }
                val response =
                    client.get("/verifier/result/${session.id}") {
                        accept(ContentType.Application.Json)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals("pending", body["status"]?.jsonPrimitive?.content)
            }
        }

    @Test
    fun `GET verifier result VERIFIED セッションは JSON で status verified を返す`() =
        runTest {
            val svc = VerificationService(InMemoryVerificationSessionRepository())
            val session = svc.createSession()
            svc.markVerified(session, sampleResult())

            testApplication {
                application { testModule(svc, mockk()) }
                val response =
                    client.get("/verifier/result/${session.id}") {
                        accept(ContentType.Application.Json)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals("verified", body["status"]?.jsonPrimitive?.content)
            }
        }

    @Test
    fun `GET verifier result FAILED セッションは JSON で status failed を返す`() =
        runTest {
            val svc = VerificationService(InMemoryVerificationSessionRepository())
            val session = svc.createSession()
            svc.markFailed(session, "検証失敗")

            testApplication {
                application { testModule(svc, mockk()) }
                val response =
                    client.get("/verifier/result/${session.id}") {
                        accept(ContentType.Application.Json)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals("failed", body["status"]?.jsonPrimitive?.content)
            }
        }

    @Test
    fun `GET verifier result 存在しないセッション ID は HTML エラーページを返す`() =
        runTest {
            testApplication {
                application { testModule(VerificationService(InMemoryVerificationSessionRepository()), mockk()) }
                // FreeMarker の error.ftl を返すのでステータス 200 だが内容はエラーメッセージ
                val response = client.get("/verifier/result/ghost-id")
                assertEquals(HttpStatusCode.OK, response.status)
                assertTrue(response.bodyAsText().contains("セッションが見つかりません"))
            }
        }

    // ====== E2E フロー（POST response → GET result）======

    @Test
    fun `E2E：vp_token POST 後に result が verified になる`() =
        runTest {
            val svc = VerificationService(InMemoryVerificationSessionRepository())
            val session = svc.createSession()
            val mdocVerifier = mockk<MdocVerifier>()
            every { mdocVerifier.verify(any()) } returns sampleResult()
            val validToken = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32))

            testApplication {
                application { testModule(svc, mdocVerifier) }

                // 1. vp_token を POST
                val postResp =
                    client.post("/verifier/response") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"vp_token":"$validToken","state":"${session.id}"}""")
                    }
                assertEquals(HttpStatusCode.OK, postResp.status)

                // 2. result をポーリング
                val getResp =
                    client.get("/verifier/result/${session.id}") {
                        accept(ContentType.Application.Json)
                    }
                val body = json.parseToJsonElement(getResp.bodyAsText()).jsonObject
                assertEquals("verified", body["status"]?.jsonPrimitive?.content)
            }
        }

    @Test
    fun `E2E：MdocVerifier 失敗後に result が failed になる`() =
        runTest {
            val svc = VerificationService(InMemoryVerificationSessionRepository())
            val session = svc.createSession()
            val mdocVerifier = mockk<MdocVerifier>()
            every { mdocVerifier.verify(any()) } throws IllegalArgumentException("署名不正")
            val validToken = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32))

            testApplication {
                application { testModule(svc, mdocVerifier) }

                // 1. vp_token を POST（失敗）
                val postResp =
                    client.post("/verifier/response") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"vp_token":"$validToken","state":"${session.id}"}""")
                    }
                assertEquals(HttpStatusCode.BadRequest, postResp.status)

                // 2. result をポーリング
                val getResp =
                    client.get("/verifier/result/${session.id}") {
                        accept(ContentType.Application.Json)
                    }
                val body = json.parseToJsonElement(getResp.bodyAsText()).jsonObject
                assertEquals("failed", body["status"]?.jsonPrimitive?.content)
            }
        }

    // ====== VerificationSession 状態遷移：同一セッションの再処理 ======

    @Test
    fun `処理済み（VERIFIED）セッションへの POST は 400 を返す`() =
        runTest {
            val svc = VerificationService(InMemoryVerificationSessionRepository())
            val session = svc.createSession()
            svc.markResponseReceived(session)
            svc.markVerified(session, sampleResult())

            testApplication {
                application { testModule(svc, mockk()) }
                val response =
                    client.post("/verifier/response") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"vp_token":"dGVzdA","state":"${session.id}"}""")
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
                assertEquals(
                    "invalid_request",
                    json
                        .parseToJsonElement(response.bodyAsText())
                        .jsonObject["error"]
                        ?.jsonPrimitive
                        ?.content,
                )
            }
        }
}
