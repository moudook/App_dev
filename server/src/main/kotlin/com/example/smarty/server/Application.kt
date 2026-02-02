package com.example.smarty.server

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.sse.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import com.example.smarty.server.routes.configureHealthRoutes
import com.example.smarty.server.routes.configureChatRoutes

/**
 * Smarty Server - Cloud-hosted agent runtime.
 *
 * This is the entry point for the Ktor server that will eventually
 * host the KOOG agent and serve commands to the Android client.
 *
 * Current capabilities:
 * - /health endpoint for status checks
 * - /chat/stream SSE endpoint for agent event streaming
 *
 * Future capabilities:
 * - KOOG agent runtime
 * - Command dispatch to Android clients
 */
/**
 * Server port. Can be overridden via SERVER_PORT environment variable.
 */
private val serverPort = System.getenv("SERVER_PORT")?.toIntOrNull() ?: 7860

fun main() {
    embeddedServer(Netty, port = serverPort, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    // Configure JSON serialization
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
        })
    }

    // Configure SSE plugin for streaming
    install(SSE)

    // Configure routes
    configureHealthRoutes()
    configureChatRoutes()

    // Log startup
    log.info("Smarty Server started on port $serverPort")
}
