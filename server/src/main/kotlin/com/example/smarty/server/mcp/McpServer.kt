package com.example.smarty.server.mcp

import com.example.smarty.protocol.AgentEvent
import com.example.smarty.server.HttpClientSingleton
import com.example.smarty.server.agent.ActiveEventBridge
import com.example.smarty.server.agent.ActiveSessionManager
import com.example.smarty.server.agent.AgentToolDefinitions
import com.example.smarty.server.agent.ApprovalRegistry
import com.example.smarty.server.agent.ResearchAgentTools
import com.example.smarty.server.agent.ToolExecutor
import com.example.smarty.server.data.CalendarRepository
import com.example.smarty.server.data.NoteRepository
import com.example.smarty.server.data.PostgresVectorStore
import com.example.smarty.server.data.TimerRepository
import com.example.smarty.server.llm.LlmProviderFactory
import com.example.smarty.server.llm.ToolDefinition
import com.example.smarty.server.plugins.FirebaseUserPrincipal
import com.example.smarty.server.services.NoteService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Basic Model Context Protocol (MCP) Server implementation using SSE.
 * Exposes Smarty's internal tools to OpenCode CLI natively.
 */
class McpServer(
    private val vectorStore: PostgresVectorStore,
    private val noteRepository: NoteRepository?,
    private val timerRepository: TimerRepository?,
    private val calendarRepository: CalendarRepository?,
    private val noteService: NoteService?,
) {
    // Event emitter for approval gating — injected by Application.kt route config
    var eventEmitter: (suspend (AgentEvent) -> Unit)? = null

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
    private val logger = LoggerFactory.getLogger(McpServer::class.java)

    private data class McpSession(
        val channel: Channel<ServerSentEvent>,
        val createdAt: Long = System.currentTimeMillis(),
    )

    private val sessions = ConcurrentHashMap<String, McpSession>()

    // TTL for POST-created sessions: 5 minutes
    private val SESSION_TTL_MS = 300_000L

    // All available Smarty tools, filtering out ones handled natively by OpenCode
    private val allTools: List<ToolDefinition> by lazy {
        val tools = AgentToolDefinitions.getAllTools() + ResearchAgentTools.getEnhancedTools()
        tools.filter {
            it.name != "search" && it.name != "web_search" && it.name != "web_scrape"
        }
    }

    fun configureRouting(routing: Routing) {
        routing.route("/mcp") {
            // Evict stale POST-created sessions before creating new ones
            evictStaleSessions()

            // 1. Establish SSE Connection (GET via standard SSE protocol)
            sse("/sse") {
                val sessionId = UUID.randomUUID().toString()
                val channel = Channel<ServerSentEvent>(Channel.BUFFERED)
                sessions[sessionId] = McpSession(channel)

                try {
                    // Send the endpoint URL to the client
                    val host = call.request.local.serverHost
                    val port = call.request.local.serverPort
                    val scheme = call.request.local.scheme

                    // For HF Spaces, port is typically 7860. The reverse proxy might not forward scheme properly so we fallback to a relative-like approach if needed.
                    val endpointUrl = "$scheme://$host:$port/mcp/messages?sessionId=$sessionId"
                    send(ServerSentEvent(event = "endpoint", data = endpointUrl))

                    // SSE forwarding loop with heartbeat — detects stale connections
                    while (isActive) {
                        val event = withTimeoutOrNull(30000) { channel.receive() }
                        if (event != null) {
                            send(event)
                        } else {
                            // Heartbeat: if send fails, client disconnected and loop exits
                            send(ServerSentEvent(event = "heartbeat", data = "ping"))
                        }
                    }
                } catch (e: Exception) {
                    logger.debug("[McpServer] SSE client disconnected from session $sessionId: ${e.message?.take(50)}")
                } finally {
                    sessions.remove(sessionId)
                    channel.close()
                }
            }

            // (Removed post("/sse") to allow OpenCode to properly fall back to SSE via GET)

            // 2. Receive JSON-RPC messages from client (Firebase-authenticated)
            // Localhost/daemon bypass: unauthenticated requests from 127.0.0.1 are treated as
            // the OpenCode CLI daemon. This is required because the daemon cannot provide
            // Firebase JWT tokens but must be able to discover and call MCP tools.
            post("/messages") {
                val sessionId = call.request.queryParameters["sessionId"] ?: "default"

                // Allow unauthenticated local requests (from the OpenCode daemon)
                val remoteHost = call.request.local.remoteHost
                val isLocalhost =
                    remoteHost == "127.0.0.1" || remoteHost == "::1" ||
                        remoteHost == "0:0:0:0:0:0:0:1" || remoteHost == "localhost"

                val user =
                    if (!isLocalhost) {
                        call.principal<FirebaseUserPrincipal>().also {
                            if (it == null) {
                                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
                                return@post
                            }
                        }
                    } else {
                        null
                    }

                val userId =
                    user?.userId
                        ?: ActiveSessionManager.getAllSessions().find { it.sessionId == sessionId }?.userId
                        ?: ActiveSessionManager.getAllSessions().maxByOrNull { it.lastActivity }?.userId
                        ?: getFallbackUserId()

                val body = call.receiveText()
                val request =
                    runCatching {
                        json.decodeFromString<JsonRpcRequest>(body)
                    }.getOrNull()

                logger.info("[McpServer] POST /messages: sessionId=$sessionId, hasSession=${sessions.containsKey(sessionId)}, body=$body")

                if (request == null) {
                    call.respond(HttpStatusCode.BadRequest, "Invalid JSON-RPC payload")
                    return@post
                }

                // Process synchronously and respond inline
                handleMcpRequest(sessionId, request, userId, call)
            }
        }
    }

    private fun evictStaleSessions() {
        val deadline = System.currentTimeMillis() - SESSION_TTL_MS
        sessions.entries.removeIf { it.value.createdAt < deadline }
    }

    private suspend fun handleMcpRequest(
        sessionId: String,
        request: JsonRpcRequest,
        userId: String,
        call: ApplicationCall? = null,
    ) {
        val channel = sessions[sessionId]?.channel

        try {
            val responseResult =
                when (request.method) {
                    "initialize" -> handleInitialize()
                    "notifications/initialized" -> null // Just ack
                    "tools/list" -> handleToolsList()
                    "tools/call" -> handleToolCall(request.params, userId)
                    else -> throw IllegalArgumentException("Method not supported: ${request.method}")
                }

            if (request.id != null && responseResult != null) {
                val response =
                    JsonRpcResponse(
                        id = request.id,
                        result = responseResult,
                    )
                val responseJson = json.encodeToString(response)

                // Send via SSE if channel exists
                if (channel != null) {
                    logger.info("[McpServer] Sending SSE event payload: $responseJson")
                    channel.send(ServerSentEvent(event = "message", data = responseJson))
                    call?.respondText(contentType = ContentType.Text.Plain, status = HttpStatusCode.Accepted, text = "Accepted")
                } else {
                    // Send inline HTTP response if no SSE channel (legacy/direct call)
                    call?.respondText(contentType = ContentType.Application.Json, status = HttpStatusCode.OK, text = responseJson)
                }
            } else if (call != null) {
                call.respondText(contentType = ContentType.Text.Plain, status = HttpStatusCode.Accepted, text = "Accepted")
            }
        } catch (e: Exception) {
            logger.error("[McpServer] Error processing method ${request.method}: ${e.message}", e)
            if (request.id != null) {
                val errorResponse =
                    JsonRpcResponse(
                        id = request.id,
                        error =
                            JsonRpcError(
                                code = -32603,
                                message = e.message ?: "Internal Error",
                            ),
                    )
                val errorJson = json.encodeToString(errorResponse)
                channel?.send(ServerSentEvent(event = "message", data = errorJson))
                call?.respondText(contentType = ContentType.Application.Json, status = HttpStatusCode.InternalServerError, text = errorJson)
            } else {
                call?.respond(HttpStatusCode.InternalServerError)
            }
        }
    }

    private fun handleInitialize(): JsonElement {
        return buildJsonObject {
            put("protocolVersion", "2024-11-05")
            put(
                "capabilities",
                buildJsonObject {
                    put("tools", buildJsonObject {})
                },
            )
            put(
                "serverInfo",
                buildJsonObject {
                    put("name", "smarty-mcp-server")
                    put("version", "1.0.0")
                },
            )
        }
    }

    private fun handleToolsList(): JsonElement {
        val toolsArray =
            buildJsonArray {
                for (tool in allTools) {
                    // MCP format for Tool
                    add(
                        buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put(
                                "inputSchema",
                                buildJsonObject {
                                    put("type", "object")
                                    put(
                                        "properties",
                                        buildJsonObject {
                                            for ((key, prop) in tool.parameters.properties) {
                                                put(
                                                    key,
                                                    buildJsonObject {
                                                        put("type", prop.type)
                                                        put("description", prop.description)
                                                        if (prop.enum != null) {
                                                            put("enum", buildJsonArray { prop.enum.forEach { add(it) } })
                                                        }
                                                    },
                                                )
                                            }
                                        },
                                    )
                                    if (tool.parameters.required.isNotEmpty()) {
                                        put(
                                            "required",
                                            buildJsonArray {
                                                tool.parameters.required.forEach { add(it) }
                                            },
                                        )
                                    }
                                },
                            )
                        },
                    )
                }
            }

        return buildJsonObject {
            put("tools", toolsArray)
        }
    }

    private suspend fun handleToolCall(
        params: JsonObject?,
        userId: String,
    ): JsonElement {
        val name = params?.get("name")?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing tool name")
        val args = params["arguments"]?.jsonObject ?: buildJsonObject {}

        // === PERMISSION ENGINE ===
        // Apply canonical name mapping so old aliases (open_app, launch_app, etc.)
        // are evaluated against the resolved name rather than the raw input name.
        val resolvedName = ToolExecutor.mapOldToolNames(name)
        val toolCallId = UUID.randomUUID().toString()
        val requiresApproval =
            resolvedName.equals("ask_user", ignoreCase = true) ||
                resolvedName.equals("bash", ignoreCase = true) ||
                resolvedName.startsWith("device")

        if (requiresApproval) {
            val inputSummary = args.toString().take(200)

            // Emit approval requested event — user will see it on their active session
            val approvalEvent =
                AgentEvent.ApprovalRequested(
                    eventId = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    toolId = toolCallId,
                    toolName = name,
                    toolTitle = name.replace('_', ' ').replaceFirstChar { it.uppercase() },
                    toolArgs = inputSummary,
                )
            // Attempt to deliver via the user's active session SSE stream
            ActiveEventBridge.emit(userId, approvalEvent)
            // Fallback path: McpServer-bound emitter (set in Application.kt)
            eventEmitter?.invoke(approvalEvent)

            // Suspend until UI approves/denies
            val result =
                runCatching {
                    val sessionId = userId
                    ApprovalRegistry.createPendingApproval(toolCallId, sessionId, userId).await()
                }.getOrElse { e ->
                    logger.error("[McpServer] Approval await failed for $toolCallId", e)
                    com.example.smarty.server.agent.ApprovalResult(false, "Approval system error")
                }

            if (!result.approved) {
                return buildJsonObject {
                    put(
                        "content",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", "User denied: ${result.feedback ?: "no reason given"}")
                                },
                            )
                        },
                    )
                }
            }
            logger.info("[McpServer] Tool $name approved by user, proceeding with execution")
        }

        logger.info("Executing MCP Tool: $name for userId: $userId")

        val executor =
            ToolExecutor(
                userId = userId,
                llmProvider = LlmProviderFactory.getOrCreateProvider(HttpClientSingleton.client),
                vectorStore = vectorStore,
                noteRepository = noteRepository,
                timerRepository = timerRepository,
                calendarRepository = calendarRepository,
                eventEmitter = { event -> ActiveEventBridge.emit(userId, event) },
                noteService = noteService,
            )

        return try {
            val resultStr = executor.executeTool(name, args.toString(), emptyList(), skipApprovalGate = true)
            buildJsonObject {
                put(
                    "content",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("type", "text")
                                put("text", resultStr)
                            },
                        )
                    },
                )
            }
        } catch (e: Exception) {
            logger.error("Tool execution failed for $name", e)
            buildJsonObject {
                put("isError", true)
                put(
                    "content",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("type", "text")
                                put("text", "Error executing tool: ${e.message}")
                            },
                        )
                    },
                )
            }
        }
    }

    private fun getFallbackUserId(): String {
        return try {
            val ds = com.example.smarty.server.data.DatabaseFactory.getDataSource()
            if (ds != null) {
                ds.connection.use { conn ->
                    conn.prepareStatement("SELECT id::text FROM users LIMIT 1").use { stmt ->
                        stmt.executeQuery().use { rs ->
                            if (rs.next()) rs.getString("id") else "daemon-localhost"
                        }
                    }
                }
            } else {
                "daemon-localhost"
            }
        } catch (e: Exception) {
            "daemon-localhost"
        }
    }
}
