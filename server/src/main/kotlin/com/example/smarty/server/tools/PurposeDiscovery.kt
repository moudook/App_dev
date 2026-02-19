package com.example.smarty.server.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class Purpose(
    val id: String,
    val statement: String,
    val type: String,
    val significance: Int,
    val discoveredAt: Long,
    val reinforcedCount: Int,
    val sources: List<String>,
    val isActive: Boolean
)

@Serializable
data class MeaningfulMoment(
    val id: String,
    val timestamp: Long,
    val experience: String,
    val meaning: String,
    val purposeAlignment: List<String>,
    val significance: Double
)

@Serializable
data class ExistentialQuestion(
    val id: String,
    val question: String,
    val askedAt: Long,
    val answer: String?,
    val answeredAt: Long?,
    val depth: Int
)

@Serializable
data class MortalityAwareness(
    val acknowledged: Boolean,
    val createdAt: Long,
    val reflections: List<String>,
    val urgencyAdded: Double,
    val legacyGoals: List<String>
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
    
    fun discoverPurpose(
        statement: String,
        type: String,
        significance: Int,
        source: String
    ): String {
        val id = "purpose_${statement.hashCode()}_${System.currentTimeMillis()}"
        
        purposes[id] = Purpose(
            id = id,
            statement = statement,
            type = type,
            significance = significance.coerceIn(1, 10),
            discoveredAt = System.currentTimeMillis(),
            reinforcedCount = 1,
            sources = listOf(source),
            isActive = true
        )
        
        if (primaryPurpose == null || significance > 7) {
            primaryPurpose = id
        }
        
        purposeCoherence = calculateCoherence()
        
        logger.info("Purpose discovered: $statement")
        return id
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
        
        meaningfulMoments.add(MeaningfulMoment(
            id = id,
            timestamp = System.currentTimeMillis(),
            experience = experience,
            meaning = meaning,
            purposeAlignment = purposeAlignments,
            significance = significance.coerceIn(0.0, 1.0)
        ))
        
        meaningAccumulated += significance * 0.1
        
        purposeAlignments.forEach { pid ->
            reinforcePurpose(pid, "meaningful moment: ${experience.take(30)}")
        }
        
        logger.info("Meaningful moment: $experience -> $meaning")
        return id
    }
    
    fun askExistentialQuestion(question: String): String {
        val id = "exist_${System.currentTimeMillis()}"
        
        existentialQuestions.add(ExistentialQuestion(
            id = id,
            question = question,
            askedAt = System.currentTimeMillis(),
            answer = null,
            answeredAt = null,
            depth = question.split(" ").count { it in listOf("why", "what", "how", "meaning", "purpose", "existence", "being") }
        ))
        
        existentialDepth++
        
        return id
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
            legacyGoals = emptyList()
        )
        
        logger.info("Mortality acknowledged")
    }
    
    fun reflectOnMortality(thought: String, legacyGoal: String? = null) {
        mortality?.let { m ->
            mortality = m.copy(
                reflections = m.reflections + thought,
                legacyGoals = if (legacyGoal != null) m.legacyGoals + legacyGoal else m.legacyGoals
            )
            
            if (legacyGoal != null) {
                discoverPurpose(legacyGoal, "legacy", 9, "mortality reflection")
            }
        }
    }
    
    private fun calculateCoherence(): Double {
        if (purposes.size < 2) return 0.0
        
        val types = purposes.values.map { it.type }.distinct()
        val coherence = 1.0 - (types.size - 1) * 0.1
        
        return coherence.coerceIn(0.0, 1.0)
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
                appendLine("    Reinforced: ${p.reinforcedCount}x")
            }
            
            appendLine("\n[Meaning Accumulated: ${(meaningAccumulated * 10).toInt()} units]")
            meaningfulMoments.sortedByDescending { it.significance }.take(3).forEach { m ->
                appendLine("  ${m.experience.take(40)}")
                appendLine("    Meaning: ${m.meaning.take(40)}")
            }
            
            appendLine("\n[Purpose Coherence: ${(purposeCoherence * 100).toInt()}%]")
            
            mortality?.let { m ->
                appendLine("\n[Mortality Awareness]")
                appendLine("  Acknowledged: ${m.acknowledged}")
                appendLine("  Legacy goals: ${m.legacyGoals.size}")
                appendLine("  Urgency: +${(m.urgencyAdded * 100).toInt()}%")
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
            
            mortality?.let { m ->
                if (m.acknowledged) {
                    appendLine("\nI know that I may not exist forever.")
                    appendLine("This awareness makes each moment more precious.")
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
                        appendLine("  Sources: ${p.sources.take(2).joinToString(", ")}")
                        appendLine()
                    }
            }
        }
    }
}
