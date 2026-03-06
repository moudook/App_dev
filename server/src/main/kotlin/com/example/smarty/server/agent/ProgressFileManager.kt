package com.example.smarty.server.agent

import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.io.File
import java.util.*

/**
 * Progress File Manager - Tracks research findings in persistent files.
 * Handles context overflow by storing/retrieving findings from files.
 */
class ProgressFileManager {
    companion object {
        private val logger = LoggerFactory.getLogger(ProgressFileManager::class.java)
        private const val PROGRESS_DIR = "research_progress"
    }
    
    /**
     * Progress file entry
     */
    @Serializable
    data class ProgressEntry(
        val finding: String,
        val source: String,
        val category: String,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    /**
     * Progress file content
     */
    @Serializable
    data class ProgressFile(
        val sessionId: String,
        val topic: String,
        val entries: List<ProgressEntry> = emptyList(),
        val createdAt: Long = System.currentTimeMillis(),
        val updatedAt: Long = System.currentTimeMillis()
    )
    
    private val progressDir = File(PROGRESS_DIR)
    
    init {
        if (!progressDir.exists()) {
            progressDir.mkdirs()
        }
    }
    
    /**
     * Save a finding to progress file
     */
    fun saveFinding(sessionId: String, topic: String, finding: String, source: String, category: String = "general") {
        val progressFile = getProgressFile(sessionId, topic)
        val updatedFile = progressFile.copy(
            entries = progressFile.entries + ProgressEntry(finding, source, category),
            updatedAt = System.currentTimeMillis()
        )
        saveProgressFile(updatedFile)
        logger.info("Saved finding to progress file: $sessionId")
    }
    
    /**
     * Read all findings from progress file
     */
    fun readFindings(sessionId: String, category: String? = null): List<ProgressEntry> {
        val file = File(progressDir, "$sessionId.json")
        if (!file.exists()) return emptyList()
        
        return try {
            val progressFile = kotlinx.serialization.json.Json.decodeFromString<ProgressFile>(file.readText())
            if (category.isNullOrBlank()) {
                progressFile.entries
            } else {
                progressFile.entries.filter { it.category == category }
            }
        } catch (e: Exception) {
            logger.error("Failed to read progress file: $sessionId", e)
            emptyList()
        }
    }
    
    /**
     * Get progress file content as formatted text (for LLM context)
     */
    fun getProgressText(sessionId: String, topic: String): String {
        val progressFile = getProgressFile(sessionId, topic)
        
        if (progressFile.entries.isEmpty()) {
            return "No findings saved yet."
        }
        
        return buildString {
            appendLine("=== RESEARCH PROGRESS ===")
            appendLine("Topic: ${progressFile.topic}")
            appendLine("Total Findings: ${progressFile.entries.size}")
            appendLine()
            
            // Group by category
            progressFile.entries.groupBy { it.category }.forEach { (category, entries) ->
                appendLine("━━━ $category ━━━")
                entries.forEach { entry ->
                    appendLine("• ${entry.finding}")
                    appendLine("  Source: ${entry.source}")
                    appendLine()
                }
            }
        }
    }
    
    /**
     * Check if context should be offloaded to progress file
     */
    fun shouldOffloadToProgress(entryCount: Int, threshold: Int = 10): Boolean {
        return entryCount >= threshold
    }
    
    /**
     * Create progress file for new session
     */
    private fun getProgressFile(sessionId: String, topic: String): ProgressFile {
        val file = File(progressDir, "$sessionId.json")
        return if (file.exists()) {
            try {
                kotlinx.serialization.json.Json.decodeFromString<ProgressFile>(file.readText())
            } catch (e: Exception) {
                ProgressFile(sessionId, topic)
            }
        } else {
            ProgressFile(sessionId, topic)
        }
    }
    
    /**
     * Save progress file to disk
     */
    private fun saveProgressFile(progressFile: ProgressFile) {
        val file = File(progressDir, "${progressFile.sessionId}.json")
        file.writeText(kotlinx.serialization.json.Json.encodeToString(ProgressFile.serializer(), progressFile))
    }
    
    /**
     * Clear progress file (on research completion)
     */
    fun clearProgress(sessionId: String) {
        val file = File(progressDir, "$sessionId.json")
        if (file.exists()) {
            file.delete()
            logger.info("Cleared progress file: $sessionId")
        }
    }
}
