package com.example.smarty.server.agent

import com.example.smarty.protocol.DeviceCapabilities
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory registry to track device capabilities for active users.
 * In production, this should be backed by Redis or a database.
 */
object DeviceRegistry {
    private val userCapabilities = ConcurrentHashMap<String, DeviceCapabilities>()

    fun registerDevice(
        userId: String,
        capabilities: DeviceCapabilities,
    ) {
        userCapabilities[userId] = capabilities
    }

    fun getCapabilities(userId: String): DeviceCapabilities? = userCapabilities[userId]
}
