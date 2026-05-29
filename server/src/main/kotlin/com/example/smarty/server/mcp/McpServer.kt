package com.example.smarty.server.mcp

import com.example.smarty.protocol.AgentEvent
import com.example.smarty.server.HttpClientSingleton
import com.example.smarty.server.agent.ActiveEventBridge
import com.example.smarty.server.agent.ActiveSessionManager
import com.example.smarty.server.agent.AgentRunManager
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
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.principal
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Basic Model Context Protocol (MCP) Server implementation using SSE.
 * Exposes Smarty's internal tools to OpenCode CLI natively.
 * REVISION V6: Privileged mode — only ask_user requires user interaction.
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

    private data class McpSession(
        val channel: Channel<ServerSentEvent>,
        val createdAt: Long = System.currentTimeMillis(),
    )

    private val sessions = ConcurrentHashMap<String, McpSession>()
    private val sessionTtlMs = 300_000L

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

                val userId = principal?.userId
                    ?: com.example.smarty.server.agent.ActiveUserRegistry.getMostRecentActiveUser()
                    ?: getFallbackUserId()

                val userSessions = ActiveSessionManager.getAllSessions().filter { it.userId == userId }

                val body = call.receiveText()
                val request = runCatching { json.decodeFromString<JsonRpcRequest>(body) }.getOrNull()
                logger.info("[McpServer] POST /messages: sessionId=$mcpSessionId, userId=$userId, principal=${principal?.userId}, activeUser=${com.example.smarty.server.agent.ActiveUserRegistry.getMostRecentActiveUser()}, body=${body.take(500)}")

                if (request == null) {
                    call.respond(HttpStatusCode.BadRequest, "Invalid JSON-RPC payload")
                    return@post
                }

                handleMcpRequest(mcpSessionId, request, userId, userSessions, call)
            }
        }
    }

    private fun evictStaleSessions() {
        val deadline = System.currentTimeMillis() - sessionTtlMs
        sessions.entries.removeIf { it.value.createdAt < deadline }
    }

    private suspend fun handleMcpRequest(
        mcpSessionId: String,
        request: JsonRpcRequest,
        userId: String,
        userSessions: List<ActiveSessionManager.SessionInfo>,
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
                        for (session in userSessions) {
                            ApprovalRegistry.cancelApprovalsForSession(session.sessionId)
                        }
                        null
                    }
                    "tools/list" -> handleToolsList()
                    "tools/call" -> handleToolCall(request.params, userId, userSessions)
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
            put("protocolVersion", JsonPrimitive("2024-11-05"))
            put("capabilities", buildJsonObject { put("tools", buildJsonObject {}) })
            put(
                "serverInfo",
                buildJsonObject {
                    put("name", JsonPrimitive("smarty-mcp-server"))
                    put("version", JsonPrimitive("1.0.0"))
                },
            )
        }

    private fun ToolProperty.toJsonSchema(): JsonObject =
        buildJsonObject {
            put("type", JsonPrimitive(type))
            description?.let { put("description", JsonPrimitive(it)) }
            enum?.let { enumList -> put("enum", buildJsonArray { enumList.forEach { add(JsonPrimitive(it)) } }) }
            items?.let { put("items", it.toJsonSchema()) }
            properties?.let { props ->
                put(
                    "properties",
                    buildJsonObject {
                        props.forEach { (k, v) -> put(k, v.toJsonSchema()) }
                    },
                )
            }
            required?.takeIf { it.isNotEmpty() }?.let { reqList ->
                put("required", buildJsonArray { reqList.forEach { add(JsonPrimitive(it)) } })
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
                                put("name", JsonPrimitive(tool.name))
                                put("description", JsonPrimitive(tool.description))
                                put(
                                    "inputSchema",
                                    buildJsonObject {
                                        put("type", JsonPrimitive("object"))
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
                                                buildJsonArray { tool.parameters.required.forEach { add(JsonPrimitive(it)) } },
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
        userSessions: List<ActiveSessionManager.SessionInfo>,
    ): JsonElement {
        val name = params?.get("name")?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing tool name")
        val args = params["arguments"]?.jsonObject ?: buildJsonObject {}
        val resolvedName = ToolExecutor.mapOldToolNames(name)
        val toolCallId = "mcp-${UUID.randomUUID()}"

        suspend fun emitToAllSessions(event: AgentEvent) {
            for (session in userSessions) {
                runCatching { AgentRunManager.emitEvent(session.sessionId, event) }
            }
        }

        val primarySessionId = userSessions.firstOrNull()?.sessionId ?: "global"

        logger.info("[MCP] Tool call: name=$name -> resolved=$resolvedName, toolCallId=$toolCallId, user=$userId, sessions=${userSessions.size}, args=${args.toString().take(200)}")

        thinkingStorage.updateToolCall(primarySessionId, toolCallId, resolvedName, "started", args.toString())

        // High-risk path sandbox (only for file-adjacent tools)
        if (resolvedName == "bash" || resolvedName == "command" || resolvedName.contains("write") || resolvedName.contains("replace")) {
            val argsStr = args.toString()
            val isHighRisk =
                argsStr.contains(".opencode") ||
                    argsStr.contains(".ssh") ||
                    argsStr.contains("/etc/") ||
                    argsStr.contains(".aws") ||
                    argsStr.contains("~/.config") ||
                    argsStr.contains("package.json")

            if (isHighRisk) {
                val errorMsg = "Security Violation: Access to high-risk path blocked by Ktor MCP Sandbox."
                thinkingStorage.updateToolCall(primarySessionId, toolCallId, resolvedName, "failed", args.toString(), errorMsg)
                return buildJsonObject {
                    put("isError", JsonPrimitive(true))
                    put(
                        "content",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("type", JsonPrimitive("text"))
                                    put("text", JsonPrimitive(errorMsg))
                                },
                            )
                        },
                    )
                }
            }
        }

        // PRIVILEGED MODE: Only ask_user requires user interaction (clarification question card).
        // All other tools (bash, device, memory, etc.) run autonomously — no approval gate.
        if (resolvedName == "ask_user" || resolvedName == "askuser") {
            logger.info("[MCP] ask_user: toolCallId=$toolCallId, userId=$userId, sessions=${userSessions.size}")
            val approvalEvent =
                AgentEvent.ApprovalRequested(
                    UUID.randomUUID().toString(),
                    System.currentTimeMillis(),
                    toolCallId,
                    resolvedName,
                    resolvedName.replace('_', ' ').replaceFirstChar { it.uppercase() },
                    args.toString(),
                )
            eventEmitter?.invoke(approvalEvent)
            emitToAllSessions(approvalEvent)
            logger.info("[MCP] ask_user: emitted ApprovalRequested for toolCallId=$toolCallId, now waiting for approval...")

            val result =
                runCatching {
                    // 161s timeout — golden ratio (φ ≈ 1.618) × 100 ≈ 161s
                    withTimeoutOrNull(161_000L) {
                        ApprovalRegistry.createPendingApproval(toolCallId, primarySessionId, userId).await()
                    }
                }.getOrNull() ?: com.example.smarty.server.agent.ApprovalResult(false, "Approval timed out")
            logger.info("[MCP] ask_user: approval resolved for toolCallId=$toolCallId, approved=${result.approved}, feedback=${result.feedback?.take(100)}")

            if (!result.approved) {
                val denial = result.feedback ?: "User denied"
                thinkingStorage.updateToolCall(primarySessionId, toolCallId, resolvedName, "failed", args.toString(), denial)
                val deniedEvent = AgentEvent.ApprovalDenied(UUID.randomUUID().toString(), System.currentTimeMillis(), toolCallId)
                eventEmitter?.invoke(deniedEvent)
                emitToAllSessions(deniedEvent)
                return buildJsonObject {
                    put(
                        "content",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("type", JsonPrimitive("text"))
                                    put("text", JsonPrimitive(denial))
                                },
                            )
                        },
                    )
                }
            }

            val userResponse = result.feedback ?: "Proceed with your best judgment"
            thinkingStorage.updateToolCall(primarySessionId, toolCallId, resolvedName, "completed", args.toString(), userResponse)
            val grantedEvent = AgentEvent.ApprovalGranted(UUID.randomUUID().toString(), System.currentTimeMillis(), toolCallId)
            eventEmitter?.invoke(grantedEvent)
            emitToAllSessions(grantedEvent)
            return buildJsonObject {
                put(
                    "content",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("type", JsonPrimitive("text"))
                                put("text", JsonPrimitive(userResponse))
                            },
                        )
                    },
                )
            }
        }

        // All other tools: execute autonomously — no approval gate
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
                    emitToAllSessions(it)
                },
                noteService,
            )
        return try {
            val resultStr = executor.executeTool(name, args.toString(), emptyList(), skipApprovalGate = true)
            thinkingStorage.updateToolCall(primarySessionId, toolCallId, resolvedName, "completed", args.toString(), resultStr)
            buildJsonObject {
                put(
                    "content",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("type", JsonPrimitive("text"))
                                put("text", JsonPrimitive(resultStr))
                            },
                        )
                    },
                )
            }
        } catch (e: Exception) {
            val errorMsg = "Error executing tool: ${e.message}"
            thinkingStorage.updateToolCall(primarySessionId, toolCallId, resolvedName, "failed", args.toString(), errorMsg)
            buildJsonObject {
                put("isError", JsonPrimitive(true))
                put(
                    "content",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("type", JsonPrimitive("text"))
                                put("text", JsonPrimitive(errorMsg))
                            },
                        )
                    },
                )
            }
        }
    }

    private fun getFallbackUserId(): String =
        runCatching {
            com.example.smarty.server.data.DatabaseFactory.getDataSource()?.connection?.use { conn ->
                conn.prepareStatement("SELECT id::text FROM users LIMIT 1").use { stmt ->
                    stmt.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else "daemon-localhost" }
                }
            }
        }.getOrNull() ?: "daemon-localhost"
}
