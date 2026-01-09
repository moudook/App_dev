package com.example.smarty.agent.tools.notes

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.example.smarty.agent.tools.base.NoteOperationResult
import com.example.smarty.data.model.Note
import com.example.smarty.data.model.ProcessingStatus
import com.example.smarty.data.repository.JarvisRepository
import com.example.smarty.util.ContentTypeDetector
import kotlinx.serialization.Serializable

@Serializable
data class CreateNoteArgs(
    @property:LLMDescription("The main text content of the note to be saved")
    val content: String,
    @property:LLMDescription("A short, descriptive title for the note (2-5 words). ALWAYS provide a meaningful title that summarizes the note content. Examples: 'Meeting Notes', 'Shopping List', 'Project Ideas', 'Birthday Reminder'")
    val title: String? = null,
    @property:LLMDescription("Optional category name to organize the note (e.g., 'Work', 'Personal', 'Ideas')")
    val category: String? = null
)

/**
 * Tool for creating new notes.
 * The LLM can use this to save information as notes.
 */
class CreateNoteTool(
    private val repository: JarvisRepository,
    private val onProcessNote: suspend (Note) -> Unit
) : Tool<CreateNoteArgs, NoteOperationResult>(
    argsSerializer = CreateNoteArgs.serializer(),
    resultSerializer = NoteOperationResult.serializer(),
    name = "create_note",
    description = """ONLY use when user says "create note" or "save this". Do NOT use for chat responses.""".trimIndent()
) {
    override suspend fun execute(args: CreateNoteArgs): NoteOperationResult {
        return try {
            // Robust validation for content
            val contentStr = args.content.trim()
            if (contentStr.isBlank() || 
                contentStr.equals("null", ignoreCase = true) || 
                contentStr.equals("EMPTY_RESULT", ignoreCase = true)) {
                return NoteOperationResult(
                    success = false,
                    message = "I didn't find any meaningful content to save as a note.",
                    error = "Null or empty content provided"
                )
            }

            val detectedType = ContentTypeDetector.detectContentType(args.content)
            val title = args.title ?: ContentTypeDetector.extractTitle(args.content, detectedType)

            val note = Note(
                title = title,
                content = args.content,
                type = detectedType,
                processingStatus = if (args.category != null)
                    ProcessingStatus.COMPLETED else ProcessingStatus.PROCESSING,
                isAiCreated = true
            )

            repository.insertNote(note)

            if (args.category != null) {
                val category = repository.getOrCreateCategory(args.category)
                repository.updateNote(note.copy(
                    categoryId = category.id,
                    categoryName = category.name,
                    processingStatus = ProcessingStatus.COMPLETED
                ))
            } else {
                onProcessNote(note)
            }

            NoteOperationResult(
                success = true,
                noteId = note.id,
                noteTitle = title,
                message = "Note created successfully"
            )
        } catch (e: Exception) {
            NoteOperationResult(
                success = false,
                message = "Failed to create note",
                error = e.message
            )
        }
    }
}
