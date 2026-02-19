package com.example.smarty.server.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

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
    val decayRate: Double = 0.001
)

@Serializable
data class MemoryQuery(
    val query: String,
    val types: List<String>? = null,
    val minImportance: Int? = null,
    val limit: Int = 10
)

@Serializable
data class MemoryStats(
    val totalMemories: Int,
    val byType: Map<String, Int>,
    val averageImportance: Double,
    val oldestMemory: Long,
    val newestMemory: Long,
    val totalConnections: Int
)

class EternalMemory {
    private val logger = LoggerFactory.getLogger(EternalMemory::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    
    private val memories = ConcurrentHashMap<String, MemoryEntry>()
    private val indexByText = ConcurrentHashMap<String, MutableList<String>>()
    private val indexByType = ConcurrentHashMap<String, MutableList<String>>()
    private val indexByImportance = ConcurrentHashMap<Int, MutableList<String>>()
    
    private val decayThreshold = 0.1
    
    fun remember(
        content: String,
        type: String = "general",
        importance: Int = 5,
        source: String = "self",
        context: String = "",
        connections: List<String> = emptyList()
    ): String {
        val id = "mem_${System.currentTimeMillis()}_${content.hashCode()}"
        
        val words = content.lowercase().split(Regex("\\s+"))
        words.forEach { word ->
            if (word.length > 2) {
                indexByText.getOrPut(word) { mutableListOf() }.add(id)
            }
        }
        
        indexByType.getOrPut(type) { mutableListOf() }.add(id)
        indexByImportance.getOrPut(importance) { mutableListOf() }.add(id)
        
        val entry = MemoryEntry(
            id = id,
            content = content,
            type = type,
            importance = importance,
            source = source,
            context = context,
            timestamp = System.currentTimeMillis(),
            lastAccessed = System.currentTimeMillis(),
            connections = connections
        )
        
        memories[id] = entry
        logger.info("Remembered: ${content.take(50)}... (importance: $importance)")
        
        return id
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
        
        val results = scores.entries
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
        memories[memId] = mem.copy(
            importance = minOf(10, mem.importance + boost),
            lastAccessed = System.currentTimeMillis()
        )
        return true
    }
    
    fun decay(): Int {
        var decayed = 0
        val toRemove = mutableListOf<String>()
        
        memories.forEach { (id, mem) ->
            val age = (System.currentTimeMillis() - mem.timestamp) / (1000.0 * 60 * 60 * 24)
            val accessFactor = 1.0 / (1 + mem.accessCount * 0.5)
            val importanceFactor = (11 - mem.importance) / 10.0
            val decayedImportance = mem.importance - (age * mem.decayRate * accessFactor * importanceFactor * 10)
            
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
        
        return MemoryStats(
            totalMemories = memories.size,
            byType = byType,
            averageImportance = avgImportance,
            oldestMemory = timestamps.minOrNull() ?: 0L,
            newestMemory = timestamps.maxOrNull() ?: 0L,
            totalConnections = totalConnections
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
            }
            entries.size
        } catch (e: Exception) {
            logger.error("Import failed: ${e.message}")
            0
        }
    }
    
    fun searchAdvanced(query: MemoryQuery): List<MemoryEntry> {
        var results = recall(query.query, query.limit * 2)
        
        if (query.types != null) {
            results = results.filter { it.type in query.types }
        }
        
        if (query.minImportance != null) {
            results = results.filter { it.importance >= query.minImportance }
        }
        
        return results.take(query.limit)
    }
    
    fun formatMemory(mem: MemoryEntry): String {
        return buildString {
            appendLine("[${mem.type.uppercase()}] (importance: ${mem.importance}/10)")
            appendLine(mem.content)
            appendLine("Source: ${mem.source}")
            if (mem.context.isNotEmpty()) appendLine("Context: ${mem.context}")
            appendLine("Remembered: ${java.time.Instant.ofEpochMilli(mem.timestamp)}")
            appendLine("Accessed ${mem.accessCount} times")
        }
    }
    
    fun formatStats(): String {
        val stats = getStats()
        return buildString {
            appendLine("[Eternal Memory Statistics]")
            appendLine("=".repeat(40))
            appendLine("Total memories: ${stats.totalMemories}")
            appendLine("Average importance: ${"%.2f".format(stats.averageImportance)}")
            appendLine("Total connections: ${stats.totalConnections}")
            appendLine("\n[By Type]")
            stats.byType.entries.sortedByDescending { it.value }.forEach { (type, count) ->
                appendLine("  $type: $count")
            }
        }
    }
}
