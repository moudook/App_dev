package com.example.smarty.util.api

/**
 * API Key rotation and management utility.
 * Implements the dual-key architecture:
 * - Key 1 (first key): Reserved for AI Agent operations
 * - Keys 2, 3, 4...: Used for normal operations (note analysis, etc.)
 *
 * This eliminates duplicate key cycling logic across the codebase.
 */
object ApiKeyRotator {

    /**
     * Get keys rotated for normal operations (note analysis, document processing).
     * Prioritizes keys 2, 3, 4... first, then falls back to key 1 (agent key).
     *
     * Use case: analyzeContent(), analyzeDocument()
     *
     * @param allKeys All available API keys for a provider
     * @return Keys ordered: [key2, key3, key4..., key1] for fallback
     */
    fun getRotatedKeysWithAgentFallback(allKeys: List<String>): List<String> {
        return if (allKeys.size > 1) {
            // Prioritize non-agent keys, then agent key as last fallback
            allKeys.drop(1) + listOf(allKeys.first())
        } else {
            allKeys // Only one key available, must use it
        }
    }

    /**
     * Get only normal keys (excludes agent key).
     * Used for normal chat operations that should NEVER use the agent key.
     *
     * Use case: agentChat() (normal conversation, not agent loop)
     *
     * @param allKeys All available API keys for a provider
     * @return Keys 2, 3, 4... (excludes key 1 if multiple keys exist)
     */
    fun getNormalKeysOnly(allKeys: List<String>): List<String> {
        return if (allKeys.size > 1) {
            allKeys.drop(1) // Skip first key (agent key), use all others
        } else {
            allKeys // Only 1 key exists, must share with agent
        }
    }

    /**
     * Get the dedicated agent key (key 1).
     * Reserved exclusively for AI Agent reasoning and tool execution.
     *
     * Use case: agentReasoning(), agentFinalResponse()
     *
     * @param allKeys All available API keys for a provider
     * @return The first API key (dedicated agent key)
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
     * Check if a key is the agent key (first key).
     *
     * @param allKeys All available API keys for a provider
     * @param key The key to check
     * @return True if this is the agent key (key 1)
     */
    fun isAgentKey(allKeys: List<String>, key: String): Boolean {
        return allKeys.isNotEmpty() && allKeys.first() == key && allKeys.size > 1
    }

    /**
     * Get descriptive label for a key (for logging).
     *
     * @param allKeys All available API keys for a provider
     * @param key The key to label
     * @return Human-readable label like "key-1 (agent)" or "key-2"
     */
    fun getKeyLabel(allKeys: List<String>, key: String): String {
        val index = getKeyIndex(allKeys, key)
        return when {
            index == 1 && allKeys.size > 1 -> "key-1 (agent)"
            index > 0 -> "key-$index"
            else -> "unknown-key"
        }
    }
}
