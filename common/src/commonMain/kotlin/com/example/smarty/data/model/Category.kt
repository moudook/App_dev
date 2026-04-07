package com.example.smarty.core.domain.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
// import java.util.UUID // UUID is Java-specific.

// TODO: Remove Room annotations from this KMP common module.
// Strategy (Option B):
// Temporarily keeping @Entity, @PrimaryKey in commonMain
// by relying on `androidx.room:room-common` (pure annotations).
//
// Future Plan:
// 1. Create Android-specific wrapper classes in :app (e.g., RoomCategory).
// 2. Remove all `androidx.room.*` imports from this file.
@Serializable
@Entity(
    tableName = "categories",
    indices = [
        Index(value = ["name"], unique = true),
    ],
)
data class Category(
    @PrimaryKey
    val id: String, // Removed default UUID generation to avoid Java dependency in pure common (or need expect/actual)
    val name: String,
    val description: String? = null,
    val noteCount: Int = 0,
    val isAiGenerated: Boolean = true,
    val createdAt: Long = 0L, // System.currentTimeMillis() replacement
    val lastUpdated: Long = 0L,
)
