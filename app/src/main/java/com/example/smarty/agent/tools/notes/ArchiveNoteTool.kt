package com.example.smarty.agent.tools.notes

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.example.smarty.agent.tools.base.CogniToolUtils
import com.example.smarty.agent.tools.base.NoteOperationResult
import com.example.smarty.data.model.Note
import com.example.smarty.data.repository.CogniRepository
import kotlinx.serialization.Serializable

@Serializable
data class ArchiveNoteArgs(
    @property:LLMDescription("The unique ID of the note to archive (use search_notes first)")
    val noteId: String? = null,
    @property:LLMDescription("Text description to find and archive a note by matching title/content")
    val description: String? = null
)

/**
 * Tool for archiving notes.
 * Respects PrivacyGuard - private notes cannot be archived by AI.
 */
class ArchiveNoteTool(
    private val repository: CogniRepository,
    private val getActiveNotes: () -> List<Note>,
    private val findNoteByDescription: suspend (String, List<Note>) -> Note?
) : Tool<ArchiveNoteArgs, NoteOperationResult>(
    argsSerializer = ArchiveNoteArgs.serializer(),
    resultSerializer = NoteOperationResult.serializer(),
    name = "archive_note",
    description = """
        Moves a note to the Archive, hiding it from main lists but keeping it searchable.
        Triggers: "Archive note X", "Hide this note", "Move to archive".
        Different from Delete: Archived notes can be restored. Deleted notes are gone.
    """.trimIndent()
) {
    override suspend fun execute(args: ArchiveNoteArgs): NoteOperationResult {
        return try {
            val noteToArchive = when {
                args.noteId != null -> CogniToolUtils.getFreshAiAccessibleNote(repository, args.noteId)
                args.description != null -> {
                    val notes = CogniToolUtils.filterNotesForAiModification(getActiveNotes())
                    findNoteByDescription(args.description, notes)?.let {
                        CogniToolUtils.getFreshAiAccessibleNote(repository, it.id)
                    }
                }
                else -> null
            }

            if (noteToArchive == null) {
                return NoteOperationResult(
                    success = false,
                    message = "Note not found",  // SECURITY: Generic message - don't leak private note existence
                    error = "Note inaccessible"
                )
            }

            repository.archiveNote(noteToArchive.id)

            NoteOperationResult(
                success = true,
                noteId = noteToArchive.id,
                noteTitle = noteToArchive.title,
                message = "Note '${noteToArchive.title}' archived"
            )
        } catch (e: Exception) {
            NoteOperationResult(
                success = false,
                message = "Failed to archive note",
                error = e.message
            )
        }
    }
}
