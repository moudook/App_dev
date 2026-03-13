package com.example.smarty.data.sync

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.smarty.core.domain.model.CalendarEvent
import com.example.smarty.core.domain.model.ChatMessage
import com.example.smarty.core.domain.model.ChatRole
import com.example.smarty.core.domain.model.Note
import com.example.smarty.data.local.*
import com.example.smarty.data.remote.RemoteDataSource
import com.example.smarty.protocol.*
import kotlinx.coroutines.flow.first

class MigrationManager(
    private val context: Context,
    private val remoteDataSource: RemoteDataSource,
    private val noteDao: NoteDao,
    private val calendarDao: CalendarDao,
    private val chatDao: ChatDao,
    private val syncQueueDao: SyncQueueDao
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    suspend fun migrateIfNeeded(): MigrationResult {
        if (hasMigrated()) {
            Log.i(TAG, "Migration already completed, skipping")
            return MigrationResult.AlreadyMigrated
        }

        Log.i(TAG, "Starting one-time migration to server...")

        return try {
            val notes = noteDao.getAllNotesOnce()
            val sessions = chatDao.getAllSessionsOnce()
            val events = calendarDao.getAllEventsOnce()

            Log.i(TAG, "Found ${notes.size} notes, ${sessions.size} sessions, ${events.size} events to migrate")

            val noteItems = notes.map { note ->
                NotePushItem(
                    id = note.id,
                    title = note.title,
                    content = note.content,
                    categoryId = null, // TODO: resolve category name to ID
                    updatedAt = note.updatedAt
                )
            }

            val sessionItems = sessions.map { session ->
                val messages = chatDao.getMessagesForSessionOnce(session.id).map { msg ->
                    MessagePushItem(
                        id = msg.id,
                        role = msg.role,
                        content = msg.content,
                        createdAt = msg.timestamp
                    )
                }
                SessionPushItem(
                    id = session.id,
                    title = session.title,
                    createdAt = session.createdAt,
                    messages = messages
                )
            }

            val eventItems = events.map { event ->
                EventPushItem(
                    id = event.id,
                    title = event.title,
                    startTime = event.startTime,
                    endTime = event.endTime,
                    description = event.description,
                    reminderMinutes = event.reminderMinutes ?: 15
                )
            }

            if (noteItems.isEmpty() && sessionItems.isEmpty() && eventItems.isEmpty()) {
                markMigrationComplete()
                Log.i(TAG, "No data to migrate, marking complete")
                return MigrationResult.Success(0, 0, 0)
            }

            val request = SyncPushRequest(
                notes = noteItems.ifEmpty { null },
                sessions = sessionItems.ifEmpty { null },
                events = eventItems.ifEmpty { null }
            )

            val response = remoteDataSource.pushChanges(request)

            if (response == null) {
                Log.e(TAG, "Migration failed: no response from server")
                return MigrationResult.Error("No response from server")
            }

            if (!response.success) {
                Log.e(TAG, "Migration failed: ${response.errors}")
                return MigrationResult.Error(response.errors.joinToString())
            }

            markMigrationComplete()

            Log.i(TAG, "Migration complete: ${notes.size} notes, ${sessions.size} sessions, ${events.size} events")
            MigrationResult.Success(notes.size, sessions.size, events.size)
        } catch (e: Exception) {
            Log.e(TAG, "Migration failed", e)
            MigrationResult.Error(e.message ?: "Unknown error")
        }
    }

    private fun hasMigrated(): Boolean = prefs.getBoolean(KEY_MIGRATED, false)

    private fun markMigrationComplete() {
        prefs.edit()
            .putBoolean(KEY_MIGRATED, true)
            .putLong(KEY_MIGRATION_TIME, System.currentTimeMillis())
            .apply()
    }

    fun getMigrationTime(): Long? {
        val time = prefs.getLong(KEY_MIGRATION_TIME, 0)
        return if (time > 0) time else null
    }

    companion object {
        private const val TAG = "MigrationManager"
        private const val PREFS_NAME = "migration_prefs"
        private const val KEY_MIGRATED = "has_migrated_to_server"
        private const val KEY_MIGRATION_TIME = "migration_time"
    }
}

sealed class MigrationResult {
    data class Success(val notes: Int, val sessions: Int, val events: Int) : MigrationResult()
    object AlreadyMigrated : MigrationResult()
    data class Error(val message: String) : MigrationResult()
}
