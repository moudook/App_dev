package com.example.smarty.server.agent2

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.slf4j.LoggerFactory

data class ChatUsage(
    val modelId: String = "",
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
    val latencyMs: Long = 0,
)

interface ChatUsageListener {
    fun onRequest(modelId: String)
    fun onResponse(usage: ChatUsage)
    fun onError(modelId: String, error: Throwable)
}

class LoggingChatUsageListener : ChatUsageListener {
    private val logger = LoggerFactory.getLogger(LoggingChatUsageListener::class.java)

    override fun onRequest(modelId: String) {
        logger.info("[Usage] Request started: $modelId")
    }

    override fun onResponse(usage: ChatUsage) {
        logger.info("[Usage] $usage")
    }

    override fun onError(modelId: String, error: Throwable) {
        logger.warn("[Usage] Error on $modelId: ${error.message}")
    }
}
