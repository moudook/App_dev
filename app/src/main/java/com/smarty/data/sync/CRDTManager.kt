
package com.smarty.data.sync

import com.smarty.data.dao.SmartDatabaseDao
import com.smarty.data.entity.*
import com.smarty.data.model.CRDTOperation
import com.smarty.data.model.SyncState
import com.smarty.data.model.VectorClock
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant

/**
 * CRDT Manager for conflict-free replicated data types
 */
class CRDTManager(
    private val dao: SmartDatabaseDao
) {

    private val vectorClocks = mutableMapOf<String, VectorClock>()
    private val pendingOperations = MutableStateFlow<List<CRDTOperation>>(emptyList())

    /**
     * Merge two versions of an entity using CRDT principles
     */
    fun <T> mergeEntities(
        local: T,
        remote: T,
        entityType: String,
        mergeStrategy: MergeStrategy = MergeStrategy.LAST_WRITE_WINS
    ): T where T : EntityWithMetadata {
        return when (mergeStrategy) {
            MergeStrategy.LAST_WRITE_WINS -> {
                if (local.lastModified >= remote.lastModified) local else remote
            }
            MergeStrategy.MANUAL -> {
                // Flag for manual resolution
                throw MergeConflictException("Manual merge required for $entityType")
            }
            MergeStrategy.CUSTOM -> {
                customMerge(local, remote, entityType)
            }
        }
    }

    /**
     * Custom merge logic for specific entity types
     */
    private fun <T> customMerge(local: T, remote: T, entityType: String): T where T : EntityWithMetadata {
        return when (entityType) {
            "notes" -> mergeNotes(local as NoteEntity, remote as NoteEntity) as T
            "tags" -> mergeTags(local as TagEntity, remote as TagEntity) as T
            "chat_messages" -> mergeChatMessages(local as ChatMessageEntity, remote as ChatMessageEntity) as T
            else -> {
                if (local.lastModified >= remote.lastModified) local else remote
            }
        }
    }

    private fun mergeNotes(local: NoteEntity, remote: NoteEntity): NoteEntity {
        return NoteEntity(
            id = local.id,
            supabaseId = local.supabaseId,
            userId = local.userId,
            title = if (remote.lastModified > local.lastModified) remote.title else local.title,
            content = mergeContent(local.content, remote.content, local.lastModified, remote.lastModified),
            category = remote.category ?: local.category,
            priority = maxOf(local.priority, remote.priority),
            isPinned = local.isPinned || remote.isPinned,
            isArchived = local.isArchived && remote.isArchived,
            syncState = SyncState.SYNCED,
            lastModified = maxOf(local.lastModified, remote.lastModified),
            createdAt = minOf(local.createdAt, remote.createdAt),
            version = maxOf(local.version, remote.version) + 1,
            isDeleted = local.isDeleted || remote.isDeleted,
            metadata = mergeMetadata(local.metadata, remote.metadata)
        )
    }

    private fun mergeTags(local: TagEntity, remote: TagEntity): TagEntity {
        return TagEntity(
            id = local.id,
            supabaseId = local.supabaseId,
            userId = local.userId,
            name = remote.name, // Name should be consistent
            type = remote.type,
            color = remote.color ?: local.color,
            icon = remote.icon ?: local.icon,
            parentTagId = remote.parentTagId ?: local.parentTagId,
            isSystem = local.isSystem || remote.isSystem,
            syncState = SyncState.SYNCED,
            lastModified = maxOf(local.lastModified, remote.lastModified),
            version = maxOf(local.version, remote.version) + 1,
            isDeleted = local.isDeleted || remote.isDeleted
        )
    }

    private fun mergeChatMessages(local: ChatMessageEntity, remote: ChatMessageEntity): ChatMessageEntity {
        return ChatMessageEntity(
            id = local.id,
            supabaseId = local.supabaseId,
            chatId = local.chatId,
            userId = local.userId,
            content = if (remote.lastModified > local.lastModified) remote.content else local.content,
            messageType = remote.messageType,
            senderType = remote.senderType,
            contextSnapshot = mergeMetadata(local.contextSnapshot, remote.contextSnapshot),
            syncState = SyncState.SYNCED,
            lastModified = maxOf(local.lastModified, remote.lastModified),
            createdAt = minOf(local.createdAt, remote.createdAt),
            version = maxOf(local.version, remote.version) + 1,
            isDeleted = local.isDeleted || remote.isDeleted
        )
    }

    private fun mergeContent(local: String, remote: String, localTime: Instant, remoteTime: Instant): String {
        // Simple merge: prefer newer content
        return if (remoteTime > localTime) remote else local
    }

    private fun mergeMetadata(local: String?, remote: String?): String? {
        return remote ?: local
    }

    /**
     * Update vector clock for an operation
     */
    fun updateVectorClock(entityId: String, deviceId: String): VectorClock {
        val clock = vectorClocks.getOrPut(entityId) { VectorClock() }
        clock.increment(deviceId)
        vectorClocks[entityId] = clock
        return clock
    }

    /**
     * Check if two vector clocks are concurrent (conflict)
     */
    fun isConcurrent(clock1: VectorClock, clock2: VectorClock): Boolean {
        return clock1.isConcurrentWith(clock2)
    }

    /**
     * Add operation to pending queue
     */
    fun queueOperation(operation: CRDTOperation) {
        val current = pendingOperations.value.toMutableList()
        current.add(operation)
        pendingOperations.value = current
    }

    /**
     * Process pending operations
     */
    suspend fun processPendingOperations() {
        val operations = pendingOperations.value
        // Process operations in order
        operations.sortedBy { it.timestamp }.forEach { operation ->
            // Apply operation
            applyOperation(operation)
        }
        pendingOperations.value = emptyList()
    }

    private suspend fun applyOperation(operation: CRDTOperation) {
        // Implementation depends on operation type
        when (operation.type) {
            "INSERT" -> {
                when (operation.entityType) {
                    "notes" -> {
                        val note = NoteEntity(
                            id = operation.entityId,
                            supabaseId = "note_${operation.entityId}",
                            userId = (operation.data["userId"] as? Number)?.toLong() ?: 0,
                            title = operation.data["title"] as? String ?: "",
                            content = operation.data["content"] as? String ?: "",
                            category = operation.data["category"] as? String,
                            priority = (operation.data["priority"] as? Number)?.toInt() ?: 0,
                            isPinned = operation.data["isPinned"] as? Boolean ?: false,
                            isArchived = operation.data["isArchived"] as? Boolean ?: false,
                            isDeleted = operation.data["isDeleted"] as? Boolean ?: false,
                            syncState = SyncState.SYNCED,
                            lastModified = operation.timestamp,
                            createdAt = operation.timestamp,
                            version = 1,
                            metadata = "{}"
                        )
                        dao.insertNote(note)
                    }
                    "tags" -> {
                        val tag = TagEntity(
                            id = operation.entityId,
                            supabaseId = "tag_${operation.entityId}",
                            userId = (operation.data["userId"] as? Number)?.toLong() ?: 0,
                            name = operation.data["name"] as? String ?: "",
                            type = operation.data["type"] as? String ?: "",
                            color = operation.data["color"] as? String,
                            icon = operation.data["icon"] as? String,
                            parentTagId = (operation.data["parentTagId"] as? Number)?.toLong(),
                            isSystem = operation.data["isSystem"] as? Boolean ?: false,
                            syncState = SyncState.SYNCED,
                            lastModified = operation.timestamp,
                            version = 1,
                            isDeleted = false
                        )
                        dao.insertTag(tag)
                    }
                    "chat_messages" -> {
                        val message = ChatMessageEntity(
                            id = operation.entityId,
                            supabaseId = "msg_${operation.entityId}",
                            chatId = (operation.data["chatId"] as? Number)?.toLong() ?: 0,
                            userId = (operation.data["userId"] as? Number)?.toLong() ?: 0,
                            content = operation.data["content"] as? String ?: "",
                            messageType = operation.data["messageType"] as? String ?: "",
                            senderType = operation.data["senderType"] as? String ?: "",
                            contextSnapshot = operation.data["contextSnapshot"] as? String ?: "{}",
                            syncState = SyncState.SYNCED,
                            lastModified = operation.timestamp,
                            createdAt = operation.timestamp,
                            version = 1,
                            isDeleted = false
                        )
                        dao.insertChatMessage(message)
                    }
                }
            }
            "UPDATE" -> {
                when (operation.entityType) {
                    "notes" -> {
                        val existing = dao.getNoteById(operation.entityId)
                        existing?.let { note ->
                            val updated = note.copy(
                                title = operation.data["title"] as? String ?: note.title,
                                content = operation.data["content"] as? String ?: note.content,
                                category = operation.data["category"] as? String ?: note.category,
                                priority = (operation.data["priority"] as? Number)?.toInt() ?: note.priority,
                                isPinned = operation.data["isPinned"] as? Boolean ?: note.isPinned,
                                isArchived = operation.data["isArchived"] as? Boolean ?: note.isArchived,
                                lastModified = operation.timestamp,
                                version = note.version + 1
                            )
                            dao.updateNote(updated)
                        }
                    }
                    "tags" -> {
                        val existing = dao.getTagById(operation.entityId)
                        existing?.let { tag ->
                            val updated = tag.copy(
                                name = operation.data["name"] as? String ?: tag.name,
                                type = operation.data["type"] as? String ?: tag.type,
                                color = operation.data["color"] as? String ?: tag.color,
                                icon = operation.data["icon"] as? String ?: tag.icon,
                                parentTagId = (operation.data["parentTagId"] as? Number)?.toLong() ?: tag.parentTagId,
                                isSystem = operation.data["isSystem"] as? Boolean ?: tag.isSystem,
                                lastModified = operation.timestamp,
                                version = tag.version + 1
                            )
                            dao.updateTag(updated)
                        }
                    }
                    "chat_messages" -> {
                        val existing = dao.getChatMessageById(operation.entityId)
                        existing?.let { msg ->
                            val updated = msg.copy(
                                content = operation.data["content"] as? String ?: msg.content,
                                messageType = operation.data["messageType"] as? String ?: msg.messageType,
                                contextSnapshot = operation.data["contextSnapshot"] as? String ?: msg.contextSnapshot,
                                lastModified = operation.timestamp,
                                version = msg.version + 1
                            )
                            dao.updateChatMessage(updated)
                        }
                    }
                }
            }
            "DELETE" -> {
                // Soft delete: update is_deleted flag
                when (operation.entityType) {
                    "notes" -> {
                        val existing = dao.getNoteById(operation.entityId)
                        existing?.let { note ->
                            dao.updateNote(note.copy(isDeleted = true, lastModified = operation.timestamp, version = note.version + 1))
                        }
                    }
                    "tags" -> {
                        val existing = dao.getTagById(operation.entityId)
                        existing?.let { tag ->
                            dao.updateTag(tag.copy(isDeleted = true, lastModified = operation.timestamp, version = tag.version + 1))
                        }
                    }
                    "chat_messages" -> {
                        val existing = dao.getChatMessageById(operation.entityId)
                        existing?.let { msg ->
                            dao.updateChatMessage(msg.copy(isDeleted = true, lastModified = operation.timestamp, version = msg.version + 1))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Merge strategies for conflict resolution
 */
enum class MergeStrategy {
    LAST_WRITE_WINS,
    MANUAL,
    CUSTOM
}

/**
 * Exception for merge conflicts
 */
class MergeConflictException(message: String) : Exception(message)

/**
 * Marker interface for entities with metadata
 */
interface EntityWithMetadata {
    val lastModified: Instant
    val version: Long
}

/**
 * Sync state manager
 */
class SyncStateManager {

    private val syncStates = mutableMapOf<String, SyncStatus>()

    fun updateSyncStatus(entityType: String, entityId: Long, status: SyncStatus) {
        val key = "$entityType:$entityId"
        syncStates[key] = status
    }

    fun getSyncStatus(entityType: String, entityId: Long): SyncStatus? {
        val key = "$entityType:$entityId"
        return syncStates[key]
    }

    fun isSyncing(entityType: String, entityId: Long): Boolean {
        return getSyncStatus(entityType, entityId) == SyncStatus.SYNCING
    }

    fun markForSync(entityType: String, entityId: Long) {
        updateSyncStatus(entityType, entityId, SyncStatus.PENDING)
    }
}

enum class SyncStatus {
    PENDING,
    SYNCING,
    SYNCED,
    CONFLICT,
    ERROR
}
