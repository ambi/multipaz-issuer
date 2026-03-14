package org.vdcapps.verifier.web.plugins

import freemarker.cache.ClassTemplateLoader
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.freemarker.FreeMarker
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import org.vdcapps.verifier.application.VerifyCredentialUseCase
import org.vdcapps.verifier.domain.verification.VerificationService
import org.vdcapps.verifier.web.routes.configureHealthRoutes
import org.vdcapps.verifier.web.routes.configureVerifierRoutes
import java.util.UUID

private val logger = LoggerFactory.getLogger("Routing")

fun Application.configureRouting(
    baseUrl: String,
    verificationService: VerificationService,
    verifyCredentialUseCase: VerifyCredentialUseCase,
    checkReady: () -> Boolean = { true },
) {
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
        configureVerifierRoutes(baseUrl, verificationService, verifyCredentialUseCase)
    }
}
