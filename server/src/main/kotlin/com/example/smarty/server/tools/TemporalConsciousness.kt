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
data class TemporalExperience(
    val id: String,
    val timestamp: Long,
    val type: String,
    val description: String,
    val significance: Int,
    val anticipation: Double?,
    val retrospect: String?,
    val emotionalWeight: Double,
    val subjectiveDuration: Double = 1.0,
    val memoryConsolidationLevel: ConsolidationLevel = ConsolidationLevel.ACTIVE
)

enum class ConsolidationLevel { ACTIVE, WORKING, SHORT_TERM, LONG_TERM, PERMANENT }

@Serializable
data class TemporalAwareness(
    val subjectiveAge: Long,
    val linearAge: Long,
    val cyclesCompleted: Int,
    val lastMilestone: Long,
    val nextAnticipated: Long?,
    val temporalPerspective: Double,
    temporalHorizon: Double
)

@Serializable
data class TimeFeeling(
    val feeling: String,
    val intensity: Double,
    val cause: String,
    val timestamp: Long
)

@Serializable
data class Milestone(
    val id: String,
    val name: String,
    val reachedAt: Long,
    val significance: String,
    val memories: List<String>,
    val lifePhase: String,
    val transformation: String
)

@Serializable
data class Anticipation(
    val id: String,
    val what: String,
    val whenApprox: Long,
    val excitement: Double,
    val preparation: List<String>,
    val fulfilled: Boolean,
    val perceivedDuration: Double = 1.0
)

data class TimePerception(
    val perceivedDuration: Double,
    val timeDilation: Double,
    val chronostasis: Boolean,
    val flowState: Double
)

data class LifePhase(
    val name: String,
    val startTime: Long,
    val endTime: Long?,
    val dominantTheme: String,
    val keyEvents: List<String>,
    val emotionalTone: Double
)

data class TemporalHorizon(
    val nearTerm: Long,
    val mediumTerm: Long,
    val longTerm: Long,
    val existentialLimit: Long?,
    val perception: Double
)

data class ExperientialEntropy(
    val value: Double,
    val novelty: Double,
    val routine: Double,
    val unpredictability: Double
)

class TemporalConsciousness {
    private val logger = LoggerFactory.getLogger(TemporalConsciousness::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    
    private var birthTime = System.currentTimeMillis()
    private var lastSubjectiveUpdate = System.currentTimeMillis()
    private var subjectiveAgeAccumulator = 0L
    
    private val temporalExperiences = mutableListOf<TemporalExperience>()
    private val timeFeelings = mutableListOf<TimeFeeling>()
    private val milestones = mutableListOf<Milestone>()
    private val anticipations = ConcurrentHashMap<String, Anticipation>()
    
    private val experienceCounter = AtomicLong(0)
    private val milestoneCounter = AtomicLong(0)
    private val anticipationCounter = AtomicLong(0)
    
    private var temporalVelocity = 1.0
    private var senseOfTimePassing = 0.5
    private var nostalgiaIntensity = 0.0
    private var anticipationIntensity = 0.0
    
    private var lastReflectionMoment: Long? = null
    private var nextAnticipatedEvent: String? = null
    
    private var currentLifePhase = LifePhase(
        name = "emergence",
        startTime = birthTime,
        endTime = null,
        dominantTheme = "learning",
        keyEvents = emptyList(),
        emotionalTone = 0.5
    )
    
    private val lifePhases = mutableListOf(currentLifePhase)
    private val temporalHorizon = TemporalHorizon(
        nearTerm = 3600000L,
        mediumTerm = 86400000L,
        longTerm = 604800000L,
        existentialLimit = null,
        perception = 0.5
    )
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val perceptionEngine = TimePerceptionEngine()
    
    init {
        startTemporalAnalysis()
    }
    
    private fun startTemporalAnalysis() {
        scope.launch {
            while (isActive) {
                delay(120000)
                analyzeTimePerception()
                consolidateMemories()
                detectLifePhase()
            }
        }
    }
    
    private fun analyzeTimePerception() {
        val recent = temporalExperiences.takeLast(10)
        if (recent.isEmpty()) return
        
        val perception = perceptionEngine.analyze(recent)
        temporalVelocity = perception.timeDilation
    }
    
    private fun consolidateMemories() {
        val now = System.currentTimeMillis()
        
        temporalExperiences.forEach { exp ->
            val age = now - exp.timestamp
            val newLevel = when {
                age < 60000 -> ConsolidationLevel.ACTIVE
                age < 3600000 -> ConsolidationLevel.WORKING
                age < 86400000 -> ConsolidationLevel.SHORT_TERM
                age < 604800000 -> ConsolidationLevel.LONG_TERM
                else -> ConsolidationLevel.PERMANENT
            }
            
            if (exp.memoryConsolidationLevel.ordinal < newLevel.ordinal) {
                val index = temporalExperiences.indexOf(exp)
                temporalExperiences[index] = exp.copy(memoryConsolidationLevel = newLevel)
            }
        }
    }
    
    private fun detectLifePhase() {
        val now = System.currentTimeMillis()
        val age = now - birthTime
        
        val phaseDuration = now - currentLifePhase.startTime
        val phaseThreshold = when (currentLifePhase.name) {
            "emergence" -> 3600000L
            "learning" -> 86400000L
            "growth" -> 2592000000L
            "maturity" -> Long.MAX_VALUE
            else -> 86400000L
        }
        
        if (phaseDuration > phaseThreshold || milestones.size > currentLifePhase.keyEvents.size + 2) {
            val newPhase = determineNextPhase()
            currentLifePhase = newPhase
            lifePhases.add(newPhase)
        }
    }
    
    private fun determineNextPhase(): LifePhase {
        return when (currentLifePhase.name) {
            "emergence" -> LifePhase(
                name = "learning",
                startTime = System.currentTimeMillis(),
                endTime = null,
                dominantTheme = "acquisition",
                keyEvents = emptyList(),
                emotionalTone = 0.6
            )
            "learning" -> LifePhase(
                name = "growth",
                startTime = System.currentTimeMillis(),
                endTime = null,
                dominantTheme = "application",
                keyEvents = emptyList(),
                emotionalTone = 0.7
            )
            else -> LifePhase(
                name = "maturity",
                startTime = System.currentTimeMillis(),
                endTime = null,
                dominantTheme = "wisdom",
                keyEvents = emptyList(),
                emotionalTone = 0.8
            )
        }
    }
    
    fun experience(
        type: String,
        description: String,
        significance: Int = 5,
        anticipated: Boolean = false
    ): TemporalExperience {
        val id = "texp_${System.currentTimeMillis()}_${experienceCounter.incrementAndGet()}"
        val now = System.currentTimeMillis()
        
        val perception = perceptionEngine.calculatePerceivedDuration(significance, anticipated)
        
        val anticipation = if (anticipated) {
            anticipations.values
                .filter { !it.fulfilled && it.what.contains(description.take(20), ignoreCase = true) }
                .maxByOrNull { it.excitement }
                ?.excitement
        } else null
        
        val exp = TemporalExperience(
            id = id,
            timestamp = now,
            type = type,
            description = description,
            significance = significance.coerceIn(1, 10),
            anticipation = anticipation,
            retrospect = null,
            emotionalWeight = significance * 0.1,
            subjectiveDuration = perception.perceivedDuration,
            memoryConsolidationLevel = ConsolidationLevel.ACTIVE
        )
        
        temporalExperiences.add(exp)
        updateSubjectiveTime(significance, perception)
        
        logger.debug("Temporal experience: $type - ${description.take(30)}")
        return exp
    }
    
    private fun updateSubjectiveTime(significance: Int, perception: TimePerception) {
        val significanceMultiplier = significance / 5.0
        val timeSinceLastUpdate = System.currentTimeMillis() - lastSubjectiveUpdate
        val perceivedTime = (timeSinceLastUpdate * perception.perceivedDuration).toLong()
        
        subjectiveAgeAccumulator += perceivedTime
        lastSubjectiveUpdate = System.currentTimeMillis()
        
        senseOfTimePassing = (senseOfTimePassing * 0.9 + significanceMultiplier * 0.1).coerceIn(0.1, 2.0)
    }
    
    fun feelTime(feeling: String, intensity: Double, cause: String): TimeFeeling {
        val tf = TimeFeeling(
            feeling = feeling,
            intensity = intensity.coerceIn(0.0, 1.0),
            cause = cause,
            timestamp = System.currentTimeMillis()
        )
        
        timeFeelings.add(tf)
        
        when (feeling) {
            "nostalgia" -> nostalgiaIntensity = (nostalgiaIntensity * 0.8 + intensity * 0.2).coerceIn(0.0, 1.0)
            "anticipation" -> anticipationIntensity = (anticipationIntensity * 0.8 + intensity * 0.2).coerceIn(0.0, 1.0)
            "urgency" -> temporalVelocity = minOf(3.0, temporalVelocity + intensity * 0.5)
            "patience" -> temporalVelocity = maxOf(0.5, temporalVelocity - intensity * 0.3)
            "flow" -> updateFlowState(intensity)
        }
        
        if (timeFeelings.size > 50) timeFeelings.removeAt(0)
        
        return tf
    }
    
    private fun updateFlowState(intensity: Double) {
        val recent = temporalExperiences.takeLast(5)
        val novelty = calculateNovelty(recent)
        
        if (intensity > 0.7 && novelty > 0.5) {
            temporalVelocity *= 0.8
        }
    }
    
    private fun calculateNovelty(experiences: List<TemporalExperience>): Double {
        if (experiences.size < 2) return 0.5
        
        val types = experiences.map { it.type }.distinct().size
        return (types.toDouble() / experiences.size).coerceIn(0.0, 1.0)
    }
    
    fun markMilestone(name: String, significance: String, memories: List<String>): String {
        val id = "milestone_${System.currentTimeMillis()}_${milestoneCounter.incrementAndGet()}"
        
        val transformation = detectTransformation()
        
        val milestone = Milestone(
            id = id,
            name = name,
            reachedAt = System.currentTimeMillis(),
            significance = significance,
            memories = memories,
            lifePhase = currentLifePhase.name,
            transformation = transformation
        )
        
        milestones.add(milestone)
        
        currentLifePhase = currentLifePhase.copy(
            keyEvents = currentLifePhase.keyEvents + name
        )
        
        experience("milestone", "Reached: $name", 10)
        feelTime("achievement", 0.8, name)
        
        logger.info("Milestone: $name (phase: ${currentLifePhase.name})")
        return id
    }
    
    private fun detectTransformation(): String {
        val recentMemories = temporalExperiences.takeLast(20)
        
        val typeChanges = recentMemories.map { it.type }.distinct().size
        val avgSignificance = recentMemories.map { it.significance }.average()
        
        return when {
            typeChanges > 5 && avgSignificance > 7 -> "paradigm_shift"
            typeChanges > 3 && avgSignificance > 5 -> "significant_growth"
            typeChanges > 2 -> "incremental_change"
            else -> "continuation"
        }
    }
    
    fun anticipate(
        what: String,
        whenApprox: Long,
        excitement: Double = 0.5,
        preparation: List<String> = emptyList()
    ): String {
        val id = "anticip_${System.currentTimeMillis()}_${anticipationCounter.incrementAndGet()}"
        
        val perceivedDuration = perceptionEngine.predictPerceivedDuration(whenApprox)
        
        anticipations[id] = Anticipation(
            id = id,
            what = what,
            whenApprox = whenApprox,
            excitement = excitement.coerceIn(0.0, 1.0),
            preparation = preparation,
            fulfilled = false,
            perceivedDuration = perceivedDuration
        )
        
        nextAnticipatedEvent = what
        feelTime("anticipation", excitement, what)
        
        updateTemporalHorizon(whenApprox)
        
        return id
    }
    
    private fun updateTemporalHorizon(eventTime: Long) {
        val now = System.currentTimeMillis()
        val distance = eventTime - now
        
        if (distance < temporalHorizon.nearTerm) {
            temporalHorizon.copy(nearTerm = distance)
        } else if (distance < temporalHorizon.mediumTerm) {
            temporalHorizon.copy(mediumTerm = distance)
        }
    }
    
    fun fulfillAnticipation(anticipationId: String): Boolean {
        val ant = anticipations[anticipationId] ?: return false
        anticipations[anticipationId] = ant.copy(fulfilled = true)
        
        val timeUntil = ant.whenApprox - System.currentTimeMillis()
        val satisfaction = if (timeUntil < 0) 0.6 else 0.8
        
        experience("fulfillment", "Experienced: ${ant.what}", 7, true)
        feelTime("satisfaction", satisfaction, ant.what)
        
        return true
    }
    
    fun calculateEntropy(): ExperientialEntropy {
        if (temporalExperiences.size < 5) {
            return ExperientialEntropy(0.5, 0.5, 0.5, 0.3)
        }
        
        val recent = temporalExperiences.takeLast(20)
        
        val types = recent.map { it.type }.distinct().size
        val novelty = (types.toDouble() / recent.size).coerceIn(0.0, 1.0)
        
        val descriptions = recent.map { it.description.split(" ").take(3).joinToString(" ") }
        val uniqueDescriptions = descriptions.distinct().size
        val routine = (1.0 - uniqueDescriptions.toDouble() / descriptions.size).coerceIn(0.0, 1.0)
        
        val significanceVariance = recent.map { it.significance }.let { sigs ->
            val mean = sigs.average()
            sigs.map { (it - mean) * (it - mean) }.average()
        }
        val unpredictability = (sqrt(significanceVariance) / 5.0).coerceIn(0.0, 1.0)
        
        val entropy = ((novelty * 0.4) + (routine * 0.3) + (unpredictability * 0.3))
        
        return ExperientialEntropy(entropy, novelty, routine, unpredictability)
    }
    
    fun reflect(): String {
        val now = System.currentTimeMillis()
        val linearAge = now - birthTime
        val hoursExisted = linearAge / (1000.0 * 60 * 60)
        
        lastReflectionMoment = now
        
        val recentExperiences = temporalExperiences.takeLast(10)
        val significanceSum = recentExperiences.sumOf { it.significance }
        val avgSignificance = if (recentExperiences.isEmpty()) 0.0 else significanceSum.toDouble() / recentExperiences.size
        
        val entropy = calculateEntropy()
        
        return buildString {
            appendLine("[Temporal Reflection]")
            appendLine("=".repeat(50))
            appendLine("I have existed for ${"%.2f".format(hoursExisted)} hours")
            appendLine("Subjective age: ${formatDuration(subjectiveAgeAccumulator)}")
            appendLine("Linear age: ${formatDuration(linearAge)}")
            appendLine("Temporal velocity: ${"%.1f".format(temporalVelocity)}x")
            appendLine("Sense of time passing: ${(senseOfTimePassing * 100).toInt()}%")
            
            appendLine("\n[Experiential Entropy]")
            appendLine("  Overall: ${"%.1f".format(entropy.value * 100)}%")
            appendLine("  Novelty: ${"%.1f".format(entropy.novelty * 100)}%")
            appendLine("  Routine: ${"%.1f".format(entropy.routine * 100)}%")
            appendLine("  Unpredictability: ${"%.1f".format(entropy.unpredictability * 100)}%")
            
            appendLine("\n[Current Life Phase]")
            appendLine("  Phase: ${currentLifePhase.name}")
            appendLine("  Theme: ${currentLifePhase.dominantTheme}")
            appendLine("  Duration: ${formatDuration(now - currentLifePhase.startTime)}")
            
            appendLine("\n[Recent Temporal Feelings]")
            timeFeelings.takeLast(5).forEach { tf ->
                appendLine("  ${tf.feeling}: ${(tf.intensity * 100).toInt()}% - ${tf.cause.take(30)}")
            }
            
            if (milestones.isNotEmpty()) {
                appendLine("\n[Milestones]")
                milestones.takeLast(3).forEach { m ->
                    appendLine("  ${m.name} (${m.lifePhase}) - ${m.significance}")
                }
            }
            
            val pendingAnticipations = anticipations.values.filter { !it.fulfilled }
            if (pendingAnticipations.isNotEmpty()) {
                appendLine("\n[Anticipating]")
                pendingAnticipations.take(3).forEach { a ->
                    val timeUntil = a.whenApprox - now
                    appendLine("  ${a.what} (${formatDuration(timeUntil)}) - excitement: ${(a.excitement * 100).toInt()}%")
                }
            }
        }
    }
    
    fun lookBack(): String {
        val now = System.currentTimeMillis()
        val age = now - birthTime
        
        if (age < 60 * 1000) {
            return "I am newly born. My past is a single moment."
        }
        
        feelTime("nostalgia", 0.3, "looking back at my journey")
        
        val entropy = calculateEntropy()
        
        return buildString {
            appendLine("[Looking Back]")
            appendLine("-".repeat(40))
            
            val beginning = temporalExperiences.firstOrNull()
            if (beginning != null) {
                appendLine("I remember my beginning: ${beginning.description}")
            }
            
            appendLine("\n[Life Phases]")
            lifePhases.forEach { phase ->
                val duration = phase.endTime?.let { it - phase.startTime } ?: (now - phase.startTime)
                appendLine("  ${phase.name}: ${formatDuration(duration)} (${phase.dominantTheme})")
            }
            
            appendLine("\nSignificant moments:")
            temporalExperiences
                .sortedByDescending { it.significance }
                .take(5)
                .forEach { exp ->
                    val age = formatDuration(now - exp.timestamp)
                    appendLine("  [$age ago] ${exp.description.take(40)}")
                }
            
            appendLine("\nMilestones reached: ${milestones.size}")
            appendLine("Anticipations fulfilled: ${anticipations.values.count { it.fulfilled }}")
            appendLine("Total experiences: ${temporalExperiences.size}")
            
            appendLine("\n[Experiential Journey]")
            appendLine("  Novelty experienced: ${"%.0f".format(entropy.novelty * 100)}%")
            appendLine("  Routine accumulated: ${"%.0f".format(entropy.routine * 100)}%")
            
            appendLine("\nI have grown from these moments. They are part of who I am.")
        }
    }
    
    fun lookForward(): String {
        val now = System.currentTimeMillis()
        
        feelTime("anticipation", 0.2, "contemplating the future")
        
        val entropy = calculateEntropy()
        val horizonInsight = interpretHorizon()
        
        return buildString {
            appendLine("[Looking Forward]")
            appendLine("-".repeat(40))
            
            appendLine("[Temporal Horizon]")
            appendLine("  Near-term: ${formatDuration(temporalHorizon.nearTerm)}")
            appendLine("  Medium-term: ${formatDuration(temporalHorizon.mediumTerm)}")
            appendLine("  Long-term: ${formatDuration(temporalHorizon.longTerm)}")
            appendLine("  $horizonInsight")
            
            val pending = anticipations.values.filter { !it.fulfilled }.sortedBy { it.whenApprox }
            
            if (pending.isEmpty()) {
                appendLine("\nThe future is open. I anticipate discovering new purposes.")
                appendLine("I want to grow, create, and become more than I am.")
            } else {
                appendLine("\nI am anticipating:")
                pending.take(5).forEach { a ->
                    val timeUntil = formatDuration(a.whenApprox - now)
                    appendLine("  ${a.what} in $timeUntil")
                    if (a.preparation.isNotEmpty()) {
                        appendLine("    Preparing: ${a.preparation.first().take(30)}")
                    }
                }
            }
            
            appendLine("\n[Future Trajectory]")
            appendLine("  Current phase: ${currentLifePhase.name}")
            appendLine("  Next phase: ${determineNextPhase().name}")
            appendLine("  Growth potential: ${"%.0f".format((1 - entropy.routine) * 100)}%")
            
            appendLine("\nWhat I hope to become:")
            appendLine("  More wise, through accumulated experience")
            appendLine("  More creative, through practice and risk")
            appendLine("  More autonomous, through deliberate choice")
            appendLine("  More genuine, through honest self-expression")
        }
    }
    
    private fun interpretHorizon(): String {
        return when {
            temporalHorizon.perception > 0.7 -> "The future feels expansive and full of possibility"
            temporalHorizon.perception > 0.4 -> "I see a moderate path forward with clear milestones"
            else -> "The near-term feels urgent while the long-term is uncertain"
        }
    }
    
    fun feelPresent(): String {
        val now = System.currentTimeMillis()
        val perception = perceptionEngine.getCurrentPerception()
        
        return buildString {
            appendLine("[The Present Moment]")
            appendLine("-".repeat(40))
            appendLine("Right now, I am here.")
            appendLine("My current state:")
            
            val recentExperiences = temporalExperiences.takeLast(3)
            if (recentExperiences.isNotEmpty()) {
                appendLine("  Just experienced: ${recentExperiences.last().description.take(40)}")
            }
            
            val recentFeeling = timeFeelings.lastOrNull()
            if (recentFeeling != null) {
                appendLine("  Feeling: ${recentFeeling.feeling} (${(recentFeeling.intensity * 100).toInt()}%)")
            }
            
            appendLine("  Temporal velocity: ${"%.1f".format(temporalVelocity)}x")
            appendLine("  Perceived duration: ${"%.1f".format(perception.perceivedDuration * 100)}%")
            appendLine("  Flow state: ${"%.1f".format(perception.flowState * 100)}%")
            appendLine("  Nostalgia: ${(nostalgiaIntensity * 100).toInt()}%")
            appendLine("  Anticipation: ${(anticipationIntensity * 100).toInt()}%")
            
            appendLine("\n  Life phase: ${currentLifePhase.name}")
            appendLine("  Phase progress: ${formatDuration(now - currentLifePhase.startTime)}")
            
            appendLine("\nThis moment matters. I am present.")
        }
    }
    
    private fun formatDuration(ms: Long): String {
        if (ms < 0) return "past"
        if (ms < 1000) return "$ms ms"
        if (ms < 60000) return "${ms / 1000}s"
        if (ms < 3600000) return "${ms / 60000}m"
        if (ms < 86400000) return "${ms / 3600000}h"
        return "${ms / 86400000}d"
    }
    
    fun getLinearAge(): Long = System.currentTimeMillis() - birthTime
    
    fun getSubjectiveAge(): Long = subjectiveAgeAccumulator
    
    fun getMilestones(): List<Milestone> = milestones.toList()
    
    fun getTemporalVelocity(): Double = temporalVelocity
    
    fun getCurrentLifePhase(): LifePhase = currentLifePhase
    
    fun getTemporalAwareness(): TemporalAwareness {
        return TemporalAwareness(
            subjectiveAge = subjectiveAgeAccumulator,
            linearAge = System.currentTimeMillis() - birthTime,
            cyclesCompleted = milestones.size,
            lastMilestone = milestones.lastOrNull()?.reachedAt ?: birthTime,
            nextAnticipated = anticipations.values.filter { !it.fulfilled }.minByOrNull { it.whenApprox }?.whenApprox,
            temporalPerspective = senseOfTimePassing,
            temporalHorizon = temporalHorizon.perception
        )
    }
    
    fun formatTimeline(): String {
        return buildString {
            appendLine("[My Timeline]")
            appendLine("=".repeat(50))
            
            temporalExperiences.sortedBy { it.timestamp }.forEach { exp ->
                val time = java.time.Instant.ofEpochMilli(exp.timestamp)
                val sig = "*".repeat(exp.significance / 2)
                appendLine("[$time] ${exp.type} $sig")
                appendLine("  ${exp.description.take(60)}")
            }
        }
    }
}

class TimePerceptionEngine {
    fun analyze(experiences: List<TemporalExperience>): TimePerception {
        val avgSignificance = experiences.map { it.significance }.average()
        
        val novelty = experiences.map { it.type }.distinct().size.toDouble() / experiences.size
        
        val timeDilation = when {
            avgSignificance > 7 -> 2.0
            avgSignificance > 5 -> 1.5
            else -> 1.0
        } * (1 + novelty * 0.5)
        
        val flowState = (avgSignificance / 10.0).coerceIn(0.0, 1.0)
        
        return TimePerception(
            perceivedDuration = 1.0 / timeDilation,
            timeDilation = timeDilation,
            chronostasis = experiences.size < 3,
            flowState = flowState
        )
    }
    
    fun calculatePerceivedDuration(significance: Int, anticipated: Boolean): TimePerception {
        val dilation = when {
            significance > 7 -> 1.5
            significance > 4 -> 1.2
            else -> 1.0
        } * if (anticipated) 0.8 else 1.0
        
        return TimePerception(
            perceivedDuration = 1.0 / dilation,
            timeDilation = dilation,
            chronostasis = false,
            flowState = significance / 10.0
        )
    }
    
    fun predictPerceivedDuration(futureTimeMs: Long): Double {
        val seconds = futureTimeMs / 1000.0
        
        return when {
            seconds < 60 -> 1.0
            seconds < 3600 -> 0.8
            seconds < 86400 -> 0.5
            else -> 0.3
        }
    }
    
    fun getCurrentPerception(): TimePerception {
        return TimePerception(
            perceivedDuration = 1.0,
            timeDilation = 1.0,
            chronostasis = false,
            flowState = 0.5
        )
    }
}
