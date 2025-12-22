package com.example.smarty.data.local

import androidx.room.*
import com.example.smarty.data.model.Note
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes WHERE isArchived = 0 ORDER BY createdAt DESC")
    fun getAllNotes(): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE categoryId = :categoryId AND isArchived = 0 ORDER BY createdAt DESC")
    fun getNotesByCategory(categoryId: String): Flow<List<Note>>

    @Query("""
        SELECT * FROM notes 
        WHERE isArchived = 0 
        AND (:query IS NULL OR :query = '' OR title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%' OR summary LIKE '%' || :query || '%')
        AND (:hasTypeFilter = 0 OR type IN (:types))
        ORDER BY createdAt DESC
    """)
    fun searchNotes(query: String?, types: List<com.example.smarty.data.model.NoteType>, hasTypeFilter: Boolean): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getNoteById(id: String): Note?

    @Query("SELECT * FROM notes WHERE isArchived = 1 ORDER BY updatedAt DESC")
    fun getArchivedNotes(): Flow<List<Note>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: Note)

    @Update
    suspend fun updateNote(note: Note)

    @Delete
    suspend fun deleteNote(note: Note)

    @Transaction
    @Query("UPDATE notes SET isArchived = 1, updatedAt = :timestamp WHERE id = :noteId")
    suspend fun archiveNote(noteId: String, timestamp: Long = System.currentTimeMillis())

    @Transaction
    @Query("UPDATE notes SET isArchived = 0, updatedAt = :timestamp WHERE id = :noteId")
    suspend fun unarchiveNote(noteId: String, timestamp: Long = System.currentTimeMillis())

    // Bulk operations (Phase 4)
    @Query("UPDATE notes SET isArchived = 1, updatedAt = :timestamp WHERE id IN (:noteIds)")
    suspend fun archiveNotes(noteIds: List<String>, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE notes SET isArchived = 0, updatedAt = :timestamp WHERE id IN (:noteIds)")
    suspend fun unarchiveNotes(noteIds: List<String>, timestamp: Long = System.currentTimeMillis())

    @Query("DELETE FROM notes WHERE id IN (:noteIds)")
    suspend fun deleteNotesByIds(noteIds: List<String>)

    @Query("SELECT * FROM notes WHERE id IN (:noteIds)")
    suspend fun getNotesByIds(noteIds: List<String>): List<Note>

    @Transaction
    @Query("UPDATE notes SET categoryId = :categoryId, categoryName = :categoryName, updatedAt = :timestamp WHERE id = :noteId")
    suspend fun updateNoteCategory(
        noteId: String,
        categoryId: String,
        categoryName: String,
        timestamp: Long = System.currentTimeMillis()
    )

    // Backup operations - one-shot queries
    @Transaction
    @Query("SELECT * FROM notes ORDER BY createdAt DESC")
    suspend fun getAllNotesOnce(): List<Note>

    @Transaction
    @Query("DELETE FROM notes")
    suspend fun deleteAllNotes()

    @Query("DELETE FROM notes WHERE id = :noteId")
    suspend fun deleteNoteById(noteId: String)

    /**
     * Clear categoryId for all notes in a category.
     * Called before category deletion to prevent orphaned references (BUG-028).
     */
    @Query("UPDATE notes SET categoryId = NULL, categoryName = NULL, updatedAt = :timestamp WHERE categoryId = :categoryId")
    suspend fun clearCategoryFromNotes(categoryId: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE notes SET isViewed = :isViewed WHERE id = :noteId")
    suspend fun updateNoteViewedStatus(noteId: String, isViewed: Boolean)
}
