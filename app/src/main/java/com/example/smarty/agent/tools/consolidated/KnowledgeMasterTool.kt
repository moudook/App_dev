package com.example.smarty.agent.tools.consolidated

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.example.smarty.agent.tools.base.NoteInfo
import com.example.smarty.agent.tools.base.CategoryInfo
import com.example.smarty.data.model.Note
import com.example.smarty.data.model.NoteType
import com.example.smarty.data.model.ProcessingStatus
import com.example.smarty.data.repository.JarvisRepository
import com.example.smarty.util.ContentTypeDetector
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.util.UUID

@Serializable
data class KnowledgeMasterArgs(
    @property:LLMDescription("The intent of the operation: 'create_note', 'update_note', 'delete_note', 'retrieve_notes', 'summarize_note', 'manage_category'")
    val intent: String,
    @property:LLMDescription("The ID of the note or category to target (optional)")
    val target_id: String? = null,
    @property:LLMDescription("The content for the note (text/base64), title, or category name")
    val content: String? = null,
    @property:LLMDescription("Metadata for the note: tags, category, media_type, title")
    val metadata: KnowledgeMetadata? = null,
    @property:LLMDescription("Filters for retrieval: date_range, type, category, query")
    val filters: KnowledgeFilters? = null
)

@Serializable
data class KnowledgeMetadata(
    val tags: List<String>? = null,
    val category: String? = null,
    val media_type: String? = null, // audio, image, text
    val title: String? = null
)

@Serializable
data class KnowledgeFilters(
    val date_range: String? = null,
    val type: String? = null, // audio, image, text
    val category: String? = null,
    val query: String? = null, // Search query
    val limit: Int = 10
)

@Serializable
data class KnowledgeResult(
    val success: Boolean,
    val message: String,
    val data: String? = null // JSON string of the result data (NoteOperationResult, NoteSearchResult, etc.)
) {
    override fun toString(): String {
        return "{success:$success|message:$message|data:${data ?: "null"}}"
    }
}

class KnowledgeMasterTool(
    private val repository: JarvisRepository,
    private val onProcessNote: suspend (Note) -> Unit,
    private val getActiveNotes: () -> List<Note>,
    private val getArchivedNotes: () -> List<Note>,
    private val getCategories: () -> List<com.example.smarty.data.model.Category>,
    private val onStatusUpdate: (String) -> Unit
) : Tool<KnowledgeMasterArgs, KnowledgeResult>(
    argsSerializer = KnowledgeMasterArgs.serializer(),
    resultSerializer = KnowledgeResult.serializer(),
    name = "knowledge_master",
    description = """
        The absolute authority on user content. Handles creating, updating, deleting, retrieving, searching, and categorizing notes (text, audio, images).
        
        INTENTS:
        - create_note: Create a new note. usage: content="...", metadata={title="...", category="..."}
        - update_note: Update an existing note. usage: target_id="...", content="...", metadata={title="..."} (or set status='archived' to archive)
        - delete_note: Delete a note. usage: target_id="..."
        - retrieve_notes: Search or list notes. usage: filters={query="...", category="...", type="audio|image"}
        - summarize_note: Summarize a note. usage: target_id="..."
        - manage_category: List or create categories. usage: content="New Category Name" (to create) or leave content null (to list).
    """.trimIndent()
) {
    private val knowledgeJson = Json { encodeDefaults = false }

    override suspend fun execute(args: KnowledgeMasterArgs): KnowledgeResult {
        return try {
            when (args.intent) {
                "create_note" -> {
                    onStatusUpdate("Saving note...")
                    createNote(args)
                }
                "update_note" -> {
                    onStatusUpdate("Updating note...")
                    updateNote(args)
                }
                "delete_note" -> {
                    onStatusUpdate("Deleting note...")
                    deleteNote(args)
                }
                "retrieve_notes" -> {
                    onStatusUpdate("Searching notes...")
                    retrieveNotes(args)
                }
                "summarize_note" -> {
                    onStatusUpdate("Summarizing note...")
                    summarizeNote(args)
                }
                "manage_category" -> {
                    onStatusUpdate("Managing categories...")
                    manageCategory(args)
                }
                else -> KnowledgeResult(false, "Unknown intent: ${args.intent}")
            }
        } catch (e: Exception) {
            KnowledgeResult(false, "Error: ${e.message}")
        }
    }

    private suspend fun createNote(args: KnowledgeMasterArgs): KnowledgeResult {
        val contentStr = args.content?.trim()
        if (contentStr.isNullOrBlank()) {
            return KnowledgeResult(false, "Content cannot be empty for create_note")
        }

        val detectedType = ContentTypeDetector.detectContentType(contentStr)
        val title = args.metadata?.title ?: ContentTypeDetector.extractTitle(contentStr, detectedType)
        val categoryName = args.metadata?.category

        val note = Note(
            id = UUID.randomUUID().toString(),
            title = title,
            content = contentStr,
            type = detectedType,
            processingStatus = if (categoryName != null) ProcessingStatus.COMPLETED else ProcessingStatus.PROCESSING,
            isAiCreated = true
        )

        repository.insertNote(note)

        if (categoryName != null) {
            val category = repository.getOrCreateCategory(categoryName)
            repository.updateNote(note.copy(
                categoryId = category.id,
                categoryName = category.name,
                processingStatus = ProcessingStatus.COMPLETED
            ))
        } else {
            onProcessNote(note)
        }

        return KnowledgeResult(true, "Note created successfully", knowledgeJson.encodeToString(mapOf("id" to note.id, "title" to title)))
    }

    private suspend fun updateNote(args: KnowledgeMasterArgs): KnowledgeResult {
        val noteId = args.target_id ?: return KnowledgeResult(false, "target_id required for update_note")
        val notes = getActiveNotes() + getArchivedNotes() // Check all notes
        val note = notes.find { it.id == noteId } ?: return KnowledgeResult(false, "Note not found: $noteId")

        var updatedNote = note
        
        if (args.content != null) updatedNote = updatedNote.copy(content = args.content)
        if (args.metadata?.title != null) updatedNote = updatedNote.copy(title = args.metadata.title)
        
        repository.updateNote(updatedNote)
        
        return KnowledgeResult(true, "Note updated successfully", knowledgeJson.encodeToString(mapOf("id" to note.id, "title" to updatedNote.title)))
    }

    private suspend fun deleteNote(args: KnowledgeMasterArgs): KnowledgeResult {
        val noteId = args.target_id ?: return KnowledgeResult(false, "target_id required for delete_note")
        val notes = getActiveNotes() + getArchivedNotes()
        val note = notes.find { it.id == noteId } ?: return KnowledgeResult(false, "Note not found: $noteId")
        repository.deleteNote(note)
        return KnowledgeResult(true, "Note deleted successfully")
    }

    private suspend fun retrieveNotes(args: KnowledgeMasterArgs): KnowledgeResult {
        val query = args.filters?.query
        val category = args.filters?.category
        val type = args.filters?.type
        val activeNotes = getActiveNotes()

        var results = activeNotes

        if (!query.isNullOrBlank()) {
             results = results.filter { 
                 it.title.contains(query, ignoreCase = true) || 
                 it.content?.contains(query, ignoreCase = true) == true 
             }
        }

        if (!category.isNullOrBlank()) {
            results = results.filter { it.categoryName?.equals(category, ignoreCase = true) == true }
        }

        if (!type.isNullOrBlank()) {
            val noteType = try { NoteType.valueOf(type.uppercase()) } catch(e: Exception) { NoteType.DOCUMENT }
            results = results.filter { it.type == noteType }
        }

        // Limit results
        val limit = args.filters?.limit ?: 10
        results = results.take(limit)

        val noteInfos = results.map { 
            NoteInfo(it.id, it.title, it.content ?: "", it.summary, it.categoryName, it.type.name, it.createdAt) 
        }

        return KnowledgeResult(true, "Found ${results.size} notes", knowledgeJson.encodeToString(noteInfos))
    }

    private suspend fun summarizeNote(args: KnowledgeMasterArgs): KnowledgeResult {
        val noteId = args.target_id ?: return KnowledgeResult(false, "target_id required for summarize_note")
        val note = getActiveNotes().find { it.id == noteId } ?: return KnowledgeResult(false, "Note not found")

        onProcessNote(note)
        
        return KnowledgeResult(true, "Note queued for summarization. The summary will be available shortly.", knowledgeJson.encodeToString(mapOf("id" to note.id)))
    }

    private suspend fun manageCategory(args: KnowledgeMasterArgs): KnowledgeResult {
        if (args.content != null) {
            // Create category
            val category = repository.getOrCreateCategory(args.content)
            return KnowledgeResult(true, "Category '${category.name}' created/retrieved", knowledgeJson.encodeToString(CategoryInfo(category.name, 0)))
        } else {
            // List categories
            val categories = getCategories().map { cat ->
                val count = getActiveNotes().count { it.categoryId == cat.id }
                CategoryInfo(cat.name, count)
            }
            return KnowledgeResult(true, "Found ${categories.size} categories", knowledgeJson.encodeToString(categories))
        }
    }
}