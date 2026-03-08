package org.multipaz.issuer.web.plugins

import io.ktor.server.application.Application
import io.ktor.server.freemarker.FreeMarker
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import freemarker.cache.ClassTemplateLoader
import io.ktor.server.routing.routing
import org.multipaz.issuer.application.IssueCredentialUseCase
import org.multipaz.issuer.domain.credential.CredentialIssuanceService
import org.multipaz.issuer.domain.credential.NonceStore
import org.multipaz.issuer.infrastructure.entra.EntraIdClient
import org.multipaz.issuer.infrastructure.multipaz.PhotoIdBuilder
import org.multipaz.issuer.web.routes.configureAuthRoutes
import org.multipaz.issuer.web.routes.configureHomeRoutes
import org.multipaz.issuer.web.routes.configureOid4vciRoutes
import org.slf4j.event.Level

fun Application.configureRouting(
    baseUrl: String,
    entraIdClient: EntraIdClient,
    issuanceService: CredentialIssuanceService,
    issueCredentialUseCase: IssueCredentialUseCase,
    photoIdBuilder: PhotoIdBuilder,
    nonceStore: NonceStore,
) {
    install(FreeMarker) {
        templateLoader = ClassTemplateLoader(this::class.java.classLoader, "templates")
    }

    install(CallLogging) {
        level = Level.INFO
    }

    routing {
        configureHomeRoutes()
        configureAuthRoutes(entraIdClient)
        configureOid4vciRoutes(baseUrl, issuanceService, issueCredentialUseCase, photoIdBuilder, nonceStore)
    }
}
