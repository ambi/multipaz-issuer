package org.vdcapps.issuer

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.netty.EngineMain
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.vdcapps.issuer.application.IssueCredentialUseCase
import org.vdcapps.issuer.domain.credential.CredentialIssuanceService
import org.vdcapps.issuer.domain.credential.InMemoryIssuanceSessionRepository
import org.vdcapps.issuer.domain.credential.IssuanceSessionRepository
import org.vdcapps.issuer.domain.credential.NonceStore
import org.vdcapps.issuer.infrastructure.entra.EntraIdClient
import org.vdcapps.issuer.infrastructure.multipaz.IssuerKeyStore
import org.vdcapps.issuer.infrastructure.multipaz.PhotoIdBuilder
import org.vdcapps.issuer.infrastructure.redis.RedisIssuanceSessionRepository
import org.vdcapps.issuer.infrastructure.redis.RedisRateLimiter
import org.vdcapps.issuer.web.plugins.configureAuth
import org.vdcapps.issuer.web.util.RateLimiter
import org.vdcapps.issuer.web.util.RateLimiterPort
import org.vdcapps.issuer.web.plugins.configureRouting
import org.vdcapps.issuer.web.plugins.configureSerialization
import redis.clients.jedis.JedisPool

private val logger = LoggerFactory.getLogger("Application")

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    val config = environment.config

    // 設定値の読み込み
    val baseUrl = config.property("issuer.baseUrl").getString()
    val keyStorePath = config.property("issuer.keyStorePath").getString()
    val keyStorePassword = config.property("issuer.keyStorePassword").getString()
    val issuingCountry = config.property("issuer.issuingCountry").getString()
    val issuingAuthority = config.property("issuer.issuingAuthority").getString()
    val validityDays = config.propertyOrNull("issuer.credentialValidityDays")?.getString()?.toInt() ?: 365
    val trustedProxyCount = config.propertyOrNull("issuer.trustedProxyCount")?.getString()?.toInt() ?: 1

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
            install(HttpTimeout) {
                requestTimeoutMillis = 15_000
                connectTimeoutMillis = 5_000
                socketTimeoutMillis = 15_000
            }
        }
    environment.monitor.subscribe(io.ktor.server.application.ApplicationStopped) { httpClient.close() }

    // インフラ層
    val issuerKeyStore = IssuerKeyStore(keyStorePath, keyStorePassword)
    val photoIdBuilder = PhotoIdBuilder(issuerKeyStore)
    val entraIdClient = EntraIdClient(httpClient)

    // ドメイン層
    val redisUrl = config.propertyOrNull("session.redisUrl")?.getString()?.takeIf { it.isNotBlank() }
    var redisPool: JedisPool? = null
    val issuanceRepository: IssuanceSessionRepository
    val rateLimiter: RateLimiterPort
    if (redisUrl != null) {
        val repo = RedisIssuanceSessionRepository(redisUrl)
        redisPool = repo.pool
        environment.monitor.subscribe(io.ktor.server.application.ApplicationStopped) { repo.close() }
        issuanceRepository = repo
        rateLimiter = RedisRateLimiter(repo.pool)
    } else {
        issuanceRepository = InMemoryIssuanceSessionRepository()
        rateLimiter = RateLimiter
    }
    val issuanceService = CredentialIssuanceService(issuanceRepository)
    val nonceHmacSecret = config.propertyOrNull("session.nonceHmacSecret")?.getString()?.takeIf { it.length == 64 }
    val nonceStore =
        if (nonceHmacSecret != null) {
            NonceStore(secretKey = java.util.HexFormat.of().parseHex(nonceHmacSecret))
        } else {
            logger.warn("NONCE_HMAC_SECRET が未設定です。インスタンスをまたいだ nonce 検証ができません（単一インスタンスのみ有効）。")
            NonceStore()
        }

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
        trustedProxyCount = trustedProxyCount,
        rateLimiter = rateLimiter,
        checkReady = {
            redisPool?.let { pool ->
                runCatching { pool.resource.use { jedis -> jedis.ping() == "PONG" } }.getOrDefault(false)
            } ?: true
        },
    )
}
