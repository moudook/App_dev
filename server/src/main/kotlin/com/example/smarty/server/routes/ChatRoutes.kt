package com.example.smarty.server.routes

import io.ktor.server.request.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.sse.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.example.smarty.protocol.AgentEvent
import com.example.smarty.protocol.ClientEvent
import com.example.smarty.core.domain.model.CalendarEvent
import com.example.smarty.core.domain.model.RecallResult
import com.example.smarty.features.chat.agent.models.ScreenContext
import java.util.UUID

import com.example.smarty.protocol.AgentCommand
import com.example.smarty.server.agent.ServerAgent
import com.example.smarty.server.data.PostgresVectorStore
import com.example.smarty.server.data.ChatRepository
import com.example.smarty.server.data.DatabaseFactory
import com.example.smarty.server.data.NoteRepository
import com.example.smarty.server.data.TimerRepository
import com.example.smarty.server.data.CalendarRepository
import com.example.smarty.server.data.ChatMessageNotesRepository
import com.example.smarty.server.data.CalendarEventNotesRepository
import com.example.smarty.server.data.ConversationSummarizer
import com.example.smarty.server.llm.LlmMessage
import com.example.smarty.server.llm.LlmProviderFactory
import com.example.smarty.server.llm.ProviderRouter
import com.example.smarty.server.llm.RoutingStrategy
import com.example.smarty.server.plugins.FirebaseUserPrincipal
import com.example.smarty.server.plugins.firebaseUser
import com.example.smarty.server.tools.TavilySearchTool
import io.ktor.client.HttpClient
import com.example.smarty.server.agent.ThinkingStorageManagerSingleton
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json

/**
 * Configure chat streaming routes.
 *
 * Endpoints:
 * - GET /chat/stream?query=...&sessionId=... - SSE stream of agent events (authenticated)
 * - POST /chat/stream - SSE stream with body payload for large context (authenticated)
 * - POST /chat/events - Receive events from client (authenticated)
 * - GET /chat/events/test - Test endpoint for AgentEvent serialization (public)
 */

/**
 * Request body for POST /chat/stream endpoint.
 * Allows sending larger payloads including file context.
 */
@Serializable
data class ChatRequest(
    val query: String,
    val sessionId: String? = null,
    val provider: String? = null,
    val providerUrl: String? = null,
    val model: String? = null,
    val token: String? = null,
    val fileContext: String? = null,  // Extracted text from uploaded files
    val attachments: List<AttachmentInfo>? = null,  // Metadata about attachments
    val timezone: String? = null,  // User's timezone (e.g., "America/New_York")
    val clientTime: Long? = null,   // User's current time in epoch millis
    val personality: String? = null  // AI personality: PROFESSIONAL, CASUAL, CONCISE, DETAILED
)

@Serializable
data class AttachmentInfo(
    val type: String,  // "image", "pdf", "document"
    val name: String,
    val mimeType: String? = null
)

@Serializable
data class BriefingRequest(
    val prompt: String,
    val token: String? = null
)

@Serializable
data class BriefingResponse(
    val briefing: String,
    val success: Boolean = true
)

fun Application.configureChatRoutes() {
    // JSON encoder for events
    val json = Json {
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
    val tavilyTool = TavilySearchTool()
    val providerRouter = ProviderRouter(httpClient)

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

                    call.application.log.info("Generating daily briefing for user: $userId")

                    // Use ProviderRouter to select the SMARTEST provider
                    val briefingProvider = providerRouter.selectProvider(RoutingStrategy.SMARTEST, request.token)
                    val briefingSummarizer = ConversationSummarizer(briefingProvider)

                    // Create agent for single run
                    val agent = ServerAgent(
                        llmProvider = briefingProvider,
                        tavilyTool = tavilyTool,
                        vectorStore = vectorStore,
                        summarizer = briefingSummarizer,
                        noteRepository = noteRepository,
                        timerRepository = timerRepository,
                        calendarRepository = calendarRepository,
                        eventEmitter = { /* No streaming for briefing yet */ },
                        userId = userId
                    )

                    // Run the agent (no modelOverride - provider already selected)
                    val briefing = agent.run(
                        query = request.prompt,
                        history = emptyList()
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

                try {
                    val event = call.receive<ClientEvent>()
                    call.application.log.info("Received client event: ${event::class.simpleName} for session: $sessionId (user: ${user.userId})")

                    if (chatRepository != null) {
                        when (event) {
                            is ClientEvent.ToolResult -> {
                                val statusPrefix = if (event.isError) "Error" else "Success"
                                val content = "Tool Output [${event.commandId}] ($statusPrefix): ${event.result}"
                                chatRepository.saveMessage(user.userId, sessionId, LlmMessage.Role.TOOL.name, content)
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
                                val content = "Knowledge Recall Results:\n" + event.results.joinToString("\n") { "- ${it.title}: ${it.content} (Score: ${it.score})" }
                                chatRepository.saveMessage(user.userId, sessionId, LlmMessage.Role.TOOL.name, content)
                            }
                            is ClientEvent.CalendarEventsResponse -> {
                                val content = "Calendar Events:\n" + event.events.joinToString("\n") { "- ${it.title} (${java.time.Instant.ofEpochMilli(it.startTime)})" }
                                chatRepository.saveMessage(user.userId, sessionId, LlmMessage.Role.TOOL.name, content)
                            }
                            is ClientEvent.ScreenContextResponse -> {
                                val ctx = event.context
                                val content = if (ctx != null) {
                                    "Current Screen Context:\nApp: ${ctx.referringApp}\nSelected Text: ${ctx.selectedText}\nData: ${ctx.contextData}"
                                } else {
                                    "Screen Context: No data available"
                                }
                                chatRepository.saveMessage(user.userId, sessionId, LlmMessage.Role.TOOL.name, content)
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
             * SSE endpoint for agent event streaming.
             *
             * Executes the ServerAgent strategy and streams real-time events.
             *
             * Usage:
             * ```
             * curl -N -H "Accept: text/event-stream" -H "Authorization: Bearer <token>" "http://localhost:7860/chat/stream?query=hello&sessionId=UUID"
             * ```
             */
            sse("/chat/stream") {
                val user = call.firebaseUser()
                if (user == null) {
                    send(ServerSentEvent(
                        data = json.encodeToString(AgentEvent.Error(
                            eventId = UUID.randomUUID().toString(),
                            timestamp = System.currentTimeMillis(),
                            message = "Authentication required",
                            code = "UNAUTHORIZED"
                        )),
                        event = "error"
                    ))
                    return@sse
                }

                val userId = user.userId
                val query = call.request.queryParameters["query"] ?: "default query"
                var sessionId = call.request.queryParameters["sessionId"]
                val providerParam = call.request.queryParameters["provider"]
                val providerUrlParam = call.request.queryParameters["providerUrl"]
                val modelParam = call.request.queryParameters["model"]
                val tokenParam = call.request.queryParameters["token"] ?: call.request.queryParameters["apiKey"]
                val timezoneParam = call.request.queryParameters["timezone"]
                val clientTimeParam = call.request.queryParameters["clientTime"]?.toLongOrNull()
                val personalityParam = call.request.queryParameters["personality"]

                // Log the incoming request
                call.application.log.info("SSE stream started for query: $query (Session: $sessionId, User: $userId, Provider: $providerParam, Model: $modelParam, URL: $providerUrlParam)")

                // Create provider and summarizer for this specific request
                val streamProvider = when (providerParam?.uppercase()) {
                    "CHEAPEST" -> providerRouter.selectProvider(RoutingStrategy.CHEAPEST, tokenParam)
                    "FASTEST" -> providerRouter.selectProvider(RoutingStrategy.FASTEST, tokenParam)
                    "SMARTEST" -> providerRouter.selectProvider(RoutingStrategy.SMARTEST, tokenParam)
                    "BALANCED", "AUTO" -> providerRouter.selectProvider(RoutingStrategy.BALANCED, tokenParam)
                    else -> LlmProviderFactory.create(httpClient, providerParam, providerUrlParam, tokenParam)
                }

                val streamSummarizer = ConversationSummarizer(streamProvider)

                // Handle Session Persistence (non-fatal: DB errors won't kill chat)
                val history = if (chatRepository != null) {
                    try {
                        if (sessionId.isNullOrBlank()) {
                            // No session ID provided - create new session
                            sessionId = chatRepository.createSession(userId, "New Chat")
                            call.application.log.info("Created new session: $sessionId for user: $userId")
                        } else {
                            // Session ID provided - verify it exists and belongs to user
                            val existingSession = chatRepository.getSession(userId, sessionId)
                            if (existingSession == null) {
                                // Session doesn't exist for this user - create it with the provided ID
                                val created = chatRepository.createSessionWithId(userId, sessionId, "Continued Chat")
                                if (created) {
                                    call.application.log.info("Created session with client ID: $sessionId for user: $userId")
                                } else {
                                    // Session exists but belongs to another user - create new session
                                    sessionId = chatRepository.createSession(userId, "New Chat")
                                    call.application.log.warn("Session ID conflict - created new session: $sessionId for user: $userId")
                                }
                            }
                        }

                        // Save User Message (only if not a continuation query)
                        if (query.isNotBlank()) {
                            chatRepository.saveMessage(userId, sessionId!!, LlmMessage.Role.USER.name, query)
                        }

                        // Load History
                        chatRepository.getHistory(userId, sessionId!!)
                    } catch (e: Exception) {
                        call.application.log.error("DB persistence failed (non-fatal), continuing without history", e)
                        emptyList()
                    }
                } else {
                    emptyList()
                }

                // Register active session BEFORE creating agent (needed for progressive thinking save)
                val activeSessionId = sessionId ?: UUID.randomUUID().toString()
                com.example.smarty.server.agent.ActiveSessionManager.startSession(userId, activeSessionId, "chat")

                // Collect citations during stream
                val collectedCitations = mutableListOf<com.example.smarty.protocol.ProtocolWebCitation>()

                // Create agent instance for this request with userId for multi-tenant isolation
                val agent = ServerAgent(
                    llmProvider = streamProvider,
                    tavilyTool = tavilyTool,
                    vectorStore = vectorStore,
                    summarizer = streamSummarizer,
                    noteRepository = noteRepository,
                    timerRepository = timerRepository,
                    calendarRepository = calendarRepository,
                    eventEmitter = { event ->
                        try {
                            // Collect citations from NotifyCitations command
                            if (event is AgentEvent.Command) {
                                val command = event.command
                                if (command is com.example.smarty.protocol.AgentCommand.NotifyCitations) {
                                    collectedCitations.addAll(command.citations)
                                }
                            }

                            // PROGRESSIVE SAVE: Save thinking AND tool calls to database during streaming
                            // This ensures thinking and tools are persisted even if stream fails
                            if (event is AgentEvent.Processing || event is AgentEvent.ToolCall) {
                                val currentThinking = ThinkingStorageManagerSingleton.instance
                                    .getCurrentThinking(activeSessionId)
                                
                                // Extract tool calls from thinking trace for progressive save
                                val currentToolCalls = if (currentThinking.contains("SMARTY_TRACE_V2")) {
                                    // Parse tool calls from thinking trace
                                    currentThinking.substringAfter("SMARTY_TRACE_V2:")
                                } else {
                                    null
                                }
                                
                                if (currentThinking.isNotBlank()) {
                                    chatRepository?.updateMessageThinking(
                                        userId = userId,
                                        sessionId = activeSessionId,
                                        thinking = currentThinking,
                                        toolCalls = currentToolCalls
                                    )
                                }
                            }

                            val eventType = when(event) {
                                is AgentEvent.Processing -> "processing"
                                is AgentEvent.ToolCall -> "tool_call"
                                is AgentEvent.Command -> "command"
                                is AgentEvent.Result -> "result"
                                is AgentEvent.Error -> "error"
                                is AgentEvent.StateSync -> "state_sync"
                                is AgentEvent.ToolBlocked -> "tool_blocked"
                                is AgentEvent.Question -> "question"
                            }
                            call.application.log.info("Sending SSE event: $eventType (ID: ${event.eventId})")
                            send(ServerSentEvent(
                                data = json.encodeToString(event),
                                event = eventType
                            ))
                        } catch (e: Exception) {
                            call.application.log.warn("Failed to send SSE event (client disconnected): ${e.message}")
                        }
                    },
                    userId = userId
                )

                try {
                    // Run the agent strategy with history, model override, and time context
                    // CRITICAL: Pass sessionId to preserve chat history continuity
                    val assistantResponse = agent.run(
                        query = query,
                        sessionId = activeSessionId,
                        history = history,
                        modelOverride = modelParam,
                        clientTimezone = timezoneParam,
                        clientTimeMillis = clientTimeParam,
                        personality = personalityParam
                    )

                    // Save Smarty Response if persistence is enabled
                    if (chatRepository != null && assistantResponse.isNotEmpty()) {
                        try {
                            // Retrieve the rich SMARTY_TRACE_V2 thinking trace that was built during
                            // streaming. This is the correct source for the thinking field — the old
                            // <think>-tag extraction no longer works with the new trace format.
                            val thinkingTrace = ThinkingStorageManagerSingleton.instance
                                .finalizeAndGetThinking(activeSessionId)
                                .ifBlank { null }

                            // Convert citations to JSON
                            val citationsJson = if (collectedCitations.isNotEmpty()) {
                                json.encodeToString(collectedCitations)
                            } else {
                                "[]"
                            }

                            chatRepository.saveMessage(
                                userId = userId,
                                sessionId = sessionId!!,
                                role = LlmMessage.Role.ASSISTANT.name,
                                content = assistantResponse,
                                thinking = thinkingTrace,
                                toolCalls = citationsJson
                            )
                            call.application.log.info(
                                "Saved assistant response: thinking=${thinkingTrace?.length ?: 0} chars, citations=${collectedCitations.size}"
                            )
                        } catch (e: Exception) {
                            call.application.log.error("Failed to save assistant response (non-fatal)", e)
                        }
                    }
                } catch (e: Exception) {
                    call.application.log.error("Agent execution failed", e)
                    try {
                        send(ServerSentEvent(
                            data = json.encodeToString(AgentEvent.Error(
                                eventId = UUID.randomUUID().toString(),
                                timestamp = System.currentTimeMillis(),
                                message = "An internal error occurred: ${e.message?.take(100) ?: "Unknown error"}",
                                code = "INTERNAL_ERROR"
                            )),
                            event = "error"
                        ))
                    } catch (sendError: Exception) {
                        call.application.log.warn("Failed to send error SSE (client disconnected): ${sendError.message}")
                    }
                } finally {
                    // Always end the active session
                    com.example.smarty.server.agent.ActiveSessionManager.endSession(userId, activeSessionId)
                }

                call.application.log.info("SSE stream completed for query: $query (Session: $sessionId, User: $userId)")
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

                    call.application.log.info("POST chat/query started for user: $userId, hasFileContext: ${request.fileContext != null}")

                    // Create provider for this request
                    val streamProvider = when (request.provider?.uppercase()) {
                        "CHEAPEST" -> providerRouter.selectProvider(RoutingStrategy.CHEAPEST, request.token)
                        "FASTEST" -> providerRouter.selectProvider(RoutingStrategy.FASTEST, request.token)
                        "SMARTEST" -> providerRouter.selectProvider(RoutingStrategy.SMARTEST, request.token)
                        "BALANCED", "AUTO" -> providerRouter.selectProvider(RoutingStrategy.BALANCED, request.token)
                        else -> LlmProviderFactory.create(httpClient, request.provider, request.providerUrl, request.token)
                    }

                    val streamSummarizer = ConversationSummarizer(streamProvider)

                    // Combine query with file context if present
                    val fullQuery = if (!request.fileContext.isNullOrBlank()) {
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
                    val history = if (chatRepository != null) {
                        if (sessionId.isNullOrBlank()) {
                            sessionId = chatRepository.createSession(userId, "New Chat")
                        }

                        // Save User Message
                        if (request.query.isNotBlank()) {
                            chatRepository.saveMessage(userId, sessionId!!, LlmMessage.Role.USER.name, fullQuery)
                        }

                        chatRepository.getHistory(userId, sessionId!!)
                    } else {
                        emptyList()
                    }

// Collect events for response and citations
                    val events = mutableListOf<AgentEvent>()
                    val collectedCitations = mutableListOf<com.example.smarty.protocol.ProtocolWebCitation>()

                    val agent = ServerAgent(
                        llmProvider = streamProvider,
                        tavilyTool = tavilyTool,
                        vectorStore = vectorStore,
                        summarizer = streamSummarizer,
                        noteRepository = noteRepository,
                        timerRepository = timerRepository,
                        calendarRepository = calendarRepository,
                        eventEmitter = { event ->
                            events.add(event)
                            // Collect citations
                            if (event is AgentEvent.Command) {
                                val command = event.command
                                if (command is com.example.smarty.protocol.AgentCommand.NotifyCitations) {
                                    collectedCitations.addAll(command.citations)
                                }
                            }
                        },
                        userId = userId
                    )

                    // Register active session to prevent digest scheduler interference
                    val activeSessionId = sessionId ?: UUID.randomUUID().toString()
                    com.example.smarty.server.agent.ActiveSessionManager.startSession(userId, activeSessionId, "chat_query")

                    try {
                    val assistantResponse = agent.run(
                        query = fullQuery,
                        sessionId = activeSessionId,
                        history = history,
                        modelOverride = request.model,
                        clientTimezone = request.timezone,
                        clientTimeMillis = request.clientTime,
                        personality = request.personality
                    )

                        // Save response with citations
                        if (chatRepository != null && assistantResponse.isNotEmpty()) {
                            // Retrieve the rich SMARTY_TRACE_V2 thinking trace persisted during
                            // streaming instead of regex-parsing deprecated <think> tags.
                            val thinkingTrace = ThinkingStorageManagerSingleton.instance
                                .finalizeAndGetThinking(activeSessionId)
                                .ifBlank { null }

                            // Convert citations to JSON
                            val citationsJson = if (collectedCitations.isNotEmpty()) {
                                json.encodeToString(collectedCitations)
                            } else {
                                "[]"
                            }

                            chatRepository.saveMessage(
                                userId = userId,
                                sessionId = sessionId!!,
                                role = LlmMessage.Role.ASSISTANT.name,
                                content = assistantResponse,
                                thinking = thinkingTrace,
                                toolCalls = citationsJson
                            )
                            call.application.log.info(
                                "Saved assistant response: thinking=${thinkingTrace?.length ?: 0} chars, citations=${collectedCitations.size}"
                            )
                        }

                        // Return all events
                        call.respond(HttpStatusCode.OK, mapOf(
                            "sessionId" to sessionId,
                            "response" to assistantResponse,
                            "events" to events.map { json.encodeToString(it) }
                        ))
                    } finally {
                        com.example.smarty.server.agent.ActiveSessionManager.endSession(userId, activeSessionId)
                    }

                } catch (e: Exception) {
                    call.application.log.error("POST chat/query failed", e)
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "An internal error occurred."))
                }
            }
        }

        /**
         * Test endpoint to verify AgentEvent serialization.
         * Returns all event types as JSON array.
         */
        get("/chat/events/test") {
            val testEvents = listOf(
                AgentEvent.Processing(
                    eventId = "test-processing",
                    timestamp = System.currentTimeMillis(),
                    content = "Test processing content"
                ),
                AgentEvent.Command(
                    eventId = "test-command",
                    timestamp = System.currentTimeMillis(),
                    command = AgentCommand.AddNote(
                        commandId = "cmd-123",
                        content = "Test Note Content",
                        category = "Test"
                    )
                ),
                AgentEvent.Result(
                    eventId = "test-result",
                    timestamp = System.currentTimeMillis(),
                    content = "Test result content",
                    isFinal = true
                )
            )

            val json = Json { prettyPrint = true }
            call.respondText(
                json.encodeToString(testEvents),
                ContentType.Application.Json
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
                call.respond(HttpStatusCode.OK, mapOf(
                    "success" to true,
                    "messageId" to messageId,
                    "noteId" to noteId
                ))
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
                call.respond(HttpStatusCode.OK, mapOf(
                    "success" to success,
                    "messageId" to messageId,
                    "noteId" to noteId
                ))
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
                call.respond(HttpStatusCode.OK, mapOf(
                    "messageId" to messageId,
                    "linkedNoteIds" to linkedNoteIds,
                    "count" to linkedNoteIds.size
                ))
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
                call.respond(HttpStatusCode.OK, mapOf(
                    "success" to true,
                    "deletedCount" to deletedCount,
                    "fromMessageId" to messageId
                ))
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
                val messages = chatRepository?.getHistory(userId, sessionId, limit = 50) ?: emptyList()
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
    }
}
