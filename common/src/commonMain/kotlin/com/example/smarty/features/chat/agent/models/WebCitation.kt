package com.example.smarty.features.chat.agent.models

import kotlinx.serialization.Serializable

/**
 * Web search citation for AI responses
 */
@Serializable
data class WebCitation(
    val title: String,
    val url: String,
    val snippet: String
)
