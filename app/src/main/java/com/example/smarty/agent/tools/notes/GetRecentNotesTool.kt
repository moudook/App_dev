package com.example.smarty.agent.tools.notes

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import android.util.Log
import com.example.smarty.agent.tools.base.NoteInfo
import com.example.smarty.data.model.Note
import com.example.smarty.data.model.getAttachments
import com.example.smarty.util.PrivacyGuard
import com.example.smarty.util.toon.ToonManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val json = Json { encodeDefaults = false }
private const val TAG = "GetRecentNotesTool"

@Serializable
data class GetRecentNotesArgs(
    @property:LLMDescription("Number of recent notes to return (default: 5, max: 20)")
    val limit: Int = 5,
    @property:LLMDescription("Optional: filter by note type (BRAIN_DUMP, AUDIO, IMAGE, DOCUMENT, etc). Leave empty for all types.")
    val noteType: String? = null,
    @property:LLMDescription("Optional: filter by category name")
    val category: String? = null
)

@Serializable
data class RecentNoteInfo(
    val id: String,
    val title: String,
    val summary: String?,
    val category: String?,
    val type: String,
    val createdAt: Long,
    val hasAttachments: Boolean,
    val attachmentTypes: List<String>  // e.g., ["audio", "document", "image"]
)

@Serializable
data class GetRecentNotesResult(
    val success: Boolean,
    val notes: List<RecentNoteInfo>,
    val totalCount: Int,
    val message: String
) {
    override fun toString(): String {
        val jsonStr = json.encodeToString(serializer(), this)
        return ToonManager.jsonToToon(jsonStr)
    }
}

/**
 * Tool for getting the most recent notes, sorted by creation time (newest first).
 *
 * USE THIS TOOL when user asks for:
 * - "latest note/notecard"
 * - "most recent note"
 * - "newest note"
 * - "what did I add recently"
 * - "show my recent notes"
 *
 * This tool returns notes sorted by createdAt DESC, ensuring the truly latest
 * notes are returned regardless of content or type.
 */
class GetRecentNotesTool(
    private val getActiveNotes: () -> List<Note>
) : Tool<GetRecentNotesArgs, GetRecentNotesResult>(
    argsSerializer = GetRecentNotesArgs.serializer(),
    resultSerializer = GetRecentNotesResult.serializer(),
    name = "get_recent_notes",
    description = """
        MUST USE THIS TOOL when user asks for "latest", "most recent", "newest" notes.
        Gets notes sorted by creation time (newest first).

        This is the CORRECT tool for:
        - "show my latest note" → get_recent_notes(limit=1)
        - "what's my most recent notecard" → get_recent_notes(limit=1)
        - "show recent notes" → get_recent_notes(limit=5)
        - "latest document note" → get_recent_notes(limit=1, noteType="DOCUMENT")

        DO NOT use search_notes for "latest" queries - use THIS tool instead!
    """.trimIndent()
) {
    override suspend fun execute(args: GetRecentNotesArgs): GetRecentNotesResult {
        return try {
            val allNotes = PrivacyGuard.getAiVisibleNotes(getActiveNotes())
            Log.d(TAG, "Getting recent notes from ${allNotes.size} total notes")

            // Apply type filter if specified
            val typeFilteredNotes = if (!args.noteType.isNullOrBlank()) {
                allNotes.filter { note ->
                    note.type.name.equals(args.noteType, ignoreCase = true)
                }
            } else {
                allNotes
            }

            // Apply category filter if specified
            val categoryFilteredNotes = if (!args.category.isNullOrBlank()) {
                typeFilteredNotes.filter { note ->
                    note.categoryName?.equals(args.category, ignoreCase = true) == true
                }
            } else {
                typeFilteredNotes
            }

            // Sort by createdAt DESC (newest first) and take the limit
            val safeLimit = args.limit.coerceIn(1, 20)
            val recentNotes = categoryFilteredNotes
                .sortedByDescending { it.createdAt }
                .take(safeLimit)

            Log.d(TAG, "Found ${recentNotes.size} recent notes (limit: $safeLimit)")

            // Map to RecentNoteInfo with attachment details
            val noteInfos = recentNotes.map { note ->
                val attachments = note.getAttachments()
                val attachmentTypes = mutableSetOf<String>()

                // Categorize attachments
                attachments.forEach { att ->
                    when {
                        att.mimeType.startsWith("audio/") -> attachmentTypes.add("audio")
                        att.mimeType.startsWith("image/") -> attachmentTypes.add("image")
                        att.mimeType.startsWith("video/") -> attachmentTypes.add("video")
                        att.mimeType.startsWith("application/pdf") -> attachmentTypes.add("document")
                        att.mimeType.startsWith("application/") -> attachmentTypes.add("document")
                        att.mimeType.startsWith("text/") -> attachmentTypes.add("document")
                    }
                }

                // Also check legacy single file attachment
                note.fileMimeType?.let { mimeType ->
                    when {
                        mimeType.startsWith("audio/") -> attachmentTypes.add("audio")
                        mimeType.startsWith("image/") -> attachmentTypes.add("image")
                        mimeType.startsWith("video/") -> attachmentTypes.add("video")
                        mimeType.startsWith("application/pdf") -> attachmentTypes.add("document")
                        mimeType.startsWith("application/") -> attachmentTypes.add("document")
                        mimeType.startsWith("text/") -> attachmentTypes.add("document")
                    }
                }

                RecentNoteInfo(
                    id = note.id,
                    title = note.title,
                    summary = note.summary?.take(150),
                    category = note.categoryName,
                    type = note.type.name,
                    createdAt = note.createdAt,
                    hasAttachments = attachments.isNotEmpty() || note.fileUri != null,
                    attachmentTypes = attachmentTypes.toList()
                )
            }

            val filterDescription = buildString {
                if (!args.noteType.isNullOrBlank()) append(" of type ${args.noteType}")
                if (!args.category.isNullOrBlank()) append(" in category ${args.category}")
            }

            val message = when {
                noteInfos.isEmpty() -> "No notes found$filterDescription"
                noteInfos.size == 1 -> "Here is your latest note$filterDescription"
                else -> "Here are your ${noteInfos.size} most recent notes$filterDescription"
            }

            GetRecentNotesResult(
                success = noteInfos.isNotEmpty(),
                notes = noteInfos,
                totalCount = noteInfos.size,
                message = message
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting recent notes: ${e.message}", e)
            GetRecentNotesResult(
                success = false,
                notes = emptyList(),
                totalCount = 0,
                message = "Error getting recent notes: ${e.message}"
            )
        }
    }
}
