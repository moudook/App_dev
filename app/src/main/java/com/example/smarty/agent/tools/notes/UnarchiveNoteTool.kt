package com.example.smarty.agent.tools.notes

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.example.smarty.agent.tools.base.JarvisToolUtils
import com.example.smarty.agent.tools.base.NoteOperationResult
import com.example.smarty.data.model.Note
import com.example.smarty.data.repository.JarvisRepository
import kotlinx.serialization.Serializable

@Serializable
data class UnarchiveNoteArgs(
    @property:LLMDescription("The unique ID of the archived note to restore")
    val noteId: String? = null,
    @property:LLMDescription("Text description to find and restore an archived note")
    val description: String? = null
)

/**
 * Tool for unarchiving notes.
 * Respects PrivacyGuard - private notes cannot be unarchived by AI.
 */
class UnarchiveNoteTool(
    private val repository: JarvisRepository,
    private val getArchivedNotes: () -> List<Note>,
    private val findNoteByDescription: suspend (String, List<Note>) -> Note?
) : Tool<UnarchiveNoteArgs, NoteOperationResult>(
    argsSerializer = UnarchiveNoteArgs.serializer(),
    resultSerializer = NoteOperationResult.serializer(),
    name = "unarchive_note",
    description = """
        Restores a note from the Archive back to the main list.
        Triggers: "Unarchive note X", "Bring back note Y", "Restore the note about Z".
    """.trimIndent()
) {
    override suspend fun execute(args: UnarchiveNoteArgs): NoteOperationResult {
        return try {
            val noteToUnarchive = when {
                args.noteId != null -> JarvisToolUtils.getFreshAiAccessibleNote(repository, args.noteId)
                args.description != null -> {
                    val notes = JarvisToolUtils.filterNotesForAiModification(getArchivedNotes())
                    findNoteByDescription(args.description, notes)?.let {
                        JarvisToolUtils.getFreshAiAccessibleNote(repository, it.id)
                    }
                }
                else -> null
            }

            if (noteToUnarchive == null) {
                return NoteOperationResult(
                    success = false,
                    message = "Note not found",  // SECURITY: Generic message - don't leak private note existence
                    error = "Note inaccessible"
                )
            }

            repository.unarchiveNote(noteToUnarchive.id)

            NoteOperationResult(
                success = true,
                noteId = noteToUnarchive.id,
                noteTitle = noteToUnarchive.title,
                message = "Note '${noteToUnarchive.title}' restored from archive"
            )
        } catch (e: Exception) {
            NoteOperationResult(
                success = false,
                message = "Failed to unarchive note",
                error = e.message
            )
        }
    }
}
