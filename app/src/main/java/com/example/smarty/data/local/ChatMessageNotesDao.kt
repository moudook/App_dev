package com.example.smarty.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * DAO for chat message to note relationships.
 *
 * SINGLE RESPONSIBILITY: Only manages chat_message_notes junction table.
 * DRY: Same pattern as CalendarEventNotesDao.
 * GLOBAL STATE: Ensures referential integrity via foreign keys.
 */
@Dao
interface ChatMessageNotesDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(note: ChatMessageNote)

    @Delete
    suspend fun delete(note: ChatMessageNote)

    @Query("SELECT note_id FROM chat_message_notes WHERE message_id = :messageId")
    suspend fun getLinkedNoteIds(messageId: String): List<String>

    @Query("SELECT note_id FROM chat_message_notes WHERE message_id = :messageId")
    fun getLinkedNoteIdsFlow(messageId: String): Flow<List<String>>

    @Query("SELECT message_id FROM chat_message_notes WHERE note_id = :noteId")
    suspend fun getLinkedMessageIds(noteId: String): List<String>

    @Query("SELECT message_id FROM chat_message_notes WHERE note_id = :noteId")
    fun getLinkedMessageIdsFlow(noteId: String): Flow<List<String>>

    @Query("DELETE FROM chat_message_notes WHERE message_id = :messageId")
    suspend fun deleteAllForMessage(messageId: String)

    @Query("DELETE FROM chat_message_notes WHERE note_id = :noteId")
    suspend fun deleteAllForNote(noteId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM chat_message_notes WHERE message_id = :messageId AND note_id = :noteId)")
    suspend fun isLinked(
        messageId: String,
        noteId: String,
    ): Boolean

    @Query("SELECT COUNT(*) FROM chat_message_notes WHERE message_id = :messageId")
    suspend fun getLinkCountForMessage(messageId: String): Int

    @Query("SELECT COUNT(*) FROM chat_message_notes WHERE note_id = :noteId")
    suspend fun getLinkCountForNote(noteId: String): Int

    @Transaction
    suspend fun linkMessageToNote(
        messageId: String,
        noteId: String,
    ) {
        if (!isLinked(messageId, noteId)) {
            insert(ChatMessageNote(messageId, noteId))
        }
    }

    @Transaction
    suspend fun linkMultipleNotesToMessage(
        messageId: String,
        noteIds: List<String>,
    ) {
        noteIds.forEach { noteId ->
            linkMessageToNote(messageId, noteId)
        }
    }

    @Transaction
    suspend fun unlinkMessageFromNote(
        messageId: String,
        noteId: String,
    ) {
        delete(ChatMessageNote(messageId, noteId))
    }
}
