package com.example.smarty.agent.tools.batch

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.example.smarty.data.model.Note
import com.example.smarty.data.repository.JarvisRepository
import com.example.smarty.util.PrivacyGuard
import com.example.smarty.util.toon.ToonManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val json = Json { encodeDefaults = false }

@Serializable
data class BatchOperationArgs(
    @property:LLMDescription("Operation type: 'preview' (show matching notes), 'archive' (archive all matches), 'update_category' (move to category)")
    val operation: String,
    @property:LLMDescription("Search query to find notes for batch operation")
    val searchQuery: String,
    @property:LLMDescription("Optional: filter by current category")
    val category: String? = null,
    @property:LLMDescription("Target category for 'update_category' operation")
    val targetCategory: String? = null,
    @property:LLMDescription("Maximum notes to affect (safety limit, default 10, max 25)")
    val maxNotes: Int = 10,
    @property:LLMDescription("Set to true to execute the operation, false to preview only. ALWAYS preview first!")
    val execute: Boolean = false
)

@Serializable
data class BatchNoteInfo(
    val id: String,
    val title: String,
    val category: String?,
    val createdAt: Long
)

@Serializable
data class BatchOperationResult(
    val success: Boolean,
    val operation: String,
    val matchingNotes: List<BatchNoteInfo>,
    val affectedCount: Int,
    val message: String,
    val requiresConfirmation: Boolean = false,
    val error: String? = null
) {
    override fun toString(): String {
        val jsonStr = json.encodeToString(serializer(), this)
        return ToonManager.jsonToToon(jsonStr)
    }
}

/**
 * Tool for batch operations on multiple notes.
 *
 * SECURITY:
 * - Always requires preview before execution for destructive operations
 * - Respects privacy guard - only operates on AI-visible notes
 * - Has safety limit on max notes per operation
 *
 * Operations:
 * - preview: Show matching notes without making changes
 * - archive: Archive all matching notes
 * - update_category: Move all matching notes to a category
 */
class BatchOperationsTool(
    private val repository: JarvisRepository,
    private val getActiveNotes: () -> List<Note>
) : Tool<BatchOperationArgs, BatchOperationResult>(
    argsSerializer = BatchOperationArgs.serializer(),
    resultSerializer = BatchOperationResult.serializer(),
    name = "batch_notes",
    description = """
        Performs bulk actions (Archive, Move Category) on multiple notes matching a query.
        Triggers: "Archive all notes about X", "Move all todo notes to Work category".
        SAFETY: Always run with 'execute=false' (preview) first, then ask for confirmation.
    """.trimIndent()
) {
    override suspend fun execute(args: BatchOperationArgs): BatchOperationResult {
        val allNotes = getActiveNotes()
        val visibleNotes = PrivacyGuard.getAiVisibleNotes(allNotes)
        val safeMaxNotes = args.maxNotes.coerceIn(1, 25)

        // Find matching notes
        val matchingNotes = visibleNotes.filter { note ->
            val matchesQuery = args.searchQuery.isBlank() ||
                    note.title.contains(args.searchQuery, ignoreCase = true) ||
                    note.content.contains(args.searchQuery, ignoreCase = true) ||
                    note.summary?.contains(args.searchQuery, ignoreCase = true) == true

            val matchesCategory = args.category.isNullOrBlank() ||
                    note.categoryName?.equals(args.category, ignoreCase = true) == true

            matchesQuery && matchesCategory
        }.take(safeMaxNotes)

        val noteInfos = matchingNotes.map {
            BatchNoteInfo(
                id = it.id,
                title = it.title,
                category = it.categoryName,
                createdAt = it.createdAt
            )
        }

        // Validate operation
        val operation = args.operation.lowercase()
        if (operation !in listOf("preview", "archive", "update_category")) {
            return BatchOperationResult(
                success = false,
                operation = args.operation,
                matchingNotes = noteInfos,
                affectedCount = 0,
                message = "Unknown operation: ${args.operation}. Use 'preview', 'archive', or 'update_category'.",
                error = "Invalid operation"
            )
        }

        // Preview mode or no matches - return list without changes
        if (operation == "preview" || !args.execute || matchingNotes.isEmpty()) {
            return BatchOperationResult(
                success = true,
                operation = operation,
                matchingNotes = noteInfos,
                affectedCount = 0,
                message = when {
                    matchingNotes.isEmpty() -> "No notes found matching '${args.searchQuery}'"
                    operation == "preview" -> "Found ${matchingNotes.size} notes matching '${args.searchQuery}'. Review the list and set execute=true to proceed."
                    !args.execute -> "Found ${matchingNotes.size} notes. Set execute=true to proceed with ${operation}."
                    else -> "Preview complete."
                },
                requiresConfirmation = matchingNotes.isNotEmpty() && operation != "preview"
            )
        }

        // Execute operation
        var affectedCount = 0
        var skippedPrivate = 0
        try {
            when (operation) {
                "archive" -> {
                    matchingNotes.forEach { note ->
                        // SECURITY FIX: Re-verify privacy before each operation
                        // Note could have been marked private between preview and execute
                        val freshNote = repository.getNoteById(note.id)
                        if (freshNote != null && PrivacyGuard.canAiProcess(freshNote)) {
                            repository.archiveNote(note.id)
                            affectedCount++
                        } else {
                            skippedPrivate++
                        }
                    }
                }

                "update_category" -> {
                    if (args.targetCategory.isNullOrBlank()) {
                        return BatchOperationResult(
                            success = false,
                            operation = operation,
                            matchingNotes = noteInfos,
                            affectedCount = 0,
                            message = "targetCategory is required for update_category operation",
                            error = "Missing targetCategory"
                        )
                    }

                    val category = repository.getOrCreateCategory(args.targetCategory)
                    matchingNotes.forEach { note ->
                        // SECURITY FIX: Re-verify privacy before each operation
                        val freshNote = repository.getNoteById(note.id)
                        if (freshNote != null && PrivacyGuard.canAiProcess(freshNote)) {
                            repository.updateNoteCategory(note.id, category.id, category.name)
                            affectedCount++
                        } else {
                            skippedPrivate++
                        }
                    }
                }
            }

            val skippedMsg = if (skippedPrivate > 0) " ($skippedPrivate notes skipped - now private)" else ""
            return BatchOperationResult(
                success = true,
                operation = operation,
                matchingNotes = noteInfos,
                affectedCount = affectedCount,
                message = when (operation) {
                    "archive" -> "Successfully archived $affectedCount notes$skippedMsg"
                    "update_category" -> "Successfully moved $affectedCount notes to '${args.targetCategory}'$skippedMsg"
                    else -> "Operation completed on $affectedCount notes$skippedMsg"
                }
            )
        } catch (e: Exception) {
            return BatchOperationResult(
                success = false,
                operation = operation,
                matchingNotes = noteInfos,
                affectedCount = affectedCount,
                message = "Operation partially failed after $affectedCount notes: ${e.message}",
                error = e.message
            )
        }
    }

    override fun toString(): String {
        return "BatchOperationsTool - Bulk operations on multiple notes"
    }
}
