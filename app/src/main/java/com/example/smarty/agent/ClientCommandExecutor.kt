package com.example.smarty.agent

import com.example.smarty.data.model.Note
import com.example.smarty.data.model.Category
import com.example.smarty.data.model.AudioTrack
import com.example.smarty.data.model.AIMemory
import com.example.smarty.data.model.CalendarEvent
import com.example.smarty.agent.models.ScreenContext
import com.example.smarty.viewmodel.managers.CategoryStatInfo
import com.example.smarty.viewmodel.managers.UserPatternsReport
import com.example.smarty.viewmodel.managers.LearningReport
import com.example.smarty.viewmodel.managers.SearchResultItem
import com.example.smarty.viewmodel.managers.SearchQueryAnalysis
import com.example.smarty.viewmodel.managers.RecallResult
import com.example.smarty.viewmodel.managers.StyleAnalysisReport
import com.example.smarty.viewmodel.managers.AudioFeatureManager.AudioSearchResult
import com.example.smarty.agent.tools.base.WebSearchResult

/**
 * Interface for commands sent FROM the Agent TO the Client/System.
 * These represent actions the agent wants to perform or data it needs to fetch.
 */
interface ClientCommandExecutor {
    // Data Retrieval
    fun getActiveNotes(): List<Note>
    fun getArchivedNotes(): List<Note>
    fun getCategories(): List<Category>
    fun getTavilyApiKey(): String?
    fun getOpenAiApiKey(): String?
    fun getGeminiApiKey(): String?
    fun getScreenContext(): ScreenContext?
    fun getDeviceAudio(): List<AudioTrack> = emptyList()
    fun getCurrentScreen(): String = "unknown"
    fun getSystemStatus(): Map<String, String> = emptyMap()

    // Note Operations
    fun addNote(content: String, category: String? = null)
    fun updateNote(noteId: String, title: String? = null, content: String? = null)
    fun deleteNoteById(noteId: String)
    fun archiveNote(noteId: String)
    fun unarchiveNote(noteId: String)
    fun summarizeNote(noteId: String)
    suspend fun processNoteWithAi(note: Note)
    suspend fun findNoteByDescription(description: String, notes: List<Note>): Note?
    suspend fun markNoteAsAnalyzedForMemory(noteId: String)
    fun addTodoToNote(noteId: String, text: String)

    // Category Operations
    suspend fun onCreateCategory(name: String): Category
    suspend fun getCategoryStats(): List<CategoryStatInfo>

    // App Navigation & Control
    fun launchApp(packageName: String)
    fun findPackageName(appName: String): String?
    fun navigateTo(screen: String)
    fun shareContent(text: String, title: String? = null)
    fun toggleTheme(isDark: Boolean)
    fun clearCache()
    fun backupData()
    fun setPrivacyMode(mode: String)

    // Memory & Intelligence
    fun syncMemory()
    suspend fun storeMemory(content: String, scope: String? = null)
    suspend fun updateMemory(id: String, content: String? = null, type: String? = null, confidence: Float? = null): Boolean
    suspend fun deleteMemory(id: String): Boolean
    suspend fun retrieveMemories(query: String?, limit: Int = 10): List<AIMemory>
    fun consolidateMemories()
    suspend fun getMemoryStats(): Map<String, Any> = emptyMap()
    suspend fun analyzePatterns(): UserPatternsReport
    suspend fun learnFromNotes(maxNotes: Int = 20): LearningReport

    // Search
    suspend fun searchNotes(query: String, category: String? = null, noteType: String? = null, timeRange: String = "all", limit: Int = 10): List<SearchResultItem>
    suspend fun advancedSearch(query: String, algorithm: String = "hybrid", limit: Int = 10, minScore: Double = 0.3): List<SearchResultItem>
    fun analyzeQuery(query: String): SearchQueryAnalysis
    suspend fun performRecall(query: String, minScore: Double = 0.3): List<RecallResult>
    fun onDeepResearch(topic: String, apiKey: String, focusAreas: List<String>?, searchDepth: Int)
    fun onAnalyzeStyle(limit: Int): StyleAnalysisReport
    suspend fun onWebSearch(query: String, maxResults: Int, topic: String, onCitationsFound: (List<WebCitation>) -> Unit): WebSearchResult
    suspend fun onParallelWebSearch(queries: List<String>, maxResults: Int, topic: String, onCitationsFound: (List<WebCitation>) -> Unit): WebSearchResult

    // Audio Control
    fun requestAudioPlayback(track: AudioTrack)
    fun playAudioList(tracks: List<AudioTrack>)
    fun pauseAudioPlayback()
    fun resumeAudioPlayback()
    fun stopAudioPlayback()
    fun seekAudioTo(positionMs: Long)
    fun toggleAudioPlayback()
    fun getCurrentAudioTrack(): AudioTrack?
    fun getCurrentAudioPosition(): Long
    fun getAudioDuration(): Long
    fun isAudioPlaying(): Boolean
    fun findMatchingAudio(query: String): AudioSearchResult

    // Calendar & Time
    fun addCalendarEvent(title: String, startTimeStr: String, endTimeStr: String?, description: String?, location: String?, isPrivate: Boolean)
    fun deleteCalendarEvent(eventId: String)
    suspend fun queryCalendarEvents(query: String?): List<CalendarEvent>
    fun bulkDeleteEvents(eventIds: List<String>)
    fun setTimer(name: String, timeStr: String, isAlarm: Boolean)
    fun cancelTimer(timerId: String)

    // Bulk Operations
    fun bulkArchiveNotes(noteIds: List<String>)
    fun bulkDeleteNotes(noteIds: List<String>)
    fun bulkMoveToCategory(noteIds: List<String>, categoryName: String)
}
