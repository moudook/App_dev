package com.example.smarty.data.local

import androidx.paging.PagingSource
import androidx.room.*
import com.example.smarty.core.domain.model.Note
import com.example.smarty.core.domain.model.ProcessingStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {

    companion object {
        /**
         * Sanitizes user input for safe use in FTS5 MATCH queries.
         * Escapes/removes special characters that could cause crashes or unexpected behavior.
         * FTS5 special characters: " * - OR AND NOT ( )
         */
        fun sanitizeFtsQuery(query: String): String {
            if (query.isBlank()) return ""

            return query
                .replace("\"", "\"\"")  // Escape double quotes
                .replace("*", "")       // Remove wildcards
                .replace("-", " ")      // Replace minus (NOT operator) with space
                .replace("(", "")       // Remove parentheses
                .replace(")", "")
                .trim()
                .split("\\s+".toRegex())
                .filter { it.isNotBlank() && it !in listOf("OR", "AND", "NOT") }  // Filter out boolean operators
                .joinToString(" ") { "\"$it\"*" }  // Wrap each word in quotes and add prefix search
        }
    }
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
    fun searchNotes(query: String?, types: List<com.example.smarty.core.domain.model.NoteType>, hasTypeFilter: Boolean): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getNoteById(id: String): Note?

    /**
     * Observe a note by ID as a Flow.
     * Emits new value whenever the note is updated in the database.
     * Used for reactive UI updates (e.g., detail view auto-refresh).
     */
    @Query("SELECT * FROM notes WHERE id = :id LIMIT 1")
    fun getNoteByIdFlow(id: String): Flow<Note?>

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

    // Processing Queue
    @Query("SELECT * FROM notes WHERE processingStatus = 'PROCESSING' AND updatedAt < :timeoutThreshold")
    suspend fun getStuckProcessingNotes(timeoutThreshold: Long): List<Note>

    @Query("SELECT * FROM notes WHERE processingStatus = :status")
    suspend fun getNotesByProcessingStatus(status: com.example.smarty.core.domain.model.ProcessingStatus): List<Note>

    @Query("UPDATE notes SET processingStatus = :status, updatedAt = :timestamp WHERE id = :noteId")
    suspend fun updateProcessingStatus(noteId: String, status: com.example.smarty.core.domain.model.ProcessingStatus, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE notes SET processingStatus = 'PENDING', updatedAt = :timestamp WHERE processingStatus = 'PROCESSING' AND updatedAt < :timeoutThreshold")
    suspend fun resetStuckNotes(timeoutThreshold: Long, timestamp: Long = System.currentTimeMillis()): Int

    @Query("SELECT * FROM notes WHERE processingStatus = 'PENDING' ORDER BY createdAt ASC LIMIT 1")
    suspend fun getNextPendingNote(): Note?

    @Query("SELECT COUNT(*) FROM notes WHERE processingStatus = 'PENDING'")
    suspend fun getPendingProcessingCount(): Int

    // FTS Search
    @RawQuery
    suspend fun checkpoint(supportSQLiteQuery: androidx.sqlite.db.SupportSQLiteQuery): Int

    @SkipQueryVerification
    @Query("INSERT INTO notes_fts(notes_fts) VALUES('optimize')")
    suspend fun optimizeFtsIndex()

    @SkipQueryVerification
    @Query("""
        SELECT notes.* FROM notes
        JOIN notes_fts ON notes.rowid = notes_fts.rowid
        WHERE notes_fts MATCH :query
        AND isArchived = 0
    """)
    suspend fun searchNotesFts(query: String): List<Note>

    @SkipQueryVerification
    @Query("""
        SELECT notes.* FROM notes
        JOIN notes_fts ON notes.rowid = notes_fts.rowid
        WHERE notes_fts MATCH :query
        AND isArchived = 0
        ORDER BY createdAt DESC
    """)
    suspend fun searchNotesFts4(query: String): List<Note>

    @SkipQueryVerification
    @Query("""
        SELECT notes.* FROM notes
        JOIN notes_fts ON notes.rowid = notes_fts.rowid
        WHERE notes_fts MATCH :query
        AND isArchived = 0
    """)
    fun searchNotesFtsFlow(query: String): Flow<List<Note>>

    @SkipQueryVerification
    @Query("""
        SELECT notes.* FROM notes
        JOIN notes_fts ON notes.rowid = notes_fts.rowid
        WHERE notes_fts MATCH :query
        AND isArchived = 0
        ORDER BY createdAt DESC
    """)
    fun searchNotesFts4Flow(query: String): Flow<List<Note>>

    @SkipQueryVerification
    @Query("""
        SELECT notes.* FROM notes
        JOIN notes_fts ON notes.rowid = notes_fts.rowid
        WHERE notes_fts MATCH :query
        AND isArchived = 0
        AND type IN (:types)
    """)
    suspend fun searchNotesFtsWithType(query: String, types: List<com.example.smarty.core.domain.model.NoteType>): List<Note>

    @SkipQueryVerification
    @Query("""
        SELECT notes.* FROM notes
        JOIN notes_fts ON notes.rowid = notes_fts.rowid
        WHERE notes_fts MATCH :query
        AND isArchived = 0
        AND type IN (:types)
        ORDER BY createdAt DESC
    """)
    suspend fun searchNotesFts4WithType(query: String, types: List<com.example.smarty.core.domain.model.NoteType>): List<Note>

    // Paging
    @Query("SELECT * FROM notes WHERE isArchived = 0 ORDER BY isPinned DESC, createdAt DESC")
    fun getAllNotesPaged(): PagingSource<Int, Note>

    @Query("SELECT * FROM notes WHERE categoryId = :categoryId AND isArchived = 0 ORDER BY isPinned DESC, createdAt DESC")
    fun getNotesByCategoryPaged(categoryId: String): PagingSource<Int, Note>

    @Query("SELECT * FROM notes WHERE isArchived = 1 ORDER BY updatedAt DESC")
    fun getArchivedNotesPaged(): PagingSource<Int, Note>

    @Query("""
        SELECT * FROM notes
        WHERE isArchived = 0
        AND (:query IS NULL OR :query = '' OR title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%' OR summary LIKE '%' || :query || '%')
        AND (:hasTypeFilter = 0 OR type IN (:types))
        ORDER BY createdAt DESC
    """)
    fun searchNotesPaged(query: String?, types: List<com.example.smarty.core.domain.model.NoteType>, hasTypeFilter: Boolean): PagingSource<Int, Note>

    // Reminders
    @Query("UPDATE notes SET reminderText = :reminderText, reminderExpiresAt = :expiresAt, updatedAt = :timestamp WHERE id = :noteId")
    suspend fun setReminder(noteId: String, reminderText: String, expiresAt: Long?, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE notes SET reminderText = NULL, reminderExpiresAt = NULL, updatedAt = :timestamp WHERE id = :noteId")
    suspend fun clearReminder(noteId: String, timestamp: Long = System.currentTimeMillis())

    @Query("SELECT * FROM notes WHERE reminderText IS NOT NULL AND (reminderExpiresAt IS NULL OR reminderExpiresAt > :now) ORDER BY reminderExpiresAt ASC")
    fun getNotesWithActiveReminders(now: Long = System.currentTimeMillis()): Flow<List<Note>>

    @Query("SELECT COUNT(*) FROM notes")
    suspend fun getNoteCount(): Int

    @Query("SELECT * FROM notes WHERE createdAt >= :timestamp AND isArchived = 0")
    suspend fun getNotesCreatedAfter(timestamp: Long): List<Note>

    @Query("SELECT * FROM notes WHERE updatedAt >= :timestamp AND isArchived = 0")
    suspend fun getNotesModifiedSince(timestamp: Long): List<Note>

    @Query("SELECT COUNT(*) FROM notes WHERE isPinned = 1 AND isArchived = 0")
    suspend fun getPinnedNotesCount(): Int
}
