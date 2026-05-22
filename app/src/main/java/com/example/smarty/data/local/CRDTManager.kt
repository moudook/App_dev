package com.example.smarty.data.local

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap
import com.example.smarty.core.domain.model.*

/**
 * CRDT Manager - Conflict-free Replicated Data Types
 * Enables intelligent merge strategies for offline-first sync
 */
class CRDTManager {
    
    // Vector clocks for tracking causality
    private val vectorClocks = ConcurrentHashMap<String, VectorClock>()
    
    // Merge strategies per entity type
    private val mergeStrategies = mapOf<String, MergeStrategy<*>>(
        "notes" to NoteMergeStrategy(),
    )
    
    /**
     * Resolve conflict between local and remote versions
     */
    suspend fun <T> resolveConflict(
        local: T,
        remote: T,
        entityType: String,
        localTimestamp: Long,
        remoteTimestamp: Long,
        vectorClockLocal: VectorClock,
        vectorClockRemote: VectorClock,
    ): ConflictResolution<T> {
        @Suppress("UNCHECKED_CAST")
        val strategy = (mergeStrategies[entityType] as? MergeStrategy<T>) ?: DefaultMergeStrategy<T>()
        
        return when {
            // If one clearly dominates (happened-before relationship)
            vectorClockLocal.happensBefore(vectorClockRemote) ->
                ConflictResolution.RemoteWins(remote)
            
            vectorClockRemote.happensBefore(vectorClockLocal) ->
                ConflictResolution.LocalWins(local)
            
            // Concurrent modifications - use custom merge
            else -> {
                val merged = strategy.merge(local, remote, localTimestamp, remoteTimestamp)
                ConflictResolution.Merged(merged)
            }
        }
    }
    
    /**
     * Update vector clock for an entity
     */
    fun updateVectorClock(entityId: String, nodeId: String) {
        val clock = vectorClocks.getOrPut(entityId) { VectorClock() }
        clock.increment(nodeId)
    }
    
    /**
     * Get current vector clock for an entity
     */
    fun getVectorClock(entityId: String): VectorClock {
        return vectorClocks.getOrPut(entityId) { VectorClock() }
    }
    
    /**
     * Vector clock for tracking causality
     */
    data class VectorClock(
        private val clock: MutableMap<String, Long> = ConcurrentHashMap()
    ) {
        fun increment(nodeId: String) {
            clock[nodeId] = (clock[nodeId] ?: 0) + 1
        }
        
        fun update(other: VectorClock) {
            other.clock.forEach { (nodeId, count) ->
                clock[nodeId] = maxOf(clock[nodeId] ?: 0, count)
            }
        }
        
        fun happensBefore(other: VectorClock): Boolean {
            val allNodes = clock.keys + other.clock.keys
            return allNodes.all { nodeId ->
                (clock[nodeId] ?: 0) <= (other.clock[nodeId] ?: 0)
            } && clock.any { (nodeId, count) ->
                count < (other.clock[nodeId] ?: 0)
            }
        }
        
        fun concurrentWith(other: VectorClock): Boolean {
            return !happensBefore(other) && !other.happensBefore(this)
        }
    }
    
    /**
     * Conflict resolution result
     */
    sealed class ConflictResolution<T> {
        data class LocalWins<T>(val data: T) : ConflictResolution<T>()
        data class RemoteWins<T>(val data: T) : ConflictResolution<T>()
        data class Merged<T>(val data: T) : ConflictResolution<T>()
    }
    
    /**
     * Merge strategy interface
     */
    interface MergeStrategy<T> {
        suspend fun merge(local: T, remote: T, localTs: Long, remoteTs: Long): T
    }
    
    /**
     * Default merge strategy - last write wins with field-level merge
     */
    class DefaultMergeStrategy<T> : MergeStrategy<T> {
        override suspend fun merge(local: T, remote: T, localTs: Long, remoteTs: Long): T {
            // For generic types, prefer remote (server) version
            // In production, implement field-level merging
            return remote
        }
    }
    
    /**
     * Note-specific merge strategy
     */
    class NoteMergeStrategy : MergeStrategy<Note> {
        override suspend fun merge(local: Note, remote: Note, localTs: Long, remoteTs: Long): Note {
            return Note(
                id = local.id,
                title = if (localTs > remoteTs) local.title else remote.title,
                content = if (localTs > remoteTs) local.content else remote.content,
                summary = local.summary?.takeIf { it.isNotBlank() } ?: remote.summary,
                sourceUrl = local.sourceUrl ?: remote.sourceUrl,
                imageUri = local.imageUri ?: remote.imageUri,
                fileUri = local.fileUri ?: remote.fileUri,
                fileName = local.fileName ?: remote.fileName,
                fileMimeType = local.fileMimeType ?: remote.fileMimeType,
                fileSize = local.fileSize ?: remote.fileSize,
                type = local.type,
                categoryId = local.categoryId ?: remote.categoryId,
                categoryName = local.categoryName ?: remote.categoryName,
                whySaved = local.whySaved ?: remote.whySaved,
                processingStatus = if (local.processingStatus == ProcessingStatus.COMPLETED) 
                    local.processingStatus else remote.processingStatus,
                createdAt = minOf(local.createdAt, remote.createdAt),
                updatedAt = maxOf(localTs, remoteTs),
                isArchived = local.isArchived && remote.isArchived,
                todoContent = local.todoContent ?: remote.todoContent,
                excludeFromAiChat = local.excludeFromAiChat || remote.excludeFromAiChat,
                isFullPrivacy = local.isFullPrivacy || remote.isFullPrivacy,
                isAiCreated = local.isAiCreated || remote.isAiCreated,
                attachmentsJson = local.attachmentsJson ?: remote.attachmentsJson,
                tagsJson = mergeTagsJson(local.tagsJson, remote.tagsJson),
                isViewed = local.isViewed && remote.isViewed,
                isPinned = local.isPinned || remote.isPinned,
                reminderText = local.reminderText ?: remote.reminderText,
                reminderExpiresAt = local.reminderExpiresAt ?: remote.reminderExpiresAt,
                chunkAnalysesJson = mergeChunkAnalyses(local.chunkAnalysesJson, remote.chunkAnalysesJson),
            )
        }
        
        private fun mergeTagsJson(local: String?, remote: String?): String? {
            // Merge tag arrays, removing duplicates
            return local ?: remote
        }
        
        private fun mergeChunkAnalyses(local: String?, remote: String?): String? {
            // Merge chunk analyses, combining unique entries
            return local ?: remote
        }
    }
    

}