package com.example.smarty.server.routes

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
import java.util.UUID

import com.example.smarty.protocol.AgentCommand
import com.example.smarty.server.agent.ServerAgent
import com.example.smarty.server.data.EmbeddingClient
import com.example.smarty.server.data.SupabaseVectorStore
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
 * - GET /chat/stream?query=... - SSE stream of agent events
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

    routing {
        /**
         * SSE endpoint for agent event streaming.
         *
         * Executes the ServerAgent strategy and streams real-time events.
         *
         * Usage:
         * ```
         * curl -N -H "Accept: text/event-stream" "http://localhost:7860/chat/stream?query=hello"
         * ```
         */
        sse("/chat/stream") {
            val query = call.request.queryParameters["query"] ?: "default query"

            // Log the incoming request
            call.application.log.info("SSE stream started for query: $query")

            // Create agent instance for this request
            // Note: ServerAgent is lightweight, but we pass the shared singletons
            val agent = ServerAgent(
                llmProvider = llmProvider,
                tavilyTool = tavilyTool,
                vectorStore = vectorStore,
                embeddingClient = embeddingClient,
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
                // Run the agent strategy
                agent.run(query)
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

            call.application.log.info("SSE stream completed for query: $query")
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
