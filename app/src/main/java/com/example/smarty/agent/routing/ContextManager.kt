package com.example.smarty.agent.routing

import android.util.Log
import com.example.smarty.data.local.AIProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Universal Context Manager.
 *
 * Implements "Context Caching" strategy:
 * 1. **Native Native**: For models with huge context (Gemini 1.5+), pass full content.
 * 2. **Compression**: Stubbed for now.
 */
object ContextManager {
    private const val TAG = "ContextManager"

    suspend fun optimizeContextForModel(
        content: String,
        currentExecutor: Any,  // ExecutorResult.Success
        ingestionExecutor: Any? = null  // Optional ingestion executor
    ): String {
        // Extract provider from executor using reflection or casting
        val provider = try {
            val providerField = currentExecutor::class.java.getDeclaredField("provider")
            providerField.isAccessible = true
            providerField.get(currentExecutor) as? AIProvider
        } catch (e: Exception) {
            Log.w(TAG, "Could not extract provider from executor: ${e.message}")
            null
        }

        // Strategy 1: Direct Pass for Gemini (Native Caching support)
        if (provider == AIProvider.GEMINI) {
            Log.d(TAG, "Direct Pass: Using native context for Gemini")
            return content
        }

        // Fallback: Return content as is (relies on truncation upstream if needed, or future compression impl)
        return content
    }

    // Unused but kept for structure
    private suspend fun compressContext(
        content: String
    ): String = withContext(Dispatchers.Default) {
        return@withContext "summary"
    }
}
