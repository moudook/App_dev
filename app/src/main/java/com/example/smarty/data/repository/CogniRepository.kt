package com.example.smarty.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.room.Transaction
import com.example.smarty.data.local.CalendarDao
import com.example.smarty.data.local.CategoryDao
import com.example.smarty.data.local.NoteDao
import com.example.smarty.data.local.NoteVersionDao
import com.example.smarty.data.model.NoteVersion
import com.example.smarty.data.model.CalendarEvent
import com.example.smarty.data.model.Category
import com.example.smarty.data.model.Note
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first

class CogniRepository(
    private val noteDao: NoteDao,
    private val categoryDao: CategoryDao,
    private val calendarDao: CalendarDao,  // Required for calendar functionality
    private val noteVersionDao: NoteVersionDao? = null  // Optional for backwards compatibility
) {
    // Notes
    fun getAllNotes(): Flow<List<Note>> = noteDao.getAllNotes()
        .distinctUntilChanged()

    fun getNotesByCategory(categoryId: String): Flow<List<Note>> =
        noteDao.getNotesByCategory(categoryId)
            .distinctUntilChanged()

    fun getArchivedNotes(): Flow<List<Note>> = noteDao.getArchivedNotes()
        .distinctUntilChanged()

    suspend fun getNoteById(id: String): Note? = noteDao.getNoteById(id)

    fun searchNotes(query: String, types: List<com.example.smarty.data.model.NoteType>): Flow<List<Note>> {
        val hasTypeFilter = types.isNotEmpty()
        // If types list is empty, Room requires a non-empty list for IN clause even if we use the boolean flag logic.
        // We pass a dummy list in that case, but hasTypeFilter=false ensures it's ignored.
        val effectiveTypes = if (types.isEmpty()) listOf(com.example.smarty.data.model.NoteType.BRAIN_DUMP) else types
        return noteDao.searchNotes(query, effectiveTypes, hasTypeFilter)
            .distinctUntilChanged()
    }

    // =========================================================================
    // FTS5 FULL-TEXT SEARCH
    // =========================================================================

    /**
     * Fast full-text search using FTS5.
     * Sanitizes query to prevent FTS5 syntax errors.
     * Returns results ranked by relevance (BM25 algorithm).
     */
    suspend fun searchNotesFts(query: String): List<Note> {
        val sanitizedQuery = sanitizeFtsQuery(query)
        if (sanitizedQuery.isBlank()) return emptyList()
        return try {
            noteDao.searchNotesFts(sanitizedQuery)
        } catch (e: Exception) {
            // Fallback to LIKE search if FTS fails
            noteDao.searchNotes(query, emptyList(), false).first()
        }
    }

    /**
     * FTS search with type filter.
     */
    suspend fun searchNotesFtsWithType(query: String, types: List<com.example.smarty.data.model.NoteType>): List<Note> {
        val sanitizedQuery = sanitizeFtsQuery(query)
        if (sanitizedQuery.isBlank()) return emptyList()
        return try {
            noteDao.searchNotesFtsWithType(sanitizedQuery, types)
        } catch (e: Exception) {
            noteDao.searchNotes(query, types, true).first()
        }
    }

    /**
     * Reactive FTS search as Flow.
     */
    fun searchNotesFtsFlow(query: String): Flow<List<Note>> {
        val sanitizedQuery = sanitizeFtsQuery(query)
        if (sanitizedQuery.isBlank()) {
            return kotlinx.coroutines.flow.flowOf(emptyList())
        }
        return noteDao.searchNotesFtsFlow(sanitizedQuery)
            .distinctUntilChanged()
    }

    /**
     * Sanitize query for FTS5 to prevent syntax errors and INJECTION ATTACKS.
     * Delegates to NoteDao.sanitizeFtsQuery for consistent sanitization across the codebase.
     *
     * SECURITY: FTS5 has special syntax that can be exploited to bypass filters.
     *
     * Attack vectors prevented:
     * - Boolean injection: 'OR 1=1' -> matches everything
     * - Column filter bypass: 'title:*' -> wildcard all titles
     * - Phrase manipulation: '"secret" OR "password"'
     * - Quote injection: Escapes quotes and wraps each term
     */
    private fun sanitizeFtsQuery(query: String): String {
        return NoteDao.sanitizeFtsQuery(query)
    }

    // =========================================================================
    // PAGING3 QUERIES
    // =========================================================================

    companion object {
        // Default paging configuration - 20 items per page, prefetch 2 pages
        private val DEFAULT_PAGING_CONFIG = PagingConfig(
            pageSize = 20,
            prefetchDistance = 40,
            enablePlaceholders = false,
            initialLoadSize = 40
        )

        // OPTIMIZED: Pre-compiled regex patterns (avoid recompilation per call)
        // SECURITY: Enhanced FTS5 injection prevention patterns
        /**
         * FTS5 Special Characters to remove:
         * - Quotes: " '
         * - Wildcards: * ^
         * - Grouping: ( ) [ ]
         * - Column filter: :
         * - Phrase modifiers: { } + -
         */
        private val FTS_SPECIAL_CHARS_REGEX = Regex("""["'*^():{}\[\]+\-]""")

        /**
         * FTS5 Boolean operators that can be exploited for injection.
         * Using word boundaries to match whole words only.
         */
        private val FTS_BOOLEAN_OPERATORS_REGEX = Regex("""\b(AND|OR|NOT|NEAR)\b""", RegexOption.IGNORE_CASE)

        private val WHITESPACE_REGEX = Regex("\\s+")

        // Legacy alias for backwards compatibility
        private val FTS_OPERATORS_REGEX = FTS_SPECIAL_CHARS_REGEX
    }

    /**
     * Get all notes as paginated data.
     * Pinned notes appear first, then sorted by creation date.
     */
    fun getAllNotesPaged(): Flow<PagingData<Note>> {
        return Pager(
            config = DEFAULT_PAGING_CONFIG,
            pagingSourceFactory = { noteDao.getAllNotesPaged() }
        ).flow
    }

    /**
     * Get notes by category as paginated data.
     */
    fun getNotesByCategoryPaged(categoryId: String): Flow<PagingData<Note>> {
        return Pager(
            config = DEFAULT_PAGING_CONFIG,
            pagingSourceFactory = { noteDao.getNotesByCategoryPaged(categoryId) }
        ).flow
    }

    /**
     * Get archived notes as paginated data.
     */
    fun getArchivedNotesPaged(): Flow<PagingData<Note>> {
        return Pager(
            config = DEFAULT_PAGING_CONFIG,
            pagingSourceFactory = { noteDao.getArchivedNotesPaged() }
        ).flow
    }

    /**
     * Search notes with pagination.
     */
    fun searchNotesPaged(query: String?, types: List<com.example.smarty.data.model.NoteType>): Flow<PagingData<Note>> {
        val hasTypeFilter = types.isNotEmpty()
        val effectiveTypes = if (types.isEmpty()) listOf(com.example.smarty.data.model.NoteType.BRAIN_DUMP) else types
        return Pager(
            config = DEFAULT_PAGING_CONFIG,
            pagingSourceFactory = { noteDao.searchNotesPaged(query, effectiveTypes, hasTypeFilter) }
        ).flow
    }

    /**
     * Force a refresh of notes data.
     * Since we use Room Flow, data is automatically updated.
     * This function is mainly for pull-to-refresh visual feedback.
     */
    suspend fun refreshNotes() {
        // Force recalculate category counts to ensure data integrity
        syncAllCategoryCounts()
    }

    @Transaction
    suspend fun insertNote(note: Note) {
        noteDao.insertNote(note)
        note.categoryId?.let { categoryDao.incrementNoteCount(it) }
    }

    @Transaction
    suspend fun insertNotes(notes: List<Note>) {
        if (notes.isEmpty()) return
        noteDao.insertNotes(notes)
        // Update category counts
        notes.mapNotNull { it.categoryId }
            .groupingBy { it }
            .eachCount()
            .forEach { (categoryId, count) ->
                repeat(count) { categoryDao.incrementNoteCount(categoryId) }
            }
    }

    suspend fun updateNote(note: Note) = noteDao.updateNote(note)

    suspend fun updateNotes(notes: List<Note>) {
        if (notes.isEmpty()) return
        noteDao.updateNotes(notes)
    }

    @Transaction
    suspend fun deleteNote(note: Note) {
        noteDao.deleteNote(note)
        note.categoryId?.let { categoryDao.decrementNoteCount(it) }
        // Clean up any calendar events linked to this note
        calendarDao.clearNoteLinkForNote(note.id)
    }

    @Transaction
    suspend fun archiveNote(noteId: String) {
        val note = noteDao.getNoteById(noteId) ?: return
        noteDao.archiveNote(noteId)
        note.categoryId?.let { categoryDao.decrementNoteCount(it) }
    }

    @Transaction
    suspend fun unarchiveNote(noteId: String) {
        val note = noteDao.getNoteById(noteId) ?: return
        noteDao.unarchiveNote(noteId)
        note.categoryId?.let { categoryDao.incrementNoteCount(it) }
    }

    // Bulk operations (Phase 4)
    @Transaction
    suspend fun archiveNotes(noteIds: List<String>) {
        if (noteIds.isEmpty()) return
        val notes = noteDao.getNotesByIds(noteIds)
        noteDao.archiveNotes(noteIds)
        // Decrement category counts for all affected categories
        notes.forEach { note ->
            note.categoryId?.let { categoryDao.decrementNoteCount(it) }
        }
    }

    @Transaction
    suspend fun unarchiveNotes(noteIds: List<String>) {
        if (noteIds.isEmpty()) return
        val notes = noteDao.getNotesByIds(noteIds)
        noteDao.unarchiveNotes(noteIds)
        // Increment category counts for all affected categories
        notes.forEach { note ->
            note.categoryId?.let { categoryDao.incrementNoteCount(it) }
        }
    }

    @Transaction
    suspend fun deleteNotes(noteIds: List<String>) {
        if (noteIds.isEmpty()) return
        val notes = noteDao.getNotesByIds(noteIds)
        noteDao.deleteNotesByIds(noteIds)
        // Decrement category counts and clean up calendar links for all affected notes
        notes.forEach { note ->
            note.categoryId?.let { categoryDao.decrementNoteCount(it) }
            calendarDao.clearNoteLinkForNote(note.id)
        }
    }

    @Transaction
    suspend fun updateNoteCategory(noteId: String, categoryId: String, categoryName: String) {
        val note = noteDao.getNoteById(noteId) ?: return
        val oldCategoryId = note.categoryId
        noteDao.updateNoteCategory(noteId, categoryId, categoryName)
        oldCategoryId?.let { categoryDao.decrementNoteCount(it) }
        categoryDao.incrementNoteCount(categoryId)
    }

    suspend fun updateNoteViewedStatus(noteId: String, isViewed: Boolean) {
        noteDao.updateNoteViewedStatus(noteId, isViewed)
    }

    // =========================================================================
    // PIN OPERATIONS
    // =========================================================================

    suspend fun pinNote(noteId: String) = noteDao.pinNote(noteId)

    suspend fun unpinNote(noteId: String) = noteDao.unpinNote(noteId)

    suspend fun toggleNotePin(noteId: String) = noteDao.togglePin(noteId)

    // =========================================================================
    // REMINDER OPERATIONS
    // =========================================================================

    suspend fun setNoteReminder(noteId: String, reminderText: String, expiresAt: Long? = null) {
        noteDao.setReminder(noteId, reminderText, expiresAt)
    }

    suspend fun clearNoteReminder(noteId: String) = noteDao.clearReminder(noteId)

    fun getNotesWithActiveReminders() = noteDao.getNotesWithActiveReminders()

    // =========================================================================
    // VERSION OPERATIONS (Git-like history)
    // =========================================================================

    /**
     * Save a version snapshot before updating a note
     */
    @Transaction
    suspend fun updateNoteWithVersion(note: Note, changeDescription: String? = null) {
        // Get current version before update
        val currentNote = noteDao.getNoteById(note.id)

        if (currentNote != null && noteVersionDao != null) {
            // Only create version if content actually changed
            val hasChanges = currentNote.title != note.title ||
                             currentNote.content != note.content ||
                             currentNote.summary != note.summary

            if (hasChanges) {
                val latestVersion = noteVersionDao.getLatestVersionNumber(note.id) ?: 0
                val newVersion = NoteVersion(
                    noteId = note.id,
                    title = currentNote.title,
                    content = currentNote.content,
                    summary = currentNote.summary,
                    versionNumber = latestVersion + 1,
                    changeDescription = changeDescription
                )
                noteVersionDao.insertVersion(newVersion)

                // Keep only last 10 versions
                noteVersionDao.pruneOldVersions(note.id, keepCount = 10)
            }
        }

        // Update the actual note
        noteDao.updateNote(note)
    }

    /**
     * Get version history for a note
     */
    fun getNoteVersions(noteId: String): Flow<List<NoteVersion>>? {
        return noteVersionDao?.getVersionsForNote(noteId)
    }

    /**
     * Get version history as one-shot query
     */
    suspend fun getNoteVersionsOnce(noteId: String): List<NoteVersion> {
        return noteVersionDao?.getVersionsForNoteOnce(noteId) ?: emptyList()
    }

    /**
     * Restore a note to a previous version
     */
    @Transaction
    suspend fun restoreNoteVersion(noteId: String, versionId: String): Boolean {
        val version = noteVersionDao?.getVersionById(versionId) ?: return false
        val currentNote = noteDao.getNoteById(noteId) ?: return false

        // Save current state as a version before restoring
        val latestVersion = noteVersionDao?.getLatestVersionNumber(noteId) ?: 0
        val saveVersion = NoteVersion(
            noteId = noteId,
            title = currentNote.title,
            content = currentNote.content,
            summary = currentNote.summary,
            versionNumber = latestVersion + 1,
            changeDescription = "Auto-saved before restore"
        )
        noteVersionDao?.insertVersion(saveVersion)

        // Restore the old version
        val restoredNote = currentNote.copy(
            title = version.title,
            content = version.content,
            summary = version.summary,
            updatedAt = System.currentTimeMillis()
        )
        noteDao.updateNote(restoredNote)

        return true
    }

    /**
     * Get version count for a note
     */
    suspend fun getNoteVersionCount(noteId: String): Int {
        return noteVersionDao?.getVersionCount(noteId) ?: 0
    }

    // Categories
    fun getAllCategories(): Flow<List<Category>> = categoryDao.getAllCategories()
        .distinctUntilChanged()

    suspend fun getCategoryById(id: String): Category? = categoryDao.getCategoryById(id)

    suspend fun getCategoryByName(name: String): Category? = categoryDao.getCategoryByName(name)

    suspend fun insertCategory(category: Category) = categoryDao.insertCategory(category)

    suspend fun updateCategory(category: Category) = categoryDao.updateCategory(category)

    suspend fun deleteCategory(category: Category) = categoryDao.deleteCategory(category)

    /**
     * Safely delete a category with proper cleanup (BUG-028 fix).
     * Uses atomic SQL UPDATE to clear categoryId from all notes,
     * avoiding issues with stale StateFlow data.
     */
    @Transaction
    suspend fun deleteCategoryWithCleanup(category: Category) {
        // First, atomically clear categoryId from all notes in this category
        noteDao.clearCategoryFromNotes(category.id)
        // Then delete the category
        categoryDao.deleteCategory(category)
    }

    /**
     * Get or create a category by name.
     *
     * DB-003/TOCTOU-002: Fixed race condition where concurrent calls could create
     * duplicate categories. Now re-checks after insert to return the winning category.
     */
    suspend fun getOrCreateCategory(name: String): Category {
        // First check if category exists
        categoryDao.getCategoryByName(name)?.let { return it }

        // Create new category
        val newCategory = Category(name = name)
        categoryDao.insertCategory(newCategory)

        // Re-check to handle race condition: if another thread inserted first,
        // return that one instead (prevents duplicates with same name)
        return categoryDao.getCategoryByName(name) ?: newCategory
    }

    // Sync/Recalculation - fixes any count mismatches
    suspend fun syncAllCategoryCounts() = categoryDao.recalculateAllCounts()

    suspend fun syncCategoryCount(categoryId: String) = categoryDao.recalculateCategoryCount(categoryId)

    // =========================================================================
    // CALENDAR EVENTS
    // =========================================================================

    fun getAllCalendarEvents(): Flow<List<CalendarEvent>> = calendarDao.getAllEvents()
        .distinctUntilChanged()

    suspend fun getCalendarEventById(id: String): CalendarEvent? = calendarDao.getEventById(id)

    /**
     * Get calendar event by ID for AI operations.
     * SECURITY: Returns null for private events - AI cannot access them.
     */
    suspend fun getCalendarEventByIdForAi(id: String): CalendarEvent? {
        val event = calendarDao.getEventById(id) ?: return null
        return if (event.isEventPrivate) null else event
    }

    /**
     * Insert a calendar event.
     */
    suspend fun insertCalendarEvent(event: CalendarEvent) {
        calendarDao.insertEvent(event)
    }

    suspend fun updateCalendarEvent(event: CalendarEvent) {
        calendarDao.updateEvent(event)
    }

    suspend fun deleteCalendarEvent(eventId: String) {
        calendarDao.deleteEventById(eventId)
    }

    /**
     * Search calendar events by title (case-insensitive).
     * SECURITY: Filters out private events - AI cannot access private calendar entries.
     */
    suspend fun searchCalendarEvents(query: String): List<CalendarEvent> {
        val allEvents = calendarDao.getAllEventsOnce()
        val lowerQuery = query.lowercase()
        return allEvents.filter { event ->
            !event.isEventPrivate &&  // SECURITY: Exclude private events
            (event.title.lowercase().contains(lowerQuery) ||
            event.description?.lowercase()?.contains(lowerQuery) == true)
        }
    }

    /**
     * Get upcoming AI-visible events.
     */
    suspend fun getUpcomingCalendarEvents(limit: Int = 10): List<CalendarEvent> {
        return calendarDao.getAiVisibleUpcomingEvents(limit = limit)
    }

    /**
     * Get AI-visible events within a time range.
     * BUG FIX (NEW-015): Needed for GetEventsTool to query events by date range.
     * SECURITY: Filters out private events - AI cannot access private calendar entries.
     */
    suspend fun getAiVisibleEventsInRange(startMillis: Long, endMillis: Long): List<CalendarEvent> {
        return calendarDao.getEventsForDay(startMillis, endMillis)
            .filter { !it.isEventPrivate }
    }
}
