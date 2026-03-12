package com.example.smarty.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * DAO for calendar event to note relationships.
 * 
 * SINGLE RESPONSIBILITY: Only manages calendar_event_notes junction table.
 * DRY: Same pattern as ChatMessageNotesDao.
 * GLOBAL STATE: Ensures referential integrity via foreign keys.
 */
@Dao
interface CalendarEventNotesDao {
    
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(note: CalendarEventNote)
    
    @Delete
    suspend fun delete(note: CalendarEventNote)
    
    @Query("SELECT note_id FROM calendar_event_notes WHERE event_id = :eventId")
    suspend fun getLinkedNoteIds(eventId: String): List<String>
    
    @Query("SELECT note_id FROM calendar_event_notes WHERE event_id = :eventId")
    fun getLinkedNoteIdsFlow(eventId: String): Flow<List<String>>
    
    @Query("SELECT event_id FROM calendar_event_notes WHERE note_id = :noteId")
    suspend fun getLinkedEventIds(noteId: String): List<String>
    
    @Query("SELECT event_id FROM calendar_event_notes WHERE note_id = :noteId")
    fun getLinkedEventIdsFlow(noteId: String): Flow<List<String>>
    
    @Query("DELETE FROM calendar_event_notes WHERE event_id = :eventId")
    suspend fun deleteAllForEvent(eventId: String)
    
    @Query("DELETE FROM calendar_event_notes WHERE note_id = :noteId")
    suspend fun deleteAllForNote(noteId: String)
    
    @Query("SELECT EXISTS(SELECT 1 FROM calendar_event_notes WHERE event_id = :eventId AND note_id = :noteId)")
    suspend fun isLinked(eventId: String, noteId: String): Boolean
    
    @Query("SELECT COUNT(*) FROM calendar_event_notes WHERE event_id = :eventId")
    suspend fun getLinkCountForEvent(eventId: String): Int
    
    @Query("SELECT COUNT(*) FROM calendar_event_notes WHERE note_id = :noteId")
    suspend fun getLinkCountForNote(noteId: String): Int
    
    @Transaction
    suspend fun linkEventToNote(eventId: String, noteId: String) {
        if (!isLinked(eventId, noteId)) {
            insert(CalendarEventNote(eventId, noteId))
        }
    }
    
    @Transaction
    suspend fun linkMultipleNotesToEvent(eventId: String, noteIds: List<String>) {
        noteIds.forEach { noteId ->
            linkEventToNote(eventId, noteId)
        }
    }
    
    @Transaction
    suspend fun unlinkEventFromNote(eventId: String, noteId: String) {
        delete(CalendarEventNote(eventId, noteId))
    }
}
