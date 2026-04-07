package com.example.smarty.data.local

/**
 * Supported AI Connections.
 */
enum class AIConnection {
    LOCAL_PC, // Local LLM server via USB/WiFi connection
    ;

    val displayName: String
        get() =
            when (this) {
                LOCAL_PC -> "Local LLM"
            }
}
