package com.example.smarty.data.model

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.smarty.util.PrivacyAware
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

/**
 * Todo item stored as JSON within Note.todoContent
 */
data class TodoItem(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val isCompleted: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Attachment item stored as JSON within Note.attachmentsJson
 * Supports multiple files per note
 */
data class NoteAttachment(
    val id: String = UUID.randomUUID().toString(),
    val uri: String,  // File URI as string
    val fileName: String,
    val mimeType: String,
    val fileSize: Long = 0
)

enum class NoteType {
    BRAIN_DUMP,
    YOUTUBE,
    WEBSITE,
    IMAGE,
    TWITTER,
    INSTAGRAM,
    DOCUMENT,
    SPREADSHEET,
    PRESENTATION,
    VIDEO,
    AUDIO,
    CODE,
    ARCHIVE,
    APK,
    FILE;

    // O(1) lookup for analyzable types using bit flag
    companion object {
        private const val ANALYZABLE_MASK = 0b0000001111111111 // First 10 types are analyzable
        fun isAnalyzable(type: NoteType): Boolean = type.ordinal < 10
    }
}

enum class ProcessingStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED
}

@Entity(
    tableName = "notes",
    indices = [
        Index(value = ["categoryId"]),
        Index(value = ["isArchived"]),
        Index(value = ["createdAt"]),
        Index(value = ["type"]),
        // Composite indices for common queries
        Index(value = ["isArchived", "createdAt"]),  // Archived/active notes by date
        Index(value = ["categoryId", "isArchived"]),  // Category notes filtering
        Index(value = ["excludeFromAiChat"]),  // AI chat exclusion filtering
        Index(value = ["isFullPrivacy"])  // Full privacy mode filtering
    ]
)
data class Note(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val content: String,
    val summary: String? = null,
    val sourceUrl: String? = null,
    val imageUri: String? = null,
    val fileUri: String? = null,
    val fileName: String? = null,
    val fileMimeType: String? = null,
    val fileSize: Long? = null,
    val type: NoteType,
    val categoryId: String? = null,
    val categoryName: String? = null,
    val whySaved: String? = null,
    val processingStatus: ProcessingStatus = ProcessingStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isArchived: Boolean = false,
    val todoContent: String? = null,  // JSON string of List<TodoItem>
    val excludeFromAiChat: Boolean = false,  // Exclude this note from AI chat context
    val isFullPrivacy: Boolean = false,  // Full privacy mode - no AI processing at all
    val isAiCreated: Boolean = false, // Flag to indicate if note was created by AI
    val attachmentsJson: String? = null,  // JSON string of List<NoteAttachment> for multiple files
    val tagsJson: String? = null,  // JSON string of List<String> for AI-generated tags
    val isViewed: Boolean = false // Track if the note has been viewed by the user
) : PrivacyAware {
    /**
     * PrivacyAware implementation.
     * A note is private if EITHER privacy flag is set:
     * - isFullPrivacy: Complete privacy mode (no AI access whatsoever)
     * - excludeFromAiChat: Excluded from AI chat context
     *
     * When isPrivate is true, AI cannot see, search, modify, or reference this note.
     *
     * Note: This is a computed property (no backing field) so Room ignores it automatically.
     */
    override val isPrivate: Boolean
        get() = isFullPrivacy || excludeFromAiChat

    companion object {
        @Ignore
        private val gson = Gson()
    }
}

// Cached Gson instance for performance
private object GsonHolder {
    val instance: Gson = Gson()
    val todoListType = object : TypeToken<List<TodoItem>>() {}.type!!
    val attachmentListType = object : TypeToken<List<NoteAttachment>>() {}.type!!
    val tagsListType = object : TypeToken<List<String>>() {}.type!!
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
        updatedAt = System.currentTimeMillis()
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
        updatedAt = System.currentTimeMillis()
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
        updatedAt = System.currentTimeMillis()
    )
}

/**
 * Get all attachment URIs (combines legacy single + multiple attachments)
 */
fun Note.getAllAttachmentUris(): List<String> {
    val attachments = getAttachments()
    if (attachments.isNotEmpty()) {
        return attachments.map { it.uri }
    }
    // Fall back to legacy single attachment
    return listOfNotNull(imageUri ?: fileUri)
}
