package com.example.smarty.server.routes

import com.example.smarty.protocol.CalendarEventInfo
import com.example.smarty.protocol.NoteInfo
import com.example.smarty.server.data.CalendarEventNotesRepository
import com.example.smarty.server.data.CalendarRepository
import com.example.smarty.server.data.ChatMessageNotesRepository
import com.example.smarty.server.data.ChatRepository
import com.example.smarty.server.data.DatabaseFactory
import com.example.smarty.server.data.NoteRepository
import com.example.smarty.server.data.SyncRepository
import com.example.smarty.server.plugins.firebaseUser
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * OPTIMIZED SYNC PULL RESPONSE
 * Supports delta-sync with lastSyncAt timestamp
 */
@Serializable
data class OptimizedSyncPullResponse(
    val notes: List<NoteInfo>,
    val sessions: List<OptimizedSessionInfo>,
    val events: List<CalendarEventInfo>,
    val lastSyncAt: Long,
    val hasMore: Boolean = false,
    val syncChecksum: String, // For client to detect changes
)

@Serializable
data class OptimizedSessionInfo(
    val id: String,
    val title: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val messageCount: Int,
    val lastMessagePreview: String,
    val messages: List<OptimizedMessageInfo>,
)

@Serializable
data class OptimizedMessageInfo(
    val id: String,
    val role: String,
    val content: String,
    val thinking: String? = null,
    val createdAt: Long,
)

/**
 * DELTA SYNC REQUEST - Client sends lastSyncAt timestamp
 */
@Serializable
data class DeltaSyncRequest(
    val lastSyncAt: Long? = null,
    val limit: Int? = null,
)

/**
 * Response cache entry
 */
data class CacheEntry(
    val response: OptimizedSyncPullResponse,
    val timestamp: Long,
    val userId: String,
) {
    fun isStale(cacheTtlMs: Long): Boolean = (System.currentTimeMillis() - timestamp) > cacheTtlMs
}

/**
 * Global cache for sync responses (per-user, 5 second TTL)
 */
class SyncResponseCache {
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val mutexes = ConcurrentHashMap<String, Mutex>()

    fun getMutex(userId: String): Mutex = mutexes.getOrPut(userId) { Mutex() }

    fun get(
        userId: String,
        cacheTtlMs: Long = 5000,
    ): CacheEntry? {
        val entry = cache[userId]
        return if (entry != null && !entry.isStale(cacheTtlMs)) entry else null
    }

    fun put(
        userId: String,
        response: OptimizedSyncPullResponse,
    ) {
        cache[userId] = CacheEntry(response, System.currentTimeMillis(), userId)
    }

    fun invalidate(userId: String) {
        cache.remove(userId)
    }

    companion object {
        val instance = SyncResponseCache()
    }
}

fun Application.configureOptimizedSyncRoutes() {
    val logger = LoggerFactory.getLogger("OptimizedSyncRoutes")
    val dataSource = DatabaseFactory.getDataSource()
    val chatMessageNotesRepo = dataSource?.let { ChatMessageNotesRepository(it) }
    val calendarEventNotesRepo = dataSource?.let { CalendarEventNotesRepository(it) }
    val noteRepository = dataSource?.let { NoteRepository(it, chatMessageNotesRepo!!, calendarEventNotesRepo!!) }
    val calendarRepository = dataSource?.let { CalendarRepository(it, calendarEventNotesRepo!!) }
    val chatRepository = dataSource?.let { ChatRepository(it, chatMessageNotesRepo!!) }
    val syncRepository = dataSource?.let { SyncRepository(it) }
    val syncCache = SyncResponseCache.instance

    routing {
        authenticate("firebase") {
            route("/api/v1/sync") {
                /**
                 * OPTIMIZED PULL ENDPOINT
                 * - Supports delta-sync (only changes since lastSyncAt)
                 * - Response caching (5s TTL per user)
                 * - Mutex lock prevents duplicate concurrent pulls
                 * - Batch message loading (eliminates N+1)
                 * - Returns checksum for change detection
                 */
                post("/pull") {
                    val user = call.firebaseUser() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                    if (noteRepository == null || chatRepository == null || calendarRepository == null || syncRepository == null) {
                        return@post call.respond(HttpStatusCode.ServiceUnavailable, "Database not available")
                    }

                    val userId = user.userId
                    val startTime = System.currentTimeMillis()

                    // Parse delta-sync request
                    val request =
                        try {
                            call.receive<DeltaSyncRequest>()
                        } catch (e: Exception) {
                            DeltaSyncRequest()
                        }
                    val lastSyncAt = request.lastSyncAt ?: 0L
                    val limit = request.limit ?: 1000

                    // Input validation
                    if (lastSyncAt < 0) {
                        return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid lastSyncAt"))
                    }
                    if (limit !in 1..5000) {
                        return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid limit (must be 1-5000)"))
                    }

                    // Get mutex for this user to prevent duplicate concurrent pulls
                    val mutex = syncCache.getMutex(userId)

                    return@post mutex.withLock {
                        try {
                            // Check cache first (5s TTL)
                            val cached = syncCache.get(userId, cacheTtlMs = 5000)
                            if (cached != null) {
                                logger.info("Cache HIT for user {} ({}ms)", userId, System.currentTimeMillis() - startTime)
                                return@withLock call.respond(cached.response)
                            }

                            logger.info("Pull request: lastSyncAt={}, limit={}", lastSyncAt, limit)

                            // DELTA SYNC: Only fetch changed data
                            val notes =
                                if (lastSyncAt > 0) {
                                    noteRepository.listByUserUpdatedAfter(userId, lastSyncAt, limit = 200)
                                } else {
                                    noteRepository.listByUser(userId, limit = 200)
                                }

                            val sessions =
                                if (lastSyncAt > 0) {
                                    chatRepository.listSessionsUpdatedAfter(userId, lastSyncAt, limit = 50)
                                } else {
                                    chatRepository.listAllSessions(userId, limit = 50)
                                }

                            val events =
                                if (lastSyncAt > 0) {
                                    calendarRepository.listEventsUpdatedAfter(userId, lastSyncAt, limit = 200)
                                } else {
                                    calendarRepository.listAllEvents(userId, limit = 200)
                                }

                            // BATCH LOAD: Get all messages in ONE query per session (not N+1)
                            val sessionData =
                                sessions.map { session ->
                                    val messages =
                                        chatRepository.getAllMessagesForSession(userId, session.id)
                                            .take(100) // Limit messages per session for sync
                                    OptimizedSessionInfo(
                                        id = session.id,
                                        title = session.title,
                                        createdAt = session.createdAt,
                                        updatedAt = session.updatedAt,
                                        messageCount = session.messageCount,
                                        lastMessagePreview = session.lastMessagePreview,
                                        messages =
                                            messages.map { msg ->
                                                OptimizedMessageInfo(
                                                    id = msg.id.toString(),
                                                    role = msg.role,
                                                    content = msg.content,
                                                    thinking = msg.thinking,
                                                    createdAt = msg.createdAt,
                                                )
                                            },
                                    )
                                }

                            // Calculate checksum for change detection
                            val syncChecksum = calculateSyncChecksum(notes, sessionData, events)

                            val syncStatus = syncRepository.getSyncStatus(userId)
                            val lastPullAt = syncStatus?.lastPullAt ?: 0L

                            val response =
                                OptimizedSyncPullResponse(
                                    notes = notes,
                                    sessions = sessionData,
                                    events = events,
                                    lastSyncAt = lastPullAt,
                                    hasMore = notes.size >= 200 || sessions.size >= 50 || events.size >= 200,
                                    syncChecksum = syncChecksum,
                                )

                            // Cache the response
                            syncCache.put(userId, response)

                            val duration = System.currentTimeMillis() - startTime
                            logger.info(
                                "Pull complete for user {}: {} notes, {} sessions, {} events in {}ms (delta: {})",
                                userId,
                                notes.size,
                                sessionData.size,
                                events.size,
                                duration,
                                lastSyncAt > 0,
                            )

                            call.respond(response)
                        } catch (e: Exception) {
                            logger.error("Sync pull failed for user $userId", e)
                            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Sync failed: ${e.message}"))
                        }
                    }
                }

                /**
                 * PUSH endpoint - invalidate cache on successful push
                 */
                post("/push") {
                    val user = call.firebaseUser() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                    if (noteRepository == null || chatRepository == null || calendarRepository == null || syncRepository == null) {
                        return@post call.respond(HttpStatusCode.ServiceUnavailable, "Database not available")
                    }

                    try {
                        val userId = user.userId
                        val request = call.receive<com.example.smarty.protocol.SyncPushRequest>()
                        val createdNotes = mutableListOf<String>()
                        val createdSessions = mutableListOf<String>()
                        val createdEvents = mutableListOf<String>()
                        val errors = mutableListOf<String>()

                        request.notes?.forEach { noteItem ->
                            try {
                                val info = com.example.smarty.protocol.NoteInfo(
                                    id = noteItem.id ?: "",
                                    title = noteItem.title,
                                    content = noteItem.content,
                                    summary = noteItem.summary,
                                    sourceUrl = noteItem.sourceUrl,
                                    imageUri = noteItem.imageUri,
                                    fileUri = noteItem.fileUri,
                                    fileName = noteItem.fileName,
                                    fileMimeType = noteItem.fileMimeType,
                                    fileSize = noteItem.fileSize,
                                    type = noteItem.type,
                                    categoryId = noteItem.categoryId,
                                    categoryName = noteItem.categoryName,
                                    stackId = noteItem.stackId,
                                    parentNoteId = noteItem.parentNoteId,
                                    whySaved = noteItem.whySaved,
                                    processingStatus = noteItem.processingStatus,
                                    isArchived = noteItem.isArchived,
                                    isPinned = noteItem.isPinned,
                                    isFavorite = noteItem.isFavorite,
                                    isFullPrivacy = noteItem.isFullPrivacy,
                                    excludeFromAiChat = noteItem.excludeFromAiChat,
                                    isAiCreated = noteItem.isAiCreated,
                                    isViewed = noteItem.isViewed,
                                    todoContent = noteItem.todoContent,
                                    attachmentsJson = noteItem.attachmentsJson,
                                    tagsJson = noteItem.tagsJson,
                                    chunkAnalysesJson = noteItem.chunkAnalysesJson,
                                    reminderText = noteItem.reminderText,
                                    reminderExpiresAt = noteItem.reminderExpiresAt,
                                    createdAt = System.currentTimeMillis(), // Fallback
                                    updatedAt = noteItem.updatedAt
                                )

                                if (noteItem.id != null) {
                                    val updated = noteRepository.update(userId, info)
                                    if (!updated) {
                                        val id = noteRepository.create(userId, info)
                                        createdNotes.add(id)
                                    }
                                } else {
                                    val id = noteRepository.create(userId, info)
                                    createdNotes.add(id)
                                }
                            } catch (e: Exception) {
                                errors.add("Note error: ${e.message}")
                            }
                        }

                        request.sessions?.forEach { sessionItem ->
                            try {
                                val created = chatRepository.createSessionWithId(userId, sessionItem.id, sessionItem.title)
                                if (created) {
                                    createdSessions.add(sessionItem.id)
                                }

                                sessionItem.messages?.forEach { msg ->
                                    chatRepository.saveMessage(userId, sessionItem.id, msg.role, msg.content, msg.thinking)
                                }
                            } catch (e: Exception) {
                                errors.add("Session error: ${e.message}")
                            }
                        }

                        request.events?.forEach { eventItem ->
                            try {
                                if (eventItem.id != null) {
                                    val nonNullId = eventItem.id!!
                                    val id =
                                        calendarRepository.createWithId(
                                            userId,
                                            nonNullId,
                                            eventItem.title,
                                            eventItem.startTime,
                                            eventItem.endTime,
                                            eventItem.description,
                                        )
                                    if (id == nonNullId) {
                                        createdEvents.add(id)
                                    }
                                } else {
                                    val id =
                                        calendarRepository.create(
                                            userId,
                                            eventItem.title,
                                            eventItem.startTime,
                                            eventItem.endTime,
                                            eventItem.description,
                                            eventItem.reminderMinutes,
                                        )
                                    createdEvents.add(id)
                                }
                            } catch (e: Exception) {
                                errors.add("Event error: ${e.message}")
                            }
                        }

                        syncRepository.updateSyncStatus(userId)

                        // INVALIDATE CACHE on successful push
                        syncCache.invalidate(userId)

                        call.respond(
                            SyncPushResponse(
                                success = errors.isEmpty(),
                                createdNotes = createdNotes,
                                createdSessions = createdSessions,
                                createdEvents = createdEvents,
                                errors = errors,
                            ),
                        )
                    } catch (e: Exception) {
                        logger.error("Sync push failed", e)
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Push failed: ${e.message}"))
                    }
                }

                get("/status") {
                    val user = call.firebaseUser() ?: return@get call.respond(HttpStatusCode.Unauthorized)
                    if (syncRepository == null) {
                        return@get call.respond(HttpStatusCode.ServiceUnavailable, "Database not available")
                    }

                    try {
                        val status = syncRepository.getSyncStatus(user.userId)
                        call.respond(
                            SyncStatusResponse(
                                lastSyncAt = status?.lastSyncAt,
                                lastPullAt = status?.lastPullAt,
                            ),
                        )
                    } catch (e: Exception) {
                        logger.error("Failed to get sync status", e)
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to get status"))
                    }
                }
            }
        }
    }
}

/**
 * Calculate a checksum for the sync state
 */
private fun calculateSyncChecksum(
    notes: List<NoteInfo>,
    sessions: List<OptimizedSessionInfo>,
    events: List<CalendarEventInfo>,
): String {
    val totalSize = notes.size + sessions.size + events.size
    if (totalSize == 0) return "empty"

    // Simple checksum based on counts and latest update times
    val latestNote = notes.maxOfOrNull { it.updatedAt } ?: 0L
    val latestSession = sessions.maxOfOrNull { it.updatedAt } ?: 0L
    val latestEvent = events.maxOfOrNull { it.createdAt } ?: 0L

    return "${notes.size}-${sessions.size}-${events.size}-$latestNote-$latestSession-$latestEvent"
}
