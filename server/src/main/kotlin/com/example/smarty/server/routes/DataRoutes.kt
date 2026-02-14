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
import com.example.smarty.server.plugins.FirebaseUserPrincipal
import com.example.smarty.server.plugins.firebaseUser
import kotlinx.serialization.Serializable
import java.util.UUID

// DTOs for Create/Update requests
@Serializable
data class CreateNoteRequest(val title: String, val content: String, val category: String? = null)

@Serializable
data class UpdateNoteRequest(val title: String? = null, val content: String? = null, val category: String? = null)

@Serializable
data class CreateEventRequest(val title: String, val startTime: Long, val endTime: Long, val description: String? = null, val reminderMinutes: Int = 15)

@Serializable
data class CreateTimerRequest(val name: String, val durationMs: Long, val isAlarm: Boolean = false)

fun Application.configureDataRoutes() {
    val dataSource = DatabaseFactory.getDataSource()
    val noteRepository = dataSource?.let { NoteRepository(it) }
    val calendarRepository = dataSource?.let { CalendarRepository(it) }
    val timerRepository = dataSource?.let { TimerRepository(it) }

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
                            val id = noteRepository.create(user.userId, request.title, request.content, request.category)
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
                                val updated = noteRepository.update(user.userId, id, request.title, request.content, request.category)
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
            }
        }
    }
}
