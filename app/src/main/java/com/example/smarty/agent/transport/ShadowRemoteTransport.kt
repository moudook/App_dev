package com.example.smarty.agent.transport

import android.util.Log
import com.example.smarty.protocol.AgentCommand
import kotlinx.serialization.json.Json

/**
 * Shadow remote transport for development and debugging.
 *
 * This transport serializes AgentCommand to JSON and logs it,
 * simulating the behavior of a future remote transport without
 * actually performing any networking or command execution.
 *
 * Key characteristics:
 * - Serializes commands to JSON using kotlinx.serialization
 * - Logs serialized JSON at debug level
 * - Does NOT execute commands
 * - Does NOT perform networking
 * - Fire-and-forget (no acknowledgments)
 *
 * Usage: Enable in development to verify command serialization
 * before implementing actual remote transport.
 */
class ShadowRemoteTransport : CommandTransport {

    companion object {
        private const val TAG = "ShadowRemote"
    }

    // Configure JSON for readable output (development only)
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    /**
     * Serialize command to JSON and log it.
     *
     * This method does not execute the command - it only logs
     * what would be sent to a remote server.
     *
     * @param command The AgentCommand to serialize and log
     */
    override fun dispatch(command: AgentCommand) {
        try {
            val serialized = json.encodeToString(AgentCommand.serializer(), command)
            Log.d(TAG, "Would send to remote:\n$serialized")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to serialize command: ${command::class.simpleName}", e)
        }
    }
}
