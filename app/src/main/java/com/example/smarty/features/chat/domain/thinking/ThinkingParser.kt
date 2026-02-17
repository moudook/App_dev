package com.example.smarty.features.chat.domain.thinking

/**
 * Parsed response containing thinking process and final answer.
 * Used for displaying reasoning from AI responses.
 */
data class ParsedResponse(
    val thinking: String?,  // Content inside 