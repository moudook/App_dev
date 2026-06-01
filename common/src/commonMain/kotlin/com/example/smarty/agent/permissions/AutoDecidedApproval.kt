package com.example.smarty.agent.permissions

/**
 * A `permission.asked` event that the policy short-circuited. Surfaced
 * in the UI as a brief "auto-approved" or "blocked by policy" chip near
 * the agent's streaming message, so the user can see why no approval
 * card appeared.
 *
 * Replaces the [PendingApproval] flow entirely for ALLOW/DENY decisions
 * — the user doesn't need to interact, so the chip is informational
 * only. Chips auto-fade after a few seconds.
 *
 * @param toolName   Machine name of the tool (`bash`, `websearch`, etc.)
 * @param toolId     OpenCode `callID` from the `permission.asked` event.
 *                   Needed so the timeline can be cross-referenced if
 *                   the user later asks "why was my web search auto-run?".
 * @param decision   [ToolPermissionDecision.ALLOW] or [ToolPermissionDecision.DENY].
 *                   [ToolPermissionDecision.DEFAULT] is filtered out by the
 *                   collector — those still go through the approval card flow.
 * @param sessionId  OpenCode session ID, for audit-log cross-reference.
 * @param reason     Human-readable explanation (e.g. "Allowed by default
 *                   policy", "Blocked: bash is denied to prevent
 *                   filesystem access").
 * @param timestamp  Epoch ms when the decision was made.
 */
data class AutoDecidedApproval(
    val toolName: String,
    val toolId: String,
    val decision: ToolPermissionDecision,
    val sessionId: String?,
    val reason: String,
    val timestamp: Long = System.currentTimeMillis(),
) {
    val isAllow: Boolean get() = decision == ToolPermissionDecision.ALLOW
    val isDeny: Boolean get() = decision == ToolPermissionDecision.DENY
}
