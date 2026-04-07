package com.example.smarty.server.routes

import com.example.smarty.protocol.ExecutionPolicy
import com.example.smarty.protocol.HandshakeRequest
import com.example.smarty.protocol.HandshakeResponse
import com.example.smarty.protocol.HybridActionPolicy
import com.example.smarty.protocol.RemoteSyncState
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.*

fun Application.configureHandshakeRoutes() {
    routing {
        post("/api/v1/session/init") {
            val request = call.receive<HandshakeRequest>()

            // Generate a session ID
            val sessionId = UUID.randomUUID().toString()

            // Define a default execution policy (this could be dynamic based on hardware)
            val policy =
                ExecutionPolicy(
                    serverSide =
                        listOf(
                            "CREATE_NOTE", "UPDATE_NOTE", "DELETE_NOTE", "SEARCH_NOTES",
                            "SUMMARIZE_NOTE", "WEB_SEARCH", "BATCH_ACTIONS",
                            "SCHEDULE_EVENT", "DEEP_RESEARCH", "MEMORY_STORE",
                            "GENERATE_BRIEFING", "ANALYZE_DOCUMENT",
                        ),
                    deviceSide =
                        listOf(
                            "TOGGLE_FLASHLIGHT",
                            "SET_VOLUME",
                            "PLAY_AUDIO",
                            "LAUNCH_APP",
                            "TAKE_SCREENSHOT",
                            "TRIGGER_HAPTIC",
                            "CAPTURE_SCREEN_CONTEXT",
                        ),
                    hybrid =
                        listOf(
                            HybridActionPolicy(
                                action = "TRANSCRIBE_AUDIO",
                                prefer = "server",
                                fallback = "device",
                                condition = "network_validated == true",
                            ),
                        ),
                )

            val response =
                HandshakeResponse(
                    sessionId = sessionId,
                    executionPolicy = policy,
                    syncState =
                        RemoteSyncState(
                            lastServerTimestamp = java.time.Instant.now().toString(),
                            pendingSyncCount = 0,
                        ),
                )

            call.respond(response)
        }
    }
}
