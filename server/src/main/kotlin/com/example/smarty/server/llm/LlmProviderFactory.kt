package com.example.smarty.server.llm

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * OpenCode Zen API is the ONLY LLM provider.
 * All model inference routes through the Zen HTTP API natively.
 * NO CLI daemon required.
 */
object LlmProviderFactory {
    private val logger = LoggerFactory.getLogger(LlmProviderFactory::class.java)

    private val daemonJson =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
            isLenient = true
        }

    @Volatile
    private var cachedProvider: LlmProvider? = null

    @Volatile
    private var cachedHttpClient: HttpClient? = null

    fun getOrCreateHttpClient(): HttpClient =
        cachedHttpClient ?: synchronized(this) {
            cachedHttpClient ?: HttpClient(OkHttp) {
                engine {
                    config {
                        // LLM inference on HF Space can be very slow — the daemon may take
                        // >5 min to start streaming the first SSE event. 30 min read/write
                        // timeout matches HttpClientFactory.createLongTimeout() semantics
                        // and is the only reason chat responses were failing with
                        // "AI service took too long to respond" after exactly 5 min.
                        connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                        readTimeout(30, java.util.concurrent.TimeUnit.MINUTES)
                        writeTimeout(30, java.util.concurrent.TimeUnit.MINUTES)
                        callTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS) // disable total-call cap
                    }
                }
                install(ContentNegotiation) {
                    json(daemonJson)
                }
            }.also {
                cachedHttpClient = it
                logger.info("[LlmProviderFactory] HTTP client created (OkHttp engine + ContentNegotiation with 30-min read/write timeouts)")
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
        logger.info(
            "[LlmProviderFactory] Creating OpencodeLlmProvider — model={}, daemon port={}",
            resolvedModel,
            4096,
        )

        return OpencodeLlmProvider(
            client = client,
            defaultModel = resolvedModel,
        )
    }
}
