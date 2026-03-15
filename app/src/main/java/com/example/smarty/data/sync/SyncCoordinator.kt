package com.example.smarty.data.sync

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.smarty.core.domain.model.CalendarEvent
import com.example.smarty.core.domain.model.ChatMessage
import com.example.smarty.core.domain.model.ChatRole
import com.example.smarty.core.domain.model.ChatSession
import com.example.smarty.core.domain.model.Note
import com.example.smarty.data.local.*
import com.example.smarty.data.remote.RemoteDataSource
import com.example.smarty.protocol.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

class SyncCoordinator(
    private val context: Context,
    private val remoteDataSource: RemoteDataSource,
    private val noteDao: NoteDao,
    private val calendarDao: CalendarDao,
    private val chatDao: ChatDao,
    private val syncQueueDao: SyncQueueDao,
    private val networkMonitor: NetworkMonitor
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val generatedImagesPrefs: SharedPreferences = context.getSharedPreferences(PREFS_GENERATED_IMAGES, Context.MODE_PRIVATE)
    
    // MUTEX LOCK: Prevents duplicate concurrent pull requests
    // This fixes the issue where 3 identical pull requests fire within ~2 seconds
    private val pullMutex = Mutex()
    
    // Track last pull time for debouncing (5 second window)
    @Volatile
    private var lastPullTime = 0L
    private val pullDebounceMs = 5000L // 5 seconds

    val isOnline = networkMonitor.isOnline

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
        Log.i(TAG, ">>> pullFromServer STARTING - isOnline=${isOnline.value}")
        
        if (!isOnline.value) {
            Log.w(TAG, "<<< pullFromServer FAILED: Device is offline")
            return PullResult.Offline
        }

        // DEBOUNCE CHECK: Skip if pulled within last 5 seconds
        val now = System.currentTimeMillis()
        if (now - lastPullTime < pullDebounceMs) {
            Log.d(TAG, "Pull debounced: last pull was ${now - lastPullTime}ms ago")
            return PullResult.Offline // Treat as temporarily unavailable
        }

        Log.i(TAG, "Starting pull from server...")

        // MUTEX LOCK: Only one pull can execute at a time
        return pullMutex.withLock {
            // Double-check debounce after acquiring lock
            val nowAfterLock = System.currentTimeMillis()
            if (nowAfterLock - lastPullTime < pullDebounceMs) {
                Log.d(TAG, "Pull debounced (post-lock): last pull was ${nowAfterLock - lastPullTime}ms ago")
                return@withLock PullResult.Offline
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

                Log.i(TAG, ">>> pullFromServer SUCCESS: notes=${response.notes.size}, sessions=${response.sessions.size}, events=${response.events.size}")

                var notesUpdated = 0
                var sessionsUpdated = 0
                var eventsUpdated = 0

                // Sync notes with deduplication
                response.notes.forEach { noteInfo ->
                    val existing = noteDao.getNoteByIdSync(noteInfo.id)
                    val note = mapToNote(noteInfo)

                    // ADD: Content-based deduplication to prevent duplicates
                    val shouldInsert = if (existing == null) {
                        // Check for notes with similar content (within last 5 seconds to catch race conditions)
                        val recentNotes = noteDao.getNotesCreatedAfter(System.currentTimeMillis() - 5000)
                        val isDuplicateByContent = recentNotes.any { recentNote ->
                            recentNote.content.trim() == note.content.trim() &&
                            recentNote.title.trim() == note.title.trim()
                        }
                        if (isDuplicateByContent) {
                            Log.w(TAG, "Skipping duplicate note by content: ${noteInfo.id}")
                        }
                        !isDuplicateByContent
                    } else {
                        // Existing note - update if server version is newer
                        existing.updatedAt < noteInfo.updatedAt
                    }

                    if (shouldInsert && (existing == null || existing.updatedAt < noteInfo.updatedAt)) {
                        if (existing != null) {
                            noteDao.updateNote(note)
                        } else {
                            noteDao.insertNote(note)
                        }
                        notesUpdated++
                    }
                }

                // Sync sessions
                response.sessions.forEach { sessionData ->
                    val existingSession = chatDao.getSessionById(sessionData.id)

                    if (existingSession == null || existingSession.updatedAt < sessionData.updatedAt) {
                        val session = ChatSession(
                            id = sessionData.id,
                            title = sessionData.title ?: "Chat",
                            createdAt = sessionData.createdAt,
                            updatedAt = sessionData.updatedAt,
                            messageCount = sessionData.messageCount,
                            lastMessagePreview = sessionData.lastMessagePreview,
                            isActive = false
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
                            // Also check if a message with same role+normalized content already exists locally
                            // (app and server generate different UUIDs for the same message)
                            // BUG FIX: Normalize content before comparing - strip <think> tags and trim
                            // so server content (with think tags) matches app content (tags stripped)
                            val normalizedServerContent = normalizeContentForDedup(msgData.content)
                            val existingByContent = existingLocalMessages.any { local ->
                                local.role == msgData.role.uppercase() &&
                                normalizeContentForDedup(local.content) == normalizedServerContent
                            }
                            if (existingById == null && !existingByContent) {
                                // Extract thinking from server content if embedded in <think> tags
                                val thinking = msgData.thinking ?: extractThinkingFromContent(msgData.content)
                                val cleanContent = if (thinking != null && msgData.thinking == null) {
                                    // Server had thinking embedded in content, strip it
                                    stripThinkTags(msgData.content)
                                } else {
                                    msgData.content
                                }
                                val message = ChatMessage(
                                    id = msgData.id,
                                    role = when (msgData.role.uppercase()) {
                                        "USER" -> ChatRole.USER
                                        "SMARTY", "ASSISTANT" -> ChatRole.SMARTY
                                        else -> ChatRole.SYSTEM
                                    },
                                    content = cleanContent,
                                    thinking = thinking,
                                    timestamp = msgData.createdAt
                                )
                                val entity = com.example.smarty.core.domain.model.ChatMessageEntity.fromChatMessage(message, sessionData.id)
                                chatDao.insertMessage(entity)
                            }
                        }
                        sessionsUpdated++
                    }
                }

                // Sync events
                response.events.forEach { eventInfo ->
                    val existing = calendarDao.getEventById(eventInfo.id)

                    if (existing == null) {
                        val event = CalendarEvent(
                            id = eventInfo.id,
                            title = eventInfo.title,
                            startTime = eventInfo.startTime,
                            endTime = eventInfo.endTime,
                            description = eventInfo.description,
                            reminderMinutes = eventInfo.reminderMinutes,
                            createdAt = eventInfo.createdAt
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
                            val imgJson = JSONObject().apply {
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
                    Log.i(TAG, "Stored ${imagesStored} generated images locally")
                }

                // Update last sync time
                prefs.edit().putLong(KEY_LAST_PULL, System.currentTimeMillis()).apply()

                val duration = System.currentTimeMillis() - startTime
                Log.i(TAG, "Pull complete in ${duration}ms: $notesUpdated notes, $sessionsUpdated sessions, $eventsUpdated events, ${generatedImages.size} generated images")
                PullResult.Success(notesUpdated, sessionsUpdated, eventsUpdated)
            } catch (e: Exception) {
                Log.e(TAG, "Pull failed", e)
                PullResult.Error(e.message ?: "Unknown error")
            }
        }
    }

    suspend fun pushPendingChanges(): PushResult {
        if (!isOnline.value) {
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
                            notesToPush.add(NotePushItem(
                                id = note.id,
                                title = note.title,
                                content = note.content,
                                categoryId = null, // TODO: resolve category name to ID
                                updatedAt = note.updatedAt
                            ))
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error preparing note for push: ${item.entityId}", e)
                    }
                }
                SyncEntityType.EVENT.name -> {
                    try {
                        val event = calendarDao.getEventById(item.entityId)
                        if (event != null) {
                            eventsToPush.add(EventPushItem(
                                id = event.id,
                                title = event.title,
                                startTime = event.startTime,
                                endTime = event.endTime,
                                description = event.description,
                                reminderMinutes = event.reminderMinutes ?: 15
                            ))
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

        val request = SyncPushRequest(
            notes = notesToPush.ifEmpty { null },
            sessions = sessionsToPush.ifEmpty { null },
            events = eventsToPush.ifEmpty { null }
        )

        return try {
            val response = remoteDataSource.pushChanges(request)
            if (response == null) {
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
                // Mark items as failed
                pendingItems.forEach { item ->
                    syncQueueDao.markFailed(item.id, response.errors.joinToString())
                }
                PushResult.Error(response.errors.joinToString())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Push failed", e)
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
            categoryName = null, // TODO: resolve categoryId to name
            isArchived = info.isArchived,
            createdAt = info.createdAt,
            updatedAt = info.updatedAt,
            type = com.example.smarty.core.domain.model.NoteType.BRAIN_DUMP,
            processingStatus = com.example.smarty.core.domain.model.ProcessingStatus.COMPLETED,
            isAiCreated = true
        )
    }

    companion object {
        private const val TAG = "SyncCoordinator"
        private const val PREFS_NAME = "sync_prefs"
        private const val PREFS_GENERATED_IMAGES = "generated_images_prefs"
        private const val KEY_LAST_PULL = "last_pull"
        private const val KEY_LAST_PUSH = "last_push"

        // Regex patterns for content normalization (deduplication)
        private val THINK_TAG_REGEX = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)
        private val THINK_OPEN_REGEX = Regex("<think>.*", RegexOption.DOT_MATCHES_ALL)
        private val FINAL_TAG_REGEX = Regex("<final>.*?</final>", RegexOption.DOT_MATCHES_ALL)

        /**
         * Normalize content for deduplication comparison.
         * Strips <think> and <final> tags, trims whitespace.
         * This ensures that server content (with think tags) matches app content (tags stripped).
         */
        fun normalizeContentForDedup(content: String): String {
            return content
                .replace(THINK_TAG_REGEX, "")
                .replace(THINK_OPEN_REGEX, "")
                .replace(FINAL_TAG_REGEX, "")
                .trim()
        }

        /**
         * Extract thinking content from <think> tags in the message content.
         */
        fun extractThinkingFromContent(content: String): String? {
            val match = THINK_TAG_REGEX.find(content)
            return match?.groupValues?.getOrNull(0)
                ?.removePrefix("<think>")
                ?.removeSuffix("</think>")
                ?.trim()
                ?.ifBlank { null }
        }

        /**
         * Strip <think> tags from content, leaving only the actual response.
         */
        fun stripThinkTags(content: String): String {
            return content
                .replace(THINK_TAG_REGEX, "")
                .replace(THINK_OPEN_REGEX, "")
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
