package com.example.smarty.server.llm

import io.ktor.client.*
import io.ktor.client.engine.okhttp.OkHttp
import org.slf4j.LoggerFactory

/**
 * OpenCode CLI is the ONLY LLM provider.
 * All API-key providers have been removed to eliminate costs.
 */
object LlmProviderFactory {
    private val logger = LoggerFactory.getLogger(LlmProviderFactory::class.java)

    @Volatile
    private var cachedProvider: LlmProvider? = null

    @Volatile
    private var cachedHttpClient: HttpClient? = null

    fun getOrCreateHttpClient(): HttpClient {
        return cachedHttpClient ?: synchronized(this) {
            cachedHttpClient ?: HttpClient(OkHttp).also { cachedHttpClient = it }
        }
    }

    fun getOrCreateProvider(client: HttpClient = getOrCreateHttpClient()): LlmProvider {
        cachedProvider?.let { return it }
        return synchronized(this) {
            cachedProvider ?: create(client).also { cachedProvider = it }
        }
    }

    fun create(
        client: HttpClient,
        providerOverride: String? = null,
        baseUrlOverride: String? = null,
        apiKeyOverride: String? = null,
        modelIdOverride: String? = null,
    ): LlmProvider {
        val finalModelId = modelIdOverride ?: System.getenv("LLM_MODEL_ID")?.takeIf { it.isNotBlank() }

        logger.info("Initializing LLM Provider: OPENCODE (CLI-only, no API keys)")

        return OpencodeLlmProvider(
            client = client,
            defaultModel = OpencodeModelRegistry.requireAllowedFreeModel(finalModelId),
        )
    }
}
