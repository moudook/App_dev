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
            cachedHttpClient ?: HttpClient(OkHttp).also {
                cachedHttpClient = it
                logger.info("[LlmProviderFactory] HTTP client created (OkHttp engine)")
            }
        }
    }

    fun getOrCreateProvider(client: HttpClient = getOrCreateHttpClient()): LlmProvider {
        cachedProvider?.let {
            logger.debug("[LlmProviderFactory] Returning cached OpenCode provider")
            return it
        }
        return synchronized(this) {
            cachedProvider ?: create(client).also {
                cachedProvider = it
                logger.info("[LlmProviderFactory] OpenCode provider created and cached")
            }
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
        logger.info("[LlmProviderFactory] create() called — modelIdOverride={}, env LLM_MODEL_ID={}, finalModel={}",
            modelIdOverride, System.getenv("LLM_MODEL_ID"), finalModelId)

        if (providerOverride != null && providerOverride.uppercase() != "OPENCODE") {
            logger.warn("[LlmProviderFactory] Ignoring non-OpenCode provider override '{}' — only OpenCode CLI is supported", providerOverride)
        }
        if (apiKeyOverride != null) {
            logger.warn("[LlmProviderFactory] Ignoring API key override — OpenCode CLI requires no API keys")
        }
        if (baseUrlOverride != null) {
            logger.warn("[LlmProviderFactory] Ignoring base URL override — OpenCode CLI uses local subprocess")
        }

        val resolvedModel = OpencodeModelRegistry.requireAllowedFreeModel(finalModelId)
        logger.info("[LlmProviderFactory] Creating OpencodeLlmProvider — model={}, agent={}, daemon port={}",
            resolvedModel, System.getenv("OPENCODE_AGENT") ?: "smarty-headless-agent", 4096)

        return OpencodeLlmProvider(
            client = client,
            defaultModel = resolvedModel,
        )
    }
}
