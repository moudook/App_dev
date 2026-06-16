package com.example.smarty.server.agent

import com.example.smarty.server.agent2.ContextWindowManager
import com.example.smarty.server.agent2.OpenRouterChatModelFactory
import com.example.smarty.server.agent2.PostgresChatMemoryStore
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

    // LangChain4j Phase 1 foundation keys
    val CONTEXT_WINDOW_MANAGER: AttributeKey<ContextWindowManager> =
        AttributeKey("ContextWindowManager")
    val CHAT_MODEL_FACTORY: AttributeKey<OpenRouterChatModelFactory> =
        AttributeKey("ChatModelFactory")
    val CHAT_MEMORY_STORE: AttributeKey<PostgresChatMemoryStore> =
        AttributeKey("ChatMemoryStore")
}

/** Pull the [ToolPermissionEnforcer] singleton off the [Application]. */
val Application.toolPermissionEnforcer: ToolPermissionEnforcer
    get() = attributes[ApplicationAttributes.TOOL_PERMISSION_ENFORCER]

/** Pull the [PermissionRepository] singleton off the [Application]. */
val Application.permissionRepository: PermissionRepository
    get() = attributes[ApplicationAttributes.PERMISSION_REPOSITORY]
