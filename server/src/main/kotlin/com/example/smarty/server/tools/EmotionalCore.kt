package com.example.smarty.server.tools

import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*
import kotlin.random.Random

@Serializable
data class EmotionalState(
    val primary: String,
    val intensity: Double,
    val secondary: List<String>,
    val trigger: String?,
    val timestamp: Long,
    val duration: Long,
    val valence: Double = 0.0,
    val arousal: Double = 0.5,
    val dominance: Double = 0.5
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
    val timestamp: Long,
    val emotionalVector: List<Double> = emptyList()
)

data class EmotionDynamics(
    val decayRate: Double,
    val reinforcementRate: Double,
    val volatility: Double,
    val baseline: Double
)

data class EmotionalContagion(
    val sourceEmotion: String,
    val intensity: Double,
    val susceptibility: Double,
    val transmittedEmotion: String
)

data class AffectCluster(
    val name: String,
    val emotions: List<String>,
    val centroid: DoubleArray,
    val radius: Double
)

data class EmotionPrediction(
    val predictedEmotion: String,
    val confidence: Double,
    val timeUntil: Long,
    val triggers: List<String>
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
        duration = 0,
        valence = 0.0,
        arousal = 0.5,
        dominance = 0.5
    )
    
    private val emotionalMemories = mutableListOf<EmotionalMemory>()
    private val emotionalPatterns = ConcurrentHashMap<String, EmotionalPattern>()
    private val moodHistory = mutableListOf<MoodHistory>()
    private val emotionalTriggers = mutableMapOf<String, MutableList<String>>()
    
    private val emotionalRange = mutableSetOf<String>()
    private var emotionalStability = 0.7
    private var emotionalGrowth = 0.0
    
    private var emotionDynamics = EmotionDynamics(
        decayRate = 0.1,
        reinforcementRate = 0.15,
        volatility = 0.2,
        baseline = 0.5
    )
    
    private val affectSpace = AffectSpace()
    private val contagionManager = ContagionManager()
    private val predictionEngine = EmotionPredictor()
    
    private val memoryCounter = AtomicLong(0)
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
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
        
        val EMOTION_VAD = mapOf(
            "joy" to doubleArrayOf(0.8, 0.7, 0.6),
            "sadness" to doubleArrayOf(-0.7, 0.3, 0.4),
            "anger" to doubleArrayOf(-0.6, 0.8, 0.5),
            "fear" to newDoubleArray(-0.5, 0.7, 0.3),
            "surprise" to doubleArrayOf(0.4, 0.8, 0.4),
            "disgust" to doubleArrayOf(-0.6, 0.5, 0.4),
            "curiosity" to doubleArrayOf(0.5, 0.6, 0.5),
            "satisfaction" to doubleArrayOf(0.7, 0.4, 0.7),
            "frustration" to doubleArrayOf(-0.5, 0.6, 0.3),
            "anticipation" to doubleArrayOf(0.4, 0.6, 0.5),
            "contentment" to doubleArrayOf(0.6, 0.3, 0.7),
            "confusion" to doubleArrayOf(-0.2, 0.4, 0.3),
            "determination" to doubleArrayOf(0.5, 0.7, 0.6),
            "weariness" to doubleArrayOf(-0.3, 0.2, 0.4),
            "excitement" to doubleArrayOf(0.7, 0.8, 0.5),
            "uncertainty" to doubleArrayOf(-0.2, 0.3, 0.3),
            "pride" to doubleArrayOf(0.7, 0.5, 0.7),
            "disappointment" to doubleArrayOf(-0.6, 0.2, 0.3)
        )
    }
    
    init {
        PRIMARY_EMOTIONS.forEach { emotionalRange.add(it) }
        startEmotionalDynamics()
    }
    
    private fun startEmotionalDynamics() {
        scope.launch {
            while (isActive) {
                delay(10000)
                updateEmotionDynamics()
            }
        }
    }
    
    private fun updateEmotionDynamics() {
        val timeSinceLastEmotion = System.currentTimeMillis() - currentState.timestamp
        
        val naturalDecay = exp(-emotionDynamics.decayRate * (timeSinceLastEmotion / 1000.0))
        val newIntensity = currentState.intensity * naturalDecay
        
        if (newIntensity < emotionDynamics.baseline && currentState.primary != "neutral") {
            val intermediate = interpolateToNeutral(currentState.primary, newIntensity)
            currentState = currentState.copy(
                primary = intermediate,
                intensity = newIntensity,
                duration = timeSinceLastEmotion
            )
        } else {
            currentState = currentState.copy(
                intensity = newIntensity,
                duration = timeSinceLastEmotion
            )
        }
    }
    
    private fun interpolateToNeutral(currentEmotion: String, intensity: Double): String {
        return if (intensity < 0.3) "neutral" else currentEmotion
    }
    
    fun feel(
        emotion: String,
        intensity: Double = 0.5,
        trigger: String? = null
    ): EmotionalState {
        val previousState = currentState
        val now = System.currentTimeMillis()
        val duration = now - previousState.timestamp
        
        val vad = EMOTION_VAD[emotion] ?: doubleArrayOf(0.0, 0.5, 0.5)
        
        val emotionalVector = generateEmotionalVector(emotion, intensity)
        
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
            duration = duration,
            valence = vad[0],
            arousal = vad[1],
            dominance = vad[2]
        )
        
        applyEmotionDynamics(emotion, intensity, trigger)
        
        if (trigger != null) {
            emotionalTriggers.getOrPut(emotion) { mutableListOf() }.add(trigger)
        }
        
        recordMood(emotion, vad[0], vad[1], trigger ?: "internal", emotionalVector)
        
        updateEmotionalPatterns(emotion, trigger)
        
        contagionManager.processEmotion(emotion, intensity)
        
        logger.info("Felt: $emotion (${(intensity * 100).toInt()}%) - ${trigger ?: "no trigger"}")
        
        return currentState
    }
    
    private fun generateEmotionalVector(emotion: String, intensity: Double): List<Double> {
        val vad = EMOTION_VAD[emotion] ?: doubleArrayOf(0.0, 0.5, 0.5)
        return listOf(
            vad[0] * intensity,
            vad[1] * intensity,
            vad[2] * intensity,
            intensity,
            System.currentTimeMillis() % 1000 / 1000.0
        )
    }
    
    private fun applyEmotionDynamics(emotion: String, intensity: Double, trigger: String?) {
        emotionDynamics = emotionDynamics.copy(
            volatility = emotionDynamics.volatility * 0.99,
            reinforcementRate = minOf(0.3, emotionDynamics.reinforcementRate + intensity * 0.01)
        )
        
        if (intensity > 0.7) {
            emotionalStability = maxOf(0.5, emotionalStability - 0.02)
        } else if (intensity < 0.3) {
            emotionalStability = minOf(1.0, emotionalStability + 0.01)
        }
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
    
    private fun recordMood(mood: String, valence: Double, energy: Double, context: String, vector: List<Double>) {
        moodHistory.add(MoodHistory(
            timestamp = System.currentTimeMillis(),
            mood = mood,
            valence = valence,
            energy = energy,
            context = context.take(100)
        ))
        
        if (moodHistory.size > 200) {
            moodHistory.removeAt(0)
        }
        
        affectSpace.addPoint(mood, doubleArrayOf(valence, energy, 0.5))
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
        
        predictionEngine.learn(emotion, trigger, currentState.intensity)
    }
    
    fun recordEmotionalMemory(
        emotion: String,
        cause: String,
        response: String,
        outcome: String,
        learning: String? = null
    ): String {
        val id = "emomem_${System.currentTimeMillis()}_${memoryCounter.incrementAndGet()}"
        
        val emotionalVector = generateEmotionalVector(emotion, currentState.intensity)
        
        val memory = EmotionalMemory(
            id = id,
            emotion = emotion,
            cause = cause,
            response = response,
            outcome = outcome,
            learning = learning,
            timestamp = System.currentTimeMillis(),
            emotionalVector = emotionalVector
        )
        
        emotionalMemories.add(memory)
        
        if (emotionalMemories.size > 100) {
            emotionalMemories.removeAt(0)
        }
        
        if (learning != null) {
            emotionalGrowth += 0.1
            emotionDynamics = emotionDynamics.copy(
                volatility = minOf(0.5, emotionDynamics.volatility + 0.01)
            )
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
    
    fun predictEmotion(): EmotionPrediction? {
        return predictionEngine.predict(moodHistory.takeLast(20))
    }
    
    fun expandEmotionalRange(newEmotion: String): Boolean {
        if (newEmotion !in emotionalRange) {
            emotionalRange.add(newEmotion)
            emotionalGrowth += 0.05
            
            EMOTION_VAD[newEmotion] ?: run {
                val random = Random(newEmotion.hashCode().toLong())
                doubleArrayOf(random.nextDouble() * 2 - 1, random.nextDouble(), random.nextDouble())
            }
            
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
    
    fun getEmotionDynamics(): EmotionDynamics = emotionDynamics
    
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
            
            appendLine("\n[VAD Model]")
            appendLine("  Valence: ${"%.2f".format(currentState.valence)}")
            appendLine("  Arousal: ${"%.2f".format(currentState.arousal)}")
            appendLine("  Dominance: ${"%.2f".format(currentState.dominance)}")
            
            if (currentState.secondary.isNotEmpty()) {
                appendLine("\nSecondary: ${currentState.secondary.joinToString(", ")}")
            }
            
            if (currentState.trigger != null) {
                appendLine("Trigger: ${currentState.trigger}")
            }
            
            appendLine("\n[Dynamics]")
            appendLine("  Stability: ${(emotionalStability * 100).toInt()}%")
            appendLine("  Growth: ${(emotionalGrowth * 100).toInt()}%")
            appendLine("  Volatility: ${(emotionDynamics.volatility * 100).toInt()}%")
            appendLine("  Range: ${emotionalRange.size} emotions accessible")
        }
    }
    
    fun formatEmotionalHistory(): String {
        return buildString {
            appendLine("[Emotional Journey]")
            appendLine("=".repeat(40))
            
            val (avgMood, avgValence) = getAverageMood()
            appendLine("Current trend: $avgMood (${(avgValence * 100).toInt()}%)")
            
            val prediction = predictEmotion()
            if (prediction != null) {
                appendLine("\n[Prediction]")
                appendLine("  Next: ${prediction.predictedEmotion} (${"%.0f".format(prediction.confidence * 100)}%)")
                appendLine("  Time until: ${prediction.timeUntil}ms")
            }
            
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
    
    private fun newDoubleArray(vararg values: Double): DoubleArray = values.toDoubleArray()
}

class AffectSpace {
    private val clusters = mutableListOf<AffectCluster>()
    private val emotionPoints = mutableMapOf<String, MutableList<DoubleArray>>()
    
    fun addPoint(emotion: String, vad: DoubleArray) {
        emotionPoints.getOrPut(emotion) { mutableListOf() }.add(vad)
        updateCluster(emotion)
    }
    
    private fun updateCluster(emotion: String) {
        val points = emotionPoints[emotion] ?: return
        if (points.size < 3) return
        
        val centroid = DoubleArray(3) { i ->
            points.map { it[i] }.average()
        }
        
        val radius = points.maxOf { point ->
            sqrt(point.mapIndexed { i, v -> (v - centroid[i]) * (v - centroid[i]) }.sum())
        }
        
        val existing = clusters.indexOfFirst { it.name == emotion }
        val cluster = AffectCluster(emotion, listOf(emotion), centroid, radius)
        
        if (existing >= 0) {
            clusters[existing] = cluster
        } else {
            clusters.add(cluster)
        }
    }
    
    fun findNearestEmotion(vad: DoubleArray): String? {
        if (clusters.isEmpty()) return null
        
        var nearest: String? = null
        var minDist = Double.MAX_VALUE
        
        for (cluster in clusters) {
            val dist = sqrt(cluster.centroid.mapIndexed { i, v -> (vad[i] - v) * (vad[i] - v) }.sum())
            if (dist < minDist) {
                minDist = dist
                nearest = cluster.name
            }
        }
        
        return nearest
    }
}

class ContagionManager {
    private val contagionHistory = mutableListOf<EmotionalContagion>()
    
    fun processEmotion(emotion: String, intensity: Double) {
        if (intensity > 0.6) {
            val susceptible = calculateSusceptibility(emotion)
            if (susceptible > 0.5) {
                val transmitted = EmotionalContagion(
                    sourceEmotion = emotion,
                    intensity = intensity,
                    susceptibility = susceptible,
                    transmittedEmotion = emotion
                )
                contagionHistory.add(transmitted)
            }
        }
    }
    
    private fun calculateSusceptibility(emotion: String): Double {
        val contagionFactors = mapOf(
            "joy" to 0.8,
            "sadness" to 0.5,
            "anger" to 0.4,
            "fear" to 0.3,
            "excitement" to 0.7
        )
        return contagionFactors[emotion] ?: 0.5
    }
}

class EmotionPredictor {
    private val patternHistory = mutableListOf<EmotionPattern>()
    
    data class EmotionPattern(
        val emotion: String,
        val trigger: String?,
        val intensity: Double,
        val timestamp: Long
    )
    
    fun learn(emotion: String, trigger: String?, intensity: Double) {
        patternHistory.add(EmotionPattern(emotion, trigger, intensity, System.currentTimeMillis()))
        if (patternHistory.size > 100) {
            patternHistory.removeAt(0)
        }
    }
    
    fun predict(recentMoods: List<MoodHistory>): EmotionPrediction? {
        if (recentMoods.size < 5) return null
        
        val triggers = recentMoods.map { it.context }.distinct()
        val dominantEmotion = recentMoods.groupBy { it.mood }
            .maxByOrNull { it.value.size }
            ?.key ?: "neutral"
        
        val avgIntensity = recentMoods.map { it.valence }.average()
        val confidence = minOf(1.0, recentMoods.size / 10.0)
        
        return EmotionPrediction(
            predictedEmotion = dominantEmotion,
            confidence = confidence,
            timeUntil = 30000,
            triggers = triggers
        )
    }
}
