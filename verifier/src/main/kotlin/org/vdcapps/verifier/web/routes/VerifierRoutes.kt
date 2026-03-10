package org.vdcapps.verifier.web.routes

import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.client.j2se.MatrixToImageWriter
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.freemarker.FreeMarkerContent
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import org.vdcapps.verifier.application.VerifyCredentialUseCase
import org.vdcapps.verifier.domain.verification.VerificationService
import org.vdcapps.verifier.domain.verification.VerificationSession
import java.io.ByteArrayOutputStream
import java.net.URLEncoder

private val logger = LoggerFactory.getLogger("VerifierRoutes")
private val json = Json { ignoreUnknownKeys = true }

// ISO/IEC TS 23220-4 Photo ID のネームスペース
private const val NAMESPACE_23220_2 = "org.iso.23220.2"

fun Route.configureVerifierRoutes(
    baseUrl: String,
    verificationService: VerificationService,
    verifyCredentialUseCase: VerifyCredentialUseCase,
) {
    // ========== ブラウザ UI ==========

    /*
     * 検証開始ページ。
     * 新しい VerificationSession を作成し、OID4VP QR コードと Digital Credentials API ボタンを表示する。
     */
    get("/verifier") {
        val session = verifyCredentialUseCase.startVerification()
        val requestUri = "$baseUrl/verifier/request/${session.id}"
        val oid4vpUri = buildOid4vpUri(requestUri)

        call.respond(
            FreeMarkerContent(
                "verifier.ftl",
                mapOf(
                    "sessionId" to session.id,
                    "qrCodeBase64" to generateQrBase64(oid4vpUri),
                    "oid4vpUri" to oid4vpUri,
                    "baseUrl" to baseUrl,
                    "presentationDefinitionJson" to buildPresentationDefinition(session.id).toString(),
                    "nonce" to session.nonce,
                ),
            ),
        )
    }

    // ========== OID4VP プロトコルエンドポイント ==========

    /*
     * OID4VP Authorization Request エンドポイント（request_uri パターン）。
     * Wallet が GET して Authorization Request JSON を取得する。
     */
    get("/verifier/request/{sessionId}") {
        val sessionId =
            call.parameters["sessionId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest)
        val session =
            verificationService.findById(sessionId)
                ?: return@get call.respond(HttpStatusCode.NotFound, OidVpError("invalid_request", "Session not found"))

        if (session.state != VerificationSession.State.PENDING) {
            return@get call.respond(
                HttpStatusCode.BadRequest,
                OidVpError("invalid_request", "Session is not in PENDING state"),
            )
        }

        val responseUri = "$baseUrl/verifier/response"
        val authRequest = buildAuthorizationRequest(baseUrl, responseUri, session)

        call.response.headers.append("Cache-Control", "no-store")
        call.respondText(authRequest.toString(), ContentType.Application.Json)
    }

    /*
     * OID4VP Response エンドポイント（direct_post）。
     * Wallet が vp_token と presentation_submission を POST する。
     * Digital Credentials API 経由の場合も同じエンドポイントを使う（JS から fetch で送信）。
     */
    post("/verifier/response") {
        val contentType = call.request.headers["Content-Type"] ?: ""

        // application/x-www-form-urlencoded（OID4VP direct_post）と
        // application/json（DC API 経由 JS fetch）の両方に対応
        val (vpToken, state) =
            if (contentType.contains("application/json")) {
                val body = json.parseToJsonElement(call.receiveText()).jsonObject
                val vp = body["vp_token"]?.jsonPrimitive?.content
                val s = body["state"]?.jsonPrimitive?.content
                vp to s
            } else {
                val params = call.receiveParameters()
                params["vp_token"] to params["state"]
            }

        if (vpToken.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, OidVpError("invalid_request", "vp_token is required"))
            return@post
        }

        val session =
            if (!state.isNullOrBlank()) {
                verificationService.findById(state)
            } else {
                null
            }

        if (session == null) {
            call.respond(HttpStatusCode.BadRequest, OidVpError("invalid_request", "Unknown or missing state"))
            return@post
        }

        if (session.state != VerificationSession.State.PENDING &&
            session.state != VerificationSession.State.RESPONSE_RECEIVED
        ) {
            call.respond(HttpStatusCode.BadRequest, OidVpError("invalid_request", "Session already processed"))
            return@post
        }

        try {
            verifyCredentialUseCase.processVpToken(session, vpToken)
            logger.info("検証成功: sessionId=${session.id}")
            // Wallet への OID4VP レスポンスは redirect_uri を返す（Wallet がリダイレクト先を開く）
            call.respond(
                buildJsonObject {
                    put("redirect_uri", "$baseUrl/verifier/result/${session.id}")
                },
            )
        } catch (e: Exception) {
            logger.warn("検証失敗: sessionId=${session.id}, error=${e.message}")
            call.respond(
                HttpStatusCode.BadRequest,
                OidVpError("invalid_vp_token", e.message ?: "Verification failed"),
            )
        }
    }

    /*
     * 検証結果ページ。
     * - セッションが PENDING / RESPONSE_RECEIVED の場合: ポーリング用 JSON を返す（Accept: application/json）
     * - セッションが VERIFIED / FAILED の場合: 結果 HTML を返す
     */
    get("/verifier/result/{sessionId}") {
        val sessionId =
            call.parameters["sessionId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest)
        val session =
            verificationService.findById(sessionId)
                ?: return@get call.respond(
                    FreeMarkerContent("error.ftl", mapOf("message" to "セッションが見つかりません。")),
                )

        val acceptsJson = call.request.headers["Accept"]?.contains("application/json") == true

        when (session.state) {
            VerificationSession.State.PENDING,
            VerificationSession.State.RESPONSE_RECEIVED,
            -> {
                if (acceptsJson) {
                    call.respond(buildJsonObject { put("status", "pending") })
                } else {
                    call.respond(
                        FreeMarkerContent(
                            "verifier_result.ftl",
                            mapOf(
                                "sessionId" to sessionId,
                                "status" to "pending",
                            ),
                        ),
                    )
                }
            }

            VerificationSession.State.VERIFIED -> {
                val result = session.result!!
                if (acceptsJson) {
                    call.respond(buildJsonObject { put("status", "verified") })
                } else {
                    // クレームをフラットな List<Map> に変換してテンプレートに渡す
                    val claimRows =
                        result.claims.flatMap { (ns, elements) ->
                            elements.map { (name, value) -> mapOf("namespace" to ns, "name" to name, "value" to value) }
                        }
                    call.respond(
                        FreeMarkerContent(
                            "verifier_result.ftl",
                            mapOf(
                                "sessionId" to sessionId,
                                "status" to "verified",
                                "docType" to result.docType,
                                "issuerSubject" to result.issuerCertificateSubject,
                                "validFrom" to result.validFrom.toString(),
                                "validUntil" to result.validUntil.toString(),
                                "claimRows" to claimRows,
                            ),
                        ),
                    )
                }
            }

            VerificationSession.State.FAILED -> {
                if (acceptsJson) {
                    call.respond(buildJsonObject { put("status", "failed") })
                } else {
                    call.respond(
                        FreeMarkerContent(
                            "verifier_result.ftl",
                            mapOf(
                                "sessionId" to sessionId,
                                "status" to "failed",
                                "errorMessage" to (session.errorMessage ?: "不明なエラー"),
                            ),
                        ),
                    )
                }
            }
        }
    }
}

// ========== ヘルパー関数 ==========

/**
 * OID4VP Authorization Request JSON を構築する（request_uri パターンで返す内容）。
 *
 * Multipaz Compose Wallet が受け入れる OID4VP draft 20 形式。
 */
private fun buildAuthorizationRequest(
    baseUrl: String,
    responseUri: String,
    session: VerificationSession,
) = buildJsonObject {
    put("response_type", "vp_token")
    put("client_id", responseUri)
    put("client_id_scheme", "redirect_uri")
    put("response_mode", "direct_post")
    put("response_uri", responseUri)
    put("nonce", session.nonce)
    put("state", session.id)
    put("presentation_definition", buildPresentationDefinition(session.id))
}

/** Photo ID (org.iso.23220.photoid.1) を要求する Presentation Definition を構築する。 */
private fun buildPresentationDefinition(sessionId: String) =
    buildJsonObject {
        put("id", "photo-id-request-$sessionId")
        putJsonArray("input_descriptors") {
            add(
                buildJsonObject {
                    put("id", "photo-id")
                    putJsonObject("format") {
                        putJsonObject("mso_mdoc") {
                            putJsonArray("alg") { add(kotlinx.serialization.json.JsonPrimitive("ES256")) }
                        }
                    }
                    putJsonObject("constraints") {
                        putJsonArray("fields") {
                            // 最低限の必須クレームを要求
                            for (path in listOf(
                                "\$['$NAMESPACE_23220_2']['family_name']",
                                "\$['$NAMESPACE_23220_2']['given_name']",
                                "\$['$NAMESPACE_23220_2']['birth_date']",
                            )) {
                                add(
                                    buildJsonObject {
                                        putJsonArray("path") { add(kotlinx.serialization.json.JsonPrimitive(path)) }
                                        put("intent_to_retain", false)
                                    },
                                )
                            }
                        }
                    }
                },
            )
        }
    }

/**
 * openid4vp:// URI を構築する（request_uri パターン）。
 * Wallet は request_uri にアクセスして Authorization Request JSON を取得する。
 */
private fun buildOid4vpUri(requestUri: String): String = "openid4vp://?request_uri=${URLEncoder.encode(requestUri, "UTF-8")}"

private fun generateQrBase64(
    content: String,
    size: Int = 300,
): String {
    val bitMatrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
    val output = ByteArrayOutputStream()
    MatrixToImageWriter.writeToStream(bitMatrix, "PNG", output)
    return java.util.Base64
        .getEncoder()
        .encodeToString(output.toByteArray())
}

@Serializable
private data class OidVpError(
    val error: String,
    @SerialName("error_description") val errorDescription: String,
)
