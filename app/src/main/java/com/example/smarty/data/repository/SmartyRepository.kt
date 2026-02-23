package com.example.smarty.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.room.Transaction
import android.util.Log
import com.example.smarty.data.local.CalendarDao
import com.example.smarty.data.local.CategoryDao
import com.example.smarty.data.local.SmartyDatabase
import com.example.smarty.data.local.NoteDao
import com.example.smarty.data.local.NoteVersionDao
import com.example.smarty.core.domain.model.NoteVersion
import com.example.smarty.core.domain.model.CalendarEvent
import com.example.smarty.core.domain.model.Category
import com.example.smarty.core.domain.model.Note
import com.example.smarty.data.cache.ToolResultCache
import com.example.smarty.data.repository.ServerSyncRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmartyRepository(
    private val noteDao: NoteDao,
    private val categoryDao: CategoryDao,
    private val calendarDao: CalendarDao,  // Required for calendar functionality
    private val noteVersionDao: NoteVersionDao? = null,  // Optional for backwards compatibility
    private val context: android.content.Context? = null,
    private val syncRepository: SyncRepository? = null
) {
    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * Initialize synchronization for a specific user.
     * Starts observing remote changes and merging them into local database.
     * Also triggers an initial pull from server.
     */
    fun initializeSync(userId: String) {
        syncRepository?.let { repo ->
            repo.initializeForUser(userId)

            // Trigger initial pull from server (includes chat sessions)
            scope.launch {
                if (repo is ServerSyncRepository) {
                    repo.pullFromServer()
                }
            }

            // Observe Remote Notes
            scope.launch {
                repo.getRemoteNotesFlow().collect { remoteNotes ->
                    if (remoteNotes.isNotEmpty()) {
                        upsertRemoteNotes(remoteNotes)
                    }
                }
            }

            // Observe Remote Categories
            scope.launch {
                repo.getRemoteCategoriesFlow().collect { remoteCategories ->
                    if (remoteCategories.isNotEmpty()) {
                        remoteCategories.forEach { category ->
                            val existing = categoryDao.getCategoryById(category.id)
                            // Simple Last-Write-Wins based on lastUpdated
                            if (existing == null || category.lastUpdated > existing.lastUpdated) {
                                categoryDao.insertCategory(category)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Insert/Update notes coming from remote sync.
     * Does NOT trigger sync back to cloud (prevents infinite loop).
     * Maintains category counts.
     */
    private suspend fun upsertRemoteNotes(notes: List<Note>) {
        if (notes.isEmpty()) return

        // 1. Insert/Update in DAO (OnConflictStrategy.REPLACE)
        noteDao.insertNotes(notes)

        // 2. Update category counts locally
        notes.mapNotNull { it.categoryId }
            .groupingBy { it }
            .eachCount()
            .forEach { (categoryId, count) ->
                // This might be inaccurate if we're replacing notes that moved categories
                // A full recalculate might be safer but more expensive.
                // For now, let's trigger a recalc for affected categories
                categoryDao.recalculateCategoryCount(categoryId)
            }

        // 3. Invalidate cache
        ToolResultCache.invalidateNoteCache()
    }

    /**
     * Get application context.
     * Required for some AI fallback logic and resource resolution.
     */
    fun getApplicationContext(): android.content.Context {
        return context ?: com.example.smarty.SmartyApplication.appInstance.applicationContext
    }

    // Notes
    fun getAllNotes(): Flow<List<Note>> = noteDao.getAllNotes()
        .distinctUntilChanged()

    /**
     * Get all notes visible to AI (filtered by privacy settings).
     * Uses PrivacyGuard filtering to exclude private notes.
     */
    suspend fun getAiVisibleNotes(): List<Note> {
        val allNotes = noteDao.getAllNotes().first()
        return allNotes.filter { note ->
            !note.isFullPrivacy && !note.excludeFromAiChat && !note.isArchived
        }
    }

    fun getNotesByCategory(categoryId: String): Flow<List<Note>> =
        noteDao.getNotesByCategory(categoryId)
            .distinctUntilChanged()

    fun getArchivedNotes(): Flow<List<Note>> = noteDao.getArchivedNotes()
        .distinctUntilChanged()

    suspend fun getNoteById(id: String): Note? = noteDao.getNoteById(id)

    /**
     * Observe a note by ID as a Flow.
     * Emits new value whenever the note is updated in the database.
     * Used for reactive detail view that auto-updates when AI processing completes.
     */
    fun getNoteByIdFlow(id: String): Flow<Note?> = noteDao.getNoteByIdFlow(id)
        .distinctUntilChanged()

    fun searchNotes(query: String, types: List<com.example.smarty.core.domain.model.NoteType>): Flow<List<Note>> {
        val hasTypeFilter = types.isNotEmpty()
        // If types list is empty, Room requires a non-empty list for IN clause even if we use the boolean flag logic.
        // We pass a dummy list in that case, but hasTypeFilter=false ensures it's ignored.
        val effectiveTypes = if (types.isEmpty()) listOf(com.example.smarty.core.domain.model.NoteType.BRAIN_DUMP) else types
        return noteDao.searchNotes(query, effectiveTypes, hasTypeFilter)
            .distinctUntilChanged()
    }

    // =========================================================================
    // PROCESSING QUEUE OPERATIONS
    // =========================================================================

    suspend fun getStuckProcessingNotes(timeoutThreshold: Long): List<Note> {
        return noteDao.getStuckProcessingNotes(timeoutThreshold)
    }

    suspend fun getNotesByProcessingStatus(status: com.example.smarty.core.domain.model.ProcessingStatus): List<Note> {
        return noteDao.getNotesByProcessingStatus(status)
    }

    suspend fun updateProcessingStatus(noteId: String, status: com.example.smarty.core.domain.model.ProcessingStatus) {
        noteDao.updateProcessingStatus(noteId, status)
    }

    suspend fun resetStuckNotes(timeoutThreshold: Long): Int {
        return noteDao.resetStuckNotes(timeoutThreshold)
    }

    suspend fun getNextPendingNote(): Note? {
        return noteDao.getNextPendingNote()
    }

    suspend fun getPendingProcessingCount(): Int {
        return noteDao.getPendingProcessingCount()
    }

    /**
     * Optimize the search index for better performance.
     */
    suspend fun optimizeFtsIndex() {
        try {
            // Only FTS5 supports the 'optimize' command
            if (SmartyDatabase.getFtsVersion() == 5) {
                noteDao.optimizeFtsIndex()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Search index optimization failed: ${e.message}")
        }
    }

    /**
     * Legacy alias for optimizeFtsIndex
     */
    suspend fun optimizeSearchIndex() {
        optimizeFtsIndex()
    }

    // =========================================================================
    // FTS FULL-TEXT SEARCH (with FTS5/FTS4/LIKE fallback)
    // =========================================================================

    /**
     * Fast full-text search using FTS.
     * Automatically uses FTS5, FTS4, or LIKE search based on device capability.
     * - FTS5: Ranked by relevance (BM25 algorithm)
     * - FTS4: Ordered by creation date (no BM25)
     * - LIKE: Fallback if no FTS available
     */
    suspend fun searchNotesFts(query: String): List<Note> {
        val sanitizedQuery = NoteDao.sanitizeFtsQuery(query)
        if (sanitizedQuery.isBlank()) return emptyList()

        return try {
            when (SmartyDatabase.getFtsVersion()) {
                5 -> {
                    Log.d(TAG, "Using FTS5 search")
                    noteDao.searchNotesFts(sanitizedQuery)
                }
                4 -> {
                    Log.d(TAG, "Using FTS4 search (no bm25 ranking)")
                    noteDao.searchNotesFts4(sanitizedQuery)
                }
                else -> {
                    Log.d(TAG, "FTS not available, using LIKE search")
                    noteDao.searchNotes(query, emptyList(), false).first()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "FTS search failed, falling back to LIKE: ${e.message}")
            noteDao.searchNotes(query, emptyList(), false).first()
        }
    }

    /**
     * FTS search with type filter.
     */
    suspend fun searchNotesFtsWithType(query: String, types: List<com.example.smarty.core.domain.model.NoteType>): List<Note> {
        val sanitizedQuery = NoteDao.sanitizeFtsQuery(query)
        if (sanitizedQuery.isBlank()) return emptyList()

        return try {
            when (SmartyDatabase.getFtsVersion()) {
                5 -> noteDao.searchNotesFtsWithType(sanitizedQuery, types)
                4 -> noteDao.searchNotesFts4WithType(sanitizedQuery, types)
                else -> noteDao.searchNotes(query, types, true).first()
            }
        } catch (e: Exception) {
            Log.w(TAG, "FTS search with type failed, falling back: ${e.message}")
            noteDao.searchNotes(query, types, true).first()
        }
    }

    /**
     * Reactive FTS search as Flow.
     */
    fun searchNotesFtsFlow(query: String): Flow<List<Note>> {
        val sanitizedQuery = NoteDao.sanitizeFtsQuery(query)
        if (sanitizedQuery.isBlank()) {
            return kotlinx.coroutines.flow.flowOf(emptyList())
        }

        return try {
            when (SmartyDatabase.getFtsVersion()) {
                5 -> noteDao.searchNotesFtsFlow(sanitizedQuery).distinctUntilChanged()
                4 -> noteDao.searchNotesFts4Flow(sanitizedQuery).distinctUntilChanged()
                else -> noteDao.searchNotes(query, emptyList(), false).distinctUntilChanged()
            }
        } catch (e: Exception) {
            Log.w(TAG, "FTS flow search failed, falling back: ${e.message}")
            noteDao.searchNotes(query, emptyList(), false).distinctUntilChanged()
        }
    }

    // =========================================================================
    // PAGING3 QUERIES
    // =========================================================================

    companion object {
        private const val TAG = "SmartyRepository"

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
    fun searchNotesPaged(query: String?, types: List<com.example.smarty.core.domain.model.NoteType>): Flow<PagingData<Note>> {
        val hasTypeFilter = types.isNotEmpty()
        val effectiveTypes = if (types.isEmpty()) listOf(com.example.smarty.core.domain.model.NoteType.BRAIN_DUMP) else types
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

        // Cloud Sync
        syncRepository?.let { repo ->
            scope.launch { repo.syncNote(note) }
        }
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
                categoryDao.updateNoteCount(categoryId, count)
            }

        // Cloud Sync
        syncRepository?.let { repo ->
            scope.launch {
                notes.forEach { repo.syncNote(it) }
            }
        }
    }

    /**
     * Update a note and handle category count changes.
     * If the note's category changed (e.g., after AI processing), update counts accordingly.
     */
    @Transaction
    suspend fun updateNote(note: Note) {
        // Get the old note to check if category changed
        val oldNote = noteDao.getNoteById(note.id)
        val oldCategoryId = oldNote?.categoryId
        val newCategoryId = note.categoryId

        // Update the note
        noteDao.updateNote(note)

        // Handle category count changes
        if (oldCategoryId != newCategoryId) {
            // Decrement old category count (if note was in a category)
            oldCategoryId?.let { categoryDao.decrementNoteCount(it) }
            // Increment new category count (if note is now in a category)
            newCategoryId?.let { categoryDao.incrementNoteCount(it) }
        }

        // SECURITY: Invalidate AI tool cache - privacy state may have changed
        ToolResultCache.invalidateNoteCache()

        // Cloud Sync
        syncRepository?.let { repo ->
            scope.launch { repo.syncNote(note) }
        }
    }

    /**
     * Update multiple notes and handle category count changes.
     * Efficiently handles category changes by batching count updates.
     */
    @Transaction
    suspend fun updateNotes(notes: List<Note>) {
        if (notes.isEmpty()) return

        // Get old notes to check for category changes
        val noteIds = notes.map { it.id }
        val oldNotes = noteIds.mapNotNull { noteDao.getNoteById(it) }
        val oldCategoryMap = oldNotes.associate { it.id to it.categoryId }

        // Update all notes
        noteDao.updateNotes(notes)

        // Calculate category count changes
        val categoryChanges = mutableMapOf<String, Int>() // categoryId -> delta

        for (note in notes) {
            val oldCategoryId = oldCategoryMap[note.id]
            val newCategoryId = note.categoryId

            if (oldCategoryId != newCategoryId) {
                // Decrement old category
                oldCategoryId?.let {
                    categoryChanges[it] = (categoryChanges[it] ?: 0) - 1
                }
                // Increment new category
                newCategoryId?.let {
                    categoryChanges[it] = (categoryChanges[it] ?: 0) + 1
                }
            }
        }

        // Apply category count changes
        for ((categoryId, delta) in categoryChanges) {
            if (delta != 0) {
                categoryDao.updateNoteCount(categoryId, delta)
            }
        }

        // SECURITY: Invalidate AI tool cache - privacy state may have changed
        ToolResultCache.invalidateNoteCache()
    }

    @Transaction
    suspend fun deleteNote(note: Note) {
        noteDao.deleteNote(note)
        note.categoryId?.let { categoryDao.decrementNoteCount(it) }
        // Clean up any calendar events linked to this note
        calendarDao.clearNoteLinkForNote(note.id)
        // SECURITY: Invalidate AI tool cache - note no longer exists
        ToolResultCache.invalidateNoteCache()

        // Cloud Sync
        syncRepository?.let { repo ->
            scope.launch { repo.deleteNote(note.id) }
        }
    }

    @Transaction
    suspend fun archiveNote(noteId: String) {
        val note = noteDao.getNoteById(noteId) ?: return
        noteDao.archiveNote(noteId)
        note.categoryId?.let { categoryDao.decrementNoteCount(it) }
        // SECURITY: Invalidate AI tool cache - note visibility changed
        ToolResultCache.invalidateNoteCache()
    }

    @Transaction
    suspend fun unarchiveNote(noteId: String) {
        val note = noteDao.getNoteById(noteId) ?: return
        noteDao.unarchiveNote(noteId)
        note.categoryId?.let { categoryDao.incrementNoteCount(it) }
        // SECURITY: Invalidate AI tool cache - note visibility changed
        ToolResultCache.invalidateNoteCache()
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
        // SECURITY: Invalidate AI tool cache - note visibility changed
        ToolResultCache.invalidateNoteCache()
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
        // SECURITY: Invalidate AI tool cache - note visibility changed
        ToolResultCache.invalidateNoteCache()
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
        // SECURITY: Invalidate AI tool cache - notes deleted
        ToolResultCache.invalidateNoteCache()
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

    suspend fun insertCategory(category: Category) {
        categoryDao.insertCategory(category)
        // Cloud Sync
        syncRepository?.let { repo ->
            scope.launch { repo.syncCategory(category) }
        }
    }

    suspend fun updateCategory(category: Category) {
        categoryDao.updateCategory(category)
        // Cloud Sync
        syncRepository?.let { repo ->
            scope.launch { repo.syncCategory(category) }
        }
    }

    suspend fun deleteCategory(category: Category) {
        categoryDao.deleteCategory(category)
        // Cloud Sync
        syncRepository?.let { repo ->
            scope.launch { repo.deleteCategory(category.id) }
        }
    }

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
        // Validate category name: max 10 characters
        if (name.length > 10) {
            // Truncate to 10 characters if it exceeds the limit
            val truncatedName = name.take(10)
            // First check if truncated category exists
            categoryDao.getCategoryByName(truncatedName)?.let { return it }

            // Create new category with truncated name
            val newCategory = Category(id = java.util.UUID.randomUUID().toString(), name = truncatedName, createdAt = System.currentTimeMillis())
            categoryDao.insertCategory(newCategory)

            // Re-check to handle race condition: if another thread inserted first,
            // return that one instead (prevents duplicates with same name)
            return categoryDao.getCategoryByName(truncatedName) ?: newCategory
        }

        // First check if category exists
        categoryDao.getCategoryByName(name)?.let { return it }

        // Create new category
        val newCategory = Category(id = java.util.UUID.randomUUID().toString(), name = name, createdAt = System.currentTimeMillis())
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

    suspend fun getNoteCount(): Int {
        return noteDao.getNoteCount()
    }

    /**
     * Get all categories via coroutine (non-blocking).
     * Replaces blocking getAllCategoriesSync.
     */
    suspend fun getAllCategoriesOneShot(): List<Category> {
        return categoryDao.getAllCategories().first()
    }

    /**
     * DEPRECATED: Get all categories synchronously (blocking).
     * This method blocks the current thread and should be avoided.
     * Kept only for legacy Java compatibility if absolutely necessary.
     * Prefer getAllCategoriesOneShot() or getAllCategories() Flow.
     */
    fun getAllCategoriesSync(): List<Category> {
        return kotlinx.coroutines.runBlocking {
            categoryDao.getAllCategories().first()
        }
    }

    // =========================================================================
    // CALENDAR OPERATIONS
    // =========================================================================

    fun getAllEvents(): Flow<List<CalendarEvent>> {
        return calendarDao.getAllEvents()
    }

    suspend fun getEventById(id: String): CalendarEvent? {
        return calendarDao.getEventById(id)
    }

    suspend fun insertEvent(event: CalendarEvent) {
        calendarDao.insertEvent(event)
    }

    suspend fun updateEvent(event: CalendarEvent) {
        calendarDao.updateEvent(event)
    }

    suspend fun deleteEvent(event: CalendarEvent) {
        calendarDao.deleteEvent(event)
    }

    suspend fun deleteEventById(id: String) {
        calendarDao.deleteEventById(id)
    }

    suspend fun deleteEventsByIds(ids: List<String>) {
        calendarDao.deleteEventsByIds(ids)
    }

    fun getEventsInRange(startMillis: Long, endMillis: Long): Flow<List<CalendarEvent>> {
        return calendarDao.getEventsInRange(startMillis, endMillis)
    }

    suspend fun getEventsForDay(dayStartMillis: Long, dayEndMillis: Long): List<CalendarEvent> {
        return calendarDao.getEventsForDay(dayStartMillis, dayEndMillis)
    }

    fun getUpcomingEvents(nowMillis: Long = System.currentTimeMillis(), limit: Int = 20): Flow<List<CalendarEvent>> {
        return calendarDao.getUpcomingEvents(nowMillis, limit)
    }

    suspend fun getTodayEvents(dayStart: Long, dayEnd: Long): List<CalendarEvent> {
        return calendarDao.getTodayEvents(dayStart, dayEnd)
    }

    fun getEventsForNote(noteId: String): Flow<List<CalendarEvent>> {
        return calendarDao.getEventsForNote(noteId)
    }

    suspend fun clearNoteLinkForNote(noteId: String) {
        calendarDao.clearNoteLinkForNote(noteId)
    }

    fun getAiVisibleEvents(): Flow<List<CalendarEvent>> {
        return calendarDao.getAiVisibleEvents()
    }

    suspend fun getAiVisibleUpcomingEvents(nowMillis: Long = System.currentTimeMillis(), limit: Int = 10): List<CalendarEvent> {
        return calendarDao.getAiVisibleUpcomingEvents(nowMillis, limit)
    }

    suspend fun getAllEventsOnce(): List<CalendarEvent> {
        return calendarDao.getAllEventsOnce()
    }

    suspend fun getActiveTimersOnce(): List<com.example.smarty.core.domain.model.SmartyTimer> {
        return calendarDao.getActiveTimersOnce()
    }

    suspend fun deleteAllEvents() {
        calendarDao.deleteAllEvents()
    }

    suspend fun getEventCount(): Int {
        return calendarDao.getEventCount()
    }

    suspend fun hasEventsOnDay(dayStart: Long, dayEnd: Long): Boolean {
        return calendarDao.hasEventsOnDay(dayStart, dayEnd)
    }

    /**
     * Clear all user data from local storage.
     * CRITICAL: This must be called on sign-out to prevent data leakage to next user.
     */
    suspend fun clearAllUserData() {
        // Clear all tables to prevent data leakage between users
        noteDao.deleteAllNotes()
        categoryDao.deleteAllCategories()

        // Access DAOs not passed in constructor via database instance
        val database = SmartyDatabase.getDatabase(getApplicationContext())

        database.chatDao().deleteAllMessages()
        database.chatDao().deleteAllSessions()
        calendarDao.deleteAllEvents()
        calendarDao.deleteAllTimers()
        database.aiCacheDao().clearAll()
        database.impressedLogDao().deleteAllLogs()
        // agentExecutionDao removed - no longer in use
        noteVersionDao?.deleteAllVersions()
    }
}
