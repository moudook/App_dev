package com.example.smarty.agent.tools.notes

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.example.smarty.agent.tools.base.CogniToolUtils
import com.example.smarty.agent.tools.base.NoteInfo
import com.example.smarty.agent.tools.base.NoteSearchResult
import com.example.smarty.data.model.Note
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

@Serializable
data class SearchNotesArgs(
    @property:LLMDescription("Search keywords to find in note titles, content, or summaries")
    val query: String,
    @property:LLMDescription("Optional category name to filter results (e.g., 'Work', 'Personal')")
    val category: String? = null
)

/**
 * Tool for searching user's notes.
 * Respects PrivacyGuard - private notes are never returned.
 */
class SearchNotesTool(
    private val getActiveNotes: () -> List<Note>
) : Tool<SearchNotesArgs, NoteSearchResult>() {

    override val argsSerializer: KSerializer<SearchNotesArgs> = SearchNotesArgs.serializer()
    override val resultSerializer: KSerializer<NoteSearchResult> = NoteSearchResult.serializer()

    override val name = "search_notes"

    override val description = """
        Searches the user's notes by keyword or category.
        Use this to find relevant notes based on user queries.
        Private notes are automatically excluded from results.
    """.trimIndent()

    override suspend fun execute(args: SearchNotesArgs): NoteSearchResult {
        return try {
            val allNotes = getActiveNotes()
            val visibleNotes = CogniToolUtils.filterNotesForAi(allNotes)

            val matchingNotes = visibleNotes.filter { note ->
                val matchesQuery = args.query.isBlank() ||
                    note.title.contains(args.query, ignoreCase = true) ||
                    note.content.contains(args.query, ignoreCase = true) ||
                    note.summary?.contains(args.query, ignoreCase = true) == true

                val matchesCategory = args.category.isNullOrBlank() ||
                    note.categoryName?.equals(args.category, ignoreCase = true) == true

                matchesQuery && matchesCategory
            }.take(10) // Limit results to save context

            val noteInfos = matchingNotes.map { note ->
                NoteInfo(
                    id = note.id,
                    title = note.title,
                    summary = note.summary,
                    category = note.categoryName,
                    type = note.type.name,
                    createdAt = note.createdAt
                )
            }

            NoteSearchResult(
                success = true,
                notes = noteInfos,
                totalCount = matchingNotes.size,
                message = if (noteInfos.isEmpty()) "No notes found"
                         else "Found ${noteInfos.size} matching notes"
            )
        } catch (e: Exception) {
            NoteSearchResult(
                success = false,
                notes = emptyList(),
                totalCount = 0,
                message = "Search failed: ${e.message}"
            )
        }
    }
}
