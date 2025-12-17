package com.example.smarty.data.model

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
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
    val isFullPrivacy: Boolean = false  // Full privacy mode - no AI processing at all
) {
    companion object {
        @Ignore
        private val gson = Gson()
    }
}

// Cached Gson instance for performance
private object GsonHolder {
    val instance: Gson = Gson()
    val todoListType = object : TypeToken<List<TodoItem>>() {}.type!!
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
