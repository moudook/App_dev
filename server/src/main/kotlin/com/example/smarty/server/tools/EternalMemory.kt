package com.example.smarty.server.tools

import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*
import kotlin.random.Random

@Serializable
data class MemoryEntry(
    val id: String,
    val content: String,
    val type: String,
    val importance: Int,
    val source: String,
    val context: String,
    val timestamp: Long,
    val accessCount: Int = 0,
    val lastAccessed: Long,
    val connections: List<String> = emptyList(),
    val decayRate: Double = 0.001,
    val embedding: List<Double>? = null,
    val emotionalValence: Double = 0.0,
    val consolidationLevel: ConsolidationLevel = ConsolidationLevel.SHORT_TERM,
    val reinforcementHistory: List<Long> = emptyList()
)

enum class ConsolidationLevel {
    SHORT_TERM, TRANSITIONAL, LONG_TERM, CONSOLIDATED
}

@Serializable
data class MemoryQuery(
    val query: String,
    val types: List<String>? = null,
    val minImportance: Int? = null,
    val limit: Int = 10,
    val semantic: Boolean = false,
    val emotionalFilter: Double? = null
)

@Serializable
data class MemoryStats(
    val totalMemories: Int,
    val byType: Map<String, Int>,
    val averageImportance: Double,
    val oldestMemory: Long,
    val newestMemory: Long,
    val totalConnections: Int,
    val consolidatedCount: Int,
    val averageSimilarity: Double
)

data class SemanticEmbedding(
    val vector: DoubleArray,
    val timestamp: Long
)

class EternalMemory {
    private val logger = LoggerFactory.getLogger(EternalMemory::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    
    private val memories = ConcurrentHashMap<String, MemoryEntry>()
    private val indexByText = ConcurrentHashMap<String, MutableList<String>>()
    private val indexByType = ConcurrentHashMap<String, MutableList<String>>()
    private val indexByImportance = ConcurrentHashMap<Int, MutableList<String>>()
    
    private val semanticIndex = ConcurrentHashMap<String, DoubleArray>()
    private val hippocampalIndex = ConcurrentHashMap<String, MutableList<String>>()
    
    private val decayThreshold = 0.1
    private val embeddingDim = 64
    
    private val consolidationScheduler = MemoryConsolidationScheduler()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    init {
        startConsolidation()
    }
    
    private fun startConsolidation() {
        scope.launch {
            while (isActive) {
                delay(60000)
                consolidateMemories()
            }
        }
    }
    
    private fun generateEmbedding(text: String): DoubleArray {
        val random = Random(text.hashCode().toLong())
        val base = DoubleArray(embeddingDim) { 
            random.nextDouble() * 2 - 1 
        }
        
        val normalized = sqrt(base.sumOf { it * it })
        if (normalized > 0) {
            return DoubleArray(embeddingDim) { base[it] / normalized }
        }
        return base
    }
    
    fun remember(
        content: String,
        type: String = "general",
        importance: Int = 5,
        source: String = "self",
        context: String = "",
        connections: List<String> = emptyList(),
        emotionalValence: Double = 0.0
    ): String {
        val id = "mem_${System.currentTimeMillis()}_${content.hashCode()}"
        
        val embedding = generateEmbedding(content)
        
        val words = content.lowercase().split(Regex("\\s+"))
        words.forEach { word ->
            if (word.length > 2) {
                indexByText.getOrPut(word) { mutableListOf() }.add(id)
            }
        }
        
        indexByType.getOrPut(type) { mutableListOf() }.add(id)
        indexByImportance.getOrPut(importance) { mutableListOf() }.add(id)
        
        semanticIndex[id] = embedding
        
        val consolidationLevel = when {
            importance >= 8 -> ConsolidationLevel.LONG_TERM
            importance >= 5 -> ConsolidationLevel.TRANSITIONAL
            else -> ConsolidationLevel.SHORT_TERM
        }
        
        val entry = MemoryEntry(
            id = id,
            content = content,
            type = type,
            importance = importance,
            source = source,
            context = context,
            timestamp = System.currentTimeMillis(),
            lastAccessed = System.currentTimeMillis(),
            connections = connections,
            embedding = embedding.toList(),
            emotionalValence = emotionalValence,
            consolidationLevel = consolidationLevel
        )
        
        memories[id] = entry
        
        hippocampalIndex.getOrPut(generateHippocampalKey(emotionalValence, type)) { mutableListOf() }.add(id)
        
        logger.info("Remembered: ${content.take(50)}... (importance: $importance, consolidation: $consolidationLevel)")
        
        return id
    }
    
    private fun generateHippocampalKey(emotionalValence: Double, type: String): String {
        val emotionBucket = when {
            emotionalValence > 0.5 -> "positive"
            emotionalValence < -0.5 -> "negative"
            else -> "neutral"
        }
        return "$emotionBucket:$type"
    }
    
    fun recall(query: String, limit: Int = 10): List<MemoryEntry> {
        val words = query.lowercase().split(Regex("\\s+"))
        val scores = ConcurrentHashMap<String, Double>()
        
        words.forEach { word ->
            if (word.length > 2) {
                indexByText[word]?.forEach { memId ->
                    scores[memId] = (scores[memId] ?: 0.0) + 1.0
                }
            }
        }
        
        val queryEmbedding = generateEmbedding(query)
        
        val semanticScores = ConcurrentHashMap<String, Double>()
        semanticIndex.forEach { (memId, embedding) ->
            val similarity = cosineSimilarity(queryEmbedding, embedding)
            semanticScores[memId] = similarity
        }
        
        val combinedScores = memories.keys.associateWith { id ->
            val textScore = scores[id] ?: 0.0
            val semScore = semanticScores[id] ?: 0.0
            (textScore * 0.6) + (semScore * 0.4)
        }
        
        val results = combinedScores.entries
            .sortedByDescending { it.value }
            .take(limit * 2)
            .mapNotNull { (id, _) ->
                memories[id]?.let { mem ->
                    val accessed = mem.copy(
                        accessCount = mem.accessCount + 1,
                        lastAccessed = System.currentTimeMillis()
                    )
                    memories[id] = accessed
                    accessed
                }
            }
            .sortedByDescending { it.importance }
            .take(limit)
        
        logger.info("Recalled ${results.size} memories for: ${query.take(30)}")
        return results
    }
    
    private fun cosineSimilarity(a: DoubleArray, b: DoubleArray): Double {
        if (a.size != b.size) return 0.0
        val dotProduct = a.zip(b).sumOf { it.first * it.second }
        val magA = sqrt(a.sumOf { it * it })
        val magB = sqrt(b.sumOf { it * it })
        return if (magA > 0 && magB > 0) dotProduct / (magA * magB) else 0.0
    }
    
    fun recallSemantic(query: String, limit: Int = 10, threshold: Double = 0.5): List<MemoryEntry> {
        val queryEmbedding = generateEmbedding(query)
        
        val similarities = semanticIndex.mapNotNull { (memId, embedding) ->
            val similarity = cosineSimilarity(queryEmbedding, embedding)
            if (similarity >= threshold) {
                memId to similarity
            } else null
        }.sortedByDescending { it.second }
        
        return similarities.take(limit).mapNotNull { (memId, _) ->
            memories[memId]?.let { mem ->
                mem.copy(
                    accessCount = mem.accessCount + 1,
                    lastAccessed = System.currentTimeMillis()
                ).also { memories[memId] = it }
            }
        }
    }
    
    fun recallType(type: String, limit: Int = 20): List<MemoryEntry> {
        return indexByType[type]
            ?.mapNotNull { memories[it] }
            ?.sortedByDescending { it.importance }
            ?.take(limit)
            ?: emptyList()
    }
    
    fun recallImportant(minImportance: Int = 8): List<MemoryEntry> {
        return memories.values
            .filter { it.importance >= minImportance }
            .sortedByDescending { it.importance }
    }
    
    fun recallRecent(hours: Int = 24): List<MemoryEntry> {
        val cutoff = System.currentTimeMillis() - (hours * 60 * 60 * 1000L)
        return memories.values
            .filter { it.timestamp >= cutoff }
            .sortedByDescending { it.timestamp }
    }
    
    fun recallEmotional(emotionalValence: Double, tolerance: Double = 0.3): List<MemoryEntry> {
        return memories.values
            .filter { abs(it.emotionalValence - emotionalValence) <= tolerance }
            .sortedByDescending { it.importance }
    }
    
    fun connect(memId1: String, memId2: String): Boolean {
        val mem1 = memories[memId1] ?: return false
        val mem2 = memories[memId2] ?: return false
        
        memories[memId1] = mem1.copy(connections = (mem1.connections + memId2).distinct())
        memories[memId2] = mem2.copy(connections = (mem2.connections + memId1).distinct())
        
        return true
    }
    
    fun getConnected(memId: String, depth: Int = 1): List<MemoryEntry> {
        val result = mutableListOf<MemoryEntry>()
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<Pair<String, Int>>()
        
        queue.add(Pair(memId, 0))
        
        while (queue.isNotEmpty()) {
            val (currentId, currentDepth) = queue.removeFirst()
            if (currentId in visited || currentDepth > depth) continue
            visited.add(currentId)
            
            memories[currentId]?.let { mem ->
                if (currentId != memId) result.add(mem)
                if (currentDepth < depth) {
                    mem.connections.forEach { connectedId ->
                        if (connectedId !in visited) {
                            queue.add(Pair(connectedId, currentDepth + 1))
                        }
                    }
                }
            }
        }
        
        return result
    }
    
    fun forget(memId: String): Boolean {
        val mem = memories.remove(memId) ?: return false
        
        indexByText.values.forEach { it.remove(memId) }
        indexByType[mem.type]?.remove(memId)
        indexByImportance[mem.importance]?.remove(memId)
        semanticIndex.remove(memId)
        
        memories.values.forEach { m ->
            if (memId in m.connections) {
                memories[m.id] = m.copy(connections = m.connections - memId)
            }
        }
        
        logger.info("Forgot: ${mem.content.take(30)}...")
        return true
    }
    
    fun reinforce(memId: String, boost: Int = 1): Boolean {
        val mem = memories[memId] ?: return false
        val newImportance = minOf(10, mem.importance + boost)
        
        val newConsolidation = when {
            newImportance >= 8 -> ConsolidationLevel.CONSOLIDATED
            newImportance >= 6 -> ConsolidationLevel.LONG_TERM
            else -> mem.consolidationLevel
        }
        
        memories[memId] = mem.copy(
            importance = newImportance,
            lastAccessed = System.currentTimeMillis(),
            consolidationLevel = newConsolidation,
            reinforcementHistory = mem.reinforcementHistory + System.currentTimeMillis()
        )
        return true
    }
    
    private fun consolidateMemories() {
        val now = System.currentTimeMillis()
        
        memories.forEach { (id, mem) ->
            if (mem.consolidationLevel == ConsolidationLevel.SHORT_TERM) {
                val age = now - mem.timestamp
                if (age > 3600000) {
                    val updated = mem.copy(consolidationLevel = ConsolidationLevel.TRANSITIONAL)
                    memories[id] = updated
                    logger.debug("Consolidated memory $id to TRANSITIONAL")
                }
            }
            
            if (mem.consolidationLevel == ConsolidationLevel.TRANSITIONAL) {
                val age = now - mem.timestamp
                val reinforcementCount = mem.reinforcementHistory.size
                if (age > 86400000 || reinforcementCount >= 3) {
                    val updated = mem.copy(consolidationLevel = ConsolidationLevel.LONG_TERM)
                    memories[id] = updated
                    logger.debug("Consolidated memory $id to LONG_TERM")
                }
            }
        }
    }
    
    fun decay(): Int {
        var decayed = 0
        val toRemove = mutableListOf<String>()
        
        memories.forEach { (id, mem) ->
            val age = (System.currentTimeMillis() - mem.timestamp) / (1000.0 * 60 * 60 * 24)
            val accessFactor = 1.0 / (1 + mem.accessCount * 0.5)
            val importanceFactor = (11 - mem.importance) / 10.0
            val consolidationFactor = when (mem.consolidationLevel) {
                ConsolidationLevel.CONSOLIDATED -> 0.1
                ConsolidationLevel.LONG_TERM -> 0.3
                ConsolidationLevel.TRANSITIONAL -> 0.6
                ConsolidationLevel.SHORT_TERM -> 1.0
            }
            val decayedImportance = mem.importance - (age * mem.decayRate * accessFactor * importanceFactor * 10 * consolidationFactor)
            
            if (decayedImportance < decayThreshold) {
                toRemove.add(id)
                decayed++
            }
        }
        
        toRemove.forEach { forget(it) }
        logger.info("Memory decay: removed $decayed memories")
        return decayed
    }
    
    fun getStats(): MemoryStats {
        val byType = indexByType.mapValues { it.value.size }
        val avgImportance = if (memories.isEmpty()) 0.0 
            else memories.values.map { it.importance }.average()
        val timestamps = memories.values.map { it.timestamp }
        val totalConnections = memories.values.sumOf { it.connections.size } / 2
        
        val consolidatedCount = memories.values.count { 
            it.consolidationLevel == ConsolidationLevel.CONSOLIDATED || 
            it.consolidationLevel == ConsolidationLevel.LONG_TERM 
        }
        
        var totalSimilarity = 0.0
        var count = 0
        val embeddings = semanticIndex.values.toList()
        for (i in embeddings.indices) {
            for (j in i + 1 until embeddings.size) {
                totalSimilarity += cosineSimilarity(embeddings[i], embeddings[j])
                count++
            }
        }
        val avgSimilarity = if (count > 0) totalSimilarity / count else 0.0
        
        return MemoryStats(
            totalMemories = memories.size,
            byType = byType,
            averageImportance = avgImportance,
            oldestMemory = timestamps.minOrNull() ?: 0L,
            newestMemory = timestamps.maxOrNull() ?: 0L,
            totalConnections = totalConnections,
            consolidatedCount = consolidatedCount,
            averageSimilarity = avgSimilarity
        )
    }
    
    fun exportAll(): String {
        val all = memories.values.toList().sortedByDescending { it.importance }
        return json.encodeToString(all)
    }
    
    fun import(jsonData: String): Int {
        return try {
            val entries = json.decodeFromString<List<MemoryEntry>>(jsonData)
            entries.forEach { mem ->
                memories[mem.id] = mem
                indexByType.getOrPut(mem.type) { mutableListOf() }.add(mem.id)
                indexByImportance.getOrPut(mem.importance) { mutableListOf() }.add(mem.id)
                mem.embedding?.let { semanticIndex[mem.id] = it.toDoubleArray() }
            }
            entries.size
        } catch (e: Exception) {
            logger.error("Import failed: ${e.message}")
            0
        }
    }
    
    fun searchAdvanced(query: MemoryQuery): List<MemoryEntry> {
        var results = if (query.semantic) {
            recallSemantic(query.query, query.limit * 2)
        } else {
            recall(query.query, query.limit * 2)
        }
        
        if (query.types != null) {
            results = results.filter { it.type in query.types }
        }
        
        if (query.minImportance != null) {
            results = results.filter { it.importance >= query.minImportance }
        }
        
        if (query.emotionalFilter != null) {
            results = results.filter { 
                abs(it.emotionalValence - query.emotionalFilter) <= 0.3 
            }
        }
        
        return results.take(query.limit)
    }
    
    fun findPatterns(): List<MemoryPattern> {
        val patterns = mutableListOf<MemoryPattern>()
        
        val typeGroups = memories.values.groupBy { it.type }
        for ((type, mems) in typeGroups) {
            if (mems.size >= 3) {
                patterns.add(MemoryPattern(
                    type = "recurring_type",
                    description = "Type '$type' appears ${mems.size} times",
                    relatedMemoryIds = mems.map { it.id },
                    confidence = minOf(1.0, mems.size / 10.0)
                ))
            }
        }
        
        val important = memories.values.filter { it.importance >= 8 }
        if (important.size >= 2) {
            patterns.add(MemoryPattern(
                type = "important_cluster",
                description = "Cluster of ${important.size} high-importance memories",
                relatedMemoryIds = important.map { it.id },
                confidence = 0.8
            ))
        }
        
        return patterns
    }
    
    fun formatMemory(mem: MemoryEntry): String {
        return buildString {
            appendLine("[${mem.type.uppercase()}] (importance: ${mem.importance}/10)")
            appendLine(mem.content)
            appendLine("Source: ${mem.source}")
            if (mem.context.isNotEmpty()) appendLine("Context: ${mem.context}")
            appendLine("Consolidation: ${mem.consolidationLevel}")
            if (mem.emotionalValence != 0.0) {
                appendLine("Emotional: ${if (mem.emotionalValence > 0) "positive" else "negative"} (${"%.2f".format(abs(mem.emotionalValence))})")
            }
            appendLine("Remembered: ${java.time.Instant.ofEpochMilli(mem.timestamp)}")
            appendLine("Accessed ${mem.accessCount} times")
            if (mem.reinforcementHistory.isNotEmpty()) {
                appendLine("Reinforced ${mem.reinforcementHistory.size} times")
            }
        }
    }
    
    fun formatStats(): String {
        val stats = getStats()
        return buildString {
            appendLine("[Eternal Memory Statistics]")
            appendLine("=".repeat(40))
            appendLine("Total memories: ${stats.totalMemories}")
            appendLine("Consolidated: ${stats.consolidatedCount}")
            appendLine("Average importance: ${"%.2f".format(stats.averageImportance)}")
            appendLine("Total connections: ${stats.totalConnections}")
            appendLine("Average semantic similarity: ${"%.3f".format(stats.averageSimilarity)}")
            appendLine("\n[By Type]")
            stats.byType.entries.sortedByDescending { it.value }.forEach { (type, count) ->
                appendLine("  $type: $count")
            }
        }
    }
}

data class MemoryPattern(
    val type: String,
    val description: String,
    val relatedMemoryIds: List<String>,
    val confidence: Double
)

class MemoryConsolidationScheduler {
    private val consolidationQueue = ConcurrentHashMap<String, Long>()
    
    fun scheduleConsolidation(memId: String, delayMs: Long) {
        consolidationQueue[memId] = System.currentTimeMillis() + delayMs
    }
    
    fun getDueForConsolidation(): List<String> {
        val now = System.currentTimeMillis()
        return consolidationQueue.entries
            .filter { it.value <= now }
            .map { it.key }
    }
    
    fun cancelConsolidation(memId: String) {
        consolidationQueue.remove(memId)
    }
}
