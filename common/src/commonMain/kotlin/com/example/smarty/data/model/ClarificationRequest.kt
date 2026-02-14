package com.example.smarty.core.domain.model

import kotlinx.serialization.Serializable

/**
 * Request for user clarification during complex planning.
 */
@Serializable
data class ClarificationRequest(
    val question: String,
    val options: List<String>,       // e.g. ["Vacation", "Business"]
    val allowCustomInput: Boolean = true // e.g. "Other..."
)
