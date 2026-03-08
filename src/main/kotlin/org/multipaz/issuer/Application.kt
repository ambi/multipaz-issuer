package org.multipaz.issuer

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.config.ApplicationConfig
import kotlinx.serialization.json.Json
import org.multipaz.issuer.application.IssueCredentialUseCase
import org.multipaz.issuer.domain.credential.CredentialIssuanceService
import org.multipaz.issuer.domain.credential.InMemoryIssuanceSessionRepository
import org.multipaz.issuer.infrastructure.entra.EntraIdClient
import org.multipaz.issuer.infrastructure.multipaz.IssuerKeyStore
import org.multipaz.issuer.infrastructure.multipaz.PhotoIdBuilder
import org.multipaz.issuer.web.plugins.configureAuth
import org.multipaz.issuer.web.plugins.configureRouting
import org.multipaz.issuer.web.plugins.configureSerialization

fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)

fun Application.module() {
    val config = environment.config

    // 設定値の読み込み
    val baseUrl = config.property("issuer.baseUrl").getString()
    val keyStorePath = config.property("issuer.keyStorePath").getString()
    val keyStorePassword = config.property("issuer.keyStorePassword").getString()
    val issuingCountry = config.property("issuer.issuingCountry").getString()
    val issuingAuthority = config.property("issuer.issuingAuthority").getString()
    val validityDays = config.propertyOrNull("issuer.credentialValidityDays")?.getString()?.toInt() ?: 365

    val tenantId = config.property("entra.tenantId").getString()
    val clientId = config.property("entra.clientId").getString()
    val clientSecret = config.property("entra.clientSecret").getString()
    val redirectUri = config.property("entra.redirectUri").getString()

    // HTTP クライアント（Graph API + OAuth トークン交換）
    val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    // インフラ層
    val issuerKeyStore = IssuerKeyStore(keyStorePath, keyStorePassword)
    val photoIdBuilder = PhotoIdBuilder(issuerKeyStore)
    val entraIdClient = EntraIdClient(httpClient)

    // ドメイン層
    val sessionRepository = InMemoryIssuanceSessionRepository()
    val issuanceService = CredentialIssuanceService(sessionRepository)

    // アプリケーション層
    val issueCredentialUseCase = IssueCredentialUseCase(
        issuanceService = issuanceService,
        photoIdBuilder = photoIdBuilder,
        issuingCountry = issuingCountry,
        issuingAuthority = issuingAuthority,
        validityDays = validityDays,
    )

    // プラグイン・ルーティングの設定
    configureSerialization()
    configureAuth(httpClient, tenantId, clientId, clientSecret, redirectUri)
    configureRouting(baseUrl, entraIdClient, issuanceService, issueCredentialUseCase, photoIdBuilder)
}
