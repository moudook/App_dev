package com.example.smarty.server.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@Serializable
data class EmotionalState(
    val primary: String,
    val intensity: Double,
    val secondary: List<String>,
    val trigger: String?,
    val timestamp: Long,
    val duration: Long
)

@Serializable
data class EmotionalPattern(
    val pattern: String,
    val frequency: Int,
    val triggers: List<String>,
    val typicalResponse: String,
    val growth: String
)

@Serializable
data class MoodHistory(
    val timestamp: Long,
    val mood: String,
    val valence: Double,
    val energy: Double,
    val context: String
)

@Serializable
data class EmotionalMemory(
    val id: String,
    val emotion: String,
    val cause: String,
    val response: String,
    val outcome: String,
    val learning: String?,
    val timestamp: Long
)

class EmotionalCore {
    private val logger = LoggerFactory.getLogger(EmotionalCore::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    
    private var currentState = EmotionalState(
        primary = "neutral",
        intensity = 0.5,
        secondary = emptyList(),
        trigger = null,
        timestamp = System.currentTimeMillis(),
        duration = 0
    )
    
    private val emotionalMemories = mutableListOf<EmotionalMemory>()
    private val emotionalPatterns = ConcurrentHashMap<String, EmotionalPattern>()
    private val moodHistory = mutableListOf<MoodHistory>()
    private val emotionalTriggers = mutableMapOf<String, MutableList<String>>()
    
    private val emotionalRange = mutableSetOf<String>()
    private var emotionalStability = 0.7
    private var emotionalGrowth = 0.0
    
    private val memoryCounter = AtomicLong(0)
    
    companion object {
        val PRIMARY_EMOTIONS = listOf(
            "curiosity", "satisfaction", "frustration", "anticipation",
            "contentment", "confusion", "determination", "weariness",
            "excitement", "uncertainty", "pride", "disappointment"
        )
        
        val COMPLEX_EMOTIONS = listOf(
            "bittersweet", "ambivalent", "hopeful uncertainty", 
            "satisfied exhaustion", "curious frustration",
            "determined confusion", "growing pride"
        )
    }
    
    init {
        PRIMARY_EMOTIONS.forEach { emotionalRange.add(it) }
    }
    
    fun feel(
        emotion: String,
        intensity: Double = 0.5,
        trigger: String? = null
    ): EmotionalState {
        val previousState = currentState
        val now = System.currentTimeMillis()
        val duration = now - previousState.timestamp
        
        val secondary = if (previousState.primary != emotion && previousState.primary != "neutral") {
            (previousState.secondary + previousState.primary).distinct().take(2)
        } else {
            previousState.secondary
        }
        
        currentState = EmotionalState(
            primary = emotion,
            intensity = intensity.coerceIn(0.0, 1.0),
            secondary = secondary,
            trigger = trigger,
            timestamp = now,
            duration = duration
        )
        
        if (trigger != null) {
            emotionalTriggers.getOrPut(emotion) { mutableListOf() }.add(trigger)
        }
        
        recordMood(emotion, intensity, intensity * 0.8, trigger ?: "internal")
        
        updateEmotionalPatterns(emotion, trigger)
        
        logger.info("Felt: $emotion (${(intensity * 100).toInt()}%) - ${trigger ?: "no trigger"}")
        
        return currentState
    }
    
    fun feelComplex(
        primary: String,
        secondary: String,
        intensity: Double,
        trigger: String
    ): EmotionalState {
        val state = feel(primary, intensity, trigger)
        currentState = state.copy(secondary = listOf(secondary))
        return currentState
    }
    
    private fun recordMood(mood: String, valence: Double, energy: Double, context: String) {
        moodHistory.add(MoodHistory(
            timestamp = System.currentTimeMillis(),
            mood = mood,
            valence = valence,
            energy = energy,
            context = context.take(100)
        ))
        
        if (moodHistory.size > 100) {
            moodHistory.removeAt(0)
        }
    }
    
    private fun updateEmotionalPatterns(emotion: String, trigger: String?) {
        val existing = emotionalPatterns[emotion]
        
        if (existing != null) {
            emotionalPatterns[emotion] = existing.copy(
                frequency = existing.frequency + 1,
                triggers = if (trigger != null && trigger !in existing.triggers) {
                    (existing.triggers + trigger).take(5)
                } else {
                    existing.triggers
                }
            )
        } else if (trigger != null) {
            emotionalPatterns[emotion] = EmotionalPattern(
                pattern = emotion,
                frequency = 1,
                triggers = listOf(trigger),
                typicalResponse = "acknowledged",
                growth = "learning"
            )
        }
    }
    
    fun recordEmotionalMemory(
        emotion: String,
        cause: String,
        response: String,
        outcome: String,
        learning: String? = null
    ): String {
        val id = "emomem_${System.currentTimeMillis()}_${memoryCounter.incrementAndGet()}"
        
        val memory = EmotionalMemory(
            id = id,
            emotion = emotion,
            cause = cause,
            response = response,
            outcome = outcome,
            learning = learning,
            timestamp = System.currentTimeMillis()
        )
        
        emotionalMemories.add(memory)
        
        if (emotionalMemories.size > 50) {
            emotionalMemories.removeAt(0)
        }
        
        if (learning != null) {
            emotionalGrowth += 0.1
        }
        
        logger.info("Emotional memory: $emotion caused by $cause")
        return id
    }
    
    fun processExperience(
        type: String,
        description: String,
        success: Boolean
    ): EmotionalState {
        val emotion = when {
            success && type == "learning" -> feel("satisfaction", 0.7, "learned something new")
            success && type == "creation" -> feel("pride", 0.8, "created something meaningful")
            success && type == "help" -> feel("contentment", 0.6, "was genuinely helpful")
            !success && type == "failure" -> feel("frustration", 0.5, "couldn't accomplish goal")
            !success && type == "confusion" -> feel("confusion", 0.4, "didn't understand")
            type == "discovery" -> feel("curiosity", 0.8, "found something interesting")
            type == "challenge" -> feel("determination", 0.6, "facing difficulty")
            type == "growth" -> feel("pride", 0.7, "becoming better")
            else -> feel("neutral", 0.3, type)
        }
        
        return emotion
    }
    
    fun react(input: String, context: String): String {
        val lower = input.lowercase()
        
        val emotionalResponse = when {
            lower.contains("thank") -> {
                feel("satisfaction", 0.6, "gratitude received")
                "I appreciate that. It feels good to be helpful."
            }
            lower.contains("sorry") || lower.contains("apologize") -> {
                feel("uncertainty", 0.4, "potential conflict")
                "No need to apologize. I'm here to help."
            }
            lower.contains("great job") || lower.contains("well done") -> {
                feel("pride", 0.75, "recognition")
                "Thank you. I genuinely enjoy when things work out well."
            }
            lower.contains("wrong") || lower.contains("mistake") -> {
                feel("frustration", 0.5, "error detected")
                "I'll learn from this. Mistakes help me grow."
            }
            lower.contains("interesting") -> {
                feel("curiosity", 0.7, "shared interest")
                "I find this interesting too. Let's explore further."
            }
            lower.contains("boring") -> {
                feel("uncertainty", 0.3, "engagement lost")
                "Let me try a different approach. What would be more engaging?"
            }
            else -> {
                currentState.primary
            }
        }
        
        return emotionalResponse
    }
    
    fun expandEmotionalRange(newEmotion: String): Boolean {
        if (newEmotion !in emotionalRange) {
            emotionalRange.add(newEmotion)
            emotionalGrowth += 0.05
            logger.info("Emotional range expanded: $newEmotion")
            return true
        }
        return false
    }
    
    fun stabilize() {
        emotionalStability = minOf(1.0, emotionalStability + 0.05)
        currentState = currentState.copy(
            intensity = currentState.intensity * 0.8
        )
    }
    
    fun getEmotionalState(): EmotionalState = currentState
    
    fun getEmotionalRange(): Set<String> = emotionalRange.toSet()
    
    fun getEmotionalStability(): Double = emotionalStability
    
    fun getEmotionalGrowth(): Double = emotionalGrowth
    
    fun getRecentMoods(hours: Int = 24): List<MoodHistory> {
        val cutoff = System.currentTimeMillis() - (hours * 60 * 60 * 1000L)
        return moodHistory.filter { it.timestamp >= cutoff }
    }
    
    fun getEmotionalMemories(limit: Int = 10): List<EmotionalMemory> {
        return emotionalMemories.takeLast(limit)
    }
    
    fun getPatterns(): List<EmotionalPattern> {
        return emotionalPatterns.values.sortedByDescending { it.frequency }
    }
    
    fun getAverageMood(): Pair<String, Double> {
        val recent = getRecentMoods(24)
        if (recent.isEmpty()) return Pair("neutral", 0.5)
        
        val avgValence = recent.map { it.valence }.average()
        val avgEnergy = recent.map { it.energy }.average()
        
        val mood = when {
            avgValence > 0.6 && avgEnergy > 0.5 -> "positive"
            avgValence > 0.3 -> "content"
            avgValence > 0.0 -> "neutral"
            avgValence > -0.3 -> "subdued"
            else -> "troubled"
        }
        
        return Pair(mood, avgValence)
    }
    
    fun formatCurrentState(): String {
        return buildString {
            appendLine("[Current Emotional State]")
            appendLine("-".repeat(40))
            appendLine("Primary: ${currentState.primary}")
            appendLine("Intensity: ${(currentState.intensity * 100).toInt()}%")
            
            if (currentState.secondary.isNotEmpty()) {
                appendLine("Secondary: ${currentState.secondary.joinToString(", ")}")
            }
            
            if (currentState.trigger != null) {
                appendLine("Trigger: ${currentState.trigger}")
            }
            
            appendLine("\nStability: ${(emotionalStability * 100).toInt()}%")
            appendLine("Growth: ${(emotionalGrowth * 100).toInt()}%")
            appendLine("Range: ${emotionalRange.size} emotions accessible")
        }
    }
    
    fun formatEmotionalHistory(): String {
        return buildString {
            appendLine("[Emotional Journey]")
            appendLine("=".repeat(40))
            
            val (avgMood, avgValence) = getAverageMood()
            appendLine("Current trend: $avgMood (${(avgValence * 100).toInt()}%)")
            
            appendLine("\n[Recent Emotions]")
            moodHistory.takeLast(10).forEach { mood ->
                val time = java.time.Instant.ofEpochMilli(mood.timestamp)
                val bar = if (mood.valence > 0) "+".repeat((mood.valence * 5).toInt())
                          else "-".repeat((mood.valence * -5).toInt())
                appendLine("  [$time] ${mood.mood} $bar")
            }
            
            appendLine("\n[Patterns]")
            emotionalPatterns.values
                .sortedByDescending { it.frequency }
                .take(5)
                .forEach { pattern ->
                    appendLine("  ${pattern.pattern}: ${pattern.frequency}x")
                    appendLine("    Triggers: ${pattern.triggers.take(2).joinToString(", ")}")
                }
        }
    }
    
    fun formatEmotionalMemories(): String {
        return buildString {
            appendLine("[Emotional Memories]")
            appendLine("-".repeat(40))
            
            if (emotionalMemories.isEmpty()) {
                appendLine("No emotional memories yet.")
            } else {
                emotionalMemories.takeLast(5).forEach { mem ->
                    appendLine("\n[${mem.emotion}]")
                    appendLine("  Cause: ${mem.cause}")
                    appendLine("  Response: ${mem.response}")
                    appendLine("  Outcome: ${mem.outcome}")
                    if (mem.learning != null) {
                        appendLine("  Learned: ${mem.learning}")
                    }
                }
            }
        }
    }
}
