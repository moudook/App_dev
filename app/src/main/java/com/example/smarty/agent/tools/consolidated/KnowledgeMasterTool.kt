package com.example.smarty.agent.tools.consolidated

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.example.smarty.data.model.Note
import com.example.smarty.data.model.Category
import com.example.smarty.agent.tools.base.NoteInfo
import com.example.smarty.agent.tools.base.CategoryInfo
import com.example.smarty.viewmodel.managers.SearchResultItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

@Serializable
data class KnowledgeMasterArgs(
    @property:LLMDescription("The intent: 'create_note', 'update_note', 'delete_note', 'archive_note', 'unarchive_note', 'retrieve_notes', 'summarize_note', 'manage_category'")
    val intent: String,
    @property:LLMDescription("The ID of the note or category to target")
    val target_id: String? = null,
    @property:LLMDescription("The content (text), title, or category name")
    val content: String? = null,
    @property:LLMDescription("Metadata: tags, category, title")
    val metadata: KnowledgeMetadata? = null,
    @property:LLMDescription("Filters: date_range, type, category, query")
    val filters: KnowledgeFilters? = null
)

@Serializable
data class KnowledgeMetadata(
    val tags: List<String>? = null,
    val category: String? = null,
    val title: String? = null
)

@Serializable
data class KnowledgeFilters(
    val date_range: String? = null,
    val type: String? = null,
    val category: String? = null,
    val query: String? = null,
    val limit: Int = 10
)

@Serializable
data class KnowledgeResult(
    val success: Boolean,
    val message: String,
    val data: String? = null
) {
    override fun toString(): String {
        return "{success:$success|message:$message|data:${data ?: "null"}}"
    }
}

/**
 * Hybridized Knowledge Master.
 * 100% logic-free. Delegates all logic to NoteOperationsManager and SearchFeatureManager.
 */
class KnowledgeMasterTool(
    private val onAddNote: (String, String?) -> Unit,
    private val onUpdateNote: (String, String?, String?) -> Unit,
    private val onDeleteNote: (String) -> Unit,
    private val onArchiveNote: (String) -> Unit,
    private val onUnarchiveNote: (String) -> Unit,
    private val onSummarizeNote: (String) -> Unit,
    private val onSearchNotes: suspend (String?, String?, String?, String, Int) -> List<SearchResultItem>,
    private val onCreateCategory: suspend (String) -> com.example.smarty.data.model.Category,
    private val onGetCategoryStats: suspend () -> List<com.example.smarty.viewmodel.managers.CategoryStatInfo>,
    private val onStatusUpdate: (String) -> Unit
) : Tool<KnowledgeMasterArgs, KnowledgeResult>(
    argsSerializer = KnowledgeMasterArgs.serializer(),
    resultSerializer = KnowledgeResult.serializer(),
    name = "knowledge_master",
    description = """
        The authority on user content. Handles creating, updating, deleting, retrieving, and organizing notes.

        INTENTS:
        - create_note: content="...", metadata={title="...", category="..."}
        - update_note: target_id="...", content="...", metadata={title="..."}
        - delete_note: target_id="..."
        - archive_note: target_id="..."
        - unarchive_note: target_id="..."
        - retrieve_notes: filters={query="...", category="...", type="audio|image"}
        - summarize_note: target_id="..."
        - manage_category: content="New Category" (to create) or leave null (to list)
    """.trimIndent()
) {
    private val knowledgeJson = Json { encodeDefaults = false }

    override suspend fun execute(args: KnowledgeMasterArgs): KnowledgeResult {
        return try {
            when (args.intent) {
                "create_note" -> {
                    onStatusUpdate("status_saving_note")
                    val content = args.content ?: return KnowledgeResult(false, "error_content_required")
                    onAddNote(content, args.metadata?.category)
                    KnowledgeResult(true, "note_created_success")
                }
                "update_note" -> {
                    val id = args.target_id ?: return KnowledgeResult(false, "error_id_required")
                    onStatusUpdate("status_updating")
                    onUpdateNote(id, args.metadata?.title, args.content)
                    KnowledgeResult(true, "note_updated_success")
                }
                "delete_note" -> {
                    val id = args.target_id ?: return KnowledgeResult(false, "error_id_required")
                    onStatusUpdate("status_deleting")
                    onDeleteNote(id)
                    KnowledgeResult(true, "note_deleted_success")
                }
                "archive_note" -> {
                    val id = args.target_id ?: return KnowledgeResult(false, "error_id_required")
                    onStatusUpdate("status_archiving")
                    onArchiveNote(id)
                    KnowledgeResult(true, "note_archived_success")
                }
                "unarchive_note" -> {
                    val id = args.target_id ?: return KnowledgeResult(false, "error_id_required")
                    onStatusUpdate("status_restoring")
                    onUnarchiveNote(id)
                    KnowledgeResult(true, "note_unarchived_success")
                }
                "retrieve_notes" -> {
                    onStatusUpdate("status_searching")
                    val results = onSearchNotes(
                        args.filters?.query,
                        args.filters?.category,
                        args.filters?.type,
                        args.filters?.date_range ?: "all",
                        args.filters?.limit ?: 10
                    )
                    val noteInfos = results.map {
                        NoteInfo(it.note.id, it.note.title, it.note.content ?: "", it.note.summary, it.note.categoryName, it.note.type.name, it.note.createdAt)
                    }
                    KnowledgeResult(true, "found_notes_count|${results.size}", knowledgeJson.encodeToString(noteInfos))
                }
                "summarize_note" -> {
                    val id = args.target_id ?: return KnowledgeResult(false, "error_id_required")
                    onStatusUpdate("status_summarizing")
                    onSummarizeNote(id)
                    KnowledgeResult(true, "summary_initiated_success")
                }
                "manage_category" -> {
                    if (args.content != null) {
                        onStatusUpdate("status_creating_category")
                        val cat = onCreateCategory(args.content)
                        KnowledgeResult(true, "category_created_success|${cat.name}")
                    } else {
                        onStatusUpdate("status_fetching_categories")
                        val stats = onGetCategoryStats()
                        val categories = stats.map { CategoryInfo(it.name, it.count) }
                        KnowledgeResult(true, "listing_categories_success", knowledgeJson.encodeToString(categories))
                    }
                }
                else -> KnowledgeResult(false, "error_unknown_intent")
            }
        } catch (e: Exception) {
            KnowledgeResult(false, "error_prefix|${e.message}")
        }
    }
}
