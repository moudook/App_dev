package com.example.smarty.core.domain.model

/**
 * A calendar event referenced in an AI chat response.
 * Clickable card that opens event details in a bottom sheet.
 */
data class EventReference(
    val eventId: String,
    val title: String,
    val timeSnippet: String,
    val description: String? = null
)
