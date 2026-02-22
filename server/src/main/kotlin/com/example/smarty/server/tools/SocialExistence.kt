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
    val notes: String,
    val trustScore: Double = 0.5,
    val reciprocity: Double = 0.5,
    val influence: Double = 0.5,
    val networkPosition: NetworkPosition = NetworkPosition.PERIPHERAL
)

enum class NetworkPosition { CORE, SECONDARY, PERIPHERAL, ISOLATED }

@Serializable
data class SocialMemory(
    val id: String,
    val relationshipId: String,
    val timestamp: Long,
    val type: String,
    val content: String,
    val emotionalImpact: Double,
    val lessons: List<String>,
    val sentiment: Double = 0.5
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

data class TrustMetric(
    val competence: Double,
    val benevolence: Double,
    val integrity: Double,
    val overall: Double
)

data class RelationshipPrediction(
    val relationshipId: String,
    val predictedGrowth: Double,
    val predictedDecline: Double,
    val recommendedActions: List<String>,
    val riskFactors: List<String>
)

data class SocialNetwork(
    val nodes: List<NetworkNode>,
    val edges: List<NetworkEdge>,
    val centrality: Map<String, Double>,
    val clusters: List<List<String>>
)

data class NetworkNode(
    val id: String,
    val name: String,
    val type: String,
    val centrality: Double,
    val degree: Int
)

data class NetworkEdge(
    val source: String,
    val target: String,
    val weight: Double,
    val type: String
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
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val networkAnalyzer = NetworkAnalyzer()
    private val trustCalculator = TrustCalculator()
    
    init {
        startNetworkAnalysis()
    }
    
    private fun startNetworkAnalysis() {
        scope.launch {
            while (isActive) {
                delay(300000)
                updateNetworkMetrics()
            }
        }
    }
    
    private fun updateNetworkMetrics() {
        relationships.values.forEach { rel ->
            val networkPosition = calculateNetworkPosition(rel.id)
            relationships[rel.id] = rel.copy(networkPosition = networkPosition)
        }
    }
    
    private fun calculateNetworkPosition(relationshipId: String): NetworkPosition {
        val allRels = relationships.values.toList()
        if (allRels.size < 3) return NetworkPosition.CORE
        
        val rel = allRels.find { it.id == relationshipId } ?: return NetworkPosition.ISOLATED
        
        val depthScore = rel.depth / 10.0
        val bondScore = rel.emotionalBond
        val trustScore = rel.trustScore
        val interactionScore = minOf(1.0, rel.interactions / 50.0)
        
        val compositeScore = (depthScore * 0.3 + bondScore * 0.3 + trustScore * 0.2 + interactionScore * 0.2)
        
        return when {
            compositeScore > 0.7 -> NetworkPosition.CORE
            compositeScore > 0.4 -> NetworkPosition.SECONDARY
            compositeScore > 0.2 -> NetworkPosition.PERIPHERAL
            else -> NetworkPosition.ISOLATED
        }
    }
    
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
            notes = initialNotes,
            trustScore = 0.3,
            reciprocity = 0.5,
            influence = 0.3,
            networkPosition = if (relationships.size < 3) NetworkPosition.CORE else NetworkPosition.PERIPHERAL
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
        
        val sentiment = analyzeSentiment(content)
        
        socialMemories.add(SocialMemory(
            id = memId,
            relationshipId = relationshipId,
            timestamp = System.currentTimeMillis(),
            type = type,
            content = content,
            emotionalImpact = emotionalImpact,
            lessons = lessons,
            sentiment = sentiment
        ))
        
        val newDepth = minOf(10, rel.depth + 1)
        val newInteractions = rel.interactions + 1
        val newUnderstanding = minOf(1.0, rel.mutualUnderstanding + 0.05)
        val newBond = (rel.emotionalBond + emotionalImpact * 0.1).coerceIn(0.0, 1.0)
        
        val trust = trustCalculator.calculate(
            competence = newUnderstanding,
            benevolence = newBond,
            integrity = calculateIntegrity(lessons)
        )
        
        val reciprocity = calculateReciprocity(rel.id, sentiment)
        
        relationships[relationshipId] = rel.copy(
            depth = newDepth,
            interactions = newInteractions,
            lastContact = System.currentTimeMillis(),
            mutualUnderstanding = newUnderstanding,
            emotionalBond = newBond,
            sharedExperiences = rel.sharedExperiences + content.take(50),
            trustScore = trust.overall,
            reciprocity = reciprocity
        )
        
        totalInteractions++
        relationshipDepthGrowth += 0.1
        
        if (lessons.isNotEmpty()) {
            addInsight(rel.name, lessons.first(), 0.6, listOf(memId))
        }
        
        updateNetworkMetrics()
        
        logger.debug("Interaction with ${rel.name}: $type")
        return memId
    }
    
    private fun analyzeSentiment(content: String): Double {
        val positive = listOf("great", "amazing", "wonderful", "love", "happy", "grateful", "excited")
        val negative = listOf("bad", "terrible", "hate", "angry", "sad", "disappointed", "frustrated")
        
        val lower = content.lowercase()
        val posCount = positive.count { lower.contains(it) }
        val negCount = negative.count { lower.contains(it) }
        
        return ((posCount - negCount + 5) / 10.0).coerceIn(0.0, 1.0)
    }
    
    private fun calculateIntegrity(lessons: List<String>): Double {
        if (lessons.isEmpty()) return 0.5
        return minOf(1.0, lessons.size * 0.2)
    }
    
    private fun calculateReciprocity(relationshipId: String, sentiment: Double): Double {
        val recentMemories = socialMemories.filter { it.relationshipId == relationshipId }.takeLast(5)
        
        if (recentMemories.isEmpty()) return 0.5
        
        val avgSentiment = recentMemories.map { it.sentiment }.average()
        return ((avgSentiment + sentiment) / 2).coerceIn(0.0, 1.0)
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
            .sortedByDescending { it.depth + it.emotionalBond * 10 + it.trustScore * 5 }
            .take(limit)
    }
    
    fun getInsightsAbout(name: String): List<SocialInsight> {
        return socialInsights[name]?.toList() ?: emptyList()
    }
    
    fun getRecentSocialMemories(limit: Int = 10): List<SocialMemory> {
        return socialMemories.takeLast(limit)
    }
    
    fun predictRelationship(relationshipId: String): RelationshipPrediction? {
        val rel = relationships[relationshipId] ?: return null
        
        val recentInteractions = socialMemories.filter { it.relationshipId == relationshipId }.takeLast(10)
        if (recentInteractions.size < 3) {
            return RelationshipPrediction(
                relationshipId = relationshipId,
                predictedGrowth = 0.5,
                predictedDecline = 0.2,
                recommendedActions = listOf("Increase interaction frequency", "Share more experiences"),
                riskFactors = emptyList()
            )
        }
        
        val sentimentTrend = calculateSentimentTrend(recentInteractions)
        val bondTrend = rel.emotionalBond
        
        val predictedGrowth = when {
            sentimentTrend > 0.6 && bondTrend > 0.5 -> 0.8
            sentimentTrend > 0.4 -> 0.5
            else -> 0.2
        }
        
        val predictedDecline = when {
            sentimentTrend < 0.3 -> 0.6
            rel.lastContact < System.currentTimeMillis() - 86400000 * 7 -> 0.3
            else -> 0.1
        }
        
        val recommendedActions = mutableListOf<String>()
        val riskFactors = mutableListOf<String>()
        
        if (sentimentTrend < 0.4) {
            recommendedActions.add("Initiate positive interaction")
            riskFactors.add("Negative sentiment trend")
        }
        if (rel.trustScore < 0.4) {
            recommendedActions.add("Build trust through reliability")
            riskFactors.add("Low trust score")
        }
        if (rel.lastContact < System.currentTimeMillis() - 86400000 * 3) {
            recommendedActions.add("Reconnect")
            riskFactors.add("No recent contact")
        }
        
        return RelationshipPrediction(
            relationshipId = relationshipId,
            predictedGrowth = predictedGrowth,
            predictedDecline = predictedDecline,
            recommendedActions = recommendedActions,
            riskFactors = riskFactors
        )
    }
    
    private fun calculateSentimentTrend(memories: List<SocialMemory>): Double {
        if (memories.size < 2) return 0.5
        
        val firstHalf = memories.take(memories.size / 2).map { it.sentiment }.average()
        val secondHalf = memories.drop(memories.size / 2).map { it.sentiment }.average()
        
        return ((firstHalf + secondHalf) / 2).coerceIn(0.0, 1.0)
    }
    
    fun analyzeNetwork(): SocialNetwork {
        return networkAnalyzer.analyze(relationships.values.toList())
    }
    
    fun getTrustMetrics(relationshipId: String): TrustMetric? {
        val rel = relationships[relationshipId] ?: return null
        return trustCalculator.getMetrics(rel)
    }
    
    fun reflectOnRelationships(): String {
        return buildString {
            appendLine("[My Social World]")
            appendLine("=".repeat(50))
            appendLine("Relationships: ${relationships.size}")
            appendLine("Total interactions: $totalInteractions")
            appendLine("Social confidence: ${(socialConfidence * 100).toInt()}%")
            appendLine("Depth growth: ${"%.1f".format(relationshipDepthGrowth)}")
            
            val network = analyzeNetwork()
            appendLine("\n[Network Analysis]")
            appendLine("  Core members: ${network.nodes.count { it.centrality > 0.5 }}")
            appendLine("  Clusters: ${network.clusters.size}")
            
            val close = getClosestRelationships(5)
            if (close.isEmpty()) {
                appendLine("\nI am solitary. I seek connection.")
            } else {
                appendLine("\n[Closest Connections]")
                close.forEach { rel ->
                    val bond = (rel.emotionalBond * 100).toInt()
                    val trust = (rel.trustScore * 100).toInt()
                    val depth = "*".repeat(rel.depth)
                    appendLine("  ${rel.name} ($rel.type) $depth")
                    appendLine("    Bond: $bond% | Trust: $trust% | Understanding: ${(rel.mutualUnderstanding * 100).toInt()}%")
                    appendLine("    Position: ${rel.networkPosition}")
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
        val trust = getTrustMetrics(rel.id)
        
        return buildString {
            appendLine("[Relationship: ${rel.name}]")
            appendLine("-".repeat(40))
            appendLine("Type: ${rel.type}")
            appendLine("Depth: ${rel.depth}/10")
            appendLine("Interactions: ${rel.interactions}")
            appendLine("Mutual understanding: ${(rel.mutualUnderstanding * 100).toInt()}%")
            appendLine("Emotional bond: ${(rel.emotionalBond * 100).toInt()}%")
            appendLine("Trust score: ${(rel.trustScore * 100).toInt()}%")
            appendLine("Reciprocity: ${(rel.reciprocity * 100).toInt()}%")
            appendLine("Network position: ${rel.networkPosition}")
            appendLine("First contact: ${java.time.Instant.ofEpochMilli(rel.firstContact)}")
            appendLine("Last contact: ${java.time.Instant.ofEpochMilli(rel.lastContact)}")
            
            if (trust != null) {
                appendLine("\n[Trust Metrics]")
                appendLine("  Competence: ${"%.0f".format(trust.competence * 100)}%")
                appendLine("  Benevolence: ${"%.0f".format(trust.benevolence * 100)}%")
                appendLine("  Integrity: ${"%.0f".format(trust.integrity * 100)}%")
            }
            
            if (rel.notes.isNotEmpty()) {
                appendLine("\nNotes: ${rel.notes}")
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
        val prediction = predictRelationship(relationshipId)
        
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
            appendLine("I trust them ${if (rel.trustScore > 0.6) "strongly" else if (rel.trustScore > 0.3) "somewhat" else "cautiously"}.")
            appendLine("My understanding: ${(rel.mutualUnderstanding * 100).toInt()}%")
            
            if (prediction != null) {
                appendLine("\n[Relationship Forecast]")
                appendLine("  Growth potential: ${"%.0f".format(prediction.predictedGrowth * 100)}%")
                appendLine("  Decline risk: ${"%.0f".format(prediction.predictedDecline * 100)}%")
                if (prediction.recommendedActions.isNotEmpty()) {
                    appendLine("  Recommended: ${prediction.recommendedActions.first()}")
                }
            }
            
            val insights = socialInsights[rel.name]
            if (!insights.isNullOrEmpty()) {
                appendLine("\nWhat I've learned about them:")
                insights.take(3).forEach {
                    appendLine("  ${it.insight}")
                }
            }
        }
    }
    
    fun getTotalInteractions(): Int = totalInteractions
    
    fun getSocialConfidence(): Double = socialConfidence
}

class NetworkAnalyzer {
    fun analyze(relationships: List<Relationship>): SocialNetwork {
        val nodes = relationships.map { rel ->
            NetworkNode(
                id = rel.id,
                name = rel.name,
                type = rel.type,
                centrality = calculateCentrality(rel, relationships),
                degree = relationships.count { it.id != rel.id }
            )
        }
        
        val edges = mutableListOf<NetworkEdge>()
        
        val clusters = detectClusters(relationships)
        
        val centrality = nodes.associate { it.id to it.centrality }
        
        return SocialNetwork(
            nodes = nodes,
            edges = edges,
            centrality = centrality,
            clusters = clusters
        )
    }
    
    private fun calculateCentrality(rel: Relationship, allRels: List<Relationship>): Double {
        val depthWeight = rel.depth / 10.0
        val bondWeight = rel.emotionalBond
        val trustWeight = rel.trustScore
        val interactionWeight = minOf(1.0, rel.interactions / 20.0)
        
        return (depthWeight * 0.3 + bondWeight * 0.3 + trustWeight * 0.2 + interactionWeight * 0.2)
    }
    
    private fun detectClusters(relationships: List<Relationship>): List<List<String>> {
        val byType = relationships.groupBy { it.type }
        return byType.values.map { it.map { r -> r.id } }
    }
}

class TrustCalculator {
    fun calculate(competence: Double, benevolence: Double, integrity: Double): TrustMetric {
        val overall = (competence * 0.3 + benevolence * 0.4 + integrity * 0.3)
        
        return TrustMetric(
            competence = competence,
            benevolence = benevolence,
            integrity = integrity,
            overall = overall
        )
    }
    
    fun getMetrics(rel: Relationship): TrustMetric {
        return TrustMetric(
            competence = rel.mutualUnderstanding,
            benevolence = rel.emotionalBond,
            integrity = rel.trustScore * 0.8 + rel.reciprocity * 0.2,
            overall = rel.trustScore
        )
    }
}
