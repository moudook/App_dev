package com.example.smarty.agent.transport

import com.example.smarty.protocol.AgentCommand

/**
 * Transport layer for delivering AgentCommand to execution.
 *
 * This is a pure delivery abstraction:
 * - No validation (handled upstream)
 * - No logging (handled upstream)
 * - No retries, acknowledgments, or delivery guarantees
 *
 * Implementations may deliver commands locally (direct execution),
 * remotely (network), or through other mechanisms (queues, etc.).
 */
interface CommandTransport {
    /**
     * Dispatch a validated command for execution.
     *
     * Fire-and-forget semantics:
     * - No return value
     * - No acknowledgment
     * - No delivery guarantee
     *
     * @param command The validated AgentCommand to deliver
     */
    fun dispatch(command: AgentCommand)
}
