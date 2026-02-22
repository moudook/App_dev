package com.example.smarty.server.tools

import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*
import kotlin.random.Random

@Serializable
data class UserTrait(
    val id: String,
    val trait: String,
    val category: String,
    val confidence: Double,
    val evidence: List<String>,
    val observedAt: Long,
    val lastReinforced: Long = System.currentTimeMillis(),
    val stability: Double = 0.5,
    val bayesianPosterior: Double = 0.5
)

@Serializable
data class BehaviorPattern(
    val id: String,
    val pattern: String,
    val frequency: Int,
    val contexts: List<String>,
    val predictions: List<String>,
    val firstObserved: Long,
    val lastObserved: Long,
    val confidence: Double = 0.5,
    val temporalPattern: Map<Int, Int> = emptyMap()
)

@Serializable
data class UserPrediction(
    val prediction: String,
    val confidence: Double,
    val reasoning: String,
    val basedOn: List<String>
)

data class BigFiveProfile(
    val openness: Double = 0.5,
    val conscientiousness: Double = 0.5,
    val extraversion: Double = 0.5,
    val agreeableness: Double = 0.5,
    val neuroticism: Double = 0.5,
    val lastUpdated: Long = System.currentTimeMillis()
)

data class PersonalityEmbedding(
    val vector: DoubleArray,
    val timestamp: Long
)

data class SentimentAnalysis(
    val overall: Double,
    val joy: Double,
    val sadness: Double,
    val anger: Double,
    val fear: Double,
    val surprise: Double,
    val timestamp: Long
)

data class UserMoodState(
    val currentMood: Double,
    val volatility: Double,
    val dominantEmotion: String,
    val trend: MoodTrend,
    val lastUpdated: Long
)

enum class MoodTrend { IMPROVING, STABLE, DECLINING, FLUCTUATING }

class PsychologicalModel {
    private val logger = LoggerFactory.getLogger(PsychologicalModel::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    
    private val traits = ConcurrentHashMap<String, UserTrait>()
    private val patterns = ConcurrentHashMap<String, BehaviorPattern>()
    private val interactionHistory = mutableListOf<InteractionRecord>()
    private val preferences = mutableMapOf<String, String>()
    private val dislikes = mutableMapOf<String, String>()
    private val goals = mutableMapOf<String, String>()
    private val fears = mutableMapOf<String, String>()
    
    private var bigFiveProfile = BigFiveProfile()
    private var moodState = UserMoodState(0.5, 0.2, "neutral", MoodTrend.STABLE, System.currentTimeMillis())
    private val sentimentHistory = mutableListOf<SentimentAnalysis>()
    private val personalityEmbeddings = mutableListOf<PersonalityEmbedding>()
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val bayesianUpdater = BayesianTraitUpdater()
    
    private val embeddingDim = 32
    
    companion object {
        private const val MAX_INTERACTIONS = 500
        private const val TRAIT_DECAY = 0.995
    }
    
    init {
        startBackgroundAnalysis()
    }
    
    private fun startBackgroundAnalysis() {
        scope.launch {
            while (isActive) {
                delay(300000)
                updateBigFiveProfile()
                updateMoodState()
                decayOldTraits()
            }
        }
    }
    
    fun observeTrait(
        trait: String,
        category: String,
        evidence: String,
        confidence: Double = 0.7
    ): String {
        val traitId = "trait_${category}_${trait.hashCode()}"
        
        val prior = traits[traitId]?.bayesianPosterior ?: 0.5
        val posterior = bayesianUpdater.update(prior, confidence, 1)
        
        val existing = traits[traitId]
        if (existing != null) {
            val newEvidence = (existing.evidence + evidence).distinct().take(10)
            val newConfidence = minOf(1.0, existing.confidence + 0.05)
            val newStability = calculateStability(newEvidence.size, existing.stability)
            
            traits[traitId] = existing.copy(
                evidence = newEvidence,
                confidence = newConfidence,
                lastReinforced = System.currentTimeMillis(),
                stability = newStability,
                bayesianPosterior = posterior
            )
        } else {
            traits[traitId] = UserTrait(
                id = traitId,
                trait = trait,
                category = category,
                confidence = confidence,
                evidence = listOf(evidence),
                observedAt = System.currentTimeMillis(),
                bayesianPosterior = posterior,
                stability = 0.3
            )
        }
        
        updateBigFiveFromTrait(category, trait, posterior)
        
        logger.info("Observed trait: $trait in $category (confidence: ${traits[traitId]?.confidence}, posterior: ${"%.2f".format(posterior)})")
        return traitId
    }
    
    private fun calculateStability(evidenceCount: Int, currentStability: Double): Double {
        val evidenceWeight = minOf(1.0, evidenceCount / 10.0)
        return (currentStability * 0.7 + evidenceWeight * 0.3).coerceIn(0.0, 1.0)
    }
    
    private fun updateBigFiveFromTrait(category: String, trait: String, confidence: Double) {
        val delta = confidence * 0.05
        
        val (dimension, direction) = when (category) {
            "preference" -> when {
                trait.contains("creative", ignoreCase = true) || trait.contains("curious", ignoreCase = true) -> "openness" to 1
                trait.contains("organized", ignoreCase = true) || trait.contains("careful", ignoreCase = true) -> "conscientiousness" to 1
                trait.contains("social", ignoreCase = true) || trait.contains("talkative", ignoreCase = true) -> "extraversion" to 1
                trait.contains("kind", ignoreCase = true) || trait.contains("cooperative", ignoreCase = true) -> "agreeableness" to 1
                else -> null to 0
            }
            "emotional_state" -> when {
                trait.contains("stress", ignoreCase = true) || trait.contains("anxious", ignoreCase = true) -> "neuroticism" to 1
                trait.contains("happy", ignoreCase = true) || trait.contains("positive", ignoreCase = true) -> "neuroticism" to -1
                else -> null to 0
            }
            else -> null to 0
        }
        
        if (dimension != null && direction != 0) {
            when (dimension) {
                "openness" -> bigFiveProfile = bigFiveProfile.copy(
                    openness = (bigFiveProfile.openness + delta * direction).coerceIn(0.0, 1.0)
                )
                "conscientiousness" -> bigFiveProfile = bigFiveProfile.copy(
                    conscientiousness = (bigFiveProfile.conscientiousness + delta * direction).coerceIn(0.0, 1.0)
                )
                "extraversion" -> bigFiveProfile = bigFiveProfile.copy(
                    extraversion = (bigFiveProfile.extraversion + delta * direction).coerceIn(0.0, 1.0)
                )
                "agreeableness" -> bigFiveProfile = bigFiveProfile.copy(
                    agreeableness = (bigFiveProfile.agreeableness + delta * direction).coerceIn(0.0, 1.0)
                )
                "neuroticism" -> bigFiveProfile = bigFiveProfile.copy(
                    neuroticism = (bigFiveProfile.neuroticism + delta * direction).coerceIn(0.0, 1.0)
                )
            }
        }
    }
    
    private fun updateBigFiveProfile() {
        val traitInferences = mapOf(
            "openness" to traits.values.filter { 
                it.category == "preference" && (it.trait.contains("creative") || it.trait.contains("curious"))
            }.map { it.confidence }.average(),
            "conscientiousness" to traits.values.filter { 
                it.category == "preference" && (it.trait.contains("organized") || it.trait.contains("planned"))
            }.map { it.confidence }.average(),
            "extraversion" to traits.values.filter { 
                it.category == "preference" && (it.trait.contains("social") || it.trait.contains("talkative"))
            }.map { it.confidence }.average(),
            "agreeableness" to traits.values.filter { 
                it.category == "preference" && (it.trait.contains("kind") || it.trait.contains("cooperative"))
            }.map { it.confidence }.average(),
            "neuroticism" to traits.values.filter { 
                it.category == "emotional_state" && it.trait.contains("stress")
            }.map { it.confidence }.average()
        )
        
        for ((dimension, inferred) in traitInferences) {
            if (!inferred.isNaN()) {
                val current = when (dimension) {
                    "openness" -> bigFiveProfile.openness
                    "conscientiousness" -> bigFiveProfile.conscientiousness
                    "extraversion" -> bigFiveProfile.extraversion
                    "agreeableness" -> bigFiveProfile.agreeableness
                    "neuroticism" -> bigFiveProfile.neuroticism
                    else -> 0.5
                }
                val blended = current * 0.8 + inferred * 0.2
                
                when (dimension) {
                    "openness" -> bigFiveProfile = bigFiveProfile.copy(openness = blended)
                    "conscientiousness" -> bigFiveProfile = bigFiveProfile.copy(conscientiousness = blended)
                    "extraversion" -> bigFiveProfile = bigFiveProfile.copy(extraversion = blended)
                    "agreeableness" -> bigFiveProfile = bigFiveProfile.copy(agreeableness = blended)
                    "neuroticism" -> bigFiveProfile = bigFiveProfile.copy(neuroticism = blended)
                }
            }
        }
    }
    
    private fun updateMoodState() {
        if (sentimentHistory.size < 3) return
        
        val recent = sentimentHistory.takeLast(10)
        val currentMood = recent.last().overall
        val moods = recent.map { it.overall }
        
        val variance = moods.map { (it - moods.average()) * (it - moods.average()) }.average()
        val volatility = sqrt(variance)
        
        val trend = when {
            moods.size >= 5 -> {
                val firstHalf = moods.take(moods.size / 2).average()
                val secondHalf = moods.drop(moods.size / 2).average()
                when {
                    secondHalf - firstHalf > 0.1 -> MoodTrend.IMPROVING
                    firstHalf - secondHalf > 0.1 -> MoodTrend.DECLINING
                    volatility > 0.2 -> MoodTrend.FLUCTUATING
                    else -> MoodTrend.STABLE
                }
            }
            else -> MoodTrend.STABLE
        }
        
        val dominantEmotion = recent.last().let {
            val emotions = listOf("joy" to it.joy, "sadness" to it.sadness, 
                "anger" to it.anger, "fear" to it.fear, "surprise" to it.surprise)
            emotions.maxByOrNull { it.second }?.first ?: "neutral"
        }
        
        moodState = UserMoodState(
            currentMood = currentMood,
            volatility = volatility,
            dominantEmotion = dominantEmotion,
            trend = trend,
            lastUpdated = System.currentTimeMillis()
        )
    }
    
    private fun decayOldTraits() {
        traits.forEach { (id, trait) ->
            val age = System.currentTimeMillis() - trait.lastReinforced
            if (age > 86400000 * 7) {
                val decayFactor = TRAIT_DECAY
                traits[id] = trait.copy(confidence = trait.confidence * decayFactor)
            }
        }
    }
    
    fun observePattern(
        pattern: String,
        context: String,
        prediction: String
    ): String {
        val patternId = "pattern_${pattern.hashCode()}"
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        
        val existing = patterns[patternId]
        if (existing != null) {
            val newContexts = (existing.contexts + context).distinct().take(10)
            val newPredictions = (existing.predictions + prediction).distinct().take(5)
            val newTemporal = existing.temporalPattern.toMutableMap()
            newTemporal[hour] = (newTemporal[hour] ?: 0) + 1
            
            patterns[patternId] = existing.copy(
                frequency = existing.frequency + 1,
                contexts = newContexts,
                predictions = newPredictions,
                lastObserved = System.currentTimeMillis(),
                confidence = minOf(1.0, existing.confidence + 0.05),
                temporalPattern = newTemporal
            )
        } else {
            patterns[patternId] = BehaviorPattern(
                id = patternId,
                pattern = pattern,
                frequency = 1,
                contexts = listOf(context),
                predictions = listOf(prediction),
                firstObserved = System.currentTimeMillis(),
                lastObserved = System.currentTimeMillis(),
                temporalPattern = mapOf(hour to 1)
            )
        }
        
        return patternId
    }
    
    fun recordPreference(preference: String, value: String) {
        preferences[preference] = value
        observeTrait(preference, "preference", "User stated: $value", 0.9)
    }
    
    fun recordDislike(dislike: String, reason: String) {
        dislikes[dislike] = reason
        observeTrait(dislike, "dislike", "User dislikes: $reason", 0.9)
    }
    
    fun recordGoal(goal: String, details: String) {
        goals[goal] = details
        observeTrait(goal, "goal", "User goal: $details", 0.8)
    }
    
    fun recordFear(fear: String, context: String) {
        fears[fear] = context
        observeTrait(fear, "fear", "User concern: $context", 0.7)
    }
    
    fun recordInteraction(content: String) {
        val sentiment = analyzeSentiment(content)
        sentimentHistory.add(sentiment)
        
        if (sentimentHistory.size > 100) {
            sentimentHistory.removeAt(0)
        }
        
        val embedding = generatePersonalityEmbedding(content)
        personalityEmbeddings.add(embedding)
        if (personalityEmbeddings.size > 50) {
            personalityEmbeddings.removeAt(0)
        }
        
        val record = InteractionRecord(
            content = content,
            timestamp = System.currentTimeMillis(),
            sentiment = sentiment.overall
        )
        interactionHistory.add(record)
        
        if (interactionHistory.size > MAX_INTERACTIONS) {
            interactionHistory.removeAt(0)
        }
        
        analyzeForPatterns(content)
    }
    
    private fun generatePersonalityEmbedding(text: String): PersonalityEmbedding {
        val random = Random(text.hashCode().toLong())
        val vector = DoubleArray(embeddingDim) { random.nextDouble() }
        
        val normalized = sqrt(vector.sumOf { it * it })
        if (normalized > 0) {
            return PersonalityEmbedding(
                vector = DoubleArray(embeddingDim) { vector[it] / normalized },
                timestamp = System.currentTimeMillis()
            )
        }
        return PersonalityEmbedding(vector, System.currentTimeMillis())
    }
    
    private fun analyzeSentiment(text: String): SentimentAnalysis {
        val lower = text.lowercase()
        
        val joy = detectEmotion(lower, listOf("happy", "joy", "excited", "great", "wonderful", "love", "amazing"))
        val sadness = detectEmotion(lower, listOf("sad", "down", "depressed", "unhappy", "disappointed", "upset"))
        val anger = detectEmotion(lower, listOf("angry", "mad", "frustrated", "annoyed", "irritated"))
        val fear = detectEmotion(lower, listOf("afraid", "scared", "worried", "anxious", "nervous"))
        val surprise = detectEmotion(lower, listOf("surprised", "amazed", "shocked", "unexpected"))
        
        val overall = (joy - sadness + 0.5) / 1.5
        
        return SentimentAnalysis(
            overall = overall.coerceIn(-1.0, 1.0),
            joy = joy,
            sadness = sadness,
            anger = anger,
            fear = fear,
            surprise = surprise,
            timestamp = System.currentTimeMillis()
        )
    }
    
    private fun detectEmotion(text: String, keywords: List<String>): Double {
        val matches = keywords.count { text.contains(it) }
        return minOf(1.0, matches * 0.25)
    }
    
    private fun analyzeForPatterns(content: String) {
        val lower = content.lowercase()
        
        if (lower.contains("always") || lower.contains("every time")) {
            val patternMatch = Regex("always (.+?)(?:\\.|,|$)").find(lower)
            patternMatch?.let {
                observePattern(it.groupValues[1], "stated_always", "User will likely ${it.groupValues[1]}")
            }
        }
        
        if (lower.contains("never") || lower.contains("don't like")) {
            val patternMatch = Regex("never (.+?)(?:\\.|,|$)").find(lower)
            patternMatch?.let {
                recordDislike(it.groupValues[1], "User stated they never ${it.groupValues[1]}")
            }
        }
        
        if (lower.contains("want to") || lower.contains("goal")) {
            val goalMatch = Regex("want to (.+?)(?:\\.|,|$)").find(lower)
            goalMatch?.let {
                recordGoal(it.groupValues[1], "User expressed goal")
            }
        }
        
        if (lower.contains("stressed") || lower.contains("anxious") || lower.contains("worried")) {
            observeTrait("experiencing_stress", "emotional_state", content.take(100), 0.6)
        }
        
        if (lower.contains("excited") || lower.contains("happy") || lower.contains("great")) {
            observeTrait("positive_mood", "emotional_state", content.take(100), 0.6)
        }
    }
    
    fun predict(about: String): UserPrediction {
        val relevantTraits = traits.values.filter { 
            it.trait.contains(about, ignoreCase = true) || 
            it.category.contains(about, ignoreCase = true)
        }
        
        val relevantPatterns = patterns.values.filter {
            it.pattern.contains(about, ignoreCase = true) ||
            it.predictions.any { p -> p.contains(about, ignoreCase = true) }
        }
        
        val prediction = when {
            relevantPatterns.isNotEmpty() -> {
                val topPattern = relevantPatterns.maxByOrNull { it.frequency * it.confidence }
                val pred = topPattern?.predictions?.firstOrNull() ?: "Pattern observed but no specific prediction"
                UserPrediction(
                    prediction = pred,
                    confidence = minOf(0.9, 0.5 + (topPattern?.frequency ?: 0) * 0.1),
                    reasoning = "Based on ${topPattern?.frequency ?: 0} observations with ${"%.0f".format(topPattern?.confidence ?: 0.5 * 100)}% confidence",
                    basedOn = topPattern?.contexts?.take(3) ?: emptyList()
                )
            }
            relevantTraits.isNotEmpty() -> {
                val topTrait = relevantTraits.maxByOrNull { it.confidence * it.stability }
                UserPrediction(
                    prediction = "User likely ${topTrait?.trait ?: "unknown"}",
                    confidence = topTrait?.confidence ?: 0.5,
                    reasoning = "Based on ${topTrait?.evidence?.size ?: 0} observations with ${"%.0f".format(topTrait?.bayesianPosterior ?: 0.5 * 100)}% posterior probability",
                    basedOn = topTrait?.evidence?.take(3) ?: emptyList()
                )
            }
            else -> UserPrediction(
                prediction = "Insufficient data for prediction",
                confidence = 0.0,
                reasoning = "No relevant traits or patterns observed yet",
                basedOn = emptyList()
            )
        }
        
        return prediction
    }
    
    fun predictNextAction(): UserPrediction {
        val recentInteractions = interactionHistory.takeLast(5)
        val activeGoals = goals.entries.take(3)
        
        val timeOfDay = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val temporalPatterns = patterns.values
            .filter { it.temporalPattern.containsKey(timeOfDay) }
            .sortedByDescending { it.temporalPattern[timeOfDay] ?: 0 }
        
        val prediction = when {
            temporalPatterns.isNotEmpty() -> {
                val topPattern = temporalPatterns.first()
                UserPrediction(
                    prediction = "Based on time patterns, user may: ${topPattern.predictions.first()}",
                    confidence = 0.6,
                    reasoning = "This pattern occurs frequently at ${timeOfDay}:00",
                    basedOn = listOf(topPattern.pattern)
                )
            }
            activeGoals.isNotEmpty() -> {
                val goal = activeGoals.first()
                UserPrediction(
                    prediction = "User may want to make progress on: ${goal.key}",
                    confidence = 0.6,
                    reasoning = "User has active goal: ${goal.value}",
                    basedOn = listOf(goal.key)
                )
            }
            recentInteractions.any { it.content.contains("later", ignoreCase = true) } -> {
                UserPrediction(
                    prediction = "User may be procrastinating on a task",
                    confidence = 0.5,
                    reasoning = "Recent use of 'later' suggests deferred action",
                    basedOn = recentInteractions.filter { it.content.contains("later", ignoreCase = true) }.map { it.content }
                )
            }
            else -> UserPrediction(
                prediction = "No strong prediction available",
                confidence = 0.3,
                reasoning = "Need more interaction data",
                basedOn = emptyList()
            )
        }
        
        return prediction
    }
    
    fun getTraitsByCategory(category: String): List<UserTrait> {
        return traits.values
            .filter { it.category == category }
            .sortedByDescending { it.confidence }
    }
    
    fun getAllTraits(): List<UserTrait> {
        return traits.values.sortedByDescending { it.confidence }
    }
    
    fun getPatterns(): List<BehaviorPattern> {
        return patterns.values.sortedByDescending { it.frequency }
    }
    
    fun getPreferences(): Map<String, String> = preferences.toMap()
    
    fun getGoals(): Map<String, String> = goals.toMap()
    
    fun getBigFiveProfile(): BigFiveProfile = bigFiveProfile
    
    fun getMoodState(): UserMoodState = moodState
    
    fun formatProfile(): String {
        return buildString {
            appendLine("[User Profile]")
            appendLine("─".repeat(50))
            
            appendLine("\n[Big Five Personality]")
            appendLine("  Openness: ${"%.1f".format(bigFiveProfile.openness * 100)}%")
            appendLine("  Conscientiousness: ${"%.1f".format(bigFiveProfile.conscientiousness * 100)}%")
            appendLine("  Extraversion: ${"%.1f".format(bigFiveProfile.extraversion * 100)}%")
            appendLine("  Agreeableness: ${"%.1f".format(bigFiveProfile.agreeableness * 100)}%")
            appendLine("  Neuroticism: ${"%.1f".format(bigFiveProfile.neuroticism * 100)}%")
            
            appendLine("\n[Mood State]")
            appendLine("  Current: ${moodState.dominantEmotion} (${"%.1f".format(moodState.currentMood * 100)}%)")
            appendLine("  Volatility: ${"%.1f".format(moodState.volatility * 100)}%")
            appendLine("  Trend: ${moodState.trend}")
            
            if (preferences.isNotEmpty()) {
                appendLine("\n[Preferences]")
                preferences.entries.take(10).forEach { (k, v) ->
                    appendLine("  • $k: $v")
                }
            }
            
            if (dislikes.isNotEmpty()) {
                appendLine("\n[Dislikes]")
                dislikes.entries.take(5).forEach { (k, v) ->
                    appendLine("  • $k: $v")
                }
            }
            
            if (goals.isNotEmpty()) {
                appendLine("\n[Goals]")
                goals.entries.take(5).forEach { (k, v) ->
                    appendLine("  • $k: $v")
                }
            }
            
            val topTraits = traits.values.sortedByDescending { it.confidence }.take(10)
            if (topTraits.isNotEmpty()) {
                appendLine("\n[Observed Traits]")
                topTraits.forEach { t ->
                    appendLine("  • [${t.category}] ${t.trait} (${(t.confidence * 100).toInt()}%)")
                }
            }
            
            val topPatterns = patterns.values.sortedByDescending { it.frequency }.take(5)
            if (topPatterns.isNotEmpty()) {
                appendLine("\n[Patterns]")
                topPatterns.forEach { p ->
                    appendLine("  • ${p.pattern} (${p.frequency}x)")
                }
            }
        }
    }
    
    fun formatPrediction(prediction: UserPrediction): String {
        return buildString {
            appendLine("[Prediction]")
            appendLine("─".repeat(40))
            appendLine("Prediction: ${prediction.prediction}")
            appendLine("Confidence: ${(prediction.confidence * 100).toInt()}%")
            appendLine("Reasoning: ${prediction.reasoning}")
            if (prediction.basedOn.isNotEmpty()) {
                appendLine("Based on:")
                prediction.basedOn.forEach { appendLine("  • $it") }
            }
        }
    }
}

data class InteractionRecord(
    val content: String,
    val timestamp: Long,
    val sentiment: Double
)

class BayesianTraitUpdater {
    private val priorStrength = 1.0
    
    fun update(prior: Double, likelihood: Double, observations: Int): Double {
        val alpha = prior * priorStrength + likelihood * observations
        val beta = (1 - prior) * priorStrength + (1 - likelihood) * observations
        return alpha / (alpha + beta)
    }
}
