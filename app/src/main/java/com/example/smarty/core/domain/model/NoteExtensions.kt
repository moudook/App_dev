package com.example.smarty.core.domain.model

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

// Cached Gson instance for performance
private object GsonHolder {
    val instance: Gson = Gson()
    val todoListType = object : TypeToken<List<TodoItem>>() {}.type!!
    val attachmentListType = object : TypeToken<List<NoteAttachment>>() {}.type!!
    val tagsListType = object : TypeToken<List<String>>() {}.type!!
    val chunkAnalysisListType = object : TypeToken<List<ChunkAnalysis>>() {}.type!!
}

/**
 * Extension function to parse todos from JSON (uses cached Gson instance)
 */
fun Note.getTodos(): List<TodoItem> {
    if (todoContent.isNullOrBlank()) return emptyList()
    return try {
        GsonHolder.instance.fromJson(todoContent, GsonHolder.todoListType) ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }
}

/**
 * Extension function to create a new Note with updated todos (uses cached Gson instance)
 */
fun Note.withTodos(todos: List<TodoItem>): Note {
    val json = if (todos.isEmpty()) null else GsonHolder.instance.toJson(todos)
    return copy(
        todoContent = json,
        updatedAt = System.currentTimeMillis(),
    )
}

/**
 * Extension function to parse attachments from JSON
 */
fun Note.getAttachments(): List<NoteAttachment> {
    if (attachmentsJson.isNullOrBlank()) return emptyList()
    return try {
        GsonHolder.instance.fromJson(attachmentsJson, GsonHolder.attachmentListType) ?: emptyList()
    } catch (e: Exception) {
        // CRITICAL: Log JSON parsing failures - this can cause audio to be "invisible"
        android.util.Log.e("Note", " ATTACHMENT JSON PARSE FAILED for note ${id.take(8)}: ${e.message}")
        android.util.Log.d("Note", "Raw JSON: ${attachmentsJson?.take(200)}")
        emptyList()
    }
}

/**
 * Extension function to create a new Note with updated attachments
 */
fun Note.withAttachments(attachments: List<NoteAttachment>): Note {
    val json = if (attachments.isEmpty()) null else GsonHolder.instance.toJson(attachments)
    return copy(
        attachmentsJson = json,
        updatedAt = System.currentTimeMillis(),
    )
}

/**
 * OPTIMIZED: Get total attachment count without full JSON deserialization.
 * Uses fast string counting instead of parsing entire attachment list.
 *
 * Performance: O(n) string scan vs O(n*k) full deserialization
 * where k = fields per attachment object.
 */
fun Note.getAttachmentCount(): Int {
    // Check multiple attachments first (fast path using string counting)
    if (!attachmentsJson.isNullOrBlank()) {
        // Count occurrences of '"id":' which appears once per attachment object
        // This is ~10x faster than full JSON parsing for count-only operations
        val count = attachmentsJson!!.windowed(5, 1).count { it == "\"id\":" }
        if (count > 0) return count
    }

    // Fall back to legacy single attachment check
    return if (imageUri != null || fileUri != null) 1 else 0
}

/**
 * Extension function to parse tags from JSON
 */
fun Note.getTags(): List<String> {
    if (tagsJson.isNullOrBlank()) return emptyList()
    return try {
        GsonHolder.instance.fromJson(tagsJson, GsonHolder.tagsListType) ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }
}

/**
 * Extension function to create a new Note with updated tags
 */
fun Note.withTags(tags: List<String>): Note {
    val json = if (tags.isEmpty()) null else GsonHolder.instance.toJson(tags)
    return copy(
        tagsJson = json,
        updatedAt = System.currentTimeMillis(),
    )
}

/**
 * Extension function to parse chunk analyses from JSON
 */
fun Note.getChunkAnalyses(): List<ChunkAnalysis> {
    if (chunkAnalysesJson.isNullOrBlank()) return emptyList()
    return try {
        GsonHolder.instance.fromJson(chunkAnalysesJson, GsonHolder.chunkAnalysisListType) ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }
}

/**
 * Extension function to create a new Note with updated chunk analyses
 */
fun Note.withChunkAnalyses(analyses: List<ChunkAnalysis>): Note {
    val json = if (analyses.isEmpty()) null else GsonHolder.instance.toJson(analyses)
    return copy(
        chunkAnalysesJson = json,
        updatedAt = System.currentTimeMillis(),
    )
}

/**
 * Check if this note has per-chunk analyses available
 */
fun Note.hasChunkAnalyses(): Boolean = !chunkAnalysesJson.isNullOrBlank()

/**
 * Get all attachment URIs (combines legacy single + multiple attachments)
 * SECURITY FIX (E-004/GDPR): Returns ALL file URIs to ensure complete deletion
 */
fun Note.getAllAttachmentUris(): List<String> {
    val attachments = getAttachments()
    if (attachments.isNotEmpty()) {
        // Return all attachment URIs from the JSON array
        return attachments.map { it.uri }
    }
    // Fall back to legacy single attachments - return BOTH if set (not just one)
    // This ensures audio files stored as fileUri are properly deleted
    return listOfNotNull(imageUri, fileUri).distinct()
}
