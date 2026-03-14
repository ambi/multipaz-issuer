package org.vdcapps.issuer.web.plugins

import freemarker.cache.ClassTemplateLoader
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
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
import org.vdcapps.issuer.application.IssueCredentialUseCase
import org.vdcapps.issuer.domain.credential.CredentialIssuanceService
import org.vdcapps.issuer.domain.credential.NonceStore
import org.vdcapps.issuer.infrastructure.entra.EntraIdClient
import org.vdcapps.issuer.infrastructure.multipaz.PhotoIdBuilder
import org.vdcapps.issuer.web.routes.configureAuthRoutes
import org.vdcapps.issuer.web.routes.configureHealthRoutes
import org.vdcapps.issuer.web.routes.configureHomeRoutes
import org.vdcapps.issuer.web.routes.configureOid4vciRoutes
import java.util.UUID

private val logger = LoggerFactory.getLogger("Routing")

fun Application.configureRouting(
    baseUrl: String,
    entraIdClient: EntraIdClient,
    issuanceService: CredentialIssuanceService,
    issueCredentialUseCase: IssueCredentialUseCase,
    photoIdBuilder: PhotoIdBuilder,
    nonceStore: NonceStore,
    checkReady: () -> Boolean = { true },
) {
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
        configureOid4vciRoutes(baseUrl, issuanceService, issueCredentialUseCase, photoIdBuilder, nonceStore)
    }
}
