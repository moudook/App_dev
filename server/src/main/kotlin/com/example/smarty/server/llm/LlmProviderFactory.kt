package com.example.smarty.server.llm

import io.ktor.client.*
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * OpenCode CLI is the ONLY LLM provider.
 * All model inference routes through the daemon HTTP API (port 4096).
 * NO API keys required.
 */
object LlmProviderFactory {
    private val logger = LoggerFactory.getLogger(LlmProviderFactory::class.java)

    private val daemonJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = true
    }

    @Volatile
    private var cachedProvider: LlmProvider? = null

    @Volatile
    private var cachedHttpClient: HttpClient? = null

    fun getOrCreateHttpClient(): HttpClient {
        return cachedHttpClient ?: synchronized(this) {
            cachedHttpClient ?: HttpClient(OkHttp) {
                install(ContentNegotiation) {
                    json(daemonJson)
                }
            }.also {
                cachedHttpClient = it
                logger.info("[LlmProviderFactory] HTTP client created (OkHttp engine + ContentNegotiation)")
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

    fun create(client: HttpClient): LlmProvider {
        val resolvedModel = OpencodeModelRegistry.requireAllowedFreeModel(null)
        logger.info("[LlmProviderFactory] Creating OpencodeLlmProvider — model={}, daemon port={}",
            resolvedModel, 4096)

        return OpencodeLlmProvider(
            client = client,
            defaultModel = resolvedModel,
        )
    }
}
