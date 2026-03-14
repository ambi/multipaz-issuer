package org.vdcapps.verifier

import io.ktor.server.application.Application
import org.slf4j.LoggerFactory
import org.vdcapps.verifier.application.VerifyCredentialUseCase
import org.vdcapps.verifier.domain.verification.InMemoryVerificationSessionRepository
import org.vdcapps.verifier.domain.verification.VerificationService
import org.vdcapps.verifier.domain.verification.VerificationSessionRepository
import org.vdcapps.verifier.infrastructure.multipaz.MdocVerifier
import org.vdcapps.verifier.infrastructure.redis.RedisVerificationSessionRepository
import org.vdcapps.verifier.web.plugins.configureRouting
import org.vdcapps.verifier.web.plugins.configureSerialization
import redis.clients.jedis.JedisPool
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

private val logger = LoggerFactory.getLogger("Application")

/** PEM ファイルから X.509 証明書リストを読み込む。パスが空の場合は空リストを返す。 */
private fun loadTrustedCertificates(path: String?): List<X509Certificate> {
    if (path.isNullOrBlank()) return emptyList()
    return try {
        val cf = CertificateFactory.getInstance("X.509")
        val certs =
            java.io.File(path).inputStream().use { stream ->
                cf.generateCertificates(stream).map { it as X509Certificate }
            }
        logger.info("信頼済み発行者証明書を ${certs.size} 件ロードしました: $path")
        certs
    } catch (e: Exception) {
        logger.error("信頼済み発行者証明書のロードに失敗しました: $path", e)
        throw e
    }
}

fun main(args: Array<String>): Unit =
    io.ktor.server.netty.EngineMain
        .main(args)

fun Application.module() {
    val config = environment.config

    // 設定値の読み込み
    val baseUrl = config.property("verifier.baseUrl").getString()
    val trustedCertPath = config.propertyOrNull("verifier.trustedIssuerCertPath")?.getString()

    // インフラ層
    val trustedCertificates = loadTrustedCertificates(trustedCertPath)
    val responseUri = "$baseUrl/verifier/response"
    val mdocVerifier = MdocVerifier(trustedCertificates, responseUri)

    // ドメイン層
    val redisUrl = config.propertyOrNull("session.redisUrl")?.getString()?.takeIf { it.isNotBlank() }
    var redisPool: JedisPool? = null
    val verificationRepository: VerificationSessionRepository =
        if (redisUrl != null) {
            RedisVerificationSessionRepository(redisUrl).also { repo ->
                redisPool = repo.pool
                environment.monitor.subscribe(io.ktor.server.application.ApplicationStopped) { repo.close() }
            }
        } else {
            InMemoryVerificationSessionRepository()
        }
    val verificationService = VerificationService(verificationRepository)

    // アプリケーション層
    val verifyCredentialUseCase =
        VerifyCredentialUseCase(
            verificationService = verificationService,
            mdocVerifier = mdocVerifier,
        )

    // プラグイン・ルーティングの設定
    configureSerialization()
    configureRouting(
        baseUrl = baseUrl,
        verificationService = verificationService,
        verifyCredentialUseCase = verifyCredentialUseCase,
        checkReady = {
            redisPool?.let { pool ->
                runCatching { pool.resource.use { jedis -> jedis.ping() == "PONG" } }.getOrDefault(false)
            } ?: true
        },
    )
}
