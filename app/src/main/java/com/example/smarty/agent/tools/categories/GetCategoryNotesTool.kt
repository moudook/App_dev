package com.example.smarty.agent.tools.categories

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.example.smarty.agent.tools.base.NoteInfo
import com.example.smarty.agent.tools.base.NoteSearchResult
import com.example.smarty.data.cache.ToolResultCache
import com.example.smarty.data.model.Note
import com.example.smarty.util.PrivacyGuard
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class GetCategoryNotesArgs(
    @property:LLMDescription("The exact category name to retrieve notes from (e.g., 'Work', 'Personal')")
    val categoryName: String
)

/**
 * Tool for getting notes in a specific category.
 * Respects PrivacyGuard - private notes are excluded.
 */
class GetCategoryNotesTool(
    private val getActiveNotes: () -> List<Note>
) : Tool<GetCategoryNotesArgs, NoteSearchResult>() {

    override val argsSerializer: KSerializer<GetCategoryNotesArgs> = GetCategoryNotesArgs.serializer()
    override val resultSerializer: KSerializer<NoteSearchResult> = NoteSearchResult.serializer()

    override val name = "get_category_notes"

    override val description = """
        Gets all notes in a specific category.
        Use when the user wants to see notes in a particular category.
        Private notes are automatically excluded.
    """.trimIndent()

    override suspend fun execute(args: GetCategoryNotesArgs): NoteSearchResult {
        // Check cache first to avoid duplicate DB queries
        val cacheKey = ToolResultCache.generateKey(name, args.categoryName)
        val cached = ToolResultCache.get(cacheKey)
        if (cached != null) {
            return Json.decodeFromString(NoteSearchResult.serializer(), cached)
        }

        return try {
            val allNotes = getActiveNotes()
            val visibleNotes = PrivacyGuard.getAiVisibleNotes(allNotes)

            val categoryNotes = visibleNotes.filter { note ->
                note.categoryName?.equals(args.categoryName, ignoreCase = true) == true
            }.take(10) // Limit to save context

            // ID is for internal operations only - AI should never expose to user
            val noteInfos = categoryNotes.map { note ->
                NoteInfo(
                    id = note.id,  // Internal use only
                    title = note.title,
                    content = note.content.take(500),  // Truncate for token efficiency
                    summary = note.summary,
                    category = note.categoryName,
                    type = note.type.name,
                    createdAt = note.createdAt
                )
            }

            val result = NoteSearchResult(
                success = true,
                notes = noteInfos,
                totalCount = categoryNotes.size,
                message = if (noteInfos.isEmpty())
                    "No notes found in category '${args.categoryName}'"
                else
                    "Found ${noteInfos.size} notes in '${args.categoryName}'"
            )

            // Cache the successful result
            ToolResultCache.put(cacheKey, Json.encodeToString(NoteSearchResult.serializer(), result))

            result
        } catch (e: Exception) {
            NoteSearchResult(
                success = false,
                notes = emptyList(),
                totalCount = 0,
                message = "Failed to get category notes: ${e.message}"
            )
        }
    }
}
