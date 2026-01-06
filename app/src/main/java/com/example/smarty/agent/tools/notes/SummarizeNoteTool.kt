package com.example.smarty.agent.tools.notes

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.example.smarty.agent.tools.base.CogniToolUtils
import com.example.smarty.agent.tools.base.SummarizeResult
import com.example.smarty.data.repository.CogniRepository
import kotlinx.serialization.Serializable

@Serializable
data class SummarizeNoteArgs(
    @property:LLMDescription("The unique ID of the note to summarize or read in detail")
    val noteId: String
)

/**
 * Tool for getting note content for summarization.
 * Returns the note's full content so the LLM can generate a summary.
 * Respects PrivacyGuard - private notes cannot be summarized.
 */
class SummarizeNoteTool(
    private val repository: CogniRepository
) : Tool<SummarizeNoteArgs, SummarizeResult>(
    argsSerializer = SummarizeNoteArgs.serializer(),
    resultSerializer = SummarizeResult.serializer(),
    name = "summarize_note",
    description = """
        Retrieves the FULL content of a note for reading or summarization.
        Triggers: "Summarize note X", "Read me note Y", "What is inside note Z?".
        Action: Returns title, summary, and full body content.
    """.trimIndent()
) {
    override suspend fun execute(args: SummarizeNoteArgs): SummarizeResult {
        return try {
            val note = CogniToolUtils.getFreshAiAccessibleNote(repository, args.noteId)
                ?: return SummarizeResult(
                    success = false,
                    noteId = args.noteId,
                    title = "",
                    summary = null,
                    content = "",
                    error = "Note not found"  // SECURITY: Generic message - don't leak private note existence
                )

            SummarizeResult(
                success = true,
                noteId = note.id,
                title = note.title,
                summary = note.summary,
                content = note.content
            )
        } catch (e: Exception) {
            SummarizeResult(
                success = false,
                noteId = args.noteId,
                title = "",
                summary = null,
                content = "",
                error = e.message
            )
        }
    }
}
