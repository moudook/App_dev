package com.example.smarty.agent

import com.example.smarty.agent.models.WebCitation
import com.example.smarty.agent.models.ImageDisplayItem
import com.example.smarty.protocol.AgentCommand

/**
 * Interface for events sent FROM the Agent TO the Client/UI.
 * These are "fire and forget" notifications or status updates.
 *
 * The Agent DECIDES what to do, then EMITS commands via this sink.
 * The Android Client EXECUTES the commands.
 *
 * Separation of concerns:
 * - Agent (Brain): Decision-making, tool selection, response generation
 * - Client (Body): Command execution, device access, UI updates
 */
interface AgentEventSink {
    // =========================================================================
    // LEGACY METHODS (kept for backwards compatibility during migration)
    // These will be replaced by emit(AgentCommand) once migration is complete.
    // =========================================================================
    fun onToolExecutionStarted(toolName: String, toolDisplayName: String)
    fun onToolExecutionCompleted(toolName: String)
    fun onStatusUpdate(status: String)
    fun onCitationsFound(citations: List<WebCitation>)
    fun onDisplayImages(images: List<ImageDisplayItem>)
    fun onPlanStatusChanged(status: String?)

    // =========================================================================
    // COMMAND EMISSION (new unified pattern)
    // All device-side effects should go through this method.
    // =========================================================================

    /**
     * Emit a command for the Android client to execute.
     *
     * This is the primary interface for the Agent to request actions from the device.
     * Commands are fire-and-forget from the Agent's perspective.
     *
     * @param command The AgentCommand to execute
     */
    fun emit(command: AgentCommand)
}
