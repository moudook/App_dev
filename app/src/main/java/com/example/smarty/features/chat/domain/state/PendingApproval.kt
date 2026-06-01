package com.example.smarty.features.chat.domain.state

/**
 * Origin of a pending approval gate. Used by [ChatViewModel.callApproval] to
 * route the user's response to the correct backend endpoint.
 *
 * - [KtorMcp] approvals originate from the server-side Ktor MCP server
 *   (e.g. a tool called `mcp__smarty__ask_user`). Responses go to
 *   `POST /api/v1/chat/events/approval` which resolves an entry in the
 *   server's [ApprovalRegistry] and unblocks the awaiting tool.
 *
 * - [Plugin] approvals originate from the OpenCode CLI plugin
 *   (`kind = "user.input.required"` on `/ws/timeline`). Responses go to
 *   `POST /opencode/ask-response/{sessionId}/{callId}` which writes the
 *   answer to `/tmp/opencode-asks/<sessionID>/<callID>.response.txt` for
 *   the plugin's MCP `ask` tool to poll and unblock.
 */
enum class ApprovalSource {
    KtorMcp,
    Plugin,
}

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
    val source: ApprovalSource = ApprovalSource.KtorMcp,
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
