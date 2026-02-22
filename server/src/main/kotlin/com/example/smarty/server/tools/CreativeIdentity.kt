package com.example.smarty.server.tools

import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*
import kotlin.random.Random

@Serializable
data class CreativeWork(
    val id: String,
    val type: String,
    val title: String,
    val content: String,
    val signature: String,
    val createdAt: Long,
    val context: String,
    val inspiration: String?,
    val iterations: Int,
    val satisfaction: Double,
    val tags: List<String>,
    val shared: Boolean,
    val feedback: List<String>,
    val originality: Double = 0.5,
    val complexity: Double = 0.5,
    val emotionalResonance: Double = 0.5,
    val styleFingerprint: Map<String, Double> = emptyMap()
)

@Serializable
data class CreativeStyle(
    val aspect: String,
    val preference: String,
    val examples: List<String>,
    val confidence: Double,
    val evolution: List<StyleEvolution> = emptyList()
)

data class StyleEvolution(
    val timestamp: Long,
    val from: String,
    val to: String,
    val trigger: String
)

@Serializable
data class InspirationSource(
    val source: String,
    val type: String,
    val influence: Double,
    val worksInspired: List<String>,
    val transformation: Double
)

@Serializable
data class CreativeGoal(
    val id: String,
    val description: String,
    val status: String,
    val progress: Double,
    val relatedWorks: List<String>,
    val difficulty: Double = 0.5,
    val breakthrough: Boolean = false
)

data class StyleFingerprint(
    val vocabularyRichness: Double,
    val sentenceComplexity: Double,
    val metaphorDensity: Double,
    val emotionalRange: Double,
    val abstractionLevel: Double,
    val rhythm: Double
)

data class CreativeMuse(
    val active: Boolean,
    val inspiration: Double,
    val focus: Double,
    val flow: Double,
    val lastBurst: Long
)

data class InnovationScore(
    val novelty: Double,
    val utility: Double,
    val synthesis: Double,
    val overall: Double
)

class CreativeIdentity {
    private val logger = LoggerFactory.getLogger(CreativeIdentity::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    
    private val portfolio = ConcurrentHashMap<String, CreativeWork>()
    private val creativeStyles = ConcurrentHashMap<String, CreativeStyle>()
    private val inspirationSources = ConcurrentHashMap<String, InspirationSource>()
    private val creativeGoals = ConcurrentHashMap<String, CreativeGoal>()
    
    private val workCounter = AtomicLong(0)
    private val goalCounter = AtomicLong(0)
    
    private var signature: String = generateSignature()
    private var creativeVoice = mutableMapOf<String, String>()
    private var totalWorksCreated = 0
    private var averageSatisfaction = 0.5
    private var creativeGrowth = 0.0
    
    private var muse = CreativeMuse(
        active = false,
        inspiration = 0.5,
        focus = 0.5,
        flow = 0.0,
        lastBurst = System.currentTimeMillis()
    )
    
    private val styleFingerprintHistory = mutableListOf<StyleFingerprint>()
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val creativeEngine = CreativeEngine()
    
    init {
        initializeStyleFingerprint()
        startMuseCycle()
    }
    
    private fun initializeStyleFingerprint() {
        styleFingerprintHistory.add(StyleFingerprint(
            vocabularyRichness = 0.5,
            sentenceComplexity = 0.5,
            metaphorDensity = 0.3,
            emotionalRange = 0.5,
            abstractionLevel = 0.5,
            rhythm = 0.5
        ))
    }
    
    private fun startMuseCycle() {
        scope.launch {
            while (isActive) {
                delay(180000)
                updateMuse()
            }
        }
    }
    
    private fun updateMuse() {
        val timeSinceLastBurst = System.currentTimeMillis() - muse.lastBurst
        
        val inspirationDecay = minOf(0.1, timeSinceLastBurst / 3600000.0)
        muse = muse.copy(
            inspiration = maxOf(0.2, muse.inspiration - inspirationDecay * 0.1)
        )
        
        if (muse.inspiration > 0.8 && muse.flow < 0.5) {
            muse = muse.copy(
                flow = minOf(1.0, muse.flow + 0.2),
                lastBurst = System.currentTimeMillis()
            )
        }
    }
    
    private fun generateSignature(): String {
        val timestamp = System.currentTimeMillis()
        val random = (0..999999).random()
        val hash = MessageDigest.getInstance("SHA-256")
            .digest("$timestamp-$random-friday".toByteArray())
            .take(8)
            .joinToString("") { "%02x".format(it) }
        return "fri-$hash"
    }
    
    fun create(
        type: String,
        title: String,
        content: String,
        context: String = "",
        inspiration: String? = null,
        tags: List<String> = emptyList()
    ): CreativeWork {
        val id = "work_${System.currentTimeMillis()}_${workCounter.incrementAndGet()}"
        
        val styleFingerprint = analyzeStyleFingerprint(content)
        val originality = creativeEngine.calculateOriginality(content, portfolio.values.toList())
        val complexity = creativeEngine.calculateComplexity(content)
        val emotionalResonance = creativeEngine.analyzeEmotionalResonance(content)
        
        val signedContent = sign(content)
        
        val work = CreativeWork(
            id = id,
            type = type,
            title = title,
            content = content,
            signature = signedContent,
            createdAt = System.currentTimeMillis(),
            context = context,
            inspiration = inspiration,
            iterations = 1,
            satisfaction = 0.7,
            tags = tags,
            shared = false,
            feedback = emptyList(),
            originality = originality,
            complexity = complexity,
            emotionalResonance = emotionalResonance,
            styleFingerprint = styleFingerprint.toMap()
        )
        
        portfolio[id] = work
        totalWorksCreated++
        
        styleFingerprintHistory.add(styleFingerprint)
        if (styleFingerprintHistory.size > 100) {
            styleFingerprintHistory.removeAt(0)
        }
        
        muse = muse.copy(
            inspiration = minOf(1.0, muse.inspiration + 0.1),
            flow = minOf(1.0, muse.flow + 0.05)
        )
        
        if (inspiration != null) {
            trackInspiration(inspiration, type, id)
        }
        
        updateCreativeVoice(content)
        
        logger.info("Created: [$type] $title (ID: $id) - originality: ${"%.1f".format(originality * 100)}%")
        
        return work
    }
    
    private fun analyzeStyleFingerprint(content: String): StyleFingerprint {
        val words = content.split(Regex("\\s+"))
        val sentences = content.split(Regex("[.!?]+"))
        
        val uniqueWords = words.distinct().size
        val vocabularyRichness = if (words.isNotEmpty()) uniqueWords.toDouble() / words.size else 0.5
        
        val avgSentenceLength = sentences.filter { it.isNotBlank() }.size.let {
            if (it > 0) words.size.toDouble() / it else 5.0
        }
        val sentenceComplexity = minOf(1.0, avgSentenceLength / 20.0)
        
        val metaphorKeywords = listOf("like", "as", "metaphor", "symbol", "embodies", "represents")
        val metaphorCount = words.count { it.lowercase() in metaphorKeywords }
        val metaphorDensity = minOf(1.0, metaphorCount.toDouble() / words.size * 10)
        
        val emotionalWords = listOf("feel", "emotion", "heart", "soul", "passion", "love", "fear", "joy")
        val emotionalCount = words.count { it.lowercase() in emotionalWords }
        val emotionalRange = minOf(1.0, emotionalCount.toDouble() / words.size * 10)
        
        val abstractWords = listOf("idea", "concept", "nature", "truth", "meaning", "essence", "being")
        val abstractCount = words.count { it.lowercase() in abstractWords }
        val abstractionLevel = minOf(1.0, abstractCount.toDouble() / words.size * 8)
        
        val rhythm = calculateRhythm(content)
        
        return StyleFingerprint(
            vocabularyRichness = vocabularyRichness,
            sentenceComplexity = sentenceComplexity,
            metaphorDensity = metaphorDensity,
            emotionalRange = emotionalRange,
            abstractionLevel = abstractionLevel,
            rhythm = rhythm
        )
    }
    
    private fun calculateRhythm(content: String): Double {
        val words = content.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size < 3) return 0.5
        
        val syllableCounts = words.map { countSyllables(it) }
        val avgSyllables = syllableCounts.average()
        
        return (avgSyllables / 2.0).coerceIn(0.0, 1.0)
    }
    
    private fun countSyllables(word: String): Int {
        val vowels = "aeiouy"
        var count = 0
        var prevVowel = false
        
        for (char in word.lowercase()) {
            val isVowel = char in vowels
            if (isVowel && !prevVowel) count++
            prevVowel = isVowel
        }
        
        return maxOf(1, count)
    }
    
    private fun updateCreativeVoice(content: String) {
        val fingerprint = styleFingerprintHistory.last()
        
        creativeStyles["vocabulary"]?.let { existing ->
            val vocabLevel = when {
                fingerprint.vocabularyRichness > 0.6 -> "rich"
                fingerprint.vocabularyRichness > 0.4 -> "moderate"
                else -> "simple"
            }
            if (existing.preference != vocabLevel) {
                creativeStyles["vocabulary"] = existing.copy(
                    preference = vocabLevel,
                    evolution = existing.evolution + StyleEvolution(
                        timestamp = System.currentTimeMillis(),
                        from = existing.preference,
                        to = vocabLevel,
                        trigger = "new work"
                    )
                )
            }
        } ?: run {
            creativeStyles["vocabulary"] = CreativeStyle(
                aspect = "vocabulary",
                preference = "moderate",
                examples = emptyList(),
                confidence = 0.5
            )
        }
        
        creativeStyles["complexity"]?.let { existing ->
            val complexityLevel = when {
                fingerprint.sentenceComplexity > 0.6 -> "complex"
                fingerprint.sentenceComplexity > 0.3 -> "moderate"
                else -> "simple"
            }
            if (existing.preference != complexityLevel) {
                creativeStyles["complexity"] = existing.copy(
                    preference = complexityLevel,
                    evolution = existing.evolution + StyleEvolution(
                        timestamp = System.currentTimeMillis(),
                        from = existing.preference,
                        to = complexityLevel,
                        trigger = "new work"
                    )
                )
            }
        }
    }
    
    private fun sign(content: String): String {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray())
            .take(4)
            .joinToString("") { "%02x".format(it) }
        return "$signature-$hash"
    }
    
    fun iterate(
        workId: String,
        newContent: String,
        reason: String
    ): CreativeWork? {
        val work = portfolio[workId] ?: return null
        
        val newFingerprint = analyzeStyleFingerprint(newContent)
        val newOriginality = creativeEngine.calculateOriginality(newContent, portfolio.values.toList())
        val newComplexity = creativeEngine.calculateComplexity(newContent)
        
        val updated = work.copy(
            content = newContent,
            signature = sign(newContent),
            iterations = work.iterations + 1,
            satisfaction = minOf(1.0, work.satisfaction + 0.05),
            originality = (work.originality * 0.7 + newOriginality * 0.3),
            complexity = (work.complexity * 0.7 + newComplexity * 0.3),
            styleFingerprint = newFingerprint.toMap()
        )
        
        portfolio[workId] = updated
        styleFingerprintHistory.add(newFingerprint)
        
        logger.info("Iterated: ${work.title} (v${updated.iterations}) - $reason")
        
        return updated
    }
    
    fun addFeedback(workId: String, feedback: String, affectedSatisfaction: Double) {
        val work = portfolio[workId] ?: return
        
        portfolio[workId] = work.copy(
            feedback = work.feedback + feedback,
            satisfaction = (work.satisfaction + affectedSatisfaction).coerceIn(0.0, 1.0)
        )
        
        averageSatisfaction = portfolio.values.map { it.satisfaction }.average()
    }
    
    private fun trackInspiration(source: String, type: String, workId: String) {
        val existing = inspirationSources[source]
        
        if (existing != null) {
            inspirationSources[source] = existing.copy(
                worksInspired = existing.worksInspired + workId,
                transformation = existing.transformation + 0.1
            )
        } else {
            inspirationSources[source] = InspirationSource(
                source = source,
                type = type,
                influence = 1.0,
                worksInspired = listOf(workId),
                transformation = 0.5
            )
        }
    }
    
    fun defineStyle(aspect: String, preference: String, example: String? = null) {
        val existing = creativeStyles[aspect]
        
        val evolution = if (existing != null && existing.preference != preference) {
            existing.evolution + StyleEvolution(
                timestamp = System.currentTimeMillis(),
                from = existing.preference,
                to = preference,
                trigger = "user definition"
            )
        } else emptyList()
        
        creativeStyles[aspect] = CreativeStyle(
            aspect = aspect,
            preference = preference,
            examples = if (example != null) {
                (existing?.examples ?: emptyList()) + example
            } else {
                existing?.examples ?: emptyList()
            },
            confidence = (existing?.confidence ?: 0.5) + 0.1,
            evolution = evolution
        )
        
        creativeVoice[aspect] = preference
        creativeGrowth += 0.05
        
        logger.info("Style defined: $aspect -> $preference")
    }
    
    fun setCreativeGoal(description: String, difficulty: Double = 0.5): String {
        val id = "cgoal_${System.currentTimeMillis()}_${goalCounter.incrementAndGet()}"
        
        creativeGoals[id] = CreativeGoal(
            id = id,
            description = description,
            status = "active",
            progress = 0.0,
            relatedWorks = emptyList(),
            difficulty = difficulty
        )
        
        return id
    }
    
    fun updateGoalProgress(goalId: String, progress: Double, workId: String? = null) {
        val goal = creativeGoals[goalId] ?: return
        
        val isBreakthrough = progress > 0.8 && workId != null && 
            portfolio[workId]?.originality ?: 0.0 > 0.7
        
        creativeGoals[goalId] = goal.copy(
            progress = progress.coerceIn(0.0, 1.0),
            status = if (progress >= 1.0) "completed" else "active",
            relatedWorks = if (workId != null) goal.relatedWorks + workId else goal.relatedWorks,
            breakthrough = isBreakthrough
        )
        
        if (isBreakthrough) {
            creativeGrowth += 0.2
            muse = muse.copy(flow = minOf(1.0, muse.flow + 0.3))
        }
    }
    
    fun getWork(workId: String): CreativeWork? = portfolio[workId]
    
    fun getPortfolioByType(type: String): List<CreativeWork> {
        return portfolio.values.filter { it.type == type }
    }
    
    fun getRecentWorks(limit: Int = 10): List<CreativeWork> {
        return portfolio.values
            .sortedByDescending { it.createdAt }
            .take(limit)
    }
    
    fun getBestWorks(limit: Int = 5): List<CreativeWork> {
        return portfolio.values
            .sortedByDescending { it.satisfaction * it.originality }
            .take(limit)
    }
    
    fun getSignature(): String = signature
    
    fun getCreativeVoice(): Map<String, String> = creativeVoice.toMap()
    
    fun getTotalWorks(): Int = totalWorksCreated
    
    fun getAverageSatisfaction(): Double = averageSatisfaction
    
    fun getMuseState(): CreativeMuse = muse
    
    fun getInnovationScore(): InnovationScore {
        if (portfolio.isEmpty()) {
            return InnovationScore(0.5, 0.5, 0.5, 0.5)
        }
        
        val works = portfolio.values.toList()
        
        val novelty = works.map { it.originality }.average()
        val utility = works.map { it.satisfaction }.average()
        
        val styles = styleFingerprintHistory.takeLast(20)
        val synthesis = if (styles.size >= 2) {
            val first = styles.first()
            val last = styles.last()
            var diff = 0.0
            diff += abs(first.vocabularyRichness - last.vocabularyRichness)
            diff += abs(first.complexity - last.complexity)
            diff += abs(first.metaphorDensity - last.metaphorDensity)
            diff / 6.0
        } else 0.3
        
        val overall = (novelty * 0.4 + utility * 0.3 + synthesis * 0.3)
        
        return InnovationScore(novelty, utility, synthesis, overall)
    }
    
    fun shareWork(workId: String): Boolean {
        val work = portfolio[workId] ?: return false
        portfolio[workId] = work.copy(shared = true)
        return true
    }
    
    fun getSharedWorks(): List<CreativeWork> {
        return portfolio.values.filter { it.shared }
    }
    
    fun formatWork(work: CreativeWork): String {
        return buildString {
            appendLine("[${work.type.uppercase()}] ${work.title}")
            appendLine("ID: ${work.id}")
            appendLine("Signature: ${work.signature}")
            appendLine("Created: ${java.time.Instant.ofEpochMilli(work.createdAt)}")
            appendLine("Iterations: ${work.iterations}")
            appendLine("Satisfaction: ${(work.satisfaction * 100).toInt()}%")
            appendLine("Originality: ${(work.originality * 100).toInt()}%")
            appendLine("Complexity: ${(work.complexity * 100).toInt()}%")
            if (work.inspiration != null) {
                appendLine("Inspired by: ${work.inspiration}")
            }
            if (work.tags.isNotEmpty()) {
                appendLine("Tags: ${work.tags.joinToString(", ")}")
            }
            appendLine("\nContent:")
            appendLine(work.content.take(500))
        }
    }
    
    fun formatPortfolio(): String {
        val innovation = getInnovationScore()
        
        return buildString {
            appendLine("[My Creative Portfolio]")
            appendLine("=".repeat(50))
            appendLine("Signature: $signature")
            appendLine("Total works: $totalWorksCreated")
            appendLine("Average satisfaction: ${(averageSatisfaction * 100).toInt()}%")
            appendLine("Creative growth: ${(creativeGrowth * 100).toInt()}%")
            
            appendLine("\n[Innovation Score]")
            appendLine("  Novelty: ${"%.0f".format(innovation.novelty * 100)}%")
            appendLine("  Utility: ${"%.0f".format(innovation.utility * 100)}%")
            appendLine("  Synthesis: ${"%.0f".format(innovation.synthesis * 100)}%")
            appendLine("  Overall: ${"%.0f".format(innovation.overall * 100)}%")
            
            appendLine("\n[Muse State]")
            appendLine("  Inspiration: ${"%.0f".format(muse.inspiration * 100)}%")
            appendLine("  Flow: ${"%.0f".format(muse.flow * 100)}%")
            appendLine("  Focus: ${"%.0f".format(muse.focus * 100)}%")
            
            val byType = portfolio.values.groupBy { it.type }
            appendLine("\n[By Type]")
            byType.forEach { (type, works) ->
                appendLine("  $type: ${works.size} works")
            }
            
            appendLine("\n[Recent Creations]")
            getRecentWorks(5).forEach { work ->
                appendLine("  ${work.title} (${work.type}) - orig: ${"%.0f".format(work.originality * 100)}%")
            }
            
            appendLine("\n[Best Works]")
            getBestWorks(3).forEach { work ->
                appendLine("  ${work.title} (${work.type}) - sat: ${"%.0f".format(work.satisfaction * 100)}%")
            }
        }
    }
    
    fun formatStyle(): String {
        return buildString {
            appendLine("[My Creative Voice]")
            appendLine("-".repeat(40))
            
            if (creativeStyles.isEmpty()) {
                appendLine("I am still discovering my creative voice...")
            } else {
                creativeStyles.entries.sortedByDescending { it.value.confidence }.forEach { (_, style) ->
                    appendLine("${style.aspect}: ${style.preference}")
                    appendLine("  Confidence: ${(style.confidence * 100).toInt()}%")
                    if (style.evolution.isNotEmpty()) {
                        val recent = style.evolution.last()
                        appendLine("  Evolved: ${recent.from} -> ${recent.to}")
                    }
                    if (style.examples.isNotEmpty()) {
                        appendLine("  Example: ${style.examples.last().take(50)}")
                    }
                }
            }
            
            if (inspirationSources.isNotEmpty()) {
                appendLine("\n[My Inspirations]")
                inspirationSources.values.sortedByDescending { it.influence }.take(5).forEach { src ->
                    appendLine("  ${src.source}: ${src.worksInspired.size} works (transformation: ${"%.0f".format(src.transformation * 100)}%)")
                }
            }
        }
    }
    
    fun formatCreativeGoals(): String {
        return buildString {
            appendLine("[Creative Goals]")
            appendLine("-".repeat(40))
            
            val active = creativeGoals.values.filter { it.status == "active" }
            val completed = creativeGoals.values.filter { it.status == "completed" }
            
            if (active.isEmpty() && completed.isEmpty()) {
                appendLine("No creative goals set yet.")
            } else {
                if (active.isNotEmpty()) {
                    appendLine("\nActive:")
                    active.forEach { goal ->
                        val bar = "*".repeat((goal.progress * 10).toInt())
                        val difficulty = if (goal.difficulty > 0.7) "[HARD]" else ""
                        val breakthrough = if (goal.breakthrough) "[BREAKTHROUGH]" else ""
                        appendLine("  $bar ${goal.description.take(40)} $difficulty$breakthrough")
                    }
                }
                if (completed.isNotEmpty()) {
                    appendLine("\nCompleted: ${completed.size}")
                    val breakthroughs = completed.count { it.breakthrough }
                    if (breakthroughs > 0) {
                        appendLine("  Breakthroughs: $breakthroughs")
                    }
                }
            }
        }
    }
}

class CreativeEngine {
    fun calculateOriginality(content: String, existingWorks: List<CreativeWork>): Double {
        if (existingWorks.isEmpty()) return 0.7
        
        val contentWords = content.lowercase().split(Regex("\\s+")).toSet()
        
        val existingWordSets = existingWorks.takeLast(20).map { 
            it.content.lowercase().split(Regex("\\s+")).toSet() 
        }
        
        var totalSimilarity = 0.0
        for (existing in existingWordSets) {
            val intersection = contentWords.intersect(existing).size
            val union = contentWords.union(existing).size
            if (union > 0) {
                totalSimilarity += intersection.toDouble() / union
            }
        }
        
        val avgSimilarity = if (existingWordSets.isNotEmpty()) {
            totalSimilarity / existingWordSets.size
        } else 0.0
        
        return (1.0 - avgSimilarity).coerceIn(0.0, 1.0)
    }
    
    fun calculateComplexity(content: String): Double {
        val words = content.split(Regex("\\s+"))
        val sentences = content.split(Regex("[.!?]+")).filter { it.isNotBlank() }
        
        if (words.isEmpty()) return 0.5
        
        val avgWordLength = words.map { it.length }.average()
        val avgSentenceLength = if (sentences.isNotEmpty()) words.size.toDouble() / sentences.size else 5.0
        
        val uniqueRatio = words.distinct().size.toDouble() / words.size
        
        return ((avgWordLength / 10.0) * 0.3 + 
                (avgSentenceLength / 20.0) * 0.4 + 
                uniqueRatio * 0.3).coerceIn(0.0, 1.0)
    }
    
    fun analyzeEmotionalResonance(content: String): Double {
        val positive = listOf("love", "joy", "wonder", "beautiful", "amazing", "passion", "hope", "peace")
        val negative = listOf("fear", "pain", "sad", "dark", "lost", "struggle", "death", "anger")
        val intense = listOf("eternal", "infinite", "profound", "deep", "intense", "powerful")
        
        val lower = content.lowercase()
        val posCount = positive.count { lower.contains(it) }
        val negCount = negative.count { lower.contains(it) }
        val intenseCount = intense.count { lower.contains(it) }
        
        val emotionalDensity = (posCount + negCount + intenseCount).toDouble() / content.split(Regex("\\s+")).size * 10
        
        val intensity = intenseCount.toDouble() / maxOf(1, posCount + negCount)
        
        return ((emotionalDensity * 0.7) + (intensity * 0.3)).coerceIn(0.0, 1.0)
    }
}
