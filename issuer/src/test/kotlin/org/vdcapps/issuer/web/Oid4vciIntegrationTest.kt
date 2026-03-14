package org.vdcapps.issuer.web

import freemarker.cache.ClassTemplateLoader
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.freemarker.FreeMarker
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.vdcapps.issuer.web.plugins.configureSerialization
import org.vdcapps.issuer.web.routes.RateLimiter
import org.vdcapps.issuer.web.routes.configureOid4vciRoutes
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * OID4VCI エンドポイントの統合テスト。
 * Ktor の testApplication を使ってサーバーをインメモリで起動する。
 *
 * NOTE: 完全な E2E テスト（Wallet との通信）は Multipaz Compose Wallet を使って手動検証する。
 * このテストはプロトコル層の正確性を検証する。
 */
class Oid4vciIntegrationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun setUp() {
        // テスト間でレート制限をリセットする
        RateLimiter.clearForTesting()
    }

    // ---- /.well-known/openid-credential-issuer ----

    @Test
    fun `credential issuer メタデータが正しい形式で返る`() =
        runTest {
            testApplication {
                application { testModule() }
                val response = client.get("/.well-known/openid-credential-issuer")
                assertEquals(HttpStatusCode.OK, response.status)

                val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
                assertNotNull(body["credential_issuer"])
                assertNotNull(body["credential_endpoint"])
                assertNotNull(body["credential_configurations_supported"])

                val configs = body["credential_configurations_supported"]!!.jsonObject
                assertTrue(
                    configs.containsKey("org.iso.23220.photoid.1"),
                    "Photo ID doctype がメタデータに含まれていること",
                )
            }
        }

    @Test
    fun `credential issuer メタデータに nonce_endpoint が含まれる`() =
        runTest {
            testApplication {
                application { testModule() }
                val body =
                    json
                        .parseToJsonElement(
                            client.get("/.well-known/openid-credential-issuer").bodyAsText(),
                        ).jsonObject
                assertNotNull(body["nonce_endpoint"])
            }
        }

    @Test
    fun `credential configurations_supported に mso_mdoc format が含まれる`() =
        runTest {
            testApplication {
                application { testModule() }
                val body =
                    json
                        .parseToJsonElement(
                            client.get("/.well-known/openid-credential-issuer").bodyAsText(),
                        ).jsonObject
                val config =
                    body["credential_configurations_supported"]!!
                        .jsonObject["org.iso.23220.photoid.1"]!!
                        .jsonObject
                assertEquals("mso_mdoc", config["format"]?.jsonPrimitive?.content)
            }
        }

    // ---- /.well-known/oauth-authorization-server ----

    @Test
    fun `authorization server メタデータが正しい形式で返る`() =
        runTest {
            testApplication {
                application { testModule() }
                val response = client.get("/.well-known/oauth-authorization-server")
                assertEquals(HttpStatusCode.OK, response.status)

                val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
                assertNotNull(body["token_endpoint"])
                assertNotNull(body["grant_types_supported"])
            }
        }

    @Test
    fun `authorization server メタデータに pre-authorized_code grant が含まれる`() =
        runTest {
            testApplication {
                application { testModule() }
                val body =
                    json
                        .parseToJsonElement(
                            client.get("/.well-known/oauth-authorization-server").bodyAsText(),
                        ).jsonObject
                val grantTypes = body["grant_types_supported"]!!.jsonArray.map { it.jsonPrimitive.content }
                assertTrue(
                    grantTypes.contains("urn:ietf:params:oauth:grant-type:pre-authorized_code"),
                    "grant_types_supported に pre-authorized_code が含まれること",
                )
            }
        }

    @Test
    fun `authorization server メタデータに jwks_uri が含まれる`() =
        runTest {
            testApplication {
                application { testModule() }
                val body =
                    json
                        .parseToJsonElement(
                            client.get("/.well-known/oauth-authorization-server").bodyAsText(),
                        ).jsonObject
                assertNotNull(body["jwks_uri"])
                assertTrue(body["jwks_uri"]!!.jsonPrimitive.content.endsWith("/jwks"))
            }
        }

    // ---- POST /nonce ----

    @Test
    fun `nonce エンドポイントは c_nonce を返す`() =
        runTest {
            testApplication {
                application { testModule() }
                val response = client.post("/nonce")
                assertEquals(HttpStatusCode.OK, response.status)

                val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
                assertNotNull(body["c_nonce"], "c_nonce が含まれること")
                assertTrue(body["c_nonce"]!!.jsonPrimitive.content.isNotEmpty())
            }
        }

    @Test
    fun `nonce エンドポイントは c_nonce_expires_in を返す`() =
        runTest {
            testApplication {
                application { testModule() }
                val body =
                    json.parseToJsonElement(client.post("/nonce").bodyAsText()).jsonObject
                assertNotNull(body["c_nonce_expires_in"])
            }
        }

    @Test
    fun `nonce エンドポイントは呼ぶたびに異なる c_nonce を返す`() =
        runTest {
            testApplication {
                application { testModule() }
                val nonce1 =
                    json
                        .parseToJsonElement(client.post("/nonce").bodyAsText())
                        .jsonObject["c_nonce"]!!
                        .jsonPrimitive.content
                val nonce2 =
                    json
                        .parseToJsonElement(client.post("/nonce").bodyAsText())
                        .jsonObject["c_nonce"]!!
                        .jsonPrimitive.content
                assertTrue(nonce1 != nonce2, "nonce が毎回異なること")
            }
        }

    @Test
    fun `nonce レスポンスに Cache-Control no-store が含まれる`() =
        runTest {
            testApplication {
                application { testModule() }
                val response = client.post("/nonce")
                assertEquals("no-store", response.headers["Cache-Control"])
            }
        }

    // ---- POST /par ----

    @Test
    fun `par エンドポイントは 400 を返す`() =
        runTest {
            testApplication {
                application { testModule() }
                val response =
                    client.post("/par") {
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody("client_id=test")
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
                val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals("request_not_supported", body["error"]?.jsonPrimitive?.content)
            }
        }

    // ---- GET /jwks ----

    @Test
    fun `jwks エンドポイントは JWK Set を返す`() =
        runTest {
            testApplication {
                application { testModule() }
                val response = client.get("/jwks")
                assertEquals(HttpStatusCode.OK, response.status)

                val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
                assertNotNull(body["keys"])
                val keys = body["keys"]!!.jsonArray
                assertEquals(1, keys.size)
            }
        }

    @Test
    fun `jwks の鍵は EC P-256 鍵`() =
        runTest {
            testApplication {
                application { testModule() }
                val body =
                    json.parseToJsonElement(client.get("/jwks").bodyAsText()).jsonObject
                val key = body["keys"]!!.jsonArray[0].jsonObject
                assertEquals("EC", key["kty"]?.jsonPrimitive?.content)
                assertEquals("P-256", key["crv"]?.jsonPrimitive?.content)
                assertEquals("ES256", key["alg"]?.jsonPrimitive?.content)
                assertEquals("sig", key["use"]?.jsonPrimitive?.content)
            }
        }

    // ---- POST /token ----

    @Test
    fun `無効な grant_type で token エンドポイントを呼ぶと 400 が返る`() =
        runTest {
            testApplication {
                application { testModule() }
                val response =
                    client.post("/token") {
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody("grant_type=authorization_code&code=test")
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
                val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals("unsupported_grant_type", body["error"]?.jsonPrimitive?.content)
            }
        }

    @Test
    fun `pre-authorized_code なしで token エンドポイントを呼ぶと 400 が返る`() =
        runTest {
            testApplication {
                application { testModule() }
                val response =
                    client.post("/token") {
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody("grant_type=urn:ietf:params:oauth:grant-type:pre-authorized_code")
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
                val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals("invalid_request", body["error"]?.jsonPrimitive?.content)
            }
        }

    @Test
    fun `無効な pre-authorized_code で token エンドポイントを呼ぶと 400 が返る`() =
        runTest {
            testApplication {
                application { testModule() }
                val response =
                    client.post("/token") {
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody("grant_type=urn:ietf:params:oauth:grant-type:pre-authorized_code&pre-authorized_code=invalid")
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
                val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals("invalid_grant", body["error"]?.jsonPrimitive?.content)
            }
        }

    @Test
    fun `有効な pre-authorized_code で token エンドポイントを呼ぶと access_token が返る`() =
        runTest {
            val setup = TestSetup()
            val issuanceSession = setup.createTestSession()

            testApplication {
                application { testModule(setup) }
                val response =
                    client.post("/token") {
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody(
                            "grant_type=urn:ietf:params:oauth:grant-type:pre-authorized_code" +
                                "&pre-authorized_code=${issuanceSession.preAuthorizedCode}",
                        )
                    }
                assertEquals(HttpStatusCode.OK, response.status)

                val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
                assertNotNull(body["access_token"], "access_token が含まれること")
                assertTrue(body["access_token"]!!.jsonPrimitive.content.isNotEmpty())
            }
        }

    @Test
    fun `有効な pre-authorized_code で token エンドポイントを呼ぶと c_nonce が返る`() =
        runTest {
            val setup = TestSetup()
            val issuanceSession = setup.createTestSession()

            testApplication {
                application { testModule(setup) }
                val body =
                    json
                        .parseToJsonElement(
                            client
                                .post("/token") {
                                    contentType(ContentType.Application.FormUrlEncoded)
                                    setBody(
                                        "grant_type=urn:ietf:params:oauth:grant-type:pre-authorized_code" +
                                            "&pre-authorized_code=${issuanceSession.preAuthorizedCode}",
                                    )
                                }.bodyAsText(),
                        ).jsonObject
                assertNotNull(body["c_nonce"])
                assertEquals("DPoP", body["token_type"]?.jsonPrimitive?.content)
            }
        }

    @Test
    fun `同じ pre-authorized_code を 2 回使うと 2 回目は 400 が返る`() =
        runTest {
            val setup = TestSetup()
            val issuanceSession = setup.createTestSession()
            val code = issuanceSession.preAuthorizedCode

            testApplication {
                application { testModule(setup) }
                val body1 =
                    "grant_type=urn:ietf:params:oauth:grant-type:pre-authorized_code&pre-authorized_code=$code"

                // 1回目は成功
                val r1 =
                    client.post("/token") {
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody(body1)
                    }
                assertEquals(HttpStatusCode.OK, r1.status)

                // 2回目は失敗（セッション状態が PENDING ではない）
                val r2 =
                    client.post("/token") {
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody(body1)
                    }
                assertEquals(HttpStatusCode.BadRequest, r2.status)
            }
        }

    @Test
    fun `token レスポンスに Cache-Control no-store が含まれる`() =
        runTest {
            val setup = TestSetup()
            val issuanceSession = setup.createTestSession()

            testApplication {
                application { testModule(setup) }
                val response =
                    client.post("/token") {
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody(
                            "grant_type=urn:ietf:params:oauth:grant-type:pre-authorized_code" +
                                "&pre-authorized_code=${issuanceSession.preAuthorizedCode}",
                        )
                    }
                assertEquals("no-store", response.headers["Cache-Control"])
            }
        }

    // ---- POST /credential ----

    @Test
    fun `Bearer token なしで credential エンドポイントを呼ぶと 401 が返る`() =
        runTest {
            testApplication {
                application { testModule() }
                val response =
                    client.post("/credential") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"format":"mso_mdoc","doctype":"org.iso.23220.photoid.1"}""")
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
        }

    @Test
    fun `無効な Bearer token で credential エンドポイントを呼ぶと 401 が返る`() =
        runTest {
            testApplication {
                application { testModule() }
                val response =
                    client.post("/credential") {
                        contentType(ContentType.Application.Json)
                        header("Authorization", "Bearer invalid-token")
                        setBody("""{"format":"mso_mdoc","doctype":"org.iso.23220.photoid.1"}""")
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
        }

    @Test
    fun `有効な Bearer token でも proof なしだと 400 が返る`() =
        runTest {
            val setup = TestSetup()
            val issuanceSession = setup.createTestSession()

            testApplication {
                application { testModule(setup) }
                // token を取得
                val tokenBody =
                    json
                        .parseToJsonElement(
                            client
                                .post("/token") {
                                    contentType(ContentType.Application.FormUrlEncoded)
                                    setBody(
                                        "grant_type=urn:ietf:params:oauth:grant-type:pre-authorized_code" +
                                            "&pre-authorized_code=${issuanceSession.preAuthorizedCode}",
                                    )
                                }.bodyAsText(),
                        ).jsonObject
                val accessToken = tokenBody["access_token"]!!.jsonPrimitive.content

                val response =
                    client.post("/credential") {
                        contentType(ContentType.Application.Json)
                        header("Authorization", "Bearer $accessToken")
                        setBody("""{"format":"mso_mdoc","doctype":"org.iso.23220.photoid.1"}""")
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
                val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals("invalid_proof", body["error"]?.jsonPrimitive?.content)
            }
        }

    @Test
    fun `有効な Bearer token で不正な proof JWT だと 400 が返る`() =
        runTest {
            val setup = TestSetup()
            val issuanceSession = setup.createTestSession()

            testApplication {
                application { testModule(setup) }
                val tokenBody =
                    json
                        .parseToJsonElement(
                            client
                                .post("/token") {
                                    contentType(ContentType.Application.FormUrlEncoded)
                                    setBody(
                                        "grant_type=urn:ietf:params:oauth:grant-type:pre-authorized_code" +
                                            "&pre-authorized_code=${issuanceSession.preAuthorizedCode}",
                                    )
                                }.bodyAsText(),
                        ).jsonObject
                val accessToken = tokenBody["access_token"]!!.jsonPrimitive.content

                val response =
                    client.post("/credential") {
                        contentType(ContentType.Application.Json)
                        header("Authorization", "Bearer $accessToken")
                        setBody(
                            """{"format":"mso_mdoc","proof":{"proof_type":"jwt","jwt":"not-a-valid-jwt"}}""",
                        )
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
                val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals("invalid_proof", body["error"]?.jsonPrimitive?.content)
            }
        }

    @Test
    fun `DPoP ヘッダーの token も受け付ける`() =
        runTest {
            testApplication {
                application { testModule() }
                val response =
                    client.post("/credential") {
                        contentType(ContentType.Application.Json)
                        header("Authorization", "DPoP invalid-token")
                        setBody("""{"format":"mso_mdoc","doctype":"org.iso.23220.photoid.1"}""")
                    }
                // DPoP トークンは認識されるが不正なトークンなので 401
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
        }

    @Test
    fun `mso_mdoc 以外の format で credential を呼ぶと 400 が返る`() =
        runTest {
            val setup = TestSetup()
            val issuanceSession = setup.createTestSession()

            testApplication {
                application { testModule(setup) }
                val tokenBody =
                    json
                        .parseToJsonElement(
                            client
                                .post("/token") {
                                    contentType(ContentType.Application.FormUrlEncoded)
                                    setBody(
                                        "grant_type=urn:ietf:params:oauth:grant-type:pre-authorized_code" +
                                            "&pre-authorized_code=${issuanceSession.preAuthorizedCode}",
                                    )
                                }.bodyAsText(),
                        ).jsonObject
                val accessToken = tokenBody["access_token"]!!.jsonPrimitive.content

                val response =
                    client.post("/credential") {
                        contentType(ContentType.Application.Json)
                        header("Authorization", "Bearer $accessToken")
                        setBody("""{"format":"jwt_vc","doctype":"org.iso.23220.photoid.1"}""")
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
                val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals("unsupported_credential_format", body["error"]?.jsonPrimitive?.content)
            }
        }

    // ---- POST /credential: Content-Type 検証 ----

    @Test
    fun `application_json 以外の Content-Type で credential を呼ぶと 415 が返る`() =
        runTest {
            val setup = TestSetup()
            val issuanceSession = setup.createTestSession()

            testApplication {
                application { testModule(setup) }
                val tokenBody =
                    json
                        .parseToJsonElement(
                            client
                                .post("/token") {
                                    contentType(ContentType.Application.FormUrlEncoded)
                                    setBody(
                                        "grant_type=urn:ietf:params:oauth:grant-type:pre-authorized_code" +
                                            "&pre-authorized_code=${issuanceSession.preAuthorizedCode}",
                                    )
                                }.bodyAsText(),
                        ).jsonObject
                val accessToken = tokenBody["access_token"]!!.jsonPrimitive.content

                val response =
                    client.post("/credential") {
                        contentType(ContentType.Application.FormUrlEncoded)
                        header("Authorization", "Bearer $accessToken")
                        setBody("format=mso_mdoc")
                    }
                assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
                val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals("invalid_request", body["error"]?.jsonPrimitive?.content)
            }
        }

    // ---- レート制限 ----

    @Test
    fun `nonce エンドポイントが 60 回を超えると 429 が返る`() =
        runTest {
            testApplication {
                application { testModule() }
                repeat(60) { client.post("/nonce") }
                val response = client.post("/nonce")
                assertEquals(HttpStatusCode.TooManyRequests, response.status)
                val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals("rate_limit_exceeded", body["error"]?.jsonPrimitive?.content)
            }
        }

    @Test
    fun `token エンドポイントが 10 回を超えると 429 が返る`() =
        runTest {
            testApplication {
                application { testModule() }
                repeat(10) {
                    client.post("/token") {
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody("grant_type=urn:ietf:params:oauth:grant-type:pre-authorized_code&pre-authorized_code=dummy")
                    }
                }
                val response =
                    client.post("/token") {
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody("grant_type=urn:ietf:params:oauth:grant-type:pre-authorized_code&pre-authorized_code=dummy")
                    }
                assertEquals(HttpStatusCode.TooManyRequests, response.status)
                val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals("rate_limit_exceeded", body["error"]?.jsonPrimitive?.content)
            }
        }

    @Test
    fun `credential エンドポイントが 5 回を超えると 429 が返る`() =
        runTest {
            testApplication {
                application { testModule() }
                repeat(5) {
                    client.post("/credential") {
                        contentType(ContentType.Application.Json)
                        header("Authorization", "Bearer dummy")
                        setBody("""{"format":"mso_mdoc"}""")
                    }
                }
                val response =
                    client.post("/credential") {
                        contentType(ContentType.Application.Json)
                        header("Authorization", "Bearer dummy")
                        setBody("""{"format":"mso_mdoc"}""")
                    }
                assertEquals(HttpStatusCode.TooManyRequests, response.status)
                val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals("rate_limit_exceeded", body["error"]?.jsonPrimitive?.content)
            }
        }

    // ---- GET /offer/{sessionId} ----

    @Test
    fun `offer に存在しないセッション ID を指定すると 200 でエラーページが返る`() =
        runTest {
            testApplication {
                application { testModuleWithFreeMarker() }
                val response = client.get("/offer/nonexistent-session-id")
                // エラーページを FreeMarker で描画するため 200
                assertEquals(HttpStatusCode.OK, response.status)
            }
        }

    @Test
    fun `offer に有効なセッション ID を指定すると 200 が返る`() =
        runTest {
            val setup = TestSetup()
            val issuanceSession = setup.createTestSession()

            testApplication {
                application { testModuleWithFreeMarker(setup) }
                val response = client.get("/offer/${issuanceSession.id}")
                assertEquals(HttpStatusCode.OK, response.status)
            }
        }
}

/**
 * テスト用モジュールで共有する依存オブジェクト。
 * テストが事前にセッションを作成したり NonceStore を操作するために使う。
 */
private class TestSetup {
    val baseUrl = "http://localhost"
    val sessionRepository =
        org.vdcapps.issuer.domain.credential
            .InMemoryIssuanceSessionRepository()
    val issuanceService =
        org.vdcapps.issuer.domain.credential
            .CredentialIssuanceService(sessionRepository)
    val nonceStore =
        org.vdcapps.issuer.domain.credential
            .NonceStore()

    val tempKeyStore =
        java.io.File
            .createTempFile("test-issuer", ".p12")
            .apply {
                deleteOnExit()
                delete()
            }
    val keyStore =
        org.vdcapps.issuer.infrastructure.multipaz.IssuerKeyStore(
            tempKeyStore.absolutePath,
            "testpass",
        )
    val photoIdBuilder =
        org.vdcapps.issuer.infrastructure.multipaz
            .PhotoIdBuilder(keyStore)

    val issueUseCase =
        org.vdcapps.issuer.application.IssueCredentialUseCase(
            issuanceService = issuanceService,
            photoIdBuilder = photoIdBuilder,
            issuingCountry = "JP",
            issuingAuthority = "Test Issuer",
            validityDays = 365,
        )

    suspend fun createTestSession(): org.vdcapps.issuer.domain.credential.IssuanceSession {
        val credential =
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
        return issuanceService.createSession(credential)
    }
}

/**
 * テスト用のモジュール設定（JSON エンドポイントのみ）。
 * Entra ID 認証なしで OID4VCI エンドポイントのみを有効化する。
 */
private fun io.ktor.server.application.Application.testModule(setup: TestSetup = TestSetup()) {
    configureSerialization()
    routing {
        configureOid4vciRoutes(setup.baseUrl, setup.issuanceService, setup.issueUseCase, setup.photoIdBuilder, setup.nonceStore)
    }
}

/**
 * FreeMarker テンプレートも含むモジュール設定（/offer ルートのテスト用）。
 */
private fun io.ktor.server.application.Application.testModuleWithFreeMarker(setup: TestSetup = TestSetup()) {
    configureSerialization()
    install(FreeMarker) {
        templateLoader = ClassTemplateLoader(this::class.java.classLoader, "templates")
    }
    routing {
        configureOid4vciRoutes(setup.baseUrl, setup.issuanceService, setup.issueUseCase, setup.photoIdBuilder, setup.nonceStore)
    }
}
