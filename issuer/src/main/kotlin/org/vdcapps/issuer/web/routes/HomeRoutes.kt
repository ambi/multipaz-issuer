package org.vdcapps.issuer.web.routes

import io.ktor.server.freemarker.FreeMarkerContent
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import org.vdcapps.issuer.web.plugins.UserSession

fun Route.configureHomeRoutes() {
    get("/") {
        val session = call.sessions.get<UserSession>()
        if (session != null) {
            call.respond(
                FreeMarkerContent(
                    "profile.ftl",
                    mapOf(
                        "displayName" to session.displayName,
                        "givenName" to session.givenName,
                        "familyName" to session.familyName,
                        "email" to (session.email ?: ""),
                        "hasPhoto" to session.hasPhoto,
                    ),
                ),
            )
        } else {
            call.respond(FreeMarkerContent("home.ftl", emptyMap<String, Any>()))
        }
    }

    get("/profile") {
        val session = call.sessions.get<UserSession>()
        if (session == null) {
            call.respondRedirect("/")
            return@get
        }
        call.respond(
            FreeMarkerContent(
                "profile.ftl",
                mapOf(
                    "displayName" to session.displayName,
                    "givenName" to session.givenName,
                    "familyName" to session.familyName,
                    "email" to (session.email ?: ""),
                    "hasPhoto" to session.hasPhoto,
                ),
            ),
        )
    }
}
