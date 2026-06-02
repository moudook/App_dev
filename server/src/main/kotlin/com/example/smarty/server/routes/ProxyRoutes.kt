package com.example.smarty.server.routes

import com.example.smarty.server.HttpClientSingleton
import com.example.smarty.server.plugins.FirebaseUserPrincipal
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveText
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("ProxyRoutes")

fun Application.configureProxyRoutes() {
    val hfToken = System.getenv("HF_TOKEN") ?: ""
    val hfSpaceUrl = System.getenv("HF_SPACE_URL") ?: run {
        logger.warn("HF_SPACE_URL not set, proxy will be unavailable")
        return
    }
    if (hfToken.isBlank()) {
        logger.warn("HF_TOKEN not set, proxy will be unavailable")
        return
    }

    val client = HttpClientSingleton.client

    routing {
        authenticate("firebase") {
            route("/api/v1/proxy") {
                get("/{path...}") {
                    val user = call.principal<FirebaseUserPrincipal>()
                    if (user == null) {
                        call.respondText("{\"error\":\"Authentication required\"}", ContentType.Application.Json, HttpStatusCode.Unauthorized)
                        return@get
                    }

                    val path = call.parameters.getAll("path")?.joinToString("/") ?: ""
                    val uri = call.request.uri
                    val queryString = uri.substringAfter("?", "")
                    val target = buildString {
                        append(hfSpaceUrl.trimEnd('/'))
                        append('/')
                        append(path)
                        if (queryString.isNotEmpty()) {
                            append('?')
                            append(queryString)
                        }
                    }

                    logger.info("Proxying GET $target")
                    val response = client.get(target) {
                        header("Authorization", "Bearer $hfToken")
                    }

                    val ct = response.contentType()
                    val responseContentType = ct?.toString() ?: "application/json"
                    val body = response.bodyAsText()
                    call.response.header("Content-Type", responseContentType)
                    call.respondText(body, ContentType.parse(responseContentType), response.status)
                }

                post("/{path...}") {
                    val user = call.principal<FirebaseUserPrincipal>()
                    if (user == null) {
                        call.respondText("{\"error\":\"Authentication required\"}", ContentType.Application.Json, HttpStatusCode.Unauthorized)
                        return@post
                    }

                    val path = call.parameters.getAll("path")?.joinToString("/") ?: ""
                    val target = "${hfSpaceUrl.trimEnd('/')}/${path}"

                    logger.info("Proxying POST $target")
                    val requestContentType = call.request.contentType()
                    val body = call.receiveText()
                    val response = client.post(target) {
                        header("Authorization", "Bearer $hfToken")
                        header("Content-Type", requestContentType?.toString() ?: "application/json")
                        setBody(body)
                    }

                    val ct = response.contentType()
                    val responseContentType = ct?.toString() ?: "application/json"
                    val responseBody = response.bodyAsText()
                    call.response.header("Content-Type", responseContentType)
                    call.respondText(responseBody, ContentType.parse(responseContentType), response.status)
                }
            }
        }
    }
}
