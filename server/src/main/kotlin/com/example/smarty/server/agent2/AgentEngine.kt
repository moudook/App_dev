package com.example.smarty.server.agent2

import com.example.smarty.protocol.AgentEvent
import kotlinx.coroutines.flow.Flow

data class AgentRequest(
    val query: String,
    val sessionId: String,
    val userId: String,
    val historyJson: String? = null,
    val modelOverride: String? = null,
    val clientTimezone: String? = null,
    val clientTimeMillis: Long? = null,
    val personality: String? = null,
    val section: String? = null,
    val resumeToolResultJson: String? = null,
)

data class AgentResponse(
    val text: String,
    val events: List<AgentEvent> = emptyList(),
)

interface AgentEngine {
    val name: String

    suspend fun stream(
        request: AgentRequest,
        eventEmitter: suspend (AgentEvent) -> Unit,
    ): Flow<String>
}
