package com.example.smarty.data.local

import androidx.room.*
import kotlinx.serialization.Serializable

/**
 * User entity - now properly integrated with Room database
 * Represents a user in the system with sync state management
 */
@Serializable
@Entity(
    tableName = "users",
    indices = [
        Index(value = ["firebase_uid"], unique = true),
        Index(value = ["email"], unique = true),
        Index(value = ["is_active"]),
        Index(value = ["last_login_at"]),
        Index(value = ["updated_at"]),
    ],
)
data class UserEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "firebase_uid")
    val firebaseUid: String,
    val email: String?,
    @ColumnInfo(name = "display_name")
    val displayName: String?,
    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true,
    @ColumnInfo(name = "is_premium")
    val isPremium: Boolean = false,
    @ColumnInfo(name = "subscription_expires_at")
    val subscriptionExpiresAt: Long? = null,
    @ColumnInfo(name = "feature_flags")
    val featureFlags: String = "{}",
    @ColumnInfo(name = "sync_state")
    val syncState: String = SyncState.PENDING.name,
    @ColumnInfo(name = "device_fingerprint")
    val deviceFingerprint: String? = null,
    @ColumnInfo(name = "last_device_id")
    val lastDeviceId: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "last_login_at")
    val lastLoginAt: Long = System.currentTimeMillis(),
) {
    enum class SyncState {
        PENDING,
        SYNCING,
        SYNCED,
        CONFLICT,
        ERROR,
    }

    /**
     * Device context for user-aware operations
     */
    data class DeviceContext(
        val deviceId: String,
        val deviceName: String,
        val deviceType: String = "android",
        val appVersion: String,
        val osVersion: String,
        val lastActiveAt: Long = System.currentTimeMillis(),
        val capabilities: List<String> = emptyList(),
    )
}

/**
 * Sync state for user data
 */
@Entity(
    tableName = "sync_state",
    indices = [
        Index(value = ["user_id"], unique = true),
    ],
)
data class SyncStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "user_id")
    val userId: String,
    @ColumnInfo(name = "last_sync_at")
    val lastSyncAt: Long? = null,
    @ColumnInfo(name = "last_pull_at")
    val lastPullAt: Long? = null,
    @ColumnInfo(name = "last_push_at")
    val lastPushAt: Long? = null,
    @ColumnInfo(name = "pending_operations")
    val pendingOperations: Int = 0,
    @ColumnInfo(name = "conflict_count")
    val conflictCount: Int = 0,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
)
