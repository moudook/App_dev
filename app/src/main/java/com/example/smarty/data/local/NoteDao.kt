package com.example.smarty.data.local

import androidx.paging.PagingSource
import androidx.room.*
import com.example.smarty.data.model.Note
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    /**
     * Get all active notes, sorted with pinned notes first, then by creation date
     */
    @Query("SELECT * FROM notes WHERE isArchived = 0 ORDER BY isPinned DESC, createdAt DESC")
    fun getAllNotes(): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE categoryId = :categoryId AND isArchived = 0 ORDER BY isPinned DESC, createdAt DESC")
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotes(notes: List<Note>)

    @Update
    suspend fun updateNote(note: Note)

    @Update
    suspend fun updateNotes(notes: List<Note>)

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

    /**
     * Pin a note (moves it to top of list)
     */
    @Query("UPDATE notes SET isPinned = 1, updatedAt = :timestamp WHERE id = :noteId")
    suspend fun pinNote(noteId: String, timestamp: Long = System.currentTimeMillis())

    /**
     * Unpin a note
     */
    @Query("UPDATE notes SET isPinned = 0, updatedAt = :timestamp WHERE id = :noteId")
    suspend fun unpinNote(noteId: String, timestamp: Long = System.currentTimeMillis())

    /**
     * Toggle pin status
     */
    @Query("UPDATE notes SET isPinned = NOT isPinned, updatedAt = :timestamp WHERE id = :noteId")
    suspend fun togglePin(noteId: String, timestamp: Long = System.currentTimeMillis())

    /**
     * Set reminder for a note
     */
    @Query("UPDATE notes SET reminderText = :reminderText, reminderExpiresAt = :expiresAt, updatedAt = :timestamp WHERE id = :noteId")
    suspend fun setReminder(noteId: String, reminderText: String, expiresAt: Long?, timestamp: Long = System.currentTimeMillis())

    /**
     * Clear reminder from a note
     */
    @Query("UPDATE notes SET reminderText = NULL, reminderExpiresAt = NULL, updatedAt = :timestamp WHERE id = :noteId")
    suspend fun clearReminder(noteId: String, timestamp: Long = System.currentTimeMillis())

    /**
     * Get notes with active reminders (not expired)
     */
    @Query("SELECT * FROM notes WHERE reminderText IS NOT NULL AND (reminderExpiresAt IS NULL OR reminderExpiresAt > :currentTime) AND isArchived = 0")
    fun getNotesWithActiveReminders(currentTime: Long = System.currentTimeMillis()): Flow<List<Note>>

    // =========================================================================
    // DAILY DIGEST QUERIES
    // =========================================================================

    /**
     * Get notes created after a certain timestamp (for daily digest)
     */
    @Query("SELECT * FROM notes WHERE createdAt > :timestamp AND isArchived = 0 ORDER BY createdAt DESC")
    suspend fun getNotesCreatedAfter(timestamp: Long): List<Note>

    /**
     * Get total count of non-archived notes
     */
    @Query("SELECT COUNT(*) FROM notes WHERE isArchived = 0")
    suspend fun getNoteCount(): Int

    /**
     * Get count of pinned notes
     */
    @Query("SELECT COUNT(*) FROM notes WHERE isPinned = 1 AND isArchived = 0")
    suspend fun getPinnedNotesCount(): Int

    // =========================================================================
    // FTS5 FULL-TEXT SEARCH QUERIES
    // =========================================================================

    /**
     * Full-text search using FTS5 for fast, ranked results.
     * Searches title, content, and summary fields.
     * Returns notes ordered by relevance (BM25 ranking).
     * @SkipQueryVerification is needed because FTS table is created via migration
     */
    @SkipQueryVerification
    @Query("""
        SELECT notes.* FROM notes
        INNER JOIN notes_fts ON notes.rowid = notes_fts.rowid
        WHERE notes_fts MATCH :query
        AND notes.isArchived = 0
        ORDER BY bm25(notes_fts) DESC
    """)
    suspend fun searchNotesFts(query: String): List<Note>

    /**
     * Full-text search with type filter.
     * Combines FTS5 search with note type filtering.
     */
    @SkipQueryVerification
    @Query("""
        SELECT notes.* FROM notes
        INNER JOIN notes_fts ON notes.rowid = notes_fts.rowid
        WHERE notes_fts MATCH :query
        AND notes.isArchived = 0
        AND notes.type IN (:types)
        ORDER BY bm25(notes_fts) DESC
    """)
    suspend fun searchNotesFtsWithType(query: String, types: List<com.example.smarty.data.model.NoteType>): List<Note>

    /**
     * FTS5 search as Flow for reactive updates.
     */
    @SkipQueryVerification
    @Query("""
        SELECT notes.* FROM notes
        INNER JOIN notes_fts ON notes.rowid = notes_fts.rowid
        WHERE notes_fts MATCH :query
        AND notes.isArchived = 0
        ORDER BY bm25(notes_fts) DESC
    """)
    fun searchNotesFtsFlow(query: String): Flow<List<Note>>

    // =========================================================================
    // PAGING3 QUERIES
    // =========================================================================

    /**
     * Paginated query for all active notes.
     * Returns a PagingSource for efficient loading of large note lists.
     * Pinned notes appear first, then sorted by creation date.
     */
    @Query("SELECT * FROM notes WHERE isArchived = 0 ORDER BY isPinned DESC, createdAt DESC")
    fun getAllNotesPaged(): PagingSource<Int, Note>

    /**
     * Paginated query for notes by category.
     */
    @Query("SELECT * FROM notes WHERE categoryId = :categoryId AND isArchived = 0 ORDER BY isPinned DESC, createdAt DESC")
    fun getNotesByCategoryPaged(categoryId: String): PagingSource<Int, Note>

    /**
     * Paginated query for archived notes.
     */
    @Query("SELECT * FROM notes WHERE isArchived = 1 ORDER BY updatedAt DESC")
    fun getArchivedNotesPaged(): PagingSource<Int, Note>

    /**
     * Paginated search query with type filter.
     */
    @Query("""
        SELECT * FROM notes
        WHERE isArchived = 0
        AND (:query IS NULL OR :query = '' OR title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%' OR summary LIKE '%' || :query || '%')
        AND (:hasTypeFilter = 0 OR type IN (:types))
        ORDER BY isPinned DESC, createdAt DESC
    """)
    fun searchNotesPaged(query: String?, types: List<com.example.smarty.data.model.NoteType>, hasTypeFilter: Boolean): PagingSource<Int, Note>
}
