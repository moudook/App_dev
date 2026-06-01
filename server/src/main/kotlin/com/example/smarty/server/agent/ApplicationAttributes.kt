package com.example.smarty.server.agent

import com.example.smarty.server.data.PermissionRepository
import io.ktor.server.application.Application
import io.ktor.util.AttributeKey

/**
 * Type-safe keys for Application attributes. Centralized so
 * routes / tools can pull singletons without relying on string
 * literals scattered across the codebase.
 */
object ApplicationAttributes {
    val TOOL_PERMISSION_ENFORCER: AttributeKey<ToolPermissionEnforcer> =
        AttributeKey("ToolPermissionEnforcer")
    val PERMISSION_REPOSITORY: AttributeKey<PermissionRepository> =
        AttributeKey("PermissionRepository")
}

/** Pull the [ToolPermissionEnforcer] singleton off the [Application]. */
val Application.toolPermissionEnforcer: ToolPermissionEnforcer
    get() = attributes[ApplicationAttributes.TOOL_PERMISSION_ENFORCER]

/** Pull the [PermissionRepository] singleton off the [Application]. */
val Application.permissionRepository: PermissionRepository
    get() = attributes[ApplicationAttributes.PERMISSION_REPOSITORY]
