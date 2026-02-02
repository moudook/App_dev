package com.example.smarty.server.agent

import com.example.smarty.protocol.AgentCommand
import com.example.smarty.protocol.AgentEvent
import com.example.smarty.server.data.EmbeddingClient
import com.example.smarty.server.data.SupabaseVectorStore
import com.example.smarty.server.llm.*
import com.example.smarty.server.tools.TavilySearchTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import java.util.UUID
import org.slf4j.LoggerFactory

/**
 * Server-side AI Agent.
 * Orchestrates the "Remote Brain" logic using a pluggable LLM provider.
 */
class ServerAgent(
    private val llmProvider: LlmProvider,
    private val tavilyTool: TavilySearchTool,
    private val vectorStore: SupabaseVectorStore,
    private val embeddingClient: EmbeddingClient,
    private val eventEmitter: suspend (AgentEvent) -> Unit
) {
    private val logger = LoggerFactory.getLogger(ServerAgent::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private val tools = listOf(
        ToolDefinition(
            name = "add_note",
            description = "Save a note or reminder to the user's personal notebook.",
            parameters = ToolParameters(
                properties = mapOf(
                    "content" to ToolProperty("string", "The content of the note"),
                    "category" to ToolProperty("string", "Category (e.g., Work, Personal, Ideas)")
                ),
                required = listOf("content")
            )
        ),
        ToolDefinition(
            name = "set_timer",
            description = "Set a countdown timer or alarm.",
            parameters = ToolParameters(
                properties = mapOf(
                    "name" to ToolProperty("string", "Label for the timer"),
                    "time_str" to ToolProperty("string", "Duration (e.g., '5m', '1h') or time"),
                    "is_alarm" to ToolProperty("boolean", "True for alarm, false for timer")
                ),
                required = listOf("name", "time_str")
            )
        ),
        ToolDefinition(
            name = "web_search",
            description = "Search the internet for current information.",
            parameters = ToolParameters(
                properties = mapOf(
                    "query" to ToolProperty("string", "The search query")
                ),
                required = listOf("query")
            )
        )
    )

    suspend fun run(query: String) {
        logger.info("Agent starting for query: $query via ${llmProvider.providerName}")

        emit(AgentEvent.Thinking(
            eventId = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            content = "Reading memory and planning..."
        ))

        // 1. RAG
        val embedding = embeddingClient.embed(query)
        val memories = vectorStore.search(embedding, limit = 3)
        val memoryContext = if (memories.isNotEmpty()) {
            memories.joinToString("\n") { "- ${it.content}" }
        } else "No relevant memories."

        // 2. Build Messages
        val messages = listOf(
            LlmMessage(
                role = LlmMessage.Role.SYSTEM,
                content = """
                    You are Smarty, a helpful AI assistant.
                    User Memories:
                    $memoryContext

                    Rules:
                    - Be concise.
                    - Use tools for actions (notes, timers, search).
                    - If you use a tool, do not output text explanation unless necessary.
                """.trimIndent()
            ),
            LlmMessage(role = LlmMessage.Role.USER, content = query)
        )

        // 3. Stream from LLM
        var currentContent = ""
        var currentToolId = ""
        var currentToolName = ""
        var currentToolArgs = ""
        var isToolCallInProgress = false

        try {
            llmProvider.stream(messages, tools).collect { chunk ->
                // Handle Content
                if (!chunk.content.isNullOrEmpty()) {
                    currentContent += chunk.content
                    emit(AgentEvent.Thinking(
                        eventId = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        content = chunk.content
                    ))
                }

                // Handle Tool Call Accumulation
                val toolCall = chunk.toolCall
                if (toolCall != null) {
                    if (!isToolCallInProgress) {
                        isToolCallInProgress = true
                        emit(AgentEvent.ToolCall(
                            eventId = UUID.randomUUID().toString(),
                            timestamp = System.currentTimeMillis(),
                            toolName = toolCall.functionName,
                            displayName = "Preparing ${toolCall.functionName}...",
                            status = "started"
                        ))
                    }
                    if (toolCall.id.isNotEmpty()) currentToolId = toolCall.id
                    if (toolCall.functionName.isNotEmpty()) currentToolName = toolCall.functionName
                    currentToolArgs += toolCall.arguments
                }
            }

            // 4. Execute Tool if present
            if (isToolCallInProgress && currentToolName.isNotEmpty()) {
                executeTool(currentToolName, currentToolArgs)
            } else if (currentContent.isNotEmpty()) {
                // Final result if no tool
                emit(AgentEvent.Result(
                    eventId = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    content = currentContent,
                    isFinal = true
                ))
            }

        } catch (e: Exception) {
            logger.error("LLM stream error", e)
            emit(AgentEvent.Error(
                eventId = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                message = "Brain freeze: ${e.message}",
                code = "LLM_ERROR"
            ))
        }
    }

    private suspend fun executeTool(name: String, argsJson: String) {
        logger.info("Executing tool: $name with args: $argsJson")

        try {
            when (name) {
                "add_note" -> {
                    val args = json.decodeFromString<AddNoteArgs>(argsJson)
                    emit(AgentEvent.Command(
                        eventId = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        command = AgentCommand.AddNote(
                            commandId = UUID.randomUUID().toString(),
                            content = args.content,
                            category = args.category
                        )
                    ))
                    emitResult("Note added.")
                }
                "set_timer" -> {
                    val args = json.decodeFromString<SetTimerArgs>(argsJson)
                    emit(AgentEvent.Command(
                        eventId = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        command = AgentCommand.SetTimer(
                            commandId = UUID.randomUUID().toString(),
                            name = args.name,
                            timeStr = args.time_str,
                            isAlarm = args.is_alarm
                        )
                    ))
                    emitResult("Timer set.")
                }
                "web_search" -> {
                    val args = json.decodeFromString<WebSearchArgs>(argsJson)
                    val result = tavilyTool.search(args.query)
                    emitResult("Search Results:\n$result")
                }
                else -> emitResult("Unknown tool: $name")
            }

            emit(AgentEvent.ToolCall(
                eventId = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                toolName = name,
                displayName = "Executed $name",
                status = "completed"
            ))

        } catch (e: Exception) {
            logger.error("Tool execution failed", e)
            emit(AgentEvent.ToolCall(
                eventId = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                toolName = name,
                displayName = "Failed $name",
                status = "failed"
            ))
            emitResult("Failed to execute action: ${e.message}")
        }
    }

    private suspend fun emitResult(content: String) {
        emit(AgentEvent.Result(
            eventId = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            content = content,
            isFinal = true
        ))
    }

    private suspend fun emit(event: AgentEvent) {
        eventEmitter(event)
    }

    @Serializable data class AddNoteArgs(val content: String, val category: String? = null)
    @Serializable data class SetTimerArgs(val name: String, val time_str: String, val is_alarm: Boolean = false)
    @Serializable data class WebSearchArgs(val query: String)
}
