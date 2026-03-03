package com.example.smarty.server.llm

import io.ktor.client.HttpClient
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Strategy for selecting an LLM provider based on high-level goals.
 */
enum class RoutingStrategy {
    CHEAPEST,       // Prioritize lowest cost
    FASTEST,        // Prioritize lowest latency
    SMARTEST,       // Prioritize reasoning capability
    BALANCED        // Mix of cost and performance
}

/**
 * Specific capabilities required for a task.
 */
enum class Capability {
    FAST_CHAT,              // Low latency, standard reasoning (gemini-3-flash)
    REASONING,              // Complex logic, deep thinking (gemini-3-pro-high)
    VISION_UNDERSTANDING,   // Image analysis, OCR (gemini-3-pro-high)
    IMAGE_GENERATION        // Creating images (gemini-3-image)
}

/**
 * Router that selects the best LLM provider/model based on capability and health.
 * Centralizes all model selection logic ("The Brain").
 */
class ProviderRouter(private val httpClient: HttpClient) {
    private val logger = LoggerFactory.getLogger(ProviderRouter::class.java)

    // Model mapping for capabilities (Gemini Production Stack)
    private val capabilityMap = mapOf(
        Capability.FAST_CHAT to "gemini-1.5-flash",
        Capability.REASONING to "gemini-1.5-pro",
        Capability.VISION_UNDERSTANDING to "gemini-1.5-flash",
        Capability.IMAGE_GENERATION to "imagen-3.0-generate-001" // Placeholder or actual model
    )

    // Health tracking (simple circuit breaker)
    private val failureCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val lastFailureTime = ConcurrentHashMap<String, AtomicLong>()
    private val COOL_DOWN_MS = 60_000L

    /**
     * Get the specific model name for a required capability.
     */
    fun getModelForCapability(capability: Capability): String {
        // Allow environment variable to override all model selections
        System.getenv("LLM_MODEL_ID")?.takeIf { it.isNotBlank() }?.let {
            return it
        }
        return capabilityMap[capability] ?: "gemini-1.5-flash"
    }

    /**
     * Get an LLM provider instance configured for the requested strategy.
     */
    fun selectProvider(strategy: RoutingStrategy, apiKey: String? = null): LlmProvider {
        return LlmProviderFactory.create(httpClient, "GEMINI", apiKeyOverride = apiKey)
    }

    /**
     * Get a provider specifically for a capability.
     */
    fun getProviderForCapability(capability: Capability, apiKey: String? = null): LlmProvider {
        return LlmProviderFactory.create(httpClient, "GEMINI", apiKeyOverride = apiKey)
    }

    fun reportFailure(providerName: String) {
        val count = failureCounts.computeIfAbsent(providerName) { AtomicInteger(0) }.incrementAndGet()
        lastFailureTime.computeIfAbsent(providerName) { AtomicLong(0) }.set(System.currentTimeMillis())
        logger.warn("Reported failure for $providerName. Count: $count")
    }

    fun isHealthy(providerName: String): Boolean {
        val count = failureCounts[providerName]?.get() ?: 0
        if (count < 3) return true

        val lastTime = lastFailureTime[providerName]?.get() ?: 0L
        if (System.currentTimeMillis() - lastTime > COOL_DOWN_MS) {
            failureCounts[providerName]?.set(0)
            return true
        }
        return false
    }
}
