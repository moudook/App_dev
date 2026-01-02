package com.example.smarty.agent.tools.notes

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.example.smarty.agent.tools.base.CogniToolUtils
import com.example.smarty.agent.tools.base.NoteOperationResult
import com.example.smarty.data.repository.CogniRepository
import kotlinx.serialization.Serializable

@Serializable
data class UpdateNoteArgs(
    @property:LLMDescription("The unique ID of the note to update (obtained from search results)")
    val noteId: String,
    @property:LLMDescription("New title for the note. Leave null to keep existing title")
    val newTitle: String? = null,
    @property:LLMDescription("New content for the note. Leave null to keep existing content")
    val newContent: String? = null,
    @property:LLMDescription("New category name. Leave null to keep existing category")
    val newCategory: String? = null
)

/**
 * Tool for updating existing notes.
 * Respects PrivacyGuard - private notes cannot be modified.
 */
class UpdateNoteTool(
    private val repository: CogniRepository
) : Tool<UpdateNoteArgs, NoteOperationResult>(
    argsSerializer = UpdateNoteArgs.serializer(),
    resultSerializer = NoteOperationResult.serializer(),
    name = "update_note",
    description = """
        Updates an existing note's title, content, or category.
        Use when the user wants to modify a note.
        Private notes cannot be updated.
    """.trimIndent()
) {
    override suspend fun execute(args: UpdateNoteArgs): NoteOperationResult {
        return try {
            val note = CogniToolUtils.getFreshAiAccessibleNote(repository, args.noteId)
                ?: return NoteOperationResult(
                    success = false,
                    message = "Note not found",  // SECURITY: Generic message - don't leak private note existence
                    error = "Note inaccessible"
                )

            var updatedNote = note.copy(
                title = args.newTitle ?: note.title,
                content = args.newContent ?: note.content,
                updatedAt = System.currentTimeMillis()
            )

            if (args.newCategory != null && args.newCategory != note.categoryName) {
                val category = repository.getOrCreateCategory(args.newCategory)
                updatedNote = updatedNote.copy(
                    categoryId = category.id,
                    categoryName = category.name
                )
            }

            repository.updateNote(updatedNote)

            NoteOperationResult(
                success = true,
                noteId = note.id,
                noteTitle = updatedNote.title,
                message = "Note updated successfully"
            )
        } catch (e: Exception) {
            NoteOperationResult(
                success = false,
                message = "Failed to update note",
                error = e.message
            )
        }
    }
}
