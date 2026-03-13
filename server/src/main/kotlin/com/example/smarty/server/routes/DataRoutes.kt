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
import com.example.smarty.server.data.TimerRepository
import com.example.smarty.server.data.FcmTokenRepository
import com.example.smarty.server.data.ChatMessageNotesRepository
import com.example.smarty.server.data.CalendarEventNotesRepository
import com.example.smarty.server.plugins.FirebaseUserPrincipal
import com.example.smarty.server.plugins.firebaseUser
import com.example.smarty.protocol.NoteInfo
import com.example.smarty.protocol.CalendarEventInfo
import com.example.smarty.protocol.TimerInfo
import kotlinx.serialization.Serializable
import java.util.UUID

// DTOs for Create/Update requests
@Serializable
data class CreateNoteRequest(val title: String, val content: String, val categoryId: String? = null)

@Serializable
data class UpdateNoteRequest(val title: String? = null, val content: String? = null, val categoryId: String? = null)

@Serializable
data class CreateEventRequest(val title: String, val startTime: Long, val endTime: Long, val description: String? = null, val reminderMinutes: Int = 15)

@Serializable
data class CreateTimerRequest(val name: String, val durationMs: Long, val isAlarm: Boolean = false)

@Serializable
data class RegisterFcmTokenRequest(val token: String, val deviceName: String? = null, val deviceId: String? = null)

// --- VAULT DTOs ---
@Serializable
data class VaultStoreRequest(val encryptedBlob: String, val version: Int)

@Serializable
data class VaultResponse(val encryptedBlob: String, val version: Int, val updatedAt: Long)

fun Application.configureDataRoutes() {
    val dataSource = DatabaseFactory.getDataSource()
    val chatMessageNotesRepo = dataSource?.let { ChatMessageNotesRepository(it) }
    val calendarEventNotesRepo = dataSource?.let { CalendarEventNotesRepository(it) }
    val noteRepository = dataSource?.let { NoteRepository(it, chatMessageNotesRepo!!, calendarEventNotesRepo!!) }
    val calendarRepository = dataSource?.let { CalendarRepository(it, calendarEventNotesRepo!!) }
    val timerRepository = dataSource?.let { TimerRepository(it) }
    val fcmTokenRepository = dataSource?.let { FcmTokenRepository(it) }
    val database = DatabaseFactory.getDatabase()
    val vaultRepository = database?.let { com.example.smarty.server.data.VaultRepository(it) }

    routing {
        authenticate("firebase") {
            route("/api/v1") {
                
                // --- NOTES ---
                route("/notes") {
                    get {
                        val user = call.firebaseUser() ?: return@get call.respond(HttpStatusCode.Unauthorized)
                        if (noteRepository == null) return@get call.respond(HttpStatusCode.ServiceUnavailable, "Database not available")
                        
                        val notes = noteRepository.listByUser(user.userId)
                        call.respond(notes)
                    }

                    post {
                        val user = call.firebaseUser() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                        if (noteRepository == null) return@post call.respond(HttpStatusCode.ServiceUnavailable, "Database not available")
                        
                        try {
                            val request = call.receive<CreateNoteRequest>()
                            val id = noteRepository.create(user.userId, request.title, request.content, request.categoryId)
                            call.respond(HttpStatusCode.Created, mapOf("id" to id))
                        } catch (e: Exception) {
                            call.application.log.error("Failed to create note", e)
                            call.respond(HttpStatusCode.BadRequest, "Invalid request")
                        }
                    }

                    route("/{id}") {
                        put {
                            val user = call.firebaseUser() ?: return@put call.respond(HttpStatusCode.Unauthorized)
                            if (noteRepository == null) return@put call.respond(HttpStatusCode.ServiceUnavailable, "Database not available")
                            
                            val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest)
                            try {
                                val request = call.receive<UpdateNoteRequest>()
                                val updated = noteRepository.update(user.userId, id, request.title, request.content, request.categoryId)
                                if (updated) call.respond(HttpStatusCode.OK)
                                else call.respond(HttpStatusCode.NotFound)
                            } catch (e: Exception) {
                                call.respond(HttpStatusCode.BadRequest)
                            }
                        }

                        delete {
                            val user = call.firebaseUser() ?: return@delete call.respond(HttpStatusCode.Unauthorized)
                            if (noteRepository == null) return@delete call.respond(HttpStatusCode.ServiceUnavailable, "Database not available")
                            
                            val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                            val deleted = noteRepository.delete(user.userId, id)
                            if (deleted) call.respond(HttpStatusCode.OK)
                            else call.respond(HttpStatusCode.NotFound)
                        }
                    }
                }

                // --- CALENDAR ---
                route("/calendar") {
                    get {
                        val user = call.firebaseUser() ?: return@get call.respond(HttpStatusCode.Unauthorized)
                        if (calendarRepository == null) return@get call.respond(HttpStatusCode.ServiceUnavailable, "Database not available")
                        
                        val events = calendarRepository.listUpcoming(user.userId)
                        call.respond(events)
                    }

                    post {
                        val user = call.firebaseUser() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                        if (calendarRepository == null) return@post call.respond(HttpStatusCode.ServiceUnavailable, "Database not available")
                        
                        try {
                            val request = call.receive<CreateEventRequest>()
                            val id = calendarRepository.create(user.userId, request.title, request.startTime, request.endTime, request.description, request.reminderMinutes)
                            call.respond(HttpStatusCode.Created, mapOf("id" to id))
                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.BadRequest)
                        }
                    }

                    delete("/{id}") {
                        val user = call.firebaseUser() ?: return@delete call.respond(HttpStatusCode.Unauthorized)
                        if (calendarRepository == null) return@delete call.respond(HttpStatusCode.ServiceUnavailable, "Database not available")
                        
                        val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                        val deleted = calendarRepository.delete(user.userId, id)
                        if (deleted) call.respond(HttpStatusCode.OK)
                        else call.respond(HttpStatusCode.NotFound)
                    }
                }

                // --- TIMERS ---
                route("/timers") {
                    get {
                        val user = call.firebaseUser() ?: return@get call.respond(HttpStatusCode.Unauthorized)
                        if (timerRepository == null) return@get call.respond(HttpStatusCode.ServiceUnavailable, "Database not available")
                        
                        val timers = timerRepository.listActive(user.userId)
                        call.respond(timers)
                    }

                    post {
                        val user = call.firebaseUser() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                        if (timerRepository == null) return@post call.respond(HttpStatusCode.ServiceUnavailable, "Database not available")
                        
                        try {
                            // Note: We might need a unified creation logic if we want to support 'triggerAt' for alarms specifically
                            val request = call.receive<CreateTimerRequest>()
                            val id = timerRepository.create(user.userId, request.name, durationMs = request.durationMs, isAlarm = request.isAlarm)
                            call.respond(HttpStatusCode.Created, mapOf("id" to id))
                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.BadRequest)
                        }
                    }

                    delete("/{id}") {
                        val user = call.firebaseUser() ?: return@delete call.respond(HttpStatusCode.Unauthorized)
                        if (timerRepository == null) return@delete call.respond(HttpStatusCode.ServiceUnavailable, "Database not available")
                        
                        val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                        val deleted = timerRepository.delete(user.userId, id)
                        if (deleted) call.respond(HttpStatusCode.OK)
                        else call.respond(HttpStatusCode.NotFound)
                    }
                }

                // --- FCM TOKENS ---
                route("/fcm") {
                    post("/register") {
                        val user = call.firebaseUser() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                        if (fcmTokenRepository == null) return@post call.respond(HttpStatusCode.ServiceUnavailable, "Database not available")
                        
                        try {
                            val request = call.receive<RegisterFcmTokenRequest>()
                            if (request.token.isBlank()) {
                                return@post call.respond(HttpStatusCode.BadRequest, "Token is required")
                            }
                            fcmTokenRepository.upsertToken(user.userId, request.token, request.deviceName, request.deviceId)
                            call.respond(HttpStatusCode.OK)
                        } catch (e: Exception) {
                            call.application.log.error("Failed to register FCM token", e)
                            call.respond(HttpStatusCode.BadRequest)
                        }
                    }
                }

                // --- ZERO-KNOWLEDGE VAULT ---
                route("/vault") {
                    get {
                        val user = call.firebaseUser() ?: return@get call.respond(HttpStatusCode.Unauthorized)
                        if (vaultRepository == null) return@get call.respond(HttpStatusCode.ServiceUnavailable, "Database not available")

                        val data = vaultRepository.get(user.userId)
                        if (data != null) {
                            call.respond(VaultResponse(data.encryptedBlob, data.version, data.updatedAt))
                        } else {
                            call.respond(HttpStatusCode.NotFound)
                        }
                    }

                    post {
                        val user = call.firebaseUser() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                        if (vaultRepository == null) return@post call.respond(HttpStatusCode.ServiceUnavailable, "Database not available")

                        try {
                            val request = call.receive<VaultStoreRequest>()
                            // Basic valid checks (e.g. max size 10MB)
                            if (request.encryptedBlob.length > 10_000_000) { 
                                return@post call.respond(HttpStatusCode.PayloadTooLarge, "Vault blob exceeds 10MB limit")
                            }
                            
                            vaultRepository.store(user.userId, request.encryptedBlob, request.version)
                            call.respond(HttpStatusCode.OK)
                        } catch (e: Exception) {
                            call.application.log.error("Failed to update vault", e)
                            call.respond(HttpStatusCode.BadRequest)
                        }
                    }

                    delete {
                        val user = call.firebaseUser() ?: return@delete call.respond(HttpStatusCode.Unauthorized)
                        if (vaultRepository == null) return@delete call.respond(HttpStatusCode.ServiceUnavailable, "Database not available")

                        vaultRepository.delete(user.userId)
                        call.respond(HttpStatusCode.OK)
                    }
                }

                // --- EXPORT ALL DATA (Cloud Backup) ---
                get("/export/all") {
                    val user = call.firebaseUser() ?: return@get call.respond(HttpStatusCode.Unauthorized)
                    if (noteRepository == null || calendarRepository == null || timerRepository == null) {
                        return@get call.respond(HttpStatusCode.ServiceUnavailable, "Database not available")
                    }

                    try {
                        val userId = user.userId

                        // Fetch ALL user data (high limits for complete export)
                        val notes = noteRepository.listByUser(userId, limit = 10000)
                        val events = calendarRepository.listAllEvents(userId, limit = 5000)
                        val timers = timerRepository.listActive(userId)

                        // Build export response
                        val exportData = ExportAllDataResponse(
                            notes = notes,
                            events = events,
                            timers = timers,
                            exportedAt = System.currentTimeMillis()
                        )

                        call.application.log.info("Export requested for user $userId: ${notes.size} notes, ${events.size} events, ${timers.size} timers")
                        call.respond(exportData)
                    } catch (e: Exception) {
                        call.application.log.error("Export failed for user ${user.userId}", e)
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Export failed: ${e.message}"))
                    }
                }

                // =============================================================================
                // CALENDAR EVENT NOTE RELATIONSHIP ENDPOINTS (v4.2.0)
                // =============================================================================

                /**
                 * POST /api/v1/calendar/events/{eventId}/notes/{noteId}
                 * Link a note to a calendar event.
                 */
                post("/calendar/events/{eventId}/notes/{noteId}") {
                    val user = call.firebaseUser() ?: return@post call.respond(HttpStatusCode.Unauthorized, "User not authenticated")
                    val userId = user.userId
                    val eventId = call.parameters["eventId"] ?: return@post call.respond(HttpStatusCode.BadRequest, "eventId required")
                    val noteId = call.parameters["noteId"] ?: return@post call.respond(HttpStatusCode.BadRequest, "noteId required")

                    try {
                        calendarRepository?.linkNoteToEvent(userId, eventId, noteId)
                        call.respond(HttpStatusCode.OK, mapOf(
                            "success" to true,
                            "eventId" to eventId,
                            "noteId" to noteId
                        ))
                    } catch (e: IllegalAccessException) {
                        call.respond(HttpStatusCode.Forbidden, e.message ?: "Access denied")
                    } catch (e: Exception) {
                        call.application.log.error("Failed to link note to event", e)
                        call.respond(HttpStatusCode.InternalServerError, "Failed to link note")
                    }
                }

                /**
                 * DELETE /api/v1/calendar/events/{eventId}/notes/{noteId}
                 * Unlink a note from a calendar event.
                 */
                delete("/calendar/events/{eventId}/notes/{noteId}") {
                    val user = call.firebaseUser() ?: return@delete call.respond(HttpStatusCode.Unauthorized, "User not authenticated")
                    val userId = user.userId
                    val eventId = call.parameters["eventId"] ?: return@delete call.respond(HttpStatusCode.BadRequest, "eventId required")
                    val noteId = call.parameters["noteId"] ?: return@delete call.respond(HttpStatusCode.BadRequest, "noteId required")

                    try {
                        val success = calendarRepository?.unlinkNoteFromEvent(userId, eventId, noteId) ?: false
                        call.respond(HttpStatusCode.OK, mapOf(
                            "success" to success,
                            "eventId" to eventId,
                            "noteId" to noteId
                        ))
                    } catch (e: IllegalAccessException) {
                        call.respond(HttpStatusCode.Forbidden, e.message ?: "Access denied")
                    } catch (e: Exception) {
                        call.application.log.error("Failed to unlink note from event", e)
                        call.respond(HttpStatusCode.InternalServerError, "Failed to unlink note")
                    }
                }

                /**
                 * GET /api/v1/calendar/events/{eventId}/notes
                 * Get all notes linked to a calendar event.
                 */
                get("/calendar/events/{eventId}/notes") {
                    val user = call.firebaseUser() ?: return@get call.respond(HttpStatusCode.Unauthorized, "User not authenticated")
                    val userId = user.userId
                    val eventId = call.parameters["eventId"] ?: return@get call.respond(HttpStatusCode.BadRequest, "eventId required")

                    try {
                        val linkedNoteIds = calendarRepository?.getLinkedNotes(userId, eventId) ?: emptyList()
                        call.respond(HttpStatusCode.OK, mapOf(
                            "eventId" to eventId,
                            "linkedNoteIds" to linkedNoteIds,
                            "count" to linkedNoteIds.size
                        ))
                    } catch (e: IllegalAccessException) {
                        call.respond(HttpStatusCode.Forbidden, e.message ?: "Access denied")
                    } catch (e: Exception) {
                        call.application.log.error("Failed to get linked notes", e)
                        call.respond(HttpStatusCode.InternalServerError, "Failed to get linked notes")
                    }
                }
            }
        }
    }
}

@Serializable
data class ExportAllDataResponse(
    val notes: List<NoteInfo>,
    val events: List<CalendarEventInfo>,
    val timers: List<TimerInfo>,
    val exportedAt: Long
)
