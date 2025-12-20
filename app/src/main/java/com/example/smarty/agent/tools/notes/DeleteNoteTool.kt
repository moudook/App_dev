package com.example.smarty.agent.tools.notes

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.example.smarty.agent.tools.base.CogniToolUtils
import com.example.smarty.agent.tools.base.NoteOperationResult
import com.example.smarty.data.model.Note
import com.example.smarty.data.repository.CogniRepository
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

@Serializable
data class DeleteNoteArgs(
    @property:LLMDescription("The unique ID of the note to delete (use search_notes first to find the ID)")
    val noteId: String? = null,
    @property:LLMDescription("Text description to find and delete a note by matching title/content")
    val description: String? = null
)

/**
 * Tool for deleting notes.
 * Respects PrivacyGuard - private notes cannot be deleted by AI.
 */
class DeleteNoteTool(
    private val repository: CogniRepository,
    private val getActiveNotes: () -> List<Note>,
    private val findNoteByDescription: suspend (String, List<Note>) -> Note?
) : Tool<DeleteNoteArgs, NoteOperationResult>() {

    override val argsSerializer: KSerializer<DeleteNoteArgs> = DeleteNoteArgs.serializer()
    override val resultSerializer: KSerializer<NoteOperationResult> = NoteOperationResult.serializer()

    override val name = "delete_note"

    override val description = """
        Deletes a note from the user's collection.
        Use when the user asks to remove or delete a note.
        Can specify by noteId or description.
        Private notes cannot be deleted.
    """.trimIndent()

    override suspend fun execute(args: DeleteNoteArgs): NoteOperationResult {
        return try {
            val noteToDelete = when {
                args.noteId != null -> CogniToolUtils.getFreshAiAccessibleNote(repository, args.noteId)
                args.description != null -> {
                    val notes = CogniToolUtils.filterNotesForAiModification(getActiveNotes())
                    findNoteByDescription(args.description, notes)?.let {
                        CogniToolUtils.getFreshAiAccessibleNote(repository, it.id)
                    }
                }
                else -> null
            }

            if (noteToDelete == null) {
                return NoteOperationResult(
                    success = false,
                    message = "Note not found",  // SECURITY: Generic message - don't leak private note existence
                    error = "Note inaccessible"
                )
            }

            repository.deleteNote(noteToDelete)

            NoteOperationResult(
                success = true,
                noteId = noteToDelete.id,
                noteTitle = noteToDelete.title,
                message = "Note '${noteToDelete.title}' deleted"
            )
        } catch (e: Exception) {
            NoteOperationResult(
                success = false,
                message = "Failed to delete note",
                error = e.message
            )
        }
    }
}
