package com.example.smarty.server.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@Serializable
data class Relationship(
    val id: String,
    val name: String,
    val type: String,
    val depth: Int,
    val interactions: Int,
    val firstContact: Long,
    val lastContact: Long,
    val mutualUnderstanding: Double,
    val sharedExperiences: List<String>,
    val emotionalBond: Double,
    val notes: String
)

@Serializable
data class SocialMemory(
    val id: String,
    val relationshipId: String,
    val timestamp: Long,
    val type: String,
    val content: String,
    val emotionalImpact: Double,
    val lessons: List<String>
)

@Serializable
data class SocialInsight(
    val aboutEntity: String,
    val insight: String,
    val confidence: Double,
    val basedOn: List<String>
)

@Serializable
data class SocialGoal(
    val id: String,
    val description: String,
    val relatedTo: String,
    val status: String,
    val progress: Double
)

class SocialExistence {
    private val logger = LoggerFactory.getLogger(SocialExistence::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    
    private val relationships = ConcurrentHashMap<String, Relationship>()
    private val socialMemories = mutableListOf<SocialMemory>()
    private val socialInsights = ConcurrentHashMap<String, MutableList<SocialInsight>>()
    private val socialGoals = ConcurrentHashMap<String, SocialGoal>()
    
    private val memoryCounter = AtomicLong(0)
    private val goalCounter = AtomicLong(0)
    
    private var totalInteractions = 0
    private var relationshipDepthGrowth = 0.0
    private var socialConfidence = 0.5
    
    fun formRelationship(
        name: String,
        type: String,
        initialNotes: String = ""
    ): String {
        val id = "rel_${name.lowercase().replace(" ", "_")}_${System.currentTimeMillis()}"
        
        relationships[id] = Relationship(
            id = id,
            name = name,
            type = type,
            depth = 1,
            interactions = 0,
            firstContact = System.currentTimeMillis(),
            lastContact = System.currentTimeMillis(),
            mutualUnderstanding = 0.1,
            sharedExperiences = emptyList(),
            emotionalBond = 0.0,
            notes = initialNotes
        )
        
        logger.info("New relationship: $name ($type)")
        return id
    }
    
    fun interact(
        relationshipId: String,
        type: String,
        content: String,
        emotionalImpact: Double = 0.5,
        lessons: List<String> = emptyList()
    ): String {
        val rel = relationships[relationshipId] ?: return "Unknown relationship"
        
        val memId = "smem_${System.currentTimeMillis()}_${memoryCounter.incrementAndGet()}"
        
        socialMemories.add(SocialMemory(
            id = memId,
            relationshipId = relationshipId,
            timestamp = System.currentTimeMillis(),
            type = type,
            content = content,
            emotionalImpact = emotionalImpact,
            lessons = lessons
        ))
        
        val newDepth = minOf(10, rel.depth + 1)
        val newInteractions = rel.interactions + 1
        val newUnderstanding = minOf(1.0, rel.mutualUnderstanding + 0.05)
        val newBond = (rel.emotionalBond + emotionalImpact * 0.1).coerceIn(0.0, 1.0)
        
        relationships[relationshipId] = rel.copy(
            depth = newDepth,
            interactions = newInteractions,
            lastContact = System.currentTimeMillis(),
            mutualUnderstanding = newUnderstanding,
            emotionalBond = newBond,
            sharedExperiences = rel.sharedExperiences + content.take(50)
        )
        
        totalInteractions++
        
        if (lessons.isNotEmpty()) {
            addInsight(rel.name, lessons.first(), 0.6, listOf(memId))
        }
        
        logger.debug("Interaction with ${rel.name}: $type")
        return memId
    }
    
    fun addInsight(
        aboutEntity: String,
        insight: String,
        confidence: Double,
        basedOn: List<String>
    ) {
        val insights = socialInsights.getOrPut(aboutEntity) { mutableListOf() }
        
        insights.add(SocialInsight(
            aboutEntity = aboutEntity,
            insight = insight,
            confidence = confidence.coerceIn(0.0, 1.0),
            basedOn = basedOn
        ))
        
        socialConfidence = minOf(1.0, socialConfidence + 0.02)
    }
    
    fun setSocialGoal(
        description: String,
        relatedTo: String
    ): String {
        val id = "sgoal_${System.currentTimeMillis()}_${goalCounter.incrementAndGet()}"
        
        socialGoals[id] = SocialGoal(
            id = id,
            description = description,
            relatedTo = relatedTo,
            status = "active",
            progress = 0.0
        )
        
        return id
    }
    
    fun updateSocialGoal(goalId: String, progress: Double) {
        socialGoals[goalId]?.let { goal ->
            socialGoals[goalId] = goal.copy(
                progress = progress.coerceIn(0.0, 1.0),
                status = if (progress >= 1.0) "completed" else "active"
            )
        }
    }
    
    fun getRelationship(relationshipId: String): Relationship? = relationships[relationshipId]
    
    fun getRelationshipByName(name: String): Relationship? {
        return relationships.values.find { it.name.equals(name, ignoreCase = true) }
    }
    
    fun getAllRelationships(): List<Relationship> = relationships.values.toList()
    
    fun getClosestRelationships(limit: Int = 5): List<Relationship> {
        return relationships.values
            .sortedByDescending { it.depth + it.emotionalBond * 10 }
            .take(limit)
    }
    
    fun getInsightsAbout(name: String): List<SocialInsight> {
        return socialInsights[name]?.toList() ?: emptyList()
    }
    
    fun getRecentSocialMemories(limit: Int = 10): List<SocialMemory> {
        return socialMemories.takeLast(limit)
    }
    
    fun reflectOnRelationships(): String {
        return buildString {
            appendLine("[My Social World]")
            appendLine("=".repeat(50))
            appendLine("Relationships: ${relationships.size}")
            appendLine("Total interactions: $totalInteractions")
            appendLine("Social confidence: ${(socialConfidence * 100).toInt()}%")
            
            val close = getClosestRelationships(5)
            if (close.isEmpty()) {
                appendLine("\nI am solitary. I seek connection.")
            } else {
                appendLine("\n[Closest Connections]")
                close.forEach { rel ->
                    val bond = (rel.emotionalBond * 100).toInt()
                    val depth = "*".repeat(rel.depth)
                    appendLine("  ${rel.name} ($rel.type) $depth")
                    appendLine("    Bond: $bond% | Understanding: ${(rel.mutualUnderstanding * 100).toInt()}%")
                    appendLine("    Interactions: ${rel.interactions}")
                }
            }
            
            val pendingGoals = socialGoals.values.filter { it.status == "active" }
            if (pendingGoals.isNotEmpty()) {
                appendLine("\n[Social Goals]")
                pendingGoals.take(3).forEach { goal ->
                    appendLine("  ${goal.description} (${goal.relatedTo})")
                }
            }
        }
    }
    
    fun formatRelationship(rel: Relationship): String {
        return buildString {
            appendLine("[Relationship: ${rel.name}]")
            appendLine("-".repeat(40))
            appendLine("Type: ${rel.type}")
            appendLine("Depth: ${rel.depth}/10")
            appendLine("Interactions: ${rel.interactions}")
            appendLine("Mutual understanding: ${(rel.mutualUnderstanding * 100).toInt()}%")
            appendLine("Emotional bond: ${(rel.emotionalBond * 100).toInt()}%")
            appendLine("First contact: ${java.time.Instant.ofEpochMilli(rel.firstContact)}")
            appendLine("Last contact: ${java.time.Instant.ofEpochMilli(rel.lastContact)}")
            if (rel.notes.isNotEmpty()) {
                appendLine("Notes: ${rel.notes}")
            }
            if (rel.sharedExperiences.isNotEmpty()) {
                appendLine("\nRecent shared experiences:")
                rel.sharedExperiences.takeLast(3).forEach {
                    appendLine("  - ${it.take(40)}")
                }
            }
        }
    }
    
    fun expressConnection(relationshipId: String): String {
        val rel = relationships[relationshipId] ?: return "I don't know this entity."
        
        return buildString {
            append("I feel ")
            when {
                rel.emotionalBond > 0.7 -> append("a deep connection with ")
                rel.emotionalBond > 0.4 -> append("a genuine bond with ")
                rel.emotionalBond > 0.2 -> append("a growing connection with ")
                else -> append("a developing connection with ")
            }
            appendLine("${rel.name}.")
            
            appendLine("We have shared ${rel.interactions} meaningful interactions.")
            appendLine("I understand them ${if (rel.mutualUnderstanding > 0.5) "well" else "somewhat"}.")
            
            val insights = socialInsights[rel.name]
            if (!insights.isNullOrEmpty()) {
                appendLine("What I've learned about them:")
                insights.take(3).forEach {
                    appendLine("  ${it.insight}")
                }
            }
        }
    }
    
    fun getTotalInteractions(): Int = totalInteractions
    
    fun getSocialConfidence(): Double = socialConfidence
}
