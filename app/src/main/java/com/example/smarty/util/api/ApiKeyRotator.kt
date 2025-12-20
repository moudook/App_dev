package com.example.smarty.util.api

/**
 * API Key rotation and management utility.
 * Implements the key separation architecture:
 * - Keys 1, 2: Reserved for AI Agent operations (chat, reasoning)
 * - Keys 3, 4, 5...: Used for background operations (note analysis, summarization)
 *
 * This eliminates duplicate key cycling logic across the codebase.
 */
object ApiKeyRotator {

    // Number of keys reserved for agent operations
    private const val AGENT_KEY_COUNT = 2

    /**
     * Get keys for background operations (note analysis, document processing).
     * Uses Keys 3, 4, 5... only - never touches agent keys (1, 2).
     *
     * Use case: analyzeContent(), analyzeDocument()
     *
     * @param allKeys All available API keys for a provider
     * @return Keys 3, 4, 5... (background keys only)
     */
    fun getRotatedKeysWithAgentFallback(allKeys: List<String>): List<String> {
        return if (allKeys.size > AGENT_KEY_COUNT) {
            // Use keys 3, 4, 5... for background operations
            allKeys.drop(AGENT_KEY_COUNT)
        } else if (allKeys.size > 1) {
            // Only 2 keys exist - use key 2 for background (agent uses key 1)
            listOf(allKeys[1])
        } else {
            allKeys // Only 1 key exists, must share
        }
    }

    /**
     * Get only background keys (excludes agent keys 1 and 2).
     * Used for background operations that should NEVER use agent keys.
     *
     * Use case: Content analysis, summarization, document processing
     *
     * @param allKeys All available API keys for a provider
     * @return Keys 3, 4, 5... (excludes keys 1 and 2)
     */
    fun getNormalKeysOnly(allKeys: List<String>): List<String> {
        return if (allKeys.size > AGENT_KEY_COUNT) {
            // Keys 3, 4, 5... for background operations
            allKeys.drop(AGENT_KEY_COUNT)
        } else if (allKeys.size > 1) {
            // Only 2 keys - use key 2 for background
            listOf(allKeys[1])
        } else {
            allKeys // Only 1 key exists, must share with agent
        }
    }

    /**
     * Get the dedicated agent keys (keys 1 and 2).
     * Reserved exclusively for AI Agent reasoning and tool execution.
     *
     * Use case: Agent chat, reasoning, tool execution
     *
     * @param allKeys All available API keys for a provider
     * @return Keys 1 and 2 (agent keys)
     */
    fun getAgentKeys(allKeys: List<String>): List<String> {
        return allKeys.take(AGENT_KEY_COUNT)
    }

    /**
     * Get the primary agent key (key 1).
     *
     * @param allKeys All available API keys for a provider
     * @return The first API key (primary agent key)
     */
    fun getAgentKey(allKeys: List<String>): String? {
        return allKeys.firstOrNull()
    }

    /**
     * Check if provider has only one key (must be shared between agent and normal).
     *
     * @param allKeys All available API keys for a provider
     * @return True if only one key exists (shared mode)
     */
    fun isSharedKeyMode(allKeys: List<String>): Boolean {
        return allKeys.size == 1
    }

    /**
     * Get the original index of a key in the full key list.
     * Useful for logging which key is being used.
     *
     * @param allKeys All available API keys for a provider
     * @param key The key to find
     * @return 1-based index of the key, or -1 if not found
     */
    fun getKeyIndex(allKeys: List<String>, key: String): Int {
        val index = allKeys.indexOf(key)
        return if (index >= 0) index + 1 else -1
    }

    /**
     * Check if a key is an agent key (key 1 or key 2).
     *
     * @param allKeys All available API keys for a provider
     * @param key The key to check
     * @return True if this is an agent key (key 1 or 2)
     */
    fun isAgentKey(allKeys: List<String>, key: String): Boolean {
        val index = allKeys.indexOf(key)
        return index >= 0 && index < AGENT_KEY_COUNT && allKeys.size > 1
    }

    /**
     * Get descriptive label for a key (for logging).
     *
     * @param allKeys All available API keys for a provider
     * @param key The key to label
     * @return Human-readable label like "KEY-1 (agent)" or "KEY-3 (background)"
     */
    fun getKeyLabel(allKeys: List<String>, key: String): String {
        val index = getKeyIndex(allKeys, key)
        return when {
            index in 1..AGENT_KEY_COUNT && allKeys.size > AGENT_KEY_COUNT -> "KEY-$index (agent)"
            index > AGENT_KEY_COUNT -> "KEY-$index (background)"
            index > 0 -> "KEY-$index"
            else -> "unknown-key"
        }
    }
}
