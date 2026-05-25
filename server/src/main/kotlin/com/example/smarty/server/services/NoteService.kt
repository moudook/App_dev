package com.example.smarty.server.services

import com.example.smarty.protocol.NoteInfo
import com.example.smarty.server.agent.NoteProcessingAgent
import com.example.smarty.server.data.NoteRepository
import com.example.smarty.server.data.PostgresVectorStore
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.security.MessageDigest

/**
 * Advanced Note Service that orchestrates the note life cycle.
 * Handles persistence, AI enrichment (summarization, tagging), and semantic indexing.
 */
class NoteService(
    private val noteRepository: NoteRepository,
    private val noteProcessingAgent: NoteProcessingAgent,
    private val vectorStore: PostgresVectorStore,
    private val adaptiveSearchService: AdaptiveSearchService,
) {
    private val logger = LoggerFactory.getLogger(NoteService::class.java)
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Create a note and trigger background enrichment.
     */
    suspend fun createNote(
        userId: String,
        title: String,
        content: String,
        categoryId: String? = null,
        isAiCreated: Boolean = false,
    ): String {
        val initialNote =
            NoteInfo(
                id = "", // Will be generated
                title = title,
                content = content,
                categoryId = categoryId,
                processingStatus = "PROCESSING",
                isAiCreated = isAiCreated,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            )

        val (noteId, isDuplicate) = noteRepository.createWithDuplicateStatus(userId, initialNote)

        if (!isDuplicate) {
            // Trigger background enrichment
            serviceScope.launch {
                enrichNote(userId, noteId, title, content)
            }
        } else {
            logger.info("Skipping background enrichment for duplicate note {}", noteId)
        }

        return noteId
    }

    /**
     * Update a note and re-trigger enrichment if content changed.
     */
    suspend fun updateNote(
        userId: String,
        noteId: String,
        title: String?,
        content: String?,
        categoryId: String? = null,
    ): Boolean {
        val existing = noteRepository.getById(userId, noteId) ?: return false

        val updatedNote =
            existing.copy(
                title = title ?: existing.title,
                content = content ?: existing.content,
                categoryId = categoryId ?: existing.categoryId,
                updatedAt = System.currentTimeMillis(),
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
     * Trigger background enrichment asynchronously.
     */
    fun triggerEnrichmentAsync(
        userId: String,
        noteId: String,
        title: String,
        content: String,
    ) {
        serviceScope.launch {
            enrichNote(userId, noteId, title, content)
        }
    }

    /**
     * Background job to enrich note metadata using AI.
     */
    suspend fun enrichNote(
        userId: String,
        noteId: String,
        title: String,
        content: String,
    ) {
        try {
            logger.info("Enriching note $noteId for user $userId")

            val existing = noteRepository.getById(userId, noteId) ?: return

            val isArchivedTagPresent = existing.isArchived || 
                content.contains(Regex("_isArchived\\s*=\\s*true", RegexOption.IGNORE_CASE)) || 
                content.contains(Regex("#_isArchived\\b", RegexOption.IGNORE_CASE))
            val cleanedContent = content
                .replace(Regex("_isArchived\\s*=\\s*true", RegexOption.IGNORE_CASE), "")
                .replace(Regex("#_isArchived\\b", RegexOption.IGNORE_CASE), "")
                .trim()

            if (existing.isAiCreated) {
                logger.info("Skipping enrichment for AI-created note $noteId to prevent infinite processing loops")
                val markedNote =
                    existing.copy(
                        processingStatus = "COMPLETED",
                        isArchived = isArchivedTagPresent,
                        updatedAt = System.currentTimeMillis(),
                    )
                noteRepository.update(userId, markedNote)

                vectorStore.store(
                    userId,
                    cleanedContent,
                    mapOf(
                        "id" to noteId,
                        "title" to title,
                        "summary" to (existing.summary ?: ""),
                        "category" to (existing.categoryName ?: "note"),
                    ),
                )
                return
            }

            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(cleanedContent.toByteArray(Charsets.UTF_8))
            val currentHash = digest.joinToString("") { "%02x".format(it) }

            if (existing.processedContentHash == currentHash) {
                logger.info("Skipping enrichment for note ${noteId} because content has not changed.")
                return
            }

            val analysis = noteProcessingAgent.processNote(cleanedContent)

            if (analysis.success) {
                val existing = noteRepository.getById(userId, noteId) ?: return
                val enrichedNote =
                    existing.copy(
                        summary = analysis.summary,
                        categoryName = analysis.category,
                        whySaved = analysis.whySaved,
                        todoContent = analysis.todos.joinToString("\n"),
                        tagsJson = Json.encodeToString(analysis.tags),
                        stackId = analysis.stackId,
                        processingStatus = "COMPLETED",
                        processedContentHash = currentHash,
                        isArchived = isArchivedTagPresent,
                        updatedAt = System.currentTimeMillis(),
                    )

                noteRepository.update(userId, enrichedNote)

                // Also update the vector store for semantic search
                vectorStore.store(
                    userId,
                    cleanedContent,
                    mapOf(
                        "id" to noteId,
                        "title" to title,
                        "summary" to (analysis.summary ?: ""),
                        "category" to (analysis.category ?: "note"),
                    ),
                )

                // Save extracted memories to vector store
                analysis.memories.forEach { memory ->
                    try {
                        vectorStore.store(
                            userId = userId,
                            content = memory,
                            metadata = mapOf("type" to "factual", "source_note_id" to noteId)
                        )
                    } catch (e: Exception) {
                        logger.warn("Failed to store memory: $memory", e)
                    }
                }

                logger.info("Successfully enriched note $noteId")
            }
        } catch (e: Exception) {
            logger.error("Failed to enrich note $noteId", e)
            try {
                val existingFailed = noteRepository.getById(userId, noteId)
                if (existingFailed != null) {
                    val failedNote =
                        existingFailed.copy(
                            processingStatus = "FAILED",
                            updatedAt = System.currentTimeMillis(),
                        )
                    noteRepository.update(userId, failedNote)
                }
            } catch (inner: Exception) {
                logger.error("Failed to update status to FAILED for note $noteId", inner)
            }
        }
    }

    /**
     * Advanced hybrid search (FTS + Semantic).
     */
    suspend fun searchNotes(
        userId: String,
        query: String,
        limit: Int = 20,
    ): List<NoteInfo> {
        // 1. Get all candidates from DB using FTS, filtering out archived/private notes
        val dbResults = noteRepository.listByUser(userId, limit = 100).filter {
            !it.isArchived && !it.isFullPrivacy && !it.excludeFromAiChat
        }

        // 2. Apply adaptive semantic search on candidates
        return adaptiveSearchService.search(query, dbResults, limit)
    }

    suspend fun getNotes(userId: String): List<NoteInfo> = noteRepository.listByUser(userId)

    suspend fun getNote(
        userId: String,
        noteId: String,
    ): NoteInfo? = noteRepository.getById(userId, noteId)

    suspend fun deleteNote(
        userId: String,
        noteId: String,
    ): Boolean = noteRepository.delete(userId, noteId)
}
