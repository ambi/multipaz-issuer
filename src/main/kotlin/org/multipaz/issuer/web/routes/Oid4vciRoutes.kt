package org.multipaz.issuer.web.routes

import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.nimbusds.jose.jwk.ECKey
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.freemarker.FreeMarkerContent
import io.ktor.server.request.receive
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
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.multipaz.issuer.application.IssueCredentialUseCase
import org.multipaz.issuer.domain.credential.CredentialIssuanceService
import org.multipaz.issuer.domain.credential.IssuanceSession
import org.multipaz.issuer.infrastructure.multipaz.PhotoIdBuilder
import org.multipaz.issuer.web.plugins.UserSession
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.util.Base64

private val logger = LoggerFactory.getLogger("Oid4vciRoutes")
private val json = Json { ignoreUnknownKeys = true }

fun Route.configureOid4vciRoutes(
    baseUrl: String,
    issuanceService: CredentialIssuanceService,
    issueCredentialUseCase: IssueCredentialUseCase,
    photoIdBuilder: PhotoIdBuilder,
) {
    // ========== ブラウザ UI ==========

    /** ユーザーがプロフィールフォームを送信 → セッション作成 → QR コードページへ */
    post("/issue") {
        val session = call.sessions.get<UserSession>()
        if (session == null) {
            call.respondRedirect("/")
            return@post
        }

        val params = call.receiveParameters()
        val familyName = params["familyName"]?.trim().orEmpty()
        val givenName = params["givenName"]?.trim().orEmpty()
        val birthDateStr = params["birthDate"]?.trim().orEmpty()

        if (familyName.isEmpty() || givenName.isEmpty() || birthDateStr.isEmpty()) {
            call.respond(FreeMarkerContent("profile.ftl", mapOf(
                "displayName" to session.displayName,
                "givenName" to session.givenName,
                "familyName" to session.familyName,
                "email" to (session.email ?: ""),
                "hasPhoto" to session.hasPhoto,
                "error" to "全ての項目を入力してください。",
            )))
            return@post
        }

        val birthDate = try {
            LocalDate.parse(birthDateStr)
        } catch (e: Exception) {
            call.respond(FreeMarkerContent("profile.ftl", mapOf(
                "displayName" to session.displayName,
                "givenName" to session.givenName,
                "familyName" to session.familyName,
                "email" to (session.email ?: ""),
                "hasPhoto" to session.hasPhoto,
                "error" to "生年月日の形式が正しくありません。",
            )))
            return@post
        }

        // EntraUser 相当のオブジェクトを UserSession から再構成
        // 写真は Graph API から再取得する（セッションには保存しない）
        val fakeUser = org.multipaz.issuer.domain.identity.EntraUser(
            id = session.userId,
            displayName = session.displayName,
            givenName = session.givenName,
            familyName = session.familyName,
            email = session.email,
            photo = null, // 写真は発行時に Graph API で再取得（下記 TODO 参照）
        )
        // TODO: Graph API から photo を再取得する場合は EntraIdClient.fetchPhoto() を呼ぶ

        val issuanceSession = issueCredentialUseCase.startIssuance(
            user = fakeUser,
            birthDate = birthDate,
            familyName = familyName,
            givenName = givenName,
        )

        call.respondRedirect("/offer/${issuanceSession.id}")
    }

    /** QR コードと credential offer URI を表示するページ */
    get("/offer/{sessionId}") {
        val sessionId = call.parameters["sessionId"]
            ?: return@get call.respond(HttpStatusCode.NotFound)
        val issuanceSession = issuanceService.findSessionById(sessionId)
            ?: return@get call.respond(FreeMarkerContent("error.ftl",
                mapOf("message" to "セッションが見つかりません。有効期限が切れた可能性があります。")))

        // openid-credential-offer URI（QR コードに埋め込む）
        val offerUri = buildCredentialOfferUri(baseUrl, sessionId)
        val qrBase64 = generateQrCodeBase64(offerUri)

        call.respond(FreeMarkerContent("offer.ftl", mapOf(
            "qrCodeBase64" to qrBase64,
            "offerUri" to offerUri,
        )))
    }

    // ========== OID4VCI メタデータエンドポイント ==========

    /** Credential Issuer Metadata (OID4VCI §10.2) */
    get("/.well-known/openid-credential-issuer") {
        call.respondText(
            buildIssuerMetadata(baseUrl).toString(),
            ContentType.Application.Json,
        )
    }

    /** Authorization Server Metadata (RFC 8414) */
    get("/.well-known/oauth-authorization-server") {
        call.respondText(
            buildAuthServerMetadata(baseUrl).toString(),
            ContentType.Application.Json,
        )
    }

    // ========== OID4VCI プロトコルエンドポイント ==========

    /**
     * Credential Offer オブジェクトを返す（credential_offer_uri 参照用）。
     * Wallet が QR コードをスキャンして最初に呼ぶエンドポイント。
     */
    get("/credential-offer/{sessionId}") {
        val sessionId = call.parameters["sessionId"]
            ?: return@get call.respond(HttpStatusCode.NotFound)
        val session = issuanceService.findSessionById(sessionId)
            ?: return@get call.respond(HttpStatusCode.Gone)
        if (session.state != IssuanceSession.State.PENDING) {
            return@get call.respond(HttpStatusCode.Gone)
        }

        call.respondText(
            buildCredentialOffer(baseUrl, session).toString(),
            ContentType.Application.Json,
        )
    }

    /**
     * c_nonce 発行エンドポイント（OID4VCI §7.3 optional）。
     * Wallet が credential リクエスト前に呼ぶことがある。
     */
    post("/nonce") {
        val authHeader = call.request.headers["Authorization"]
            ?: return@post call.respond(HttpStatusCode.Unauthorized, OidError("invalid_token", "Bearer token required"))
        val accessToken = authHeader.removePrefix("Bearer ").trim()
        val session = issuanceService.findSessionByAccessToken(accessToken)
            ?: return@post call.respond(HttpStatusCode.Unauthorized, OidError("invalid_token", "Unknown access token"))

        call.respond(buildJsonObject {
            put("c_nonce", session.cNonce)
            put("c_nonce_expires_in", 300)
        })
    }

    /**
     * Token エンドポイント: pre-authorized_code を access_token に交換する。
     * Wallet が credential-offer の次に呼ぶ。
     */
    post("/token") {
        val params = call.receiveParameters()
        val grantType = params["grant_type"]

        if (grantType != "urn:ietf:params:oauth:grant-type:pre-authorized_code") {
            call.respond(HttpStatusCode.BadRequest,
                OidError("unsupported_grant_type", "Only pre-authorized_code grant is supported"))
            return@post
        }

        val preAuthorizedCode = params["pre-authorized_code"]
        if (preAuthorizedCode.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest,
                OidError("invalid_request", "pre-authorized_code is required"))
            return@post
        }

        val (accessToken, session) = issuanceService.exchangePreAuthorizedCode(preAuthorizedCode)
            ?: run {
                call.respond(HttpStatusCode.BadRequest,
                    OidError("invalid_grant", "Invalid or expired pre-authorized_code"))
                return@post
            }

        call.respond(buildJsonObject {
            put("access_token", accessToken)
            put("token_type", "Bearer")
            put("expires_in", 300)
            put("c_nonce", session.cNonce)
            put("c_nonce_expires_in", 300)
        })
    }

    /**
     * Credential エンドポイント: proof を検証して署名済み mdoc を発行する。
     * Wallet が /token の後に呼ぶ。
     */
    post("/credential") {
        // --- 認証 ---
        val authHeader = call.request.headers["Authorization"]
            ?: return@post call.respond(HttpStatusCode.Unauthorized,
                OidError("invalid_token", "Bearer token required"))
        val accessToken = authHeader.removePrefix("Bearer ").trim()

        val session = issuanceService.findSessionByAccessToken(accessToken)
            ?: return@post call.respond(HttpStatusCode.Unauthorized,
                OidError("invalid_token", "Unknown or expired access token"))

        if (session.state != IssuanceSession.State.TOKEN_ISSUED) {
            call.respond(HttpStatusCode.BadRequest,
                OidError("invalid_request", "Credential already issued or session invalid"))
            return@post
        }

        // --- リクエストボディのパース ---
        val body = try {
            json.parseToJsonElement(call.receiveText()).jsonObject
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, OidError("invalid_request", "Invalid JSON body"))
            return@post
        }

        val format = body["format"]?.jsonPrimitive?.content
        if (format != "mso_mdoc") {
            call.respond(HttpStatusCode.BadRequest,
                OidError("unsupported_credential_format", "Only mso_mdoc is supported"))
            return@post
        }

        // --- Proof of Possession の検証 ---
        val proofObj = body["proof"]?.jsonObject
            ?: return@post call.respond(HttpStatusCode.BadRequest,
                OidError("invalid_proof", "proof is required"))

        val proofType = proofObj["proof_type"]?.jsonPrimitive?.content
        if (proofType != "jwt") {
            call.respond(HttpStatusCode.BadRequest,
                OidError("invalid_proof", "Only jwt proof_type is supported"))
            return@post
        }

        val proofJwt = proofObj["jwt"]?.jsonPrimitive?.content
            ?: return@post call.respond(HttpStatusCode.BadRequest,
                OidError("invalid_proof", "jwt field missing"))

        val holderPublicKey = try {
            extractAndValidateProofJwt(proofJwt, session.cNonce, baseUrl, photoIdBuilder)
        } catch (e: Exception) {
            logger.warn("proof JWT の検証に失敗しました: ${e.message}")
            call.respond(HttpStatusCode.BadRequest, OidError("invalid_proof", e.message ?: "Proof validation failed"))
            return@post
        }

        // --- 証明書の発行 ---
        val credential = try {
            issueCredentialUseCase.issueCredential(session, holderPublicKey)
        } catch (e: Exception) {
            logger.error("証明書の生成に失敗しました", e)
            call.respond(HttpStatusCode.InternalServerError,
                OidError("server_error", "Failed to issue credential"))
            return@post
        }

        call.respond(buildJsonObject {
            put("format", "mso_mdoc")
            put("credential", credential)
        })
    }
}

// ========== ヘルパー関数 ==========

private fun buildCredentialOfferUri(baseUrl: String, sessionId: String): String {
    val offerUrl = "$baseUrl/credential-offer/$sessionId"
    val encoded = java.net.URLEncoder.encode(offerUrl, "UTF-8")
    return "openid-credential-offer://?credential_offer_uri=$encoded"
}

private fun buildCredentialOffer(baseUrl: String, session: IssuanceSession): JsonObject =
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
    }

private fun buildIssuerMetadata(baseUrl: String): JsonObject = buildJsonObject {
    put("credential_issuer", baseUrl)
    putJsonArray("authorization_servers") { add(JsonPrimitive(baseUrl)) }
    put("credential_endpoint", "$baseUrl/credential")
    put("nonce_endpoint", "$baseUrl/nonce")
    putJsonObject("credential_configurations_supported") {
        putJsonObject(PhotoIdBuilder.PHOTO_ID_DOCTYPE) {
            put("format", "mso_mdoc")
            put("doctype", PhotoIdBuilder.PHOTO_ID_DOCTYPE)
            putJsonArray("cryptographic_binding_methods_supported") {
                add(JsonPrimitive("cose_key"))
            }
            putJsonArray("credential_signing_alg_values_supported") {
                add(JsonPrimitive("ES256"))
            }
            putJsonObject("proof_types_supported") {
                putJsonObject("jwt") {
                    putJsonArray("proof_signing_alg_values_supported") {
                        add(JsonPrimitive("ES256"))
                    }
                }
            }
            putJsonArray("display") {
                add(buildJsonObject {
                    put("name", "Photo ID")
                    put("locale", "ja-JP")
                    put("description", "ISO/IEC TS 23220-4 準拠の Photo ID")
                })
            }
        }
    }
}

private fun buildAuthServerMetadata(baseUrl: String): JsonObject = buildJsonObject {
    put("issuer", baseUrl)
    put("token_endpoint", "$baseUrl/token")
    put("pre-authorized_grant_anonymous_access_supported", true)
    putJsonArray("grant_types_supported") {
        add(JsonPrimitive("urn:ietf:params:oauth:grant-type:pre-authorized_code"))
    }
}

/**
 * proof JWT を検証し、holder の [org.multipaz.crypto.EcPublicKey] を返す。
 *
 * 検証内容:
 * - JWT ヘッダーに alg=ES256 と jwk が含まれること
 * - aud クレームが credential issuer の URL（baseUrl）であること
 * - nonce クレームが c_nonce と一致すること
 * - JWT 署名が jwk で検証できること
 */
private fun extractAndValidateProofJwt(
    jwt: String,
    expectedNonce: String,
    baseUrl: String,
    photoIdBuilder: PhotoIdBuilder,
): org.multipaz.crypto.EcPublicKey {
    val signedJwt = com.nimbusds.jwt.SignedJWT.parse(jwt)
    val header = signedJwt.header
    val claims = signedJwt.jwtClaimsSet

    // JWK を取得（Wallet の公開鍵）
    val ecJwk = (header.jwk as? ECKey)
        ?: throw IllegalArgumentException("proof JWT header に EC JWK が含まれていません")

    // 署名検証
    val verifier = com.nimbusds.jose.crypto.ECDSAVerifier(ecJwk.toECPublicKey())
    if (!signedJwt.verify(verifier)) {
        throw IllegalArgumentException("proof JWT の署名検証に失敗しました")
    }

    // aud 検証
    val aud = claims.audience
    if (aud.none { it == baseUrl }) {
        throw IllegalArgumentException("proof JWT の aud が不正です: $aud")
    }

    // nonce 検証
    val nonce = claims.getStringClaim("nonce")
    if (nonce != expectedNonce) {
        throw IllegalArgumentException("proof JWT の nonce が一致しません")
    }

    // iat 検証（5 分以内）
    val iat = claims.issueTime
    if (iat == null || (Clock.System.now().toEpochMilliseconds() - iat.time) > 5 * 60 * 1000) {
        throw IllegalArgumentException("proof JWT が古すぎます (iat)")
    }

    // EC 公開鍵の座標を取得
    val ecPoint = ecJwk.toECPublicKey().w
    val xBytes = ecPoint.affineX.toByteArray()
    val yBytes = ecPoint.affineY.toByteArray()

    return photoIdBuilder.ecPublicKeyFromCoordinates(xBytes, yBytes)
}

private fun generateQrCodeBase64(content: String, size: Int = 300): String {
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
