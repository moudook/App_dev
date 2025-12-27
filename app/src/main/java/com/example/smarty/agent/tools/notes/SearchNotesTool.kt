package com.example.smarty.agent.tools.notes

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import android.util.Log
import com.example.smarty.agent.tools.base.CogniToolUtils
import com.example.smarty.agent.tools.base.NoteInfo
import com.example.smarty.agent.tools.base.NoteSearchResult
import com.example.smarty.data.model.Note
import com.example.smarty.util.search.SemanticSearchEngine
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

private const val TAG = "SearchNotesTool"

@Serializable
data class SearchNotesArgs(
    @property:LLMDescription("Search keywords to find in note titles, content, or summaries")
    val query: String,
    @property:LLMDescription("Optional category name to filter results (e.g., 'Work', 'Personal')")
    val category: String? = null
)

/**
 * Tool for searching user's notes with SEMANTIC SEARCH.
 * Uses fuzzy matching, phonetic similarity, and n-gram matching.
 * Can find "meeting notes" even if searching for "meetings" or "mtg".
 * Respects PrivacyGuard - private notes are never returned.
 */
class SearchNotesTool(
    private val getActiveNotes: () -> List<Note>
) : Tool<SearchNotesArgs, NoteSearchResult>() {

    override val argsSerializer: KSerializer<SearchNotesArgs> = SearchNotesArgs.serializer()
    override val resultSerializer: KSerializer<NoteSearchResult> = NoteSearchResult.serializer()

    override val name = "search_notes"

    override val description = """
        Searches the user's notes by keyword or category using semantic/fuzzy matching.
        Understands variations like "meetings" matching "meeting notes" or "mtg".
        Use this to find relevant notes based on user queries.
        Private notes are automatically excluded from results.
    """.trimIndent()

    override suspend fun execute(args: SearchNotesArgs): NoteSearchResult {
        return try {
            val allNotes = getActiveNotes()
            val visibleNotes = CogniToolUtils.filterNotesForAi(allNotes)

            Log.d(TAG, "Searching ${visibleNotes.size} notes for: '${args.query}'")

            // Apply category filter first
            val categoryFilteredNotes = if (args.category.isNullOrBlank()) {
                visibleNotes
            } else {
                visibleNotes.filter { note ->
                    note.categoryName?.equals(args.category, ignoreCase = true) == true
                }
            }

            // Use semantic search if query is provided
            val matchingNotes = if (args.query.isBlank()) {
                categoryFilteredNotes.take(10)
            } else {
                // Use SemanticSearchEngine for fuzzy matching
                // Search full content - no limit, so AI can find info anywhere in notes
                val searchResults = SemanticSearchEngine.search(
                    query = args.query,
                    items = categoryFilteredNotes,
                    textExtractor = { note ->
                        listOfNotNull(
                            note.title,
                            note.summary,
                            note.content  // Full content search - find info anywhere in note
                        )
                    },
                    minScore = 0.15  // Lower threshold for more inclusive matching
                )

                if (searchResults.isNotEmpty()) {
                    Log.d(TAG, "Semantic search found ${searchResults.size} matches")
                    searchResults.take(10).map { it.item }
                } else {
                    // Fallback to basic contains matching
                    Log.d(TAG, "No semantic matches, falling back to basic search")
                    categoryFilteredNotes.filter { note ->
                        val queryLower = args.query.lowercase()
                        note.title.lowercase().contains(queryLower) ||
                        note.content.lowercase().contains(queryLower) ||
                        note.summary?.lowercase()?.contains(queryLower) == true
                    }.take(10)
                }
            }

            // Map notes to NoteInfo - ID is for internal operations only
            // AI should present notes by title/content, never expose IDs to user
            val noteInfos = matchingNotes.map { note ->
                NoteInfo(
                    id = note.id,  // Internal use only for update/delete
                    title = note.title,
                    content = note.content.take(500),  // Truncate for token efficiency
                    summary = note.summary,
                    category = note.categoryName,
                    type = note.type.name,
                    createdAt = note.createdAt
                )
            }

            Log.d(TAG, "Returning ${noteInfos.size} results")

            NoteSearchResult(
                success = true,
                notes = noteInfos,
                totalCount = matchingNotes.size,
                message = if (noteInfos.isEmpty()) "No notes found matching '${args.query}'"
                         else "Found ${noteInfos.size} matching notes"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Search failed: ${e.message}", e)
            NoteSearchResult(
                success = false,
                notes = emptyList(),
                totalCount = 0,
                message = "Search failed: ${e.message}"
            )
        }
    }
}
