package com.example.smarty.agent.tools.memory

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.example.smarty.data.local.AIMemoryDao
import com.example.smarty.data.model.AIMemory
import com.example.smarty.data.model.MemoryType
import com.example.smarty.util.toon.ToonManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val json = Json { encodeDefaults = false }

/**
 * =============================================================================
 * MANAGE MEMORY TOOL
 * =============================================================================
 *
 * Allows the AI to read, add, update, and delete user memories.
 * Memories help personalize AI responses based on learned user preferences.
 *
 * PRIVACY RULES (CRITICAL):
 * - NEVER store private note content
 * - NEVER store passwords or sensitive credentials
 * - NEVER store calendar event details
 * - Only store abstract preferences and patterns
 *
 * =============================================================================
 */

@Serializable
data class ManageMemoryArgs(
    @property:LLMDescription("Action to perform: 'get' (retrieve all memories), 'add' (create new memory), 'update' (modify existing), 'delete' (remove memory)")
    val action: String,

    @property:LLMDescription("Memory ID - required for 'update' and 'delete' actions")
    val memoryId: String? = null,

    @property:LLMDescription("Memory type: 'preference' (user preferences like 'prefers concise responses'), 'pattern' (behavioral like 'asks about work on Mondays'), 'style' (communication like 'uses informal language'), 'fact' (general like 'user is a developer')")
    val type: String? = null,

    @property:LLMDescription("Content of the memory - abstract description only, NEVER include private note content or sensitive data")
    val content: String? = null,

    @property:LLMDescription("Confidence level 0.0-1.0 (default 1.0). Lower if unsure about the memory")
    val confidence: Float = 1.0f,

    @property:LLMDescription("Optional: what triggered this learning (e.g., 'User explicitly stated preference')")
    val source: String? = null
)

@Serializable
data class MemorySummary(
    val id: String,
    val type: String,
    val content: String,
    val confidence: Float,
    val usageCount: Int
)

@Serializable
data class ManageMemoryResult(
    val success: Boolean,
    val message: String,
    val memories: List<MemorySummary> = emptyList()
) {
    override fun toString(): String {
        val jsonStr = json.encodeToString(serializer(), this)
        return ToonManager.jsonToToon(jsonStr)
    }
}

class ManageMemoryTool(
    private val aiMemoryDao: AIMemoryDao
) : Tool<ManageMemoryArgs, ManageMemoryResult>(
    argsSerializer = ManageMemoryArgs.serializer(),
    resultSerializer = ManageMemoryResult.serializer(),
    name = "manage_memory",
    description = """
        Manage persistent user memories for personalization.
        Use 'get' to retrieve all stored memories about the user.
        Use 'add' to store a NEW preference, pattern, or fact about the user.
        Use 'update' to modify an existing memory's content or confidence.
        Use 'delete' to remove an incorrect or outdated memory.
        
        WHEN TO USE:
        - User says "remember that I..." → add a new memory
        - User contradicts a previous preference → update or delete the memory
        - You need context about user preferences → get memories first
        
        PRIVACY RULES:
        - ONLY store abstract preferences (e.g., "User prefers bullet points")
        - NEVER store private note content
        - NEVER store passwords or sensitive data
    """.trimIndent()
) {
    override suspend fun execute(args: ManageMemoryArgs): ManageMemoryResult {
        return when (args.action.lowercase()) {
            "get" -> getMemories()
            "add" -> addMemory(args)
            "update" -> updateMemory(args)
            "delete" -> deleteMemory(args)
            else -> ManageMemoryResult(
                success = false,
                message = "Unknown action: ${args.action}. Use 'get', 'add', 'update', or 'delete'."
            )
        }
    }

    private suspend fun getMemories(): ManageMemoryResult {
        val memories = aiMemoryDao.getRecentMemories(limit = 50)
        
        if (memories.isEmpty()) {
            return ManageMemoryResult(
                success = true,
                message = "No memories stored yet. Start learning about the user through conversation.",
                memories = emptyList()
            )
        }

        val summaries = memories.map { mem ->
            MemorySummary(
                id = mem.id,
                type = mem.type.name.lowercase(),
                content = mem.content,
                confidence = mem.confidence,
                usageCount = mem.usageCount
            )
        }

        return ManageMemoryResult(
            success = true,
            message = "Found ${memories.size} memories about the user.",
            memories = summaries
        )
    }

    private suspend fun addMemory(args: ManageMemoryArgs): ManageMemoryResult {
        val content = args.content
            ?: return ManageMemoryResult(false, "Content is required to add a memory.")

        val type = parseMemoryType(args.type)
            ?: return ManageMemoryResult(false, "Invalid memory type: ${args.type}. Use: preference, pattern, style, or fact.")

        // Privacy check - reject potentially sensitive content
        if (containsSensitiveContent(content)) {
            return ManageMemoryResult(
                success = false,
                message = "Cannot store this memory - it may contain sensitive information. Only abstract preferences should be stored."
            )
        }

        // Check for duplicates
        if (aiMemoryDao.memoryExists(content, type)) {
            return ManageMemoryResult(
                success = false,
                message = "A similar memory already exists."
            )
        }

        val memory = AIMemory(
            type = type,
            content = content,
            confidence = args.confidence.coerceIn(0.1f, 1.0f),
            source = args.source
        )

        aiMemoryDao.insertMemory(memory)

        return ManageMemoryResult(
            success = true,
            message = "Memory added: $content"
        )
    }

    private suspend fun updateMemory(args: ManageMemoryArgs): ManageMemoryResult {
        val memoryId = args.memoryId
            ?: return ManageMemoryResult(false, "Memory ID is required for update.")

        val existing = aiMemoryDao.getMemoryById(memoryId)
            ?: return ManageMemoryResult(false, "Memory not found with ID: $memoryId")

        val newContent = args.content ?: existing.content
        
        // Privacy check
        if (containsSensitiveContent(newContent)) {
            return ManageMemoryResult(
                success = false,
                message = "Cannot update - new content may contain sensitive information."
            )
        }

        val newType = args.type?.let { parseMemoryType(it) } ?: existing.type

        val updated = existing.copy(
            type = newType,
            content = newContent,
            confidence = args.confidence.coerceIn(0.1f, 1.0f),
            source = args.source ?: existing.source,
            lastUsedAt = System.currentTimeMillis()
        )

        aiMemoryDao.updateMemory(updated)

        return ManageMemoryResult(
            success = true,
            message = "Memory updated: $newContent"
        )
    }

    private suspend fun deleteMemory(args: ManageMemoryArgs): ManageMemoryResult {
        val memoryId = args.memoryId
            ?: return ManageMemoryResult(false, "Memory ID is required for delete.")

        val existing = aiMemoryDao.getMemoryById(memoryId)
            ?: return ManageMemoryResult(false, "Memory not found with ID: $memoryId")

        aiMemoryDao.deleteMemory(existing)

        return ManageMemoryResult(
            success = true,
            message = "Memory deleted: ${existing.content}"
        )
    }

    private fun parseMemoryType(type: String?): MemoryType? {
        return when (type?.lowercase()) {
            "preference" -> MemoryType.PREFERENCE
            "pattern" -> MemoryType.PATTERN
            "style" -> MemoryType.STYLE
            "fact" -> MemoryType.FACT
            else -> null
        }
    }

    /**
     * Check if content might contain sensitive information.
     * Very conservative - rejects anything that looks like private data.
     */
    private fun containsSensitiveContent(content: String): Boolean {
        val lower = content.lowercase()
        val sensitivePatterns = listOf(
            "password", "secret", "api key", "token",
            "credit card", "card number", "cvv", "expiry",
            "social security", "ssn", "bank account",
            "meeting with", "appointment at", "scheduled at",
            "private note", "confidential"
        )
        return sensitivePatterns.any { pattern -> lower.contains(pattern) }
    }

    override fun toString(): String {
        return "ManageMemoryTool - Read/add/update/delete user memories for personalization"
    }
}
