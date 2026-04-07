package com.example.smarty.core.domain.model

/**
 * A note referenced in an AI chat response.
 * Clickable card that opens note details in a bottom sheet.
 */
data class NoteReference(
    val noteId: String,
    val title: String,
    val snippet: String,
    val category: String? = null,
)
