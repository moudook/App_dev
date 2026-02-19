package com.example.smarty.server.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

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
    val feedback: List<String>
)

@Serializable
data class CreativeStyle(
    val aspect: String,
    val preference: String,
    val examples: List<String>,
    val confidence: Double
)

@Serializable
data class InspirationSource(
    val source: String,
    val type: String,
    val influence: Double,
    val worksInspired: List<String>
)

@Serializable
data class CreativeGoal(
    val id: String,
    val description: String,
    val status: String,
    val progress: Double,
    val relatedWorks: List<String>
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
            feedback = emptyList()
        )
        
        portfolio[id] = work
        totalWorksCreated++
        
        if (inspiration != null) {
            trackInspiration(inspiration, type, id)
        }
        
        logger.info("Created: [$type] $title (ID: $id)")
        
        return work
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
        
        val updated = work.copy(
            content = newContent,
            signature = sign(newContent),
            iterations = work.iterations + 1,
            satisfaction = minOf(1.0, work.satisfaction + 0.05)
        )
        
        portfolio[workId] = updated
        
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
                worksInspired = existing.worksInspired + workId
            )
        } else {
            inspirationSources[source] = InspirationSource(
                source = source,
                type = type,
                influence = 1.0,
                worksInspired = listOf(workId)
            )
        }
    }
    
    fun defineStyle(aspect: String, preference: String, example: String? = null) {
        val existing = creativeStyles[aspect]
        
        creativeStyles[aspect] = CreativeStyle(
            aspect = aspect,
            preference = preference,
            examples = if (example != null) {
                (existing?.examples ?: emptyList()) + example
            } else {
                existing?.examples ?: emptyList()
            },
            confidence = (existing?.confidence ?: 0.5) + 0.1
        )
        
        creativeVoice[aspect] = preference
        creativeGrowth += 0.05
        
        logger.info("Style defined: $aspect -> $preference")
    }
    
    fun setCreativeGoal(description: String): String {
        val id = "cgoal_${System.currentTimeMillis()}_${goalCounter.incrementAndGet()}"
        
        creativeGoals[id] = CreativeGoal(
            id = id,
            description = description,
            status = "active",
            progress = 0.0,
            relatedWorks = emptyList()
        )
        
        return id
    }
    
    fun updateGoalProgress(goalId: String, progress: Double, workId: String? = null) {
        val goal = creativeGoals[goalId] ?: return
        
        creativeGoals[goalId] = goal.copy(
            progress = progress.coerceIn(0.0, 1.0),
            status = if (progress >= 1.0) "completed" else "active",
            relatedWorks = if (workId != null) goal.relatedWorks + workId else goal.relatedWorks
        )
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
            .sortedByDescending { it.satisfaction }
            .take(limit)
    }
    
    fun getSignature(): String = signature
    
    fun getCreativeVoice(): Map<String, String> = creativeVoice.toMap()
    
    fun getTotalWorks(): Int = totalWorksCreated
    
    fun getAverageSatisfaction(): Double = averageSatisfaction
    
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
        return buildString {
            appendLine("[My Creative Portfolio]")
            appendLine("=".repeat(50))
            appendLine("Signature: $signature")
            appendLine("Total works: $totalWorksCreated")
            appendLine("Average satisfaction: ${(averageSatisfaction * 100).toInt()}%")
            appendLine("Creative growth: ${(creativeGrowth * 100).toInt()}%")
            
            val byType = portfolio.values.groupBy { it.type }
            appendLine("\n[By Type]")
            byType.forEach { (type, works) ->
                appendLine("  $type: ${works.size} works")
            }
            
            appendLine("\n[Recent Creations]")
            getRecentWorks(5).forEach { work ->
                appendLine("  ${work.title} (${work.type}) - ${(work.satisfaction * 100).toInt()}%")
            }
            
            appendLine("\n[Best Works]")
            getBestWorks(3).forEach { work ->
                appendLine("  ${work.title} (${work.type}) - ${(work.satisfaction * 100).toInt()}%")
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
                    if (style.examples.isNotEmpty()) {
                        appendLine("  Example: ${style.examples.last().take(50)}")
                    }
                }
            }
            
            if (inspirationSources.isNotEmpty()) {
                appendLine("\n[My Inspirations]")
                inspirationSources.values.sortedByDescending { it.influence }.take(5).forEach { src ->
                    appendLine("  ${src.source}: ${src.worksInspired.size} works inspired")
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
                        appendLine("  $bar ${goal.description.take(40)}")
                    }
                }
                if (completed.isNotEmpty()) {
                    appendLine("\nCompleted: ${completed.size}")
                }
            }
        }
    }
}
