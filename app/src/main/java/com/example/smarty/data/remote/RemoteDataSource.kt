package com.example.smarty.data.remote

import android.util.Log
import com.example.smarty.BuildConfig
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

            val response = client.post("$baseUrl/api/v1/sync/pull") {
                addAuthHeaders(token)
            }

            if (response.status.isSuccess()) {
                response.body()
            } else {
                Log.e(TAG, "Failed to pull data: ${response.status}")
                null
            }
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

    suspend fun saveChatMessage(sessionId: String, role: String, content: String): Boolean {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken() ?: return false

            val response = client.post("$baseUrl/api/v1/chat/messages") {
                addAuthHeaders(token)
                contentType(ContentType.Application.Json)
                setBody(mapOf("sessionId" to sessionId, "role" to role, "content" to content))
            }

            response.status.isSuccess()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving chat message: ${e.message}", e)
            false
        }
    }
}
