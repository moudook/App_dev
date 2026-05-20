package com.example.smarty.server.config

/**
 * Centralized Application Configuration.
 *
 * OpenCode CLI is the ONLY LLM provider — no API keys required.
 * All model inference routes through `opencode run` with free Zen models.
 *
 * Single Responsibility: Only handles configuration management.
 * DRY: Replaces scattered System.getenv() calls across files.
 * Global State: Single source of truth for all configuration.
 */
object AppConfig {
    // Database Configuration
    val dbUrl: String? = System.getenv("DB_URL")
    val dbUser: String? = System.getenv("DB_USER")
    val dbPassword: String? = System.getenv("DB_PASSWORD")
    val dbDriver: String = System.getenv("DB_DRIVER") ?: "org.postgresql.Driver"

    // Optional API keys (only for tools that still need external services)
    // Krea is optional for image generation
    val kreaApiKey: String? = System.getenv("KREA_API_KEY")?.trim()?.ifBlank { null }

    // Firebase Configuration
    val fcmServerKey: String? = System.getenv("FCM_SERVER_KEY")
    val fcmProjectId: String? = System.getenv("FCM_PROJECT_ID")

    // Server Configuration
    val serverPort: Int = System.getenv("SERVER_PORT")?.toIntOrNull() ?: 7860
    val serverHost: String = System.getenv("SERVER_HOST") ?: "0.0.0.0"
    val environment: String = System.getenv("ENVIRONMENT") ?: "development"

    // Feature Flags
    val enableDeepResearch: Boolean = System.getenv("ENABLE_DEEP_RESEARCH")?.toBoolean() ?: true
    val enableVision: Boolean = System.getenv("ENABLE_VISION")?.toBoolean() ?: true
    val enableRAG: Boolean = System.getenv("ENABLE_RAG")?.toBoolean() ?: true

    // Limits
    val maxConcurrentSessions: Int = System.getenv("MAX_CONCURRENT_SESSIONS")?.toIntOrNull() ?: 10
    val maxSessionDurationMinutes: Int = System.getenv("MAX_SESSION_DURATION_MINUTES")?.toIntOrNull() ?: 120
    val maxTokensPerRequest: Int = System.getenv("MAX_TOKENS_PER_REQUEST")?.toIntOrNull() ?: 8192

    // Timeouts
    val httpTimeoutMs: Long = System.getenv("HTTP_TIMEOUT_MS")?.toLongOrNull() ?: 300_000
    val connectionTimeoutMs: Long = System.getenv("CONNECTION_TIMEOUT_MS")?.toLongOrNull() ?: 30_000

    // Monitoring
    val enableMonitoring: Boolean = System.getenv("ENABLE_MONITORING")?.toBoolean() ?: true
    val enableTracing: Boolean = System.getenv("ENABLE_TRACING")?.toBoolean() ?: false

    val isDevelopment: Boolean = environment == "development"
    val isProduction: Boolean = environment == "production"

    /**
     * Parse comma-separated API keys from environment variable.
     */
    private fun parseApiKeys(envVar: String?): List<String> {
        if (envVar.isNullOrBlank()) return emptyList()
        return envVar.split(",").map { it.trim() }.filter { it.isNotBlank() }
    }

    /**
     * Validate that required configuration is present.
     * OpenCode CLI requires NO API keys — only DB_URL is mandatory.
     */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()

        if (dbUrl.isNullOrBlank()) {
            errors.add("DB_URL environment variable is required")
        }

        return errors
    }

    /**
     * Get configuration summary for logging.
     */
    fun getSummary(): Map<String, Any?> =
        mapOf(
            "environment" to environment,
            "server_port" to serverPort,
            "llm_provider" to "opencode-cli-free",
            "krea_api_key_set" to (kreaApiKey != null),
            "max_concurrent_sessions" to maxConcurrentSessions,
            "monitoring_enabled" to enableMonitoring,
            "tracing_enabled" to enableTracing,
        )
}
