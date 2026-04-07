package com.example.smarty.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * Junction table entity for calendar event to note relationships.
 *
 * SINGLE RESPONSIBILITY: Represents ONLY the relationship between events and notes.
 * DRY: Same pattern as ChatMessageNote.
 * GLOBAL STATE: Foreign keys enforced at database level, not Room level.
 */
@Entity(
    tableName = "calendar_event_notes",
    primaryKeys = ["event_id", "note_id"],
    indices = [
        Index(value = ["event_id"]),
        Index(value = ["note_id"]),
    ],
)
data class CalendarEventNote(
    @ColumnInfo(name = "event_id") val eventId: String,
    @ColumnInfo(name = "note_id") val noteId: String,
)
