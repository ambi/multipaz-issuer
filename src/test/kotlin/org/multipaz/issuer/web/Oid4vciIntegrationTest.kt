package org.multipaz.issuer.web

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.issuer.web.plugins.configureSerialization
import org.multipaz.issuer.web.routes.configureOid4vciRoutes
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

    @Test
    fun `credential issuer メタデータが正しい形式で返る`() = runTest {
        testApplication {
            application { testModule() }
            val response = client.get("/.well-known/openid-credential-issuer")
            assertEquals(HttpStatusCode.OK, response.status)

            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertNotNull(body["credential_issuer"])
            assertNotNull(body["credential_endpoint"])
            assertNotNull(body["credential_configurations_supported"])

            val configs = body["credential_configurations_supported"]!!.jsonObject
            assertTrue(configs.containsKey("org.iso.23220.photoid.1"),
                "Photo ID doctype がメタデータに含まれていること")
        }
    }

    @Test
    fun `authorization server メタデータが正しい形式で返る`() = runTest {
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
    fun `無効な grant_type で token エンドポイントを呼ぶと 400 が返る`() = runTest {
        testApplication {
            application { testModule() }
            val response = client.post("/token") {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody("grant_type=authorization_code&code=test")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("unsupported_grant_type", body["error"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `無効な pre-authorized_code で token エンドポイントを呼ぶと 400 が返る`() = runTest {
        testApplication {
            application { testModule() }
            val response = client.post("/token") {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody("grant_type=urn:ietf:params:oauth:grant-type:pre-authorized_code&pre-authorized_code=invalid")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("invalid_grant", body["error"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `Bearer token なしで credential エンドポイントを呼ぶと 401 が返る`() = runTest {
        testApplication {
            application { testModule() }
            val response = client.post("/credential") {
                contentType(ContentType.Application.Json)
                setBody("""{"format":"mso_mdoc","doctype":"org.iso.23220.photoid.1"}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
    }

    @Test
    fun `無効な Bearer token で credential エンドポイントを呼ぶと 401 が返る`() = runTest {
        testApplication {
            application { testModule() }
            val response = client.post("/credential") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer invalid-token")
                setBody("""{"format":"mso_mdoc","doctype":"org.iso.23220.photoid.1"}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
    }


}

/**
 * テスト用のモジュール設定。
 * Entra ID 認証なしで OID4VCI エンドポイントのみを有効化する。
 */
private fun io.ktor.server.application.Application.testModule() {
    val baseUrl = "http://localhost"
    val sessionRepository = org.multipaz.issuer.domain.credential.InMemoryIssuanceSessionRepository()
    val issuanceService = org.multipaz.issuer.domain.credential.CredentialIssuanceService(sessionRepository)

    // テスト用の IssuerKeyStore（一時ディレクトリに生成）
    val tempKeyStore = java.io.File.createTempFile("test-issuer", ".p12").apply { deleteOnExit() }
    val keyStore = org.multipaz.issuer.infrastructure.multipaz.IssuerKeyStore(
        tempKeyStore.absolutePath, "testpass"
    )
    val photoIdBuilder = org.multipaz.issuer.infrastructure.multipaz.PhotoIdBuilder(keyStore)

    val issueUseCase = org.multipaz.issuer.application.IssueCredentialUseCase(
        issuanceService = issuanceService,
        photoIdBuilder = photoIdBuilder,
        issuingCountry = "JP",
        issuingAuthority = "Test Issuer",
        validityDays = 365,
    )

    val nonceStore = org.multipaz.issuer.domain.credential.NonceStore()

    configureSerialization()
    routing {
        configureOid4vciRoutes(baseUrl, issuanceService, issueUseCase, photoIdBuilder, nonceStore)
    }
}
