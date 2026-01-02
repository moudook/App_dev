package com.example.smarty.agent.tools.external

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import android.util.Log
import com.example.smarty.agent.tools.base.NoteOperationResult
import com.example.smarty.data.model.Note
import com.example.smarty.data.model.NoteType
import com.example.smarty.data.model.ProcessingStatus
import com.example.smarty.data.repository.CogniRepository
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class SaveScreenArgs(
    @property:LLMDescription("Title for the saved screen note (optional, will be auto-generated if not provided)")
    val title: String? = null,
    @property:LLMDescription("Description or context about what's on the screen")
    val description: String? = null,
    @property:LLMDescription("Tags to categorize this screen capture")
    val tags: List<String> = emptyList()
)

/**
 * Tool for saving screen context as a note.
 *
 * When user says "save this screen" or "capture this", this tool:
 * 1. Captures any available screen context (app context, selected text, etc.)
 * 2. Saves it as a note for later reference
 *
 * Note: Full screenshot capture requires MediaProjection API which needs
 * additional user permission. This tool captures available context data.
 */
class SaveScreenTool(
    private val repository: CogniRepository,
    private val getScreenContext: () -> ScreenContext?
) : Tool<SaveScreenArgs, NoteOperationResult>(
    argsSerializer = SaveScreenArgs.serializer(),
    resultSerializer = NoteOperationResult.serializer(),
    name = "save_screen",
    description = """
        Saves the current screen context as a note.
        Use when user wants to save/capture/remember what's on screen.

        Examples:
        - "save this screen" -> saves current screen context
        - "capture this" -> saves current screen
        - "remember what I'm looking at" -> saves screen context
        - "save this page" -> saves current screen content

        The saved note will include:
        - Source app name
        - Any selected text
        - Screen context description
        - Timestamp
    """.trimIndent()
) {
    companion object {
        private const val TAG = "SaveScreenTool"
    }

    override suspend fun execute(args: SaveScreenArgs): NoteOperationResult {
        Log.d(TAG, "Saving screen context")

        return try {
            val screenContext = getScreenContext()

            if (screenContext == null) {
                return NoteOperationResult(
                    success = false,
                    message = "No screen context available to save"
                )
            }

            // Build note content from screen context
            val content = buildString {
                screenContext.selectedText?.let {
                    if (it.isNotBlank()) {
                        appendLine("**Selected Text:**")
                        appendLine(it)
                        appendLine()
                    }
                }

                screenContext.referringApp?.let {
                    appendLine("**Source App:** $it")
                }

                screenContext.capturedAt?.let {
                    val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                        .format(java.util.Date(it))
                    appendLine("**Captured:** $timestamp")
                }

                args.description?.let {
                    appendLine()
                    appendLine("**Notes:**")
                    appendLine(it)
                }

                // Add context data if available
                screenContext.contextData?.let { data ->
                    if (data.isNotEmpty()) {
                        appendLine()
                        appendLine("**Context:**")
                        data.forEach { (key, value) ->
                            appendLine("- $key: $value")
                        }
                    }
                }
            }

            // Generate title
            val title = args.title ?: run {
                val appName = screenContext.referringApp ?: "Screen"
                val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date())
                "Captured from $appName at $time"
            }

            // Create the note
            val note = Note(
                id = UUID.randomUUID().toString(),
                title = title,
                content = content,
                type = NoteType.BRAIN_DUMP,  // Use BRAIN_DUMP for text-based notes
                processingStatus = ProcessingStatus.PENDING,
                tagsJson = if (args.tags.isNotEmpty()) {
                    com.google.gson.Gson().toJson(args.tags)
                } else null,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )

            repository.insertNote(note)
            Log.d(TAG, "Screen context saved as note: ${note.id}")

            NoteOperationResult(
                success = true,
                noteId = note.id,
                noteTitle = title,
                message = "Screen saved as note: $title"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error saving screen: ${e.message}", e)
            NoteOperationResult(
                success = false,
                message = "Failed to save screen: ${e.message}"
            )
        }
    }
}

/**
 * Represents captured screen context when assistant is triggered.
 */
data class ScreenContext(
    val selectedText: String?,
    val referringApp: String?,
    val capturedAt: Long?,
    val contextData: Map<String, String>? = null,
    val imageUri: String? = null  // For future screenshot support
)
