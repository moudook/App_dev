package com.example.smarty.features.chat.domain

import android.util.Log

/**
 * Confidence-based intent classification system.
 * Replaces brittle first-match regex with weighted scoring algorithm.
 *
 * ARCHITECTURE: Multi-factor scoring (pattern + keywords + context + history)
 * THRESHOLD: 0.7f minimum confidence for FAST-PATH, else REASONING_REQUIRED
 */
class IntentClassifier {
    companion object {
        private const val TAG = "IntentClassifier"
        private const val CONFIDENCE_THRESHOLD = 0.7f
        private const val PATTERN_WEIGHT = 0.4f
        private const val KEYWORD_WEIGHT = 0.3f
        private const val CONTEXT_WEIGHT = 0.2f
        private const val HISTORY_WEIGHT = 0.1f
    }

    /**
     * Classify user query with confidence scoring.
     * @return IntentType with confidence score for debugging
     */
    fun classify(
        query: String,
        context: ClassificationContext? = null,
    ): ClassificationResult {
        val normalizedQuery = query.lowercase().trim()
        val scores = mutableMapOf<IntentType, Float>()

        // Factor 1: Pattern matching (structural regex)
        val patternScores = calculatePatternScores(normalizedQuery)
        patternScores.forEach { (intent, score) ->
            scores.merge(intent, score * PATTERN_WEIGHT, Float::plus)
        }

        // Factor 2: Keyword density (semantic hints)
        val keywordScores = calculateKeywordScores(normalizedQuery)
        keywordScores.forEach { (intent, score) ->
            scores.merge(intent, score * KEYWORD_WEIGHT, Float::plus)
        }

        // Factor 3: Context signals (time, location, previous actions)
        context?.let { ctx ->
            val contextScores = calculateContextScores(ctx)
            contextScores.forEach { (intent, score) ->
                scores.merge(intent, score * CONTEXT_WEIGHT, Float::plus)
            }

            // Factor 4: History bias (user habits)
            val historyScores = calculateHistoryScores(ctx.recentIntents)
            historyScores.forEach { (intent, score) ->
                scores.merge(intent, score * HISTORY_WEIGHT, Float::plus)
            }
        }

        // Select best intent or fall back to reasoning
        val bestIntent = scores.maxByOrNull { it.value }
        val finalIntent =
            bestIntent?.takeIf { it.value >= CONFIDENCE_THRESHOLD }?.key
                ?: IntentType.REASONING_REQUIRED

        val confidence = bestIntent?.value ?: 0f

        Log.d(TAG, "Classified '${query.take(30)}...' as $finalIntent (confidence: ${"%.2f".format(confidence)})")

        return ClassificationResult(finalIntent, confidence, scores.toMap())
    }

    private fun calculatePatternScores(query: String): Map<IntentType, Float> {
        val scores = mutableMapOf<IntentType, Float>()

        // Media control patterns
        if (Regex("""(play|pause|stop|skip|next|previous|resume)\s+(music|song|track|audio|video|media)""").find(query) != null) {
            scores[IntentType.MEDIA_CONTROL] = 1.0f
        } else if (Regex("""^(play|pause|stop|skip|next|resume)$""").find(query) != null) {
            scores[IntentType.MEDIA_CONTROL] = 0.9f
        }

        // Time action patterns
        if (Regex("""(set|create|start)\s+(a\s+)?(timer|alarm|reminder)\s+(for\s+)?\d+""").find(query) != null) {
            scores[IntentType.TIME_ACTION] = 1.0f
        } else if (Regex("""(wake|remind)\s+me\s+.*\s+(at|in|after)""").find(query) != null) {
            scores[IntentType.TIME_ACTION] = 0.85f
        }

        // App launch patterns
        if (Regex("""(open|launch|start)\s+(app|application)?\s*\w+""").find(query) != null) {
            scores[IntentType.APP_LAUNCH] = 0.9f
        }

        // Device control patterns
        if (Regex("""(turn|toggle|switch)\s+(on|off)\s+\w+""").find(query) != null) {
            scores[IntentType.DEVICE_TOGGLE] = 0.9f
        } else if (Regex("""(enable|disable)\s+\w+""").find(query) != null) {
            scores[IntentType.DEVICE_TOGGLE] = 0.85f
        }

        // Navigation patterns
        if (Regex("""(go\s+to|navigate\s+to|open)\s+(screen|page|tab)?\s*\w+""").find(query) != null) {
            scores[IntentType.NAVIGATION] = 0.85f
        }

        // Simple query patterns (single fact lookup)
        if (Regex("""(what|how|when|where|who)\s+(is|are|was|were|time|date|weather)""").find(query) != null &&
            query.length < 50
        ) {
            scores[IntentType.SIMPLE_QUERY] = 0.75f
        }

        return scores
    }

    private fun calculateKeywordScores(query: String): Map<IntentType, Float> {
        val scores = mutableMapOf<IntentType, Float>()
        val words = query.split(Regex("""\s+"""))

        // Media keywords
        val mediaKeywords = setOf("music", "song", "track", "playlist", "album", "artist", "volume", "mute")
        val mediaHits = words.count { it in mediaKeywords }
        if (mediaHits > 0) scores[IntentType.MEDIA_CONTROL] = (mediaHits * 0.3f).coerceAtMost(1.0f)

        // Time keywords
        val timeKeywords = setOf("timer", "alarm", "reminder", "minute", "hour", "second", "am", "pm", "oclock")
        val timeHits = words.count { it in timeKeywords }
        if (timeHits > 0) scores[IntentType.TIME_ACTION] = (timeHits * 0.3f).coerceAtMost(1.0f)

        // App keywords
        val appKeywords = setOf("open", "launch", "start", "app", "application", "youtube", "spotify", "maps")
        val appHits = words.count { it in appKeywords }
        if (appHits > 0) scores[IntentType.APP_LAUNCH] = (appHits * 0.25f).coerceAtMost(1.0f)

        // Device keywords
        val deviceKeywords = setOf("wifi", "bluetooth", "flashlight", "brightness", "volume", "airplane", "mode")
        val deviceHits = words.count { it in deviceKeywords }
        if (deviceHits > 0) scores[IntentType.DEVICE_TOGGLE] = (deviceHits * 0.3f).coerceAtMost(1.0f)

        // Complex reasoning indicators (reduce simple intent scores)
        val complexKeywords = setOf("why", "explain", "analyze", "compare", "research", "find", "search", "details")
        val complexHits = words.count { it in complexKeywords }
        if (complexHits >= 2) {
            // Boost REASONING_REQUIRED by penalizing simple intents
            scores[IntentType.REASONING_REQUIRED] = 0.5f
        }

        return scores
    }

    private fun calculateContextScores(context: ClassificationContext): Map<IntentType, Float> {
        val scores = mutableMapOf<IntentType, Float>()

        // Time-based context
        context.currentHour?.let { hour ->
            if (hour in 6..9) {
                // Morning: more likely alarm/timer related
                scores[IntentType.TIME_ACTION] = 0.2f
            } else if (hour in 22..23 || hour in 0..6) {
                // Night: more likely media control
                scores[IntentType.MEDIA_CONTROL] = 0.15f
            }
        }

        // Screen context
        context.currentScreen?.let { screen ->
            when (screen) {
                "music_player", "spotify", "audio" -> scores[IntentType.MEDIA_CONTROL] = 0.3f
                "clock", "alarm", "timer" -> scores[IntentType.TIME_ACTION] = 0.3f
                "settings" -> scores[IntentType.DEVICE_TOGGLE] = 0.25f
                else -> {}
            }
        }

        return scores
    }

    private fun calculateHistoryScores(recentIntents: List<IntentType>): Map<IntentType, Float> {
        val scores = mutableMapOf<IntentType, Float>()

        // Boost intents user frequently uses (last 5 interactions)
        recentIntents.take(5).groupingBy { it }.eachCount().forEach { (intent, count) ->
            scores[intent] = (count * 0.1f).coerceAtMost(0.3f)
        }

        return scores
    }

    data class ClassificationResult(
        val intent: IntentType,
        val confidence: Float,
        val allScores: Map<IntentType, Float>,
    )

    data class ClassificationContext(
        val currentHour: Int? = null,
        val currentScreen: String? = null,
        val recentIntents: List<IntentType> = emptyList(),
    )
}

enum class IntentType {
    MEDIA_CONTROL, // Local audio playback control
    TIME_ACTION, // Timer/Alarm/Reminder
    APP_LAUNCH, // Launch applications
    DEVICE_TOGGLE, // Enable/disable settings (wifi, bluetooth, etc)
    NAVIGATION, // Navigate to screens
    SIMPLE_QUERY, // Single fact lookup (< 50 chars, simple structure)
    REASONING_REQUIRED, // Complex queries requiring server-side AI
}
