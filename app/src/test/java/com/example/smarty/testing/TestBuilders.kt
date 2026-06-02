package com.example.smarty.testing

import com.example.smarty.core.domain.model.Attachment
import com.example.smarty.core.domain.model.AttachmentType
import com.example.smarty.core.domain.model.CalendarEvent
import com.example.smarty.core.domain.model.Category
import com.example.smarty.core.domain.model.Note
import com.example.smarty.core.domain.model.NoteType
import com.example.smarty.core.domain.model.ProcessingStatus
import java.util.UUID

/**
 * Test Data Builders - Centralized test data creation utilities.
 *
 * Purpose:
 * - Reduce code duplication in tests
 * - Provide sensible defaults
 * - Allow easy customization for specific test scenarios
 * - Improve test readability
 *
 * Usage:
 * ```kotlin
 * val note = TestBuilders.note {
 *     title = "Custom Title"
 *     categoryName = "Work"
 * }
 *
 * val category = TestBuilders.category {
 *     name = "Personal"
 *     color = "#FF0000"
 * }
 * ```
 */
object TestBuilders {
    // ==================== Note Builders ====================

    /**
     * Build a Note with customizable fields.
     *
     * @param block DSL builder lambda for customization
     * @return Note instance with configured values
     */
    fun note(block: NoteBuilder.() -> Unit = {}): Note {
        val builder = NoteBuilder()
        builder.block()
        return builder.build()
    }

    /**
     * Build a list of Notes for batch testing.
     *
     * @param count Number of notes to create
     * @param block Optional customization applied to all notes
     * @return List of Note instances
     */
    fun noteList(
        count: Int,
        block: NoteBuilder.() -> Unit = {},
    ): List<Note> = (1..count).map { note(block) }

    class NoteBuilder {
        var id: String = UUID.randomUUID().toString()
        var title: String = "Test Note"
        var content: String = "Test content for note"
        var categoryName: String = "General"
        var isArchived: Boolean = false
        var isPinned: Boolean = false
        var isPrivate: Boolean = false
        var type: NoteType = NoteType.BRAIN_DUMP
        var processingStatus: ProcessingStatus = ProcessingStatus.COMPLETED
        var isAiCreated: Boolean = false
        var createdAt: Long = System.currentTimeMillis()
        var updatedAt: Long = System.currentTimeMillis()
        var attachments: List<Attachment> = emptyList()

        fun build(): Note =
            Note(
                id = id,
                title = title,
                content = content,
                categoryName = categoryName,
                isArchived = isArchived,
                isPinned = isPinned,
                isPrivate = isPrivate,
                type = type,
                processingStatus = processingStatus,
                isAiCreated = isAiCreated,
                createdAt = createdAt,
                updatedAt = updatedAt,
                attachments = attachments,
            )

        // Pre-configured templates for common scenarios
        fun archived(): NoteBuilder =
            apply {
                isArchived = true
                title = "Archived: $title"
            }

        fun pinned(): NoteBuilder =
            apply {
                isPinned = true
            }

        fun private(): NoteBuilder =
            apply {
                isPrivate = true
                title = "Private: $title"
            }

        fun withAttachment(
            type: AttachmentType,
            url: String = "test://url",
        ): NoteBuilder =
            apply {
                attachments =
                    listOf(
                        attachment {
                            this.type = type
                            this.url = url
                        },
                    )
            }

        fun aiCreated(): NoteBuilder =
            apply {
                isAiCreated = true
                processingStatus = ProcessingStatus.COMPLETED
            }
    }

    // ==================== Category Builders ====================

    fun category(block: CategoryBuilder.() -> Unit = {}): Category {
        val builder = CategoryBuilder()
        builder.block()
        return builder.build()
    }

    fun categoryList(
        count: Int,
        block: CategoryBuilder.() -> Unit = {},
    ): List<Category> = (1..count).map { category(block) }

    class CategoryBuilder {
        var id: String = UUID.randomUUID().toString()
        var name: String = "Test Category"
        var color: String = "#2196F3" // Default blue
        var noteCount: Int = 0

        fun build(): Category =
            Category(
                id = id,
                name = name,
                color = color,
                noteCount = noteCount,
            )

        // Pre-configured templates
        fun red(): CategoryBuilder = apply { color = "#F44336" }

        fun green(): CategoryBuilder = apply { color = "#4CAF50" }

        fun orange(): CategoryBuilder = apply { color = "#FF9800" }

        fun purple(): CategoryBuilder = apply { color = "#9C27B0" }
    }

    // ==================== Calendar Event Builders ====================

    fun calendarEvent(block: CalendarEventBuilder.() -> Unit = {}): CalendarEvent {
        val builder = CalendarEventBuilder()
        builder.block()
        return builder.build()
    }

    class CalendarEventBuilder {
        var id: String = UUID.randomUUID().toString()
        var title: String = "Test Event"
        var description: String? = null
        var startTime: Long = System.currentTimeMillis() + 86400000 // Tomorrow
        var endTime: Long = startTime + 3600000 // 1 hour duration
        var location: String? = null
        var calendarId: String = "primary"
        var googleEventId: String? = null
        var isAllDay: Boolean = false
        var createdAt: Long = System.currentTimeMillis()
        var updatedAt: Long = System.currentTimeMillis()

        fun build(): CalendarEvent =
            CalendarEvent(
                id = id,
                title = title,
                description = description,
                startTime = startTime,
                endTime = endTime,
                location = location,
                calendarId = calendarId,
                googleEventId = googleEventId,
                isAllDay = isAllDay,
                createdAt = createdAt,
                updatedAt = updatedAt,
            )
    }

    // ==================== Attachment Builders ====================

    fun attachment(block: AttachmentBuilder.() -> Unit = {}): Attachment {
        val builder = AttachmentBuilder()
        builder.block()
        return builder.build()
    }

    class AttachmentBuilder {
        var id: String = UUID.randomUUID().toString()
        var type: AttachmentType = AttachmentType.IMAGE
        var url: String = "test://attachment"
        var fileName: String = "test_file"
        var fileSize: Long = 1024
        var mimeType: String = "application/octet-stream"

        fun build(): Attachment =
            Attachment(
                id = id,
                type = type,
                url = url,
                fileName = fileName,
                fileSize = fileSize,
                mimeType = mimeType,
            )

        // Pre-configured templates
        fun image(): AttachmentBuilder =
            apply {
                type = AttachmentType.IMAGE
                mimeType = "image/jpeg"
                fileName = "test_image.jpg"
            }

        fun pdf(): AttachmentBuilder =
            apply {
                type = AttachmentType.DOCUMENT
                mimeType = "application/pdf"
                fileName = "test_document.pdf"
            }

        fun audio(): AttachmentBuilder =
            apply {
                type = AttachmentType.AUDIO
                mimeType = "audio/mpeg"
                fileName = "test_audio.mp3"
            }

        fun video(): AttachmentBuilder =
            apply {
                type = AttachmentType.VIDEO
                mimeType = "video/mp4"
                fileName = "test_video.mp4"
            }
    }

    // ==================== Constants for Testing ====================

    object Constants {
        // Common test IDs
        const val TEST_USER_ID = "test_user_123"
        const val TEST_DEVICE_ID = "test_device_456"
        const val TEST_SESSION_ID = "test_session_789"

        // Common test timestamps
        const val TIMESTAMP_PAST = 1609459200000L // Jan 1, 2021
        const val TIMESTAMP_PRESENT = 1672531200000L // Jan 1, 2023
        const val TIMESTAMP_FUTURE = 1735689600000L // Jan 1, 2025

        // Common test strings
        const val EMPTY_STRING = ""
        const val LONG_STRING = "A".repeat(10000)
        const val SPECIAL_CHARS = "!@#\$%^&*()_+-=[]{}|;:',.<>?/"

        // Common test URLs
        const val TEST_URL = "https://example.com"
        const val TEST_IMAGE_URL = "https://example.com/image.jpg"
        const val TEST_PDF_URL = "https://example.com/document.pdf"
    }

    // ==================== FCM/Token Testing ====================

    object FcmTestData {
        const val VALID_TOKEN = "fcm_token_abc123xyz789"
        const val INVALID_TOKEN = "invalid_token"
        const val EXPIRED_TOKEN = "expired_token_old123"

        fun validTokenWithTimestamp(): Pair<String, Long> = VALID_TOKEN to System.currentTimeMillis()
    }

    // ==================== PDF Testing ====================

    object PdfTestData {
        const val EMPTY_PDF_TEXT = ""
        const val SHORT_PDF_TEXT = "This is a short PDF content."
        const val LONG_PDF_TEXT = "A".repeat(50000) // 50KB of text

        fun generateChunkedText(
            chunkSize: Int = 1000,
            chunks: Int = 10,
        ): String =
            buildString {
                repeat(chunks) { i ->
                    if (i > 0) append(" ") // Word boundary
                    append("Chunk ${i + 1}: ".padEnd(chunkSize, 'X'))
                }
            }
    }
}
