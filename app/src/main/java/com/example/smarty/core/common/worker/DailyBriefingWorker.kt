package com.example.smarty.core.common.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import com.example.smarty.core.common.util.NotificationHelper
import com.example.smarty.data.local.SmartyDatabase
import com.example.smarty.di.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Generates a daily morning briefing based on 3 days of user activity.
 * Reads notes, calendar events, and chat history to understand:
 * - Current priorities and pending tasks
 * - Upcoming deadlines and events
 * - Projects that may be stuck
 * - Personal interests and patterns
 *
 * Then uses the AI to generate a personalized morning greeting with:
 * - Priority reminders
 * - Encouragement and motivation
 * - Suggestions for stuck tasks
 * - Memory updates for better personalization
 */
class DailyBriefingWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    companion object {
        private const val TAG = "DailyBriefing"
        private const val WORK_NAME = "daily_briefing"
        private const val LOOKBACK_DAYS = 3

        fun schedule(context: Context) {
            // Schedule for 7:30 AM daily
            val calendar =
                Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 7)
                    set(Calendar.MINUTE, 30)
                    set(Calendar.SECOND, 0)
                }

            var delay = calendar.timeInMillis - System.currentTimeMillis()
            if (delay < 0) delay += TimeUnit.DAYS.toMillis(1) // Next day if past time

            val request =
                PeriodicWorkRequestBuilder<DailyBriefingWorker>(
                    1,
                    TimeUnit.DAYS,
                )
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    )
                    .addTag(WORK_NAME)
                    .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request,
                )
            Log.i(TAG, "Daily briefing scheduled for 7:30 AM (delay: ${delay / 1000}s)")
        }
    }

    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            return@withContext try {
                // Check for cancellation at the start
                if (isStopped) {
                    Log.w(TAG, "Daily briefing worker cancelled before starting")
                    return@withContext Result.failure()
                }

                Log.i(TAG, "Generating daily briefing...")

                val db = SmartyDatabase.getDatabase(applicationContext)
                val repository = ServiceLocator.provideRepository(applicationContext as android.app.Application)

                // Gather 3 days of context
                val threeDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(LOOKBACK_DAYS.toLong())

                // Get recent notes
                val recentNotes = db.noteDao().getNotesModifiedSince(threeDaysAgo)

                // Check for cancellation
                if (isStopped) {
                    Log.w(TAG, "Daily briefing worker cancelled after fetching notes")
                    return@withContext Result.failure()
                }

                // Get upcoming calendar events
                val upcomingEvents =
                    db.calendarDao().getUpcomingEvents(
                        System.currentTimeMillis(),
                        System.currentTimeMillis() + TimeUnit.DAYS.toMillis(3),
                    )

                // Get recent chat topics
                val recentSessions = db.chatDao().getRecentSessions(5)

                // =============================================================================
                // EDGE CASE: Skip briefing for fresh/inactive users
                // =============================================================================
                // Don't trigger briefing if user has no activity:
                // - No notes in the last 3 days
                // - No upcoming events
                // - No recent chat sessions
                // This prevents "empty" briefings for new users or inactive accounts
                val hasNotes = recentNotes.isNotEmpty()
                val hasEvents = upcomingEvents.isNotEmpty()
                val hasChats = recentSessions.isNotEmpty()

                if (!hasNotes && !hasEvents && !hasChats) {
                    Log.i(TAG, "Skipping daily briefing - no user activity found (fresh/inactive user)")
                    // Return success but don't show notification
                    // This is not a failure - it's intentional behavior
                    return@withContext Result.success()
                }

                // Check for cancellation before building briefing
                if (isStopped) {
                    Log.w(TAG, "Daily briefing worker cancelled before building briefing")
                    return@withContext Result.failure()
                }

                // Build summaries only if we have data
                val notesSummary =
                    if (hasNotes) {
                        recentNotes.take(20).joinToString("\n") { note ->
                            "- [${note.categoryName ?: "Uncategorized"}] ${note.title}: ${note.content.take(100)}"
                        }
                    } else {
                        "No recent notes"
                    }

                val eventsSummary =
                    if (hasEvents) {
                        upcomingEvents.take(10).joinToString("\n") { event ->
                            "- ${event.title} at ${java.text.SimpleDateFormat(
                                "MMM d, h:mm a",
                                java.util.Locale.getDefault(),
                            ).format(java.util.Date(event.startTime))}"
                        }
                    } else {
                        "No upcoming events"
                    }

                val chatSummary =
                    if (hasChats) {
                        recentSessions.joinToString("\n") { session ->
                            "- ${session.title}"
                        }
                    } else {
                        "No recent conversations"
                    }

                // Build the briefing prompt
                val briefingPrompt = buildBriefingPrompt(notesSummary, eventsSummary, chatSummary)

                // Check for cancellation before sending to server
                if (isStopped) {
                    Log.w(TAG, "Daily briefing worker cancelled before sending to server")
                    return@withContext Result.failure()
                }

                // Send to server for AI generation
                val remoteService = ServiceLocator.provideRemoteAgentService(applicationContext as android.app.Application)
                val briefingResponse = remoteService.generateBriefing(briefingPrompt)

                if (!briefingResponse.isNullOrBlank()) {
                    // Extract memory updates if present
                    extractAndSaveMemoryUpdates(briefingResponse)

                    // Show notification
                    NotificationHelper.showDailyBriefing(
                        applicationContext,
                        title = "Good morning! Here's your day",
                        body = briefingResponse.take(300),
                        fullContent = briefingResponse,
                    )
                    Log.i(TAG, "Daily briefing generated and shown")
                    Result.success()
                } else {
                    Log.e(TAG, "Empty briefing response")
                    if (runAttemptCount < 3) Result.retry() else Result.failure()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.w(TAG, "Daily briefing worker cancelled", e)
                Result.failure()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to generate briefing: ${e.message}", e)
                if (runAttemptCount < 3) Result.retry() else Result.failure()
            }
        }

    private fun buildBriefingPrompt(
        notes: String,
        events: String,
        chats: String,
    ): String {
        return """
            Generate my daily morning briefing. Here's my activity from the last 3 days:

            RECENT NOTES:
            $notes

            UPCOMING EVENTS:
            $events

            RECENT CONVERSATIONS:
            $chats

            Based on this, create a brief morning briefing that:
            1. Greets me warmly (vary the greeting daily)
            2. Highlights top 3 priorities for today
            3. Reminds me of any upcoming deadlines or events
            4. Encourages me on projects I'm working on
            5. Suggests actionable next steps for anything that seems stuck
            6. Optionally mentions something interesting related to my interests

            Also return a MEMORY section at the end in this format:
            ---MEMORY---
            key: value
            key: value
            ---END_MEMORY---

            Include any new insights about my preferences, habits, or priorities that should be remembered.

            Keep the briefing concise (under 200 words for the main content).
            """.trimIndent()
    }

    private suspend fun extractAndSaveMemoryUpdates(response: String) {
        val memoryRegex = Regex("---MEMORY---\\n(.*?)\\n---END_MEMORY---", RegexOption.DOT_MATCHES_ALL)
        val match = memoryRegex.find(response) ?: return

        val memoryLines = match.groupValues[1].trim().split("\n")
        for (line in memoryLines) {
            val parts = line.split(":", limit = 2)
            if (parts.size == 2) {
                val key = parts[0].trim()
                val value = parts[1].trim()
                Log.d(TAG, "Memory update: $key = $value")
                // Memory persistence handled by MemoryFeatureManager via agent
                // Server integration: POST /api/memory/store with { key, value, context }
                // Currently memory sync happens through the agent's memory management system
            }
        }
    }
}
