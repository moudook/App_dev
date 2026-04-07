package com.example.smarty.core.domain.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Represents a historical version of a note for git-like versioning.
 * Each time a note is updated, a version snapshot is saved.
 */
@Entity(
    tableName = "note_versions",
    foreignKeys = [
        ForeignKey(
            entity = Note::class,
            parentColumns = ["id"],
            childColumns = ["noteId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["noteId"]),
        Index(value = ["createdAt"]),
    ],
)
data class NoteVersion(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val noteId: String,
    val title: String,
    val content: String,
    val summary: String? = null,
    val versionNumber: Int,
    val createdAt: Long = System.currentTimeMillis(),
    val changeDescription: String? = null,
)
