package org.vdcapps.issuer.web.plugins

import freemarker.cache.ClassTemplateLoader
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.freemarker.FreeMarker
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import org.vdcapps.issuer.application.IssueCredentialUseCase
import org.vdcapps.issuer.domain.credential.CredentialIssuanceService
import org.vdcapps.issuer.domain.credential.NonceStore
import org.vdcapps.issuer.infrastructure.entra.EntraIdClient
import org.vdcapps.issuer.infrastructure.multipaz.PhotoIdBuilder
import org.vdcapps.issuer.web.routes.configureAuthRoutes
import org.vdcapps.issuer.web.routes.configureHealthRoutes
import org.vdcapps.issuer.web.routes.configureHomeRoutes
import org.vdcapps.issuer.web.routes.configureOid4vciRoutes
import org.vdcapps.issuer.web.util.RateLimiter
import org.vdcapps.issuer.web.util.RateLimiterPort
import java.util.UUID

private val logger = LoggerFactory.getLogger("Routing")

fun Application.configureRouting(
    baseUrl: String,
    entraIdClient: EntraIdClient,
    issuanceService: CredentialIssuanceService,
    issueCredentialUseCase: IssueCredentialUseCase,
    photoIdBuilder: PhotoIdBuilder,
    nonceStore: NonceStore,
    trustedProxyCount: Int = 1,
    rateLimiter: RateLimiterPort = RateLimiter,
    checkReady: () -> Boolean = { true },
) {
    // リバースプロキシ経由のクライアント IP を request.origin.remoteHost に反映する。
    // trustedProxyCount 個のプロキシが X-Forwarded-For の末尾に追記したものとみなす。
    install(XForwardedHeaders) {
        skipLastProxies(trustedProxyCount)
    }

    // HTTP セキュリティヘッダー
    val isHttps = baseUrl.startsWith("https")
    install(
        createApplicationPlugin("SecurityHeaders") {
            onCall { call ->
                call.response.headers.apply {
                    append("X-Frame-Options", "DENY")
                    append("X-Content-Type-Options", "nosniff")
                    append("X-XSS-Protection", "1; mode=block")
                    append("Referrer-Policy", "strict-origin-when-cross-origin")
                    append(
                        "Content-Security-Policy",
                        "default-src 'self'; " +
                            "script-src 'self' 'unsafe-inline' https://cdn.tailwindcss.com; " +
                            "style-src 'self' 'unsafe-inline'; " +
                            "img-src 'self' data:; " +
                            "connect-src 'self'",
                    )
                    if (isHttps) {
                        append("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
                    }
                }
            }
        },
    )

    val prometheusRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    install(MicrometerMetrics) {
        registry = prometheusRegistry
    }

    install(FreeMarker) {
        templateLoader = ClassTemplateLoader(this::class.java.classLoader, "templates")
    }

    install(CallId) {
        // X-Request-Id ヘッダーがあれば採用、なければ新規 UUID を生成
        retrieveFromHeader("X-Request-Id")
        generate { UUID.randomUUID().toString() }
        replyToHeader("X-Request-Id")
    }

    install(CallLogging) {
        level = Level.INFO
        callIdMdc("requestId")
    }

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            logger.error("Unhandled exception", cause)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Internal server error"))
        }
    }

    routing {
        configureHealthRoutes(checkReady)
        configureHomeRoutes()
        configureAuthRoutes(entraIdClient)
        configureOid4vciRoutes(baseUrl, issuanceService, issueCredentialUseCase, photoIdBuilder, nonceStore, rateLimiter)
        // Prometheus スクレイプエンドポイント（監視システムからのアクセスを想定）
        get("/metrics") {
            call.respondText(prometheusRegistry.scrape())
        }
    }
}
