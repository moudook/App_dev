package com.example.smarty.agent.tools.consolidated

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.example.smarty.data.local.AIMemoryDao
import com.example.smarty.data.model.AIMemory
import com.example.smarty.data.model.Category
import com.example.smarty.data.model.MemoryType
import com.example.smarty.data.model.Note
import com.example.smarty.util.PrivacyGuard
import com.example.smarty.agent.tools.memory.LearnedPattern // Import this to reuse if possible, or redefine locally
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.util.Calendar

@Serializable
data class CognitiveCoreArgs(
    @property:LLMDescription("The mode: 'store' (add memory), 'retrieve' (search memories), 'analyze' (user patterns), 'learn' (process notes)")
    val mode: String,
    @property:LLMDescription("Data to store, or JSON params for analyze/learn")
    val data: String? = null,
    @property:LLMDescription("Scope/Type: 'user_preference', 'fact', 'pattern'")
    val scope: String? = null,
    @property:LLMDescription("Search query for retrieval")
    val query: String? = null
)

@Serializable
data class CognitiveResult(
    val success: Boolean,
    val message: String,
    val data: String? = null
) {
    override fun toString(): String {
        return "{success:$success|message:$message|data:${data ?: "null"}}"
    }
}

// Redefine LearnedPattern locally if needed or reuse. 
// Since we are consolidating, I will define it locally to be self-contained within this tool's context or logic.
@Serializable
data class CognitivePattern(
    val type: String,
    val insight: String,
    val confidence: Float
)

class CognitiveCoreTool(
    private val aiMemoryDao: AIMemoryDao,
    private val getActiveNotes: () -> List<Note>,
    private val getCategories: () -> List<Category>,
    private val markNoteAsAnalyzed: suspend (String) -> Unit,
    private val onStatusUpdate: (String) -> Unit
) : Tool<CognitiveCoreArgs, CognitiveResult>(
    argsSerializer = CognitiveCoreArgs.serializer(),
    resultSerializer = CognitiveResult.serializer(),
    name = "cognitive_core",
    description = """
        The long-term memory and pattern recognition engine.
        
        MODES:
        - store: Save a fact or preference. usage: mode="store", data="User likes blue", scope="preference"
        - retrieve: Search memories. usage: mode="retrieve", query="color"
        - analyze: Analyze user patterns (stats, habits). usage: mode="analyze"
        - learn: Process recent notes to learn patterns. usage: mode="learn"
    """.trimIndent()
) {
    private val cognitiveJson = Json { encodeDefaults = false }

    override suspend fun execute(args: CognitiveCoreArgs): CognitiveResult {
        return try {
            when (args.mode) {
                "store" -> {
                    onStatusUpdate("Saving memory...")
                    storeMemory(args)
                }
                "retrieve" -> {
                    onStatusUpdate("Recalling info...")
                    retrieveMemories(args)
                }
                "analyze" -> {
                    onStatusUpdate("Analyzing habits...")
                    analyzePatterns(args)
                }
                "learn" -> {
                    onStatusUpdate("Learning from notes...")
                    learnFromNotes(args)
                }
                else -> CognitiveResult(false, "Unknown mode: ${args.mode}")
            }
        } catch (e: Exception) {
            CognitiveResult(false, "Error: ${e.message}")
        }
    }

    private suspend fun storeMemory(args: CognitiveCoreArgs): CognitiveResult {
        val content = args.data ?: return CognitiveResult(false, "Data required for store mode")
        val scope = args.scope ?: "fact"
        
        val type = when (scope.lowercase()) {
            "preference", "user_preference" -> MemoryType.PREFERENCE
            "pattern" -> MemoryType.PATTERN
            "style" -> MemoryType.STYLE
            else -> MemoryType.FACT
        }

        // Simple duplicate check
        if (aiMemoryDao.memoryExists(content, type)) {
            return CognitiveResult(false, "Memory already exists")
        }

        val memory = AIMemory(
            type = type,
            content = content,
            confidence = 1.0f,
            source = "User interaction"
        )
        aiMemoryDao.insertMemory(memory)
        return CognitiveResult(true, "Memory stored: $content")
    }

    private suspend fun retrieveMemories(args: CognitiveCoreArgs): CognitiveResult {
        val query = args.query
        val memories = if (query.isNullOrBlank()) {
            aiMemoryDao.getRecentMemories(10)
        } else {
            aiMemoryDao.searchMemories(query)
        }
        
        val results = memories.map { mapOf("type" to it.type.name, "content" to it.content) }
        return CognitiveResult(true, "Found ${memories.size} memories", cognitiveJson.encodeToString(results))
    }

    // ═══════════════════════════════════════════════════════════════
    // PATTERN ANALYSIS (Restored from UserPatternsTool)
    // ═══════════════════════════════════════════════════════════════
    private suspend fun analyzePatterns(args: CognitiveCoreArgs): CognitiveResult {
        val allNotes = getActiveNotes()
        val visibleNotes = PrivacyGuard.getAiVisibleNotes(allNotes)
        val categories = getCategories()
        
        if (visibleNotes.isEmpty()) return CognitiveResult(true, "No notes to analyze")

        val now = System.currentTimeMillis()
        val oneWeekAgo = now - 7 * 24 * 60 * 60 * 1000L
        val twoWeeksAgo = now - 14 * 24 * 60 * 60 * 1000L
        
        // Activity analysis
        val recentNotes = visibleNotes.filter { it.createdAt >= oneWeekAgo }
        val lastWeekNotes = visibleNotes.filter { it.createdAt >= twoWeeksAgo && it.createdAt < oneWeekAgo }
        
        val trend = when {
            recentNotes.size > lastWeekNotes.size * 1.2 -> "increasing"
            recentNotes.size < lastWeekNotes.size * 0.8 -> "decreasing"
            else -> "stable"
        }

        // Top category
        val categoryGroups = visibleNotes.groupBy { it.categoryId }
        val topCategory = categoryGroups.maxByOrNull { it.value.size }
            ?.let { categories.find { cat -> cat.id == it.key }?.name } ?: "None"

        // Topic analysis (simple word frequency)
        val stopWords = setOf("the", "and", "to", "of", "a", "in", "for", "is", "on", "that", "by", "this", "with", "it", "as", "be", "are", "at", "note", "notes")
        val wordFreq = visibleNotes.flatMap { 
            (it.title + " " + (it.content ?: "")).lowercase().split(Regex("\\W+")) 
        }
        .filter { it.length > 3 && it !in stopWords }
        .groupingBy { it }
        .eachCount()
        
        val topTopics = wordFreq.entries.sortedByDescending { it.value }.take(3).map { it.key }

        return CognitiveResult(true, "Analysis complete", cognitiveJson.encodeToString(mapOf(
            "total_notes" to visibleNotes.size,
            "recent_activity" to "$trend (${recentNotes.size} notes this week)",
            "top_category" to topCategory,
            "common_topics" to topTopics
        )))
    }

    // ═══════════════════════════════════════════════════════════════
    // LEARNING LOGIC (Restored from LearnFromNotesTool)
    // ═══════════════════════════════════════════════════════════════
    private suspend fun learnFromNotes(args: CognitiveCoreArgs): CognitiveResult {
        val allNotes = getActiveNotes()
        val visibleNotes = PrivacyGuard.getAiVisibleNotes(allNotes)
        
        // Analyze up to 20 recent notes
        val notesToAnalyze = visibleNotes
            .sortedByDescending { it.updatedAt }
            .take(20)
            
        if (notesToAnalyze.isEmpty()) {
            return CognitiveResult(true, "No notes available to learn from.")
        }
        
        val patterns = mutableListOf<CognitivePattern>()
        var memoriesCreated = 0
        
        for (note in notesToAnalyze) {
            val content = "${note.title} ${note.content ?: ""}".lowercase()
            
            // 1. Extract travel info
            extractTravelInformation(content).forEach { insight ->
                val pattern = CognitivePattern("fact", insight, 0.9f)
                if (savePatternAsMemory(pattern)) memoriesCreated++
                patterns.add(pattern)
            }
            
            // 2. Extract learning info
            extractLearningInformation(content, note.title).forEach { insight ->
                val pattern = CognitivePattern("pattern", insight, 0.85f)
                if (savePatternAsMemory(pattern)) memoriesCreated++
                patterns.add(pattern)
            }
            
            // 3. Extract other patterns
            extractOtherPatterns(content).forEach { insight ->
                val pattern = CognitivePattern("preference", insight, 0.8f)
                if (savePatternAsMemory(pattern)) memoriesCreated++
                patterns.add(pattern)
            }
            
            // Mark as analyzed (best effort)
            try { markNoteAsAnalyzed(note.id) } catch (e: Exception) {}
        }
        
        // If no specific patterns found, do general style analysis on the batch
        if (patterns.isEmpty()) {
             analyzeContentStyle(notesToAnalyze).forEach { insight ->
                 val pattern = CognitivePattern("style", insight, 0.7f)
                 if (savePatternAsMemory(pattern)) memoriesCreated++
                 patterns.add(pattern)
             }
        }

        return CognitiveResult(
            true, 
            "Analyzed ${notesToAnalyze.size} notes. Found ${patterns.size} insights.", 
            cognitiveJson.encodeToString(patterns)
        )
    }

    private suspend fun savePatternAsMemory(pattern: CognitivePattern): Boolean {
        val type = when (pattern.type.lowercase()) {
            "preference" -> MemoryType.PREFERENCE
            "pattern" -> MemoryType.PATTERN
            "style" -> MemoryType.STYLE
            else -> MemoryType.FACT
        }

        // Check if similar memory exists
        val existing = aiMemoryDao.searchMemories(pattern.insight.take(30)).firstOrNull()

        return if (existing != null) {
            val newConfidence = ((existing.confidence + pattern.confidence) / 2).coerceIn(0.1f, 1.0f)
            aiMemoryDao.updateConfidence(existing.id, newConfidence)
            aiMemoryDao.incrementUsage(existing.id)
            false 
        } else {
            val memory = AIMemory(
                type = type,
                content = pattern.insight,
                confidence = pattern.confidence,
                source = "Automatic analysis"
            )
            aiMemoryDao.insertMemory(memory)
            true
        }
    }

    // --- Helper Extraction Methods ---

    private fun extractTravelInformation(content: String): List<String> {
        val patterns = mutableListOf<String>()
        val travelKeywords = listOf("travel", "trip", "flight", "booking", "vacation")
        if (travelKeywords.any { content.contains(it) }) {
            val locationPattern = Regex("(to|visit) \\s+([a-z\\s]+?)(?:\\s+(?:on|at)|\\s+|\$)")
            locationPattern.findAll(content).forEach {
                val loc = it.groupValues[2].trim()
                if (loc.length > 3) patterns.add("User is traveling to $loc")
            }
        }
        return patterns.distinct()
    }

    private fun extractLearningInformation(content: String, title: String): List<String> {
        val patterns = mutableListOf<String>()
        val keywords = listOf("learning", "study", "course", "book", "reading")
        if (keywords.any { content.contains(it) || title.lowercase().contains(it) }) {
            val topicPattern = Regex("(?:learning|studying|reading)\\s+(?:about\\s+)?([a-z\\s\\d]+)")
            topicPattern.findAll(content).forEach {
                val topic = it.groupValues[1].trim()
                if (topic.length > 3 && !containsPersonalInfo(topic)) {
                    patterns.add("User is interested in $topic")
                }
            }
        }
        return patterns.distinct()
    }

    private fun extractOtherPatterns(content: String): List<String> {
        val patterns = mutableListOf<String>()
        val preferencePattern = Regex("(?:likes|prefers|enjoys)\\s+([a-z\\s]+)")
        preferencePattern.findAll(content).forEach {
            val pref = it.groupValues[1].trim()
            if (pref.length > 3) patterns.add("User prefers $pref")
        }
        return patterns.distinct()
    }

    private fun analyzeContentStyle(notes: List<Note>): List<String> {
        val insights = mutableListOf<String>()
        val bulletUsers = notes.count { note ->
            (note.content ?: "").contains(Regex("^\\s*[-•*]\\s", RegexOption.MULTILINE))
        }
        if (bulletUsers > notes.size / 2) insights.add("User prefers bullet points/lists")
        
        val avgLength = notes.mapNotNull { it.content?.length }.average()
        if (avgLength < 100) insights.add("User prefers short, concise notes")
        else if (avgLength > 500) insights.add("User prefers detailed, comprehensive notes")
        
        return insights
    }

    private fun containsPersonalInfo(text: String): Boolean {
        return text.contains(Regex("\\d{3}")) || // simple heuristic for numbers
               text.contains("@")
    }
}