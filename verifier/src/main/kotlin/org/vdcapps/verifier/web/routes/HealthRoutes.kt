package org.vdcapps.verifier.web.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

@Serializable
private data class HealthResponse(
    val status: String,
    val details: Map<String, String> = emptyMap(),
)

fun Route.configureHealthRoutes(checkReady: () -> Boolean = { true }) {
    // Liveness probe: アプリが起動しているか確認する。
    get("/health") {
        call.respond(HealthResponse("ok"))
    }

    // Readiness probe: 依存サービス（Redis 等）が使用可能か確認する。
    get("/readiness") {
        if (checkReady()) {
            call.respond(HealthResponse("ready"))
        } else {
            call.respond(HttpStatusCode.ServiceUnavailable, HealthResponse("not ready", mapOf("redis" to "unavailable")))
        }
    }
}
