package org.vdcapps.issuer.web.routes

import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.nimbusds.jose.jwk.ECKey
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.freemarker.FreeMarkerContent
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import org.vdcapps.issuer.application.IssueCredentialUseCase
import org.vdcapps.issuer.domain.credential.CredentialIssuanceService
import org.vdcapps.issuer.domain.credential.IssuanceSession
import org.vdcapps.issuer.domain.credential.NonceStore
import org.vdcapps.issuer.infrastructure.multipaz.PhotoIdBuilder
import org.vdcapps.issuer.web.plugins.UserSession
import java.io.ByteArrayOutputStream
import java.util.Base64

private val logger = LoggerFactory.getLogger("Oid4vciRoutes")
private val json = Json { ignoreUnknownKeys = true }

fun Route.configureOid4vciRoutes(
    baseUrl: String,
    issuanceService: CredentialIssuanceService,
    issueCredentialUseCase: IssueCredentialUseCase,
    photoIdBuilder: PhotoIdBuilder,
    nonceStore: NonceStore,
) {
    // ========== ブラウザ UI ==========

    // プロフィールフォーム送信 → セッション作成 → QR コードページへリダイレクト
    post("/issue") {
        val userSession =
            call.sessions.get<UserSession>()
                ?: return@post call.respondRedirect("/")

        val params = call.receiveParameters()
        val familyName = params["familyName"]?.trim().orEmpty()
        val givenName = params["givenName"]?.trim().orEmpty()
        val birthDateStr = params["birthDate"]?.trim().orEmpty()

        fun profileError(msg: String) =
            FreeMarkerContent(
                "profile.ftl",
                mapOf(
                    "displayName" to userSession.displayName,
                    "givenName" to userSession.givenName,
                    "familyName" to userSession.familyName,
                    "email" to (userSession.email ?: ""),
                    "hasPhoto" to userSession.hasPhoto,
                    "error" to msg,
                ),
            )

        if (familyName.isEmpty() || givenName.isEmpty() || birthDateStr.isEmpty()) {
            call.respond(profileError("全ての項目を入力してください。"))
            return@post
        }

        val birthDate =
            try {
                LocalDate.parse(birthDateStr)
            } catch (e: Exception) {
                call.respond(profileError("生年月日の形式が正しくありません。"))
                return@post
            }

        val entraUser =
            org.vdcapps.issuer.domain.identity.EntraUser(
                id = userSession.userId,
                displayName = userSession.displayName,
                givenName = userSession.givenName,
                familyName = userSession.familyName,
                email = userSession.email,
                photo = null,
            )

        val issuanceSession =
            issueCredentialUseCase.startIssuance(
                user = entraUser,
                birthDate = birthDate,
                familyName = familyName,
                givenName = givenName,
            )
        call.respondRedirect("/offer/${issuanceSession.id}")
    }

    // QR コードと credential offer URI を表示するページ
    get("/offer/{sessionId}") {
        val sessionId =
            call.parameters["sessionId"]
                ?: return@get call.respond(HttpStatusCode.NotFound)
        val issuanceSession =
            issuanceService.findSessionById(sessionId)
                ?: return@get call.respond(
                    FreeMarkerContent(
                        "error.ftl",
                        mapOf("message" to "セッションが見つかりません。有効期限が切れた可能性があります。"),
                    ),
                )

        val offerUri = buildCredentialOfferUri(baseUrl, issuanceSession)
        call.respond(
            FreeMarkerContent(
                "offer.ftl",
                mapOf(
                    "qrCodeBase64" to generateQrCodeBase64(offerUri),
                    "offerUri" to offerUri,
                ),
            ),
        )
    }

    // ========== OID4VCI メタデータエンドポイント ==========

    // Credential Issuer Metadata (OID4VCI §10.2)
    get("/.well-known/openid-credential-issuer") {
        call.respondText(buildIssuerMetadata(baseUrl).toString(), ContentType.Application.Json)
    }

    // Authorization Server Metadata (RFC 8414)
    get("/.well-known/oauth-authorization-server") {
        call.respondText(buildAuthServerMetadata(baseUrl).toString(), ContentType.Application.Json)
    }

    // ========== OID4VCI プロトコルエンドポイント ==========

    // JWK Set エンドポイント（RFC 7517）。発行者の署名検証鍵を公開する。
    get("/jwks") {
        call.respondText(photoIdBuilder.buildJwkSetJson(), ContentType.Application.Json)
    }

    /*
     * Pushed Authorization Request エンドポイント (RFC 9126)。
     * Pre-Authorized Code フローでは不使用だが、Multipaz wallet が AS メタデータで確認するため実装。
     */
    post("/par") {
        call.respond(
            HttpStatusCode.BadRequest,
            OidError("request_not_supported", "PAR is not required for pre-authorized_code flow"),
        )
    }

    /*
     * Nonce エンドポイント（OID4VCI §7.3）。
     * 認証不要。Wallet が /credential リクエスト前の proof JWT 生成に使う c_nonce を取得する。
     */
    post("/nonce") {
        val nonce = nonceStore.generate()
        call.response.headers.append("Cache-Control", "no-store")
        call.respond(
            buildJsonObject {
                put("c_nonce", nonce)
                put("c_nonce_expires_in", 600)
            },
        )
    }

    /*
     * Token エンドポイント（RFC 6749 / OID4VCI §6.1）。
     * pre-authorized_code を access_token に交換する。
     */
    post("/token") {
        val params = call.receiveParameters()

        if (params["grant_type"] != "urn:ietf:params:oauth:grant-type:pre-authorized_code") {
            call.respond(
                HttpStatusCode.BadRequest,
                OidError("unsupported_grant_type", "Only pre-authorized_code grant is supported"),
            )
            return@post
        }

        val code = params["pre-authorized_code"]
        if (code.isNullOrBlank()) {
            call.respond(
                HttpStatusCode.BadRequest,
                OidError("invalid_request", "pre-authorized_code is required"),
            )
            return@post
        }

        val (accessToken, _) =
            issuanceService.exchangePreAuthorizedCode(code)
                ?: run {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        OidError("invalid_grant", "Invalid or expired pre-authorized_code"),
                    )
                    return@post
                }

        call.response.headers.append("Cache-Control", "no-store")
        call.respond(
            buildJsonObject {
                put("access_token", accessToken)
                put("token_type", "DPoP")
                put("expires_in", 300)
                put("c_nonce", nonceStore.generate())
                put("c_nonce_expires_in", 600)
            },
        )
    }

    /*
     * Credential エンドポイント（OID4VCI §7.2）。
     * proof JWT を検証して署名済み mdoc を発行する。
     */
    post("/credential") {
        val accessToken =
            call.extractBearerToken()
                ?: return@post call.respond(
                    HttpStatusCode.Unauthorized,
                    OidError("invalid_token", "DPoP or Bearer token required"),
                )

        val session =
            issuanceService.findSessionByAccessToken(accessToken)
                ?: return@post call.respond(
                    HttpStatusCode.Unauthorized,
                    OidError("invalid_token", "Unknown or expired access token"),
                )

        if (session.state != IssuanceSession.State.TOKEN_ISSUED) {
            call.respond(
                HttpStatusCode.BadRequest,
                OidError("invalid_request", "Credential already issued or session invalid"),
            )
            return@post
        }

        val bodyText = call.receiveText()
        logger.debug("POST /credential body: $bodyText")
        val body =
            try {
                json.parseToJsonElement(bodyText).jsonObject
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, OidError("invalid_request", "Invalid JSON body"))
                return@post
            }

        val format = body["format"]?.jsonPrimitive?.content
        if (format != null && format != "mso_mdoc") {
            call.respond(
                HttpStatusCode.BadRequest,
                OidError("unsupported_credential_format", "Only mso_mdoc is supported"),
            )
            return@post
        }

        val proofJwt =
            body.extractProofJwt()
                ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    OidError("invalid_proof", "proof or proofs.jwt is required"),
                )

        val holderPublicKey =
            try {
                validateProofJwt(proofJwt, baseUrl, photoIdBuilder, nonceStore)
            } catch (e: Exception) {
                logger.warn("proof JWT 検証失敗: ${e.message}")
                call.respond(
                    HttpStatusCode.BadRequest,
                    OidError("invalid_proof", e.message ?: "Proof validation failed"),
                )
                return@post
            }

        val credential =
            try {
                issueCredentialUseCase.issueCredential(session, holderPublicKey)
            } catch (e: Exception) {
                logger.error("証明書の生成に失敗しました", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    OidError("server_error", "Failed to issue credential"),
                )
                return@post
            }

        call.respond(
            buildJsonObject {
                putJsonArray("credentials") {
                    add(buildJsonObject { put("credential", credential) })
                }
            },
        )
    }
}

// ========== ヘルパー関数 ==========

/** Authorization ヘッダーから DPoP または Bearer トークンを取り出す。 */
private fun io.ktor.server.application.ApplicationCall.extractBearerToken(): String? {
    val h = request.headers["Authorization"] ?: return null
    return when {
        h.startsWith("DPoP ", ignoreCase = true) -> h.substring(5).trim()
        h.startsWith("Bearer ", ignoreCase = true) -> h.substring(7).trim()
        else -> null
    }
}

/**
 * OID4VCI proof JWT を抽出する。新仕様（proofs.jwt 配列）と旧仕様（proof.jwt 文字列）の両方に対応。
 * JWT 文字列を返す。どちらの形式でもない場合は null を返す。
 */
private fun JsonObject.extractProofJwt(): String? {
    // 新仕様 (OID4VCI draft 14+): "proofs": {"jwt": ["<jwt1>", ...]}
    get("proofs")?.jsonObject?.get("jwt")?.let { arr ->
        return arr.jsonArray
            .firstOrNull()
            ?.jsonPrimitive
            ?.content
    }
    // 旧仕様: "proof": {"proof_type": "jwt", "jwt": "<jwt>"}
    val proofObj = get("proof")?.jsonObject ?: return null
    if (proofObj["proof_type"]?.jsonPrimitive?.content != "jwt") return null
    return proofObj["jwt"]?.jsonPrimitive?.content
}

/**
 * proof JWT を検証し、holder の公開鍵を返す。
 *
 * 検証内容:
 * - ヘッダーの jwk が EC P-256 鍵であること
 * - JWT 署名が jwk で正しく検証できること
 * - aud が baseUrl であること
 * - nonce の HMAC 署名が正しく有効期限内であること
 * - iat が 5 分以内であること
 */
private fun validateProofJwt(
    jwt: String,
    baseUrl: String,
    photoIdBuilder: PhotoIdBuilder,
    nonceStore: NonceStore,
): org.multipaz.crypto.EcPublicKey {
    val signedJwt =
        com.nimbusds.jwt.SignedJWT
            .parse(jwt)
    val header = signedJwt.header
    val claims = signedJwt.jwtClaimsSet

    val ecJwk =
        (header.jwk as? ECKey)
            ?: throw IllegalArgumentException("proof JWT header に EC JWK が含まれていません")

    if (!signedJwt.verify(
            com.nimbusds.jose.crypto
                .ECDSAVerifier(ecJwk.toECPublicKey()),
        )
    ) {
        throw IllegalArgumentException("proof JWT の署名検証に失敗しました")
    }

    if (claims.audience.none { it == baseUrl }) {
        throw IllegalArgumentException("proof JWT の aud が不正です: ${claims.audience}")
    }

    val nonce =
        claims.getStringClaim("nonce")
            ?: throw IllegalArgumentException("proof JWT に nonce がありません")
    if (!nonceStore.verify(nonce)) {
        throw IllegalArgumentException("proof JWT の nonce が無効または期限切れです")
    }

    val iat = claims.issueTime
    if (iat == null || (System.currentTimeMillis() - iat.time) > 5 * 60 * 1000) {
        throw IllegalArgumentException("proof JWT が古すぎます (iat)")
    }

    val ecPoint = ecJwk.toECPublicKey().w
    return photoIdBuilder.ecPublicKeyFromCoordinates(
        ecPoint.affineX.toByteArray(),
        ecPoint.affineY.toByteArray(),
    )
}

/** openid-credential-offer:// URI を inline 形式で構築する（OID4VCI §4.1.1）。 */
private fun buildCredentialOfferUri(
    baseUrl: String,
    session: IssuanceSession,
): String {
    val offerJson =
        buildJsonObject {
            put("credential_issuer", baseUrl)
            putJsonArray("credential_configuration_ids") {
                add(JsonPrimitive(PhotoIdBuilder.PHOTO_ID_DOCTYPE))
            }
            putJsonObject("grants") {
                putJsonObject("urn:ietf:params:oauth:grant-type:pre-authorized_code") {
                    put("pre-authorized_code", session.preAuthorizedCode)
                    put("interval", 5)
                }
            }
        }.toString()
    return "openid-credential-offer://?credential_offer=${java.net.URLEncoder.encode(offerJson, "UTF-8")}"
}

/** Credential Issuer Metadata（OID4VCI §10.2）。 */
private fun buildIssuerMetadata(baseUrl: String): JsonObject =
    buildJsonObject {
        put("credential_issuer", baseUrl)
        putJsonArray("authorization_servers") { add(JsonPrimitive(baseUrl)) }
        put("credential_endpoint", "$baseUrl/credential")
        put("nonce_endpoint", "$baseUrl/nonce")
        putJsonObject("credential_configurations_supported") {
            putJsonObject(PhotoIdBuilder.PHOTO_ID_DOCTYPE) {
                put("format", "mso_mdoc")
                put("doctype", PhotoIdBuilder.PHOTO_ID_DOCTYPE)
                putJsonArray("cryptographic_binding_methods_supported") { add(JsonPrimitive("cose_key")) }
                putJsonArray("credential_signing_alg_values_supported") { add(JsonPrimitive("ES256")) }
                putJsonObject("proof_types_supported") {
                    putJsonObject("jwt") {
                        putJsonArray("proof_signing_alg_values_supported") { add(JsonPrimitive("ES256")) }
                    }
                }
                putJsonArray("display") {
                    add(
                        buildJsonObject {
                            put("name", "Photo ID")
                            put("locale", "ja-JP")
                            put("description", "ISO/IEC TS 23220-4 準拠の Photo ID")
                        },
                    )
                }
            }
        }
    }

/** Authorization Server Metadata（RFC 8414）。Multipaz wallet が必要とするフィールドを含む。 */
private fun buildAuthServerMetadata(baseUrl: String): JsonObject =
    buildJsonObject {
        put("issuer", baseUrl)
        put("authorization_endpoint", "$baseUrl/auth/login")
        put("token_endpoint", "$baseUrl/token")
        put("pushed_authorization_request_endpoint", "$baseUrl/par")
        put("jwks_uri", "$baseUrl/jwks")
        put("pre-authorized_grant_anonymous_access_supported", true)
        putJsonArray("grant_types_supported") {
            add(JsonPrimitive("urn:ietf:params:oauth:grant-type:pre-authorized_code"))
            add(JsonPrimitive("authorization_code"))
        }
        putJsonArray("response_types_supported") { add(JsonPrimitive("code")) }
        putJsonArray("code_challenge_methods_supported") { add(JsonPrimitive("S256")) }
        putJsonArray("token_endpoint_auth_methods_supported") {
            add(JsonPrimitive("none"))
            add(JsonPrimitive("private_key_jwt"))
            add(JsonPrimitive("attest_jwt_client_auth"))
        }
        putJsonArray("dpop_signing_alg_values_supported") { add(JsonPrimitive("ES256")) }
        putJsonArray("client_attestation_pop_signing_alg_values_supported") { add(JsonPrimitive("ES256")) }
    }

private fun generateQrCodeBase64(
    content: String,
    size: Int = 300,
): String {
    val bitMatrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
    val output = ByteArrayOutputStream()
    MatrixToImageWriter.writeToStream(bitMatrix, "PNG", output)
    return Base64.getEncoder().encodeToString(output.toByteArray())
}

@Serializable
private data class OidError(
    val error: String,
    @SerialName("error_description") val errorDescription: String,
)
