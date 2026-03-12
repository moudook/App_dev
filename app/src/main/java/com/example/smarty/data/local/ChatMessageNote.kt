package com.example.smarty.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Junction table entity for chat message to note relationships.
 * 
 * SINGLE RESPONSIBILITY: Represents ONLY the relationship between messages and notes.
 * DRY: Same pattern as CalendarEventNote.
 * GLOBAL STATE: Foreign keys ensure referential integrity with cascade deletes.
 */
@Entity(
    tableName = "chat_message_notes",
    primaryKeys = ["message_id", "note_id"],
    foreignKeys = [
        ForeignKey(
            entity = ChatMessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["message_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = com.example.smarty.core.domain.model.NoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["note_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["message_id"]),
        Index(value = ["note_id"])
    ]
)
data class ChatMessageNote(
    @ColumnInfo(name = "message_id") val messageId: String,
    @ColumnInfo(name = "note_id") val noteId: String
)
