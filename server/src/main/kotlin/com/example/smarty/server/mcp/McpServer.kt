package com.example.smarty.server.mcp

import com.example.smarty.server.agent.AgentToolDefinitions
import com.example.smarty.server.agent.ActiveSessionManager
import com.example.smarty.server.agent.ResearchAgentTools
import com.example.smarty.server.agent.ToolExecutor
import com.example.smarty.server.data.CalendarRepository
import com.example.smarty.server.data.NoteRepository
import com.example.smarty.server.data.PostgresVectorStore
import com.example.smarty.server.data.TimerRepository
import com.example.smarty.server.llm.ToolDefinition
import com.example.smarty.server.services.NoteService
import com.example.smarty.server.llm.LlmProviderFactory
import com.example.smarty.server.HttpClientSingleton
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

/**
 * Basic Model Context Protocol (MCP) Server implementation using SSE.
 * Exposes Smarty's internal tools to OpenCode CLI natively.
 */
class McpServer(
    private val vectorStore: PostgresVectorStore,
    private val noteRepository: NoteRepository?,
    private val timerRepository: TimerRepository?,
    private val calendarRepository: CalendarRepository?,
    private val noteService: NoteService?
) {
    private val logger = LoggerFactory.getLogger(McpServer::class.java)
    private val sessions = ConcurrentHashMap<String, Channel<ServerSentEvent>>()

    // All available Smarty tools, filtering out ones handled natively by OpenCode
    private val allTools: List<ToolDefinition> by lazy {
        val tools = AgentToolDefinitions.getAllTools() + ResearchAgentTools.getEnhancedTools()
        tools.filter { 
            it.name != "search" && it.name != "web_search" && it.name != "web_scrape"
        }
    }

    fun configureRouting(routing: Routing) {
        routing.route("/mcp") {
            // 1. Establish SSE Connection
            sse("/sse") {
                val sessionId = UUID.randomUUID().toString()
                val channel = Channel<ServerSentEvent>(Channel.UNLIMITED)
                sessions[sessionId] = channel

                try {
                    // Send the endpoint URL to the client
                    send(ServerSentEvent(event = "endpoint", data = "/mcp/messages?sessionId=$sessionId"))

                    // Keep connection alive and forward messages
                    for (event in channel) {
                        send(event)
                    }
                } finally {
                    sessions.remove(sessionId)
                    channel.close()
                }
            }

            // 2. Receive JSON-RPC messages from client
            post("/messages") {
                val sessionId = call.request.queryParameters["sessionId"]
                if (sessionId == null || !sessions.containsKey(sessionId)) {
                    call.respond(HttpStatusCode.BadRequest, "Invalid or missing sessionId")
                    return@post
                }

                val body = call.receiveText()
                val request = runCatching {
                    Json { ignoreUnknownKeys = true }.decodeFromString<JsonRpcRequest>(body)
                }.getOrNull()

                if (request == null) {
                    call.respond(HttpStatusCode.BadRequest, "Invalid JSON-RPC payload")
                    return@post
                }

                // Immediately accept the POST request
                call.respond(HttpStatusCode.Accepted)

                // Process the message asynchronously and send response via SSE
                handleMcpRequest(sessionId, request)
            }
        }
    }

    private suspend fun handleMcpRequest(sessionId: String, request: JsonRpcRequest) {
        val channel = sessions[sessionId] ?: return

        try {
            val responseResult = when (request.method) {
                "initialize" -> handleInitialize()
                "notifications/initialized" -> null // Just ack
                "tools/list" -> handleToolsList()
                "tools/call" -> handleToolCall(request.params)
                else -> throw IllegalArgumentException("Method not supported: ${request.method}")
            }

            // If it's a request (has id), send response
            if (request.id != null && responseResult != null) {
                val response = JsonRpcResponse(
                    id = request.id,
                    result = responseResult
                )
                val responseJson = Json.encodeToString(response)
                channel.send(ServerSentEvent(event = "message", data = responseJson))
            }
        } catch (e: Exception) {
            if (request.id != null) {
                val errorResponse = JsonRpcResponse(
                    id = request.id,
                    error = JsonRpcError(code = -32603, message = e.message ?: "Internal error")
                )
                channel.send(ServerSentEvent(event = "message", data = Json.encodeToString(errorResponse)))
            }
        }
    }

    private fun handleInitialize(): JsonElement {
        return buildJsonObject {
            put("protocolVersion", "2024-11-05")
            put("capabilities", buildJsonObject {
                put("tools", buildJsonObject {})
            })
            put("serverInfo", buildJsonObject {
                put("name", "smarty-mcp-server")
                put("version", "1.0.0")
            })
        }
    }

    private fun handleToolsList(): JsonElement {
        val toolsArray = buildJsonArray {
            for (tool in allTools) {
                // MCP format for Tool
                add(buildJsonObject {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("inputSchema", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            for ((key, prop) in tool.parameters.properties) {
                                put(key, buildJsonObject {
                                    put("type", prop.type)
                                    put("description", prop.description)
                                    if (prop.enum != null) {
                                        put("enum", buildJsonArray { prop.enum.forEach { add(it) } })
                                    }
                                })
                            }
                        })
                        if (tool.parameters.required.isNotEmpty()) {
                            put("required", buildJsonArray {
                                tool.parameters.required.forEach { add(it) }
                            })
                        }
                    })
                })
            }
        }

        return buildJsonObject {
            put("tools", toolsArray)
        }
    }

    private suspend fun handleToolCall(params: JsonObject?): JsonElement {
        val name = params?.get("name")?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing tool name")
        val args = params["arguments"]?.jsonObject ?: buildJsonObject {}

        // Resolve context from ActiveSessionManager for the local OpenCode Daemon
        val activeSession = ActiveSessionManager.getAllSessions().maxByOrNull { it.lastActivity }
        val userId = activeSession?.userId ?: "default-user"

        logger.info("Executing MCP Tool: \$name for userId: \$userId")

        val executor = ToolExecutor(
            userId = userId,
            llmProvider = LlmProviderFactory.getOrCreateProvider(HttpClientSingleton.client),
            vectorStore = vectorStore,
            noteRepository = noteRepository,
            timerRepository = timerRepository,
            calendarRepository = calendarRepository,
            eventEmitter = {}, // No-op: OpenCode's SSE stream handles the tool lifecycle UI nodes
            noteService = noteService
        )

        return try {
            val resultStr = executor.executeTool(name, args.toString(), emptyList())
            buildJsonObject {
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", resultStr)
                    })
                })
            }
        } catch (e: Exception) {
            logger.error("Tool execution failed for \$name", e)
            buildJsonObject {
                put("isError", true)
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", "Error executing tool: \${e.message}")
                    })
                })
            }
        }
    }
}
