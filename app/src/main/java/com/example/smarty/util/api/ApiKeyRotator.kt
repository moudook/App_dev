package com.example.smarty.util.api

/**
 * API Key rotation and management utility.
 *
 * KEY SEPARATION ARCHITECTURE:
 * ┌─────────────────────────────────────────────────────────────┐
 * │  Key 1        │  AI Agent (Chat, Tool Execution)           │
 * │  Key 2        │  Note Card Analysis (Background)           │
 * │  Keys 3,4,5...│  Other Operations (Research, Web Search)   │
 * └─────────────────────────────────────────────────────────────┘
 *
 * This separation ensures:
 * - Agent operations never exhaust note processing quota
 * - Each feature has dedicated API budget
 * - Better rate limit distribution
 *
 * If only 1 key exists: shared across all operations
 * If only 2 keys exist: Key 1 = Agent, Key 2 = Everything else
 */
object ApiKeyRotator {

    // Key index assignments (0-based internally, 1-based for users)
    private const val AGENT_KEY_INDEX = 0        // Key 1: AI Agent
    private const val NOTE_ANALYSIS_KEY_INDEX = 1 // Key 2: Note Analysis
    private const val OTHER_KEYS_START_INDEX = 2  // Keys 3+: Other operations

    // Number of keys reserved for agent operations (legacy compatibility)
    private const val AGENT_KEY_COUNT = 2

    // Track which keys are currently in use
    private val busyKeys = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val BUSY_TIMEOUT_MS = 30_000L // 30 seconds

    /**
     * Mark a key as busy (currently in use)
     */
    fun markKeyBusy(key: String) {
        busyKeys[key] = System.currentTimeMillis()
    }

    /**
     * Mark a key as available (call completed)
     */
    fun markKeyAvailable(key: String) {
        busyKeys.remove(key)
    }

    /**
     * Check if a key is currently busy
     */
    fun isKeyBusy(key: String): Boolean {
        val busyTime = busyKeys[key] ?: return false
        // Auto-clear stale busy markers
        if (System.currentTimeMillis() - busyTime > BUSY_TIMEOUT_MS) {
            busyKeys.remove(key)
            return false
        }
        return true
    }

    /**
     * Get the next available key (not busy)
     */
    fun getNextAvailableKey(keys: List<String>): String? {
        // First, try to find a non-busy key
        for (key in keys) {
            if (!isKeyBusy(key)) {
                return key
            }
        }
        // If all are busy, return the first one (it will wait)
        return keys.firstOrNull()
    }

    /**
     * Get rotated keys prioritizing non-busy ones
     */
    fun getRotatedKeysWithBusyAwareness(keys: List<String>): List<String> {
        if (keys.size <= 1) return keys

        // Separate busy and available keys
        val available = keys.filter { !isKeyBusy(it) }
        val busy = keys.filter { isKeyBusy(it) }

        // Available keys first, then busy ones as fallback
        return available + busy
    }

    // ==================== FEATURE-SPECIFIC KEY GETTERS ====================

    /**
     * Get the dedicated AI Agent key (Key 1).
     * Used for: Chat, reasoning, tool execution
     *
     * @param allKeys All available API keys for a provider
     * @return Key 1 (or null if no keys)
     */
    fun getAgentKey(allKeys: List<String>): String? {
        return allKeys.getOrNull(AGENT_KEY_INDEX)
    }

    /**
     * Get the dedicated Note Analysis key (Key 2).
     * Used for: Note card analysis, categorization, summarization
     *
     * Falls back to Key 1 if only 1 key exists.
     *
     * @param allKeys All available API keys for a provider
     * @return Key 2 (or Key 1 if only 1 key exists)
     */
    fun getNoteAnalysisKey(allKeys: List<String>): String? {
        return when {
            allKeys.size >= 2 -> allKeys[NOTE_ANALYSIS_KEY_INDEX]  // Key 2
            allKeys.isNotEmpty() -> allKeys[0]  // Fallback to Key 1
            else -> null
        }
    }

    /**
     * Get keys for other operations (Keys 3, 4, 5...).
     * Used for: Research, web search, batch operations
     *
     * Falls back to Key 2, then Key 1 if not enough keys.
     *
     * @param allKeys All available API keys for a provider
     * @return Keys 3+ (or fallback keys)
     */
    fun getOtherOperationKeys(allKeys: List<String>): List<String> {
        return when {
            allKeys.size > OTHER_KEYS_START_INDEX -> allKeys.drop(OTHER_KEYS_START_INDEX)  // Keys 3+
            allKeys.size >= 2 -> listOf(allKeys[1])  // Fallback to Key 2
            allKeys.isNotEmpty() -> listOf(allKeys[0])  // Fallback to Key 1
            else -> emptyList()
        }
    }

    // ==================== LEGACY METHODS (for backward compatibility) ====================

    /**
     * Get keys for background operations (note analysis, document processing).
     * Uses Key 2 primarily, falls back appropriately.
     *
     * Use case: analyzeContent(), analyzeDocument()
     *
     * @param allKeys All available API keys for a provider
     * @return Keys for note analysis (Key 2 preferred)
     */
    fun getRotatedKeysWithAgentFallback(allKeys: List<String>): List<String> {
        // For note analysis, use Key 2 (NOTE_ANALYSIS_KEY_INDEX)
        return when {
            allKeys.size >= 2 -> listOf(allKeys[NOTE_ANALYSIS_KEY_INDEX])  // Key 2 only
            allKeys.isNotEmpty() -> listOf(allKeys[0])  // Fallback to Key 1
            else -> emptyList()
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
     * Get agent keys (Key 1 only for dedicated agent usage).
     * For backward compatibility, returns list with single key.
     *
     * @param allKeys All available API keys for a provider
     * @return List containing Key 1 only
     */
    fun getAgentKeys(allKeys: List<String>): List<String> {
        return listOfNotNull(getAgentKey(allKeys))
    }

    /**
     * Check if provider has only one key (must be shared between all operations).
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
     * Check if a key is the agent key (key 1).
     *
     * @param allKeys All available API keys for a provider
     * @param key The key to check
     * @return True if this is the agent key (key 1)
     */
    fun isAgentKey(allKeys: List<String>, key: String): Boolean {
        return allKeys.indexOf(key) == AGENT_KEY_INDEX && allKeys.size > 1
    }

    /**
     * Get descriptive label for a key (for logging).
     *
     * @param allKeys All available API keys for a provider
     * @param key The key to label
     * @return Human-readable label like "KEY-1 (agent)" or "KEY-2 (notes)"
     */
    fun getKeyLabel(allKeys: List<String>, key: String): String {
        val index = getKeyIndex(allKeys, key)
        return when {
            allKeys.size == 1 -> "KEY-1 (shared)"
            index == 1 -> "KEY-1 (agent)"
            index == 2 -> "KEY-2 (notes)"
            index > 2 -> "KEY-$index (other)"
            else -> "unknown-key"
        }
    }
}
