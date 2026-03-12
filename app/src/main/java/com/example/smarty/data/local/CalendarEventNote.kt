package com.example.smarty.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Junction table entity for calendar event to note relationships.
 * 
 * SINGLE RESPONSIBILITY: Represents ONLY the relationship between events and notes.
 * DRY: Same pattern as ChatMessageNote.
 * GLOBAL STATE: Foreign keys ensure referential integrity with cascade deletes.
 */
@Entity(
    tableName = "calendar_event_notes",
    primaryKeys = ["event_id", "note_id"],
    foreignKeys = [
        ForeignKey(
            entity = "calendar_events",
            parentColumns = ["id"],
            childColumns = ["event_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = "notes",
            parentColumns = ["id"],
            childColumns = ["note_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["event_id"]),
        Index(value = ["note_id"])
    ]
)
data class CalendarEventNote(
    @ColumnInfo(name = "event_id") val eventId: String,
    @ColumnInfo(name = "note_id") val noteId: String
)
