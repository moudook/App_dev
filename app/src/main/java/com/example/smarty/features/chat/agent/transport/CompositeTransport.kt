package com.example.smarty.features.chat.agent.transport

import com.example.smarty.protocol.AgentCommand
import com.example.smarty.protocol.ClientEvent

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
     * @return The result from the primary transport
     */
    override suspend fun dispatch(command: AgentCommand): ClientEvent? {
        // Shadow first (observe before execute)
        shadow?.dispatch(command)

        // Primary always executes and its result is returned
        return primary.dispatch(command)
    }
}
