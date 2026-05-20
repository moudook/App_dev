package com.example.smarty.data.remote

import android.util.Log
import com.example.smarty.BuildConfig
import com.example.smarty.data.model.LogReasoningRequest
import com.example.smarty.data.model.LogReasoningResponse
import com.example.smarty.data.model.ProgressiveDisclosureResponse
import com.example.smarty.data.model.ReasoningTimelineResponse
import com.example.smarty.data.model.ReasoningTracesResponse
import com.example.smarty.core.domain.model.Task
import com.example.smarty.core.domain.model.TaskCreateResponse
import com.example.smarty.core.domain.model.TaskResponse
import com.example.smarty.core.domain.model.TasksResponse
import com.example.smarty.protocol.*
import com.google.firebase.auth.FirebaseAuth
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class RemoteDataSource(
    private val client: HttpClient,
    private val serverUrlProvider: () -> String,
    private val deviceIdProvider: () -> String,
) {
    companion object {
        private const val TAG = "RemoteDataSource"
    }

    private suspend fun getFirebaseToken(): String? {
        return try {
            val user = FirebaseAuth.getInstance().currentUser
            if (user != null) {
                val tokenResult = user.getIdToken(false).await()
                tokenResult.token
            } else {
                Log.w(TAG, "No Firebase user signed in")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get Firebase token: ${e.message}")
            null
        }
    }

    private fun getDeviceId(): String {
        return try {
            deviceIdProvider()
        } catch (e: Exception) {
            "smarty-unknown"
        }
    }

    private fun HttpRequestBuilder.addAuthHeaders(token: String) {
        header(HttpHeaders.Authorization, "Bearer $token")
        header("X-Smarty-Version", BuildConfig.VERSION_NAME)
        header("X-Smarty-Device-Id", getDeviceId())
    }

    // ==================== SYNC API ====================

    /**
     * OPTIMIZED PULL with delta-sync support
     * @param lastSyncAt Timestamp of last sync (for delta-sync, null = full sync)
     * @param limit Maximum items to return per category
     */
    suspend fun pullAllData(
        lastSyncAt: Long? = null,
        limit: Int = 1000,
    ): SyncPullResponse? {
        Log.i(TAG, ">>> RemoteDataSource.pullAllData STARTING - lastSyncAt=$lastSyncAt")
        val serverUrl = serverUrlProvider()
        Log.d(TAG, "Server URL: $serverUrl")

        val token = getFirebaseToken()
        if (token == null) {
            Log.e(TAG, "Cannot pull data - no Firebase authentication token available")
            return null
        }
        Log.d(TAG, "Firebase token obtained successfully")

        return try {
            // Retry logic with exponential backoff
            val maxRetries = 3
            var lastException: Exception? = null

            for (attempt in 1..maxRetries) {
                try {
                    Log.d(TAG, "Pull attempt $attempt/$maxRetries to $serverUrl/api/v1/sync/pull")
                    val response =
                        client.post("$serverUrl/api/v1/sync/pull") {
                            addAuthHeaders(token)
                            contentType(ContentType.Application.Json)
                            // DELTA SYNC: Send lastSyncAt timestamp
                            setBody(
                                buildJsonObject {
                                    put("lastSyncAt", lastSyncAt ?: 0L)
                                    put("limit", limit)
                                },
                            )
                        }

                    if (response.status.isSuccess()) {
                        val pullResponse: SyncPullResponse = response.body()
                        Log.i(
                            TAG,
                            "Pull successful: ${pullResponse.notes.size} notes, ${pullResponse.sessions.size} sessions, ${pullResponse.events.size} events, ${pullResponse.generatedImages.size} images",
                        )
                        return pullResponse
                    } else {
                        val errorBody =
                            try {
                                response.body<String>()
                            } catch (e: Exception) {
                                "Unable to read error body"
                            }
                        Log.e(TAG, "Failed to pull data: ${response.status} (attempt $attempt) - $errorBody")
                        if (attempt == maxRetries) {
                            Log.e(TAG, "Pull failed after $maxRetries attempts")
                            return null
                        }
                    }
                } catch (e: Exception) {
                    lastException = e
                    Log.w(TAG, "Pull attempt $attempt failed: ${e.javaClass.simpleName}: ${e.message}")
                    if (attempt < maxRetries) {
                        val delayMs = (1000 * attempt).toLong() // Exponential backoff: 1s, 2s, 3s
                        Log.d(TAG, "Retrying after ${delayMs}ms delay...")
                        kotlinx.coroutines.delay(delayMs)
                    }
                }
            }

            Log.e(TAG, "Pull failed after all retries", lastException)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error pulling data: ${e.javaClass.simpleName}: ${e.message}", e)
            null
        }
    }

    suspend fun pushChanges(request: SyncPushRequest): SyncPushResponse? {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken() ?: return null

            val response =
                client.post("$baseUrl/api/v1/sync/push") {
                    addAuthHeaders(token)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }

            if (response.status.isSuccess()) {
                response.body()
            } else {
                Log.e(TAG, "Failed to push changes: ${response.status}")
                SyncPushResponse(success = false, errors = listOf("HTTP ${response.status}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error pushing changes: ${e.message}", e)
            SyncPushResponse(success = false, errors = listOf(e.message ?: "Unknown error"))
        }
    }

    suspend fun getSyncStatus(): SyncStatusResponse? {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken() ?: return null

            val response =
                client.get("$baseUrl/api/v1/sync/status") {
                    addAuthHeaders(token)
                }

            if (response.status.isSuccess()) {
                response.body()
            } else {
                Log.e(TAG, "Failed to get sync status: ${response.status}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting sync status: ${e.message}", e)
            null
        }
    }

    // ==================== NOTES API ====================

    suspend fun fetchNotes(): List<NoteInfo> {
        return try {
            val baseUrl = serverUrlProvider()
            Log.d(TAG, "Fetching notes from: $baseUrl/api/v1/notes")

            val token = getFirebaseToken()
            if (token == null) {
                Log.e(TAG, "Cannot fetch notes - no Firebase authentication token")
                return emptyList()
            }

            val response =
                client.get("$baseUrl/api/v1/notes") {
                    addAuthHeaders(token)
                }

            if (response.status.isSuccess()) {
                val notes: List<NoteInfo> = response.body()
                Log.i(TAG, "Successfully fetched ${notes.size} notes from server")
                return notes
            } else {
                val errorBody =
                    try {
                        response.body<String>()
                    } catch (e: Exception) {
                        "Unable to read error body"
                    }
                Log.e(TAG, "Failed to fetch notes: ${response.status} - $errorBody")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching notes: ${e.javaClass.simpleName}: ${e.message}", e)
            emptyList()
        }
    }

    suspend fun createNote(note: com.example.smarty.core.domain.model.Note): String? {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken() ?: return null

            val response =
                client.post("$baseUrl/api/v1/notes") {
                    addAuthHeaders(token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        mapOf(
                            "title" to note.title,
                            "content" to note.content,
                            "categoryId" to note.categoryId,
                            "summary" to note.summary,
                            "sourceUrl" to note.sourceUrl,
                            "type" to note.type.name,
                            "processingStatus" to note.processingStatus.name
                        )
                    )
                }

            if (response.status.isSuccess()) {
                val result: Map<String, String> = response.body()
                result["id"]
            } else {
                Log.e(TAG, "Failed to create note: ${response.status}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating note: ${e.message}", e)
            null
        }
    }

    suspend fun updateNote(note: com.example.smarty.core.domain.model.Note): Boolean {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken() ?: return false

            val response =
                client.put("$baseUrl/api/v1/notes/${note.id}") {
                    addAuthHeaders(token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        mapOf(
                            "title" to note.title,
                            "content" to note.content,
                            "categoryId" to note.categoryId,
                            "summary" to note.summary,
                            "sourceUrl" to note.sourceUrl,
                            "type" to note.type.name,
                            "processingStatus" to note.processingStatus.name
                        )
                    )
                }

            response.status.isSuccess()
        } catch (e: Exception) {
            Log.e(TAG, "Error updating note: ${e.message}", e)
            false
        }
    }

    suspend fun deleteNote(id: String): Boolean {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken() ?: return false

            val response =
                client.delete("$baseUrl/api/v1/notes/$id") {
                    addAuthHeaders(token)
                }

            response.status.isSuccess()
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting note: ${e.message}", e)
            false
        }
    }

    // ==================== CALENDAR API ====================

    suspend fun fetchCalendarEvents(): List<CalendarEventInfo> {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken() ?: return emptyList()

            val response =
                client.get("$baseUrl/api/v1/calendar") {
                    addAuthHeaders(token)
                }

            if (response.status.isSuccess()) {
                response.body()
            } else {
                Log.e(TAG, "Failed to fetch calendar events: ${response.status}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching calendar events: ${e.message}", e)
            emptyList()
        }
    }

    suspend fun createCalendarEvent(
        title: String,
        startTime: Long,
        endTime: Long,
        description: String?,
        reminderMinutes: Int,
    ): String? {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken() ?: return null

            val response =
                client.post("$baseUrl/api/v1/calendar") {
                    addAuthHeaders(token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        mapOf(
                            "title" to title,
                            "startTime" to startTime,
                            "endTime" to endTime,
                            "description" to description,
                            "reminderMinutes" to reminderMinutes,
                        ),
                    )
                }

            if (response.status.isSuccess()) {
                val result: Map<String, String> = response.body()
                result["id"]
            } else {
                Log.e(TAG, "Failed to create calendar event: ${response.status}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating calendar event: ${e.message}", e)
            null
        }
    }

    suspend fun deleteCalendarEvent(id: String): Boolean {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken() ?: return false

            val response =
                client.delete("$baseUrl/api/v1/calendar/$id") {
                    addAuthHeaders(token)
                }

            response.status.isSuccess()
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting calendar event: ${e.message}", e)
            false
        }
    }

    // ==================== TIMERS API ====================

    suspend fun fetchTimers(): List<TimerInfo> {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken() ?: return emptyList()

            val response =
                client.get("$baseUrl/api/v1/timers") {
                    addAuthHeaders(token)
                }

            if (response.status.isSuccess()) {
                response.body()
            } else {
                Log.e(TAG, "Failed to fetch timers: ${response.status}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching timers: ${e.message}", e)
            emptyList()
        }
    }

    // ==================== CHAT SESSIONS API ====================

    suspend fun fetchChatSessions(): List<Map<String, Any?>> {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken() ?: return emptyList()

            val response =
                client.get("$baseUrl/api/v1/chat/sessions") {
                    addAuthHeaders(token)
                }

            if (response.status.isSuccess()) {
                response.body()
            } else {
                Log.e(TAG, "Failed to fetch chat sessions: ${response.status}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching chat sessions: ${e.message}", e)
            emptyList()
        }
    }

    suspend fun createChatSession(title: String?): String? {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken() ?: return null

            val response =
                client.post("$baseUrl/api/v1/chat/sessions") {
                    addAuthHeaders(token)
                    contentType(ContentType.Application.Json)
                    setBody(mapOf("title" to title))
                }

            if (response.status.isSuccess()) {
                val result: Map<String, String> = response.body()
                result["id"]
            } else {
                Log.e(TAG, "Failed to create chat session: ${response.status}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating chat session: ${e.message}", e)
            null
        }
    }

    /**
     * Save a chat message to the server.
     *
     * @param sessionId Chat session ID
     * @param role Message role (USER, ASSISTANT, SYSTEM)
     * @param content Message content
     * @param thinking Optional AI thinking/reasoning content (for collapsible display)
     * @return true if successful
     */
    suspend fun saveChatMessage(
        sessionId: String,
        role: String,
        content: String,
        thinking: String? = null, // ✅ Added thinking parameter
    ): Boolean {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken() ?: return false

            val response =
                client.post("$baseUrl/api/v1/chat/messages") {
                    addAuthHeaders(token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        mapOf(
                            "sessionId" to sessionId,
                            "role" to role,
                            "content" to content,
                            "thinking" to thinking, // ✅ Send thinking to server
                        ),
                    )
                }

            response.status.isSuccess()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving chat message: ${e.message}", e)
            false
        }
    }

    suspend fun deleteChatSession(sessionId: String): Boolean {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken() ?: return false

            val response =
                client.delete("$baseUrl/api/v1/chat/sessions/$sessionId") {
                    addAuthHeaders(token)
                }

            response.status.isSuccess()
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting chat session: ${e.message}", e)
            false
        }
    }

    // ==================== REASONING API ====================

    /**
     * Get reasoning timeline for a session
     * @param sessionId The chat session ID
     * @return ReasoningTimelineResponse or null if failed
     */
    suspend fun getReasoningTimeline(sessionId: String): ReasoningTimelineResponse? {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken() ?: return null

            val response =
                client.get("$baseUrl/api/reasoning/session/$sessionId") {
                    addAuthHeaders(token)
                }

            if (response.status.isSuccess()) {
                response.body()
            } else {
                Log.w(TAG, "Failed to get reasoning timeline: ${response.status}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting reasoning timeline: ${e.message}", e)
            null
        }
    }

    /**
     * Get reasoning traces for a session (with optional message filter)
     * @param sessionId The chat session ID
     * @param messageId Optional message ID to filter traces
     * @return ReasoningTracesResponse or null if failed
     */
    suspend fun getReasoningTraces(
        sessionId: String,
        messageId: String? = null,
    ): ReasoningTracesResponse? {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken() ?: return null

            val url =
                buildString {
                    append("$baseUrl/api/reasoning/session/$sessionId/traces")
                    if (messageId != null) {
                        append("?messageId=$messageId")
                    }
                }

            val response =
                client.get(url) {
                    addAuthHeaders(token)
                }

            if (response.status.isSuccess()) {
                response.body()
            } else {
                Log.w(TAG, "Failed to get reasoning traces: ${response.status}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting reasoning traces: ${e.message}", e)
            null
        }
    }

    /**
     * Get progressive disclosure levels for UI
     * @param sessionId The chat session ID
     * @return ProgressiveDisclosureResponse or null if failed
     */
    suspend fun getProgressiveDisclosure(sessionId: String): ProgressiveDisclosureResponse? {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken() ?: return null

            val response =
                client.get("$baseUrl/api/reasoning/session/$sessionId/disclosure") {
                    addAuthHeaders(token)
                }

            if (response.status.isSuccess()) {
                response.body()
            } else {
                Log.w(TAG, "Failed to get progressive disclosure: ${response.status}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting progressive disclosure: ${e.message}", e)
            null
        }
    }

    /**
     * Log a reasoning step
     * @param request The reasoning step data
     * @return LogReasoningResponse or null if failed
     */
    suspend fun logReasoningStep(request: LogReasoningRequest): LogReasoningResponse? {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken() ?: return null

            val response =
                client.post("$baseUrl/api/reasoning/log") {
                    addAuthHeaders(token)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }

            if (response.status.isSuccess()) {
                response.body()
            } else {
                Log.w(TAG, "Failed to log reasoning step: ${response.status}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error logging reasoning step: ${e.message}", e)
            null
        }
    }

    // ==================== TASKS API ====================

    suspend fun getTasks(status: String? = null): TasksResponse? {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken() ?: return null

            val url = if (status != null) "$baseUrl/api/tasks?status=$status" else "$baseUrl/api/tasks"
            val response = client.get(url) {
                addAuthHeaders(token)
            }

            if (response.status.isSuccess()) {
                response.body()
            } else {
                Log.w(TAG, "Failed to get tasks: ${response.status}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting tasks: ${e.message}", e)
            null
        }
    }

    suspend fun getTask(taskId: String): TaskResponse? {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken() ?: return null

            val response = client.get("$baseUrl/api/tasks/$taskId") {
                addAuthHeaders(token)
            }

            if (response.status.isSuccess()) {
                response.body()
            } else {
                Log.w(TAG, "Failed to get task: ${response.status}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting task: ${e.message}", e)
            null
        }
    }

    suspend fun createTask(task: Task): TaskCreateResponse? {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken() ?: return null

            val response = client.post("$baseUrl/api/tasks") {
                addAuthHeaders(token)
                contentType(ContentType.Application.Json)
                setBody(task)
            }

            if (response.status.isSuccess()) {
                response.body()
            } else {
                Log.w(TAG, "Failed to create task: ${response.status}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating task: ${e.message}", e)
            null
        }
    }

    suspend fun updateTaskStatus(taskId: String, status: String): TaskResponse? {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken() ?: return null

            val response = client.patch("$baseUrl/api/tasks/$taskId/status") {
                addAuthHeaders(token)
                contentType(ContentType.Application.Json)
                setBody(mapOf("status" to status))
            }

            if (response.status.isSuccess()) {
                response.body()
            } else {
                Log.w(TAG, "Failed to update task status: ${response.status}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating task status: ${e.message}", e)
            null
        }
    }

    suspend fun deleteTask(taskId: String): TaskResponse? {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken() ?: return null

            val response = client.delete("$baseUrl/api/tasks/$taskId") {
                addAuthHeaders(token)
            }

            if (response.status.isSuccess()) {
                response.body()
            } else {
                Log.w(TAG, "Failed to delete task: ${response.status}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting task: ${e.message}", e)
            null
        }
    }

    // ==================== TAGS API ====================

    suspend fun getTags(): com.example.smarty.core.domain.model.TagsResponse? {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken() ?: return null

            val response = client.get("$baseUrl/api/tags") {
                addAuthHeaders(token)
            }

            if (response.status.isSuccess()) {
                response.body()
            } else {
                Log.w(TAG, "Failed to get tags: ${response.status}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting tags: ${e.message}", e)
            null
        }
    }

    suspend fun createTag(request: com.example.smarty.core.domain.model.TagCreateRequest): com.example.smarty.core.domain.model.TagCreateResponse? {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken() ?: return null

            val response = client.post("$baseUrl/api/tags") {
                addAuthHeaders(token)
                contentType(ContentType.Application.Json)
                setBody(request)
            }

            if (response.status.isSuccess()) {
                response.body()
            } else {
                Log.w(TAG, "Failed to create tag: ${response.status}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating tag: ${e.message}", e)
            null
        }
    }

    suspend fun updateTag(tag: com.example.smarty.core.domain.model.Tag): com.example.smarty.core.domain.model.TagResponse? {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken() ?: return null

            val response = client.put("$baseUrl/api/tags/${tag.id}") {
                addAuthHeaders(token)
                contentType(ContentType.Application.Json)
                setBody(tag)
            }

            if (response.status.isSuccess()) {
                response.body()
            } else {
                Log.w(TAG, "Failed to update tag: ${response.status}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating tag: ${e.message}", e)
            null
        }
    }

    suspend fun deleteTag(tagId: String): com.example.smarty.core.domain.model.TagResponse? {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken() ?: return null

            val response = client.delete("$baseUrl/api/tags/$tagId") {
                addAuthHeaders(token)
            }

            if (response.status.isSuccess()) {
                response.body()
            } else {
                Log.w(TAG, "Failed to delete tag: ${response.status}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting tag: ${e.message}", e)
            null
        }
    }

    suspend fun getNotesForTag(tagId: String): com.example.smarty.core.domain.model.TagNotesResponse? {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken() ?: return null

            val response = client.get("$baseUrl/api/tags/$tagId/notes") {
                addAuthHeaders(token)
            }

            if (response.status.isSuccess()) {
                response.body()
            } else {
                Log.w(TAG, "Failed to get notes for tag: ${response.status}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting notes for tag: ${e.message}", e)
            null
        }
    }

    suspend fun assignTagToNote(tagId: String, noteId: String): com.example.smarty.core.domain.model.TagResponse? {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken() ?: return null

            val response = client.post("$baseUrl/api/tags/$tagId/notes/$noteId") {
                addAuthHeaders(token)
            }

            if (response.status.isSuccess()) {
                response.body()
            } else {
                Log.w(TAG, "Failed to assign tag to note: ${response.status}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error assigning tag to note: ${e.message}", e)
            null
        }
    }

    suspend fun removeTagFromNote(tagId: String, noteId: String): com.example.smarty.core.domain.model.TagResponse? {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken() ?: return null

            val response = client.delete("$baseUrl/api/tags/$tagId/notes/$noteId") {
                addAuthHeaders(token)
            }

            if (response.status.isSuccess()) {
                response.body()
            } else {
                Log.w(TAG, "Failed to remove tag from note: ${response.status}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing tag from note: ${e.message}", e)
            null
        }
    }

    // ==================== CHAT FOLDERS API ====================

    suspend fun getChatFolders(): com.example.smarty.core.domain.model.ChatFoldersResponse? {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken() ?: return null
            val response = client.get("$baseUrl/api/chat/folders") {
                addAuthHeaders(token)
            }
            if (response.status.isSuccess()) {
                response.body()
            } else {
                Log.w(TAG, "Failed to get chat folders: ${response.status}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting chat folders: ${e.message}", e)
            null
        }
    }

    suspend fun createChatFolder(request: com.example.smarty.core.domain.model.ChatFolderCreateRequest): com.example.smarty.core.domain.model.ChatFolderCreateResponse? {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken() ?: return null
            val response = client.post("$baseUrl/api/chat/folders") {
                addAuthHeaders(token)
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            if (response.status.isSuccess()) {
                response.body()
            } else {
                Log.w(TAG, "Failed to create chat folder: ${response.status}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating chat folder: ${e.message}", e)
            null
        }
    }

    suspend fun updateChatFolder(folder: com.example.smarty.core.domain.model.ChatFolder): com.example.smarty.core.domain.model.ChatFolderResponse? {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken() ?: return null
            val response = client.put("$baseUrl/api/chat/folders/${folder.id}") {
                addAuthHeaders(token)
                contentType(ContentType.Application.Json)
                setBody(folder)
            }
            if (response.status.isSuccess()) {
                response.body()
            } else {
                Log.w(TAG, "Failed to update chat folder: ${response.status}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating chat folder: ${e.message}", e)
            null
        }
    }

    suspend fun deleteChatFolder(folderId: String): com.example.smarty.core.domain.model.ChatFolderResponse? {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken() ?: return null
            val response = client.delete("$baseUrl/api/chat/folders/$folderId") {
                addAuthHeaders(token)
            }
            if (response.status.isSuccess()) {
                response.body()
            } else {
                Log.w(TAG, "Failed to delete chat folder: ${response.status}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting chat folder: ${e.message}", e)
            null
        }
    }
}
