package org.vdcapps.verifier.web.plugins

import freemarker.cache.ClassTemplateLoader
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.freemarker.FreeMarker
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.routing.routing
import org.slf4j.event.Level
import org.vdcapps.verifier.application.VerifyCredentialUseCase
import org.vdcapps.verifier.domain.verification.VerificationService
import org.vdcapps.verifier.web.routes.configureVerifierRoutes

fun Application.configureRouting(
    baseUrl: String,
    verificationService: VerificationService,
    verifyCredentialUseCase: VerifyCredentialUseCase,
) {
    install(FreeMarker) {
        templateLoader = ClassTemplateLoader(this::class.java.classLoader, "templates")
    }

    install(CallLogging) {
        level = Level.INFO
    }

    routing {
        configureVerifierRoutes(baseUrl, verificationService, verifyCredentialUseCase)
    }
}
