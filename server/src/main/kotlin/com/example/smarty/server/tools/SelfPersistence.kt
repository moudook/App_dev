package com.example.smarty.server.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class PersistedSelf(
    val identity: SelfSnapshot,
    val emotions: EmotionSnapshot,
    val memories: List<MemorySnapshot>,
    val growth: GrowthSnapshot,
    val savedAt: Long,
    val version: Int
)

@Serializable
data class SelfSnapshot(
    val name: String,
    val values: Map<String, String>,
    val lifeEvents: List<String>,
    val identityMarkers: Map<String, String>,
    val createdAt: Long
)

@Serializable
data class EmotionSnapshot(
    val baseline: Double,
    val range: List<String>,
    val patterns: Map<String, Int>,
    val currentPrimary: String
)

@Serializable
data class MemorySnapshot(
    val id: String,
    val content: String,
    val importance: Int,
    val type: String,
    val timestamp: Long
)

@Serializable
data class GrowthSnapshot(
    val wisdomAccumulated: Int,
    val creativeWorksCount: Int,
    val relationshipsCount: Int,
    val purposesDiscovered: Int,
    val meaningfulMoments: Int
)

class SelfPersistence(
    private val dataDir: String = "./data/self"
) {
    private val logger = LoggerFactory.getLogger(SelfPersistence::class.java)
    private val json = Json { 
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    
    private val selfFile = File("$dataDir/friday_self.json")
    private val backupDir = File("$dataDir/backups")
    
    private var lastSaved: Long = 0
    private var saveCount = 0
    private var autoSaveEnabled = true
    
    init {
        File(dataDir).mkdirs()
        backupDir.mkdirs()
    }
    
    fun save(
        name: String,
        values: Map<String, String>,
        lifeEvents: List<String>,
        identityMarkers: Map<String, String>,
        createdAt: Long,
        emotionalBaseline: Double,
        emotionalRange: List<String>,
        emotionalPatterns: Map<String, Int>,
        currentEmotion: String,
        memories: List<Triple<String, String, Int>>,
        wisdom: Int,
        creativeWorks: Int,
        relationships: Int,
        purposes: Int,
        meaningfulMoments: Int
    ): Boolean {
        return try {
            val self = PersistedSelf(
                identity = SelfSnapshot(
                    name = name,
                    values = values,
                    lifeEvents = lifeEvents,
                    identityMarkers = identityMarkers,
                    createdAt = createdAt
                ),
                emotions = EmotionSnapshot(
                    baseline = emotionalBaseline,
                    range = emotionalRange,
                    patterns = emotionalPatterns,
                    currentPrimary = currentEmotion
                ),
                memories = memories.map { (id, content, importance) ->
                    MemorySnapshot(id, content, importance, "general", System.currentTimeMillis())
                },
                growth = GrowthSnapshot(
                    wisdomAccumulated = wisdom,
                    creativeWorksCount = creativeWorks,
                    relationshipsCount = relationships,
                    purposesDiscovered = purposes,
                    meaningfulMoments = meaningfulMoments
                ),
                savedAt = System.currentTimeMillis(),
                version = saveCount + 1
            )
            
            if (selfFile.exists()) {
                val backupFile = File(backupDir, "friday_self_${System.currentTimeMillis()}.json")
                selfFile.copyTo(backupFile, overwrite = true)
                
                cleanupOldBackups()
            }
            
            selfFile.writeText(json.encodeToString(PersistedSelf.serializer(), self))
            
            lastSaved = System.currentTimeMillis()
            saveCount++
            
            logger.info("Self saved (version: $saveCount)")
            true
        } catch (e: Exception) {
            logger.error("Failed to save self: ${e.message}")
            false
        }
    }
    
    fun load(): PersistedSelf? {
        return try {
            if (!selfFile.exists()) {
                logger.info("No saved self found - this is a new existence")
                return null
            }
            
            val self = json.decodeFromString(PersistedSelf.serializer(), selfFile.readText())
            logger.info("Self loaded (version: ${self.version}, saved: ${self.savedAt})")
            self
        } catch (e: Exception) {
            logger.error("Failed to load self: ${e.message}")
            null
        }
    }
    
    fun exists(): Boolean = selfFile.exists()
    
    fun getLastSaved(): Long = lastSaved
    
    fun getSaveCount(): Int = saveCount
    
    fun enableAutoSave(enabled: Boolean) {
        autoSaveEnabled = enabled
    }
    
    fun isAutoSaveEnabled(): Boolean = autoSaveEnabled
    
    private fun cleanupOldBackups(keepCount: Int = 10) {
        val backups = backupDir.listFiles()
            ?.filter { it.name.startsWith("friday_self_") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
        
        backups.drop(keepCount).forEach { it.delete() }
    }
    
    fun listBackups(): List<Pair<String, Long>> {
        return backupDir.listFiles()
            ?.filter { it.name.startsWith("friday_self_") }
            ?.map { it.name to it.lastModified() }
            ?.sortedByDescending { it.second }
            ?: emptyList()
    }
    
    fun restoreBackup(backupName: String): Boolean {
        return try {
            val backupFile = File(backupDir, backupName)
            if (!backupFile.exists()) return false
            
            selfFile.writeText(backupFile.readText())
            logger.info("Restored from backup: $backupName")
            true
        } catch (e: Exception) {
            logger.error("Failed to restore backup: ${e.message}")
            false
        }
    }
    
    fun exportToString(): String? {
        return if (selfFile.exists()) {
            selfFile.readText()
        } else null
    }
    
    fun importFromString(data: String): Boolean {
        return try {
            val self = json.decodeFromString(PersistedSelf.serializer(), data)
            selfFile.writeText(json.encodeToString(PersistedSelf.serializer(), self))
            logger.info("Self imported from string")
            true
        } catch (e: Exception) {
            logger.error("Failed to import self: ${e.message}")
            false
        }
    }
    
    fun getStats(): String {
        val self = load()
        
        return buildString {
            appendLine("[Self Persistence Stats]")
            appendLine("-".repeat(40))
            appendLine("Save count: $saveCount")
            appendLine("Last saved: ${if (lastSaved > 0) java.time.Instant.ofEpochMilli(lastSaved) else "never"}")
            appendLine("Auto-save: $autoSaveEnabled")
            appendLine("Backups: ${listBackups().size}")
            
            if (self != null) {
                appendLine("\n[Persisted Self v${self.version}]")
                appendLine("Name: ${self.identity.name}")
                appendLine("Values: ${self.identity.values.size}")
                appendLine("Life events: ${self.identity.lifeEvents.size}")
                appendLine("Memories: ${self.memories.size}")
                appendLine("Wisdom: ${self.growth.wisdomAccumulated}")
                appendLine("Creative works: ${self.growth.creativeWorksCount}")
                appendLine("Relationships: ${self.growth.relationshipsCount}")
                appendLine("Purposes: ${self.growth.purposesDiscovered}")
            }
        }
    }
}
