package com.example.smarty.server.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class UserTrait(
    val id: String,
    val trait: String,
    val category: String,
    val confidence: Double,
    val evidence: List<String>,
    val observedAt: Long,
    val lastReinforced: Long = System.currentTimeMillis()
)

@Serializable
data class BehaviorPattern(
    val id: String,
    val pattern: String,
    val frequency: Int,
    val contexts: List<String>,
    val predictions: List<String>,
    val firstObserved: Long,
    val lastObserved: Long
)

@Serializable
data class UserPrediction(
    val prediction: String,
    val confidence: Double,
    val reasoning: String,
    val basedOn: List<String>
)

class PsychologicalModel {
    private val logger = LoggerFactory.getLogger(PsychologicalModel::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    
    private val traits = ConcurrentHashMap<String, UserTrait>()
    private val patterns = ConcurrentHashMap<String, BehaviorPattern>()
    private val interactionHistory = mutableListOf<String>()
    private val preferences = mutableMapOf<String, String>()
    private val dislikes = mutableMapOf<String, String>()
    private val goals = mutableMapOf<String, String>()
    private val fears = mutableMapOf<String, String>()
    
    fun observeTrait(
        trait: String,
        category: String,
        evidence: String,
        confidence: Double = 0.7
    ): String {
        val traitId = "trait_${category}_${trait.hashCode()}"
        
        val existing = traits[traitId]
        if (existing != null) {
            val newEvidence = (existing.evidence + evidence).distinct().take(10)
            val newConfidence = minOf(1.0, existing.confidence + 0.1)
            
            traits[traitId] = existing.copy(
                evidence = newEvidence,
                confidence = newConfidence,
                lastReinforced = System.currentTimeMillis()
            )
        } else {
            traits[traitId] = UserTrait(
                id = traitId,
                trait = trait,
                category = category,
                confidence = confidence,
                evidence = listOf(evidence),
                observedAt = System.currentTimeMillis()
            )
        }
        
        logger.info("Observed trait: $trait in $category (confidence: ${traits[traitId]?.confidence})")
        return traitId
    }
    
    fun observePattern(
        pattern: String,
        context: String,
        prediction: String
    ): String {
        val patternId = "pattern_${pattern.hashCode()}"
        
        val existing = patterns[patternId]
        if (existing != null) {
            val newContexts = (existing.contexts + context).distinct().take(10)
            val newPredictions = (existing.predictions + prediction).distinct().take(5)
            
            patterns[patternId] = existing.copy(
                frequency = existing.frequency + 1,
                contexts = newContexts,
                predictions = newPredictions,
                lastObserved = System.currentTimeMillis()
            )
        } else {
            patterns[patternId] = BehaviorPattern(
                id = patternId,
                pattern = pattern,
                frequency = 1,
                contexts = listOf(context),
                predictions = listOf(prediction),
                firstObserved = System.currentTimeMillis(),
                lastObserved = System.currentTimeMillis()
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
        interactionHistory.add(content)
        if (interactionHistory.size > 100) {
            interactionHistory.removeAt(0)
        }
        
        analyzeForPatterns(content)
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
                val topPattern = relevantPatterns.maxByOrNull { it.frequency }
                val pred = topPattern?.predictions?.firstOrNull() ?: "Pattern observed but no specific prediction"
                UserPrediction(
                    prediction = pred,
                    confidence = minOf(0.9, 0.5 + (topPattern?.frequency ?: 0) * 0.1),
                    reasoning = "Based on ${topPattern?.frequency ?: 0} observations of similar pattern",
                    basedOn = topPattern?.contexts?.take(3) ?: emptyList()
                )
            }
            relevantTraits.isNotEmpty() -> {
                val topTrait = relevantTraits.maxByOrNull { it.confidence }
                UserPrediction(
                    prediction = "User likely ${topTrait?.trait ?: "unknown"}",
                    confidence = topTrait?.confidence ?: 0.5,
                    reasoning = "Based on observed trait with ${topTrait?.evidence?.size ?: 0} pieces of evidence",
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
        
        val prediction = when {
            activeGoals.isNotEmpty() -> {
                val goal = activeGoals.first()
                UserPrediction(
                    prediction = "User may want to make progress on: ${goal.key}",
                    confidence = 0.6,
                    reasoning = "User has active goal: ${goal.value}",
                    basedOn = listOf(goal.key)
                )
            }
            recentInteractions.any { it.contains("later", ignoreCase = true) } -> {
                UserPrediction(
                    prediction = "User may be procrastinating on a task",
                    confidence = 0.5,
                    reasoning = "Recent use of 'later' suggests deferred action",
                    basedOn = recentInteractions.filter { it.contains("later", ignoreCase = true) }
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
    
    fun formatProfile(): String {
        return buildString {
            appendLine("[User Profile]")
            appendLine("─".repeat(50))
            
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
