package com.example.smarty.core.domain.model

/**
 * Represents a historical version of a note for git-like versioning.
 * Each time a note is updated, a version snapshot is saved.
 */
data class NoteVersion(
    val id: String = java.util.UUID.randomUUID().toString(),
    val noteId: String,
    val title: String,
    val content: String,
    val summary: String? = null,
    val versionNumber: Int,
    val createdAt: Long = System.currentTimeMillis(),
    val changeDescription: String? = null,
)
