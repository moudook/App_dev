package com.example.smarty.server.services

import com.example.smarty.protocol.NoteInfo
import com.example.smarty.server.data.NoteRepository
import com.example.smarty.server.data.PostgresVectorStore
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory

/**
 * Advanced Note Service that orchestrates the note life cycle.
 * Handles persistence, AI enrichment (summarization, tagging), and semantic indexing.
 */
class NoteService(
    private val noteRepository: NoteRepository,
    private val contentAnalysisService: ContentAnalysisService,
    private val vectorStore: PostgresVectorStore,
    private val adaptiveSearchService: AdaptiveSearchService,
) {
    private val logger = LoggerFactory.getLogger(NoteService::class.java)
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Create a note and trigger background enrichment.
     */
    suspend fun createNote(userId: String, title: String, content: String, categoryId: String? = null): String {
        val initialNote = NoteInfo(
            id = "", // Will be generated
            title = title,
            content = content,
            categoryId = categoryId,
            processingStatus = "PROCESSING",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        
        val noteId = noteRepository.create(userId, initialNote)
        
        // Trigger background enrichment
        serviceScope.launch {
            enrichNote(userId, noteId, title, content)
        }
        
        return noteId
    }

    /**
     * Update a note and re-trigger enrichment if content changed.
     */
    suspend fun updateNote(userId: String, noteId: String, title: String?, content: String?, categoryId: String? = null): Boolean {
        val existing = noteRepository.getById(userId, noteId) ?: return false
        
        val updatedNote = existing.copy(
            title = title ?: existing.title,
            content = content ?: existing.content,
            categoryId = categoryId ?: existing.categoryId,
            updatedAt = System.currentTimeMillis()
        )
        
        val success = noteRepository.update(userId, updatedNote)
        
        if (success && (content != null || title != null)) {
            serviceScope.launch {
                enrichNote(userId, noteId, updatedNote.title, updatedNote.content)
            }
        }
        
        return success
    }

    /**
     * Background job to enrich note metadata using AI.
     */
    private suspend fun enrichNote(userId: String, noteId: String, title: String, content: String) {
        try {
            logger.info("Enriching note $noteId for user $userId")
            
            val analysis = contentAnalysisService.analyzeContent(content)
            
            if (analysis.success) {
                val existing = noteRepository.getById(userId, noteId) ?: return
                val enrichedNote = existing.copy(
                    summary = analysis.summary,
                    categoryName = analysis.category,
                    whySaved = analysis.whySaved,
                    todoContent = analysis.todos.joinToString("\n"),
                    processingStatus = "COMPLETED",
                    updatedAt = System.currentTimeMillis()
                )
                
                noteRepository.update(userId, enrichedNote)
                
                // Also update the vector store for semantic search
                vectorStore.store(userId, content, mapOf(
                    "id" to noteId,
                    "title" to title,
                    "summary" to (analysis.summary ?: ""),
                    "category" to (analysis.category ?: "note")
                ))
                
                logger.info("Successfully enriched note $noteId")
            }
        } catch (e: Exception) {
            logger.error("Failed to enrich note $noteId", e)
        }
    }

    /**
     * Advanced hybrid search (FTS + Semantic).
     */
    suspend fun searchNotes(userId: String, query: String, limit: Int = 20): List<NoteInfo> {
        // 1. Get all candidates from DB using FTS
        val dbResults = noteRepository.listByUser(userId, limit = 100)
        
        // 2. Apply adaptive semantic search on candidates
        return adaptiveSearchService.search(query, dbResults, limit)
    }

    suspend fun getNotes(userId: String): List<NoteInfo> = noteRepository.listByUser(userId)
    
    suspend fun getNote(userId: String, noteId: String): NoteInfo? = noteRepository.getById(userId, noteId)
    
    suspend fun deleteNote(userId: String, noteId: String): Boolean = noteRepository.delete(userId, noteId)
}
