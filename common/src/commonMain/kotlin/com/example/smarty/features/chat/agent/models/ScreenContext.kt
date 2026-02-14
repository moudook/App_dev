package com.example.smarty.features.chat.agent.models

import kotlinx.serialization.Serializable

/**
 * Capture context of what the user is currently looking at on screen.
 * Used to provide situational awareness to the AI agent.
 */
@Serializable
data class ScreenContext(
    val selectedText: String? = null,
    val referringApp: String? = null,
    val capturedAt: Long = System.currentTimeMillis(),
    val contextData: Map<String, String> = emptyMap()
)
