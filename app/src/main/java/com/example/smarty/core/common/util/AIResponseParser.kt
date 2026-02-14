package com.example.smarty.core.common.util

import com.example.smarty.data.remote.AIResponse
import com.example.smarty.core.domain.model.AttachmentMetadata
import kotlinx.serialization.json.Json

object AIResponseParser {
    fun validateCategory(category: String?): String {
        return category ?: "general"
    }

    fun smartFallbackCategorization(context: android.content.Context, content: String): AIResponse {
        return AIResponse(
            title = "Untitled Note",
            category = "general",
            summary = "Automatic fallback summary due to AI unavailability.",
            whySaved = "User created content",
            success = true
        )
    }
}
