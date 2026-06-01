package com.example.smarty.server.agent

import com.example.smarty.agent.permissions.ToolPermissionDecision
import com.example.smarty.agent.permissions.ToolPermissionPolicy
import com.example.smarty.server.data.EffectiveDecision
import com.example.smarty.server.data.PermissionRepository
import org.slf4j.LoggerFactory

/**
 * Server-side tool-permission enforcer. Wraps the shared
 * [ToolPermissionPolicy] (defined in `common` so the same allow/deny
 * matrix is enforced on Android, Ktor, and Supabase) and consults the
 * per-user `tool_permissions` overrides via [PermissionRepository].
 *
 * The OpenCode CLI's `opencode.json` already enforces the static
 * policy before any event is emitted, so this is a defensive safety
 * net: if a `permission.asked` event leaks through (plugin drift,
 * CLI misconfiguration, manual `opencode` invocation without the
 * policy file), the Ktor layer still honors it AND respects any
 * per-user overrides the user has set in the Android settings UI.
 *
 * Decision chain (see [PermissionRepository.resolveEffectiveDecision]):
 *  1. `tool_permissions` override (ALLOW/DENY, not expired) → that
 *  2. `tool_permissions` row with `INHERIT` or expired → fall through
 *  3. Static `SMARTY_DEFAULT` → ALLOW / DENY / DEFAULT
 *  4. Unknown tool → DEFAULT (let CLI surface the prompt)
 *
 * Layered with the existing in-server approval registry:
 * - [ToolPermissionDecision.ALLOW] → skip approval, just execute
 * - [ToolPermissionDecision.DENY] → block with a synthetic denial
 * - [ToolPermissionDecision.DEFAULT] → fall through to
 *   [ToolExecutor.requiresApproval] which checks the per-tool
 *   `toolApprovalRegistry` (e.g. `device` opens apps so it requires
 *   approval)
 *
 * Stateless on the enforcer itself — caching lives in
 * [PermissionRepository]. Thread-safe.
 */
class ToolPermissionEnforcer(
    private val policy: ToolPermissionPolicy = ToolPermissionPolicy.SMARTY_DEFAULT,
    private val repository: PermissionRepository? = null,
) {
    private val logger = LoggerFactory.getLogger(ToolPermissionEnforcer::class.java)

    /**
     * Synchronous, policy-only decision (no DB lookup). Used in
     * hot paths where a per-user override is irrelevant — e.g. the
     * `ToolExecutor.requiresApproval` filter, which only knows
     * the canonical tool name and not the user context.
     */
    fun decide(toolName: String): ToolPermissionDecision = policy.decide(toolName)

    /**
     * Async, per-user decision. Reads from the `tool_permissions`
     * table (cached for 30s in [PermissionRepository]). Use this
     * from request handlers and the WebSocket ingest where the
     * authenticated user is available.
     *
     * On DB unavailability, falls back to the static policy (same
     * behavior as [decide]).
     */
    suspend fun decideForUser(
        userId: String,
        toolName: String,
    ): EffectiveDecision {
        val repo = repository
            ?: return EffectiveDecision(
                userId = userId,
                toolName = toolName,
                decision = policy.decide(toolName),
                isOverridden = false,
                overrideSource = null,
                overrideUpdatedAt = null,
                overrideExpiresAt = null,
            )
        return repo.resolveEffectiveDecision(userId, toolName)
    }

    /**
     * Convenience: should the Ktor layer drop (or auto-respond to) a
     * `permission.asked` event for this tool? Returns `true` if the
     * policy explicitly allows or denies the tool, meaning the CLI
     * would have handled it internally and the event is a leak.
     */
    fun shouldShortCircuit(toolName: String): Boolean =
        decide(toolName) != ToolPermissionDecision.DEFAULT

    /**
     * Per-user variant: should we short-circuit for *this* user,
     * taking their overrides into account? Returns the full
     * [EffectiveDecision] so callers can log it.
     */
    suspend fun shouldShortCircuitForUser(
        userId: String,
        toolName: String,
    ): Boolean = decideForUser(userId, toolName).decision != ToolPermissionDecision.DEFAULT

    /**
     * The synthetic response text the Ktor layer should send to the
     * OpenCode plugin's MCP `ask` tool when the policy short-circuits
     * a `permission.asked` event. The text is delivered via the same
     * `/tmp/opencode-asks/<sessionID>/<callID>.response.txt` path as
     * real user responses.
     *
     * Sync variant uses the static policy (used when no user
     * context is available, e.g. anonymous WebSocket events).
     */
    fun syntheticResponse(toolName: String): String =
        syntheticResponseForDecision(decide(toolName))

    /**
     * Async variant — takes the per-user decision into account.
     */
    suspend fun syntheticResponseForUser(
        userId: String,
        toolName: String,
    ): String {
        val decision = decideForUser(userId, toolName).decision
        return syntheticResponseForDecision(decision)
    }

    private fun syntheticResponseForDecision(decision: ToolPermissionDecision): String =
        when (decision) {
            ToolPermissionDecision.ALLOW -> "allow"
            ToolPermissionDecision.DENY -> "deny"
            // Caller should not invoke for DEFAULT — guarded by
            // [shouldShortCircuit] check.
            ToolPermissionDecision.DEFAULT -> ""
        }
}
