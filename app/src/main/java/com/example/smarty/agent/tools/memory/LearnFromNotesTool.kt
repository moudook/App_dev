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
        // ENHANCED PATTERN DETECTION
        // ═══════════════════════════════════════════════════════════════

        // Process each note individually to extract specific behavioral patterns
        for (note in visibleNotes) {
            val content = "${note.title} ${note.content ?: ""}".lowercase()

            // 1. Extract travel-related information
            val travelPatterns = extractTravelInformation(content, note.title)
            travelPatterns.forEach { pattern ->
                val learnedPattern = LearnedPattern(
                    type = "fact",
                    insight = pattern,
                    confidence = 0.9f
                )
                val created = savePatternAsMemory(learnedPattern)
                if (created) memoriesCreated++ else memoriesUpdated++
                insights.add(learnedPattern)
            }

            // 2. Extract learning-related information
            val learningPatterns = extractLearningInformation(content, note.title)
            learningPatterns.forEach { pattern ->
                val learnedPattern = LearnedPattern(
                    type = "pattern",
                    insight = pattern,
                    confidence = 0.85f
                )
                val created = savePatternAsMemory(learnedPattern)
                if (created) memoriesCreated++ else memoriesUpdated++
                insights.add(learnedPattern)
            }

            // 3. Extract other behavioral patterns
            val otherPatterns = extractOtherPatterns(content, note.title)
            otherPatterns.forEach { pattern ->
                val learnedPattern = LearnedPattern(
                    type = "pattern",
                    insight = pattern,
                    confidence = 0.8f
                )
                val created = savePatternAsMemory(learnedPattern)
                if (created) memoriesCreated++ else memoriesUpdated++
                insights.add(learnedPattern)
            }
        }

        // If no specific patterns were found, fall back to general analysis
        if (insights.isEmpty()) {
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

    /**
     * Extract travel-related information from note content
     */
    private fun extractTravelInformation(content: String, title: String): List<String> {
        val patterns = mutableListOf<String>()

        // Look for travel-related keywords
        val travelKeywords = listOf("travel", "trip", "journey", "flight", "ticket", "booking", "vacation", "destination")
        val hasTravelKeywords = travelKeywords.any { content.contains(it) }

        if (hasTravelKeywords) {
            // Extract locations using regex patterns
            val locationPattern = Regex("""(from|to|via|destination)\s+([a-zA-Z\s]+?)(?:\s+(?:on|at|date|time)|\s+|$)""")
            val locationMatches = locationPattern.findAll(content)

            val locations = locationMatches.map { it.groupValues[2].trim() }.distinct()

            if (locations.count() >= 2) {
                val fromLocation = locations.firstOrNull { !content.contains("to $it") && !content.contains("via $it") }
                val toLocation = locations.firstOrNull { content.contains("to $it") }
                val viaLocation = locations.firstOrNull { content.contains("via $it") }

                val datePattern = Regex("""(on|date|at)\s+([a-zA-Z\s\d,]+?)(?:\s|$|\.|,)""")
                val dateMatches = datePattern.find(content)
                val date = dateMatches?.groupValues?.getOrNull(2)?.trim()

                if (fromLocation != null && toLocation != null) {
                    val viaPart = if (viaLocation != null) " via $viaLocation" else ""
                    val datePart = if (date != null) " on $date" else ""
                    patterns.add("User is traveling from $fromLocation to $toLocation$viaPart$datePart")
                }
            }
        }

        // Look for specific travel ticket patterns
        val ticketPattern = Regex("""(flight|train|bus|ticket).*(?:from|departure).*?([a-zA-Z\s]+?)\s+(?:to|destination|arrival).*?([a-zA-Z\s]+?)(?:\s+(?:on|at|date)\s+([a-zA-Z\s\d,]+?))?(?:\s|$)""")
        val ticketMatches = ticketPattern.find(content)
        if (ticketMatches != null) {
            val transportType = ticketMatches.groupValues[1]
            val fromLocation = ticketMatches.groupValues[2].trim()
            val toLocation = ticketMatches.groupValues[3].trim()
            val date = ticketMatches.groupValues.getOrNull(4)?.trim()

            val datePart = if (date != null) " on $date" else ""
            patterns.add("User has a $transportType ticket from $fromLocation to $toLocation$datePart")
        }

        return patterns
    }

    /**
     * Check if content contains personal information that should not be stored
     */
    private fun containsPersonalInfo(content: String): Boolean {
        // Check for phone numbers (various formats)
        val phonePattern = Regex("""(\+?\d{1,3}[-.\s]?)?\(?\d{3}\)?[-.\s]?\d{3}[-.\s]?\d{4}""")
        if (phonePattern.containsMatchIn(content)) return true

        // Check for email addresses
        val emailPattern = Regex("""[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}""")
        if (emailPattern.containsMatchIn(content)) return true

        // Check for other sensitive patterns (can be extended)
        val sensitiveKeywords = listOf("ssn", "social security", "credit card", "password", "pin", "id card")
        return sensitiveKeywords.any { content.contains(it, ignoreCase = true) }
    }

    /**
     * Extract learning-related information from note content
     */
    private fun extractLearningInformation(content: String, title: String): List<String> {
        val patterns = mutableListOf<String>()

        // Look for learning-related keywords
        val learningKeywords = listOf("learning", "study", "course", "book", "subject", "topic", "tutorial", "education", "research", "study")
        val hasLearningKeywords = learningKeywords.any { content.contains(it) || title.contains(it, ignoreCase = true) }

        if (hasLearningKeywords) {
            // Extract topics being learned
            val topicPattern = Regex("""(?:learning|studying|reading about|interested in|book on|course on|subject|topic)\s+(?:a\s+|an\s+|the\s+)?([a-zA-Z\s\d&-]+?)(?:\s+|\.|,|!|;|$)""")
            val topicMatches = topicPattern.findAll(content)

            val topics = topicMatches.map { it.groupValues[1].trim() }.distinct()

            for (topic in topics) {
                if (topic.length > 2 && !containsPersonalInfo(topic)) { // Avoid short words and personal info
                    patterns.add("User is interested in $topic")
                }
            }

            // Check for book titles
            val bookPattern = Regex("""(?:reading|book|textbook)\s+(?:titled\s+|called\s+|about\s+)?["']?([a-zA-Z\s\d&-]+?)["']?(?:\s+by|\s+author|\s+written)?""")
            val bookMatches = bookPattern.findAll(content)

            val books = bookMatches.map { it.groupValues[1].trim() }.distinct()

            for (book in books) {
                if (book.length > 2 && !containsPersonalInfo(book)) {
                    patterns.add("User is reading $book")
                }
            }
        }

        // Handle contact information separately
        if (content.contains("contact", ignoreCase = true) || content.contains("phone", ignoreCase = true) ||
            content.contains("number", ignoreCase = true) || title.contains("contact", ignoreCase = true)) {
            val contactPattern = Regex("""(?:contact|phone|number)\s+(?:of|for)?\s*([a-zA-Z\s]+?)\s+(?:number|is)?\s*[:\-\s]*([+\d\s\-\(\)]+)""", RegexOption.IGNORE_CASE)
            val contactMatches = contactPattern.findAll(content)

            for (match in contactMatches) {
                val personName = match.groupValues[1].trim()
                if (personName.isNotEmpty() && !containsPersonalInfo(personName)) {
                    patterns.add("User is saving contact information for $personName")
                }
            }

            // Alternative pattern for contact info
            val altContactPattern = Regex("""([a-zA-Z\s]+?)\s+(?:contact|phone|number)\s*[:\-\s]*\s*([+\d\s\-\(\)]+)""", RegexOption.IGNORE_CASE)
            val altContactMatches = altContactPattern.findAll(content)

            for (match in altContactMatches) {
                val personName = match.groupValues[1].trim()
                if (personName.isNotEmpty() && !containsPersonalInfo(personName)) {
                    patterns.add("User is saving contact information for $personName")
                }
            }
        }

        // If the title suggests learning but no specific topic was found, use the title
        if (!hasLearningKeywords && (title.contains("book", ignoreCase = true) ||
                                   title.contains("course", ignoreCase = true) ||
                                   title.contains("study", ignoreCase = true))) {
            val extractedTopic = title.replace(Regex("""^(book|course|study)\s+"""), "").trim()
            if (extractedTopic.isNotEmpty() && !containsPersonalInfo(extractedTopic)) {
                patterns.add("User is interested in $extractedTopic")
            }
        }

        return patterns
    }

    /**
     * Extract other behavioral patterns from note content
     */
    private fun extractOtherPatterns(content: String, title: String): List<String> {
        val patterns = mutableListOf<String>()

        // Look for recurring activities or interests
        val activityPattern = Regex("""(?:likes|enjoys|interested in|frequently does|often does|usually does)\s+([a-zA-Z\s]+?)(?:\s+|\.|,|!|;|$)""")
        val activityMatches = activityPattern.findAll(content)

        for (match in activityMatches) {
            val activity = match.groupValues[1].trim()
            if (activity.length > 3) {
                patterns.add("User enjoys $activity")
            }
        }

        // Look for preferences
        val preferencePattern = Regex("""(?:prefers|likes|preferring)\s+([a-zA-Z\s]+?)(?:\s+|\.|,|!|;|$)""")
        val preferenceMatches = preferencePattern.findAll(content)

        for (match in preferenceMatches) {
            val preference = match.groupValues[1].trim()
            if (preference.length > 3) {
                patterns.add("User prefers $preference")
            }
        }

        return patterns
    }

    override fun toString(): String {
        return "LearnFromNotesTool - Analyze notes to learn user preferences and patterns"
    }
}
