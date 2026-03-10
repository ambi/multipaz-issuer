package org.vdcapps.issuer.web.plugins

import freemarker.cache.ClassTemplateLoader
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.freemarker.FreeMarker
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.routing.routing
import org.slf4j.event.Level
import org.vdcapps.issuer.application.IssueCredentialUseCase
import org.vdcapps.issuer.domain.credential.CredentialIssuanceService
import org.vdcapps.issuer.domain.credential.NonceStore
import org.vdcapps.issuer.infrastructure.entra.EntraIdClient
import org.vdcapps.issuer.infrastructure.multipaz.PhotoIdBuilder
import org.vdcapps.issuer.web.routes.configureAuthRoutes
import org.vdcapps.issuer.web.routes.configureHomeRoutes
import org.vdcapps.issuer.web.routes.configureOid4vciRoutes

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
