
package com.smarty.data.model

import java.time.Instant

/**
 * Data models for sync and CRDT operations
 */

data class VectorClock(
    val clocks: MutableMap<String, Long> = mutableMapOf()
) {
    fun increment(deviceId: String) {
        clocks[deviceId] = (clocks[deviceId] ?: 0) + 1
    }

    fun isConcurrentWith(other: VectorClock): Boolean {
        val allDevices = clocks.keys + other.clocks.keys
        var lessThan = false
        var greaterThan = false

        for (device in allDevices) {
            val thisTime = clocks[device] ?: 0
            val otherTime = other.clocks[device] ?: 0

            if (thisTime < otherTime) lessThan = true
            if (thisTime > otherTime) greaterThan = true
        }

        return lessThan && greaterThan
    }

    fun merge(other: VectorClock): VectorClock {
        val merged = VectorClock()
        val allDevices = clocks.keys + other.clocks.keys

        for (device in allDevices) {
            merged.clocks[device] = maxOf(
                clocks[device] ?: 0,
                other.clocks[device] ?: 0
            )
        }

        return merged
    }
}

data class CRDTOperation(
    val id: String,
    val type: String, // INSERT, UPDATE, DELETE
    val entityType: String,
    val entityId: Long,
    val data: Map<String, Any>,
    val vectorClock: VectorClock,
    val deviceId: String,
    val timestamp: Instant
)


/**
 * Sync state tracking is handled by SyncStateEntity and the SyncState string constants below.
 */

object SyncState {
    @Retention(AnnotationRetention.SOURCE)
    @androidx.annotation.StringDef(PENDING, SYNCING, SYNCED, CONFLICT, ERROR, OFFLINE_ONLY)
    annotation class State

    const val PENDING = "pending"
    const val SYNCING = "syncing"
    const val SYNCED = "synced"
    const val CONFLICT = "conflict"
    const val ERROR = "error"
    const val OFFLINE_ONLY = "offline_only"
}

data class DeviceInfo(
    val deviceId: String,
    val deviceType: String,
    val osVersion: String,
    val appVersion: String,
    val lastActive: Instant
)

data class UserSession(
    val userId: Long,
    val sessionId: String,
    val deviceId: String,
    val startedAt: Instant,
    val lastActivity: Instant,
    val isActive: Boolean = true
)

data class PredictionModel(
    val modelId: String,
    val version: String,
    val lastTrained: Instant,
    val accuracy: Float
)

data class AIDecision(
    val decisionId: String,
    val entityType: String,
    val entityId: Long,
    val action: String,
    val confidence: Float,
    val reasoning: List<String>,
    val alternatives: List<String>,
    val timestamp: Instant
)

data class ContextSnapshot(
    val userId: Long,
    val activeNoteId: Long?,
    val activeChatId: Long?,
    val activeEventId: Long?,
    val recentEntities: List<RecentEntity>,
    val tags: List<String>,
    val timestamp: Instant
)

data class RecentEntity(
    val type: String,
    val id: Long,
    val title: String,
    val lastAccessed: Instant
)

data class MergeResult<T>(
    val merged: T,
    val conflicts: List<Conflict>,
    val strategy: String
)

data class Conflict(
    val field: String,
    val localValue: Any?,
    val remoteValue: Any?,
    val requiresManualResolution: Boolean = true
)

data class SyncMetrics(
    val totalSynced: Long,
    val conflictsResolved: Long,
    val averageSyncTime: Double,
    val lastSyncTime: Instant
)
