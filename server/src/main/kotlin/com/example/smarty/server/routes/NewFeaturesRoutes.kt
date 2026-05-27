package com.example.smarty.server.routes

import com.example.smarty.server.data.ChatFolder
import com.example.smarty.server.data.ChatFolderRepository
import com.example.smarty.server.data.Notification
import com.example.smarty.server.data.NotificationRepository
import com.example.smarty.server.data.Tag
import com.example.smarty.server.data.TagRepository
import com.example.smarty.server.data.Task
import com.example.smarty.server.data.TaskRepository
import com.example.smarty.server.plugins.FirebaseUserPrincipal
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.principal
import io.ktor.server.request.*
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

/**
 * Routes for v6.0.0 new features:
 * - Tasks (/api/tasks)
 * - Tags (/api/tags)
 * - Notifications (/api/notifications)
 * - Chat Folders (/api/chat/folders)
 */
fun Application.configureNewFeaturesRoutes(
    taskRepository: TaskRepository,
    tagRepository: TagRepository,
    notificationRepository: NotificationRepository,
    chatFolderRepository: ChatFolderRepository,
) {
    routing {
        // ============ TASKS ============
        route("/api/tasks") {
            get {
                val principal = call.principal<FirebaseUserPrincipal>() ?: return@get call.respond(HttpStatusCode.Unauthorized)
                val status = call.request.queryParameters["status"]
                val tasks = taskRepository.getTasksForUser(principal.userId, status)
                call.respond(TasksResponse(success = true, tasks = tasks))
            }

            get("/{taskId}") {
                val taskId = call.parameters["taskId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val task = taskRepository.getTaskById(taskId)
                if (task != null) {
                    call.respond(TaskResponse(success = true, task = task))
                } else {
                    call.respond(HttpStatusCode.NotFound)
                }
            }

            post {
                val principal = call.principal<FirebaseUserPrincipal>() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val task = call.receive<Task>()
                val id = taskRepository.createTask(task.copy(userId = principal.userId))
                call.respond(TaskCreateResponse(success = true, id = id))
            }

            patch("/{taskId}/status") {
                val taskId = call.parameters["taskId"] ?: return@patch call.respond(HttpStatusCode.BadRequest)
                val request = call.receive<UpdateStatusRequest>()
                if (taskRepository.updateTaskStatus(taskId, request.status)) {
                    call.respond(TaskResponse(success = true, message = "Updated"))
                } else {
                    call.respond(HttpStatusCode.NotFound)
                }
            }

            delete("/{taskId}") {
                val taskId = call.parameters["taskId"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                if (taskRepository.deleteTask(taskId)) {
                    call.respond(TaskResponse(success = true))
                } else {
                    call.respond(HttpStatusCode.NotFound)
                }
            }
        }

        // ============ TAGS ============
        route("/api/tags") {
            get {
                val principal = call.principal<FirebaseUserPrincipal>() ?: return@get call.respond(HttpStatusCode.Unauthorized)
                val tags = tagRepository.getTagsForUser(principal.userId)
                call.respond(TagsResponse(success = true, tags = tags))
            }

            post {
                val principal = call.principal<FirebaseUserPrincipal>() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val tag = call.receive<Tag>()
                val id = tagRepository.createTag(tag.copy(userId = principal.userId))
                call.respond(TagCreateResponse(success = true, id = id))
            }

            put("/{tagId}") {
                val principal = call.principal<FirebaseUserPrincipal>() ?: return@put call.respond(HttpStatusCode.Unauthorized)
                val tagId = call.parameters["tagId"] ?: return@put call.respond(HttpStatusCode.BadRequest)
                val tag = call.receive<Tag>().copy(id = tagId)
                if (tagRepository.updateTag(tag)) {
                    call.respond(TagResponse(success = true))
                } else {
                    call.respond(HttpStatusCode.NotFound)
                }
            }

            delete("/{tagId}") {
                val tagId = call.parameters["tagId"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                if (tagRepository.deleteTag(tagId)) {
                    call.respond(TagResponse(success = true))
                } else {
                    call.respond(HttpStatusCode.NotFound)
                }
            }

            get("/{tagId}/notes") {
                val principal = call.principal<FirebaseUserPrincipal>() ?: return@get call.respond(HttpStatusCode.Unauthorized)
                val tagId = call.parameters["tagId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val notes = tagRepository.getNotesForTag(tagId)
                call.respond(TagNotesResponse(success = true, notes = notes))
            }

            post("/{tagId}/notes/{noteId}") {
                val principal = call.principal<FirebaseUserPrincipal>() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val tagId = call.parameters["tagId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val noteId = call.parameters["noteId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                if (tagRepository.addTagToNote(noteId, tagId, principal.userId)) {
                    call.respond(TagResponse(success = true, message = "Tag assigned to note"))
                } else {
                    call.respond(HttpStatusCode.InternalServerError)
                }
            }

            delete("/{tagId}/notes/{noteId}") {
                val principal = call.principal<FirebaseUserPrincipal>() ?: return@delete call.respond(HttpStatusCode.Unauthorized)
                val tagId = call.parameters["tagId"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                val noteId = call.parameters["noteId"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                if (tagRepository.removeTagFromNote(noteId, tagId)) {
                    call.respond(TagResponse(success = true, message = "Tag removed from note"))
                } else {
                    call.respond(HttpStatusCode.InternalServerError)
                }
            }
        }

        // ============ NOTIFICATIONS ============
        route("/api/notifications") {
            get("/unread") {
                val principal = call.principal<FirebaseUserPrincipal>() ?: return@get call.respond(HttpStatusCode.Unauthorized)
                val notifications = notificationRepository.getUnreadNotifications(principal.userId)
                call.respond(NotificationsResponse(success = true, notifications = notifications))
            }

            get {
                val principal = call.principal<FirebaseUserPrincipal>() ?: return@get call.respond(HttpStatusCode.Unauthorized)
                val notifications = notificationRepository.getNotificationsForUser(principal.userId)
                call.respond(NotificationsResponse(success = true, notifications = notifications))
            }

            post("/{notificationId}/read") {
                val notificationId = call.parameters["notificationId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                if (notificationRepository.markAsRead(notificationId)) {
                    call.respond(NotificationResponse(success = true))
                } else {
                    call.respond(HttpStatusCode.NotFound)
                }
            }

            post("/read-all") {
                val principal = call.principal<FirebaseUserPrincipal>() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                notificationRepository.markAllAsRead(principal.userId)
                call.respond(NotificationResponse(success = true))
            }

            delete("/{notificationId}") {
                val notificationId = call.parameters["notificationId"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                if (notificationRepository.deleteNotification(notificationId)) {
                    call.respond(NotificationResponse(success = true))
                } else {
                    call.respond(HttpStatusCode.NotFound)
                }
            }
        }

        // ============ CHAT FOLDERS ============
        route("/api/chat/folders") {
            get {
                val principal = call.principal<FirebaseUserPrincipal>() ?: return@get call.respond(HttpStatusCode.Unauthorized)
                val folders = chatFolderRepository.getFoldersForUser(principal.userId)
                call.respond(ChatFoldersResponse(success = true, folders = folders))
            }

            post {
                val principal = call.principal<FirebaseUserPrincipal>() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val folder = call.receive<ChatFolder>()
                val id = chatFolderRepository.createFolder(folder.copy(userId = principal.userId))
                call.respond(ChatFolderCreateResponse(success = true, id = id))
            }

            put("/{folderId}") {
                val folderId = call.parameters["folderId"] ?: return@put call.respond(HttpStatusCode.BadRequest)
                val folder = call.receive<ChatFolder>().copy(id = folderId)
                if (chatFolderRepository.updateFolder(folder)) {
                    call.respond(ChatFolderResponse(success = true))
                } else {
                    call.respond(HttpStatusCode.NotFound)
                }
            }

            delete("/{folderId}") {
                val folderId = call.parameters["folderId"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                if (chatFolderRepository.deleteFolder(folderId)) {
                    call.respond(ChatFolderResponse(success = true))
                } else {
                    call.respond(HttpStatusCode.NotFound)
                }
            }
        }
    } // routing
}

// ============ RESPONSE MODELS ============
@Serializable data class TasksResponse(
    val success: Boolean,
    val tasks: List<Task> = emptyList(),
    val message: String? = null,
)

@Serializable data class TaskResponse(
    val success: Boolean,
    val task: Task? = null,
    val message: String? = null,
)

@Serializable data class TaskCreateResponse(
    val success: Boolean,
    val id: String,
    val message: String? = null,
)

@Serializable data class UpdateStatusRequest(
    val status: String,
)

@Serializable data class TagsResponse(
    val success: Boolean,
    val tags: List<Tag> = emptyList(),
    val message: String? = null,
)

@Serializable data class TagResponse(
    val success: Boolean,
    val message: String? = null,
)

@Serializable data class TagCreateResponse(
    val success: Boolean,
    val id: String,
    val message: String? = null,
)

@Serializable data class TagNotesResponse(
    val success: Boolean,
    val notes: List<com.example.smarty.server.data.NoteForTag> = emptyList(),
    val message: String? = null,
)

@Serializable data class NotificationsResponse(
    val success: Boolean,
    val notifications: List<Notification> = emptyList(),
    val message: String? = null,
)

@Serializable data class NotificationResponse(
    val success: Boolean,
    val message: String? = null,
)

@Serializable data class ChatFoldersResponse(
    val success: Boolean,
    val folders: List<ChatFolder> = emptyList(),
    val message: String? = null,
)

@Serializable data class ChatFolderResponse(
    val success: Boolean,
    val message: String? = null,
)

@Serializable data class ChatFolderCreateResponse(
    val success: Boolean,
    val id: String,
    val message: String? = null,
)
