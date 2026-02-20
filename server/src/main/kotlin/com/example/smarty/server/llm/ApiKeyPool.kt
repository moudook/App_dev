package com.example.smarty.server.llm

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class KeyStats(
    val keyId: String,
    var requestsCount: Long = 0,
    var successCount: Long = 0,
    var failureCount: Long = 0,
    var lastUsed: Long = 0,
    var lastError: String? = null,
    var rateLimitedUntil: Long = 0
)

data class KeyAssignment(
    val keyId: String,
    val apiKey: String,
    val agentId: String? = null
)

class ApiKeyPool(
    val poolName: String,
    apiKeys: List<String>,
    private val strategy: RotationStrategy = RotationStrategy.LEAST_USED
) {
    private val logger = LoggerFactory.getLogger(ApiKeyPool::class.java)

    enum class RotationStrategy {
        ROUND_ROBIN,
        LEAST_USED,
        RANDOM,
        DEDICATED
    }

    private val keys: List<String> = apiKeys.filter { it.isNotBlank() }
    private val keyStats = ConcurrentHashMap<String, KeyStats>()
    private val roundRobinIndex = AtomicInteger(0)
    private val dedicatedAssignments = ConcurrentHashMap<String, String>()

    init {
        keys.forEachIndexed { index, key ->
            keyStats[key] = KeyStats(keyId = "key_$index")
        }
        logger.info("ApiKeyPool '$poolName' initialized with ${keys.size} keys, strategy: $strategy")
    }

    val size: Int get() = keys.size
    val hasKeys: Boolean get() = keys.isNotEmpty()

    fun getKeyForAgent(agentId: String): KeyAssignment? {
        if (keys.isEmpty()) return null

        val existingKey = dedicatedAssignments[agentId]
        if (existingKey != null) {
            return KeyAssignment(
                keyId = keyStats[existingKey]?.keyId ?: "unknown",
                apiKey = existingKey,
                agentId = agentId
            )
        }

        val selectedKey = selectKey()
        if (selectedKey != null) {
            dedicatedAssignments[agentId] = selectedKey
            logger.info("Assigned key ${keyStats[selectedKey]?.keyId} to agent $agentId")
        }

        return selectedKey?.let {
            KeyAssignment(
                keyId = keyStats[it]?.keyId ?: "unknown",
                apiKey = it,
                agentId = agentId
            )
        }
    }

    fun releaseAgentKey(agentId: String) {
        val key = dedicatedAssignments.remove(agentId)
        if (key != null) {
            logger.info("Released key ${keyStats[key]?.keyId} from agent $agentId")
        }
    }

    fun getNextKey(): KeyAssignment? {
        if (keys.isEmpty()) return null
        val key = selectKey() ?: return null
        return KeyAssignment(
            keyId = keyStats[key]?.keyId ?: "unknown",
            apiKey = key
        )
    }

    fun reportSuccess(key: String) {
        keyStats[key]?.let { stats ->
            stats.requestsCount++
            stats.successCount++
            stats.lastUsed = System.currentTimeMillis()
        }
    }

    fun reportFailure(key: String, error: String? = null, isRateLimit: Boolean = false) {
        keyStats[key]?.let { stats ->
            stats.requestsCount++
            stats.failureCount++
            stats.lastUsed = System.currentTimeMillis()
            stats.lastError = error
            if (isRateLimit) {
                stats.rateLimitedUntil = System.currentTimeMillis() + 60000
            }
        }
    }

    fun getAvailableKeyCount(): Int {
        val now = System.currentTimeMillis()
        return keys.count { key ->
            keyStats[key]?.rateLimitedUntil?.let { it < now } ?: true
        }
    }

    fun getStats(): Map<String, KeyStats> = keyStats.toMap()

    private fun selectKey(): String? {
        if (keys.isEmpty()) return null

        val now = System.currentTimeMillis()
        val availableKeys = keys.filter { key ->
            keyStats[key]?.rateLimitedUntil?.let { it < now } ?: true
        }

        if (availableKeys.isEmpty()) {
            logger.warn("All keys in pool '$poolName' are rate limited, using least recent anyway")
            return keys.minByOrNull { keyStats[it]?.lastUsed ?: 0 }
        }

        return when (strategy) {
            RotationStrategy.ROUND_ROBIN -> {
                val index = Math.abs(roundRobinIndex.getAndIncrement() % availableKeys.size)
                availableKeys[index]
            }
            RotationStrategy.LEAST_USED -> {
                availableKeys.minByOrNull { key ->
                    keyStats[key]?.requestsCount ?: 0
                }
            }
            RotationStrategy.RANDOM -> {
                availableKeys.randomOrNull()
            }
            RotationStrategy.DEDICATED -> {
                availableKeys.firstOrNull()
            }
        }
    }

    fun formatStats(): String {
        return buildString {
            appendLine("[ApiKeyPool: $poolName]")
            appendLine("Strategy: $strategy")
            appendLine("Total Keys: ${keys.size}")
            appendLine("Available: ${getAvailableKeyCount()}")
            appendLine("-".repeat(50))
            keyStats.entries.sortedBy { it.key }.forEach { (key, stats) ->
                val maskedKey = key.take(8) + "..." + key.takeLast(4)
                appendLine("${stats.keyId} ($maskedKey):")
                appendLine("  Requests: ${stats.requestsCount} | Success: ${stats.successCount} | Failures: ${stats.failureCount}")
                if (stats.lastError != null) appendLine("  Last Error: ${stats.lastError}")
                if (stats.rateLimitedUntil > System.currentTimeMillis()) {
                    val remaining = (stats.rateLimitedUntil - System.currentTimeMillis()) / 1000
                    appendLine("  Rate Limited: ${remaining}s remaining")
                }
            }
        }
    }
}

object ApiKeyPoolManager {
    private val pools = ConcurrentHashMap<String, ApiKeyPool>()
    private val logger = LoggerFactory.getLogger(ApiKeyPoolManager::class.java)

    fun createPool(
        poolName: String,
        apiKeys: List<String>,
        strategy: ApiKeyPool.RotationStrategy = ApiKeyPool.RotationStrategy.LEAST_USED
    ): ApiKeyPool {
        val pool = ApiKeyPool(poolName, apiKeys, strategy)
        pools[poolName] = pool
        logger.info("Created API key pool '$poolName' with ${apiKeys.size} keys")
        return pool
    }

    fun createPoolFromEnv(
        poolName: String,
        envVarName: String,
        strategy: ApiKeyPool.RotationStrategy = ApiKeyPool.RotationStrategy.LEAST_USED
    ): ApiKeyPool {
        val keys = System.getenv(envVarName)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        return createPool(poolName, keys, strategy)
    }

    fun getPool(poolName: String): ApiKeyPool? = pools[poolName]

    fun getOrCreatePool(
        poolName: String,
        apiKeys: List<String>,
        strategy: ApiKeyPool.RotationStrategy = ApiKeyPool.RotationStrategy.LEAST_USED
    ): ApiKeyPool {
        return pools.getOrPut(poolName) { ApiKeyPool(poolName, apiKeys, strategy) }
    }

    fun getAllPools(): Map<String, ApiKeyPool> = pools.toMap()

    fun formatAllStats(): String {
        return buildString {
            appendLine("=".repeat(60))
            appendLine("API KEY POOL STATUS")
            appendLine("=".repeat(60))
            pools.forEach { (name, pool) ->
                appendLine(pool.formatStats())
                appendLine()
            }
        }
    }
}
