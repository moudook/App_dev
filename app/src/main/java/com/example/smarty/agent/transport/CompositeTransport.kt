package com.example.smarty.agent.transport

import com.example.smarty.protocol.AgentCommand

/**
 * Composite transport that dispatches to multiple transports.
 *
 * Dispatches commands first to an optional shadow transport (for logging/debugging),
 * then to the primary transport (for actual execution).
 *
 * Key characteristics:
 * - Shadow transport is optional (null = disabled)
 * - Shadow runs first (observe before execute)
 * - Primary always runs
 * - No error handling, retries, or acknowledgments
 * - Fire-and-forget semantics
 *
 * Usage:
 * - Default: CompositeTransport(primary = local, shadow = null)
 * - Debug mode: CompositeTransport(primary = local, shadow = shadowRemote)
 */
class CompositeTransport(
    private val primary: CommandTransport,
    private val shadow: CommandTransport? = null
) : CommandTransport {

    /**
     * Dispatch command to shadow (if enabled), then primary.
     *
     * Order: shadow → primary
     * This ensures logging/observation happens before execution.
     *
     * @param command The AgentCommand to dispatch
     */
    override fun dispatch(command: AgentCommand) {
        // Shadow first (observe before execute)
        shadow?.dispatch(command)

        // Primary always executes
        primary.dispatch(command)
    }
}
