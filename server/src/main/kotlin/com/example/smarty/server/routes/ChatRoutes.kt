package com.example.smarty.server.routes

import io.ktor.server.request.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.sse.*
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.example.smarty.protocol.AgentEvent
import com.example.smarty.protocol.ClientEvent
import com.example.smarty.data.model.AIMemory
import com.example.smarty.data.model.CalendarEvent
import com.example.smarty.viewmodel.managers.RecallResult
import com.example.smarty.agent.models.ScreenContext
import java.util.UUID

import com.example.smarty.protocol.AgentCommand
import com.example.smarty.server.agent.ServerAgent
import com.example.smarty.server.data.EmbeddingClient
import com.example.smarty.server.data.SupabaseVectorStore
import com.example.smarty.server.data.ChatRepository
import com.example.smarty.server.data.DatabaseFactory
import com.example.smarty.server.data.ConversationSummarizer
import com.example.smarty.server.llm.LlmMessage
import com.example.smarty.server.llm.LlmProviderFactory
import com.example.smarty.server.tools.TavilySearchTool
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json

/**
 * Configure chat streaming routes.
 *
 * Endpoints:
 * - GET /chat/stream?query=...&sessionId=... - SSE stream of agent events
 */
fun Application.configureChatRoutes() {
    // JSON encoder for events
    val json = Json {
        encodeDefaults = true
        prettyPrint = false
        classDiscriminator = "type"
        ignoreUnknownKeys = true
    }

    // Initialize generic HTTP Client for LLM and Tools
    val httpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            })
        }
    }

    // Initialize dependencies (Manual DI for now)
    // In production, use Koin or Dagger
    val embeddingClient = EmbeddingClient()
    val vectorStore = SupabaseVectorStore() // Reads from ENV
    val tavilyTool = TavilySearchTool()     // Reads from ENV
    val llmProvider = LlmProviderFactory.create(httpClient)
    val summarizer = ConversationSummarizer(llmProvider)

    // Database and Repository
    val dataSource = DatabaseFactory.getDataSource()
    val chatRepository = dataSource?.let { ChatRepository(it) }

    routing {
        /**
         * Receive events from the client (e.g., tool results).
         * These events are persisted to provide context for the next agent turn.
         */
        post("/chat/events") {
            val sessionId = call.request.queryParameters["sessionId"]
            if (sessionId.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, "Missing sessionId")
                return@post
            }

            try {
                val event = call.receive<ClientEvent>()
                call.application.log.info("Received client event: ${event::class.simpleName} for session: $sessionId")

                if (chatRepository != null) {
                    when (event) {
                        is ClientEvent.ToolResult -> {
                            val statusPrefix = if (event.isError) "Error" else "Success"
                            val content = "Tool Output [${event.commandId}] ($statusPrefix): ${event.result}"
                            chatRepository.saveMessage(sessionId, LlmMessage.Role.TOOL.name, content)
                        }
                        is ClientEvent.ActiveNotesResponse -> {
                            val content = "Active Notes Context: ${event.notes.joinToString { it.title }}"
                            chatRepository.saveMessage(sessionId, LlmMessage.Role.TOOL.name, content)
                        }
                        is ClientEvent.SearchResultsResponse -> {
                            val content = "Search Results Context: ${event.results.joinToString { it.title }}"
                            chatRepository.saveMessage(sessionId, LlmMessage.Role.TOOL.name, content)
                        }
                        is ClientEvent.RecallResultsResponse -> {
                            val content = "Knowledge Recall Results:\n" + event.results.joinToString("\n") { "- ${it.title}: ${it.content} (Score: ${it.score})" }
                            chatRepository.saveMessage(sessionId, LlmMessage.Role.TOOL.name, content)
                        }
                        is ClientEvent.MemoriesResponse -> {
                            val content = "Retrieved Memories:\n" + event.memories.joinToString("\n") { "- [${it.type}] ${it.content}" }
                            chatRepository.saveMessage(sessionId, LlmMessage.Role.TOOL.name, content)
                        }
                        is ClientEvent.CalendarEventsResponse -> {
                            val content = "Calendar Events:\n" + event.events.joinToString("\n") { "- ${it.title} (${java.time.Instant.ofEpochMilli(it.startTime)})" }
                            chatRepository.saveMessage(sessionId, LlmMessage.Role.TOOL.name, content)
                        }
                        is ClientEvent.ScreenContextResponse -> {
                            val ctx = event.context
                            val content = if (ctx != null) {
                                "Current Screen Context:\nApp: ${ctx.referringApp}\nSelected Text: ${ctx.selectedText}\nData: ${ctx.contextData}"
                            } else {
                                "Screen Context: No data available"
                            }
                            chatRepository.saveMessage(sessionId, LlmMessage.Role.TOOL.name, content)
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
                call.respond(HttpStatusCode.InternalServerError, "Error processing event: ${e.message}")
            }
        }

        /**
         * SSE endpoint for agent event streaming.
         *
         * Executes the ServerAgent strategy and streams real-time events.
         *
         * Usage:
         * ```
         * curl -N -H "Accept: text/event-stream" "http://localhost:7860/chat/stream?query=hello&sessionId=UUID"
         * ```
         */
        sse("/chat/stream") {
            val query = call.request.queryParameters["query"] ?: "default query"
            var sessionId = call.request.queryParameters["sessionId"]
            val providerParam = call.request.queryParameters["provider"]
            val providerUrlParam = call.request.queryParameters["providerUrl"]
            val modelParam = call.request.queryParameters["model"]
            val tokenParam = call.request.queryParameters["token"] ?: call.request.queryParameters["apiKey"]

            // Log the incoming request
            call.application.log.info("SSE stream started for query: $query (Session: $sessionId, Provider: $providerParam, Model: $modelParam, URL: $providerUrlParam)")

            // Create provider and summarizer for this specific request
            val streamProvider = LlmProviderFactory.create(httpClient, providerParam, providerUrlParam, tokenParam)
            val streamSummarizer = ConversationSummarizer(streamProvider)

            // Handle Session Persistence
            val history = if (chatRepository != null) {
                if (sessionId.isNullOrBlank()) {
                    sessionId = chatRepository.createSession("New Chat")
                    call.application.log.info("Created new session: $sessionId")
                }

                // Save User Message (only if not a continuation query)
                if (query.isNotBlank()) {
                    chatRepository.saveMessage(sessionId!!, LlmMessage.Role.USER.name, query)
                }

                // Load History
                chatRepository.getHistory(sessionId!!)
            } else {
                emptyList()
            }

            // Create agent instance for this request
            val agent = ServerAgent(
                llmProvider = streamProvider,
                tavilyTool = tavilyTool,
                vectorStore = vectorStore,
                embeddingClient = embeddingClient,
                summarizer = streamSummarizer,
                eventEmitter = { event ->
                    send(ServerSentEvent(
                        data = json.encodeToString(event),
                        event = when(event) {
                            is AgentEvent.Thinking -> "thinking"
                            is AgentEvent.ToolCall -> "tool_call"
                            is AgentEvent.Command -> "command"
                            is AgentEvent.Result -> "result"
                            is AgentEvent.Error -> "error"
                        }
                    ))
                }
            )

            try {
                // Run the agent strategy with history and model override
                val assistantResponse = agent.run(query, history, modelParam)

                // Save Assistant Response if persistence is enabled
                if (chatRepository != null && sessionId != null && assistantResponse.isNotEmpty()) {
                    chatRepository.saveMessage(sessionId!!, LlmMessage.Role.ASSISTANT.name, assistantResponse)
                }
            } catch (e: Exception) {
                call.application.log.error("Agent execution failed", e)
                send(ServerSentEvent(
                    data = json.encodeToString(AgentEvent.Error(
                        eventId = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        message = "Internal Server Error: ${e.message}",
                        code = "INTERNAL_ERROR"
                    )),
                    event = "error"
                ))
            }

            call.application.log.info("SSE stream completed for query: $query (Session: $sessionId)")
        }

        /**
         * Test endpoint to verify AgentEvent serialization.
         * Returns all event types as JSON array.
         */
        get("/chat/events/test") {
            val testEvents = listOf(
                AgentEvent.Thinking(
                    eventId = "test-thinking",
                    timestamp = System.currentTimeMillis(),
                    content = "Test thinking content"
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
    }
}
