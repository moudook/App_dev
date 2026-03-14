package com.example.smarty.data.remote

import android.util.Log
import com.example.smarty.BuildConfig
import com.example.smarty.data.model.LogReasoningRequest
import com.example.smarty.data.model.LogReasoningResponse
import com.example.smarty.data.model.ProgressiveDisclosureResponse
import com.example.smarty.data.model.ReasoningTimelineResponse
import com.example.smarty.data.model.ReasoningTracesResponse
import com.example.smarty.protocol.*
import com.google.firebase.auth.FirebaseAuth
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.tasks.await

class RemoteDataSource(
    private val client: HttpClient,
    private val serverUrlProvider: () -> String,
    private val deviceIdProvider: () -> String
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

    suspend fun pullAllData(): SyncPullResponse? {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken() ?: return null

            // Retry logic with exponential backoff
            val maxRetries = 3
            var lastException: Exception? = null
            
            for (attempt in 1..maxRetries) {
                try {
                    val response = client.post("$baseUrl/api/v1/sync/pull") {
                        addAuthHeaders(token)
                    }

                    if (response.status.isSuccess()) {
                        Log.i(TAG, "Pull successful on attempt $attempt")
                        return response.body()
                    } else {
                        Log.e(TAG, "Failed to pull data: ${response.status} (attempt $attempt)")
                        if (attempt == maxRetries) {
                            Log.e(TAG, "Pull failed after $attempt attempts")
                            return null
                        }
                    }
                } catch (e: Exception) {
                    lastException = e
                    Log.w(TAG, "Pull attempt $attempt failed: ${e.message}")
                    if (attempt < maxRetries) {
                        val delayMs = (1000 * attempt).toLong() // Exponential backoff: 1s, 2s, 3s
                        kotlinx.coroutines.delay(delayMs)
                    }
                }
            }
            
            Log.e(TAG, "Pull failed after all retries", lastException)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error pulling data: ${e.message}", e)
            null
        }
    }

    suspend fun pushChanges(request: SyncPushRequest): SyncPushResponse? {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken() ?: return null

            val response = client.post("$baseUrl/api/v1/sync/push") {
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

            val response = client.get("$baseUrl/api/v1/sync/status") {
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
            val token = getFirebaseToken() ?: return emptyList()

            val response = client.get("$baseUrl/api/v1/notes") {
                addAuthHeaders(token)
            }

            if (response.status.isSuccess()) {
                response.body()
            } else {
                Log.e(TAG, "Failed to fetch notes: ${response.status}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching notes: ${e.message}", e)
            emptyList()
        }
    }

    suspend fun createNote(title: String, content: String, category: String?): String? {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken() ?: return null

            val response = client.post("$baseUrl/api/v1/notes") {
                addAuthHeaders(token)
                contentType(ContentType.Application.Json)
                setBody(mapOf("title" to title, "content" to content, "category" to category))
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

    suspend fun updateNote(id: String, title: String?, content: String?, category: String?): Boolean {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken() ?: return false

            val response = client.put("$baseUrl/api/v1/notes/$id") {
                addAuthHeaders(token)
                contentType(ContentType.Application.Json)
                setBody(mapOf("title" to title, "content" to content, "category" to category))
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

            val response = client.delete("$baseUrl/api/v1/notes/$id") {
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

            val response = client.get("$baseUrl/api/v1/calendar") {
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
        reminderMinutes: Int
    ): String? {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken() ?: return null

            val response = client.post("$baseUrl/api/v1/calendar") {
                addAuthHeaders(token)
                contentType(ContentType.Application.Json)
                setBody(mapOf(
                    "title" to title,
                    "startTime" to startTime,
                    "endTime" to endTime,
                    "description" to description,
                    "reminderMinutes" to reminderMinutes
                ))
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

            val response = client.delete("$baseUrl/api/v1/calendar/$id") {
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

            val response = client.get("$baseUrl/api/v1/timers") {
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

            val response = client.get("$baseUrl/api/v1/chat/sessions") {
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

            val response = client.post("$baseUrl/api/v1/chat/sessions") {
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
        thinking: String? = null  // ✅ Added thinking parameter
    ): Boolean {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken() ?: return false

            val response = client.post("$baseUrl/api/v1/chat/messages") {
                addAuthHeaders(token)
                contentType(ContentType.Application.Json)
                setBody(
                    mapOf(
                        "sessionId" to sessionId,
                        "role" to role,
                        "content" to content,
                        "thinking" to thinking  // ✅ Send thinking to server
                    )
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

            val response = client.delete("$baseUrl/api/v1/chat/sessions/$sessionId") {
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

            val response = client.get("$baseUrl/api/reasoning/session/$sessionId") {
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
        messageId: String? = null
    ): ReasoningTracesResponse? {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken() ?: return null

            val url = buildString {
                append("$baseUrl/api/reasoning/session/$sessionId/traces")
                if (messageId != null) {
                    append("?messageId=$messageId")
                }
            }

            val response = client.get(url) {
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

            val response = client.get("$baseUrl/api/reasoning/session/$sessionId/disclosure") {
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

            val response = client.post("$baseUrl/api/reasoning/log") {
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
}
