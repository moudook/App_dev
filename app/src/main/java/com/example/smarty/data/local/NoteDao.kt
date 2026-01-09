package com.example.smarty.data.local

import androidx.paging.PagingSource
import androidx.room.*
import com.example.smarty.data.model.Note
import com.example.smarty.data.model.ProcessingStatus
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
    fun searchNotes(query: String?, types: List<com.example.smarty.data.model.NoteType>, hasTypeFilter: Boolean): Flow<List<Note>>

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
     *
     * SECURITY NOTE: The query parameter MUST be sanitized before calling this method.
     * Use NoteDao.sanitizeFtsQuery(userInput) to sanitize user input before passing it here.
     * Raw user input can cause crashes or unexpected behavior with FTS5 special characters.
     *
     * @param query Sanitized FTS5 query string. Use [sanitizeFtsQuery] to sanitize user input.
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
     *
     * SECURITY NOTE: The query parameter MUST be sanitized before calling this method.
     * Use NoteDao.sanitizeFtsQuery(userInput) to sanitize user input before passing it here.
     * Raw user input can cause crashes or unexpected behavior with FTS5 special characters.
     *
     * @param query Sanitized FTS5 query string. Use [sanitizeFtsQuery] to sanitize user input.
     * @param types List of note types to filter by.
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
     *
     * SECURITY NOTE: The query parameter MUST be sanitized before calling this method.
     * Use NoteDao.sanitizeFtsQuery(userInput) to sanitize user input before passing it here.
     * Raw user input can cause crashes or unexpected behavior with FTS5 special characters.
     *
     * @param query Sanitized FTS5 query string. Use [sanitizeFtsQuery] to sanitize user input.
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

    // =========================================================================
    // PROCESSING QUEUE QUERIES
    // =========================================================================

    /**
     * Get notes by processing status for queue management.
     * Used by NoteProcessingQueueManager to find pending/stuck notes.
     */
    @Query("SELECT * FROM notes WHERE processingStatus = :status ORDER BY createdAt ASC")
    suspend fun getNotesByProcessingStatus(status: ProcessingStatus): List<Note>

    /**
     * Get notes that are stuck in PROCESSING state (timeout detection).
     * Notes older than the timeout threshold that are still PROCESSING.
     */
    @Query("SELECT * FROM notes WHERE processingStatus = 'PROCESSING' AND updatedAt < :timeoutThreshold ORDER BY createdAt ASC")
    suspend fun getStuckProcessingNotes(timeoutThreshold: Long): List<Note>

    /**
     * Get count of notes pending processing.
     */
    @Query("SELECT COUNT(*) FROM notes WHERE processingStatus IN ('PENDING', 'PROCESSING')")
    suspend fun getPendingProcessingCount(): Int

    /**
     * Bulk update processing status for multiple notes.
     */
    @Query("UPDATE notes SET processingStatus = :status, updatedAt = :timestamp WHERE id IN (:noteIds)")
    suspend fun updateProcessingStatusBatch(noteIds: List<String>, status: ProcessingStatus, timestamp: Long = System.currentTimeMillis())

    /**
     * Update single note processing status.
     */
    @Query("UPDATE notes SET processingStatus = :status, updatedAt = :timestamp WHERE id = :noteId")
    suspend fun updateProcessingStatus(noteId: String, status: ProcessingStatus, timestamp: Long = System.currentTimeMillis())

    /**
     * Get next note in queue for processing (oldest PENDING note).
     */
    @Query("SELECT * FROM notes WHERE processingStatus = 'PENDING' ORDER BY createdAt ASC LIMIT 1")
    suspend fun getNextPendingNote(): Note?

    /**
     * Mark stuck PROCESSING notes as PENDING for retry.
     * Used on app startup to recover from crashes.
     */
    @Query("UPDATE notes SET processingStatus = 'PENDING', updatedAt = :timestamp WHERE processingStatus = 'PROCESSING' AND updatedAt < :timeoutThreshold")
    suspend fun resetStuckNotes(timeoutThreshold: Long, timestamp: Long = System.currentTimeMillis()): Int

    // =========================================================================
    // FTS4 COMPATIBLE SEARCH QUERIES (fallback when FTS5 not available)
    // =========================================================================

    /**
     * Full-text search using FTS4 (no bm25 ranking).
     * Returns notes ordered by creation date instead of relevance.
     * Used when device SQLite doesn't support FTS5.
     *
     * @param query Sanitized FTS query string.
     */
    @SkipQueryVerification
    @Query("""
        SELECT notes.* FROM notes
        INNER JOIN notes_fts ON notes.rowid = notes_fts.rowid
        WHERE notes_fts MATCH :query
        AND notes.isArchived = 0
        ORDER BY notes.createdAt DESC
    """)
    suspend fun searchNotesFts4(query: String): List<Note>

    /**
     * FTS4 search with type filter.
     *
     * @param query Sanitized FTS query string.
     * @param types List of note types to filter by.
     */
    @SkipQueryVerification
    @Query("""
        SELECT notes.* FROM notes
        INNER JOIN notes_fts ON notes.rowid = notes_fts.rowid
        WHERE notes_fts MATCH :query
        AND notes.isArchived = 0
        AND notes.type IN (:types)
        ORDER BY notes.createdAt DESC
    """)
    suspend fun searchNotesFts4WithType(query: String, types: List<com.example.smarty.data.model.NoteType>): List<Note>

    /**
     * FTS4 search as Flow for reactive updates.
     *
     * @param query Sanitized FTS query string.
     */
    @SkipQueryVerification
    @Query("""
        SELECT notes.* FROM notes
        INNER JOIN notes_fts ON notes.rowid = notes_fts.rowid
        WHERE notes_fts MATCH :query
        AND notes.isArchived = 0
        ORDER BY notes.createdAt DESC
    """)
    fun searchNotesFts4Flow(query: String): Flow<List<Note>>

    // =========================================================================
    // FTS MAINTENANCE QUERIES
    // =========================================================================

    /**
     * Rebuild FTS5 index if corrupted.
     * Call periodically (e.g., on app startup once per week).
     * NOTE: Only works with FTS5. Check JarvisDatabase.getFtsVersion() first.
     */
    @SkipQueryVerification
    @Query("INSERT INTO notes_fts(notes_fts) VALUES('rebuild')")
    suspend fun rebuildFtsIndex()

    /**
     * Optimize FTS5 index for better query performance.
     * Call after bulk operations.
     * NOTE: Only works with FTS5. Check JarvisDatabase.getFtsVersion() first.
     */
    @SkipQueryVerification
    @Query("INSERT INTO notes_fts(notes_fts) VALUES('optimize')")
    suspend fun optimizeFtsIndex()

    /**
     * Check FTS5 index integrity.
     * Throws exception if corrupted.
     * NOTE: Only works with FTS5. Check JarvisDatabase.getFtsVersion() first.
     */
    @SkipQueryVerification
    @Query("INSERT INTO notes_fts(notes_fts, rank) VALUES('integrity-check', 1)")
    suspend fun checkFtsIntegrity()

    // =========================================================================
    // AI MEMORY LEARNING QUERIES
    // =========================================================================

    /**
     * Get notes that haven't been analyzed for AI memory learning.
     * Only returns AI-visible notes (not private, not archived).
     * Limited to prevent overwhelming memory with too many notes at once.
     */
    @Query("""
        SELECT * FROM notes 
        WHERE (isReadForMemory = 0 OR isReadForMemory IS NULL)
        AND isArchived = 0 
        AND isFullPrivacy = 0 
        AND excludeFromAiChat = 0
        ORDER BY createdAt DESC
        LIMIT :limit
    """)
    suspend fun getNotesNotReadForMemory(limit: Int = 50): List<Note>

    /**
     * Mark a specific note as read for AI memory analysis.
     */
    @Query("UPDATE notes SET isReadForMemory = 1, updatedAt = :timestamp WHERE id = :noteId")
    suspend fun markNoteAsReadForMemory(noteId: String, timestamp: Long = System.currentTimeMillis())

    /**
     * Mark multiple notes as read for AI memory analysis.
     */
    @Query("UPDATE notes SET isReadForMemory = 1, updatedAt = :timestamp WHERE id IN (:noteIds)")
    suspend fun markNotesAsReadForMemory(noteIds: List<String>, timestamp: Long = System.currentTimeMillis())

    /**
     * Get count of notes that haven't been analyzed for memory.
     * Includes NULL values to handle notes created before the memory feature was added.
     */
    @Query("SELECT COUNT(*) FROM notes WHERE (isReadForMemory = 0 OR isReadForMemory IS NULL) AND isArchived = 0 AND isFullPrivacy = 0 AND excludeFromAiChat = 0")
    suspend fun getUnreadForMemoryCount(): Int

    @Query("SELECT COUNT(*) FROM notes WHERE (isReadForMemory = 0 OR isReadForMemory IS NULL) AND isArchived = 0 AND isFullPrivacy = 0 AND excludeFromAiChat = 0")
    fun getUnreadForMemoryCountSync(): Int

    /**
     * Reset memory read status for all notes (for re-analysis).
     */
    @Query("UPDATE notes SET isReadForMemory = 0, updatedAt = :timestamp")
    suspend fun resetAllMemoryReadStatus(timestamp: Long = System.currentTimeMillis())
}
