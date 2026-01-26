package com.example.smarty.agent.tools.consolidated

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.example.smarty.util.toon.ToonManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val json = Json { encodeDefaults = false }

@Serializable
data class BatchOperationArgs(
    @property:LLMDescription("Operation type: 'preview' (show matching notes), 'archive' (archive all matches), 'update_category' (move to category), 'delete' (delete all matches)")
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
 * Hybridized: Logic delegated to NoteOperationsManager and SearchFeatureManager via callbacks.
 */
class BatchOperationsTool(
    private val onSearchNotes: suspend (String?, String?, String?, String, Int) -> List<com.example.smarty.viewmodel.managers.SearchResultItem>,
    private val onBulkArchive: (List<String>) -> Unit,
    private val onBulkDelete: (List<String>) -> Unit,
    private val onBulkMove: (List<String>, String) -> Unit,
    private val onStatusUpdate: (String) -> Unit
) : Tool<BatchOperationArgs, BatchOperationResult>(
    argsSerializer = BatchOperationArgs.serializer(),
    resultSerializer = BatchOperationResult.serializer(),
    name = "batch_notes",
    description = """
        Performs bulk actions (Archive, Move Category, Delete) on multiple notes matching a query.
        Triggers: "Archive all notes about X", "Move all todo notes to Work category".
        SAFETY: Always run with 'execute=false' (preview) first, then ask for confirmation.
    """.trimIndent()
) {
    override suspend fun execute(args: BatchOperationArgs): BatchOperationResult {
        return try {
            val safeMaxNotes = args.maxNotes.coerceIn(1, 25)

            onStatusUpdate("Finding matching notes...")
            // Find matching notes using the central search manager
            val results = onSearchNotes(args.searchQuery, args.category, null, "all", safeMaxNotes)

            val noteInfos = results.map {
                BatchNoteInfo(
                    id = it.note.id,
                    title = it.note.title,
                    category = it.note.categoryName,
                    createdAt = it.note.createdAt
                )
            }

            // Validate operation
            val operation = args.operation.lowercase()
            val validOps = listOf("preview", "archive", "update_category", "delete")
            if (operation !in validOps) {
                return BatchOperationResult(
                    success = false,
                    operation = args.operation,
                    matchingNotes = noteInfos,
                    affectedCount = 0,
                    message = "Unknown operation: ${args.operation}. Use ${validOps.joinToString(", ")}.",
                    error = "Invalid operation"
                )
            }

            // Preview mode or no matches
            if (operation == "preview" || !args.execute || results.isEmpty()) {
                return BatchOperationResult(
                    success = true,
                    operation = operation,
                    matchingNotes = noteInfos,
                    affectedCount = 0,
                    message = when {
                        results.isEmpty() -> "No notes found matching '${args.searchQuery}'"
                        operation == "preview" -> "Found ${results.size} notes matching '${args.searchQuery}'. Review the list and set execute=true to proceed."
                        !args.execute -> "Found ${results.size} notes. Set execute=true to proceed with ${operation}."
                        else -> "Preview complete."
                    },
                    requiresConfirmation = results.isNotEmpty() && operation != "preview"
                )
            }

            // Execute operation via manager
            onStatusUpdate("Executing batch $operation...")
            val ids = results.map { it.note.id }
            when (operation) {
                "archive" -> onBulkArchive(ids)
                "delete" -> onBulkDelete(ids)
                "update_category" -> {
                    val target = args.targetCategory ?: return BatchOperationResult(
                        success = false,
                        operation = operation,
                        matchingNotes = noteInfos,
                        affectedCount = 0,
                        message = "targetCategory is required for update_category operation",
                        error = "Missing targetCategory"
                    )
                    onBulkMove(ids, target)
                }
            }

            BatchOperationResult(
                success = true,
                operation = operation,
                matchingNotes = noteInfos,
                affectedCount = ids.size,
                message = "Successfully initiated $operation on ${ids.size} notes."
            )
        } catch (e: Exception) {
            BatchOperationResult(
                success = false,
                operation = args.operation,
                matchingNotes = emptyList(),
                affectedCount = 0,
                message = "Batch operation failed: ${e.message}",
                error = e.message
            )
        }
    }

    override fun toString(): String {
        return "BatchOperationsTool - Bulk operations on multiple notes"
    }
}
