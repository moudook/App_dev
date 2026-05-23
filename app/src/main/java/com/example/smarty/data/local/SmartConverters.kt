package com.example.smarty.data.local

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Date

/**
 * Type Converters for Room database
 * Handles JSON serialization for complex types
 */
class SmartConverters {
    private val gson = Gson()

    // ============================================================
    // LIST CONVERTERS
    // ============================================================

    @TypeConverter
    fun fromStringList(value: List<String>?): String {
        return gson.toJson(value ?: emptyList<String>())
    }

    @TypeConverter
    fun toStringList(value: String?): List<String> {
        if (value == null) return emptyList()
        val type = object : TypeToken<List<String>>() {}.type
        return gson.fromJson(value, type)
    }

    // ============================================================
    // MAP CONVERTERS
    // ============================================================

    @TypeConverter
    fun fromStringMap(value: Map<String, String>?): String {
        return gson.toJson(value ?: emptyMap<String, String>())
    }

    @TypeConverter
    fun toStringMap(value: String?): Map<String, String> {
        if (value == null) return emptyMap()
        val type = object : TypeToken<Map<String, String>>() {}.type
        return gson.fromJson(value, type)
    }

    // ============================================================
    // DATE CONVERTERS
    // ============================================================

    @TypeConverter
    fun fromDate(date: Date?): Long? {
        return date?.time
    }

    @TypeConverter
    fun toDate(timestamp: Long?): Date? {
        return timestamp?.let { Date(it) }
    }

    // ============================================================
    // ENUM CONVERTERS
    // ============================================================

    @TypeConverter
    fun fromTagType(type: TagEntity.TagType?): String? {
        return type?.name
    }

    @TypeConverter
    fun toTagType(value: String?): TagEntity.TagType {
        return try {
            value?.let { TagEntity.TagType.valueOf(it) } ?: TagEntity.TagType.MANUAL
        } catch (e: IllegalArgumentException) {
            TagEntity.TagType.MANUAL
        }
    }

    @TypeConverter
    fun fromTaskStatus(status: TaskEntity.TaskStatus?): String? {
        return status?.name
    }

    @TypeConverter
    fun toTaskStatus(value: String?): TaskEntity.TaskStatus {
        return try {
            value?.let { TaskEntity.TaskStatus.valueOf(it) } ?: TaskEntity.TaskStatus.TODO
        } catch (e: IllegalArgumentException) {
            TaskEntity.TaskStatus.TODO
        }
    }

    @TypeConverter
    fun fromPermission(permission: SharedItemEntity.Permission?): String? {
        return permission?.name
    }

    @TypeConverter
    fun toPermission(value: String?): SharedItemEntity.Permission {
        return try {
            value?.let { SharedItemEntity.Permission.valueOf(it) } ?: SharedItemEntity.Permission.VIEW
        } catch (e: IllegalArgumentException) {
            SharedItemEntity.Permission.VIEW
        }
    }

    @TypeConverter
    fun fromCheckpointType(type: AgentCheckpointEntity.CheckpointType?): String? {
        return type?.name
    }

    @TypeConverter
    fun toCheckpointType(value: String?): AgentCheckpointEntity.CheckpointType {
        return try {
            value?.let { AgentCheckpointEntity.CheckpointType.valueOf(it) } ?: AgentCheckpointEntity.CheckpointType.MANUAL
        } catch (e: IllegalArgumentException) {
            AgentCheckpointEntity.CheckpointType.MANUAL
        }
    }

    @TypeConverter
    fun fromDigestType(type: DailyDigestEntity.DigestType?): String? {
        return type?.name
    }

    @TypeConverter
    fun toDigestType(value: String?): DailyDigestEntity.DigestType {
        return try {
            value?.let { DailyDigestEntity.DigestType.valueOf(it) } ?: DailyDigestEntity.DigestType.DAILY
        } catch (e: IllegalArgumentException) {
            DailyDigestEntity.DigestType.DAILY
        }
    }

    @TypeConverter
    fun fromSearchType(type: SearchHistoryEntity.SearchType?): String? {
        return type?.name
    }

    @TypeConverter
    fun toSearchType(value: String?): SearchHistoryEntity.SearchType {
        return try {
            value?.let { SearchHistoryEntity.SearchType.valueOf(it) } ?: SearchHistoryEntity.SearchType.TEXT
        } catch (e: IllegalArgumentException) {
            SearchHistoryEntity.SearchType.TEXT
        }
    }

    // ============================================================
    // BOOLEAN CONVERTERS
    // ============================================================

    @TypeConverter
    fun fromBoolean(value: Boolean?): Int {
        return if (value == true) 1 else 0
    }

    @TypeConverter
    fun toBoolean(value: Int?): Boolean {
        return value == 1
    }

    // ============================================================
    // JSON OBJECT CONVERTERS
    // ============================================================

    @TypeConverter
    fun <T> fromJson(
        json: String?,
        classOfT: Class<T>,
    ): T? {
        if (json == null) return null
        return gson.fromJson(json, classOfT)
    }

    @TypeConverter
    fun <T> toJson(obj: T?): String? {
        return gson.toJson(obj)
    }
}
