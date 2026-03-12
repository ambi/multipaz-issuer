package org.vdcapps.issuer

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import kotlinx.serialization.json.Json
import org.vdcapps.issuer.application.IssueCredentialUseCase
import org.vdcapps.issuer.domain.credential.CredentialIssuanceService
import org.vdcapps.issuer.domain.credential.InMemoryIssuanceSessionRepository
import org.vdcapps.issuer.domain.credential.IssuanceSessionRepository
import org.vdcapps.issuer.domain.credential.NonceStore
import org.vdcapps.issuer.infrastructure.entra.EntraIdClient
import org.vdcapps.issuer.infrastructure.multipaz.IssuerKeyStore
import org.vdcapps.issuer.infrastructure.multipaz.PhotoIdBuilder
import org.vdcapps.issuer.infrastructure.redis.RedisIssuanceSessionRepository
import org.vdcapps.issuer.web.plugins.configureAuth
import org.vdcapps.issuer.web.plugins.configureRouting
import org.vdcapps.issuer.web.plugins.configureSerialization

fun main(args: Array<String>): Unit =
    io.ktor.server.netty.EngineMain
        .main(args)

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
    val httpClient =
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }

    // インフラ層
    val issuerKeyStore = IssuerKeyStore(keyStorePath, keyStorePassword)
    val photoIdBuilder = PhotoIdBuilder(issuerKeyStore)
    val entraIdClient = EntraIdClient(httpClient)

    // ドメイン層
    val redisUrl = config.propertyOrNull("session.redisUrl")?.getString()?.takeIf { it.isNotBlank() }
    val issuanceRepository: IssuanceSessionRepository =
        if (redisUrl != null) {
            RedisIssuanceSessionRepository(redisUrl).also { repo ->
                environment.monitor.subscribe(io.ktor.server.application.ApplicationStopped) { repo.close() }
            }
        } else {
            InMemoryIssuanceSessionRepository()
        }
    val issuanceService = CredentialIssuanceService(issuanceRepository)
    val nonceStore = NonceStore()

    // アプリケーション層
    val issueCredentialUseCase =
        IssueCredentialUseCase(
            issuanceService = issuanceService,
            photoIdBuilder = photoIdBuilder,
            issuingCountry = issuingCountry,
            issuingAuthority = issuingAuthority,
            validityDays = validityDays,
        )

    // プラグイン・ルーティングの設定
    configureSerialization()
    configureAuth(httpClient, tenantId, clientId, clientSecret, redirectUri)
    configureRouting(
        baseUrl = baseUrl,
        entraIdClient = entraIdClient,
        issuanceService = issuanceService,
        issueCredentialUseCase = issueCredentialUseCase,
        photoIdBuilder = photoIdBuilder,
        nonceStore = nonceStore,
    )
}
