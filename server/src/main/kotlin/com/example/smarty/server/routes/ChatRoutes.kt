package com.example.smarty.server.routes

import com.example.smarty.protocol.AgentEvent
import com.example.smarty.protocol.ClientEvent
import com.example.smarty.server.agent.ServerAgent
import com.example.smarty.server.data.CalendarEventNotesRepository
import com.example.smarty.server.data.CalendarRepository
import com.example.smarty.server.data.ChatMessageNotesRepository
import com.example.smarty.server.data.ChatRepository
import com.example.smarty.server.data.ConversationSummarizer
import com.example.smarty.server.data.DatabaseFactory
import com.example.smarty.server.data.NoteRepository
import com.example.smarty.server.data.PostgresVectorStore
import com.example.smarty.server.data.Stack
import com.example.smarty.server.data.StackRepository
import com.example.smarty.server.data.TimerRepository
import com.example.smarty.server.llm.LlmMessage
import com.example.smarty.server.llm.LlmProviderFactory
import com.example.smarty.server.llm.OpencodeModelRegistry
import com.example.smarty.server.plugins.firebaseUser
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.application
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.auth.authenticate
import io.ktor.server.request.*
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/*
 * Configure chat streaming routes.
 *
 * Endpoints:
 * - GET /chat/stream?query=...&sessionId=... - SSE stream of agent events (authenticated)
 * - POST /chat/stream - SSE stream with body payload for large context (authenticated)
 * - POST /chat/events - Receive events from client (authenticated)
 * - GET /chat/events/test - Test endpoint for AgentEvent serialization (public)
 */

/** Request body for POST /chat/stream endpoint. Allows sending larger payloads including file context. */
@Serializable
data class ChatRequest(
    val query: String,
    val sessionId: String? = null,
    val provider: String? = null,
    val providerUrl: String? = null,
    val model: String? = null,
    val variant: String? = null,
    val token: String? = null,
    val fileContext: String? = null, // Extracted text from uploaded files
    val attachments: List<AttachmentInfo>? = null, // Metadata about attachments
    val timezone: String? = null, // User's timezone (e.g., "America/New_York")
    val clientTime: Long? = null, // User's current time in epoch millis
    val personality: String? = null, // AI personality: PROFESSIONAL, CASUAL, CONCISE, DETAILED
    val messageId: String? = null, // Client-generated message ID for sync matching
    val section: String? = null, // "chat" or "notes" — Issue #18: server-side mode differentiation
)

@Serializable
data class AttachmentInfo(
    val type: String, // "image", "pdf", "document"
    val name: String,
    val mimeType: String? = null,
)

@Serializable
data class BriefingRequest(
    val prompt: String,
    val token: String? = null,
)

@Serializable
data class BriefingResponse(
    val briefing: String,
    val success: Boolean = true,
)

private fun normalizeProviderSelection(provider: String?): String? {
    // feat/cli is OpenCode-only. All provider selection is normalized to null (default),
    // meaning the server always uses OpencodeLlmProvider. Legacy API provider names
    // are accepted without error but silently mapped to the OpenCode default.
    return null
}

fun Application.configureChatRoutes(noteService: com.example.smarty.server.services.NoteService? = null) {
    // JSON encoder for events
    val json =
        Json {
            encodeDefaults = true
            prettyPrint = false
            classDiscriminator = "type"
            ignoreUnknownKeys = true
        }

    // Initialize generic HTTP Client for LLM and Tools (reused from factory)
    val httpClient = LlmProviderFactory.getOrCreateHttpClient()

    // Initialize dependencies (Manual DI for now)
    // In production, use Koin or Dagger
    val vectorStore = PostgresVectorStore()

    // Default provider - use cached instance for better performance
    val llmProvider = LlmProviderFactory.getOrCreateProvider(httpClient)
    val summarizer = ConversationSummarizer(llmProvider)

    // Database and Repository
    val dataSource = DatabaseFactory.getDataSource()
    val chatMessageNotesRepo = dataSource?.let { ChatMessageNotesRepository(it) }
    val calendarEventNotesRepo = dataSource?.let { CalendarEventNotesRepository(it) }
    val chatRepository = dataSource?.let { ChatRepository(it, chatMessageNotesRepo!!) }
    val noteRepository = dataSource?.let { NoteRepository(it, chatMessageNotesRepo!!, calendarEventNotesRepo!!) }
    val timerRepository = dataSource?.let { TimerRepository(it) }
    val calendarRepository = dataSource?.let { CalendarRepository(it, calendarEventNotesRepo!!) }
    val stackRepository = dataSource?.let { StackRepository(it) }

    routing {
        // Authenticated routes
        authenticate("firebase") {
            /**
             * Generate daily briefing.
             */
            post("/briefing/generate") {
                val user = call.firebaseUser()
                if (user == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
                    return@post
                }

                try {
                    val request = call.receive<BriefingRequest>()
                    val userId = user.userId

                    // Input validation
                    com.example.smarty.server.utils.InputValidation
                        .validateQuery(request.prompt)
                    request.token?.let {
                        if (it.length > 500) throw IllegalArgumentException("Token too long")
                    }

                    call.application.log.info("Generating daily briefing for user: $userId")

                    // Use default provider
                    val briefingSummarizer = ConversationSummarizer(llmProvider)

                    // Create agent for single run
                    val agent =
                        ServerAgent(
                            llmProvider = llmProvider,
                            vectorStore = vectorStore,
                            summarizer = briefingSummarizer,
                            noteRepository = noteRepository,
                            timerRepository = timerRepository,
                            calendarRepository = calendarRepository,
                            eventEmitter = { /* No streaming for briefing yet */ },
                            userId = userId,
                            noteService = noteService,
                            capabilities =
                                com.example.smarty.server.agent.DeviceRegistry
                                    .getCapabilities(userId),
                        )

                    // Run the agent (no modelOverride - provider already selected)
                    val briefing =
                        agent.run(
                            query = request.prompt,
                            history = emptyList(),
                        )

                    call.respond(HttpStatusCode.OK, BriefingResponse(briefing))
                } catch (e: Exception) {
                    call.application.log.error("Briefing generation failed", e)
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "An internal error occurred."))
                }
            }

            /**
             * Receive events from the client (e.g., tool results).
             * These events are persisted to provide context for the next agent turn.
             */
            post("/chat/events") {
                val user = call.firebaseUser()
                if (user == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
                    return@post
                }

                val sessionId = call.request.queryParameters["sessionId"]
                if (sessionId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, "Missing sessionId")
                    return@post
                }

                // Input validation
                try {
                    com.example.smarty.server.utils.InputValidation
                        .validateSessionId(sessionId)
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid sessionId"))
                    return@post
                }

                try {
                    val event = call.receive<ClientEvent>()
                    call.application.log.info(
                        "Received client event: ${event::class.simpleName} for session: $sessionId (user: ${user.userId})",
                    )

                    if (chatRepository != null) {
                        when (event) {
                            is ClientEvent.ToolResult -> {
                                val statusPrefix = if (event.isError) "Error" else "Success"
                                val content = "Tool Output [${event.commandId}] ($statusPrefix): ${event.result}"
                                chatRepository.saveMessage(user.userId, sessionId, LlmMessage.Role.TOOL.name, content)
                                com.example.smarty.server.agent.DeviceResponseRegistry.resolveRequest(
                                    event.commandId,
                                    mapOf("result" to event.result, "status" to if (event.isError) "error" else "success")
                                )
                            }
                            is ClientEvent.ActiveNotesResponse -> {
                                val content = "Active Notes Context: ${event.notes.joinToString { it.title }}"
                                chatRepository.saveMessage(user.userId, sessionId, LlmMessage.Role.TOOL.name, content)
                            }
                            is ClientEvent.SearchResultsResponse -> {
                                val content = "Search Results Context: ${event.results.joinToString { it.title }}"
                                chatRepository.saveMessage(user.userId, sessionId, LlmMessage.Role.TOOL.name, content)
                            }
                            is ClientEvent.RecallResultsResponse -> {
                                val content =
                                    "Knowledge Recall Results:\n" +
                                        event.results.joinToString("\n") { "- ${it.title}: ${it.content} (Score: ${it.score})" }
                                chatRepository.saveMessage(user.userId, sessionId, LlmMessage.Role.TOOL.name, content)
                            }
                            is ClientEvent.CalendarEventsResponse -> {
                                val content =
                                    "Calendar Events:\n" +
                                        event.events.joinToString(
                                            "\n",
                                        ) { "- ${it.title} (${java.time.Instant.ofEpochMilli(it.startTime)})" }
                                chatRepository.saveMessage(user.userId, sessionId, LlmMessage.Role.TOOL.name, content)
                            }
                            is ClientEvent.ScreenContextResponse -> {
                                val ctx = event.context
                                val content =
                                    if (ctx != null) {
                                        "Current Screen Context:\nApp: ${ctx.referringApp}\nSelected Text: ${ctx.selectedText}\nData: ${ctx.contextData}"
                                    } else {
                                        "Screen Context: No data available"
                                    }
                                chatRepository.saveMessage(user.userId, sessionId, LlmMessage.Role.TOOL.name, content)
                            }
                            is ClientEvent.SystemStatusResponse -> {
                                com.example.smarty.server.agent.DeviceResponseRegistry.resolveRequest(
                                    event.commandId,
                                    event.status,
                                )
                            }
                            else -> {
                                // Other events (AppState, SystemStatus) might not need to be in chat history
                                // or could be stored differently. For now, we just acknowledge them.
                            }
                        }
                    }
                    call.respond(HttpStatusCode.OK)
                } catch (e: Exception) {
                    call.application.log.error("Failed to process client event", e)
                    call.respond(HttpStatusCode.InternalServerError, "An internal error occurred while processing the event.")
                }
            }

            /**
             * WebSocket endpoint for bidirectional agent event streaming and client events.
             * Supports robust reconnection via AgentRunManager.
             */
            webSocket("/chat/ws") {
                val token = call.request.headers[io.ktor.http.HttpHeaders.Authorization]?.removePrefix("Bearer ")
                val user =
                    com.example.smarty.server.plugins
                        .verifyFirebaseToken(token ?: "", null)

                if (user == null) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Authentication required"))
                    return@webSocket
                }

                val userId = user.userId
                val sessionIdParam = call.request.queryParameters["sessionId"] ?: UUID.randomUUID().toString()

                call.application.log.info("WebSocket connected for user: $userId, session: $sessionIdParam")

                // Register with ActiveSessionManager so MCP server can find this session
                com.example.smarty.server.agent.ActiveSessionManager
                    .startSession(userId, sessionIdParam, "websocket")
                com.example.smarty.server.agent.ActiveUserRegistry
                    .setActive(userId)

                // MCP approval events reach this WebSocket via the AgentRunManager event flow,
                // NOT via ActiveEventBridge — the emitJob below subscribes to the per-session
                // flow and delivers every event (agent + MCP) through a single send() path,
                // eliminating the dual-coroutine Netty channel corruption.
                val flow =
                    com.example.smarty.server.agent.AgentRunManager
                        .getEventFlow(sessionIdParam)

                // Job 1: Heartbeat keepalive to prevent proxy idle timeouts
                // Use Frame.Text (not Ping) because HF proxy strips Ping frames
                val heartbeatJob =
                    launch {
                        while (isActive) {
                            delay(10_000L)
                            try {
                                send(Frame.Text("""{"type":"ping"}"""))
                            } catch (e: Exception) {
                                break
                            }
                        }
                    }

                // Job 2: Forward AgentEvents to client
                val emitJob =
                    launch {
                        flow.collect { event ->
                            try {
                                send(Frame.Text(json.encodeToString(AgentEvent.serializer(), event)))
                            } catch (e: Exception) {
                                call.application.log.debug("Failed to send WS frame to $userId: ${e.message}")
                            }
                        }
                    }

                // Job 3: Process incoming client messages
                try {
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val text = frame.readText()
                            try {
                                // First try to parse as ChatRequest to start a run
                                val chatRequest = json.decodeFromString<ChatRequest>(text)
                                val activeSessionId = chatRequest.sessionId ?: sessionIdParam

                                if (chatRequest.query.isNotBlank()) {
                                    // Ensure session exists before saving message (foreign key constraint)
                                    if (chatRepository != null) {
                                        val existingSession = chatRepository.getSession(userId, activeSessionId)
                                        if (existingSession == null) {
                                            chatRepository.createSessionWithId(userId, activeSessionId, "Continued Chat")
                                        }
                                    }
                                    chatRepository?.saveMessage(userId, activeSessionId, LlmMessage.Role.USER.name, chatRequest.query)
                                }

                                // Launch the run via decoupled manager
                                val started = com.example.smarty.server.agent.AgentRunManager.startRun(
                                    userId = userId,
                                    sessionId = activeSessionId,
                                    query = chatRequest.query,
                                    llmProvider = llmProvider,
                                    vectorStore = vectorStore,
                                    summarizer = summarizer,
                                    noteRepository = noteRepository,
                                    timerRepository = timerRepository,
                                    calendarRepository = calendarRepository,
                                    noteService = noteService,
                                    chatRepository = chatRepository,
                                    modelOverride = chatRequest.model?.let { OpencodeModelRegistry.requireAllowedFreeModel(it) },
                                    clientTimezone = chatRequest.timezone,
                                    clientTimeMillis = chatRequest.clientTime,
                                    personality = chatRequest.personality,
                                    history = chatRepository?.getHistory(userId, activeSessionId) ?: emptyList(),
                                    opencodeSessionId = chatRepository?.getSession(userId, activeSessionId)?.opencodeSessionId,
                                    messageId = chatRequest.messageId,
                                    variantOverride = chatRequest.variant,
                                    section = chatRequest.section,
                                )
                                if (!started) {
                                    send(Frame.Text(json.encodeToString(
                                        AgentEvent.Error(
                                            eventId = UUID.randomUUID().toString(),
                                            timestamp = System.currentTimeMillis(),
                                            message = "Agent run already active with pending approval. Please respond to the pending request first.",
                                            code = "PENDING_APPROVAL",
                                        ),
                                    )))
                                }
                            } catch (e: kotlinx.serialization.SerializationException) {
                                // If not ChatRequest, try ClientEvent
                                try {
                                    val clientEvent = json.decodeFromString<ClientEvent>(text)
                                    // Process ClientEvent similarly to the /chat/events POST route
                                    if (chatRepository != null) {
                                        when (clientEvent) {
                                            is ClientEvent.ToolResult -> {
                                                val statusPrefix = if (clientEvent.isError) "Error" else "Success"
                                                val content =
                                                    "Tool Output [${clientEvent.commandId}] ($statusPrefix): " +
                                                        clientEvent.result
                                                chatRepository.saveMessage(userId, sessionIdParam, LlmMessage.Role.TOOL.name, content)
                                                com.example.smarty.server.agent.DeviceResponseRegistry.resolveRequest(
                                                    clientEvent.commandId,
                                                    mapOf("result" to clientEvent.result, "status" to if (clientEvent.isError) "error" else "success")
                                                )
                                            }
                                            is ClientEvent.SystemStatusResponse -> {
                                                com.example.smarty.server.agent.DeviceResponseRegistry.resolveRequest(
                                                    clientEvent.commandId,
                                                    clientEvent.status,
                                                )
                                            }
                                            else -> {} // Handle other client events if needed
                                        }
                                    }
                                } catch (e2: Exception) {
                                    call.application.log.warn("Failed to parse WS message: $text", e2)
                                }
                            }
                        }
                    }
                } finally {
                    emitJob.cancel()
                    heartbeatJob.cancel()
                    // Don't cancel the run if there are pending approvals — the user
                    // can still respond via HTTP at /api/v1/chat/events/approval.
                    // AgentRunManager cleans up on natural completion or 2-hour timeout.
                    if (!com.example.smarty.server.agent.ApprovalRegistry.hasPendingForSession(sessionIdParam)) {
                        com.example.smarty.server.agent.AgentRunManager
                            .cancelRun(sessionIdParam)
                    } else {
                        call.application.log.info(
                            "WS disconnect: preserving agent run for session $sessionIdParam (pending approval)"
                        )
                    }
                    // End the session tracker entry; the agent run remains active
                    // in AgentRunManager's flow so a reconnecting client can resume.
                    com.example.smarty.server.agent.ActiveSessionManager
                        .endSession(userId, sessionIdParam)
                    call.application.log.info("WebSocket disconnected for user: $userId, session: $sessionIdParam")
                }
            }

            /**
             * POST version of SSE endpoint for larger payloads.
             * Accepts JSON body with query, sessionId, and optional file context.
             *
             * Usage:
             * ```
             * curl -X POST -H "Content-Type: application/json" -H "Authorization: Bearer <token>" \
             *   -d '{"query":"analyze this","sessionId":"UUID","fileContext":"extracted text from PDF..."}' \
             *   "http://localhost:7860/chat/query"
             * ```
             */
            post("/chat/query") {
                val user = call.firebaseUser()
                if (user == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
                    return@post
                }

                try {
                    val request = call.receive<ChatRequest>()
                    val userId = user.userId
                    var sessionId = request.sessionId

                    // Input validation
                    try {
                        com.example.smarty.server.utils.InputValidation
                            .validateQuery(request.query)
                        sessionId?.let {
                            com.example.smarty.server.utils.InputValidation
                                .validateSessionId(it)
                        }
                    } catch (e: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid input: ${e.message}"))
                        return@post
                    }

                    val safeModelOverride = request.model?.let { OpencodeModelRegistry.requireAllowedFreeModel(it) }

                    call.application.log.info("POST chat/query started for user: $userId, hasFileContext: ${request.fileContext != null}")

                    // CRITICAL FIX: If client specifically requests an OpenCode free model,
                    // force the provider to OPENCODE, overriding whatever the default/fallback is.
                    val providerParam = normalizeProviderSelection(request.provider)
                    if (safeModelOverride != null && safeModelOverride.startsWith("opencode/")) {
                        call.application.log.info("Client requested OpenCode model ($safeModelOverride). Using OPENCODE provider.")
                    }

                    // Create provider for this request
                    val streamProvider = LlmProviderFactory.create(httpClient)

                    val streamSummarizer = ConversationSummarizer(streamProvider)

                    // Combine query with file context if present
                    val fullQuery =
                        if (!request.fileContext.isNullOrBlank()) {
                            val attachmentDesc = request.attachments?.joinToString(", ") { "${it.type}: ${it.name}" } ?: "file"
                            """
                        |User uploaded: $attachmentDesc
                        |
                        |Extracted content:
                        |${request.fileContext}
                        |
                        |User's question: ${request.query}
                            """.trimMargin()
                        } else {
                            request.query
                        }

                    // Handle Session Persistence
                    val opencodeSessionId: String?
                    val history =
                        if (chatRepository != null) {
                            if (sessionId.isNullOrBlank()) {
                                sessionId = chatRepository.createSession(userId, "New Chat")
                            }

                            // Save User Message
                            if (request.query.isNotBlank()) {
                                chatRepository.saveMessage(userId, sessionId!!, LlmMessage.Role.USER.name, fullQuery)
                            }

                            opencodeSessionId = chatRepository.getSession(userId, sessionId!!)?.opencodeSessionId
                            chatRepository.getHistory(userId, sessionId!!)
                        } else {
                            opencodeSessionId = null
                            emptyList()
                        }

                    val agent =
                        ServerAgent(
                            llmProvider = streamProvider,
                            vectorStore = vectorStore,
                            summarizer = streamSummarizer,
                            noteRepository = noteRepository,
                            timerRepository = timerRepository,
                            calendarRepository = calendarRepository,
                            eventEmitter = { /* no-op */ },
                            userId = userId,
                            noteService = noteService,
                            capabilities =
                                com.example.smarty.server.agent.DeviceRegistry
                                    .getCapabilities(userId),
                        )

                    // Register active session to prevent digest scheduler interference
                    val activeSessionId = sessionId ?: UUID.randomUUID().toString()
                    com.example.smarty.server.agent.ActiveSessionManager
                        .startSession(userId, activeSessionId, "chat_query")

                    try {
                        val assistantResponse =
                            agent.run(
                                query = fullQuery,
                                sessionId = activeSessionId,
                                history = history,
                                modelOverride = safeModelOverride,
                                clientTimezone = request.timezone,
                                clientTimeMillis = request.clientTime,
                                personality = request.personality,
                                opencodeSessionId = opencodeSessionId,
                                onOpencodeSessionCreated = { newOpencodeSessionId ->
                                    if (chatRepository != null) {
                                        try {
                                            chatRepository.updateOpencodeSessionId(userId, activeSessionId, newOpencodeSessionId)
                                        } catch (e: Exception) {
                                            call.application.log.error("Failed to update opencodeSessionId", e)
                                        }
                                    }
                                },
                                variantOverride = request.variant,
                            )

                        // Save response
                        if (chatRepository != null && assistantResponse.isNotEmpty()) {
                            chatRepository.saveMessage(
                                userId = userId,
                                sessionId = sessionId!!,
                                role = LlmMessage.Role.ASSISTANT.name,
                                content = assistantResponse,
                            )
                            call.application.log.info("Saved assistant response: ${assistantResponse.length} chars")
                        }

                        // Return response
                        call.respond(
                            HttpStatusCode.OK,
                            mapOf(
                                "sessionId" to sessionId,
                                "response" to assistantResponse,
                            ),
                        )
                    } catch (e: Exception) {
                        call.application.log.error("POST chat/query OpenCode agent execution failed", e)
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            mapOf("error" to "An internal error occurred."),
                        )
                    } finally {
                        com.example.smarty.server.agent.ActiveSessionManager
                            .endSession(userId, activeSessionId)
                    }
                } catch (e: Exception) {
                    call.application.log.error("POST chat/query failed", e)
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "An internal error occurred."))
                }
            }

            // === Permission Engine: Approval callback ===
            post("/api/v1/chat/events/approval") {
                val user = call.firebaseUser() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                try {
                    val bodyText = call.receiveText()
                    val bodyJson = Json.parseToJsonElement(bodyText).jsonObject
                    val toolId =
                        bodyJson["toolId"]?.jsonPrimitive?.content
                            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing toolId"))
                    val approved = bodyJson["approved"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                    val feedback = bodyJson["feedback"]?.jsonPrimitive?.content

                    call.application.log.info("[Approval] Received: toolId=$toolId, approved=$approved, caller=${user.userId}")
                    val resolved =
                        com.example.smarty.server.agent.ApprovalRegistry.resolveApproval(
                            toolCallId = toolId,
                            approved = approved,
                            feedback = feedback,
                            callerUserId = user.userId,
                        )
                    if (resolved) {
                        call.application.log.info("[Approval] Resolved: toolId=$toolId, approved=$approved")
                        call.respond(HttpStatusCode.OK, mapOf("status" to "resumed", "toolId" to toolId))
                    } else {
                        call.application.log.warn("[Approval] Not found: toolId=$toolId")
                        call.respond(HttpStatusCode.NotFound, mapOf("status" to "not_found"))
                    }
                } catch (e: Exception) {
                    call.application.log.error("[Approval] Resolve failed", e)
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "An internal error occurred."))
                }
            }
        }

        /**
         * Test endpoint to verify AgentEvent serialization.
         * Returns all event types as JSON array.
         */
        get("/chat/events/test") {
            val testEvents =
                listOf(
                    AgentEvent.TextDelta(
                        eventId = "evt_123",
                        timestamp = System.currentTimeMillis(),
                        text = "Hello",
                    ),
                    AgentEvent.ToolStart(
                        eventId = "evt_124",
                        timestamp = System.currentTimeMillis(),
                        toolId = "tool-1",
                        name = "search_web",
                    ),
                    AgentEvent.Done(
                        eventId = "evt_125",
                        timestamp = System.currentTimeMillis(),
                    ),
                )

            val json = Json { prettyPrint = true }
            call.respondText(
                json.encodeToString(testEvents),
                ContentType.Application.Json,
            )
        }

        // =============================================================================
        // NOTE RELATIONSHIP ENDPOINTS (v4.2.0 - Junction Table Support)
        // =============================================================================

        /**
         * POST /chat/messages/{messageId}/notes/{noteId}
         * Link a note to a chat message.
         */
        post("/chat/messages/{messageId}/notes/{noteId}") {
            val user = call.firebaseUser() ?: return@post call.respond(HttpStatusCode.Unauthorized, "User not authenticated")
            val userId = user.userId
            val messageId = call.parameters["messageId"] ?: return@post call.respond(HttpStatusCode.BadRequest, "messageId required")
            val noteId = call.parameters["noteId"] ?: return@post call.respond(HttpStatusCode.BadRequest, "noteId required")

            try {
                chatRepository?.linkNoteToMessage(userId, messageId, noteId)
                call.respond(
                    HttpStatusCode.OK,
                    mapOf(
                        "success" to true,
                        "messageId" to messageId,
                        "noteId" to noteId,
                    ),
                )
            } catch (e: IllegalAccessException) {
                call.respond(HttpStatusCode.Forbidden, e.message ?: "Access denied")
            } catch (e: Exception) {
                call.application.log.error("Failed to link note to message", e)
                call.respond(HttpStatusCode.InternalServerError, "Failed to link note")
            }
        }

        /**
         * DELETE /chat/messages/{messageId}/notes/{noteId}
         * Unlink a note from a chat message.
         */
        delete("/chat/messages/{messageId}/notes/{noteId}") {
            val user = call.firebaseUser() ?: return@delete call.respond(HttpStatusCode.Unauthorized, "User not authenticated")
            val userId = user.userId
            val messageId = call.parameters["messageId"] ?: return@delete call.respond(HttpStatusCode.BadRequest, "messageId required")
            val noteId = call.parameters["noteId"] ?: return@delete call.respond(HttpStatusCode.BadRequest, "noteId required")

            try {
                val success = chatRepository?.unlinkNoteFromMessage(userId, messageId, noteId) ?: false
                call.respond(
                    HttpStatusCode.OK,
                    mapOf(
                        "success" to success,
                        "messageId" to messageId,
                        "noteId" to noteId,
                    ),
                )
            } catch (e: IllegalAccessException) {
                call.respond(HttpStatusCode.Forbidden, e.message ?: "Access denied")
            } catch (e: Exception) {
                call.application.log.error("Failed to unlink note from message", e)
                call.respond(HttpStatusCode.InternalServerError, "Failed to unlink note")
            }
        }

        /**
         * GET /chat/messages/{messageId}/notes
         * Get all notes linked to a chat message.
         */
        get("/chat/messages/{messageId}/notes") {
            val user = call.firebaseUser() ?: return@get call.respond(HttpStatusCode.Unauthorized, "User not authenticated")
            val userId = user.userId
            val messageId = call.parameters["messageId"] ?: return@get call.respond(HttpStatusCode.BadRequest, "messageId required")

            try {
                val linkedNoteIds = chatRepository?.getLinkedNotes(userId, messageId) ?: emptyList()
                call.respond(
                    HttpStatusCode.OK,
                    mapOf(
                        "messageId" to messageId,
                        "linkedNoteIds" to linkedNoteIds,
                        "count" to linkedNoteIds.size,
                    ),
                )
            } catch (e: IllegalAccessException) {
                call.respond(HttpStatusCode.Forbidden, e.message ?: "Access denied")
            } catch (e: Exception) {
                call.application.log.error("Failed to get linked notes", e)
                call.respond(HttpStatusCode.InternalServerError, "Failed to get linked notes")
            }
        }

        /**
         * DELETE /chat/messages/{messageId}/and-after
         * Delete a message and all messages after it in the same session.
         * Used for Edit & Resend feature.
         */
        delete("/chat/messages/{messageId}/and-after") {
            val user = call.firebaseUser() ?: return@delete call.respond(HttpStatusCode.Unauthorized, "User not authenticated")
            val userId = user.userId
            val messageId = call.parameters["messageId"] ?: return@delete call.respond(HttpStatusCode.BadRequest, "messageId required")

            try {
                val deletedCount = chatRepository?.deleteMessageAndAfter(userId, messageId) ?: 0
                call.respond(
                    HttpStatusCode.OK,
                    mapOf(
                        "success" to true,
                        "deletedCount" to deletedCount,
                        "fromMessageId" to messageId,
                    ),
                )
            } catch (e: IllegalAccessException) {
                call.respond(HttpStatusCode.Forbidden, e.message ?: "Access denied")
            } catch (e: Exception) {
                call.application.log.error("Failed to delete messages", e)
                call.respond(HttpStatusCode.InternalServerError, "Failed to delete messages")
            }
        }

        /**
         * GET /chat/sessions/{sessionId}/summary
         * Get a summary of the conversation in a session.
         */
        get("/chat/sessions/{sessionId}/summary") {
            val user = call.firebaseUser() ?: return@get call.respond(HttpStatusCode.Unauthorized, "User not authenticated")
            val userId = user.userId
            val sessionId = call.parameters["sessionId"] ?: return@get call.respond(HttpStatusCode.BadRequest, "sessionId required")

            try {
                val messages = chatRepository?.getHistory(userId, sessionId)?.take(50) ?: emptyList()
                if (messages.isEmpty()) {
                    call.respond(HttpStatusCode.OK, mapOf("summary" to "No messages in this conversation yet."))
                    return@get
                }

                val userMessages = messages.filter { it.role == com.example.smarty.server.llm.LlmMessage.Role.USER }
                val assistantMessages = messages.filter { it.role == com.example.smarty.server.llm.LlmMessage.Role.ASSISTANT }

                val summary = "Conversation with ${userMessages.size} messages from you and ${assistantMessages.size} responses."

                call.respond(HttpStatusCode.OK, mapOf("summary" to summary))
            } catch (e: Exception) {
                call.application.log.error("Failed to generate summary", e)
                call.respond(HttpStatusCode.InternalServerError, "Failed to generate summary")
            }
        }

        /**
         * DEBUG endpoint - List all chat sessions for current user
         */
        get("/chat/debug/sessions") {
            val user = call.firebaseUser() ?: return@get call.respond(HttpStatusCode.Unauthorized, "User not authenticated")
            val userId = user.userId

            try {
                if (chatRepository == null) {
                    call.respond(HttpStatusCode.OK, mapOf("error" to "chatRepository is null", "sessions" to emptyList<Any>()))
                    return@get
                }
                val sessions = chatRepository.listAllSessions(userId, limit = 100)
                call.respond(
                    HttpStatusCode.OK,
                    mapOf(
                        "userId" to userId,
                        "sessionCount" to sessions.size,
                        "sessions" to
                            sessions.map {
                                mapOf(
                                    "id" to it.id.toString(),
                                    "title" to it.title,
                                    "messageCount" to it.messageCount,
                                    "createdAt" to it.createdAt.toString(),
                                    "updatedAt" to it.updatedAt.toString(),
                                )
                            },
                    ),
                )
            } catch (e: Exception) {
                call.application.log.error("Debug sessions error", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "An internal error occurred."))
            }
        }

        /**
         * INTERRUPT endpoint - Cancel ongoing agent execution for a session
         * Users can use this to stop deep research or long-running agent tasks
         */
        post("/chat/interrupt") {
            val user = call.firebaseUser() ?: return@post call.respond(HttpStatusCode.Unauthorized, "User not authenticated")
            val userId = user.userId

            val request = call.receive<InterruptRequest>()
            val sessionId = request.sessionId

            try {
                // Cancel the coroutine to safely stop the agent without destroying session data
                if (sessionId.isNotEmpty()) {
                    val cancelled =
                        com.example.smarty.server.agent.AgentRunManager
                            .cancelRun(sessionId)
                    call.respond(
                        HttpStatusCode.OK,
                        InterruptResponse(
                            success = true,
                            message = if (cancelled) "Agent run cancelled for session $sessionId" else "No active run found for session $sessionId",
                        ),
                    )
                } else {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        InterruptResponse(
                            success = false,
                            message = "Invalid session ID",
                        ),
                    )
                }
            } catch (e: Exception) {
                call.application.log.error("Interrupt error", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    InterruptResponse(
                        success = false,
                        message = "An internal error occurred.",
                    ),
                )
            }
        }

        // ────────────────────────────────────────────────────────────────────────────
        // STACKS ROUTES (stacks + note_stacks junction)
        // ────────────────────────────────────────────────────────────────────────────

        /**
         * POST /stacks
         * Create a new stack.
         */
        post("/stacks") {
            val user = call.firebaseUser() ?: return@post call.respond(HttpStatusCode.Unauthorized, "User not authenticated")
            val userId = user.userId

            try {
                val stack = call.receive<com.example.smarty.server.data.Stack>()
                val id =
                    stackRepository?.createStack(stack.copy(userId = userId)) ?: throw IllegalStateException("Stack repository unavailable")
                call.respond(HttpStatusCode.OK, mapOf("id" to id, "success" to true))
            } catch (e: Exception) {
                call.application.log.error("Failed to create stack", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "An internal error occurred."))
            }
        }

        /**
         * GET /stacks
         * List all stacks for current user.
         */
        get("/stacks") {
            val user = call.firebaseUser() ?: return@get call.respond(HttpStatusCode.Unauthorized, "User not authenticated")
            val userId = user.userId

            try {
                val stacks = stackRepository?.getStacksForUser(userId) ?: emptyList()
                call.respond(HttpStatusCode.OK, mapOf("stacks" to stacks, "count" to stacks.size))
            } catch (e: Exception) {
                call.application.log.error("Failed to list stacks", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "An internal error occurred."))
            }
        }

        /**
         * GET /stacks/{stackId}
         * Get a single stack by ID.
         */
        get("/stacks/{stackId}") {
            val user = call.firebaseUser() ?: return@get call.respond(HttpStatusCode.Unauthorized, "User not authenticated")
            val userId = user.userId
            val stackId = call.parameters["stackId"] ?: return@get call.respond(HttpStatusCode.BadRequest, "stackId required")

            try {
                val stack = stackRepository?.getStackById(stackId)
                if (stack == null || stack.userId != userId) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Stack not found"))
                } else {
                    call.respond(HttpStatusCode.OK, stack)
                }
            } catch (e: Exception) {
                call.application.log.error("Failed to get stack", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "An internal error occurred."))
            }
        }

        /**
         * PUT /stacks/{stackId}
         * Update a stack.
         */
        put("/stacks/{stackId}") {
            val user = call.firebaseUser() ?: return@put call.respond(HttpStatusCode.Unauthorized, "User not authenticated")
            val userId = user.userId
            val stackId = call.parameters["stackId"] ?: return@put call.respond(HttpStatusCode.BadRequest, "stackId required")

            try {
                val stack = call.receive<com.example.smarty.server.data.Stack>()
                val existing = stackRepository?.getStackById(stackId)
                if (existing == null || existing.userId != userId) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Stack not found"))
                    return@put
                }
                val updated = stackRepository.updateStack(stack.copy(id = stackId, userId = userId))
                call.respond(HttpStatusCode.OK, mapOf("success" to updated))
            } catch (e: Exception) {
                call.application.log.error("Failed to update stack", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "An internal error occurred."))
            }
        }

        /**
         * DELETE /stacks/{stackId}
         * Delete a stack.
         */
        delete("/stacks/{stackId}") {
            val user = call.firebaseUser() ?: return@delete call.respond(HttpStatusCode.Unauthorized, "User not authenticated")
            val userId = user.userId
            val stackId = call.parameters["stackId"] ?: return@delete call.respond(HttpStatusCode.BadRequest, "stackId required")

            try {
                val existing = stackRepository?.getStackById(stackId)
                if (existing == null || existing.userId != userId) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Stack not found"))
                    return@delete
                }
                val deleted = stackRepository.deleteStack(stackId)
                call.respond(HttpStatusCode.OK, mapOf("success" to deleted))
            } catch (e: Exception) {
                call.application.log.error("Failed to delete stack", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "An internal error occurred."))
            }
        }

        /**
         * POST /stacks/{stackId}/notes/{noteId}
         * Add a note to a stack.
         */
        post("/stacks/{stackId}/notes/{noteId}") {
            val user = call.firebaseUser() ?: return@post call.respond(HttpStatusCode.Unauthorized, "User not authenticated")
            val userId = user.userId
            val stackId = call.parameters["stackId"] ?: return@post call.respond(HttpStatusCode.BadRequest, "stackId required")
            val noteId = call.parameters["noteId"] ?: return@post call.respond(HttpStatusCode.BadRequest, "noteId required")

            try {
                val stack = stackRepository?.getStackById(stackId)
                if (stack == null || stack.userId != userId) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Stack not found"))
                    return@post
                }
                val added = stackRepository.addNoteToStack(noteId, stackId)
                call.respond(HttpStatusCode.OK, mapOf("success" to added))
            } catch (e: Exception) {
                call.application.log.error("Failed to add note to stack", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "An internal error occurred."))
            }
        }

        /**
         * DELETE /stacks/{stackId}/notes/{noteId}
         * Remove a note from a stack.
         */
        delete("/stacks/{stackId}/notes/{noteId}") {
            val user = call.firebaseUser() ?: return@delete call.respond(HttpStatusCode.Unauthorized, "User not authenticated")
            val userId = user.userId
            val stackId = call.parameters["stackId"] ?: return@delete call.respond(HttpStatusCode.BadRequest, "stackId required")
            val noteId = call.parameters["noteId"] ?: return@delete call.respond(HttpStatusCode.BadRequest, "noteId required")

            try {
                val stack = stackRepository?.getStackById(stackId)
                if (stack == null || stack.userId != userId) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Stack not found"))
                    return@delete
                }
                val removed = stackRepository.removeNoteFromStack(noteId, stackId)
                call.respond(HttpStatusCode.OK, mapOf("success" to removed))
            } catch (e: Exception) {
                call.application.log.error("Failed to remove note from stack", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "An internal error occurred."))
            }
        }

        /**
         * GET /stacks/{stackId}/notes
         * List all notes in a stack.
         */
        get("/stacks/{stackId}/notes") {
            val user = call.firebaseUser() ?: return@get call.respond(HttpStatusCode.Unauthorized, "User not authenticated")
            val userId = user.userId
            val stackId = call.parameters["stackId"] ?: return@get call.respond(HttpStatusCode.BadRequest, "stackId required")

            try {
                val stack = stackRepository?.getStackById(stackId)
                if (stack == null || stack.userId != userId) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Stack not found"))
                    return@get
                }
                val noteIds = stackRepository.getNotesForStack(stackId)
                call.respond(HttpStatusCode.OK, mapOf("stackId" to stackId, "noteIds" to noteIds, "count" to noteIds.size))
            } catch (e: Exception) {
                call.application.log.error("Failed to list notes in stack", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "An internal error occurred."))
            }
        }
    } // End of authenticate("firebase") block

    // TEST ENDPOINT to trigger ask_user manually
    routing {
        post("/chat/test-ask-user") {
            call.respond(mapOf("status" to "noop", "message" to "ActiveEventBridge removed; use WebSocket /chat/ws for approval flows"))
        }
    }
}

@Serializable
data class InterruptRequest(
    val sessionId: String,
)

@Serializable
data class InterruptResponse(
    val success: Boolean,
    val message: String,
)
