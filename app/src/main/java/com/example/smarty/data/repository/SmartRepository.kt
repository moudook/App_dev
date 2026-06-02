package com.example.smarty.data.repository

import com.example.smarty.core.domain.model.*
import com.example.smarty.data.local.*
import com.example.smarty.data.local.CRDTManager
import com.example.smarty.data.local.OfflineFirstSyncManager
import com.example.smarty.data.local.SmartyDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Smart Repository - AI-driven data repository with creative integrations
 * Provides unified access to all data sources with intelligent features
 */
@Singleton
class SmartRepository
    @Inject
    constructor(
        private val database: SmartyDatabase,
        private val crdtManager: CRDTManager,
        private val offlineSyncManager: OfflineFirstSyncManager,
    ) {
        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val smartDao = database.smartDao()

        // ============================================================
        // USER MANAGEMENT
        // ============================================================

        /**
         * Get or create user
         */
        suspend fun getOrCreateUser(
            firebaseUid: String,
            email: String?,
            displayName: String?,
        ): UserEntity {
            val existing = smartDao.getUserByFirebaseUid(firebaseUid)
            if (existing != null) return existing

            val user =
                UserEntity(
                    id = UUID.randomUUID().toString(),
                    firebaseUid = firebaseUid,
                    email = email,
                    displayName = displayName,
                    avatarUrl = null,
                    deviceFingerprint = generateDeviceFingerprint(),
                )
            smartDao.insertUser(user)

            // Initialize sync state
            val syncState =
                SyncStateEntity(
                    userId = user.id,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                )
            smartDao.insertSyncState(syncState)

            return user
        }

        /**
         * Update user profile
         */
        suspend fun updateUser(user: UserEntity) {
            smartDao.updateUser(user)
            enqueueSync("users", user.id, OfflineFirstSyncManager.SyncOperation.Update("users", user.id, user))
        }

        /**
         * Get user with sync state
         */
        suspend fun getUserWithSyncState(userId: String): UserWithSyncState? =
            smartDao.getUserById(userId)?.let { user ->
                val syncState = smartDao.getSyncState(userId)
                UserWithSyncState(user, syncState)
            }

        // ============================================================
        // NOTE OPERATIONS WITH AI ENHANCEMENT
        // ============================================================

        /**
         * Create note with auto-tagging
         */
        suspend fun createNoteWithAutoTagging(
            note: Note,
            userId: String,
        ): Note {
            // Insert note
            smartDao.insertNote(note)

            // Auto-generate tags based on content
            val autoTags = generateTagsFromContent(note.title, note.content)
            for (tagName in autoTags) {
                val tag = getOrCreateTag(userId, tagName, TagEntity.TagType.AUTO)
                smartDao.insertNoteTag(
                    NoteTagEntity(
                        noteId = note.id,
                        tagId = tag.id,
                        userId = userId,
                        assignedBy = "ai",
                        confidenceScore = 0.7,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
            }

            // Enqueue sync
            enqueueSync("notes", note.id, OfflineFirstSyncManager.SyncOperation.Create("notes", note.id, note))

            return note
        }

        /**
         * Update note with versioning
         */
        suspend fun updateNoteWithVersion(
            note: Note,
            changeDescription: String? = null,
        ) {
            // Get current version
            val current = smartDao.getNoteById(note.id)
            if (current != null) {
                // Create version
                val version =
                    NoteVersionEntity(
                        id = UUID.randomUUID().toString(),
                        noteId = note.id,
                        title = current.title,
                        content = current.content,
                        summary = current.summary,
                        versionNo = (smartDao.getNoteVersionCount(note.id) + 1),
                        changeDescription = changeDescription,
                        createdAt = System.currentTimeMillis(),
                    )
                smartDao.insertNoteVersion(version)

                // Keep only last 10 versions
                val versions = smartDao.getNoteVersions(note.id).count()
                if (versions > 10) {
                    smartDao.pruneNoteVersions(note.id, versions - 10)
                }
            }

            // Update note
            smartDao.updateNote(note)

            // Enqueue sync
            enqueueSync("notes", note.id, OfflineFirstSyncManager.SyncOperation.Update("notes", note.id, note))
        }

        /**
         * Get notes with tags for AI context
         */
        fun getNotesWithTagsForAi(userId: String): Flow<List<NoteWithTags>> = smartDao.getNotesWithTagsForAi()

        /**
         * Find related notes by tags
         */
        suspend fun findRelatedNotes(
            noteId: String,
            limit: Int = 10,
        ): List<Note> = smartDao.findRelatedNotesByTags(noteId, limit)

        // ============================================================
        // TAG MANAGEMENT
        // ============================================================

        /**
         * Get or create tag
         */
        suspend fun getOrCreateTag(
            userId: String,
            name: String,
            type: TagEntity.TagType = TagEntity.TagType.MANUAL,
        ): TagEntity {
            val existing = smartDao.getTagByName(userId, name)
            if (existing != null) return existing

            val tag =
                TagEntity(
                    id = UUID.randomUUID().toString(),
                    userId = userId,
                    name = name,
                    tagType = type.name,
                    confidenceScore = if (type == TagEntity.TagType.AI) 0.5 else 1.0,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                )
            smartDao.insertTag(tag)

            return tag
        }

        /**
         * Assign tag to note
         */
        suspend fun assignTagToNote(
            noteId: String,
            tagId: String,
            userId: String,
            assignedBy: String = "user",
            confidenceScore: Double = 1.0,
        ) {
            val noteTag =
                NoteTagEntity(
                    noteId = noteId,
                    tagId = tagId,
                    userId = userId,
                    assignedBy = assignedBy,
                    confidenceScore = confidenceScore,
                    createdAt = System.currentTimeMillis(),
                )
            smartDao.insertNoteTag(noteTag)

            // Update tag usage count
            val tag = smartDao.getTagById(tagId)
            tag?.let {
                smartDao.updateTag(
                    it.copy(
                        usageCount = it.usageCount + 1,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }

        // ============================================================
        // TASK OPERATIONS
        // ============================================================

        /**
         * Create task from note
         */
        suspend fun createTaskFromNote(
            noteId: String,
            userId: String,
            title: String,
            dueDate: Long? = null,
        ): TaskEntity {
            val task =
                TaskEntity(
                    id = UUID.randomUUID().toString(),
                    userId = userId,
                    noteId = noteId,
                    title = title,
                    description = "Created from note",
                    status = TaskEntity.TaskStatus.TODO.name,
                    priority = 2,
                    dueDate = dueDate,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                )
            smartDao.insertTask(task)

            // Link to note
            smartDao.insertNoteTask(
                NoteTaskEntity(
                    noteId = noteId,
                    taskId = task.id,
                    userId = userId,
                    createdAt = System.currentTimeMillis(),
                ),
            )

            return task
        }

        /**
         * Complete task
         */
        suspend fun completeTask(
            taskId: String,
            userId: String,
        ) {
            val task = smartDao.getTaskById(taskId)
            task?.let {
                smartDao.updateTaskStatus(taskId, TaskEntity.TaskStatus.COMPLETED.name, System.currentTimeMillis())
            }
        }

        // ============================================================
        // REASONING OPERATIONS (AI DECISION PROVENANCE)
        // ============================================================

        /**
         * Record reasoning step
         */
        suspend fun recordReasoningStep(
            sessionId: String,
            userId: String,
            stepType: String,
            title: String,
            content: String,
            entityType: String? = null,
            entityId: String? = null,
            inputData: String? = null,
            outputData: String? = null,
            confidenceScore: Double = 0.5,
        ): ReasoningTraceEntity {
            val trace =
                ReasoningTraceEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    userId = userId,
                    stepIndex = getNextStepIndex(sessionId),
                    stepType = stepType,
                    title = title,
                    content = content,
                    entityType = entityType,
                    entityId = entityId,
                    inputData = inputData,
                    outputData = outputData,
                    confidenceScore = confidenceScore,
                    createdAt = System.currentTimeMillis(),
                )
            smartDao.insertReasoningTrace(trace)
            return trace
        }

        /**
         * Create reasoning summary
         */
        suspend fun createReasoningSummary(
            sessionId: String,
            userId: String,
            oneLiner: String,
            briefSummary: String,
            detailedSummary: String,
            reasoningType: String,
        ): ReasoningSummaryEntity {
            val traces = smartDao.getSessionReasoningTraces(sessionId).first()

            val summary =
                ReasoningSummaryEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    userId = userId,
                    oneLiner = oneLiner,
                    briefSummary = briefSummary,
                    detailedSummary = detailedSummary,
                    totalSteps = traces.size,
                    totalDurationMs = traces.sumOf { it.durationMs },
                    totalTokens = traces.sumOf { it.tokenCount },
                    confidenceScore =
                        traces
                            .map { it.confidenceScore }
                            .average()
                            .toFloat()
                            .toDouble(),
                    complexityScore = calculateComplexityScore(traces),
                    reasoningType = reasoningType,
                    tags = "[]",
                    linkedEntities = "[]",
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                )
            smartDao.insertReasoningSummary(summary)
            return summary
        }

        /**
         * Save agent checkpoint
         */
        suspend fun saveAgentCheckpoint(
            sessionId: String,
            userId: String,
            stateJson: String,
            contextJson: String? = null,
            memoryJson: String? = null,
            workflowId: String? = null,
            checkpointType: AgentCheckpointEntity.CheckpointType = AgentCheckpointEntity.CheckpointType.AUTO,
        ): AgentCheckpointEntity {
            val checkpoint =
                AgentCheckpointEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    userId = userId,
                    workflowId = workflowId,
                    stateJson = stateJson,
                    contextJson = contextJson,
                    memoryJson = memoryJson,
                    checkpointType = checkpointType.name,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                )
            smartDao.insertAgentCheckpoint(checkpoint)
            return checkpoint
        }

        // ============================================================
        // SEARCH OPERATIONS
        // ============================================================

        /**
         * Record search with AI enhancement
         */
        suspend fun recordSearch(
            userId: String,
            query: String,
            searchScope: String = "all",
            aiEnhanced: Boolean = false,
        ): SearchHistoryEntity {
            val search =
                SearchHistoryEntity(
                    id = UUID.randomUUID().toString(),
                    userId = userId,
                    query = query,
                    searchScope = searchScope,
                    aiEnhanced = aiEnhanced,
                    createdAt = System.currentTimeMillis(),
                )
            smartDao.insertSearchHistory(search)
            return search
        }

        /**
         * Unified search across all entities
         */
        suspend fun unifiedSearch(
            userId: String,
            query: String,
            includePrivate: Boolean = false,
        ): UnifiedSearchResults {
            val notes =
                smartDao
                    .getUserActiveNotes()
                    .first()
                    .filter { note ->
                        note.title.contains(query, ignoreCase = true) ||
                            note.content.contains(query, ignoreCase = true) ||
                            (includePrivate || !note.isPrivate)
                    }

            // Would search other entities too

            return UnifiedSearchResults(
                notes = notes,
                totalResults = notes.size,
            )
        }

        // ============================================================
        // SHARED ITEMS (COLLABORATION)
        // ============================================================

        /**
         * Share item with user
         */
        suspend fun shareItem(
            ownerId: String,
            itemType: String,
            itemId: String,
            sharedWithId: String? = null,
            permission: SharedItemEntity.Permission = SharedItemEntity.Permission.VIEW,
            expiresAt: Long? = null,
        ): SharedItemEntity {
            val shareToken = UUID.randomUUID().toString()

            val sharedItem =
                SharedItemEntity(
                    id = UUID.randomUUID().toString(),
                    ownerId = ownerId,
                    sharedWithId = sharedWithId,
                    itemType = itemType,
                    itemId = itemId,
                    permission = permission.name,
                    shareToken = shareToken,
                    expiresAt = expiresAt,
                    createdAt = System.currentTimeMillis(),
                )
            smartDao.insertSharedItem(sharedItem)

            return sharedItem
        }

        // ============================================================
        // DAILY DIGESTS
        // ============================================================

        /**
         * Generate daily digest
         */
        suspend fun generateDailyDigest(
            userId: String,
            date: Long,
        ): DailyDigestEntity {
            val notes = smartDao.getUserActiveNotes().first()
            val tasks = smartDao.getUserTasks(userId).first().filter { it.status != "COMPLETED" }

            val content =
                buildString {
                    append("Daily Digest - ${Date(date)}\n\n")
                    append("=== Notes ===\n")
                    notes.take(5).forEach { append("- ${it.title}\n") }
                    append("\n=== Pending Tasks ===\n")
                    tasks.take(5).forEach { append("- ${it.title} (${it.status})\n") }
                }

            val digest =
                DailyDigestEntity(
                    id = UUID.randomUUID().toString(),
                    userId = userId,
                    digestDate = date,
                    digestType = DailyDigestEntity.DigestType.DAILY.name,
                    content = content,
                    notificationSent = false,
                    linkedNoteIds = notes.take(10).map { it.id }.toString(),
                    generatedByAi = true,
                    createdAt = System.currentTimeMillis(),
                )
            smartDao.insertDailyDigest(digest)

            return digest
        }

        // ============================================================
        // SYNC OPERATIONS
        // ============================================================

        /**
         * Enqueue sync operation
         */
        private fun enqueueSync(
            entityType: String,
            entityId: String,
            operation: Any,
        ) {
            // Would create SyncQueueItem
            // For now, trigger offline sync
            scope.launch {
                offlineSyncManager.queueSync(
                    OfflineFirstSyncManager.SyncOperation.Update(entityType, entityId, operation),
                )
            }
        }

        /**
         * Start background sync
         */
        fun startBackgroundSync() {
            offlineSyncManager.startPeriodicSync()
        }

        // ============================================================
        // HELPER FUNCTIONS
        // ============================================================

        private fun generateTagsFromContent(
            title: String,
            content: String,
        ): List<String> {
            // AI-powered tag generation
            val words =
                (title + " " + content)
                    .lowercase()
                    .replace(Regex("[^a-zA-Z0-9\\s]"), "")
                    .split("\\s+".toRegex())
                    .filter { it.length > 4 }
                    .distinct()

            return words.take(5)
        }

        private suspend fun getNextStepIndex(sessionId: String): Int = smartDao.getSessionReasoningTraces(sessionId).first().size + 1

        private fun calculateComplexityScore(traces: List<ReasoningTraceEntity>): Double = traces.map { it.importanceScore }.average()

        private fun generateDeviceFingerprint(): String = UUID.randomUUID().toString()

        // ============================================================
        // DATA CLASSES
        // ============================================================

        data class UserWithSyncState(
            val user: UserEntity,
            val syncState: SyncStateEntity?,
        )

        data class UnifiedSearchResults(
            val notes: List<Note>,
            val tasks: List<TaskEntity> = emptyList(),
            val events: List<CalendarEvent> = emptyList(),
            val chats: List<ChatSession> = emptyList(),
            val totalResults: Int,
        )
    }
