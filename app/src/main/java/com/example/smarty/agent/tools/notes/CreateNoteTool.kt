package com.example.smarty.agent.tools.notes

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.example.smarty.agent.tools.base.NoteOperationResult
import com.example.smarty.data.model.Note
import com.example.smarty.data.model.ProcessingStatus
import com.example.smarty.data.repository.CogniRepository
import com.example.smarty.util.ContentTypeDetector
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

@Serializable
data class CreateNoteArgs(
    @property:LLMDescription("The main text content of the note to be saved")
    val content: String,
    @property:LLMDescription("Optional custom title. If not provided, will be auto-generated from content")
    val title: String? = null,
    @property:LLMDescription("Optional category name to organize the note (e.g., 'Work', 'Personal', 'Ideas')")
    val category: String? = null
)

/**
 * Tool for creating new notes.
 * The LLM can use this to save information as notes.
 */
class CreateNoteTool(
    private val repository: CogniRepository,
    private val onProcessNote: suspend (Note) -> Unit
) : Tool<CreateNoteArgs, NoteOperationResult>() {

    override val argsSerializer: KSerializer<CreateNoteArgs> = CreateNoteArgs.serializer()
    override val resultSerializer: KSerializer<NoteOperationResult> = NoteOperationResult.serializer()

    override val name = "create_note"

    override val description = """
        Creates a new note in the user's note collection.
        Use this when the user asks to save, remember, or note down information.
        The AI will analyze and categorize the note unless a category is specified.
    """.trimIndent()

    override suspend fun execute(args: CreateNoteArgs): NoteOperationResult {
        return try {
            if (args.content.isBlank()) {
                return NoteOperationResult(
                    success = false,
                    message = "Content cannot be empty",
                    error = "Empty content"
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
