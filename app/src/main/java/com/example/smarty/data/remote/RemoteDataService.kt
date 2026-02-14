package com.example.smarty.data.remote

import android.util.Log
import com.example.smarty.BuildConfig
import com.example.smarty.protocol.CalendarEventInfo
import com.example.smarty.protocol.NoteInfo
import com.example.smarty.protocol.TimerInfo
import com.google.firebase.auth.FirebaseAuth
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.tasks.await

class RemoteDataService(
    private val client: HttpClient,
    private val serverUrlProvider: () -> String,
    private val deviceIdProvider: () -> String
) {
    companion object {
        private const val TAG = "RemoteDataService"
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

    suspend fun fetchNotes(): List<NoteInfo> {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken() ?: return emptyList()

            val response = client.get("$baseUrl/api/v1/notes") {
                header(HttpHeaders.Authorization, "Bearer $token")
                header("X-Smarty-Version", BuildConfig.VERSION_NAME)
                header("X-Smarty-Device-Id", getDeviceId())
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

    suspend fun fetchCalendarEvents(): List<CalendarEventInfo> {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken() ?: return emptyList()

            val response = client.get("$baseUrl/api/v1/calendar") {
                header(HttpHeaders.Authorization, "Bearer $token")
                header("X-Smarty-Version", BuildConfig.VERSION_NAME)
                header("X-Smarty-Device-Id", getDeviceId())
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

    suspend fun fetchTimers(): List<TimerInfo> {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken() ?: return emptyList()

            val response = client.get("$baseUrl/api/v1/timers") {
                header(HttpHeaders.Authorization, "Bearer $token")
                header("X-Smarty-Version", BuildConfig.VERSION_NAME)
                header("X-Smarty-Device-Id", getDeviceId())
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
}
