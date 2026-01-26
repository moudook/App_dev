package com.example.smarty.viewmodel.managers

import android.util.Log
import com.example.smarty.data.local.AIMemoryDao
import com.example.smarty.data.model.AIMemory
import com.example.smarty.data.model.MemoryType
import com.example.smarty.data.model.Note
import com.example.smarty.data.model.Category
import com.example.smarty.data.repository.JarvisRepository
import com.example.smarty.util.PrivacyGuard
import com.example.smarty.util.search.SemanticSearchEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.util.*
import kotlin.math.min

/**
 * Unified manager for user behavior learning and long-term memory.
 * Hybridizes logic for:
 * - Active memory storage and retrieval (CRUD)
 * - Background pattern extraction (via MemorySyncManager & Regex)
 * - Insight consolidation and abstraction
 * - Cognitive analytics and user pattern analysis
 *
 * This manager is the "Pre-frontal Cortex" of the app, used by UI and AI.
 */
class MemoryFeatureManager(
    private val aiMemoryDao: AIMemoryDao,
    private val syncManager: MemorySyncManager,
    private val scope: CoroutineScope
) {
    /**
     * All cognitive memories as a reactive flow.
     */
    val allMemories: StateFlow<List<AIMemory>> = aiMemoryDao.getAllMemoriesFlow()
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    companion object {
        private const val TAG = "MemoryFeatureManager"

        private val SENSITIVE_PATTERNS = listOf(
            "password", "secret", "api key", "token",
            "credit card", "card number", "cvv", "expiry",
            "social security", "ssn", "bank account"
        )
    }

    /**
     * Store a new fact or preference with privacy and duplicate checks.
     */
    suspend fun storeMemory(
        content: String,
        scope: String? = "fact",
        confidence: Float = 1.0f,
        source: String = "User interaction"
    ): Boolean {
        if (containsSensitiveContent(content)) {
            Log.w(TAG, "Refused to store sensitive content in memory")
            return false
        }

        val type = when (scope?.lowercase()) {
            "preference", "user_preference" -> MemoryType.PREFERENCE
            "pattern" -> MemoryType.PATTERN
            "style" -> MemoryType.STYLE
            "fact" -> MemoryType.FACT
            else -> MemoryType.FACT
        }

        if (!aiMemoryDao.memoryExists(content, type)) {
            val memory = AIMemory(
                type = type,
                content = content,
                confidence = confidence.coerceIn(0.1f, 1.0f),
                source = source
            )
            aiMemoryDao.insertMemory(memory)
            Log.i(TAG, "Stored new cognitive entry: $content")
            return true
        }
        return false
    }

    /**
     * Update an existing memory.
     */
    suspend fun updateMemory(id: String, content: String?, type: String?, confidence: Float?): Boolean {
        val existing = aiMemoryDao.getMemoryById(id) ?: return false

        val newContent = content ?: existing.content
        if (containsSensitiveContent(newContent)) return false

        val newType = type?.let { parseMemoryType(it) } ?: existing.type

        val updated = existing.copy(
            type = newType,
            content = newContent,
            confidence = confidence?.coerceIn(0.1f, 1.0f) ?: existing.confidence,
            lastUsedAt = System.currentTimeMillis()
        )
        aiMemoryDao.updateMemory(updated)
        return true
    }

    /**
     * Delete a memory.
     */
    suspend fun deleteMemory(id: String): Boolean {
        val existing = aiMemoryDao.getMemoryById(id) ?: return false
        aiMemoryDao.deleteMemory(existing)
        return true
    }

    /**
     * Clear all memories from the database.
     */
    suspend fun clearAllMemories(): Boolean {
        return try {
            aiMemoryDao.clearAllMemories()
            Log.i(TAG, "All cognitive memories cleared")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear memories: ${e.message}")
            false
        }
    }

    /**
     * Search or retrieve recent memories.
     */
    suspend fun retrieveMemories(query: String?, limit: Int = 10): List<AIMemory> {
        return if (query.isNullOrBlank()) {
            aiMemoryDao.getRecentMemories(limit)
        } else {
            aiMemoryDao.searchMemories(query)
        }
    }

    /**
     * Analyze user's note-taking patterns.
     */
    suspend fun analyzePatterns(notes: List<Note>, categories: List<Category>): UserPatternsReport {
        val visibleNotes = PrivacyGuard.getAiVisibleNotes(notes)
        val now = System.currentTimeMillis()
        val oneWeekAgo = now - 7 * 24 * 60 * 60 * 1000L

        // 1. Category usage
        val topCategories = analyzeCategoryUsage(visibleNotes, categories, oneWeekAgo)

        // 2. Activity trends
        val activity = analyzeActivity(visibleNotes, oneWeekAgo)

        // 3. Common topics
        val topics = analyzeTopics(visibleNotes)

        return UserPatternsReport(
            summary = "You have ${visibleNotes.size} notes. Most active on ${activity.mostActiveDay ?: "various days"}.",
            topCategories = topCategories,
            activityTrend = activity,
            frequentTopics = topics,
            suggestions = generateSuggestions(topCategories, activity, topics)
        )
    }

    /**
     * Perform proactive learning from notes (Regex + Heuristics).
     */
    suspend fun learnFromNotes(notes: List<Note>, maxNotes: Int = 20): LearningReport {
        val visibleNotes = PrivacyGuard.getAiVisibleNotes(notes)
            .sortedByDescending { it.updatedAt }
            .take(maxNotes)

        var learnedCount = 0
        val insights = mutableListOf<String>()

        for (note in visibleNotes) {
            val content = "${note.title} ${note.content ?: ""}".lowercase()

            // Travel patterns
            extractTravelPatterns(content).forEach {
                if (storeMemory(it, "fact", 0.9f, "Pattern Analysis")) {
                    learnedCount++
                    insights.add(it)
                }
            }

            // Learning interests
            extractLearningInterests(content).forEach {
                if (storeMemory(it, "pattern", 0.8f, "Pattern Analysis")) {
                    learnedCount++
                    insights.add(it)
                }
            }
        }

        return LearningReport(
            notesAnalyzed = visibleNotes.size,
            newInsightsFound = learnedCount,
            insights = insights
        )
    }

    /**
     * Get high-level cognitive statistics.
     */
    suspend fun getMemoryStats(): Map<String, Any> {
        val total = aiMemoryDao.getMemoryCount()
        val byType = MemoryType.values().associate { type ->
            type.name to aiMemoryDao.getMemoryCountByType(type)
        }
        return mapOf(
            "total_memories" to total,
            "distribution" to byType,
            "unread_notes" to syncManager.unreadCount.value
        )
    }

    /**
     * Trigger background synchronization from notes.
     */
    fun syncFromNotes() {
        scope.launch {
            syncManager.syncMemoriesFromNotes()
        }
    }

    /**
     * Trigger abstraction and consolidation loop.
     */
    fun consolidateMemories() {
        scope.launch {
            syncManager.consolidateAllMemories()
        }
    }

    // === Private Helpers (Migrated Logic) ===

    private fun containsSensitiveContent(content: String): Boolean {
        val lower = content.lowercase()
        return SENSITIVE_PATTERNS.any { lower.contains(it) }
    }

    private fun parseMemoryType(type: String): MemoryType? {
        return try { MemoryType.valueOf(type.uppercase()) } catch (e: Exception) { null }
    }

    private fun analyzeCategoryUsage(notes: List<Note>, categories: List<Category>, since: Long): List<CategoryStat> {
        return categories.map { cat ->
            val count = notes.count { it.categoryId == cat.id }
            val recent = notes.count { it.categoryId == cat.id && it.createdAt >= since }
            CategoryStat(cat.name, count, recent)
        }.filter { it.totalCount > 0 }.sortedByDescending { it.totalCount }.take(5)
    }

    private fun analyzeActivity(notes: List<Note>, since: Long): ActivityTrend {
        val thisWeek = notes.count { it.createdAt >= since }
        val dayCounts = mutableMapOf<Int, Int>()
        notes.filter { it.createdAt >= since }.forEach {
            val cal = Calendar.getInstance().apply { timeInMillis = it.createdAt }
            val day = cal.get(Calendar.DAY_OF_WEEK)
            dayCounts[day] = (dayCounts[day] ?: 0) + 1
        }
        val peakDay = dayCounts.maxByOrNull { it.value }?.key
        val dayNames = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        return ActivityTrend(thisWeek, peakDay?.let { dayNames[it-1] })
    }

    private fun analyzeTopics(notes: List<Note>): List<String> {
        val words = mutableMapOf<String, Int>()
        notes.forEach { note ->
            SemanticSearchEngine.tokenize(note.title.lowercase()).filter { it.length > 3 }.forEach {
                words[it] = (words[it] ?: 0) + 1
            }
        }
        return words.entries.sortedByDescending { it.value }.take(5).map { it.key }
    }

    private fun extractTravelPatterns(content: String): List<String> {
        val patterns = mutableListOf<String>()
        if (content.contains("flight") || content.contains("travel to")) {
            val destMatch = Regex("to ([a-zA-Z\\s]{3,20})").find(content)
            destMatch?.let { patterns.add("User is planning travel to ${it.groupValues[1].trim()}") }
        }
        return patterns
    }

    private fun extractLearningInterests(content: String): List<String> {
        val interests = mutableListOf<String>()
        val matches = Regex("(?:learning|studying|researching) ([a-zA-Z\\s]{3,20})").findAll(content)
        matches.forEach { interests.add("User is interested in ${it.groupValues[1].trim()}") }
        return interests
    }

    private fun generateSuggestions(cats: List<CategoryStat>, act: ActivityTrend, topics: List<String>): List<String> {
        val suggestions = mutableListOf<String>()
        if (act.notesThisWeek < 2) suggestions.add("You haven't taken many notes lately. Want to capture some thoughts?")
        if (topics.isNotEmpty()) suggestions.add("You've been writing about ${topics.first()}. Should I summarize those notes?")
        return suggestions
    }
}

data class CategoryStat(val name: String, val totalCount: Int, val recentCount: Int)
data class ActivityTrend(val notesThisWeek: Int, val mostActiveDay: String?)
data class UserPatternsReport(
    val summary: String,
    val topCategories: List<CategoryStat>,
    val activityTrend: ActivityTrend,
    val frequentTopics: List<String>,
    val suggestions: List<String>
)
data class LearningReport(
    val notesAnalyzed: Int,
    val newInsightsFound: Int,
    val insights: List<String>
)
