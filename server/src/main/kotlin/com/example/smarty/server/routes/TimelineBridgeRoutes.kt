package com.example.smarty.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * TimelineBridgeRoutes
 *
 * Receives real-time telemetry events from the OpenCode plugin (timeline-bridge.ts)
 * running inside the OpenCode daemon process.
 *
 * Plugin -> POST /opencode/events -> here -> log + in-memory store
 *
 * Phase 1: Verification only — log everything, store in memory.
 * Phase 2: Broadcast to connected WebSocket clients (app).
 */
fun Application.configureTimelineBridgeRoutes() {

    val bridge = TimelineBridgeService

    routing {
        post("/opencode/events") {
            val body = call.receiveText()
            val ts = System.currentTimeMillis()

            try {
                val event = Json.parseToJsonElement(body).jsonObject
                val kind = event["kind"]?.jsonPrimitive?.content ?: "unknown"
                val sessionID = event["sessionID"]?.jsonPrimitive?.content ?: "no-session"

                bridge.ingest(kind, sessionID, event, ts)

                // Log with emoji prefix for easy identification in HF Space logs
                val preview = body.substring(0, minOf(body.length, 300))
                println("\uD83D\uDC26\u200D\uD83D\uDD25 [KTOR-RECV] kind=$kind session=${sessionID.take(8)} body=$preview")
                println("\uD83D\uDC26\u200D\uD83D\uDD25 [KTOR-RECV] totalEvents=${bridge.totalEvents} totalSessions=${bridge.totalSessions}")

                call.respond(HttpStatusCode.OK, mapOf("ok" to true))
            } catch (e: Exception) {
                val preview = body.substring(0, minOf(body.length, 200))
                println("\uD83D\uDC26\u200D\uD83D\uDD25 [KTOR-RECV-ERROR] error=${e.message} body=$preview")
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "unknown")))
            }
        }
    }

    println("\uD83D\uDC26\u200D\uD83D\uDD25 [KTOR] /opencode/events route registered")
}

/**
 * In-memory timeline storage for Phase 1 verification.
 * Keyed by sessionID. Each session has an ordered list of event snapshots.
 */
object TimelineBridgeService {

    data class EventSnapshot(
        val kind: String,
        val sessionID: String,
        val ts: Long,
        val raw: JsonObject
    )

    private val timelines = ConcurrentHashMap<String, CopyOnWriteArrayList<EventSnapshot>>()

    val totalEvents: Long
        get() = timelines.values.sumOf { it.size.toLong() }

    val totalSessions: Int
        get() = timelines.size

    fun ingest(kind: String, sessionID: String, event: JsonObject, ts: Long) {
        if (sessionID == "no-session") return

        val list = timelines.getOrPut(sessionID) { CopyOnWriteArrayList() }
        list.add(EventSnapshot(kind, sessionID, ts, event))

        // Log important events with extra detail
        when (kind) {
            "session.created" -> {
                println("\uD83D\uDC26\u200D\uD83D\uDD25 [TIMELINE] New session: $sessionID")
            }
            "session.idle" -> {
                val events = list.size
                println("\uD83D\uDC26\u200D\uD83D\uDD25 [TIMELINE] Session done: $sessionID | events=$events")
            }
            "part.updated" -> {
                val partType = event["partType"]?.jsonPrimitive?.content ?: "unknown"
                when (partType) {
                    "reasoning" -> {
                        val reasoning = (event["reasoning"]?.jsonPrimitive?.content ?: "").take(150)
                        println("\uD83D\uDC26\u200D\uD83D\uDD25 [TIMELINE] Reasoning: $reasoning")
                    }
                    "text" -> {
                        val text = (event["text"]?.jsonPrimitive?.content ?: "").take(150)
                        println("\uD83D\uDC26\u200D\uD83D\uDD25 [TIMELINE] Text: $text")
                    }
                    "tool" -> {
                        val tool = event["tool"]?.jsonPrimitive?.content ?: "?"
                        val state = event["state"]?.jsonPrimitive?.content ?: "?"
                        println("\uD83D\uDC26\u200D\uD83D\uDD25 [TIMELINE] Tool [$tool] -> $state")
                    }
                    "subtask" -> {
                        val agent = event["agent"]?.jsonPrimitive?.content ?: "?"
                        val state = event["state"]?.jsonPrimitive?.content ?: "?"
                        println("\uD83D\uDC26\u200D\uD83D\uDD25 [TIMELINE] Sub-agent [$agent] -> $state")
                    }
                    "step-finish" -> {
                        val cost = event["cost"]?.jsonPrimitive?.content ?: "?"
                        println("\uD83D\uDC26\u200D\uD83D\uDD25 [TIMELINE] Step finished, cost=\$$cost")
                    }
                }
            }
            "tool.before" -> {
                val tool = event["tool"]?.jsonPrimitive?.content ?: "?"
                println("\uD83D\uDC26\u200D\uD83D\uDD25 [TIMELINE] Tool call: $tool")
            }
            "tool.after" -> {
                val tool = event["tool"]?.jsonPrimitive?.content ?: "?"
                println("\uD83D\uDC26\u200D\uD83D\uDD25 [TIMELINE] Tool result: $tool")
            }
        }
    }

    fun getTimeline(sessionID: String): List<EventSnapshot> =
        timelines[sessionID]?.toList() ?: emptyList()

    fun getAllSessionIDs(): Set<String> = timelines.keys
}
