package com.example.smarty.features.chat.domain.state

/**
 * Mutable approval state for the ChatViewModel.
 * Tracked per StreamingMessageId so we can route the approval response
 * back to the correct agent session.
 */
data class PendingApproval(
    val messageId: String,
    val sessionId: String?,
    val eventId: String,
    val toolId: String,
    val toolName: String,
    val toolTitle: String,
    val toolArgs: String,
    val requestedAt: Long = System.currentTimeMillis(),
)

/**
 * Lightweight UI-only state for the approval processing step,
 * stored in a StateFlow in ChatViewModel so the bottom sheet / card
 * observes it independently of the full agentEvents list.
 */
data class ApprovalUiState(
    val isVisible: Boolean = false,
    val toolName: String = "",
    val toolTitle: String = "",
    val toolArgs: String = "",
    val messageId: String = "",
    val sessionId: String? = null,
)
