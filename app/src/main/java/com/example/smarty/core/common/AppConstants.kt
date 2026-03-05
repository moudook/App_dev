package com.example.smarty.core.common

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.hours

/**
 * Centralized Configuration for Smarty Application.
 * 
 * Architecture Principles:
 * - Type-safe configuration with sealed types
 * - Compile-time validation where possible
 * - Clear separation of concerns
 * - Easy testing with injectable configuration
 * 
 * Usage:
 * ```kotlin
 * @Inject lateinit var config: AppConfig
 * val chunkSize = config.pdf.chunkSize
 * ```
 */
object AppConfig {
    
    // ==================== PDF Processing Configuration ====================
    val pdf: PdfConfig = PdfConfig.MediumModel
    
    sealed class PdfConfig(
        val chunkSize: Int,
        val overlap: Int,
        val description: String
    ) {
        object SmallModel : PdfConfig(
            chunkSize = 8_000,
            overlap = 300,
            description = "For models with < 32K context window"
        )
        
        object MediumModel : PdfConfig(
            chunkSize = 32_000,
            overlap = 500,
            description = "Default - For 32K-128K context (Claude, GPT-4)"
        )
        
        object LargeModel : PdfConfig(
            chunkSize = 64_000,
            overlap = 800,
            description = "For 128K-256K context (Claude 200K)"
        )
        
        object XLargeModel : PdfConfig(
            chunkSize = 128_000,
            overlap = 1_000,
            description = "For 256K+ context models"
        )
        
        companion object {
            /**
             * Get optimal config for model's context window
             */
            fun forContextWindow(contextSize: Int): PdfConfig {
                return when {
                    contextSize < 32_000 -> SmallModel
                    contextSize < 128_000 -> MediumModel
                    contextSize < 256_000 -> LargeModel
                    else -> XLargeModel
                }
            }
        }
    }

    // ==================== Network Configuration ====================
    val network: NetworkConfig = NetworkConfig
    
    object NetworkConfig {
        val connectionTimeout: Duration = 60.seconds
        val readTimeout: Duration = 300.seconds
        val writeTimeout: Duration = 120.seconds
        val quickTimeout: Duration = 5.seconds
        val longTimeout: Duration = 600.seconds
        
        const val MAX_RETRIES = 3
        val retryBackoffMs: Long = 1000L
        val retryMaxBackoffMs: Long = 10_000L
    }

    // ==================== Cache Configuration ====================
    val cache: CacheConfig = CacheConfig
    
    object CacheConfig {
        const val MAX_ENTRIES = 100
        val MAX_SIZE_BYTES: Long = 100 * 1024 * 1024
        val DEFAULT_TTL: Duration = 24.hours
        
        const val MAX_LOCAL_BACKUPS = 5
        val MAX_BACKUP_SIZE_BYTES: Long = 500 * 1024 * 1024
    }

    // ==================== Chat Configuration ====================
    val chat: ChatConfig = ChatConfig
    
    object ChatConfig {
        const val MAX_MESSAGES_TO_LOAD = 100
        const val MIN_MESSAGES_FOR_SUMMARY = 15
        val SUMMARY_DELAY: Duration = 30.minutes
        const val MAX_SUGGESTIONS = 2
        const val MAX_CONTEXT_MESSAGES = 20
    }

    // ==================== Notes Configuration ====================
    val notes: NotesConfig = NotesConfig
    
    object NotesConfig {
        const val MAX_NOTES_TO_LOAD = 200
        const val MAX_TITLE_LENGTH = 200
        const val MAX_CONTENT_LENGTH = 50_000
        const val MIN_SEARCH_QUERY_LENGTH = 2
        const val MAX_SEARCH_RESULTS = 50
        const val BATCH_SIZE = 100
    }

    // ==================== Feature Flags ====================
    val features: FeatureFlags = FeatureFlags
    
    object FeatureFlags {
        const val ENABLE_THINKING = true
        const val ENABLE_VOICE_INPUT = true
        const val ENABLE_OFFLINE_MODE = true
        const val ENABLE_AUTO_BACKUP = true
        const val ENABLE_CRASH_REPORTING = true
        
        const val ENABLE_DEBUG_LOGS = false
        const val ENABLE_NETWORK_LOGS = false
        const val ENABLE_PERFORMANCE_LOGS = false
    }

    // ==================== API Endpoints ====================
    val api: ApiEndpoints = ApiEndpoints
    
    object ApiEndpoints {
        const val CHAT_MESSAGES = "/api/v1/chat/messages"
        const val CHAT_SESSIONS = "/api/v1/chat/sessions"
        const val FCM_REGISTER = "/api/fcm/register"
        const val FCM_UNREGISTER = "/api/fcm/unregister"
        const val NOTES_SYNC = "/api/sync/notes"
        const val MEMORY_STORE = "/api/memory/store"
        const val HEALTH_CHECK = "/health"
        
        fun getFullUrl(endpoint: String, baseUrl: String): String {
            return "${baseUrl.removeSuffix("/")}$endpoint"
        }
    }

    // ==================== Quality of Life ====================
    val isDebug: Boolean get() = false
    
    val versionName: String get() = "1.2.0"
    
    val versionCode: Int get() = 3
}
