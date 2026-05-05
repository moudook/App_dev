
package com.smarty.data.entity

import androidx.room.*
import com.smarty.data.model.SyncState
import com.smarty.data.sync.EntityWithMetadata
import java.time.Instant

/**
 * User entity with comprehensive sync state management and device-aware context
 */
@Entity(
    tableName = "users",
    indices = [
        Index(value = ["supabase_id"], unique = true),
        Index(value = ["email"], unique = true),
        Index(value = ["sync_state", "last_modified"])
    ]
)
data class UserEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "supabase_id")
    val supabaseId: String,

    @ColumnInfo(name = "email")
    val email: String,

    @ColumnInfo(name = "display_name")
    val displayName: String? = null,

    @ColumnInfo(name = "avatar_url")
    val avatarUrl: String? = null,

    @ColumnInfo(name = "phone_number")
    val phoneNumber: String? = null,

    @ColumnInfo(name = "timezone")
    val timezone: String = "UTC",

    @ColumnInfo(name = "locale")
    val locale: String = "en-US",

    @ColumnInfo(name = "preferences")
    val preferences: String? = null, // JSON serialized

    @ColumnInfo(name = "sync_state")
    @SyncState.State
    val syncState: String = SyncState.PENDING,

    @ColumnInfo(name = "last_sync_timestamp")
    val lastSyncTimestamp: Instant? = null,

    @ColumnInfo(name = "last_modified")
    override val lastModified: Instant = Instant.now(),

    @ColumnInfo(name = "version")
    override val version: Long = 0, // For optimistic locking

    @ColumnInfo(name = "is_deleted")
    val isDeleted: Boolean = false,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: Instant? = null,

    // Device-aware context propagation
    @Embedded(prefix = "device_")
    val deviceContext: DeviceContext? = null,

    @ColumnInfo(name = "active_device_fingerprint")
    val activeDeviceFingerprint: String? = null,

    @ColumnInfo(name = "session_token")
    val sessionToken: String? = null,

    @ColumnInfo(name = "metadata")
    val metadata: String? = null // JSON for extensibility
) : EntityWithMetadata

/**
 * Device context for user-aware operations
 */
data class DeviceContext(
    @ColumnInfo(name = "device_id")
    val deviceId: String,

    @ColumnInfo(name = "device_type")
    val deviceType: String, // MOBILE, TABLET, DESKTOP, WEB

    @ColumnInfo(name = "os_version")
    val osVersion: String,

    @ColumnInfo(name = "app_version")
    val appVersion: String,

    @ColumnInfo(name = "screen_density")
    val screenDensity: Float,

    @ColumnInfo(name = "screen_width")
    val screenWidth: Int,

    @ColumnInfo(name = "screen_height")
    val screenHeight: Int,

    @ColumnInfo(name = "network_type")
    val networkType: String, // WIFI, MOBILE, OFFLINE

    @ColumnInfo(name = "battery_level")
    val batteryLevel: Float? = null,

    @ColumnInfo(name = "is_low_power_mode")
    val isLowPowerMode: Boolean = false,

    @ColumnInfo(name = "created_at")
    val createdAt: Instant = Instant.now()
)


/**
 * User with related data
 */
data class UserWithRelations(
    @Embedded
    val user: UserEntity,

    @Relation(
        parentColumn = "id",
        entityColumn = "user_id"
    )
    val notes: List<NoteEntity> = emptyList(),

    @Relation(
        parentColumn = "id",
        entityColumn = "user_id"
    )
    val reasoningTraces: List<ReasoningTraceEntity> = emptyList(),

    @Relation(
        parentColumn = "id",
        entityColumn = "user_id"
    )
    val agentCheckpoints: List<AgentCheckpointEntity> = emptyList()
)
