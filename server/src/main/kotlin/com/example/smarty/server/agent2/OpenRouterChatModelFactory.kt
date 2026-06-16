package com.example.smarty.server.agent2

import dev.langchain4j.model.chat.listener.ChatModelListener
import dev.langchain4j.model.openai.OpenAiStreamingChatModel
import org.slf4j.LoggerFactory

data class OpenRouterConfig(
    val apiKey: String = System.getenv("OPENCODE_API_KEY") ?: "",
    val baseUrl: String = "https://openrouter.ai/api/v1",
    val siteUrl: String = System.getenv("OPENROUTER_SITE_URL") ?: "https://github.com/moudook",
    val siteTitle: String = System.getenv("OPENROUTER_SITE_TITLE") ?: "Smarty",
    val enableCache: Boolean = true,
) {
    val isConfigured: Boolean get() = apiKey.isNotBlank()
}

class OpenRouterChatModelFactory(
    private val config: OpenRouterConfig = OpenRouterConfig(),
    private val listeners: List<ChatModelListener> = emptyList(),
) {
    private val logger = LoggerFactory.getLogger(OpenRouterChatModelFactory::class.java)

    fun buildStreamingModel(
        modelId: String,
        maxTokens: Int = 4_096,
        thinkingTokens: Int = 2_048,
        temperature: Double = 0.7,
        sessionId: String? = null,
    ): OpenAiStreamingChatModel {
        val freeModelId = if (modelId.contains(":free")) modelId else "$modelId:free"

        val builder = OpenAiStreamingChatModel.builder()
            .baseUrl(config.baseUrl)
            .apiKey(config.apiKey)
            .modelName(freeModelId)
            .maxTokens(maxTokens)
            .temperature(temperature)
            .customHeaders(
                mapOf(
                    "HTTP-Referer" to config.siteUrl,
                    "X-Title" to config.siteTitle,
                ),
            )
            .logRequests(false)
            .logResponses(false)

        if (listeners.isNotEmpty()) {
            builder.listeners(listeners)
        }

        val extraBody = mutableMapOf<String, Any>()
        if (sessionId != null) {
            extraBody["session_id"] = sessionId
        }
        if (config.enableCache) {
            extraBody["x-openrouter-cache"] = true
        }

        val params = mutableMapOf<String, Any>()
        params["extra_body"] = extraBody

        if (thinkingTokens > 0) {
            params["reasoning"] = mapOf("max_tokens" to thinkingTokens)
        }

        builder.customParameters(params)

        logger.info("[OpenRouterChatModelFactory] Built streaming model: $freeModelId (maxTokens=$maxTokens, thinking=$thinkingTokens)")

        return builder.build()
    }
}
