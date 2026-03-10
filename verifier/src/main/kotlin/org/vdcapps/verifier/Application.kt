package org.vdcapps.verifier

import io.ktor.server.application.Application
import org.vdcapps.verifier.application.VerifyCredentialUseCase
import org.vdcapps.verifier.domain.verification.InMemoryVerificationSessionRepository
import org.vdcapps.verifier.domain.verification.VerificationService
import org.vdcapps.verifier.infrastructure.multipaz.MdocVerifier
import org.vdcapps.verifier.web.plugins.configureRouting
import org.vdcapps.verifier.web.plugins.configureSerialization

fun main(args: Array<String>): Unit =
    io.ktor.server.netty.EngineMain
        .main(args)

fun Application.module() {
    val config = environment.config

    // 設定値の読み込み
    val baseUrl = config.property("verifier.baseUrl").getString()

    // インフラ層
    val mdocVerifier = MdocVerifier()

    // ドメイン層
    val verificationRepository = InMemoryVerificationSessionRepository()
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
    )
}
