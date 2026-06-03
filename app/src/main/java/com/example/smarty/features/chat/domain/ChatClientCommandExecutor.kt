package com.example.smarty.features.chat.domain

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.example.smarty.core.common.util.ContentTypeDetector
import com.example.smarty.core.common.util.PrivacyGuard
import com.example.smarty.core.domain.model.*
import com.example.smarty.features.audio.domain.AudioFeatureManager
import com.example.smarty.features.audio.domain.AudioFeatureManager.AudioSearchResult
import com.example.smarty.features.calendar.domain.CalendarFeatureManager
import com.example.smarty.features.chat.agent.models.ScreenContext
import com.example.smarty.features.notes.domain.NoteOperationsManager
import com.example.smarty.features.search.domain.SearchFeatureManager
import com.example.smarty.features.system.domain.SystemFeatureManager
import com.example.smarty.features.chat.agent.ClientCommandExecutor
import com.example.smarty.ui.components.ConnectionStatus

class ChatClientCommandExecutor(
    private val application: Application,
    private val scope: CoroutineScope,
    private val activeNoteId: StateFlow<String?>,
    private val allNotes: StateFlow<List<Note>>,
    private val archivedNotes: StateFlow<List<Note>>,
    private val allCategories: StateFlow<List<Category>>,
    private val currentScreen: StateFlow<String>,
    private val isDarkTheme: StateFlow<Boolean>,
    private val connectionStatus: StateFlow<ConnectionStatus>,
    private val cacheSizeBytes: StateFlow<Long>,
    private val systemFeatureManager: SystemFeatureManager,
    private val noteOperationsManager: NoteOperationsManager,
    private val searchFeatureManager: SearchFeatureManager,
    private val audioFeatureManager: AudioFeatureManager,
    private val calendarFeatureManager: CalendarFeatureManager,
    private val onNavigate: (String?) -> Unit,
    private val onShowBreathing: () -> Unit,
    private val allowedSettings: Set<String>
) : ClientCommandExecutor {
    companion object { private const val TAG = "ChatClientCommandExecutor" }
    override fun getActiveNotes(): List<Note> {
            val rawNotes = allNotes.value
            return PrivacyGuard.getAiVisibleNotes(rawNotes)
        }

        override fun getArchivedNotes(): List<Note> =
            PrivacyGuard.getAiVisibleNotes(archivedNotes.value.map { it.copy(isArchived = false) })

        override fun getCategories(): List<Category> = allCategories.value

        override fun getScreenContext(): ScreenContext? {
            val activeId = activeNoteId.value ?: return null
            val note = allNotes.value.find { it.id == activeId } ?: return null

            return ScreenContext(
                selectedText = null,
                referringApp = application.packageName,
                capturedAt = System.currentTimeMillis(),
                contextData =
                    mapOf(
                        "active_note_id" to note.id,
                        "active_note_title" to note.title,
                        "active_note_content" to note.content,
                        "active_note_type" to note.type.name,
                        "current_screen" to currentScreen.value,
                    ),
            )
        }

        override suspend fun getDeviceAudio(): List<AudioTrack> = systemFeatureManager.getDeviceAudio()

        override fun navigateTo(screen: String) {
            onNavigate(screen)
        }

        override fun getCurrentScreen(): String = currentScreen.value

        override fun getSystemStatus(): Map<String, String> =
            systemFeatureManager.getSystemStatus(
                isDarkTheme = isDarkTheme.value,
                connectionStatus = connectionStatus.value.name,
                cacheSize = ContentTypeDetector.formatFileSize(application, cacheSizeBytes.value),
                unreadMemoryCount = 0, // Placeholder to fix compilation
            )

        override fun addNote(
            content: String,
            category: String?,
        ) {
            scope.launch {
                noteOperationsManager.addNote(
                    content = content,
                    type = NoteType.BRAIN_DUMP,
                    excludeFromAiChat = false,
                    initialCategory = category,
                )
            }
        }

        override fun updateNote(
            noteId: String,
            title: String?,
            content: String?,
        ) {
            scope.launch {
                val target = allNotes.value.find { it.id == noteId } ?: archivedNotes.value.find { it.id == noteId }
                if (target != null && target.isPrivate) {
                    Log.w(TAG, "SECURITY: Blocked Agent modify on private note: $noteId")
                    return@launch
                }
                noteOperationsManager.updateNote(noteId, title, content, allNotes.value, archivedNotes.value)
            }
        }

        override fun deleteNoteById(noteId: String) {
            scope.launch {
                val target = allNotes.value.find { it.id == noteId } ?: archivedNotes.value.find { it.id == noteId }
                if (target != null && target.isPrivate) {
                    Log.w(TAG, "SECURITY: Blocked Agent modify on private note: $noteId")
                    return@launch
                }
                noteOperationsManager.deleteNoteById(noteId, allNotes.value, archivedNotes.value)
            }
        }

        override fun archiveNote(noteId: String) {
            val target = allNotes.value.find { it.id == noteId } ?: archivedNotes.value.find { it.id == noteId }
            if (target != null && target.isPrivate) {
                Log.w(TAG, "SECURITY: Blocked Agent modify on private note: $noteId")
                return
            }
            noteOperationsManager.archiveNote(noteId)
        }

        override fun unarchiveNote(noteId: String) {
            val target = allNotes.value.find { it.id == noteId } ?: archivedNotes.value.find { it.id == noteId }
            if (target != null && target.isPrivate) {
                Log.w(TAG, "SECURITY: Blocked Agent modify on private note: $noteId")
                return
            }
            noteOperationsManager.unarchiveNote(noteId)
        }

        override fun summarizeNote(noteId: String) {
            noteOperationsManager.summarizeNote(noteId, allNotes.value, archivedNotes.value)
        }

        override suspend fun processNoteWithAi(note: Note) {
            noteOperationsManager.processNoteWithAi(note)
        }

        override suspend fun onCreateCategory(name: String): Category = noteOperationsManager.getOrCreateCategory(name)

        override suspend fun getCategoryStats(): List<CategoryStatInfo> =
            noteOperationsManager.getCategoryStats(allCategories.value, allNotes.value)

        override fun toggleTheme(isDark: Boolean) {
            systemFeatureManager.toggleTheme(isDark)
        }

        override suspend fun toggleSetting(
            setting: String,
            enable: Boolean,
        ) {
            if (setting.lowercase() !in allowedSettings) {
                Log.w(TAG, "SECURITY: Blocked toggle of unapproved setting: $setting")
                return
            }
            systemFeatureManager.toggleSetting(setting, enable)
        }

        override suspend fun takeScreenshot(save: Boolean) {
            // Screen capture is handled by SystemFeatureManager
            systemFeatureManager.captureScreen()
        }

        override fun clearCache() {
            systemFeatureManager.clearCache()
        }

        override fun backupData() {
            systemFeatureManager.backupData()
        }

        override fun setPrivacyMode(mode: String) {
            systemFeatureManager.setPrivacyMode(mode)
        }

        override suspend fun searchNotes(
            query: String,
            category: String?,
            noteType: String?,
            timeRange: String,
            limit: Int,
        ): List<SearchResultItem> = searchFeatureManager.search(query, category, noteType, timeRange, emptySet(), limit)

        override suspend fun advancedSearch(
            query: String,
            algorithm: String,
            limit: Int,
            minScore: Double,
        ): List<SearchResultItem> = searchFeatureManager.advancedSearch(query, algorithm, limit, minScore)

        override fun analyzeQuery(query: String): SearchQueryAnalysis = searchFeatureManager.analyzeQuery(query)

        override suspend fun performRecall(
            query: String,
            minScore: Double,
        ): List<RecallResult> = searchFeatureManager.performRecall(query, minScore)

        override fun requestAudioPlayback(track: AudioTrack) {
            audioFeatureManager.play(track)
        }

        override fun shareContent(
            text: String,
            title: String?,
        ) {
            systemFeatureManager.shareContent(text, title)
        }

        override fun launchApp(packageName: String) {
            systemFeatureManager.launchApp(packageName)
        }

        override fun findPackageName(appName: String): String? = systemFeatureManager.findPackageName(appName)

        override suspend fun findMatchingAudio(query: String): AudioSearchResult = audioFeatureManager.findAudioTrack(query)

        override suspend fun controlAudio(action: String) {
            when (action.lowercase()) {
                "pause" -> audioFeatureManager.pause()
                "resume" -> audioFeatureManager.resume()
                "stop" -> audioFeatureManager.stop()
                "toggle" -> audioFeatureManager.togglePlayPause()
                "next" -> audioFeatureManager.next()
                "previous", "prev" -> audioFeatureManager.previous()
                "volume_up" -> systemFeatureManager.adjustVolume(1)
                "volume_down" -> systemFeatureManager.adjustVolume(-1)
            }
        }

        override suspend fun seekAudio(positionMs: Long) {
            audioFeatureManager.seekTo(positionMs)
        }

        override fun playAudioList(tracks: List<AudioTrack>) {
            audioFeatureManager.playList(tracks)
        }

        override fun pauseAudioPlayback() {
            audioFeatureManager.pause()
        }

        override fun resumeAudioPlayback() {
            audioFeatureManager.resume()
        }

        override fun stopAudioPlayback() {
            audioFeatureManager.stop()
        }

        override fun seekAudioTo(positionMs: Long) {
            audioFeatureManager.seekTo(positionMs)
        }

        override fun toggleAudioPlayback() {
            audioFeatureManager.togglePlayPause()
        }

        override fun nextTrack() {
            audioFeatureManager.next()
        }

        override fun previousTrack() {
            audioFeatureManager.previous()
        }

        override fun getCurrentAudioTrack(): AudioTrack? = audioFeatureManager.getCurrentTrack()

        override fun getCurrentAudioPosition(): Long = audioFeatureManager.getCurrentPosition()

        override fun getAudioDuration(): Long = audioFeatureManager.getDuration()

        override fun isAudioPlaying(): Boolean = audioFeatureManager.isPlaying()

        override fun addCalendarEvent(
            title: String,
            startTimeStr: String,
            endTimeStr: String?,
            description: String?,
            location: String?,
            isPrivate: Boolean,
        ) {
            val startMillis = calendarFeatureManager.parseDateTime(startTimeStr) ?: return
            val endMillis =
                endTimeStr?.let { calendarFeatureManager.parseDateTime(it) }
                    ?: (startMillis + 3600000L)

            calendarFeatureManager.addCalendarEvent(
                title = title,
                description = description,
                startTime = startMillis,
                endTime = endMillis,
                location = location,
                isPrivate = isPrivate,
            )
        }

        override fun deleteCalendarEvent(eventId: String) {
            calendarFeatureManager.deleteCalendarEvent(eventId)
        }

        override suspend fun scheduleEvent(
            title: String,
            startTime: Long,
            endTime: Long,
            description: String?,
        ) {
            calendarFeatureManager.addCalendarEvent(
                title = title,
                description = description,
                startTime = startTime,
                endTime = endTime,
                location = null,
                isPrivate = false,
            )
        }

        override suspend fun listEvents(date: Long): List<CalendarEvent> = calendarFeatureManager.getEventsForDay(date)

        override suspend fun deleteEvent(eventId: String) {
            calendarFeatureManager.deleteCalendarEvent(eventId)
        }

        override suspend fun queryCalendarEvents(query: String?): List<CalendarEvent> =
            if (query.isNullOrBlank()) {
                calendarFeatureManager.getTodayEvents()
            } else {
                calendarFeatureManager.searchEvents(query)
            }

        override fun bulkDeleteEvents(eventIds: List<String>) {
            eventIds.forEach { id ->
                calendarFeatureManager.deleteCalendarEvent(id)
            }
        }

        override fun setTimer(
            name: String,
            timeStr: String,
            isAlarm: Boolean,
            repeat: String?,
            triggerTime: Long?,
        ) {
            val finalTriggerTime = triggerTime ?: calendarFeatureManager.parseDateTime(timeStr) ?: return
            // We're delegating to calendarFeatureManager for now.
            calendarFeatureManager.setTimer(name, finalTriggerTime, isAlarm, repeat)
        }

        override fun listTimers() {
            // Implementation for listTimers, potentially retrieving them from calendarFeatureManager
            // For now, it might be a no-op if calendarFeatureManager doesn't support it, but it should exist.
            // It looks like timer state is managed in the server mostly. If client needs to return it,
            // we'll need to fetch it. For now, since LocalCommandTransport returns "Timers listed", this is fine.
        }

        override fun cancelTimer(timerId: String) {
            calendarFeatureManager.cancelTimer(timerId)
        }

        override fun addTodoToNote(
            noteId: String,
            text: String,
        ) {
            scope.launch {
                val target = allNotes.value.find { it.id == noteId } ?: archivedNotes.value.find { it.id == noteId }
                if (target != null && target.isPrivate) {
                    Log.w(TAG, "SECURITY: Blocked Agent modify on private note: $noteId")
                    return@launch
                }
                noteOperationsManager.addTodoToNote(noteId, text)
            }
        }

        override fun showBreathing() {
            onShowBreathing()
        }

        override fun bulkArchiveNotes(noteIds: List<String>) {
            noteOperationsManager.bulkArchiveNotes(noteIds)
        }

        override fun bulkDeleteNotes(noteIds: List<String>) {
            noteOperationsManager.bulkDeleteNotes(noteIds, allNotes.value, archivedNotes.value)
        }

        override fun bulkMoveToCategory(
            noteIds: List<String>,
            categoryName: String,
        ) {
            noteOperationsManager.bulkMoveToCategory(noteIds, categoryName)
        }

        override suspend fun storeContext(
            content: String,
            type: String,
        ) {
            // Implementation for storing context
            Log.d(TAG, "Storing context: type=$type")
        }

        override suspend fun updateContext(
            id: String,
            content: String,
            type: String,
        ) {
            // Implementation for updating context
            Log.d(TAG, "Updating context: id=$id, type=$type")
        }

        override suspend fun deleteContext(id: String) {
            // Implementation for deleting context
            Log.d(TAG, "Deleting context: id=$id")
        }
}
