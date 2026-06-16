package com.example.smarty.server.agent2

import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.ChatMessageType
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.TokenCountEstimator
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator
import org.slf4j.LoggerFactory

enum class ContentTier {
    VERBATIM,
    DISTILLED,
    COMPRESSED,
    DROPPED,
}

data class CompactionPlan(
    val preservedMessages: List<ChatMessage>,
    val conversationState: String,
    val historySummary: String,
    val compressedToolResults: Map<String, String>,
    val droppedCount: Int,
)

data class PersonalityConfig(
    val memoryMultiplier: Double = 1.0,
    val verbatimExchanges: Int = 5,
    val distillationDepth: String = "normal",
)

class IntelligentCompactor(
    private val tokenizer: TokenCountEstimator = OpenAiTokenCountEstimator("gpt-4o"),
    private val compactTrigger: Int = 115_000,
) {
    private val logger = LoggerFactory.getLogger(IntelligentCompactor::class.java)

    companion object {
        private val ENTITY_ID_REGEX = Regex("""(note_|event_|timer_|task_|reminder_)[a-zA-Z0-9]+""")
        private val TOOL_RESULT_HEADERS = mapOf(
            "web_search" to 500,
            "web_scrape" to 1000,
            "manage_notes" to 300,
            "generate_image" to 200,
            "code_interpreter" to 800,
            "manage_calendar" to 500,
            "search_past_chats" to 500,
        )
    }

    fun shouldCompact(messages: List<ChatMessage>): Boolean {
        if (messages.size <= 5) return false
        val totalTokens = messages.sumOf { tokenizer.estimateTokenCountInMessage(it) }
        return totalTokens >= compactTrigger || messages.size > 30
    }

    fun plan(
        messages: List<ChatMessage>,
        personality: String? = null,
    ): CompactionPlan {
        val config = personalityConfig(personality)
        val preserved = mutableListOf<ChatMessage>()
        val distilled = mutableListOf<ChatMessage>()
        val compressed = mutableMapOf<String, String>()
        var droppedCount = 0

        val systemMessages = mutableListOf<ChatMessage>()
        val recentExchanges = mutableListOf<ChatMessage>()

        for (msg in messages) {
            when (classify(msg)) {
                ContentTier.VERBATIM -> preserved.add(msg)
                ContentTier.DISTILLED -> distilled.add(msg)
                ContentTier.COMPRESSED -> {
                    val key = toolResultKey(msg)
                    if (key != null) compressed[key] = truncateToolResult(msg)
                    droppedCount++
                }
                ContentTier.DROPPED -> droppedCount++
            }
        }

        val entityIds = extractEntityIds(messages)
        val state = buildConversationState(messages, entityIds)
        val summary = buildHistorySummary(distilled)
        val preservedTrimmed = trimToVerbatimLimit(preserved, config.verbatimExchanges)

        return CompactionPlan(
            preservedMessages = preservedTrimmed,
            conversationState = state,
            historySummary = summary,
            compressedToolResults = compressed,
            droppedCount = droppedCount,
        )
    }

    fun execute(plan: CompactionPlan): List<ChatMessage> {
        val compacted = mutableListOf<ChatMessage>()

        val stateMessage = SystemMessage(
            """
[Conversation State]
${plan.conversationState}

[History Summary]
${plan.historySummary}

[Tool Results]
${plan.compressedToolResults.entries.joinToString("\n") { "${it.key} → ${it.value}" }}
            """.trimIndent()
        )
        compacted.add(stateMessage)
        compacted.addAll(plan.preservedMessages)

        return compacted
    }

    private fun classify(msg: ChatMessage): ContentTier {
        return when (msg.type()) {
            ChatMessageType.SYSTEM -> ContentTier.VERBATIM
            ChatMessageType.USER -> ContentTier.VERBATIM
            ChatMessageType.AI -> {
                val ai = msg as? AiMessage ?: return ContentTier.VERBATIM
                when {
                    ai.text()?.startsWith("I've asked you") == true -> ContentTier.DROPPED
                    ai.text().isNullOrBlank() && !ai.hasToolExecutionRequests() -> ContentTier.DROPPED
                    ai.hasToolExecutionRequests() -> ContentTier.COMPRESSED
                    else -> ContentTier.VERBATIM
                }
            }
            ChatMessageType.TOOL_EXECUTION_RESULT -> {
                val toolMsg = msg as? ToolExecutionResultMessage ?: return ContentTier.DISTILLED
                when {
                    toolMsg.text().contains("\"success\":false") -> ContentTier.DROPPED
                    toolMsg.text().length > 500 -> ContentTier.COMPRESSED
                    else -> ContentTier.DISTILLED
                }
            }
            else -> ContentTier.DISTILLED
        }
    }

    private fun extractEntityIds(messages: List<ChatMessage>): Set<String> {
        val ids = mutableSetOf<String>()
        for (msg in messages) {
            val text = when (msg) {
                is AiMessage -> msg.text() ?: ""
                is UserMessage -> msg.singleText()
                is ToolExecutionResultMessage -> msg.text()
                else -> ""
            }
            ENTITY_ID_REGEX.findAll(text).forEach { ids.add(it.value) }
        }
        return ids
    }

    private fun buildConversationState(
        messages: List<ChatMessage>,
        entityIds: Set<String>,
    ): String {
        val lastUserMsg = messages.lastOrNull { it.type() == ChatMessageType.USER }
        val lastUserText = (lastUserMsg as? UserMessage)?.singleText() ?: "Unknown"

        val pendingAskUser = messages.any {
            it is AiMessage && it.toolExecutionRequests()?.any { req ->
                req.name() == "askUser"
            } == true
        }

        val toolCalls = messages.count { it is AiMessage && (it as AiMessage).hasToolExecutionRequests() }

        return buildString {
            appendLine("Status: $lastUserText")
            if (entityIds.isNotEmpty()) {
                appendLine("Entity IDs: ${entityIds.joinToString(", ")}")
            }
            if (pendingAskUser) {
                appendLine("Pending: awaiting user response")
            }
            appendLine("Tool calls this session: $toolCalls")
        }
    }

    private fun buildHistorySummary(distilled: List<ChatMessage>): String {
        if (distilled.isEmpty()) return "No previous context to summarize."

        val lines = mutableListOf<String>()
        for (msg in distilled) {
            when (msg) {
                is UserMessage -> lines.add("User: ${truncate(msg.singleText(), 100)}")
                is AiMessage -> {
                    val text = msg.text()
                    if (!text.isNullOrBlank()) {
                        lines.add("Assistant: ${truncate(text, 100)}")
                    }
                    msg.toolExecutionRequests()?.forEach { req ->
                        lines.add("→ Tool call: ${req.name()}(${truncate(req.arguments(), 50)})")
                    }
                }
                is ToolExecutionResultMessage -> {
                    lines.add("← Tool result: ${msg.toolName()} → ${truncate(msg.text(), 80)}")
                }
            }
        }
        return lines.joinToString("\n")
    }

    private fun toolResultKey(msg: ChatMessage): String? {
        if (msg is ToolExecutionResultMessage) {
            return "${msg.toolName()}:${msg.id()}"
        }
        return null
    }

    private fun truncateToolResult(msg: ChatMessage): String {
        if (msg is ToolExecutionResultMessage) {
            val maxLen = TOOL_RESULT_HEADERS[msg.toolName()] ?: 500
            return truncate(msg.text(), maxLen)
        }
        return truncate(msg.toString(), 500)
    }

    private fun trimToVerbatimLimit(
        messages: List<ChatMessage>,
        maxExchanges: Int,
    ): List<ChatMessage> {
        val systemMsgs = messages.filter { it.type() == ChatMessageType.SYSTEM }
        val nonSystem = messages.filter { it.type() != ChatMessageType.SYSTEM }
        val trimmed = nonSystem.takeLast(maxExchanges * 2) // user + assistant per exchange
        return systemMsgs + trimmed
    }

    private fun personalityConfig(personality: String?): PersonalityConfig {
        return when (personality?.uppercase()) {
            "CONCISE" -> PersonalityConfig(0.6, 3, "aggressive")
            "DETAILED" -> PersonalityConfig(1.5, 10, "light")
            "PROFESSIONAL" -> PersonalityConfig(1.0, 5, "normal")
            "CASUAL" -> PersonalityConfig(1.2, 7, "emotional")
            else -> PersonalityConfig(1.0, 5, "normal")
        }
    }

    private fun truncate(text: String, maxLen: Int): String {
        return if (text.length <= maxLen) text else text.take(maxLen) + "… (truncated)"
    }
}
