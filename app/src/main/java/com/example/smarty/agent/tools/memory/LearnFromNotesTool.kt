package com.example.smarty.agent.tools.memory

import android.util.Log
import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.example.smarty.data.local.AIMemoryDao
import com.example.smarty.data.model.AIMemory
import com.example.smarty.data.model.MemoryType
import com.example.smarty.data.model.Note
import com.example.smarty.util.PrivacyGuard
import com.example.smarty.util.toon.ToonManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val json = Json { encodeDefaults = false }

/**
 * =============================================================================
 * LEARN FROM NOTES TOOL
 * =============================================================================
 *
 * Analyzes user's note-taking behavior to learn patterns and preferences.
 * This tool marks notes as "read" (analyzed) and updates memories based
 * on observed patterns.
 *
 * PATTERNS DETECTED:
 * - Topic preferences (what does the user write about frequently?)
 * - Time patterns (when does the user typically create notes?)
 * - Category preferences (which categories are most used?)
 * - Content style (bullet points vs paragraphs, length preferences)
 *
 * PRIVACY RULES (CRITICAL):
 * - NEVER store actual note content in memories
 * - Only store abstract patterns and preferences
 * - Only analyze AI-visible notes (respects PrivacyGuard)
 *
 * =============================================================================
 */

private const val TAG = "LearnFromNotesTool"

@Serializable
data class LearnFromNotesArgs(
    @property:LLMDescription("Action: 'analyze' (learn from notes and update memories), 'status' (check which notes have been analyzed)")
    val action: String = "analyze",

    @property:LLMDescription("Maximum number of notes to analyze in this batch (default 20)")
    val maxNotes: Int = 20
)

@Serializable
data class LearnedPattern(
    val type: String,
    val insight: String,
    val confidence: Float
)

@Serializable
data class LearnFromNotesResult(
    val success: Boolean,
    val message: String,
    val notesAnalyzed: Int = 0,
    val newMemoriesCreated: Int = 0,
    val memoriesUpdated: Int = 0,
    val insights: List<LearnedPattern> = emptyList()
) {
    override fun toString(): String {
        val jsonStr = json.encodeToString(serializer(), this)
        return ToonManager.jsonToToon(jsonStr)
    }
}

class LearnFromNotesTool(
    private val aiMemoryDao: AIMemoryDao,
    private val getActiveNotes: () -> List<Note>,
    private val markNoteAsAnalyzed: suspend (String) -> Unit  // Marks note ID as analyzed
) : Tool<LearnFromNotesArgs, LearnFromNotesResult>(
    argsSerializer = LearnFromNotesArgs.serializer(),
    resultSerializer = LearnFromNotesResult.serializer(),
    name = "learn_from_notes",
    description = """
        Analyze user's notes to learn their preferences and patterns.
        Use this periodically to update user memory with discovered insights.
        
        WHEN TO USE:
        - User asks "analyze my notes" or "learn about me"
        - Proactively when building better personalization
        - After user creates many new notes
        
        WHAT IT LEARNS:
        - Topic interests (what user writes about most)
        - Writing style preferences (bullets vs prose)
        - Active time patterns (when user is most active)
        - Category usage patterns
        
        PRIVACY: Only processes AI-visible notes, never stores note content.
    """.trimIndent()
) {
    override suspend fun execute(args: LearnFromNotesArgs): LearnFromNotesResult {
        return when (args.action.lowercase()) {
            "analyze" -> analyzeNotes(args.maxNotes)
            "status" -> getAnalysisStatus()
            else -> LearnFromNotesResult(
                success = false,
                message = "Unknown action: ${args.action}. Use 'analyze' or 'status'."
            )
        }
    }

    private suspend fun analyzeNotes(maxNotes: Int): LearnFromNotesResult {
        val allNotes = getActiveNotes()
        val visibleNotes = PrivacyGuard.getAiVisibleNotes(allNotes)

        if (visibleNotes.isEmpty()) {
            return LearnFromNotesResult(
                success = true,
                message = "No AI-visible notes to analyze.",
                notesAnalyzed = 0
            )
        }

        // Analyze notes (most recent first)
        val notesToAnalyze = visibleNotes
            .sortedByDescending { it.updatedAt }
            .take(maxNotes)

        val insights = mutableListOf<LearnedPattern>()
        var memoriesCreated = 0
        var memoriesUpdated = 0

        // ═══════════════════════════════════════════════════════════════
        // PATTERN DETECTION
        // ═══════════════════════════════════════════════════════════════

        // 1. Topic Analysis - extract common themes (without storing content)
        val topicPatterns = analyzeTopics(visibleNotes)
        topicPatterns.forEach { pattern ->
            val created = savePatternAsMemory(pattern)
            if (created) memoriesCreated++ else memoriesUpdated++
            insights.add(pattern)
        }

        // 2. Time Pattern Analysis - when does user typically create notes?
        val timePatterns = analyzeTimePatterns(visibleNotes)
        timePatterns.forEach { pattern ->
            val created = savePatternAsMemory(pattern)
            if (created) memoriesCreated++ else memoriesUpdated++
            insights.add(pattern)
        }

        // 3. Content Style Analysis - bullet points, length preferences
        val stylePatterns = analyzeContentStyle(visibleNotes)
        stylePatterns.forEach { pattern ->
            val created = savePatternAsMemory(pattern)
            if (created) memoriesCreated++ else memoriesUpdated++
            insights.add(pattern)
        }

        // 4. Category Usage Analysis
        val categoryPatterns = analyzeCategoryUsage(visibleNotes)
        categoryPatterns.forEach { pattern ->
            val created = savePatternAsMemory(pattern)
            if (created) memoriesCreated++ else memoriesUpdated++
            insights.add(pattern)
        }

        // Mark analyzed notes
        notesToAnalyze.forEach { note ->
            try {
                markNoteAsAnalyzed(note.id)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to mark note ${note.id} as analyzed: ${e.message}")
            }
        }

        return LearnFromNotesResult(
            success = true,
            message = "Analyzed ${notesToAnalyze.size} notes and learned ${insights.size} patterns about the user.",
            notesAnalyzed = notesToAnalyze.size,
            newMemoriesCreated = memoriesCreated,
            memoriesUpdated = memoriesUpdated,
            insights = insights
        )
    }

    private suspend fun getAnalysisStatus(): LearnFromNotesResult {
        val memoryCount = aiMemoryDao.getMemoryCount()
        val recentMemories = aiMemoryDao.getRecentMemories(5)

        val insights = recentMemories.map { mem ->
            LearnedPattern(
                type = mem.type.name.lowercase(),
                insight = mem.content,
                confidence = mem.confidence
            )
        }

        return LearnFromNotesResult(
            success = true,
            message = "Currently have $memoryCount memories about the user.",
            insights = insights
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // PATTERN ANALYSIS METHODS
    // ═══════════════════════════════════════════════════════════════

    private fun analyzeTopics(notes: List<Note>): List<LearnedPattern> {
        val patterns = mutableListOf<LearnedPattern>()

        // Common words analysis (stop words filtered)
        val stopWords = setOf(
            "the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for", "of",
            "is", "it", "this", "that", "with", "from", "by", "as", "be", "was", "were",
            "are", "been", "have", "has", "had", "do", "does", "did", "will", "would",
            "could", "should", "may", "might", "must", "can", "my", "your", "his", "her",
            "its", "our", "their", "i", "you", "he", "she", "we", "they", "what", "which",
            "who", "when", "where", "why", "how", "all", "each", "every", "both", "few",
            "more", "most", "other", "some", "such", "no", "not", "only", "own", "same",
            "so", "than", "too", "very", "just", "also", "now", "here", "there", "note",
            "notes", "todo", "new", "one", "about", "need", "want", "like", "make", "get"
        )

        val wordFrequency = mutableMapOf<String, Int>()
        notes.forEach { note ->
            val words = note.title.lowercase()
                .split(Regex("\\W+"))
                .filter { it.length > 3 && it !in stopWords }

            words.forEach { word ->
                wordFrequency[word] = (wordFrequency[word] ?: 0) + 1
            }
        }

        // Top 3 frequent topics
        val topTopics = wordFrequency.entries
            .filter { it.value >= 3 }  // At least 3 mentions
            .sortedByDescending { it.value }
            .take(3)

        if (topTopics.isNotEmpty()) {
            val topicString = topTopics.joinToString(", ") { it.key }
            patterns.add(
                LearnedPattern(
                    type = "pattern",
                    insight = "User frequently writes about: $topicString",
                    confidence = 0.8f
                )
            )
        }

        return patterns
    }

    private fun analyzeTimePatterns(notes: List<Note>): List<LearnedPattern> {
        val patterns = mutableListOf<LearnedPattern>()
        val now = System.currentTimeMillis()
        val oneWeekAgo = now - 7 * 24 * 60 * 60 * 1000L

        val recentNotes = notes.filter { it.createdAt >= oneWeekAgo }
        if (recentNotes.size < 3) return patterns  // Not enough data

        // Hour of day analysis
        val hourCounts = mutableMapOf<Int, Int>()
        recentNotes.forEach { note ->
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = note.createdAt }
            val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
            hourCounts[hour] = (hourCounts[hour] ?: 0) + 1
        }

        val peakHour = hourCounts.maxByOrNull { it.value }
        if (peakHour != null && peakHour.value >= 2) {
            val timeOfDay = when (peakHour.key) {
                in 5..11 -> "morning"
                in 12..16 -> "afternoon"
                in 17..20 -> "evening"
                else -> "night"
            }
            patterns.add(
                LearnedPattern(
                    type = "pattern",
                    insight = "User is most active creating notes in the $timeOfDay",
                    confidence = 0.7f
                )
            )
        }

        // Day of week analysis
        val dayNames = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
        val dayCounts = mutableMapOf<Int, Int>()
        recentNotes.forEach { note ->
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = note.createdAt }
            val day = cal.get(java.util.Calendar.DAY_OF_WEEK) - 1
            dayCounts[day] = (dayCounts[day] ?: 0) + 1
        }

        val peakDay = dayCounts.maxByOrNull { it.value }
        if (peakDay != null && peakDay.value >= 2) {
            patterns.add(
                LearnedPattern(
                    type = "pattern",
                    insight = "User creates most notes on ${dayNames[peakDay.key]}s",
                    confidence = 0.6f
                )
            )
        }

        return patterns
    }

    private fun analyzeContentStyle(notes: List<Note>): List<LearnedPattern> {
        val patterns = mutableListOf<LearnedPattern>()
        val recentNotes = notes.take(20)  // Focus on recent behavior

        if (recentNotes.isEmpty()) return patterns

        // Check for bullet point usage
        val bulletUsers = recentNotes.count { note ->
            note.content?.contains(Regex("^\\s*[-•*]\\s", RegexOption.MULTILINE)) == true ||
            note.content?.contains(Regex("^\\s*\\d+\\.\\s", RegexOption.MULTILINE)) == true
        }

        if (bulletUsers > recentNotes.size / 2) {
            patterns.add(
                LearnedPattern(
                    type = "style",
                    insight = "User prefers bullet points and lists in their notes",
                    confidence = 0.75f
                )
            )
        }

        // Average note length analysis
        val avgLength = recentNotes.mapNotNull { it.content?.length }.average()
        if (avgLength.isFinite()) {
            val preference = when {
                avgLength < 100 -> "short and concise"
                avgLength < 500 -> "moderate length"
                else -> "detailed and comprehensive"
            }
            patterns.add(
                LearnedPattern(
                    type = "style",
                    insight = "User tends to write $preference notes",
                    confidence = 0.6f
                )
            )
        }

        return patterns
    }

    private fun analyzeCategoryUsage(notes: List<Note>): List<LearnedPattern> {
        val patterns = mutableListOf<LearnedPattern>()

        val categoryGroups = notes.groupBy { it.categoryId }
        if (categoryGroups.size > 1) {
            val topCategory = categoryGroups.maxByOrNull { it.value.size }
            val percentage = topCategory?.value?.size?.times(100)?.div(notes.size) ?: 0

            if (percentage > 40) {
                patterns.add(
                    LearnedPattern(
                        type = "pattern",
                        insight = "User heavily uses one primary category for organizing ($percentage% of notes)",
                        confidence = 0.7f
                    )
                )
            }
        }

        return patterns
    }

    /**
     * Save a learned pattern as a memory, checking for duplicates.
     * Returns true if new memory was created, false if updated.
     */
    private suspend fun savePatternAsMemory(pattern: LearnedPattern): Boolean {
        val type = when (pattern.type.lowercase()) {
            "preference" -> MemoryType.PREFERENCE
            "pattern" -> MemoryType.PATTERN
            "style" -> MemoryType.STYLE
            "fact" -> MemoryType.FACT
            else -> MemoryType.PATTERN
        }

        // Check if similar memory exists
        val existing = aiMemoryDao.searchMemories(pattern.insight.take(30))
            .firstOrNull()

        return if (existing != null) {
            // Update existing memory confidence (reinforce)
            val newConfidence = ((existing.confidence + pattern.confidence) / 2).coerceIn(0.1f, 1.0f)
            aiMemoryDao.updateConfidence(existing.id, newConfidence)
            aiMemoryDao.incrementUsage(existing.id)
            false  // Updated
        } else {
            // Create new memory
            val memory = AIMemory(
                type = type,
                content = pattern.insight,
                confidence = pattern.confidence,
                source = "Automatic analysis of note patterns"
            )
            aiMemoryDao.insertMemory(memory)
            true  // Created
        }
    }

    override fun toString(): String {
        return "LearnFromNotesTool - Analyze notes to learn user preferences and patterns"
    }
}
