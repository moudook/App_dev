package com.example.smarty.server.llm

import io.ktor.client.*
import io.ktor.client.engine.okhttp.OkHttp
import org.slf4j.LoggerFactory

/**
 * OpenCode CLI is the ONLY LLM provider.
 * All model inference routes through `opencode run` with free Zen models.
 * NO API keys required.
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

    /**
     * Create a new OpencodeLlmProvider instance.
     * All parameters are ignored — OpenCode CLI is the only provider.
     */
    fun create(
        client: HttpClient,
        providerOverride: String? = null,
        baseUrlOverride: String? = null,
        apiKeyOverride: String? = null,
        modelIdOverride: String? = null,
    ): LlmProvider {
        val finalModelId = modelIdOverride ?: System.getenv("LLM_MODEL_ID")?.takeIf { it.isNotBlank() }

        if (providerOverride != null && providerOverride.uppercase() != "OPENCODE") {
            logger.warn("Ignoring non-OpenCode provider override '$providerOverride' — only OpenCode CLI is supported")
        }
        if (apiKeyOverride != null) {
            logger.warn("Ignoring API key override — OpenCode CLI requires no API keys")
        }
        if (baseUrlOverride != null) {
            logger.warn("Ignoring base URL override — OpenCode CLI uses local subprocess")
        }

        logger.info("Initializing LLM Provider: OPENCODE (CLI-only, free models, no API keys)")

        return OpencodeLlmProvider(
            client = client,
            defaultModel = OpencodeModelRegistry.requireAllowedFreeModel(finalModelId),
        )
    }
}
