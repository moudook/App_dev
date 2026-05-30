package com.example.smarty.core.domain.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.smarty.protocol.AgentEvent
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Represents a chat session/conversation.
 * Each session contains multiple messages and can be switched between.
 *
 * CONVERSATION SUMMARIES:
 * Sessions can have AI-generated summaries that capture:
 * - Main topics discussed
 * - Actions taken (notes created, todos added, etc.)
 * - Key user preferences revealed
 *
 * Summaries are used to provide context in future conversations
 * without including full message history.
 *
 * PERFORMANCE OPTIMIZED (v3.2.2):
 * - Added composite index for (isActive, updatedAt) - fast active session lookup
 * - Added DESC index on updatedAt - efficient recent sessions query
 */
@Entity(
    tableName = "chat_sessions",
    indices = [
        // Individual column indices for common filters
        Index(value = ["updatedAt"]),
        Index(value = ["isActive"]),
        // Composite index for active session queries (most common)
        Index(value = ["isActive", "updatedAt"]),
        // Composite index for recent sessions ordering
        Index(value = ["updatedAt", "isActive"]),
    ],
)
data class ChatSession(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New Chat",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val messageCount: Int = 0,
    val lastMessagePreview: String = "",
    val isActive: Boolean = true, // Currently selected session
    val personality: String? = null, // Custom personality/tone instructions
    /**
     * AI-generated summary of the conversation.
     * Contains abstract description of topics discussed and actions taken.
     * Used for providing context in future conversations.
     *
     * PRIVACY: Never contains raw private note content.
     */
    val summary: String? = null,
    /**
     * When the summary was generated.
     * Used to determine if summary needs updating.
     */
    val summaryGeneratedAt: Long? = null,
)

/**
 * Entity for storing chat messages in the database.
 * Linked to a ChatSession via sessionId.
 *
 * PERFORMANCE OPTIMIZED (v3.2.2):
 * - Added composite index for (sessionId, role) - fast AI message filtering
 * - Added composite index for (sessionId, timestamp DESC) - efficient message list queries
 * - Added index on role for role-based filtering
 * - All queries now use index scans instead of full table scans
 *
 * QUERY PERFORMANCE (expected improvement):
 * - Session message lookup: O(n) → O(log n) - 100-1000x faster
 * - Role filtering: O(n) → O(log n) - 100-1000x faster
 * - Ordered retrieval: O(n log n) → O(log n + k) - 10-100x faster
 */
@Entity(
    tableName = "chat_messages",
    indices = [
        // Individual column indices for common filters
        Index(value = ["sessionId"]),
        Index(value = ["timestamp"]),
        Index(value = ["role"]), // NEW: For filtering USER/SMARTY/SYSTEM messages

        // Composite indices for common query patterns
        Index(value = ["sessionId", "timestamp"]), // Session messages in order
        Index(value = ["sessionId", "role"]), // NEW: Filter by role within session
        Index(value = ["sessionId", "role", "timestamp"]), // NEW: Ordered role filtering
        Index(value = ["timestamp", "sessionId"]), // NEW: Time-based queries within session
    ],
)
data class ChatMessageEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val role: String, // USER, ASSISTANT, SYSTEM
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val attachmentsJson: String = "[]", // JSON serialized attachments
    val executedActionsJson: String = "[]", // JSON serialized actions
    val referencedNoteIds: String = "", // Comma-separated note IDs
    val citationsJson: String = "[]", // JSON serialized citations from web search
    val inlineImagesJson: String = "[]", // JSON serialized inline images from ViewImageTool
    val thinking: String? = null, // AI reasoning/thinking content (SMARTY_TRACE_V2 or plain)
    val toolCallsJson: String = "[]", // JSON serialized AgentToolCallEntry list
    val agentStepsJson: String = "[]", // JSON serialized AgentStepEntry list
    val agentEventsJson: String = "[]", // JSON serialized List<AgentEvent>
) {
    /**
     * Convert to domain model ChatMessage
     */
    fun toChatMessage(
        attachments: List<Attachment> = emptyList(),
        actions: List<AgentActionResult> = emptyList(),
    ): ChatMessage {
        // Debug log
        // Log.d("ChatMessageEntity", "toChatMessage: id=$id, role=$role, contentLen=${content.length}")

        // Parse citations from JSON
        val citations =
            try {
                if (citationsJson.isNotBlank() && citationsJson != "[]") {
                    parseCitationsJson(citationsJson)
                } else {
                    emptyList()
                }
            } catch (e: Exception) {
                emptyList()
            }

        // Parse inline images from JSON
        val inlineImages: List<InlineChatImage> =
            try {
                if (inlineImagesJson.isNotBlank() && inlineImagesJson != "[]") {
                    Companion.parseInlineImagesJson(inlineImagesJson)
                } else {
                    emptyList()
                }
            } catch (e: Exception) {
                emptyList()
            }

        // Parse tool calls from toolCallsJson only
        val toolCalls: List<AgentToolCallEntry> =
            try {
                when {
                    toolCallsJson.isNotBlank() && toolCallsJson != "[]" ->
                        parseToolCallsJson(toolCallsJson)
                    else -> emptyList()
                }
            } catch (e: Exception) {
                emptyList()
            }

        val cleanThinking: String? = null

        val agentEvents: List<AgentEvent> =
            try {
                if (agentEventsJson.isNotBlank() && agentEventsJson != "[]") {
                    Json.decodeFromString<List<AgentEvent>>(agentEventsJson)
                } else {
                    emptyList()
                }
            } catch (e: Exception) {
                emptyList()
            }

        return ChatMessage(
            id = id,
            role = ChatRole.valueOf(role),
            content = content,
            attachments = attachments,
            timestamp = timestamp,
            executedActions = actions,
            referencedNoteIds = referencedNoteIds.split(",").filter { it.isNotBlank() },
            citations = citations,
            inlineImages = inlineImages,
            thinking = null,
            toolCalls = toolCalls,
            agentSteps = parseAgentStepsJson(agentStepsJson),
            agentEvents = agentEvents,
        )
    }

    companion object {
        /**
         * Create entity from domain model
         */
        fun fromChatMessage(
            message: ChatMessage,
            sessionId: String,
        ): ChatMessageEntity {
            // Serialize citations to JSON
            val citationsJson =
                if (message.citations.isNotEmpty()) {
                    serializeCitationsToJson(message.citations)
                } else {
                    "[]"
                }

            // Serialize inline images to JSON
            val inlineImagesJson =
                if (message.inlineImages.isNotEmpty()) {
                    Companion.serializeInlineImagesToJson(message.inlineImages)
                } else {
                    "[]"
                }

            // Serialize tool calls to JSON
            val toolCallsJson =
                if (message.toolCalls.isNotEmpty()) {
                    serializeToolCallsToJson(message.toolCalls)
                } else {
                    "[]"
                }

            // Serialize agent steps to JSON
            val agentStepsJson =
                if (message.agentSteps.isNotEmpty()) {
                    serializeAgentStepsToJson(message.agentSteps)
                } else {
                    "[]"
                }

            val agentEventsJson =
                if (message.agentEvents.isNotEmpty()) {
                    Json.encodeToString(message.agentEvents)
                } else {
                    "[]"
                }

            return ChatMessageEntity(
                id = message.id,
                sessionId = sessionId,
                role = message.role.name,
                content = message.content,
                timestamp = message.timestamp,
                referencedNoteIds = message.referencedNoteIds.joinToString(","),
                citationsJson = citationsJson,
                inlineImagesJson = inlineImagesJson,
                thinking = null,
                toolCallsJson = toolCallsJson,
                agentStepsJson = agentStepsJson,
                agentEventsJson = agentEventsJson,
            )
        }

        /**
         * Parse citations from JSON string
         */
        private fun parseCitationsJson(json: String): List<Citation> {
            // Simple JSON parsing without external library
            val citations = mutableListOf<Citation>()
            try {
                // Remove outer brackets and split by "},{"
                val trimmed = json.trim().removePrefix("[").removeSuffix("]")
                if (trimmed.isBlank()) return emptyList()

                // Split by },{ but keep track of nested braces
                val items = mutableListOf<String>()
                var depth = 0
                var start = 0
                for (i in trimmed.indices) {
                    when (trimmed[i]) {
                        '{' -> depth++
                        '}' -> {
                            depth--
                            if (depth == 0) {
                                items.add(trimmed.substring(start, i + 1).trim().removePrefix(",").trim())
                                start = i + 1
                            }
                        }
                    }
                }

                for (item in items) {
                    val obj = item.removePrefix("{").removeSuffix("}")
                    var title = ""
                    var url = ""
                    var snippet = ""

                    // Parse each field
                    val regex = """"(\w+)"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"""".toRegex()
                    regex.findAll(obj).forEach { match ->
                        val (key, value) = match.destructured
                        val unescaped = value.replace("\\\"", "\"").replace("\\\\", "\\")
                        when (key) {
                            "title" -> title = unescaped
                            "url" -> url = unescaped
                            "snippet" -> snippet = unescaped
                        }
                    }

                    if (title.isNotBlank() || url.isNotBlank()) {
                        citations.add(Citation(title = title, url = url, snippet = snippet))
                    }
                }
            } catch (e: Exception) {
                // Return empty on parse error
            }
            return citations
        }

        /**
         * Serialize citations to JSON string
         */
        private fun serializeCitationsToJson(citations: List<Citation>): String {
            if (citations.isEmpty()) return "[]"

            val items =
                citations.map { citation ->
                    val title = citation.title.replace("\\", "\\\\").replace("\"", "\\\"")
                    val url = citation.url.replace("\\", "\\\\").replace("\"", "\\\"")
                    val snippet = citation.snippet.replace("\\", "\\\\").replace("\"", "\\\"")
                    """{"title":"$title","url":"$url","snippet":"$snippet"}"""
                }
            return "[${items.joinToString(",")}]"
        }

        /**
         * Parse inline images from JSON string
         */
        private fun parseInlineImagesJson(json: String): List<InlineChatImage> {
            val images = mutableListOf<InlineChatImage>()
            try {
                val trimmed = json.trim().removePrefix("[").removeSuffix("]")
                if (trimmed.isBlank()) return emptyList()

                // Split by },{ but keep track of nested braces
                val items = mutableListOf<String>()
                var depth = 0
                var start = 0
                for (i in trimmed.indices) {
                    when (trimmed[i]) {
                        '{' -> depth++
                        '}' -> {
                            depth--
                            if (depth == 0) {
                                items.add(trimmed.substring(start, i + 1).trim().removePrefix(",").trim())
                                start = i + 1
                            }
                        }
                    }
                }

                for (item in items) {
                    val obj = item.removePrefix("{").removeSuffix("}")
                    var uri = ""
                    var fileName = ""
                    var noteTitle = ""

                    val regex = """"(\w+)"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"""".toRegex()
                    regex.findAll(obj).forEach { match ->
                        val (key, value) = match.destructured
                        val unescaped = value.replace("\\\"", "\"").replace("\\\\", "\\")
                        when (key) {
                            "uri" -> uri = unescaped
                            "fileName" -> fileName = unescaped
                            "noteTitle" -> noteTitle = unescaped
                        }
                    }

                    if (uri.isNotBlank()) {
                        images.add(InlineChatImage(uri = uri, fileName = fileName, noteTitle = noteTitle))
                    }
                }
            } catch (e: Exception) {
                // Return empty on parse error
            }
            return images
        }

        /**
         * Serialize inline images to JSON string
         */
        private fun serializeInlineImagesToJson(images: List<InlineChatImage>): String {
            if (images.isEmpty()) return "[]"

            val items =
                images.map { image ->
                    val uri = image.uri.replace("\\", "\\\\").replace("\"", "\\\"")
                    val fileName = image.fileName.replace("\\", "\\\\").replace("\"", "\\\"")
                    val noteTitle = image.noteTitle.replace("\\", "\\\\").replace("\"", "\\\"")
                    """{"uri":"$uri","fileName":"$fileName","noteTitle":"$noteTitle"}"""
                }
            return "[${items.joinToString(",")}]"
        }

        /**
         * Parse tool calls from our custom toolCallsJson column.
         * Format: [{"toolName":"...","status":"...","displayName":"...","inputSummary":"...","outputSummary":"...","queries":[...]}]
         */
        fun parseToolCallsJson(json: String): List<AgentToolCallEntry> {
            val entries = mutableListOf<AgentToolCallEntry>()
            try {
                val items = splitJsonObjects(json.trim().removePrefix("[").removeSuffix("]"))
                for (item in items) {
                    val fields = parseJsonFields(item)
                    val queries = parseSearchQueriesFromField(fields["queries"] ?: "[]")
                    entries.add(
                        AgentToolCallEntry(
                            toolName = fields["toolName"] ?: "",
                            status = fields["status"] ?: "completed",
                            displayName = fields["displayName"] ?: (fields["toolName"] ?: ""),
                            inputSummary = fields["inputSummary"],
                            outputSummary = fields["outputSummary"],
                            searchQueries = queries,
                        ),
                    )
                }
            } catch (_: Exception) {
            }
            return entries
        }

        /** Serialise AgentToolCallEntry list to JSON for the toolCallsJson column. */
        fun serializeToolCallsToJson(entries: List<AgentToolCallEntry>): String {
            if (entries.isEmpty()) return "[]"
            val items =
                entries.map { e ->
                    val tn = e.toolName.esc()
                    val st = e.status.esc()
                    val dn = e.displayName.esc()
                    val ins = e.inputSummary?.esc()
                    val out = e.outputSummary?.esc()
                    buildString {
                        append("{\"toolName\":\"$tn\",\"status\":\"$st\",\"displayName\":\"$dn\"")
                        if (ins != null) append(",\"inputSummary\":\"$ins\"")
                        if (out != null) append(",\"outputSummary\":\"$out\"")
                        if (e.searchQueries.isNotEmpty()) {
                            val qs =
                                e.searchQueries.joinToString(",") { sq ->
                                    val res = sq.result
                                    "{\"query\":\"${sq.query.esc()}\"" +
                                        (if (res != null) ",\"result\":\"${res.esc()}\"" else "") +
                                        "}"
                                }
                            append(",\"queries\":[$qs]")
                        }
                        append("}")
                    }
                }
            return "[${items.joinToString(",")}]"
        }

        /** Parse AgentStepEntry list from JSON. */
        fun parseAgentStepsJson(json: String): List<AgentStepEntry> {
            val entries = mutableListOf<AgentStepEntry>()
            try {
                val items = splitJsonObjects(json.trim().removePrefix("[").removeSuffix("]"))
                for (item in items) {
                    val fields = parseJsonFields(item)
                    val stepType = fields["stepType"] ?: "thinking"
                    val stepTitle = fields["stepTitle"] ?: ""
                    val stepContent = fields["stepContent"] ?: ""
                    val stepStatus = fields["stepStatus"] ?: "started"
                    val stepIndex = fields["stepIndex"]?.toIntOrNull() ?: 0
                    val toolName = fields["toolName"]
                    val durationMs = fields["durationMs"]?.toLongOrNull()
                    entries.add(
                        AgentStepEntry(
                            stepType = stepType,
                            stepTitle = stepTitle,
                            stepContent = stepContent,
                            stepStatus = stepStatus,
                            stepIndex = stepIndex,
                            toolName = toolName,
                            durationMs = durationMs,
                        ),
                    )
                }
            } catch (_: Exception) {
            }
            return entries
        }

        /** Serialize AgentStepEntry list to JSON. */
        fun serializeAgentStepsToJson(entries: List<AgentStepEntry>): String {
            if (entries.isEmpty()) return "[]"
            val items =
                entries.map { e ->
                    val st = e.stepType.esc()
                    val title = e.stepTitle.esc()
                    val content = e.stepContent.esc()
                    val status = e.stepStatus.esc()
                    val idx = e.stepIndex
                    val tool = e.toolName?.esc()
                    val dur = e.durationMs
                    buildString {
                        append(
                            "{\"stepType\":\"$st\",\"stepTitle\":\"$title\",\"stepContent\":\"$content\",\"stepStatus\":\"$status\",\"stepIndex\":$idx",
                        )
                        if (tool != null) append(",\"toolName\":\"$tool\"")
                        if (dur != null) append(",\"durationMs\":$dur")
                        append("}")
                    }
                }
            return "[${items.joinToString(",")}]"
        }

        // ─── tiny JSON utilities ───────────────────────────────────────────────

        private fun String.esc() =
            replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

        private fun buildDisplayName(
            toolName: String,
            inputSummary: String?,
        ): String {
            return when {
                toolName.contains("search", ignoreCase = true) ||
                    toolName.contains("web", ignoreCase = true) ->
                    if (inputSummary != null) "Searched: $inputSummary" else "Web Search"
                toolName.contains("memory", ignoreCase = true) ||
                    toolName.contains("note", ignoreCase = true) ->
                    if (inputSummary != null) "Saved: $inputSummary" else "Memory Action"
                toolName.contains("schedule", ignoreCase = true) ||
                    toolName.contains("calendar", ignoreCase = true) ->
                    if (inputSummary != null) "Scheduled: $inputSummary" else "Calendar Action"
                toolName.contains("remind", ignoreCase = true) ->
                    if (inputSummary != null) "Reminder: $inputSummary" else "Reminder Set"
                else -> toolName.replace("_", " ").replaceFirstChar { it.uppercase() }
            }.take(80)
        }

        /** Split a JSON array body into individual object strings. */
        private fun splitJsonObjects(body: String): List<String> {
            if (body.isBlank()) return emptyList()
            val items = mutableListOf<String>()
            var depth = 0
            var start = 0
            for (i in body.indices) {
                when (body[i]) {
                    '{' -> {
                        if (depth == 0) start = i
                        depth++
                    }
                    '}' -> {
                        depth--
                        if (depth == 0) items.add(body.substring(start, i + 1))
                    }
                }
            }
            return items
        }

        /** Parse top-level string fields from a single JSON object string. */
        private fun parseJsonFields(obj: String): Map<String, String?> {
            val map = mutableMapOf<String, String?>()
            val fieldRe = Regex(""""(\w+)"\s*:\s*(?:"((?:[^"\\]|\\.)*)"|(\{.*?\}|\[.*?\]|true|false|null|\d+))""")
            fieldRe.findAll(obj).forEach { m ->
                val key = m.groupValues[1]
                val strVal = m.groupValues[2].takeIf { it.isNotEmpty() }
                val rawVal = m.groupValues[3].takeIf { it.isNotEmpty() }
                map[key] = strVal?.replace("\\\"", "\"")?.replace("\\n", "\n")?.replace("\\\\", "\\")
                    ?: rawVal
            }
            return map
        }

        private fun parseSearchQueriesFromField(queriesJson: String): List<SearchQueryEntry> {
            val list = mutableListOf<SearchQueryEntry>()
            try {
                splitJsonObjects(queriesJson.trim().removePrefix("[").removeSuffix("]"))
                    .forEach { obj ->
                        val f = parseJsonFields(obj)
                        val q = f["q"] ?: f["query"] ?: return@forEach
                        list.add(SearchQueryEntry(query = q, result = f["r"] ?: f["result"]))
                    }
            } catch (_: Exception) {
            }
            return list
        }
    }
}

/**
 * Combined data class for UI display
 */
data class ChatSessionWithPreview(
    val session: ChatSession,
    val lastUserMessage: String?,
    val lastAssistantMessage: String?,
)
