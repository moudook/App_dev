package com.example.smarty.agent.tools.memory

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.example.smarty.data.model.Category
import com.example.smarty.data.model.Note
import com.example.smarty.util.PrivacyGuard
import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import java.util.*

@Serializable
data class UserPatternsArgs(
    @property:LLMDescription("Analysis type: 'categories' (category usage), 'activity' (note-taking patterns), 'topics' (frequent topics), 'all' (comprehensive)")
    val analysisType: String = "all"
)

@Serializable
data class CategoryUsage(
    val name: String,
    val noteCount: Int,
    val recentNotes: Int,
    val percentageOfTotal: Float
)

@Serializable
data class ActivityPattern(
    val notesThisWeek: Int,
    val notesLastWeek: Int,
    val trend: String,
    val avgNotesPerDay: Float,
    val mostActiveDay: String?,
    val totalNotes: Int
)

@Serializable
data class TopicInsight(
    val topic: String,
    val frequency: Int,
    val recentMentions: Int
)

@Serializable
data class UserPatternsResult(
    val success: Boolean,
    val analysisType: String,
    val topCategories: List<CategoryUsage>,
    val activity: ActivityPattern?,
    val frequentTopics: List<TopicInsight>,
    val suggestions: List<String>,
    val summary: String
)

/**
 * Analyzes user's note-taking patterns to provide insights and proactive suggestions.
 *
 * Features:
 * - Category usage analysis
 * - Activity trends (this week vs last week)
 * - Frequent topic extraction
 * - Personalized suggestions based on patterns
 *
 * Privacy: Only analyzes AI-visible notes, respects privacy settings.
 */
class UserPatternsTool(
    private val getActiveNotes: () -> List<Note>,
    private val getCategories: () -> List<Category>
) : Tool<UserPatternsArgs, UserPatternsResult>() {

    override val argsSerializer: KSerializer<UserPatternsArgs> = UserPatternsArgs.serializer()
    override val resultSerializer: KSerializer<UserPatternsResult> = UserPatternsResult.serializer()

    override val name = "analyze_patterns"

    override val description = """
        Analyzes user's note-taking patterns to provide insights and personalized suggestions.
        Returns: top categories, activity trends, frequent topics, and actionable suggestions.
        Use to understand user habits, provide proactive help, or when user asks about their note organization.
        Analysis types: 'categories', 'activity', 'topics', 'all'.
    """.trimIndent()

    override suspend fun execute(args: UserPatternsArgs): UserPatternsResult {
        val allNotes = getActiveNotes()
        val visibleNotes = PrivacyGuard.getAiVisibleNotes(allNotes)
        val categories = getCategories()

        val analysisType = args.analysisType.lowercase()

        val now = System.currentTimeMillis()
        val oneWeekAgo = now - 7 * 24 * 60 * 60 * 1000L
        val twoWeeksAgo = now - 14 * 24 * 60 * 60 * 1000L

        // Category analysis
        val categoryUsage = if (analysisType in listOf("categories", "all")) {
            analyzeCategoryUsage(visibleNotes, categories, oneWeekAgo)
        } else emptyList()

        // Activity analysis
        val activity = if (analysisType in listOf("activity", "all")) {
            analyzeActivity(visibleNotes, oneWeekAgo, twoWeeksAgo)
        } else null

        // Topic analysis
        val topics = if (analysisType in listOf("topics", "all")) {
            analyzeTopics(visibleNotes, oneWeekAgo)
        } else emptyList()

        // Generate suggestions
        val suggestions = generateSuggestions(categoryUsage, activity, topics)

        // Build summary
        val summary = buildSummary(visibleNotes.size, categoryUsage, activity, topics)

        return UserPatternsResult(
            success = true,
            analysisType = analysisType,
            topCategories = categoryUsage,
            activity = activity,
            frequentTopics = topics,
            suggestions = suggestions,
            summary = summary
        )
    }

    private fun analyzeCategoryUsage(
        notes: List<Note>,
        categories: List<Category>,
        oneWeekAgo: Long
    ): List<CategoryUsage> {
        val totalNotes = notes.size.coerceAtLeast(1)

        return categories.map { cat ->
            val catNotes = notes.filter { it.categoryId == cat.id }
            val recentNotes = catNotes.filter { it.createdAt >= oneWeekAgo }

            CategoryUsage(
                name = cat.name,
                noteCount = catNotes.size,
                recentNotes = recentNotes.size,
                percentageOfTotal = (catNotes.size.toFloat() / totalNotes) * 100
            )
        }
            .filter { it.noteCount > 0 }
            .sortedByDescending { it.noteCount }
            .take(5)
    }

    private fun analyzeActivity(
        notes: List<Note>,
        oneWeekAgo: Long,
        twoWeeksAgo: Long
    ): ActivityPattern {
        val thisWeekNotes = notes.filter { it.createdAt >= oneWeekAgo }
        val lastWeekNotes = notes.filter { it.createdAt >= twoWeeksAgo && it.createdAt < oneWeekAgo }

        val trend = when {
            thisWeekNotes.size > lastWeekNotes.size * 1.2 -> "increasing"
            thisWeekNotes.size < lastWeekNotes.size * 0.8 -> "decreasing"
            else -> "stable"
        }

        // Find most active day
        val dayNames = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
        val dayOfWeekCounts = mutableMapOf<String, Int>()

        thisWeekNotes.forEach { note ->
            val cal = Calendar.getInstance().apply { timeInMillis = note.createdAt }
            val dayName = dayNames[cal.get(Calendar.DAY_OF_WEEK) - 1]
            dayOfWeekCounts[dayName] = (dayOfWeekCounts[dayName] ?: 0) + 1
        }

        return ActivityPattern(
            notesThisWeek = thisWeekNotes.size,
            notesLastWeek = lastWeekNotes.size,
            trend = trend,
            avgNotesPerDay = thisWeekNotes.size / 7f,
            mostActiveDay = dayOfWeekCounts.maxByOrNull { it.value }?.key,
            totalNotes = notes.size
        )
    }

    private fun analyzeTopics(notes: List<Note>, oneWeekAgo: Long): List<TopicInsight> {
        // Common stop words to filter out
        val stopWords = setOf(
            "the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for", "of", "is", "it",
            "this", "that", "with", "from", "by", "as", "be", "was", "were", "are", "been",
            "have", "has", "had", "do", "does", "did", "will", "would", "could", "should",
            "may", "might", "must", "can", "my", "your", "his", "her", "its", "our", "their",
            "i", "you", "he", "she", "we", "they", "what", "which", "who", "when", "where",
            "why", "how", "all", "each", "every", "both", "few", "more", "most", "other",
            "some", "such", "no", "not", "only", "own", "same", "so", "than", "too", "very",
            "just", "also", "now", "here", "there", "note", "notes", "todo", "new", "one"
        )

        val wordFrequency = mutableMapOf<String, Int>()
        val recentWordFrequency = mutableMapOf<String, Int>()

        notes.forEach { note ->
            val words = (note.title + " " + (note.summary ?: ""))
                .lowercase()
                .split(Regex("\\W+"))
                .filter { it.length > 3 && it !in stopWords }

            val isRecent = note.createdAt >= oneWeekAgo

            words.forEach { word ->
                wordFrequency[word] = (wordFrequency[word] ?: 0) + 1
                if (isRecent) {
                    recentWordFrequency[word] = (recentWordFrequency[word] ?: 0) + 1
                }
            }
        }

        return wordFrequency.entries
            .filter { it.value >= 2 } // At least 2 mentions
            .sortedByDescending { it.value }
            .take(10)
            .map { (word, freq) ->
                TopicInsight(
                    topic = word,
                    frequency = freq,
                    recentMentions = recentWordFrequency[word] ?: 0
                )
            }
    }

    private fun generateSuggestions(
        categories: List<CategoryUsage>,
        activity: ActivityPattern?,
        topics: List<TopicInsight>
    ): List<String> {
        val suggestions = mutableListOf<String>()

        // Activity-based suggestions
        activity?.let {
            when (it.trend) {
                "decreasing" -> suggestions.add(
                    "Your note activity has decreased. Would you like me to help capture some thoughts?"
                )
                "increasing" -> suggestions.add(
                    "Great momentum! You've created ${it.notesThisWeek} notes this week, up from ${it.notesLastWeek} last week."
                )
            }

            if (it.avgNotesPerDay < 0.5 && it.totalNotes > 5) {
                suggestions.add(
                    "You haven't been adding notes recently. Need help organizing your thoughts?"
                )
            }
        }

        // Category-based suggestions
        if (categories.isNotEmpty()) {
            val topCategory = categories.first()
            if (topCategory.recentNotes == 0 && topCategory.noteCount > 3) {
                suggestions.add(
                    "You haven't added to '${topCategory.name}' recently. Any updates?"
                )
            }

            // Check for imbalanced categories
            if (categories.size >= 2) {
                val topPercent = categories.first().percentageOfTotal
                if (topPercent > 60) {
                    suggestions.add(
                        "Most of your notes (${topPercent.toInt()}%) are in '${categories.first().name}'. Consider organizing into more categories?"
                    )
                }
            }
        }

        // Topic-based suggestions
        if (topics.isNotEmpty()) {
            val topTopic = topics.first()
            if (topTopic.frequency >= 5) {
                suggestions.add(
                    "You frequently write about '${topTopic.topic}'. Would you like me to summarize those notes?"
                )
            }

            // Trending topic
            val trendingTopic = topics.find { it.recentMentions >= 3 && it.recentMentions > it.frequency / 2 }
            if (trendingTopic != null && trendingTopic != topTopic) {
                suggestions.add(
                    "'${trendingTopic.topic}' is a recent focus. Need help researching or organizing notes about it?"
                )
            }
        }

        return suggestions.take(3) // Max 3 suggestions
    }

    private fun buildSummary(
        totalNotes: Int,
        categories: List<CategoryUsage>,
        activity: ActivityPattern?,
        topics: List<TopicInsight>
    ): String {
        return buildString {
            append("You have $totalNotes accessible notes")

            if (categories.isNotEmpty()) {
                append(" across ${categories.size} active categories")
                append(" (top: ${categories.first().name})")
            }

            activity?.let {
                append(". This week: ${it.notesThisWeek} notes")
                if (it.trend != "stable") {
                    append(" (${it.trend})")
                }
                it.mostActiveDay?.let { day ->
                    append(", most active on ${day}s")
                }
            }

            if (topics.isNotEmpty()) {
                append(". Top topics: ${topics.take(3).joinToString(", ") { it.topic }}")
            }

            append(".")
        }
    }

    override fun toString(): String {
        return "UserPatternsTool - Note-taking pattern analysis and suggestions"
    }
}
