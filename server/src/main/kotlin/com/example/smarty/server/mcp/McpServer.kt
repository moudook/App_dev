package com.example.smarty.server.mcp

import com.example.smarty.protocol.AgentEvent
import com.example.smarty.server.HttpClientSingleton
import com.example.smarty.server.agent.ActiveEventBridge
import com.example.smarty.server.agent.ActiveSessionManager
import com.example.smarty.server.agent.AgentToolDefinitions
import com.example.smarty.server.agent.ApprovalRegistry
import com.example.smarty.server.agent.ResearchAgentTools
import com.example.smarty.server.agent.ThinkingStorageManagerSingleton
import com.example.smarty.server.agent.ToolExecutor
import com.example.smarty.server.data.CalendarRepository
import com.example.smarty.server.data.NoteRepository
import com.example.smarty.server.data.PostgresVectorStore
import com.example.smarty.server.data.TimerRepository
import com.example.smarty.server.llm.LlmProviderFactory
import com.example.smarty.server.llm.ToolDefinition
import com.example.smarty.server.llm.ToolProperty
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
 * REVISION V5: Added cancellation support and integrated with thinking trace.
 */
class McpServer(
    private val vectorStore: PostgresVectorStore,
    private val noteRepository: NoteRepository?,
    private val timerRepository: TimerRepository?,
    private val calendarRepository: CalendarRepository?,
    private val noteService: NoteService?,
) {
    var eventEmitter: (suspend (AgentEvent) -> Unit)? = null

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
    private val logger = LoggerFactory.getLogger(McpServer::class.java)
    private val thinkingStorage = ThinkingStorageManagerSingleton.instance

    private data class McpSession(val channel: Channel<ServerSentEvent>, val createdAt: Long = System.currentTimeMillis())

    private val sessions = ConcurrentHashMap<String, McpSession>()
    private val SESSION_TTL_MS = 300_000L

    private val allTools: List<ToolDefinition> by lazy {
        (AgentToolDefinitions.getAllTools() + ResearchAgentTools.getEnhancedTools()).filter {
            it.name != "search" && it.name != "web_search" && it.name != "web_scrape"
        }
    }

    fun configureRouting(routing: Routing) {
        routing.route("/mcp") {
            evictStaleSessions()

            sse("/sse") {
                val sessionId = UUID.randomUUID().toString()
                val channel = Channel<ServerSentEvent>(Channel.BUFFERED)
                sessions[sessionId] = McpSession(channel)
                try {
                    val host = call.request.local.serverHost
                    val port = call.request.local.serverPort
                    val scheme = call.request.local.scheme
                    val endpointUrl = "$scheme://$host:$port/mcp/messages?sessionId=$sessionId"
                    send(ServerSentEvent(event = "endpoint", data = endpointUrl))
                    while (isActive) {
                        val event = withTimeoutOrNull(30000) { channel.receive() }
                        if (event != null) send(event) else send(ServerSentEvent(event = "heartbeat", data = "ping"))
                    }
                } catch (e: Exception) {
                    logger.debug("[McpServer] SSE client disconnected from session $sessionId")
                } finally {
                    sessions.remove(sessionId)
                    channel.close()
                }
            }

            post("/sse") {
                val sessionId = UUID.randomUUID().toString()
                val channel = Channel<ServerSentEvent>(Channel.BUFFERED)
                sessions[sessionId] = McpSession(channel)
                val host = call.request.local.serverHost
                val port = call.request.local.serverPort
                val scheme = call.request.local.scheme
                val endpointUrl = "$scheme://$host:$port/mcp/messages?sessionId=$sessionId"
                call.respondText(
                    """{"sessionId":"$sessionId","endpoint":"$endpointUrl"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.OK,
                )
                logger.info("[McpServer] POST-SSE session created: $sessionId")
            }

            post("/messages") {
                val mcpSessionId = call.request.queryParameters["sessionId"] ?: "default"
                val remoteHost = call.request.local.remoteHost
                val isLocalhost = remoteHost == "127.0.0.1" || remoteHost == "::1" || remoteHost == "localhost"

                val principal = if (!isLocalhost) call.principal<FirebaseUserPrincipal>() else null
                if (!isLocalhost && principal == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
                    return@post
                }

                val userId = principal?.userId ?: getFallbackUserId()

                // Try to find the actual Smarty session ID mapped to this daemon session
                val smartySessionId = ActiveSessionManager.getAllSessions().find { it.userId == userId }?.sessionId ?: "global"

                val body = call.receiveText()
                val request = runCatching { json.decodeFromString<JsonRpcRequest>(body) }.getOrNull()
                logger.info("[McpServer] POST /messages: sessionId=$mcpSessionId, body=$body")

                if (request == null) {
                    call.respond(HttpStatusCode.BadRequest, "Invalid JSON-RPC payload")
                    return@post
                }

                handleMcpRequest(mcpSessionId, request, userId, smartySessionId, call)
            }
        }
    }

    private fun evictStaleSessions() {
        val deadline = System.currentTimeMillis() - SESSION_TTL_MS
        sessions.entries.removeIf { it.value.createdAt < deadline }
    }

    private suspend fun handleMcpRequest(
        mcpSessionId: String,
        request: JsonRpcRequest,
        userId: String,
        smartySessionId: String,
        call: ApplicationCall? = null,
    ) {
        val channel = sessions[mcpSessionId]?.channel
        try {
            val responseResult =
                when (request.method) {
                    "initialize" -> handleInitialize()
                    "notifications/initialized" -> null
                    "notifications/cancelled" -> {
                        logger.info("[McpServer] Request cancelled by client: ${request.params}")
                        ApprovalRegistry.cancelApprovalsForSession(smartySessionId)
                        null
                    }
                    "tools/list" -> handleToolsList()
                    "tools/call" -> handleToolCall(request.params, userId, smartySessionId)
                    else -> throw IllegalArgumentException("Method not supported: ${request.method}")
                }

            if (request.id != null && responseResult != null) {
                val responseJson = json.encodeToString(JsonRpcResponse(id = request.id, result = responseResult))
                if (channel != null) {
                    channel.send(ServerSentEvent(event = "message", data = responseJson))
                    call?.respondText("Accepted", ContentType.Text.Plain, HttpStatusCode.Accepted)
                } else {
                    call?.respondText(responseJson, ContentType.Application.Json, HttpStatusCode.OK)
                }
            } else {
                call?.respondText("Accepted", ContentType.Text.Plain, HttpStatusCode.Accepted)
            }
        } catch (e: Exception) {
            logger.error("[McpServer] Error processing ${request.method}: ${e.message}")
            if (request.id != null) {
                val errorJson =
                    json.encodeToString(
                        JsonRpcResponse(id = request.id, error = JsonRpcError(-32603, e.message ?: "Internal Error")),
                    )
                channel?.send(ServerSentEvent(event = "message", data = errorJson))
                call?.respondText(errorJson, ContentType.Application.Json, HttpStatusCode.InternalServerError)
            } else {
                call?.respond(HttpStatusCode.InternalServerError)
            }
        }
    }

    private fun handleInitialize() =
        buildJsonObject {
            put("protocolVersion", "2024-11-05")
            put("capabilities", buildJsonObject { put("tools", buildJsonObject {}) })
            put(
                "serverInfo",
                buildJsonObject {
                    put("name", "smarty-mcp-server")
                    put("version", "1.0.0")
                },
            )
        }

    private fun ToolProperty.toJsonSchema(): JsonObject = buildJsonObject {
        put("type", type)
        description?.let { put("description", it) }
        enum?.let { enumList -> put("enum", buildJsonArray { enumList.forEach { add(it) } }) }
        items?.let { put("items", it.toJsonSchema()) }
        properties?.let { props ->
            put("properties", buildJsonObject {
                props.forEach { (k, v) -> put(k, v.toJsonSchema()) }
            })
        }
        required?.takeIf { it.isNotEmpty() }?.let { reqList ->
            put("required", buildJsonArray { reqList.forEach { add(it) } })
        }
    }

    private fun handleToolsList() =
        buildJsonObject {
            put(
                "tools",
                buildJsonArray {
                    allTools.forEach { tool ->
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
                                                tool.parameters.properties.forEach { (key, prop) ->
                                                    put(key, prop.toJsonSchema())
                                                }
                                            },
                                        )
                                        if (tool.parameters.required.isNotEmpty()) {
                                            put(
                                                "required",
                                                buildJsonArray { tool.parameters.required.forEach { add(it) } },
                                            )
                                        }
                                    },
                                )
                            },
                        )
                    }
                },
            )
        }

    private suspend fun handleToolCall(
        params: JsonObject?,
        userId: String,
        smartySessionId: String,
    ): JsonElement {
        val name = params?.get("name")?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing tool name")
        val args = params["arguments"]?.jsonObject ?: buildJsonObject {}
        val resolvedName = ToolExecutor.mapOldToolNames(name)
        val toolCallId = "mcp-${UUID.randomUUID()}"

        // INTEGRATION: Add to thinking trace immediately so the UI shows activity
        thinkingStorage.updateToolCall(smartySessionId, toolCallId, resolvedName, "started", args.toString())

        val requiresApproval = resolvedName == "bash" || resolvedName.startsWith("device") || resolvedName == "ask_user"
        if (requiresApproval) {
            val approvalEvent =
                AgentEvent.ApprovalRequested(
                    UUID.randomUUID().toString(),
                    System.currentTimeMillis(),
                    toolCallId,
                    resolvedName,
                    resolvedName.replace(
                        '_',
                        ' ',
                    ).replaceFirstChar {
                        it.uppercase()
                    },
                    args.toString(),
                )
            ActiveEventBridge.emit(userId, approvalEvent)
            eventEmitter?.invoke(approvalEvent)

            val result =
                runCatching { withTimeoutOrNull(60_000L) { ApprovalRegistry.createPendingApproval(toolCallId, smartySessionId, userId).await() } }
                    .getOrNull() ?: com.example.smarty.server.agent.ApprovalResult(false, "Approval timed out or system error")

            if (!result.approved) {
                val denial = "User denied: ${result.feedback ?: "no reason given"}"
                thinkingStorage.updateToolCall(smartySessionId, toolCallId, resolvedName, "failed", args.toString(), denial)
                val deniedEvent = AgentEvent.ApprovalDenied(UUID.randomUUID().toString(), System.currentTimeMillis(), toolCallId)
                ActiveEventBridge.emit(userId, deniedEvent)
                eventEmitter?.invoke(deniedEvent)
                return buildJsonObject {
                    put(
                        "content",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", denial)
                                },
                            )
                        },
                    )
                }
            }

            // If it's ask_user, the user's text response IS the result.
            // We bypass ToolExecutor entirely.
            if (resolvedName == "ask_user") {
                val userResponse = result.feedback ?: "User provided no answer"
                thinkingStorage.updateToolCall(smartySessionId, toolCallId, resolvedName, "completed", args.toString(), userResponse)
                val grantedEvent = AgentEvent.ApprovalGranted(UUID.randomUUID().toString(), System.currentTimeMillis(), toolCallId)
                ActiveEventBridge.emit(userId, grantedEvent)
                eventEmitter?.invoke(grantedEvent)
                return buildJsonObject {
                    put(
                        "content",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", userResponse)
                                },
                            )
                        },
                    )
                }
            }
        }

        val executor =
            ToolExecutor(
                userId,
                LlmProviderFactory.getOrCreateProvider(
                    HttpClientSingleton.client,
                ),
                vectorStore,
                noteRepository,
                timerRepository,
                calendarRepository,
                {
                    ActiveEventBridge.emit(userId, it)
                },
                noteService,
            )
        return try {
            val resultStr = executor.executeTool(name, args.toString(), emptyList(), skipApprovalGate = true)
            thinkingStorage.updateToolCall(smartySessionId, toolCallId, resolvedName, "completed", args.toString(), resultStr)
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
            val errorMsg = "Error executing tool: ${e.message}"
            thinkingStorage.updateToolCall(smartySessionId, toolCallId, resolvedName, "failed", args.toString(), errorMsg)
            buildJsonObject {
                put("isError", true)
                put(
                    "content",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("type", "text")
                                put("text", errorMsg)
                            },
                        )
                    },
                )
            }
        }
    }

    private fun getFallbackUserId(): String {
        return runCatching {
            com.example.smarty.server.data.DatabaseFactory.getDataSource()?.connection?.use { conn ->
                conn.prepareStatement("SELECT id::text FROM users LIMIT 1").use { stmt ->
                    stmt.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else "daemon-localhost" }
                }
            }
        }.getOrNull() ?: "daemon-localhost"
    }
}
