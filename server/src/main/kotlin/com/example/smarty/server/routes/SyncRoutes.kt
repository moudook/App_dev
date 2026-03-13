package com.example.smarty.server.routes

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import io.ktor.server.auth.*
import com.example.smarty.server.data.DatabaseFactory
import com.example.smarty.server.data.NoteRepository
import com.example.smarty.server.data.CalendarRepository
import com.example.smarty.server.data.ChatRepository
import com.example.smarty.server.data.ChatMessageNotesRepository
import com.example.smarty.server.data.CalendarEventNotesRepository
import com.example.smarty.server.plugins.firebaseUser
import com.example.smarty.protocol.NoteInfo
import com.example.smarty.protocol.CalendarEventInfo
import com.example.smarty.server.data.SessionInfo
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

@Serializable
data class SyncPullResponse(
    val notes: List<NoteInfo>,
    val sessions: List<SessionInfoData>,
    val events: List<CalendarEventInfo>,
    val lastSyncAt: Long
)

@Serializable
data class SessionInfoData(
    val id: String,
    val title: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val messageCount: Int,
    val lastMessagePreview: String,
    val messages: List<MessageInfoData>
)

@Serializable
data class MessageInfoData(
    val id: String,
    val role: String,
    val content: String,
    val thinking: String? = null,
    val createdAt: Long
)

@Serializable
data class SyncPushRequest(
    val notes: List<NotePushItem>? = null,
    val sessions: List<SessionPushItem>? = null,
    val events: List<EventPushItem>? = null
)

@Serializable
data class NotePushItem(
    val id: String? = null,
    val title: String,
    val content: String,
    val category: String? = null,
    val updatedAt: Long
)

@Serializable
data class SessionPushItem(
    val id: String,
    val title: String?,
    val createdAt: Long,
    val messages: List<MessagePushItem>? = null
)

@Serializable
data class MessagePushItem(
    val id: String? = null,
    val role: String,
    val content: String,
    val thinking: String? = null,
    val createdAt: Long
)

@Serializable
data class EventPushItem(
    val id: String? = null,
    val title: String,
    val startTime: Long,
    val endTime: Long,
    val description: String? = null,
    val reminderMinutes: Int = 15
)

@Serializable
data class SyncPushResponse(
    val success: Boolean,
    val createdNotes: List<String> = emptyList(),
    val createdSessions: List<String> = emptyList(),
    val createdEvents: List<String> = emptyList(),
    val errors: List<String> = emptyList()
)

@Serializable
data class SyncStatusResponse(
    val lastSyncAt: Long?,
    val lastPullAt: Long?
)

fun Application.configureSyncRoutes() {
    val logger = LoggerFactory.getLogger("SyncRoutes")
    val dataSource = DatabaseFactory.getDataSource()
    val chatMessageNotesRepo = dataSource?.let { ChatMessageNotesRepository(it) }
    val calendarEventNotesRepo = dataSource?.let { CalendarEventNotesRepository(it) }
    val noteRepository = dataSource?.let { NoteRepository(it, chatMessageNotesRepo!!, calendarEventNotesRepo!!) }
    val calendarRepository = dataSource?.let { CalendarRepository(it, calendarEventNotesRepo!!) }
    val chatRepository = dataSource?.let { ChatRepository(it, chatMessageNotesRepo!!) }
    val syncRepository = dataSource?.let { com.example.smarty.server.data.SyncRepository(it) }

    routing {
        authenticate("firebase") {
            route("/api/v1/sync") {
                
                post("/pull") {
                    val user = call.firebaseUser() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                    if (noteRepository == null || chatRepository == null || calendarRepository == null || syncRepository == null) {
                        return@post call.respond(HttpStatusCode.ServiceUnavailable, "Database not available")
                    }

                    try {
                        val userId = user.userId
                        
                        val notes = noteRepository.listByUser(userId, limit = 1000)
                        val sessions = chatRepository.listAllSessions(userId, limit = 100)
                        val events = calendarRepository.listAllEvents(userId, limit = 500)
                        
                        val sessionData = sessions.map { session ->
                            val messages = chatRepository.getAllMessagesForSession(userId, session.id)
                            SessionInfoData(
                                id = session.id,
                                title = session.title,
                                createdAt = session.createdAt,
                                updatedAt = session.updatedAt,
                                messageCount = session.messageCount,
                                lastMessagePreview = session.lastMessagePreview,
                                messages = messages.map { msg ->
                                    MessageInfoData(
                                        id = msg.id.toString(),
                                        role = msg.role,
                                        content = msg.content,
                                        thinking = msg.thinking,
                                        createdAt = msg.createdAt
                                    )
                                }
                            )
                        }

                        val syncStatus = syncRepository.getSyncStatus(userId)
                        
                        call.respond(SyncPullResponse(
                            notes = notes,
                            sessions = sessionData,
                            events = events,
                            lastSyncAt = syncStatus?.lastPullAt ?: 0L
                        ))
                    } catch (e: Exception) {
                        logger.error("Sync pull failed", e)
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Sync failed: ${e.message}"))
                    }
                }

                post("/push") {
                    val user = call.firebaseUser() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                    if (noteRepository == null || chatRepository == null || calendarRepository == null || syncRepository == null) {
                        return@post call.respond(HttpStatusCode.ServiceUnavailable, "Database not available")
                    }

                    try {
                        val userId = user.userId
                        val request = call.receive<SyncPushRequest>()
                        val createdNotes = mutableListOf<String>()
                        val createdSessions = mutableListOf<String>()
                        val createdEvents = mutableListOf<String>()
                        val errors = mutableListOf<String>()

                        request.notes?.forEach { noteItem ->
                            try {
                                if (noteItem.id != null) {
                                    val updated = noteRepository.update(userId, noteItem.id, noteItem.title, noteItem.content, noteItem.category)
                                    if (!updated) {
                                        val id = noteRepository.create(userId, noteItem.title, noteItem.content, noteItem.category)
                                        createdNotes.add(id)
                                    }
                                } else {
                                    val id = noteRepository.create(userId, noteItem.title, noteItem.content, noteItem.category)
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
                                    val id = calendarRepository.createWithId(
                                        userId, eventItem.id, eventItem.title,
                                        eventItem.startTime, eventItem.endTime,
                                        eventItem.description
                                    )
                                    if (id == eventItem.id) {
                                        createdEvents.add(id)
                                    }
                                } else {
                                    val id = calendarRepository.create(
                                        userId, eventItem.title, 
                                        eventItem.startTime, eventItem.endTime,
                                        eventItem.description, eventItem.reminderMinutes
                                    )
                                    createdEvents.add(id)
                                }
                            } catch (e: Exception) {
                                errors.add("Event error: ${e.message}")
                            }
                        }

                        syncRepository.updateSyncStatus(userId)

                        call.respond(SyncPushResponse(
                            success = errors.isEmpty(),
                            createdNotes = createdNotes,
                            createdSessions = createdSessions,
                            createdEvents = createdEvents,
                            errors = errors
                        ))
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
                        call.respond(SyncStatusResponse(
                            lastSyncAt = status?.lastSyncAt,
                            lastPullAt = status?.lastPullAt
                        ))
                    } catch (e: Exception) {
                        logger.error("Failed to get sync status", e)
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to get status"))
                    }
                }
            }

            route("/api/v1/chat") {
                
                get("/sessions") {
                    val user = call.firebaseUser() ?: return@get call.respond(HttpStatusCode.Unauthorized)
                    if (chatRepository == null) {
                        return@get call.respond(HttpStatusCode.ServiceUnavailable, "Database not available")
                    }

                    try {
                        val sessions = chatRepository.listAllSessions(user.userId, limit = 100)
                        call.respond(sessions)
                    } catch (e: Exception) {
                        logger.error("Failed to list sessions", e)
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to list sessions"))
                    }
                }

                get("/sessions/{id}") {
                    val user = call.firebaseUser() ?: return@get call.respond(HttpStatusCode.Unauthorized)
                    if (chatRepository == null) {
                        return@get call.respond(HttpStatusCode.ServiceUnavailable, "Database not available")
                    }

                    val sessionId = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    
                    try {
                        val session = chatRepository.getSession(user.userId, sessionId)
                        if (session == null) {
                            return@get call.respond(HttpStatusCode.NotFound)
                        }
                        
                        val messages = chatRepository.getAllMessagesForSession(user.userId, sessionId)
                        call.respond(mapOf(
                            "session" to session,
                            "messages" to messages
                        ))
                    } catch (e: Exception) {
                        logger.error("Failed to get session", e)
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to get session"))
                    }
                }

                post("/sessions") {
                    val user = call.firebaseUser() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                    if (chatRepository == null) {
                        return@post call.respond(HttpStatusCode.ServiceUnavailable, "Database not available")
                    }

                    try {
                        val request = call.receive<CreateSessionRequest>()
                        val id = chatRepository.createSession(user.userId, request.title)
                        call.respond(HttpStatusCode.Created, mapOf("id" to id))
                    } catch (e: Exception) {
                        logger.error("Failed to create session", e)
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to create session"))
                    }
                }

                post("/messages") {
                    val user = call.firebaseUser() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                    if (chatRepository == null) {
                        return@post call.respond(HttpStatusCode.ServiceUnavailable, "Database not available")
                    }

                    try {
                        val request = call.receive<SaveMessageRequest>()
                        // ✅ Pass thinking parameter to repository for persistence
                        chatRepository.saveMessage(
                            user.userId,
                            request.sessionId,
                            request.role,
                            request.content,
                            request.thinking
                        )
                        call.respond(HttpStatusCode.OK)
                    } catch (e: Exception) {
                        logger.error("Failed to save message", e)
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to save message"))
                    }
                }

                delete("/sessions/{id}") {
                    val user = call.firebaseUser() ?: return@delete call.respond(HttpStatusCode.Unauthorized)
                    if (chatRepository == null) {
                        return@delete call.respond(HttpStatusCode.ServiceUnavailable, "Database not available")
                    }

                    val sessionId = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                    
                    try {
                        val deleted = chatRepository.deleteSession(user.userId, sessionId)
                        if (deleted) {
                            call.respond(HttpStatusCode.OK)
                        } else {
                            call.respond(HttpStatusCode.NotFound)
                        }
                    } catch (e: Exception) {
                        logger.error("Failed to delete session", e)
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to delete session"))
                    }
                }
            }
        }
    }
}

@Serializable
data class CreateSessionRequest(val title: String? = null)

/**
 * Request to save a chat message to the server.
 * 
 * @param sessionId Chat session ID
 * @param role Message role (USER, ASSISTANT, SYSTEM)
 * @param content Message content
 * @param thinking Optional AI thinking/reasoning content (for collapsible display)
 */
@Serializable
data class SaveMessageRequest(
    val sessionId: String,
    val role: String,
    val content: String,
    val thinking: String? = null  // ✅ Added thinking field for AI reasoning persistence
)
