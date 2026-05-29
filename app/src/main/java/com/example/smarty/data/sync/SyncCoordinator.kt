package com.example.smarty.data.sync

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.smarty.core.common.util.NetworkMonitor
import com.example.smarty.core.domain.model.CalendarEvent
import com.example.smarty.core.domain.model.ChatMessage
import com.example.smarty.core.domain.model.ChatRole
import com.example.smarty.core.domain.model.ChatSession
import com.example.smarty.core.domain.model.Note
import com.example.smarty.core.domain.model.NoteType
import com.example.smarty.core.domain.model.ProcessingStatus
import com.example.smarty.data.local.*
import com.example.smarty.data.remote.RemoteDataSource
import com.example.smarty.protocol.*
import com.example.smarty.ui.components.ConnectionStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.json.JSONObject

class SyncCoordinator(
    private val context: Context,
    private val remoteDataSource: RemoteDataSource,
    private val noteDao: NoteDao,
    private val calendarDao: CalendarDao,
    private val chatDao: ChatDao,
    private val syncQueueDao: SyncQueueDao,
    private val networkMonitor: NetworkMonitor,
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val generatedImagesPrefs: SharedPreferences = context.getSharedPreferences(PREFS_GENERATED_IMAGES, Context.MODE_PRIVATE)

    // MUTEX LOCK: Prevents duplicate concurrent pull requests
    // This fixes the issue where 3 identical pull requests fire within ~2 seconds
    private val pullMutex = Mutex()
    private val pushMutex = Mutex()
    private val syncAllMutex = Mutex()

    // Track last pull time for debouncing (5 second window)
    @Volatile
    private var lastPullTime = 0L

    @Volatile
    private var lastSyncAllTime = 0L
    private val pullDebounceMs = 5000L // 5 seconds

    val isOnline = networkMonitor.connectionStatus.map { it == ConnectionStatus.CONNECTED }

    /**
     * Unified sync operation with a hard 5-second debounce limit.
     * Prevents spamming the server when multiple events request sync.
     */
    suspend fun syncAll(): Pair<PushResult, PullResult> {
        val now = System.currentTimeMillis()
        if (now - lastSyncAllTime < pullDebounceMs) {
            Log.d(TAG, "SyncAll debounced: last sync was ${now - lastSyncAllTime}ms ago")
            return Pair(PushResult.Success(0, 0, 0), PullResult.Success(0, 0, 0))
        }

        return syncAllMutex.withLock {
            val nowAfterLock = System.currentTimeMillis()
            if (nowAfterLock - lastSyncAllTime < pullDebounceMs) {
                return@withLock Pair(PushResult.Success(0, 0, 0), PullResult.Success(0, 0, 0))
            }
            lastSyncAllTime = nowAfterLock

            Log.i(TAG, "Executing unified debounced syncAll...")
            val pushResult = pushPendingChanges()
            val pullResult = pullFromServer()
            Pair(pushResult, pullResult)
        }
    }

    /**
     * Get all stored generated images from local cache.
     * @return List of generated image info maps with id, prompt, imageUrl, etc.
     */
    fun getStoredGeneratedImages(): List<Map<String, Any>> {
        val images = mutableListOf<Map<String, Any>>()
        try {
            val allKeys = generatedImagesPrefs.all
            allKeys.forEach { (id, jsonStr) ->
                if (jsonStr is String) {
                    try {
                        val json = JSONObject(jsonStr)
                        val imgMap = mutableMapOf<String, Any>()
                        imgMap["id"] = json.getString("id")
                        imgMap["prompt"] = json.getString("prompt")
                        imgMap["status"] = json.getString("status")
                        val imageUrl = json.getString("imageUrl")
                        if (imageUrl.isNotEmpty()) {
                            imgMap["imageUrl"] = imageUrl
                        }
                        val supabaseUrl = json.getString("supabaseUrl")
                        if (supabaseUrl.isNotEmpty()) {
                            imgMap["supabaseUrl"] = supabaseUrl
                        }
                        imgMap["createdAt"] = json.getLong("createdAt")
                        images.add(imgMap)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse generated image: $id", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get stored generated images", e)
        }
        return images.sortedByDescending { (it["createdAt"] as? Long) ?: 0L }
    }

    /**
     * OPTIMIZED PULL with mutex lock and debouncing
     * - Mutex prevents concurrent duplicate pulls
     * - Debouncing ignores pulls within 5s of last pull
     * - Delta-sync sends lastSyncAt timestamp
     */
    suspend fun pullFromServer(): PullResult {
        Log.i(TAG, ">>> pullFromServer STARTING - isOnline=${isOnline.first()}")

        if (!isOnline.first()) {
            Log.w(TAG, "<<< pullFromServer FAILED: Device is offline")
            return PullResult.Offline
        }

        // DEBOUNCE CHECK: Skip if pulled within last 5 seconds
        val now = System.currentTimeMillis()
        if (now - lastPullTime < pullDebounceMs) {
            Log.d(TAG, "Pull debounced: last pull was ${now - lastPullTime}ms ago")
            return PullResult.Success(0, 0, 0) // Treat as success to avoid 'Not Connected' errors
        }

        Log.i(TAG, "Starting pull from server...")

        // MUTEX LOCK: Only one pull can execute at a time
        return pullMutex.withLock {
            // Double-check debounce after acquiring lock
            val nowAfterLock = System.currentTimeMillis()
            if (nowAfterLock - lastPullTime < pullDebounceMs) {
                Log.d(TAG, "Pull debounced (post-lock): last pull was ${nowAfterLock - lastPullTime}ms ago")
                return@withLock PullResult.Success(0, 0, 0)
            }

            lastPullTime = nowAfterLock

            try {
                val startTime = System.currentTimeMillis()

                // DELTA SYNC: Send lastSyncAt to get only changed data
                val lastSyncAt = getLastPullTime()
                Log.d(TAG, "Delta sync: lastSyncAt=$lastSyncAt")

                val response = remoteDataSource.pullAllData(lastSyncAt = lastSyncAt)
                if (response == null) {
                    Log.e(TAG, "<<< pullFromServer FAILED: null response from server")
                    return@withLock PullResult.Error("Failed to connect to server")
                }

                Log.i(
                    TAG,
                    ">>> pullFromServer SUCCESS: notes=${response.notes.size}, sessions=${response.sessions.size}, events=${response.events.size}",
                )

                var notesUpdated = 0
                var sessionsUpdated = 0
                var eventsUpdated = 0

                // Sync notes with idempotent upsert and conflict resolution
                response.notes.forEach { noteInfo ->
                    val existing = noteDao.getNoteByIdSync(noteInfo.id)
                    val note = mapToNote(noteInfo)

                    if (existing == null) {
                        // New note from server - check for content-based duplicates
                        val recentNotes = noteDao.getNotesCreatedAfter(System.currentTimeMillis() - 5000)
                        val isDuplicateByContent =
                            recentNotes.any { recentNote ->
                                recentNote.content.trim() == note.content.trim() &&
                                    recentNote.title.trim() == note.title.trim()
                            }
                        if (isDuplicateByContent) {
                            Log.w(TAG, "Skipping duplicate note by content: ${noteInfo.id}")
                        } else {
                            noteDao.upsertNote(note)
                            notesUpdated++
                        }
                    } else if (existing.updatedAt < noteInfo.updatedAt) {
                        // Server version is newer - apply conflict resolution (last-write-wins)
                        noteDao.updateNoteIfServerIsNewer(
                            id = note.id,
                            title = note.title,
                            content = note.content,
                            summary = note.summary,
                            sourceUrl = note.sourceUrl,
                            imageUri = note.imageUri,
                            fileUri = note.fileUri,
                            fileName = note.fileName,
                            fileMimeType = note.fileMimeType,
                            fileSize = note.fileSize,
                            type = note.type,
                            categoryId = note.categoryId,
                            categoryName = note.categoryName,
                            stackId = note.stackId,
                            parentNoteId = note.parentNoteId,
                            whySaved = note.whySaved,
                            processingStatus = note.processingStatus,
                            contentHash = note.contentHash,
                            processedContentHash = note.processedContentHash,
                            isArchived = note.isArchived,
                            isPinned = note.isPinned,
                            isFavorite = note.isFavorite,
                            isFullPrivacy = note.isFullPrivacy,
                            excludeFromAiChat = note.excludeFromAiChat,
                            isAiCreated = note.isAiCreated,
                            isViewed = note.isViewed,
                            todoContent = note.todoContent,
                            attachmentsJson = note.attachmentsJson,
                            tagsJson = note.tagsJson,
                            chunkAnalysesJson = note.chunkAnalysesJson,
                            reminderText = note.reminderText,
                            reminderExpiresAt = note.reminderExpiresAt,
                            metadata = note.metadata,
                            wordCount = note.wordCount,
                            updatedAt = note.updatedAt,
                        )
                        notesUpdated++
                    }
                    // If local is newer, skip (local changes take precedence)
                }

                // Sync sessions
                response.sessions.forEach { sessionData ->
                    val existingSession = chatDao.getSessionById(sessionData.id)

                    if (existingSession == null || existingSession.updatedAt < sessionData.updatedAt) {
                        val session =
                            ChatSession(
                                id = sessionData.id,
                                title = sessionData.title ?: "Chat",
                                createdAt = sessionData.createdAt,
                                updatedAt = sessionData.updatedAt,
                                messageCount = sessionData.messageCount,
                                lastMessagePreview = sessionData.lastMessagePreview ?: "",
                                isActive = existingSession?.isActive ?: false,
                                summary = sessionData.summary,
                                summaryGeneratedAt = sessionData.summaryGeneratedAt,
                            )

                        if (existingSession == null) {
                            chatDao.insertSession(session)
                        } else {
                            chatDao.updateSession(session)
                        }

                        // Sync messages for this session
                        // Get all existing local messages for deduplication
                        val existingLocalMessages = chatDao.getMessagesForSessionOnce(sessionData.id)
                        sessionData.messages.forEach { msgData ->
                            // Check by server ID first
                            val existingById = chatDao.getMessageById(msgData.id)
                            if (existingById != null) {
                                // Message already exists by ID, skip
                                return@forEach
                            }

                            // Check by content hash (normalized)
                            val normalizedServerContent = normalizeContentForDedup(msgData.content)
                            val existingLocal =
                                existingLocalMessages.find { local ->
                                    local.role == msgData.role.uppercase() &&
                                        normalizeContentForDedup(local.content) == normalizedServerContent
                                }
                            if (existingLocal != null) {
                                // Message already exists by content. Merge rich data before skipping.
                                if (existingLocal.thinking == null && msgData.thinking != null) {
                                    chatDao.updateMessageThinkingOnly(existingLocal.id, msgData.thinking)
                                }
                                if (existingLocal.agentStepsJson == "[]" && !msgData.agentStepsJson.isNullOrEmpty()) {
                                    chatDao.updateMessageAgentStepsOnly(existingLocal.id, msgData.agentStepsJson)
                                }
                                if (existingLocal.agentEventsJson == "[]" && !msgData.agentEventsJson.isNullOrEmpty()) {
                                    chatDao.updateMessageEventsOnly(existingLocal.id, msgData.agentEventsJson)
                                }
                                // Skip insertion
                                return@forEach
                            }

                            val thinking = msgData.thinking
                            val cleanContent = msgData.content
                            val message =
                                ChatMessage(
                                    id = msgData.id,
                                    role =
                                        when (msgData.role.uppercase()) {
                                            "USER" -> ChatRole.USER
                                            "SMARTY", "ASSISTANT" -> ChatRole.SMARTY
                                            else -> ChatRole.SYSTEM
                                        },
                                    content = cleanContent,
                                    thinking = thinking,
                                    timestamp = msgData.createdAt,
                                    agentSteps =
                                        com.example.smarty.core.domain.model.ChatMessageEntity.parseAgentStepsJson(
                                            msgData.agentStepsJson ?: "[]",
                                        ),
                                    agentEvents =
                                        try {
                                            val agentEventsJson = msgData.agentEventsJson
                                            if (!agentEventsJson.isNullOrBlank() && agentEventsJson != "[]") {
                                                Json.decodeFromString<List<AgentEvent>>(agentEventsJson)
                                            } else emptyList()
                                        } catch (e: Exception) {
                                            emptyList()
                                        },
                                )
                            val entity = com.example.smarty.core.domain.model.ChatMessageEntity.fromChatMessage(message, sessionData.id)
                            chatDao.insertMessage(entity)
                        }
                        sessionsUpdated++
                    }
                }

                // Sync events
                response.events.forEach { eventInfo ->
                    val existing = calendarDao.getEventById(eventInfo.id)

                    if (existing == null) {
                        val event =
                            CalendarEvent(
                                id = eventInfo.id,
                                title = eventInfo.title,
                                startTime = eventInfo.startTime,
                                endTime = eventInfo.endTime,
                                description = eventInfo.description,
                                location = eventInfo.location,
                                reminderMinutes = eventInfo.reminderMinutes,
                                linkedNoteId = eventInfo.linkedNoteId,
                                googleEventId = eventInfo.googleEventId,
                                isEventPrivate = eventInfo.isEventPrivate,
                                createdAt = eventInfo.createdAt,
                            )
                        calendarDao.insertEvent(event)
                        eventsUpdated++
                    }
                }

                // Sync generated images to local storage
                val generatedImages = response.generatedImages
                if (generatedImages.isNotEmpty()) {
                    var imagesStored = 0
                    generatedImages.forEach { imgInfo ->
                        try {
                            val imgJson =
                                JSONObject().apply {
                                    put("id", imgInfo.id)
                                    put("userId", imgInfo.userId)
                                    put("sessionId", imgInfo.sessionId ?: "")
                                    put("prompt", imgInfo.prompt)
                                    put("kreaJobId", imgInfo.kreaJobId)
                                    put("status", imgInfo.status)
                                    put("imageUrl", imgInfo.imageUrl ?: "")
                                    put("supabaseUrl", imgInfo.supabaseUrl ?: "")
                                    put("createdAt", imgInfo.createdAt)
                                    put("updatedAt", imgInfo.updatedAt)
                                }
                            generatedImagesPrefs.edit().putString(imgInfo.id, imgJson.toString()).apply()
                            imagesStored++
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to store generated image: ${imgInfo.id}", e)
                        }
                    }
                    Log.i(TAG, "Stored $imagesStored generated images locally")
                }

                // Update last sync time
                prefs.edit().putLong(KEY_LAST_PULL, System.currentTimeMillis()).apply()

                val duration = System.currentTimeMillis() - startTime
                Log.i(
                    TAG,
                    "Pull complete in ${duration}ms: $notesUpdated notes, $sessionsUpdated sessions, $eventsUpdated events, ${generatedImages.size} generated images",
                )
                PullResult.Success(notesUpdated, sessionsUpdated, eventsUpdated)
            } catch (e: Exception) {
                Log.e(TAG, "Pull failed", e)
                PullResult.Error(e.message ?: "Unknown error")
            }
        }
    }

    suspend fun pushPendingChanges(): PushResult {
        if (!isOnline.first()) {
            return PushResult.Offline
        }

        Log.i(TAG, "Processing pending sync queue...")

        val pendingItems = syncQueueDao.getPendingItems(limit = 100)
        if (pendingItems.isEmpty()) {
            Log.d(TAG, "No pending items to sync")
            return PushResult.Success(0, 0, 0)
        }

        val notesToPush = mutableListOf<NotePushItem>()
        val sessionsToPush = mutableListOf<SessionPushItem>()
        val eventsToPush = mutableListOf<EventPushItem>()

        pendingItems.forEach { item ->
            when (item.entityType) {
                SyncEntityType.NOTE.name -> {
                    try {
                        val note = noteDao.getNoteByIdSync(item.entityId)
                        if (note != null) {
                            notesToPush.add(
                                NotePushItem(
                                    id = note.id,
                                    title = note.title,
                                    content = note.content,
                                    summary = note.summary,
                                    sourceUrl = note.sourceUrl,
                                    imageUri = note.imageUri,
                                    fileUri = note.fileUri,
                                    fileName = note.fileName,
                                    fileMimeType = note.fileMimeType,
                                    fileSize = note.fileSize,
                                    type = note.type.name,
                                    categoryId = note.categoryId,
                                    categoryName = note.categoryName,
                                    stackId = note.stackId,
                                    parentNoteId = note.parentNoteId,
                                    whySaved = note.whySaved,
                                    processingStatus = note.processingStatus.name,
                                    contentHash = note.contentHash,
                                    processedContentHash = note.processedContentHash,
                                    isArchived = note.isArchived,
                                    isPinned = note.isPinned,
                                    isFavorite = note.isFavorite,
                                    isFullPrivacy = note.isFullPrivacy,
                                    excludeFromAiChat = note.excludeFromAiChat,
                                    isAiCreated = note.isAiCreated,
                                    isViewed = note.isViewed,
                                    todoContent = note.todoContent,
                                    attachmentsJson = note.attachmentsJson,
                                    tagsJson = note.tagsJson,
                                    chunkAnalysesJson = note.chunkAnalysesJson,
                                    reminderText = note.reminderText,
                                    reminderExpiresAt = note.reminderExpiresAt,
                                    updatedAt = note.updatedAt,
                                ),
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error preparing note for push: ${item.entityId}", e)
                    }
                }
                SyncEntityType.EVENT.name -> {
                    try {
                        val event = calendarDao.getEventById(item.entityId)
                        if (event != null) {
                            eventsToPush.add(
                                EventPushItem(
                                    id = event.id,
                                    title = event.title,
                                    startTime = event.startTime,
                                    endTime = event.endTime,
                                    description = event.description,
                                    reminderMinutes = event.reminderMinutes ?: 15,
                                    location = event.location,
                                    linkedNoteId = event.linkedNoteId,
                                    googleEventId = event.googleEventId,
                                    isEventPrivate = event.isEventPrivate,
                                ),
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error preparing event for push: ${item.entityId}", e)
                    }
                }
            }
        }

        if (notesToPush.isEmpty() && sessionsToPush.isEmpty() && eventsToPush.isEmpty()) {
            return PushResult.Success(0, 0, 0)
        }

        val request =
            SyncPushRequest(
                notes = notesToPush.ifEmpty { null },
                sessions = sessionsToPush.ifEmpty { null },
                events = eventsToPush.ifEmpty { null },
            )

        return try {
            val response = remoteDataSource.pushChanges(request)
            if (response == null) {
                // Mark items as failed but preserve them for retry
                pendingItems.forEach { item ->
                    syncQueueDao.markFailed(item.id, "No response from server")
                }
                PushResult.Error("No response from server")
            } else if (response.success) {
                // Mark items as synced
                pendingItems.forEach { item ->
                    syncQueueDao.markSynced(item.id, System.currentTimeMillis())
                }

                prefs.edit().putLong(KEY_LAST_PUSH, System.currentTimeMillis()).apply()

                Log.i(TAG, "Push complete: ${notesToPush.size} notes, ${sessionsToPush.size} sessions, ${eventsToPush.size} events")
                PushResult.Success(notesToPush.size, sessionsToPush.size, eventsToPush.size)
            } else {
                // Mark items as failed but preserve them for retry
                pendingItems.forEach { item ->
                    syncQueueDao.markFailed(item.id, response.errors.joinToString())
                }
                PushResult.Error(response.errors.joinToString())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Push failed", e)
            // Mark items as failed but preserve them for retry
            pendingItems.forEach { item ->
                syncQueueDao.markFailed(item.id, e.message ?: "Unknown error")
            }
            PushResult.Error(e.message ?: "Unknown error")
        }
    }

    fun getLastPullTime(): Long = prefs.getLong(KEY_LAST_PULL, 0)

    fun getLastPushTime(): Long = prefs.getLong(KEY_LAST_PUSH, 0)

    private fun mapToNote(info: NoteInfo): Note {
        return Note(
            id = info.id,
            title = info.title,
            content = info.content,
            summary = info.summary,
            sourceUrl = info.sourceUrl,
            imageUri = info.imageUri,
            fileUri = info.fileUri,
            fileName = info.fileName,
            fileMimeType = info.fileMimeType,
            fileSize = info.fileSize,
            type =
                try {
                    NoteType.valueOf(info.type)
                } catch (e: Exception) {
                    NoteType.BRAIN_DUMP
                },
            categoryId = info.categoryId,
            categoryName = info.categoryName,
            stackId = info.stackId,
            parentNoteId = info.parentNoteId,
            whySaved = info.whySaved,
            processingStatus =
                try {
                    ProcessingStatus.valueOf(info.processingStatus)
                } catch (e: Exception) {
                    ProcessingStatus.COMPLETED
                },
            contentHash = info.contentHash,
            processedContentHash = info.processedContentHash,
            metadata = info.metadata,
            wordCount = info.wordCount,
            createdAt = info.createdAt,
            updatedAt = info.updatedAt,
            isArchived = info.isArchived,
            isPinned = info.isPinned,
            isFavorite = info.isFavorite,
            isFullPrivacy = info.isFullPrivacy,
            excludeFromAiChat = info.excludeFromAiChat,
            isAiCreated = info.isAiCreated,
            isViewed = info.isViewed,
            todoContent = info.todoContent,
            attachmentsJson = info.attachmentsJson,
            tagsJson = info.tagsJson,
            chunkAnalysesJson = info.chunkAnalysesJson,
            reminderText = info.reminderText,
            reminderExpiresAt = info.reminderExpiresAt,
        )
    }

    companion object {
        private const val TAG = "SyncCoordinator"
        private const val PREFS_NAME = "sync_prefs"
        private const val PREFS_GENERATED_IMAGES = "generated_images_prefs"
        private const val KEY_LAST_PULL = "last_pull"
        private const val KEY_LAST_PUSH = "last_push"

        /**
         * Normalize content for deduplication comparison.
         * Normalizes whitespace for comparison.
         */
        fun normalizeContentForDedup(content: String): String {
            return content
                .replace("\\r\\n", "\\n") // Normalize line endings
                .replace(Regex("\\s+"), " ") // Collapse multiple whitespace to single space
                .trim()
        }
    }
}

sealed class PullResult {
    data class Success(val notes: Int, val sessions: Int, val events: Int) : PullResult()

    object Offline : PullResult()

    data class Error(val message: String) : PullResult()
}

sealed class PushResult {
    data class Success(val notes: Int, val sessions: Int, val events: Int) : PushResult()

    object Offline : PushResult()

    data class Error(val message: String) : PushResult()
}
