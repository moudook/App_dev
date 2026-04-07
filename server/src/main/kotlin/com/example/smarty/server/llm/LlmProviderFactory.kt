package com.example.smarty.server.llm

import io.ktor.client.*
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory

object LlmProviderFactory {
    private val logger = LoggerFactory.getLogger(LlmProviderFactory::class.java)

    // Cached provider instance to avoid recreating on each request
    @Volatile
    private var cachedProvider: LlmProvider? = null

    // Cached HTTP client for reuse
    @Volatile
    private var cachedHttpClient: HttpClient? = null

    private fun parseApiKeys(envVar: String?): List<String> {
        if (envVar.isNullOrBlank()) return emptyList()
        return envVar.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private fun getEnvKeyName(provider: String): String {
        return when (provider.uppercase()) {
            "GEMINI" -> "GEMINI_API_KEY"
            "OPENAI" -> "OPENAI_API_KEY"
            "GROQ" -> "GROQ_API_KEY"
            "DEEPSEEK" -> "DEEPSEEK_API_KEY"
            "OPENROUTER" -> "OPENROUTER_API_KEY"
            "CEREBRAS" -> "CEREBRAS_API_KEY"
            "GITHUB" -> "GITHUB_TOKEN"
            "LOCAL", "LOCAL_PC" -> "LOCAL_LLM_KEY"
            else -> "${provider.uppercase()}_API_KEY"
        }
    }

    /**
     * Get or create a cached HTTP client for reuse across requests
     */
    fun getOrCreateHttpClient(): HttpClient {
        return cachedHttpClient ?: synchronized(this) {
            cachedHttpClient ?: HttpClient(OkHttp).also { cachedHttpClient = it }
        }
    }

    /**
     * Get or create a cached LLM provider for reuse across requests
     */
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
        val activeProvider =
            providerOverride?.uppercase()
                ?: System.getenv("ACTIVE_PROVIDER")?.uppercase()
                ?: "GEMINI"

        val envBaseUrl = System.getenv("LLM_BASE_URL")?.takeIf { it.isNotBlank() }
        val envModelId = System.getenv("LLM_MODEL_ID")?.takeIf { it.isNotBlank() }

        val finalBaseUrl = baseUrlOverride ?: envBaseUrl
        val finalModelId = modelIdOverride ?: envModelId

        val envKeyName = getEnvKeyName(activeProvider)
        val rawEnvValue = System.getenv(envKeyName)
        val keys = parseApiKeys(apiKeyOverride ?: rawEnvValue)

        logger.info("Initializing LLM Provider: $activeProvider with ${keys.size} API key(s)")

        if (keys.isEmpty()) {
            logger.warn("WARNING: $envKeyName is missing. Falling back to MOCK provider.")
            return createMock(client)
        }

        return when (activeProvider) {
            "GEMINI" -> {
                if (keys.size > 1) {
                    KeyRotatingGeminiProvider(client, keys)
                } else {
                    createGemini(client, keys[0])
                }
            }
            "OPENAI" -> {
                if (keys.size > 1) {
                    KeyRotatingOpenAiProvider(
                        client,
                        "OpenAI",
                        finalBaseUrl ?: "https://api.openai.com/v1",
                        keys,
                        finalModelId ?: "gpt-4-turbo-preview",
                    )
                } else {
                    createOpenAi(client, keys[0], finalBaseUrl, finalModelId)
                }
            }
            "GROQ" -> {
                val url = finalBaseUrl ?: "https://api.groq.com/openai/v1"
                val model = finalModelId ?: "llama3-70b-8192"
                if (keys.size > 1) {
                    KeyRotatingOpenAiProvider(client, "Groq", url, keys, model)
                } else {
                    createGroq(client, keys[0], finalBaseUrl, finalModelId)
                }
            }
            "DEEPSEEK" -> {
                val url = finalBaseUrl ?: "https://api.deepseek.com"
                val model = finalModelId ?: "deepseek-chat"
                if (keys.size > 1) {
                    KeyRotatingOpenAiProvider(client, "DeepSeek", url, keys, model)
                } else {
                    createDeepSeek(client, keys[0], finalBaseUrl, finalModelId)
                }
            }
            "OPENROUTER" -> {
                val url = finalBaseUrl ?: "https://openrouter.ai/api/v1"
                val model = finalModelId ?: "openai/gpt-4o"
                if (keys.size > 1) {
                    KeyRotatingOpenAiProvider(client, "OpenRouter", url, keys, model)
                } else {
                    createOpenRouter(client, keys[0], finalBaseUrl, finalModelId)
                }
            }
            "CEREBRAS" -> {
                val url = finalBaseUrl ?: "https://api.cerebras.ai/v1"
                val model = finalModelId ?: "llama3.1-70b"
                if (keys.size > 1) {
                    KeyRotatingOpenAiProvider(client, "Cerebras", url, keys, model)
                } else {
                    createCerebras(client, keys[0], finalBaseUrl, finalModelId)
                }
            }
            "GITHUB" -> {
                val url = finalBaseUrl ?: "https://models.inference.ai.azure.com"
                val model = finalModelId ?: "gpt-4o"
                if (keys.size > 1) {
                    KeyRotatingOpenAiProvider(client, "GitHub Models", url, keys, model)
                } else {
                    createGitHub(client, keys[0], finalBaseUrl, finalModelId)
                }
            }
            "LOCAL", "LOCAL_PC" -> createLocal(client, finalBaseUrl, keys.firstOrNull(), finalModelId)
            "MOCK" -> createMock(client)
            "CUSTOM", "OPENAI_COMPATIBLE" -> {
                if (keys.isNotEmpty()) {
                    if (keys.size > 1) {
                        KeyRotatingOpenAiProvider(
                            client,
                            "Custom",
                            finalBaseUrl ?: "http://localhost:8000/v1",
                            keys,
                            finalModelId ?: "llama3",
                        )
                    } else {
                        createOpenAi(client, keys[0], finalBaseUrl, finalModelId)
                    }
                } else {
                    createLocal(client, finalBaseUrl, null, finalModelId)
                }
            }
            else -> {
                // For unknown providers, check if LLM_BASE_URL is provided - treat as OpenAI-compatible
                if (finalBaseUrl != null) {
                    logger.info("Unknown provider: $activeProvider. Using as OpenAI-compatible with baseUrl: $finalBaseUrl")
                    if (keys.isNotEmpty()) {
                        if (keys.size > 1) {
                            KeyRotatingOpenAiProvider(client, "Custom", finalBaseUrl, keys, finalModelId ?: "llama3")
                        } else {
                            createOpenAi(client, keys.first(), finalBaseUrl, finalModelId)
                        }
                    } else {
                        createLocal(client, finalBaseUrl, null, finalModelId)
                    }
                } else {
                    logger.warn("Unknown provider: $activeProvider with no LLM_BASE_URL. Falling back to OpenAI.")
                    if (keys.isNotEmpty()) {
                        if (keys.size > 1) {
                            KeyRotatingOpenAiProvider(
                                client,
                                "OpenAI",
                                finalBaseUrl ?: "https://api.openai.com/v1",
                                keys,
                                finalModelId ?: "gpt-4-turbo-preview",
                            )
                        } else {
                            createOpenAi(client, keys[0], finalBaseUrl, finalModelId)
                        }
                    } else {
                        createMock(client)
                    }
                }
            }
        }
    }

    private fun createOpenAi(
        client: HttpClient,
        apiKey: String,
        baseUrlOverride: String?,
        modelIdOverride: String?,
    ) = OpenAiCompatibleProvider(
        client = client,
        providerName = "OpenAI",
        baseUrl = baseUrlOverride ?: "https://api.openai.com/v1",
        apiKey = apiKey,
        defaultModel = modelIdOverride ?: "gpt-4-turbo-preview",
    )

    private fun createGroq(
        client: HttpClient,
        apiKey: String?,
        baseUrlOverride: String?,
        modelIdOverride: String?,
    ) = OpenAiCompatibleProvider(
        client = client,
        providerName = "Groq",
        baseUrl = baseUrlOverride ?: "https://api.groq.com/openai/v1",
        apiKey = apiKey ?: "",
        defaultModel = modelIdOverride ?: "llama3-70b-8192",
    )

    private fun createDeepSeek(
        client: HttpClient,
        apiKey: String?,
        baseUrlOverride: String?,
        modelIdOverride: String?,
    ) = OpenAiCompatibleProvider(
        client = client,
        providerName = "DeepSeek",
        baseUrl = baseUrlOverride ?: "https://api.deepseek.com",
        apiKey = apiKey ?: "",
        defaultModel = modelIdOverride ?: "deepseek-chat",
    )

    private fun createGemini(
        client: HttpClient,
        apiKey: String,
    ) = GeminiProvider(
        client = client,
        apiKey = apiKey,
    )

    private fun createOpenRouter(
        client: HttpClient,
        apiKey: String?,
        baseUrlOverride: String?,
        modelIdOverride: String?,
    ) = OpenAiCompatibleProvider(
        client = client,
        providerName = "OpenRouter",
        baseUrl = baseUrlOverride ?: "https://openrouter.ai/api/v1",
        apiKey = apiKey ?: "",
        defaultModel = modelIdOverride ?: "openai/gpt-4o",
    )

    private fun createCerebras(
        client: HttpClient,
        apiKey: String?,
        baseUrlOverride: String?,
        modelIdOverride: String?,
    ) = OpenAiCompatibleProvider(
        client = client,
        providerName = "Cerebras",
        baseUrl = baseUrlOverride ?: "https://api.cerebras.ai/v1",
        apiKey = apiKey ?: "",
        defaultModel = modelIdOverride ?: "llama3.1-70b",
    )

    private fun createGitHub(
        client: HttpClient,
        apiKey: String?,
        baseUrlOverride: String?,
        modelIdOverride: String?,
    ) = OpenAiCompatibleProvider(
        client = client,
        providerName = "GitHub Models",
        baseUrl = baseUrlOverride ?: "https://models.inference.ai.azure.com",
        apiKey = apiKey ?: "",
        defaultModel = modelIdOverride ?: "gpt-4o",
    )

    private fun createLocal(
        client: HttpClient,
        baseUrlOverride: String?,
        apiKey: String?,
        modelIdOverride: String?,
    ) = OpenAiCompatibleProvider(
        client = client,
        providerName = "Local LLM",
        baseUrl = baseUrlOverride ?: System.getenv("LOCAL_LLM_URL") ?: "http://localhost:8000/v1",
        apiKey = apiKey ?: System.getenv("LOCAL_LLM_KEY") ?: "not-needed",
        defaultModel = modelIdOverride ?: System.getenv("LOCAL_LLM_MODEL") ?: "chatglm3-6b",
    )

    private fun createMock(client: HttpClient) =
        OpenAiCompatibleProvider(
            client = client,
            providerName = "Mock",
            baseUrl = "http://localhost:7860/mock",
            apiKey = "mock-key",
            defaultModel = "mock-model",
        )
}

class KeyRotatingOpenAiProvider(
    private val client: HttpClient,
    private val baseProviderName: String,
    private val baseUrl: String,
    private val apiKeys: List<String>,
    private val defaultModel: String,
) : LlmProvider {
    private val logger = LoggerFactory.getLogger(KeyRotatingOpenAiProvider::class.java)

    /**
     * Session-scoped key rotation manager.
     * Ensures the same API key is used throughout a user request/session,
     * only rotating on errors (401, 403, 429, network errors).
     */
    private val rotationManager = KeyRotationManager(apiKeys, baseProviderName)

    override val providerName: String
        get() {
            val invalidCount = kotlinx.coroutines.runBlocking { rotationManager.getInvalidKeys().size }
            return "$baseProviderName (Rotating ${apiKeys.size} keys, $invalidCount invalid)"
        }

    /**
     * Check if an error is a permanent failure that should not be retried.
     * Permanent failures include deserialization errors, missing fields, schema mismatches.
     * Retrying these with identical args will always fail.
     */
    private fun isPermanentFailure(e: Throwable): Boolean {
        val msg = e.message ?: ""
        return when {
            // Deserialization errors - missing required fields, invalid JSON structure
            msg.contains("MissingFieldException", ignoreCase = true) -> true
            msg.contains("missing", ignoreCase = true) && msg.contains("field", ignoreCase = true) -> true
            msg.contains("deserialization", ignoreCase = true) -> true
            msg.contains("JSON", ignoreCase = true) && (msg.contains("format", ignoreCase = true) || msg.contains("structure", ignoreCase = true)) -> true
            // Schema validation errors
            msg.contains("schema", ignoreCase = true) && msg.contains("validation", ignoreCase = true) -> true
            // Invalid argument errors that won't be fixed by retrying
            msg.contains("invalid", ignoreCase = true) && (msg.contains("argument", ignoreCase = true) || msg.contains("parameter", ignoreCase = true)) -> true
            else -> false
        }
    }

    /**
     * Exponential backoff with jitter for retry logic.
     *
     * **Algorithm**: baseDelay * 2^attempt + randomJitter
     * - Prevents thundering herd problem
     * - Adds randomness to avoid synchronization with other clients
     *
     * @param attempt Retry attempt number (0-based)
     * @param baseDelay Base delay in milliseconds (default: 500ms)
     * @param maxDelay Maximum delay cap (default: 5 seconds)
     * @param jitter Factor for random jitter (default: 0.1 = ±10%)
     */
    private suspend fun delayWithBackoff(
        attempt: Int,
        baseDelay: Long = 500L,
        maxDelay: Long = 5000L,
        jitter: Double = 0.1,
    ) {
        // Exponential backoff: baseDelay * 2^attempt
        val exponentialDelay = baseDelay * (1L shl attempt)
        val cappedDelay = minOf(exponentialDelay, maxDelay)

        // Add jitter to prevent synchronized retries
        val jitterRange = cappedDelay * jitter
        val jitteredDelay = cappedDelay + (Math.random() * jitterRange * 2 - jitterRange).toLong()

        kotlinx.coroutines.delay(jitteredDelay)
    }

    /**
     * Creates a provider instance with the specified API key.
     * Factory method for easy testing and key rotation.
     */
    private fun createProvider(apiKey: String): OpenAiCompatibleProvider {
        return OpenAiCompatibleProvider(
            client = client,
            providerName = baseProviderName,
            baseUrl = baseUrl,
            apiKey = apiKey,
            defaultModel = defaultModel,
        )
    }

    /**
     * Generates a complete response with session-scoped key rotation.
     *
     * **Key Rotation Strategy**:
     * 1. Uses the same API key for all calls within a session (session affinity)
     * 2. Only rotates on error (401, 403, 429, network errors)
     * 3. Retries with exponential backoff for transient errors
     * 4. Marks keys as invalid for permanent failures (401, 403)
     *
     * **Error Handling**:
     * - InvalidKey (401/403): Mark key invalid, rotate to next key
     * - RateLimited (429): Rotate to next key with backoff
     * - ServerError/NetworkError: Retry same key with backoff, then rotate
     *
     * @param messages LLM messages to send
     * @param tools Tool definitions for function calling
     * @param model Optional model override
     * @return LLM response with content and/or tool calls
     * @throws IllegalStateException if all API keys fail
     */
    override suspend fun generate(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        model: String?,
    ): LlmResponse {
        // Use a default session ID for generate calls (no session tracking needed for non-streaming)
        val sessionId = "generate-${System.currentTimeMillis()}"
        val sessionContext = rotationManager.createSessionContext(sessionId)

        var lastException: Exception? = null
        var attempt = 0
        val maxAttempts = apiKeys.size

        while (attempt < maxAttempts) {
            // Get current key index (session-affine, skips invalid keys)
            val keyIndex =
                sessionContext.getCurrentKeyIndex()
                    ?: throw IllegalStateException("[$baseProviderName] No valid API keys remaining")

            val apiKey = apiKeys[keyIndex]
            val provider = createProvider(apiKey)

            try {
                logger.debug(
                    "[$sessionId] Generate with key #$keyIndex for $baseProviderName (attempt ${attempt + 1}/$maxAttempts)",
                )

                return provider.generate(messages, tools, model)
            } catch (e: Exception) {
                // Bug 2 Fix: Check for permanent failures - don't retry these
                if (isPermanentFailure(e)) {
                    logger.warn(
                        "[$sessionId] Permanent failure detected for $baseProviderName: ${e.message?.take(200)}. " +
                            "Not retrying - schema/deserialization errors won't be fixed by retrying.",
                    )
                    throw e
                }

                lastException = e
                val error = ApiKeyErrorClassifier.classify(e)

                when (error) {
                    is ApiKeyError.InvalidKey -> {
                        // Permanent failure: mark key invalid, rotate
                        rotationManager.markKeyInvalid(keyIndex)
                        sessionContext.rotateToNextKey("InvalidKey", keyIndex)
                        logger.warn(
                            "[$sessionId] Key #$keyIndex INVALID for $baseProviderName: InvalidKey. " +
                                "Rotating to next key.",
                        )
                        attempt++
                    }

                    is ApiKeyError.RateLimited -> {
                        // Rate limited: rotate to next key with backoff
                        sessionContext.rotateToNextKey("RateLimited", keyIndex)
                        logger.warn(
                            "[$sessionId] Key #$keyIndex rate limited for $baseProviderName: RateLimited. " +
                                "Rotating and retrying with backoff...",
                        )
                        delayWithBackoff(attempt)
                        attempt++
                    }

                    is ApiKeyError.ServerError,
                    is ApiKeyError.NetworkError,
                    -> {
                        // Transient error: retry same key with backoff first
                        if (attempt < maxAttempts - 1) {
                            logger.warn(
                                "[$sessionId] Key #$keyIndex transient error for $baseProviderName: ${error::class.simpleName}. " +
                                    "Retrying with backoff (attempt ${attempt + 1}/$maxAttempts)...",
                            )
                            delayWithBackoff(attempt)
                            attempt++
                            // Continue loop with same key index (don't rotate yet)
                        } else {
                            // Max retries reached: rotate to next key
                            sessionContext.rotateToNextKey("${error::class.simpleName}", keyIndex)
                            logger.warn(
                                "[$sessionId] Key #$keyIndex failed after ${attempt + 1} retries for $baseProviderName: ${error::class.simpleName}. " +
                                    "Rotating to next key.",
                            )
                            attempt++
                        }
                    }

                    is ApiKeyError.UnknownError -> {
                        // Unknown error: fail fast, don't rotate
                        logger.error(
                            "[$sessionId] Unknown error for $baseProviderName with key #$keyIndex: ${error::class.simpleName}. " +
                                "Failing fast without rotation.",
                        )
                        throw e
                    }
                }
            }
        }

        throw lastException ?: IllegalStateException("[$baseProviderName] All API keys failed after $maxAttempts attempts")
    }

    /**
     * Streams a response with session-scoped key rotation.
     *
     * **Key Rotation Strategy**:
     * - Same as [generate], but maintains the key throughout the entire stream
     * - If stream fails mid-way, rotates to next key and restarts the stream
     * - Session affinity ensures consistent key usage across multiple stream calls
     *   (e.g., tool call iterations in agent loop)
     *
     * **Important**: The session ID should be consistent across all stream calls
     * within the same user request to maintain session affinity.
     *
     * @param messages LLM messages to send
     * @param tools Tool definitions for function calling
     * @param model Optional model override
     * @return Flow of LLM chunks
     * @throws IllegalStateException if all API keys fail
     */
    override suspend fun stream(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        model: String?,
    ): Flow<LlmChunk> =
        flow {
            // Extract or generate session ID from messages for session affinity
            // This ensures all stream calls within the same agent loop use the same key
            val sessionId = extractSessionId(messages) ?: "stream-${System.currentTimeMillis()}"
            val sessionContext = rotationManager.createSessionContext(sessionId)

            var lastException: Exception? = null
            var attempt = 0
            val maxAttempts = apiKeys.size

            while (attempt < maxAttempts) {
                // Get current key index (session-affine, skips invalid keys)
                val keyIndex =
                    sessionContext.getCurrentKeyIndex()
                        ?: throw IllegalStateException("[$baseProviderName] No valid API keys remaining")

                val apiKey = apiKeys[keyIndex]
                val provider = createProvider(apiKey)

                try {
                    logger.debug(
                        "[$sessionId] Stream with key #$keyIndex for $baseProviderName (attempt ${attempt + 1}/$maxAttempts)",
                    )

                    // Stream with this key
                    provider.stream(messages, tools, model).collect { chunk ->
                        emit(chunk)
                    }

                    // Stream completed successfully
                    return@flow
                } catch (e: Exception) {
                    // Bug 2 Fix: Check for permanent failures - don't retry these
                    if (isPermanentFailure(e)) {
                        logger.warn(
                            "[$sessionId] Permanent failure detected for $baseProviderName: ${e.message?.take(200)}. " +
                                "Not retrying - schema/deserialization errors won't be fixed by retrying.",
                        )
                        throw e
                    }

                    lastException = e
                    val error = ApiKeyErrorClassifier.classify(e)

                    when (error) {
                        is ApiKeyError.InvalidKey -> {
                            // Permanent failure: mark key invalid, rotate
                            rotationManager.markKeyInvalid(keyIndex)
                            sessionContext.rotateToNextKey("InvalidKey", keyIndex)
                            logger.warn(
                                "[$sessionId] Key #$keyIndex INVALID for $baseProviderName: InvalidKey. " +
                                    "Rotating to next key.",
                            )
                            attempt++
                        }

                        is ApiKeyError.RateLimited -> {
                            // Rate limited: rotate to next key with backoff
                            sessionContext.rotateToNextKey("RateLimited", keyIndex)
                            logger.warn(
                                "[$sessionId] Key #$keyIndex rate limited for $baseProviderName: RateLimited. " +
                                    "Rotating and retrying with backoff...",
                            )
                            delayWithBackoff(attempt)
                            attempt++
                        }

                        is ApiKeyError.ServerError,
                        is ApiKeyError.NetworkError,
                        -> {
                            // Transient error: retry same key with backoff first
                            if (attempt < maxAttempts - 1) {
                                logger.warn(
                                    "[$sessionId] Key #$keyIndex transient error for $baseProviderName: ${error::class.simpleName}. " +
                                        "Retrying with backoff (attempt ${attempt + 1}/$maxAttempts)...",
                                )
                                delayWithBackoff(attempt)
                                attempt++
                                // Continue loop with same key index (don't rotate yet)
                            } else {
                                // Max retries reached: rotate to next key
                                sessionContext.rotateToNextKey("${error::class.simpleName}", keyIndex)
                                logger.warn(
                                    "[$sessionId] Key #$keyIndex failed after ${attempt + 1} retries for $baseProviderName: ${error::class.simpleName}. " +
                                        "Rotating to next key.",
                                )
                                attempt++
                            }
                        }

                        is ApiKeyError.UnknownError -> {
                            // Unknown error: fail fast, don't rotate
                            logger.error(
                                "[$sessionId] Unknown error for $baseProviderName with key #$keyIndex: ${error::class.simpleName}. " +
                                    "Failing fast without rotation.",
                            )
                            throw e
                        }
                    }
                }
            }

            throw lastException ?: IllegalStateException("[$baseProviderName] All API keys failed after $maxAttempts attempts")
        }

    /**
     * Extracts session ID from messages for session affinity.
     *
     * **Strategy**: Uses a hash of the conversation context to derive a stable session ID
     * across multiple stream calls within the same agent loop iteration.
     *
     * **Why**: This ensures that tool call iterations (which add TOOL messages to the
     * conversation) still use the same API key, preventing mid-conversation key rotation.
     *
     * @param messages LLM messages
     * @return Session ID derived from conversation context, or null if extraction fails
     */
    private fun extractSessionId(messages: List<LlmMessage>): String? {
        // Use the first USER message as the session anchor
        // This provides stability across tool call iterations
        val userMessage = messages.find { it.role == LlmMessage.Role.USER }
        return userMessage?.content?.hashCode()?.toString()
    }

    /**
     * Cleans up session state after request completion.
     * Call this when a user request is fully processed to free memory.
     *
     * @param sessionId Session ID to clean up
     */
    fun cleanupSession(sessionId: String) {
        rotationManager.removeSessionContext(sessionId)
    }
}

class KeyRotatingGeminiProvider(
    private val client: HttpClient,
    private val apiKeys: List<String>,
) : LlmProvider {
    private val logger = LoggerFactory.getLogger(KeyRotatingGeminiProvider::class.java)

    /**
     * Session-scoped key rotation manager.
     * Ensures the same API key is used throughout a user request/session,
     * only rotating on errors (401, 403, 429, network errors).
     */
    private val rotationManager = KeyRotationManager(apiKeys, "Gemini")

    override val providerName: String
        get() {
            val invalidCount = kotlinx.coroutines.runBlocking { rotationManager.getInvalidKeys().size }
            return "Gemini (Rotating ${apiKeys.size} keys, $invalidCount invalid)"
        }

    /**
     * Check if an error is a permanent failure that should not be retried.
     * Permanent failures include deserialization errors, missing fields, schema mismatches.
     * Retrying these with identical args will always fail.
     */
    private fun isPermanentFailure(e: Throwable): Boolean {
        val msg = e.message ?: ""
        return when {
            msg.contains("MissingFieldException", ignoreCase = true) -> true
            msg.contains("missing", ignoreCase = true) && msg.contains("field", ignoreCase = true) -> true
            msg.contains("deserialization", ignoreCase = true) -> true
            msg.contains("JSON", ignoreCase = true) && (msg.contains("format", ignoreCase = true) || msg.contains("structure", ignoreCase = true)) -> true
            msg.contains("schema", ignoreCase = true) && msg.contains("validation", ignoreCase = true) -> true
            msg.contains("invalid", ignoreCase = true) && (msg.contains("argument", ignoreCase = true) || msg.contains("parameter", ignoreCase = true)) -> true
            else -> false
        }
    }

    /**
     * Exponential backoff with jitter for retry logic.
     *
     * @param attempt Retry attempt number (0-based)
     * @param baseDelay Base delay in milliseconds (default: 500ms)
     * @param maxDelay Maximum delay cap (default: 5 seconds)
     * @param jitter Factor for random jitter (default: 0.1 = ±10%)
     */
    private suspend fun delayWithBackoff(
        attempt: Int,
        baseDelay: Long = 500L,
        maxDelay: Long = 5000L,
        jitter: Double = 0.1,
    ) {
        val exponentialDelay = baseDelay * (1L shl attempt)
        val cappedDelay = minOf(exponentialDelay, maxDelay)
        val jitterRange = cappedDelay * jitter
        val jitteredDelay = cappedDelay + (Math.random() * jitterRange * 2 - jitterRange).toLong()
        kotlinx.coroutines.delay(jitteredDelay)
    }

    /**
     * Creates a provider instance with the specified API key.
     */
    private fun createProvider(apiKey: String): GeminiProvider {
        return GeminiProvider(client = client, apiKey = apiKey)
    }

    /**
     * Generates a complete response with session-scoped key rotation.
     *
     * **Key Rotation Strategy**:
     * 1. Uses the same API key for all calls within a session (session affinity)
     * 2. Only rotates on error (401, 403, 429, network errors)
     * 3. Retries with exponential backoff for transient errors
     * 4. Marks keys as invalid for permanent failures (401, 403)
     *
     * @param messages LLM messages to send
     * @param tools Tool definitions for function calling
     * @param model Optional model override
     * @return LLM response with content and/or tool calls
     * @throws IllegalStateException if all API keys fail
     */
    override suspend fun generate(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        model: String?,
    ): LlmResponse {
        val sessionId = "generate-${System.currentTimeMillis()}"
        val sessionContext = rotationManager.createSessionContext(sessionId)

        var lastException: Exception? = null
        var attempt = 0
        val maxAttempts = apiKeys.size

        while (attempt < maxAttempts) {
            val keyIndex =
                sessionContext.getCurrentKeyIndex()
                    ?: throw IllegalStateException("[Gemini] No valid API keys remaining")

            val apiKey = apiKeys[keyIndex]
            val provider = createProvider(apiKey)

            try {
                logger.debug(
                    "[$sessionId] Generate with key #$keyIndex for Gemini (attempt ${attempt + 1}/$maxAttempts)",
                )

                return provider.generate(messages, tools, model)
            } catch (e: Exception) {
                // Bug 2 Fix: Check for permanent failures - don't retry these
                if (isPermanentFailure(e)) {
                    logger.warn(
                        "[$sessionId] Permanent failure detected for Gemini: ${e.message?.take(200)}. " +
                            "Not retrying - schema/deserialization errors won't be fixed by retrying.",
                    )
                    throw e
                }

                lastException = e
                val error = ApiKeyErrorClassifier.classify(e)

                when (error) {
                    is ApiKeyError.InvalidKey -> {
                        rotationManager.markKeyInvalid(keyIndex)
                        sessionContext.rotateToNextKey("InvalidKey", keyIndex)
                        logger.warn(
                            "[$sessionId] Key #$keyIndex INVALID for Gemini: InvalidKey. " +
                                "Rotating to next key.",
                        )
                        attempt++
                    }

                    is ApiKeyError.RateLimited -> {
                        sessionContext.rotateToNextKey("RateLimited", keyIndex)
                        logger.warn(
                            "[$sessionId] Key #$keyIndex rate limited for Gemini: RateLimited. " +
                                "Rotating and retrying with backoff...",
                        )
                        delayWithBackoff(attempt)
                        attempt++
                    }

                    is ApiKeyError.ServerError,
                    is ApiKeyError.NetworkError,
                    -> {
                        if (attempt < maxAttempts - 1) {
                            logger.warn(
                                "[$sessionId] Key #$keyIndex transient error for Gemini: ${error::class.simpleName}. " +
                                    "Retrying with backoff (attempt ${attempt + 1}/$maxAttempts)...",
                            )
                            delayWithBackoff(attempt)
                            attempt++
                        } else {
                            sessionContext.rotateToNextKey("${error::class.simpleName}", keyIndex)
                            logger.warn(
                                "[$sessionId] Key #$keyIndex failed after ${attempt + 1} retries for Gemini: ${error::class.simpleName}. " +
                                    "Rotating to next key.",
                            )
                            attempt++
                        }
                    }

                    is ApiKeyError.UnknownError -> {
                        logger.error(
                            "[$sessionId] Unknown error for Gemini with key #$keyIndex: ${error::class.simpleName}. " +
                                "Failing fast without rotation.",
                        )
                        throw e
                    }
                }
            }
        }

        throw lastException ?: IllegalStateException("[Gemini] All API keys failed after $maxAttempts attempts")
    }

    /**
     * Streams a response with session-scoped key rotation.
     *
     * **Key Rotation Strategy**:
     * - Maintains the same API key throughout the entire stream
     * - Session affinity ensures consistent key usage across multiple stream calls
     * - Only rotates on errors (401, 403, 429, network errors)
     *
     * @param messages LLM messages to send
     * @param tools Tool definitions for function calling
     * @param model Optional model override
     * @return Flow of LLM chunks
     * @throws IllegalStateException if all API keys fail
     */
    override suspend fun stream(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        model: String?,
    ): Flow<LlmChunk> =
        flow {
            val sessionId = extractSessionId(messages) ?: "stream-${System.currentTimeMillis()}"
            val sessionContext = rotationManager.createSessionContext(sessionId)

            var lastException: Exception? = null
            var attempt = 0
            val maxAttempts = apiKeys.size

            while (attempt < maxAttempts) {
                val keyIndex =
                    sessionContext.getCurrentKeyIndex()
                        ?: throw IllegalStateException("[Gemini] No valid API keys remaining")

                val apiKey = apiKeys[keyIndex]
                val provider = createProvider(apiKey)

                try {
                    logger.debug(
                        "[$sessionId] Stream with key #$keyIndex for Gemini (attempt ${attempt + 1}/$maxAttempts)",
                    )

                    provider.stream(messages, tools, model).collect { chunk ->
                        emit(chunk)
                    }

                    return@flow
                } catch (e: Exception) {
                    // Bug 2 Fix: Check for permanent failures - don't retry these
                    if (isPermanentFailure(e)) {
                        logger.warn(
                            "[$sessionId] Permanent failure detected for Gemini: ${e.message?.take(200)}. " +
                                "Not retrying - schema/deserialization errors won't be fixed by retrying.",
                        )
                        throw e
                    }

                    lastException = e
                    val error = ApiKeyErrorClassifier.classify(e)

                    when (error) {
                        is ApiKeyError.InvalidKey -> {
                            rotationManager.markKeyInvalid(keyIndex)
                            sessionContext.rotateToNextKey("InvalidKey", keyIndex)
                            logger.warn(
                                "[$sessionId] Key #$keyIndex INVALID for Gemini: InvalidKey. " +
                                    "Rotating to next key.",
                            )
                            attempt++
                        }

                        is ApiKeyError.RateLimited -> {
                            sessionContext.rotateToNextKey("RateLimited", keyIndex)
                            logger.warn(
                                "[$sessionId] Key #$keyIndex rate limited for Gemini: RateLimited. " +
                                    "Rotating and retrying with backoff...",
                            )
                            delayWithBackoff(attempt)
                            attempt++
                        }

                        is ApiKeyError.ServerError,
                        is ApiKeyError.NetworkError,
                        -> {
                            if (attempt < maxAttempts - 1) {
                                logger.warn(
                                    "[$sessionId] Key #$keyIndex transient error for Gemini: ${error::class.simpleName}. " +
                                        "Retrying with backoff (attempt ${attempt + 1}/$maxAttempts)...",
                                )
                                delayWithBackoff(attempt)
                                attempt++
                            } else {
                                sessionContext.rotateToNextKey("${error::class.simpleName}", keyIndex)
                                logger.warn(
                                    "[$sessionId] Key #$keyIndex failed after ${attempt + 1} retries for Gemini: ${error::class.simpleName}. " +
                                        "Rotating to next key.",
                                )
                                attempt++
                            }
                        }

                        is ApiKeyError.UnknownError -> {
                            logger.error(
                                "[$sessionId] Unknown error for Gemini with key #$keyIndex: ${error::class.simpleName}. " +
                                    "Failing fast without rotation.",
                            )
                            throw e
                        }
                    }
                }
            }

            throw lastException ?: IllegalStateException("[Gemini] All API keys failed after $maxAttempts attempts")
        }

    /**
     * Extracts session ID from messages for session affinity.
     * Uses the first USER message as the session anchor.
     */
    private fun extractSessionId(messages: List<LlmMessage>): String? {
        val userMessage = messages.find { it.role == LlmMessage.Role.USER }
        return userMessage?.content?.hashCode()?.toString()
    }

    /**
     * Cleans up session state after request completion.
     */
    fun cleanupSession(sessionId: String) {
        rotationManager.removeSessionContext(sessionId)
    }
}
