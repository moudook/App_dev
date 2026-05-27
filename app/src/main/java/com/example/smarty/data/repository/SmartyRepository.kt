package com.example.smarty.data.repository

import android.util.Log
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.room.Transaction
import com.example.smarty.core.domain.model.CalendarEvent
import com.example.smarty.core.domain.model.Category
import com.example.smarty.core.domain.model.Note
import com.example.smarty.core.domain.model.NoteVersion
import com.example.smarty.data.cache.ToolResultCache
import com.example.smarty.data.local.CalendarDao
import com.example.smarty.data.local.CategoryDao
import com.example.smarty.data.local.NoteDao
import com.example.smarty.data.local.NoteVersionDao
import com.example.smarty.data.local.NoteVersionEntity
import com.example.smarty.data.local.SmartyDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Main repository for data operations.
 *
 * IMPROVEMENTS:
 * - Replaced sequential scope.launch with async/await for parallel operations
 * - Removed runBlocking usage in favor of suspend functions
 * - Batched category count updates for better efficiency
 * - Consolidated redundant null checks
 * - Added structured concurrency improvements
 */
class SmartyRepository(
    private val noteDao: NoteDao,
    private val categoryDao: CategoryDao,
    private val calendarDao: CalendarDao,
    private val noteVersionDao: NoteVersionDao? = null,
    private val context: android.content.Context? = null,
    private val syncRepository: SyncRepository? = null,
) {
    companion object {
        private const val TAG = "SmartyRepository"

        // Default paging configuration - 20 items per page, prefetch 2 pages
        private val DEFAULT_PAGING_CONFIG =
            PagingConfig(
                pageSize = 20,
                prefetchDistance = 40,
                enablePlaceholders = false,
                initialLoadSize = 40,
            )

        // OPTIMIZATION: Pre-compiled regex patterns (avoid recompilation per call)
        // SECURITY: Enhanced FTS5 injection prevention patterns
        private val FTS_SPECIAL_CHARS_REGEX = Regex("""["'*^():{}\[\]+\-]""")
        private val FTS_BOOLEAN_OPERATORS_REGEX = Regex("""\b(AND|OR|NOT|NEAR)\b""", RegexOption.IGNORE_CASE)
        private val WHITESPACE_REGEX = Regex("\\s+")

        // Legacy alias for backwards compatibility
        private val FTS_OPERATORS_REGEX = FTS_SPECIAL_CHARS_REGEX
    }

    // OPTIMIZATION: Use Dispatchers.Default for CPU-bound operations, IO for DB
    private val scope = CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    /**
     * Initialize synchronization for a specific user.
     * Starts observing remote changes and merging them into local database.
     * Also triggers an initial pull from server.
     *
     * IMPROVEMENT: Uses async/await for parallel initialization instead of sequential launches
     */
    fun initializeSync(userId: String) {
        syncRepository?.let { repo ->
            repo.initializeForUser(userId)

            // OPTIMIZATION: Use async for parallel initialization with proper error handling
            scope.launch {
                try {
                    // Run initialization tasks in parallel
                    val deferreds =
                        listOf(
                            async {
                                if (repo is ServerSyncRepository) {
                                    repo.pullFromServer()
                                }
                            },
                            async {
                                repo.getRemoteNotesFlow().collect { remoteNotes ->
                                    if (remoteNotes.isNotEmpty()) {
                                        upsertRemoteNotes(remoteNotes)
                                    }
                                }
                            },
                            async {
                                repo.getRemoteCategoriesFlow().collect { remoteCategories ->
                                    if (remoteCategories.isNotEmpty()) {
                                        upsertRemoteCategories(remoteCategories)
                                    }
                                }
                            },
                        )

                    // Wait for all initialization tasks
                    deferreds.awaitAll()
                } catch (e: Exception) {
                    Log.e(TAG, "Error during sync initialization", e)
                }
            }
        }
    }

    /**
     * OPTIMIZATION: Extracted category upsert logic for reusability.
     */
    private suspend fun upsertRemoteCategories(remoteCategories: List<Category>) {
        val categoriesToUpdate =
            remoteCategories.mapNotNull { category ->
                val existing = categoryDao.getCategoryById(category.id)
                if (existing == null || category.lastUpdated > existing.lastUpdated) {
                    category
                } else {
                    null
                }
            }

        if (categoriesToUpdate.isNotEmpty()) {
            categoryDao.insertCategories(categoriesToUpdate)
        }
    }

    /**
     * Insert/Update notes coming from remote sync.
     * Does NOT trigger sync back to cloud (prevents infinite loop).
     * Maintains category counts.
     *
     * IMPROVEMENT: Batch category count updates instead of individual calls
     */
    private suspend fun upsertRemoteNotes(notes: List<Note>) {
        if (notes.isEmpty()) return

        // 1. Insert/Update in DAO (OnConflictStrategy.REPLACE)
        noteDao.insertNotes(notes)

        // 2. OPTIMIZATION: Batch update category counts
        // Group notes by category and count in a single pass
        val categoryCounts =
            notes.mapNotNull { it.categoryId }
                .groupingBy { it }
                .eachCount()

        // Recalculate counts for affected categories only
        categoryCounts.keys.forEach { categoryId ->
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

    // =========================================================================
    // NOTES - FLOW OPERATIONS
    // =========================================================================

    fun getAllNotes(): Flow<List<Note>> = noteDao.getAllNotes().distinctUntilChanged()

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

    fun getNotesByCategory(categoryId: String): Flow<List<Note>> = noteDao.getNotesByCategory(categoryId).distinctUntilChanged()

    fun getArchivedNotes(): Flow<List<Note>> = noteDao.getArchivedNotes().distinctUntilChanged()

    suspend fun getNoteById(id: String): Note? = noteDao.getNoteById(id)

    fun getNoteByIdFlow(id: String): Flow<Note?> = noteDao.getNoteByIdFlow(id).distinctUntilChanged()

    fun searchNotes(
        query: String,
        types: List<com.example.smarty.core.domain.model.NoteType>,
    ): Flow<List<Note>> {
        val hasTypeFilter = types.isNotEmpty()
        val effectiveTypes = if (types.isEmpty()) listOf(com.example.smarty.core.domain.model.NoteType.BRAIN_DUMP) else types
        return noteDao.searchNotes(query, effectiveTypes, hasTypeFilter).distinctUntilChanged()
    }

    // =========================================================================
    // PROCESSING QUEUE OPERATIONS
    // =========================================================================

    suspend fun getStuckProcessingNotes(timeoutThreshold: Long): List<Note> = noteDao.getStuckProcessingNotes(timeoutThreshold)

    suspend fun getNotesByProcessingStatus(status: com.example.smarty.core.domain.model.ProcessingStatus): List<Note> =
        noteDao.getNotesByProcessingStatus(status)

    suspend fun updateProcessingStatus(
        noteId: String,
        status: com.example.smarty.core.domain.model.ProcessingStatus,
    ) {
        noteDao.updateProcessingStatus(noteId, status)
    }

    suspend fun resetStuckNotes(timeoutThreshold: Long): Int = noteDao.resetStuckNotes(timeoutThreshold)

    suspend fun getNextPendingNote(): Note? = noteDao.getNextPendingNote()

    suspend fun getPendingProcessingCount(): Int = noteDao.getPendingProcessingCount()

    /**
     * Optimize the search index for better performance.
     */
    suspend fun optimizeFtsIndex() {
        try {
            if (SmartyDatabase.getFtsVersion() == 5) {
                noteDao.optimizeFtsIndex()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Search index optimization failed: ${e.message}")
        }
    }

    suspend fun optimizeSearchIndex() = optimizeFtsIndex()

    // =========================================================================
    // FTS FULL-TEXT SEARCH
    // =========================================================================

    /**
     * Fast full-text search using FTS.
     * Automatically uses FTS5, FTS4, or LIKE search based on device capability.
     */
    suspend fun searchNotesFts(query: String): List<Note> {
        val sanitizedQuery = NoteDao.sanitizeFtsQuery(query)
        if (sanitizedQuery.isBlank()) return emptyList()

        return runCatching {
            when (SmartyDatabase.getFtsVersion()) {
                5 -> noteDao.searchNotesFts(sanitizedQuery)
                4 -> noteDao.searchNotesFts4(sanitizedQuery)
                else -> noteDao.searchNotes(query, emptyList(), false).first()
            }
        }.getOrElse { e ->
            Log.w(TAG, "FTS search failed, falling back to LIKE: ${e.message}")
            noteDao.searchNotes(query, emptyList(), false).first()
        }
    }

    /**
     * FTS search with type filter.
     */
    suspend fun searchNotesFtsWithType(
        query: String,
        types: List<com.example.smarty.core.domain.model.NoteType>,
    ): List<Note> {
        val sanitizedQuery = NoteDao.sanitizeFtsQuery(query)
        if (sanitizedQuery.isBlank()) return emptyList()

        return runCatching {
            when (SmartyDatabase.getFtsVersion()) {
                5 -> noteDao.searchNotesFtsWithType(sanitizedQuery, types)
                4 -> noteDao.searchNotesFts4WithType(sanitizedQuery, types)
                else -> noteDao.searchNotes(query, types, true).first()
            }
        }.getOrElse { e ->
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

        return runCatching {
            when (SmartyDatabase.getFtsVersion()) {
                5 -> noteDao.searchNotesFtsFlow(sanitizedQuery).distinctUntilChanged()
                4 -> noteDao.searchNotesFts4Flow(sanitizedQuery).distinctUntilChanged()
                else -> noteDao.searchNotes(query, emptyList(), false).distinctUntilChanged()
            }
        }.getOrElse { e ->
            Log.w(TAG, "FTS flow search failed, falling back: ${e.message}")
            noteDao.searchNotes(query, emptyList(), false).distinctUntilChanged()
        }
    }

    // =========================================================================
    // PAGING3 QUERIES
    // =========================================================================

    fun getAllNotesPaged(): Flow<PagingData<Note>> =
        Pager(
            config = DEFAULT_PAGING_CONFIG,
            pagingSourceFactory = { noteDao.getAllNotesPaged() },
        ).flow

    fun getNotesByCategoryPaged(categoryId: String): Flow<PagingData<Note>> =
        Pager(
            config = DEFAULT_PAGING_CONFIG,
            pagingSourceFactory = { noteDao.getNotesByCategoryPaged(categoryId) },
        ).flow

    fun getArchivedNotesPaged(): Flow<PagingData<Note>> =
        Pager(
            config = DEFAULT_PAGING_CONFIG,
            pagingSourceFactory = { noteDao.getArchivedNotesPaged() },
        ).flow

    fun searchNotesPaged(
        query: String?,
        types: List<com.example.smarty.core.domain.model.NoteType>,
    ): Flow<PagingData<Note>> {
        val hasTypeFilter = types.isNotEmpty()
        val effectiveTypes = if (types.isEmpty()) listOf(com.example.smarty.core.domain.model.NoteType.BRAIN_DUMP) else types
        return Pager(
            config = DEFAULT_PAGING_CONFIG,
            pagingSourceFactory = { noteDao.searchNotesPaged(query, effectiveTypes, hasTypeFilter) },
        ).flow
    }

    suspend fun refreshNotes() {
        syncAllCategoryCounts()
    }

    // =========================================================================
    // NOTE CRUD OPERATIONS
    // =========================================================================

    @Transaction
    suspend fun insertNote(note: Note) {
        noteDao.insertNote(note)
        note.categoryId?.let { categoryDao.incrementNoteCount(it) }

        syncRepository?.let { repo ->
            scope.launch { repo.syncNote(note) }
        }
    }

    @Transaction
    suspend fun insertNotes(notes: List<Note>) {
        if (notes.isEmpty()) return
        noteDao.insertNotes(notes)

        // OPTIMIZATION: Batch category count updates
        notes.mapNotNull { it.categoryId }
            .groupingBy { it }
            .eachCount()
            .forEach { (categoryId, count) ->
                categoryDao.updateNoteCount(categoryId, count)
            }

        syncRepository?.let { repo ->
            scope.launch {
                notes.forEach { repo.syncNote(it) }
            }
        }
    }

    /**
     * Update a note and handle category count changes.
     */
    @Transaction
    suspend fun updateNote(note: Note) {
        val oldNote = noteDao.getNoteById(note.id)
        val oldCategoryId = oldNote?.categoryId
        val newCategoryId = note.categoryId

        noteDao.updateNote(note)

        if (oldCategoryId != newCategoryId) {
            oldCategoryId?.let { categoryDao.decrementNoteCount(it) }
            newCategoryId?.let { categoryDao.incrementNoteCount(it) }
        }

        ToolResultCache.invalidateNoteCache()

        syncRepository?.let { repo ->
            scope.launch { repo.syncNote(note) }
        }
    }

    /**
     * Update multiple notes with batched category count updates.
     * IMPROVEMENT: Single pass calculation of category changes
     */
    @Transaction
    suspend fun updateNotes(notes: List<Note>) {
        if (notes.isEmpty()) return

        // Get old notes to check for category changes
        val oldNotes = notes.mapNotNull { noteDao.getNoteById(it.id) }
        val oldCategoryMap = oldNotes.associate { it.id to it.categoryId }

        noteDao.updateNotes(notes)

        // OPTIMIZATION: Calculate all category changes in a single pass
        val categoryChanges = mutableMapOf<String, Int>()

        for (note in notes) {
            val oldCategoryId = oldCategoryMap[note.id]
            val newCategoryId = note.categoryId

            if (oldCategoryId != newCategoryId) {
                oldCategoryId?.let { categoryChanges[it] = (categoryChanges[it] ?: 0) - 1 }
                newCategoryId?.let { categoryChanges[it] = (categoryChanges[it] ?: 0) + 1 }
            }
        }

        // Apply batched category count changes
        categoryChanges.forEach { (categoryId, delta) ->
            if (delta != 0) {
                categoryDao.updateNoteCount(categoryId, delta)
            }
        }

        ToolResultCache.invalidateNoteCache()
    }

    @Transaction
    suspend fun deleteNote(note: Note) {
        noteDao.deleteNote(note)
        note.categoryId?.let { categoryDao.decrementNoteCount(it) }
        calendarDao.clearNoteLinkForNote(note.id)
        ToolResultCache.invalidateNoteCache()

        syncRepository?.let { repo ->
            scope.launch { repo.deleteNote(note.id) }
        }
    }

    @Transaction
    suspend fun archiveNote(noteId: String) {
        val note = noteDao.getNoteById(noteId) ?: return
        noteDao.archiveNote(noteId)
        note.categoryId?.let { categoryDao.decrementNoteCount(it) }
        ToolResultCache.invalidateNoteCache()
    }

    @Transaction
    suspend fun unarchiveNote(noteId: String) {
        val note = noteDao.getNoteById(noteId) ?: return
        noteDao.unarchiveNote(noteId)
        note.categoryId?.let { categoryDao.incrementNoteCount(it) }
        ToolResultCache.invalidateNoteCache()
    }

    // =========================================================================
    // BULK OPERATIONS
    // =========================================================================

    @Transaction
    suspend fun archiveNotes(noteIds: List<String>) {
        if (noteIds.isEmpty()) return
        val notes = noteDao.getNotesByIds(noteIds)
        noteDao.archiveNotes(noteIds)
        notes.forEach { note ->
            note.categoryId?.let { categoryDao.decrementNoteCount(it) }
        }
        ToolResultCache.invalidateNoteCache()
    }

    @Transaction
    suspend fun unarchiveNotes(noteIds: List<String>) {
        if (noteIds.isEmpty()) return
        val notes = noteDao.getNotesByIds(noteIds)
        noteDao.unarchiveNotes(noteIds)
        notes.forEach { note ->
            note.categoryId?.let { categoryDao.incrementNoteCount(it) }
        }
        ToolResultCache.invalidateNoteCache()
    }

    @Transaction
    suspend fun deleteNotes(noteIds: List<String>) {
        if (noteIds.isEmpty()) return
        val notes = noteDao.getNotesByIds(noteIds)
        noteDao.deleteNotesByIds(noteIds)
        notes.forEach { note ->
            note.categoryId?.let { categoryDao.decrementNoteCount(it) }
            calendarDao.clearNoteLinkForNote(note.id)
        }
        ToolResultCache.invalidateNoteCache()
    }

    @Transaction
    suspend fun updateNoteCategory(
        noteId: String,
        categoryId: String,
        categoryName: String,
    ) {
        val note = noteDao.getNoteById(noteId) ?: return
        val oldCategoryId = note.categoryId
        noteDao.updateNoteCategory(noteId, categoryId, categoryName)
        oldCategoryId?.let { categoryDao.decrementNoteCount(it) }
        categoryDao.incrementNoteCount(categoryId)
    }

    suspend fun updateNoteViewedStatus(
        noteId: String,
        isViewed: Boolean,
    ) {
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

    suspend fun setNoteReminder(
        noteId: String,
        reminderText: String,
        expiresAt: Long? = null,
    ) {
        noteDao.setReminder(noteId, reminderText, expiresAt)
    }

    suspend fun clearNoteReminder(noteId: String) = noteDao.clearReminder(noteId)

    fun getNotesWithActiveReminders() = noteDao.getNotesWithActiveReminders()

    // =========================================================================
    // VERSION OPERATIONS
    // =========================================================================

    @Transaction
    suspend fun updateNoteWithVersion(
        note: Note,
        changeDescription: String? = null,
    ) {
        val currentNote = noteDao.getNoteById(note.id)

        if (currentNote != null && noteVersionDao != null) {
            val hasChanges =
                currentNote.title != note.title ||
                    currentNote.content != note.content ||
                    currentNote.summary != note.summary

            if (hasChanges) {
                val latestVersion = noteVersionDao.getLatestVersionNumber(note.id) ?: 0
                val newVersion =
                    NoteVersionEntity(
                        id = java.util.UUID.randomUUID().toString(),
                        noteId = note.id,
                        title = currentNote.title,
                        content = currentNote.content,
                        summary = currentNote.summary,
                        versionNo = latestVersion + 1,
                        changeDescription = changeDescription,
                        createdAt = System.currentTimeMillis(),
                    )
                noteVersionDao.insertVersion(newVersion)
                noteVersionDao.pruneOldVersions(note.id, keepCount = 10)
            }
        }

        noteDao.updateNote(note)
    }

    fun getNoteVersions(noteId: String): Flow<List<NoteVersion>>? =
        noteVersionDao?.getVersionsForNote(noteId)?.map { list ->
            list.map {
                it.toDomain()
            }
        }

    suspend fun getNoteVersionsOnce(noteId: String): List<NoteVersion> =
        noteVersionDao?.getVersionsForNoteOnce(noteId)?.map {
            it.toDomain()
        } ?: emptyList()

    @Transaction
    suspend fun restoreNoteVersion(
        noteId: String,
        versionId: String,
    ): Boolean {
        val versionEntity = noteVersionDao?.getVersionById(versionId) ?: return false
        val version = versionEntity.toDomain()
        val currentNote = noteDao.getNoteById(noteId) ?: return false

        val latestVersion = noteVersionDao.getLatestVersionNumber(noteId) ?: 0
        val saveVersion =
            NoteVersionEntity(
                id = java.util.UUID.randomUUID().toString(),
                noteId = noteId,
                title = currentNote.title,
                content = currentNote.content,
                summary = currentNote.summary,
                versionNo = latestVersion + 1,
                changeDescription = "Auto-saved before restore",
                createdAt = System.currentTimeMillis(),
            )
        noteVersionDao.insertVersion(saveVersion)

        val restoredNote =
            currentNote.copy(
                title = version.title,
                content = version.content,
                summary = version.summary,
                updatedAt = System.currentTimeMillis(),
            )
        noteDao.updateNote(restoredNote)

        return true
    }

    suspend fun getVersionCount(noteId: String): Int = noteVersionDao?.getVersionCount(noteId) ?: 0

    // =========================================================================
    // CATEGORY OPERATIONS
    // =========================================================================

    fun getAllCategories(): Flow<List<Category>> = categoryDao.getAllCategories().distinctUntilChanged()

    suspend fun getCategoryById(id: String): Category? = categoryDao.getCategoryById(id)

    suspend fun getCategoryByName(name: String): Category? = categoryDao.getCategoryByName(name)

    suspend fun insertCategory(category: Category) {
        categoryDao.insertCategory(category)
        syncRepository?.let { repo ->
            scope.launch { repo.syncCategory(category) }
        }
    }

    suspend fun updateCategory(category: Category) {
        categoryDao.updateCategory(category)
        syncRepository?.let { repo ->
            scope.launch { repo.syncCategory(category) }
        }
    }

    suspend fun deleteCategory(category: Category) {
        categoryDao.deleteCategory(category)
        syncRepository?.let { repo ->
            scope.launch { repo.deleteCategory(category.id) }
        }
    }

    @Transaction
    suspend fun deleteCategoryWithCleanup(category: Category) {
        noteDao.clearCategoryFromNotes(category.id)
        categoryDao.deleteCategory(category)
    }

    /**
     * Get or create a category by name with race condition protection.
     */
    suspend fun getOrCreateCategory(name: String): Category {
        val categoryName = if (name.length > 10) name.take(10) else name

        // Check if category exists
        categoryDao.getCategoryByName(categoryName)?.let { return it }

        // Create new category
        val newCategory =
            Category(
                id = java.util.UUID.randomUUID().toString(),
                name = categoryName,
                createdAt = System.currentTimeMillis(),
            )
        categoryDao.insertCategory(newCategory)

        // Re-check to handle race condition
        return categoryDao.getCategoryByName(categoryName) ?: newCategory
    }

    suspend fun syncAllCategoryCounts() = categoryDao.recalculateAllCounts()

    suspend fun syncCategoryCount(categoryId: String) = categoryDao.recalculateCategoryCount(categoryId)

    // =========================================================================
    // CALENDAR OPERATIONS
    // =========================================================================

    fun getAllCalendarEvents(): Flow<List<CalendarEvent>> = calendarDao.getAllEvents().distinctUntilChanged()

    suspend fun getCalendarEventById(id: String): CalendarEvent? = calendarDao.getEventById(id)

    suspend fun getCalendarEventByIdForAi(id: String): CalendarEvent? {
        val event = calendarDao.getEventById(id) ?: return null
        return if (event.isEventPrivate) null else event
    }

    suspend fun insertCalendarEvent(event: CalendarEvent) = calendarDao.insertEvent(event)

    suspend fun updateCalendarEvent(event: CalendarEvent) = calendarDao.updateEvent(event)

    suspend fun deleteCalendarEvent(eventId: String) = calendarDao.deleteEventById(eventId)

    suspend fun searchCalendarEvents(query: String): List<CalendarEvent> {
        val allEvents = calendarDao.getAllEventsOnce()
        val lowerQuery = query.lowercase()
        return allEvents.filter { event ->
            !event.isEventPrivate &&
                (
                    event.title.lowercase().contains(lowerQuery) ||
                        event.description?.lowercase()?.contains(lowerQuery) == true
                )
        }
    }

    suspend fun getUpcomingCalendarEvents(limit: Int = 10): List<CalendarEvent> = calendarDao.getAiVisibleUpcomingEvents(limit = limit)

    suspend fun getAiVisibleEventsInRange(
        startMillis: Long,
        endMillis: Long,
    ): List<CalendarEvent> = calendarDao.getEventsForDay(startMillis, endMillis).filter { !it.isEventPrivate }

    suspend fun getNoteCount(): Int = noteDao.getNoteCount()

    /**
     * Get all categories via coroutine (non-blocking).
     */
    suspend fun getAllCategoriesOneShot(): List<Category> = categoryDao.getAllCategories().first()

    // =========================================================================
    // EVENT OPERATIONS (ALIASES)
    // =========================================================================

    fun getAllEvents(): Flow<List<CalendarEvent>> = calendarDao.getAllEvents()

    suspend fun getEventById(id: String): CalendarEvent? = calendarDao.getEventById(id)

    suspend fun insertEvent(event: CalendarEvent) = calendarDao.insertEvent(event)

    suspend fun updateEvent(event: CalendarEvent) = calendarDao.updateEvent(event)

    suspend fun deleteEvent(event: CalendarEvent) = calendarDao.deleteEvent(event)

    suspend fun deleteEventById(id: String) = calendarDao.deleteEventById(id)

    suspend fun deleteEventsByIds(ids: List<String>) = calendarDao.deleteEventsByIds(ids)

    fun getEventsInRange(
        startMillis: Long,
        endMillis: Long,
    ): Flow<List<CalendarEvent>> = calendarDao.getEventsInRange(startMillis, endMillis)

    suspend fun getEventsForDay(
        dayStartMillis: Long,
        dayEndMillis: Long,
    ): List<CalendarEvent> = calendarDao.getEventsForDay(dayStartMillis, dayEndMillis)

    fun getUpcomingEvents(
        nowMillis: Long = System.currentTimeMillis(),
        limit: Int = 20,
    ): Flow<List<CalendarEvent>> = calendarDao.getUpcomingEvents(nowMillis, limit)

    suspend fun getTodayEvents(
        dayStart: Long,
        dayEnd: Long,
    ): List<CalendarEvent> = calendarDao.getTodayEvents(dayStart, dayEnd)

    fun getEventsForNote(noteId: String): Flow<List<CalendarEvent>> = calendarDao.getEventsForNote(noteId)

    suspend fun clearNoteLinkForNote(noteId: String) = calendarDao.clearNoteLinkForNote(noteId)

    fun getAiVisibleEvents(): Flow<List<CalendarEvent>> = calendarDao.getAiVisibleEvents()

    suspend fun getAiVisibleUpcomingEvents(
        nowMillis: Long = System.currentTimeMillis(),
        limit: Int = 10,
    ): List<CalendarEvent> = calendarDao.getAiVisibleUpcomingEvents(nowMillis, limit)

    suspend fun getAllEventsOnce(): List<CalendarEvent> = calendarDao.getAllEventsOnce()

    suspend fun getActiveTimersOnce(): List<com.example.smarty.core.domain.model.SmartyTimer> = calendarDao.getActiveTimersOnce()

    suspend fun deleteAllEvents() = calendarDao.deleteAllEvents()

    suspend fun getEventCount(): Int = calendarDao.getEventCount()

    suspend fun hasEventsOnDay(
        dayStart: Long,
        dayEnd: Long,
    ): Boolean = calendarDao.hasEventsOnDay(dayStart, dayEnd)

    /**
     * Clear all user data from local storage.
     */
    suspend fun clearAllUserData() {
        noteDao.deleteAllNotes()
        categoryDao.deleteAllCategories()

        val database = SmartyDatabase.getDatabase(getApplicationContext())
        database.chatDao().deleteAllMessages()
        database.chatDao().deleteAllSessions()
        calendarDao.deleteAllEvents()
        calendarDao.deleteAllTimers()
        database.aiCacheDao().clearAll()
        database.impressedLogDao().deleteAllLogs()
        noteVersionDao?.deleteAllVersions()
    }
}

private fun NoteVersionEntity.toDomain(): NoteVersion {
    return NoteVersion(
        id = id,
        noteId = noteId,
        title = title,
        content = content,
        summary = summary,
        versionNumber = versionNo,
        createdAt = createdAt,
        changeDescription = changeDescription,
    )
}
