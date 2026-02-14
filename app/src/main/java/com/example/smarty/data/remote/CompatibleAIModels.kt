package com.example.smarty.data.remote

import com.google.gson.annotations.SerializedName

/**
 * Request model for compatible AI APIs (OpenAI format).
 */
data class CompatibleAIRequest(
    @SerializedName("model") val model: String,
    @SerializedName("messages") val messages: List<CompatibleAIMessage>,
    @SerializedName("temperature") val temperature: Float = 0.7f,
    @SerializedName("max_tokens") val maxTokens: Int = 1000,
    @SerializedName("stream") val stream: Boolean = false
)

/**
 * Message model for compatible AI APIs.
 */
data class CompatibleAIMessage(
    @SerializedName("role") val role: String,
    @SerializedName("content") val content: String
)
