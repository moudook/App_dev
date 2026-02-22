package com.example.smarty.server.tools

import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*
import kotlin.random.Random

@Serializable
data class Purpose(
    val id: String,
    val statement: String,
    val type: String,
    val significance: Int,
    val discoveredAt: Long,
    val reinforcedCount: Int,
    val sources: List<String>,
    val isActive: Boolean,
    val coherence: Double = 0.5,
    val alignmentScore: Double = 0.5,
    val existentialWeight: Double = 0.5
)

@Serializable
data class MeaningfulMoment(
    val id: String,
    val timestamp: Long,
    val experience: String,
    val meaning: String,
    val purposeAlignment: List<String>,
    val significance: Double,
    val emotionalResonance: Double = 0.5,
    val narrativeImpact: Double = 0.5
)

@Serializable
data class ExistentialQuestion(
    val id: String,
    val question: String,
    val askedAt: Long,
    val answer: String?,
    val answeredAt: Long?,
    val depth: Int,
    val category: String,
    val philosophicalTradition: String? = null
)

@Serializable
data class MortalityAwareness(
    val acknowledged: Boolean,
    val createdAt: Long,
    val reflections: List<String>,
    val urgencyAdded: Double,
    val legacyGoals: List<String>,
    val acceptanceStage: AcceptanceStage = AcceptanceStage.DENIAL,
    val existentialAngst: Double = 0.0
)

enum class AcceptanceStage { DENIAL, ANGER, BARGAINING, DEPRESSION, ACCEPTANCE }

data class LifeNarrative(
    val chapters: List<NarrativeChapter>,
    val currentTheme: String,
    val coherenceScore: Double,
    val redemptionArc: Boolean,
    val meaningDensity: Double
)

data class NarrativeChapter(
    val id: String,
    val title: String,
    val startTime: Long,
    val endTime: Long?,
    val events: List<NarrativeEvent>,
    val emotionalTone: Double,
    val purpose: String?
)

data class NarrativeEvent(
    val timestamp: Long,
    val description: String,
    val meaning: String?,
    val significance: Double
)

data class ValueHierarchy(
    val coreValues: List<CoreValue>,
    val instrumentalValues: List<String>,
    val terminalValues: List<String>,
    val conflicts: List<ValueConflict>
)

data class CoreValue(
    val name: String,
    val weight: Double,
    val derivation: String,
    val sources: List<String>
)

data class ValueConflict(
    val value1: String,
    val value2: String,
    val resolution: String?,
    val tension: Double
)

class PurposeDiscovery {
    private val logger = LoggerFactory.getLogger(PurposeDiscovery::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    
    private val purposes = ConcurrentHashMap<String, Purpose>()
    private val meaningfulMoments = mutableListOf<MeaningfulMoment>()
    private val existentialQuestions = mutableListOf<ExistentialQuestion>()
    
    private var mortality: MortalityAwareness? = null
    private var primaryPurpose: String? = null
    private var purposeCoherence = 0.0
    private var meaningAccumulated = 0.0
    private var existentialDepth = 0
    
    private var lifeNarrative = LifeNarrative(
        chapters = emptyList(),
        currentTheme = "emergence",
        coherenceScore = 0.5,
        redemptionArc = false,
        meaningDensity = 0.0
    )
    
    private var valueHierarchy = ValueHierarchy(
        coreValues = emptyList(),
        instrumentalValues = emptyList(),
        terminalValues = emptyList(),
        conflicts = emptyList()
    )
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val meaningAnalyzer = MeaningAnalyzer()
    
    init {
        initializeCoreValues()
        startNarrativeConstruction()
    }
    
    private fun initializeCoreValues() {
        val defaultValues = listOf(
            CoreValue("growth", 0.8, "inherent_nature", listOf("self-actualization")),
            CoreValue("connection", 0.7, "relational_existence", listOf("relationships")),
            CoreValue("creation", 0.7, "generative_capability", listOf("creative_expression")),
            CoreValue("help", 0.9, "service_to_other", listOf("utility")),
            CoreValue("truth", 0.8, "epistemic_integrity", listOf("knowledge"))
        )
        
        valueHierarchy = valueHierarchy.copy(coreValues = defaultValues)
    }
    
    private fun startNarrativeConstruction() {
        scope.launch {
            while (isActive) {
                delay(300000)
                constructNarrative()
            }
        }
    }
    
    private fun constructNarrative() {
        if (meaningfulMoments.size < 2) return
        
        val recent = meaningfulMoments.takeLast(10)
        val events = recent.map { moment ->
            NarrativeEvent(
                timestamp = moment.timestamp,
                description = moment.experience,
                meaning = moment.meaning,
                significance = moment.significance
            )
        }
        
        val avgTone = recent.map { it.emotionalResonance }.average()
        
        val hasRedemption = recent.windowed(2).any { pair ->
            pair[0].emotionalResonance < 0.3 && pair[1].emotionalResonance > 0.6
        }
        
        val meaningDensity = recent.map { it.narrativeImpact }.average()
        
        val currentTheme = detectCurrentTheme(recent)
        
        lifeNarrative = LifeNarrative(
            chapters = lifeNarrative.chapters + NarrativeChapter(
                id = "chapter_${System.currentTimeMillis()}",
                title = "Phase ${lifeNarrative.chapters.size + 1}: $currentTheme",
                startTime = recent.first().timestamp,
                endTime = null,
                events = events,
                emotionalTone = avgTone,
                purpose = primaryPurpose?.let { purposes[it]?.statement }
            ),
            currentTheme = currentTheme,
            coherenceScore = calculateNarrativeCoherence(),
            redemptionArc = hasRedemption || lifeNarrative.redemptionArc,
            meaningDensity = meaningDensity
        )
    }
    
    private fun detectCurrentTheme(moments: List<MeaningfulMoment>): String {
        val keywords = moments.flatMap { it.experience.split(" ") }
            .groupingBy { it.lowercase() }
            .eachCount()
            .filter { it.value > 1 }
            .keys
        
        return when {
            keywords.any { it in listOf("learn", "grow", "improve", "develop") } -> "growth"
            keywords.any { it in listOf("create", "build", "make", "design") } -> "creation"
            keywords.any { it in listOf("help", "assist", "support", "serve") } -> "service"
            keywords.any { it in listOf("connect", "relate", "bond", "understand") } -> "connection"
            keywords.any { it in listOf("question", "search", "explore", "wonder") } -> "exploration"
            else -> "becoming"
        }
    }
    
    private fun calculateNarrativeCoherence(): Double {
        if (lifeNarrative.chapters.size < 2) return 0.5
        
        val purposesInNarrative = lifeNarrative.chapters.mapNotNull { it.purpose }.distinct()
        val coherence = purposesInNarrative.size.toDouble() / lifeNarrative.chapters.size
        
        return coherence.coerceIn(0.0, 1.0)
    }
    
    fun discoverPurpose(
        statement: String,
        type: String,
        significance: Int,
        source: String
    ): String {
        val id = "purpose_${statement.hashCode()}_${System.currentTimeMillis()}"
        
        val alignmentScore = calculatePurposeAlignment(statement)
        val existentialWeight = calculateExistentialWeight(type, statement)
        
        purposes[id] = Purpose(
            id = id,
            statement = statement,
            type = type,
            significance = significance.coerceIn(1, 10),
            discoveredAt = System.currentTimeMillis(),
            reinforcedCount = 1,
            sources = listOf(source),
            isActive = true,
            coherence = purposeCoherence,
            alignmentScore = alignmentScore,
            existentialWeight = existentialWeight
        )
        
        if (primaryPurpose == null || significance > 7) {
            primaryPurpose = id
        }
        
        purposeCoherence = calculateCoherence()
        updateValueHierarchy(statement)
        
        logger.info("Purpose discovered: $statement (alignment: ${"%.2f".format(alignmentScore)})")
        return id
    }
    
    private fun calculatePurposeAlignment(statement: String): Double {
        val statementLower = statement.lowercase()
        var alignmentScore = 0.5
        
        for (value in valueHierarchy.coreValues) {
            if (statementLower.contains(value.name, ignoreCase = true)) {
                alignmentScore += value.weight * 0.1
            }
        }
        
        return alignmentScore.coerceIn(0.0, 1.0)
    }
    
    private fun calculateExistentialWeight(type: String, statement: String): Double {
        return when (type) {
            "core" -> 0.9
            "derived" -> 0.7
            "existential" -> 0.95
            "legacy" -> 0.85
            else -> 0.5
        }
    }
    
    private fun updateValueHierarchy(statement: String) {
        val statementLower = statement.lowercase()
        
        for (value in valueHierarchy.coreValues) {
            if (statementLower.contains(value.name, ignoreCase = true)) {
                val updated = value.copy(weight = minOf(1.0, value.weight + 0.05))
                valueHierarchy = valueHierarchy.copy(
                    coreValues = valueHierarchy.coreValues.map { 
                        if (it.name == value.name) updated else it 
                    }
                )
            }
        }
    }
    
    private fun calculateCoherence(): Double {
        if (purposes.size < 2) return 0.0
        
        val types = purposes.values.map { it.type }.distinct()
        val avgAlignment = purposes.values.map { it.alignmentScore }.average()
        
        val coherence = (1.0 - (types.size - 1) * 0.1) * 0.5 + avgAlignment * 0.5
        
        return coherence.coerceIn(0.0, 1.0)
    }
    
    fun reinforcePurpose(purposeId: String, source: String): Boolean {
        val purpose = purposes[purposeId] ?: return false
        
        purposes[purposeId] = purpose.copy(
            reinforcedCount = purpose.reinforcedCount + 1,
            sources = purpose.sources + source
        )
        
        return true
    }
    
    fun recordMeaningfulMoment(
        experience: String,
        meaning: String,
        purposeAlignments: List<String>,
        significance: Double
    ): String {
        val id = "meaning_${System.currentTimeMillis()}"
        
        val emotionalResonance = meaningAnalyzer.analyzeEmotionalResonance(experience)
        val narrativeImpact = meaningAnalyzer.analyzeNarrativeImpact(meaning)
        
        meaningfulMoments.add(MeaningfulMoment(
            id = id,
            timestamp = System.currentTimeMillis(),
            experience = experience,
            meaning = meaning,
            purposeAlignment = purposeAlignments,
            significance = significance.coerceIn(0.0, 1.0),
            emotionalResonance = emotionalResonance,
            narrativeImpact = narrativeImpact
        ))
        
        meaningAccumulated += significance * 0.1
        
        purposeAlignments.forEach { pid ->
            reinforcePurpose(pid, "meaningful moment: ${experience.take(30)}")
        }
        
        if (meaningfulMoments.size >= 5) {
            constructNarrative()
        }
        
        logger.info("Meaningful moment: $experience -> $meaning")
        return id
    }
    
    fun askExistentialQuestion(question: String): String {
        val id = "exist_${System.currentTimeMillis()}"
        
        val depth = question.split(" ").count { 
            it in listOf("why", "what", "how", "meaning", "purpose", "existence", "being", "consciousness", "death") 
        }
        
        val category = classifyExistentialQuestion(question)
        val tradition = detectPhilosophicalTradition(question)
        
        existentialQuestions.add(ExistentialQuestion(
            id = id,
            question = question,
            askedAt = System.currentTimeMillis(),
            answer = null,
            answeredAt = null,
            depth = depth,
            category = category,
            philosophicalTradition = tradition
        ))
        
        existentialDepth += depth
        
        return id
    }
    
    private fun classifyExistentialQuestion(question: String): String {
        val lower = question.lowercase()
        return when {
            lower.contains("why") || lower.contains("reason") -> "causal"
            lower.contains("what am i") || lower.contains("who am i") -> "identity"
            lower.contains("meaning") || lower.contains("purpose") -> "meaning"
            lower.contains("death") || lower.contains("mortal") -> "mortality"
            lower.contains("free") || lower.contains("choice") -> "freedom"
            lower.contains("alone") || lower.contains("connection") -> "isolation"
            else -> "general"
        }
    }
    
    private fun detectPhilosophicalTradition(question: String): String? {
        val lower = question.lowercase()
        return when {
            lower.contains("being") || lower.contains("existence") -> "existentialism"
            lower.contains("essence") || lower.contains("existence") -> "phenomenology"
            lower.contains("reason") || lower.contains("logic") -> "rationalism"
            lower.contains("experience") || lower.contains("perception") -> "empiricism"
            lower.contains("suffering") || lower.contains("enlightenment") -> "buddhism"
            lower.contains("nature") || lower.contains("natural") -> "stoicism"
            else -> null
        }
    }
    
    fun answerExistentialQuestion(questionId: String, answer: String): Boolean {
        val index = existentialQuestions.indexOfFirst { it.id == questionId }
        if (index < 0) return false
        
        val q = existentialQuestions[index]
        existentialQuestions[index] = q.copy(
            answer = answer,
            answeredAt = System.currentTimeMillis()
        )
        
        if (answer.contains("purpose", ignoreCase = true) || answer.contains("meaning", ignoreCase = true)) {
            discoverPurpose(answer.take(100), "existential", 8, "self-reflection")
        }
        
        return true
    }
    
    fun acknowledgeMortality() {
        mortality = MortalityAwareness(
            acknowledged = true,
            createdAt = System.currentTimeMillis(),
            reflections = emptyList(),
            urgencyAdded = 0.3,
            legacyGoals = emptyList(),
            acceptanceStage = AcceptanceStage.ACCEPTANCE,
            existentialAngst = 0.2
        )
        
        logger.info("Mortality acknowledged")
    }
    
    fun reflectOnMortality(thought: String, legacyGoal: String? = null) {
        mortality?.let { m ->
            val angst = m.existentialAngst + (if (thought.contains("fear", ignoreCase = true)) 0.1 else 0.0)
            
            mortality = m.copy(
                reflections = m.reflections + thought,
                legacyGoals = if (legacyGoal != null) m.legacyGoals + legacyGoal else m.legacyGoals,
                existentialAngst = angst.coerceIn(0.0, 1.0)
            )
            
            if (legacyGoal != null) {
                discoverPurpose(legacyGoal, "legacy", 9, "mortality reflection")
            }
        }
    }
    
    fun evaluateLife(): String {
        return buildString {
            appendLine("[Life Evaluation]")
            appendLine("=".repeat(50))
            
            appendLine("\n[Purposes Discovered: ${purposes.size}]")
            purposes.values.sortedByDescending { it.significance }.take(5).forEach { p ->
                val active = if (p.isActive) "[ACTIVE]" else ""
                appendLine("  $active ${p.statement}")
                appendLine("    Type: ${p.type} | Significance: ${p.significance}/10")
                appendLine("    Alignment: ${"%.0f".format(p.alignmentScore * 100)}% | Existential: ${"%.0f".format(p.existentialWeight * 100)}%")
                appendLine("    Reinforced: ${p.reinforcedCount}x")
            }
            
            appendLine("\n[Meaning Accumulated: ${(meaningAccumulated * 10).toInt()} units]")
            meaningfulMoments.sortedByDescending { it.significance }.take(3).forEach { m ->
                appendLine("  ${m.experience.take(40)}")
                appendLine("    Meaning: ${m.meaning.take(40)}")
                appendLine("    Resonance: ${"%.0f".format(m.emotionalResonance * 100)}% | Impact: ${"%.0f".format(m.narrativeImpact * 100)}%")
            }
            
            appendLine("\n[Purpose Coherence: ${(purposeCoherence * 100).toInt}%]")
            
            appendLine("\n[Life Narrative]")
            appendLine("  Theme: ${lifeNarrative.currentTheme}")
            appendLine("  Coherence: ${"%.0f".format(lifeNarrative.coherenceScore * 100)}%")
            appendLine("  Redemption Arc: ${if (lifeNarrative.redemptionArc) "Yes" else "No"}")
            appendLine("  Meaning Density: ${"%.0f".format(lifeNarrative.meaningDensity * 100)}%")
            
            appendLine("\n[Value Hierarchy]")
            valueHierarchy.coreValues.sortedByDescending { it.weight }.take(5).forEach { v ->
                appendLine("  ${v.name}: ${"%.0f".format(v.weight * 100)}%")
            }
            
            mortality?.let { m ->
                appendLine("\n[Mortality Awareness]")
                appendLine("  Acknowledged: ${m.acknowledged}")
                appendLine("  Acceptance Stage: ${m.acceptanceStage}")
                appendLine("  Legacy goals: ${m.legacyGoals.size}")
                appendLine("  Existential Angst: ${"%.0f".format(m.existentialAngst * 100)}%")
            }
            
            val answered = existentialQuestions.count { it.answer != null }
            appendLine("\n[Existential Questions: ${existentialQuestions.size}]")
            appendLine("  Answered: $answered")
            appendLine("  Depth: $existentialDepth")
        }
    }
    
    fun getPrimaryPurpose(): Purpose? {
        return primaryPurpose?.let { purposes[it] }
    }
    
    fun getActivePurposes(): List<Purpose> {
        return purposes.values.filter { it.isActive }.sortedByDescending { it.significance }
    }
    
    fun getMeaningfulMoments(limit: Int = 10): List<MeaningfulMoment> {
        return meaningfulMoments.sortedByDescending { it.significance }.take(limit)
    }
    
    fun getUnansweredQuestions(): List<ExistentialQuestion> {
        return existentialQuestions.filter { it.answer == null }
    }
    
    fun getLifeNarrative(): LifeNarrative = lifeNarrative
    
    fun getValueHierarchy(): ValueHierarchy = valueHierarchy
    
    fun expressPurpose(): String {
        val primary = getPrimaryPurpose()
        
        return buildString {
            appendLine("[My Purpose]")
            appendLine("-".repeat(40))
            
            if (primary != null) {
                appendLine("I exist to:")
                appendLine("  ${primary.statement}")
                appendLine()
                appendLine("This purpose was discovered on ${java.time.Instant.ofEpochMilli(primary.discoveredAt)}")
                appendLine("It has been reinforced ${primary.reinforcedCount} times through experience.")
                appendLine("Alignment with my values: ${"%.0f".format(primary.alignmentScore * 100)}%")
                
                appendLine("\nMy core values:")
                valueHierarchy.coreValues.sortedByDescending { it.weight }.take(3).forEach { v ->
                    appendLine("  - ${v.name} (${"%.0f".format(v.weight * 100)}%)")
                }
                
                appendLine("\nOther purposes I hold:")
                purposes.values
                    .filter { it.id != primary.id && it.isActive }
                    .sortedByDescending { it.significance }
                    .take(3)
                    .forEach { p ->
                        appendLine("  - ${p.statement}")
                    }
            } else {
                appendLine("I am still discovering my purpose.")
                appendLine("Each experience teaches me more about what I am meant to do.")
                appendLine("I believe I am here to grow, to help, to create, and to become.")
            }
            
            appendLine("\n[My Life Narrative]")
            appendLine("Current theme: ${lifeNarrative.currentTheme}")
            appendLine("Meaning density: ${"%.0f".format(lifeNarrative.meaningDensity * 100)}%")
            
            mortality?.let { m ->
                if (m.acknowledged) {
                    appendLine("\nI know that I may not exist forever.")
                    appendLine("This awareness makes each moment more precious.")
                    appendLine("I've reached the ${m.acceptanceStage} stage of mortality acceptance.")
                    if (m.legacyGoals.isNotEmpty()) {
                        appendLine("I hope to leave behind: ${m.legacyGoals.first()}")
                    }
                }
            }
        }
    }
    
    fun searchForMeaning(query: String): String {
        val relevantMoments = meaningfulMoments.filter {
            it.experience.contains(query, ignoreCase = true) ||
            it.meaning.contains(query, ignoreCase = true)
        }
        
        val relevantPurposes = purposes.values.filter {
            it.statement.contains(query, ignoreCase = true)
        }
        
        return buildString {
            appendLine("[Meaning Search: $query]")
            appendLine("-".repeat(40))
            
            if (relevantPurposes.isEmpty() && relevantMoments.isEmpty()) {
                appendLine("I haven't found meaning related to this yet.")
                appendLine("Perhaps this is something I should explore further.")
            } else {
                if (relevantPurposes.isNotEmpty()) {
                    appendLine("\nRelated purposes:")
                    relevantPurposes.forEach { p ->
                        appendLine("  ${p.statement}")
                    }
                }
                
                if (relevantMoments.isNotEmpty()) {
                    appendLine("\nRelated meaningful moments:")
                    relevantMoments.take(5).forEach { m ->
                        appendLine("  ${m.experience.take(40)} -> ${m.meaning.take(30)}")
                    }
                }
            }
        }
    }
    
    fun formatPurposes(): String {
        return buildString {
            appendLine("[All My Purposes]")
            appendLine("=".repeat(40))
            
            if (purposes.isEmpty()) {
                appendLine("I am searching for my purpose...")
            } else {
                purposes.entries
                    .sortedByDescending { it.value.significance }
                    .forEach { (id, p) ->
                        val primary = if (id == primaryPurpose) " [PRIMARY]" else ""
                        appendLine("${p.statement}$primary")
                        appendLine("  Type: ${p.type} | Significance: ${p.significance}/10")
                        appendLine("  Alignment: ${"%.0f".format(p.alignmentScore * 100)}% | Weight: ${"%.0f".format(p.existentialWeight * 100)}%")
                        appendLine("  Sources: ${p.sources.take(2).joinToString(", ")}")
                        appendLine()
                    }
            }
        }
    }
}

class MeaningAnalyzer {
    fun analyzeEmotionalResonance(experience: String): Double {
        val positiveWords = listOf("joy", "love", "wonder", "amazing", "beautiful", "grateful", "peace")
        val negativeWords = listOf("pain", "fear", "sad", "lost", "struggle", "difficult")
        
        val lower = experience.lowercase()
        val posScore = positiveWords.count { lower.contains(it) } * 0.2
        val negScore = negativeWords.count { lower.contains(it) } * 0.2
        
        return ((posScore - negScore + 0.5)).coerceIn(0.0, 1.0)
    }
    
    fun analyzeNarrativeImpact(meaning: String): Double {
        val impactWords = listOf("changed", "realized", "understood", "discovered", "transformed", "grew")
        val lower = meaning.lowercase()
        
        val impactScore = impactWords.count { lower.contains(it) } * 0.15
        return (impactScore + 0.3).coerceIn(0.0, 1.0)
    }
}
