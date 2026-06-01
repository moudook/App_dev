package com.example.smarty.agent.permissions

/**
 * Result of evaluating a tool call against the [ToolPermissionPolicy].
 *
 * - [ALLOW]   The policy explicitly permits the tool. The Android app and
 *             Ktor server should auto-approve the corresponding
 *             `permission.asked` event without surfacing an approval card.
 *             The user sees a small "auto-approved" chip for transparency.
 *
 * - [DENY]    The policy explicitly forbids the tool. The app and server
 *             should auto-deny the event without ever running the tool.
 *             The user sees a "blocked by policy" chip.
 *
 * - [DEFAULT] The policy is silent on the tool (neither allowed nor denied).
 *             The app shows the normal approval card so the user can decide.
 *             The Ktor server emits `ApprovalRequested` for human decision.
 */
enum class ToolPermissionDecision {
    ALLOW,
    DENY,
    DEFAULT,
}

/**
 * Centralized allow/deny policy for agent tools. The same policy is enforced
 * on three layers so behavior stays consistent:
 *
 * 1. **Android app** — short-circuits `permission.asked` events arriving
 *    over the Ktor `/ws/timeline` WebSocket. Auto-decided events never
 *    trigger an approval card; the user sees a brief chip instead.
 *
 * 2. **Ktor server** — short-circuits events arriving over
 *    `/opencode/events` so the WebSocket broadcast doesn't even include
 *    `permission.asked` for auto-decided tools. Also filters
 *    server-side MCP tool calls (`McpServer.ask_user` etc.) and
 *    `ToolExecutor.requiresApproval` so the policy is honored even if
 *    the Android app is bypassed.
 *
 * 3. **Supabase schema** — `tool_permissions` table lets users override
 *    the default policy per-user; `permission_audit_log` records every
 *    decision for review.
 *
 * The default policy [SMARTY_DEFAULT] mirrors `opencode.json`'s
 * `permission` block exactly. The OpenCode CLI's built-in tools
 * (`bash`, `read`, `write`, `edit`, `grep`, `glob`, `question`,
 * `external_directory`, `skill`, `todowrite`) are explicitly denied so
 * they don't conflict with the Smarty project's own MCP tools. Smarty
 * MCP tools (memory, schedule, remind, etc.) are explicitly allowed so
 * the app and server can auto-approve them without prompting the user.
 */
data class ToolPermissionPolicy(
    val allowed: Set<String>,
    val denied: Set<String>,
) {
    /**
     * Decide what to do with [toolName]. Lookup is case-insensitive and
     * ignores leading/trailing whitespace. DENY takes precedence over
     * ALLOW when a tool is in both lists (defensive — should never
     * happen in practice, but safer than silent auto-approval).
     */
    fun decide(toolName: String): ToolPermissionDecision {
        val normalized = toolName.trim().lowercase()
        if (normalized.isEmpty()) return ToolPermissionDecision.DEFAULT
        return when {
            normalized in denied -> ToolPermissionDecision.DENY
            normalized in allowed -> ToolPermissionDecision.ALLOW
            else -> ToolPermissionDecision.DEFAULT
        }
    }

    /**
     * Bulk check — returns the set of allowed/denied tool names so the
     * UI can render a "policy summary" card listing all rules.
     */
    fun summary(): PolicySummary =
        PolicySummary(
            allowed = allowed.sorted(),
            denied = denied.sorted(),
        )

    companion object {
        /**
         * The default Smarty policy — must match `opencode.json`'s
         * `permission` block. Update both files together if a tool's
         * policy changes.
         */
        val SMARTY_DEFAULT: ToolPermissionPolicy =
            ToolPermissionPolicy(
                allowed =
                    setOf(
                        // Smarty MCP tools (the project's own tools)
                        "memory",
                        "schedule",
                        "remind",
                        // device is NOT in ALLOW — it falls through to DEFAULT
                        // so the legacy toolApprovalRegistry gating applies
                        // (requires user approval for app opens, toggles, etc.)
                        "navigate",
                        "generate_image",
                        "ask_user",
                        "get_note_by_id",
                        "search_history",
                        "save_progress",
                        "read_progress",
                        "guided_breathing",
                        // Web tools (allowed so the agent can do research)
                        "webfetch",
                        "websearch",
                        // Sub-agent orchestration
                        "task",
                        "invoke_subagent",
                        "define_subagent",
                        "send_message",
                        "manage_subagents",
                    ),
                denied =
                    setOf(
                        // OpenCode CLI built-ins (denied so they don't
                        // bypass the Smarty project's own tools)
                        "bash",
                        "read",
                        "write",
                        "edit",
                        "grep",
                        "glob",
                        // OpenCode CLI's own interactive `question` tool
                        // is denied because the project has its own
                        // `ask_user` MCP tool with structured questions.
                        "question",
                        // Path/access guards
                        "external_directory",
                        "skill",
                        "todowrite",
                    ),
            )
    }
}

/**
 * Human-readable view of the active policy — used by the UI to render
 * a "policy summary" card so users can see at a glance which tools
 * will be auto-approved, which will be blocked, and which will need
 * their explicit approval.
 */
data class PolicySummary(
    val allowed: List<String>,
    val denied: List<String>,
) {
    val isEmpty: Boolean get() = allowed.isEmpty() && denied.isEmpty()
}
