package com.example.smarty.server.routes

import com.example.smarty.agent.permissions.ToolPermissionPolicy
import com.example.smarty.server.agent.permissionRepository
import com.example.smarty.server.plugins.firebaseUser
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

/**
 * HTTP API for managing per-user `tool_permissions` overrides.
 *
 *   GET  /api/v1/permissions/tools         — list effective decisions for the user
 *   PUT  /api/v1/permissions/tools/{name}  — upsert an override for a single tool
 *
 * Backed by [com.example.smarty.server.data.PermissionRepository].
 * Auth: Firebase bearer token (same `authenticate("firebase")` block
 * as the chat / sync routes).
 */
@Serializable
data class ToolPermissionDto(
    val toolName: String,
    /** Effective decision: ALLOW, DENY, or DEFAULT. */
    val decision: String,
    /** True iff the user has an active override row. */
    val isOverridden: Boolean,
    /** Source of the override: default, user_request, admin, revoked. */
    val overrideSource: String?,
    /** ISO-8601 timestamp of the last override update, or null. */
    val overrideUpdatedAt: String?,
    /** ISO-8601 timestamp of when the override expires, or null. */
    val overrideExpiresAt: String?,
)

@Serializable
data class ListPermissionsResponse(
    val tools: List<ToolPermissionDto>,
    /** Snapshot of SMARTY_DEFAULT for the client (so the settings UI can render chips even if a tool is not in tool_permissions yet). */
    val defaultPolicy: Map<String, String>,
)

@Serializable
data class UpsertPermissionRequest(
    /** ALLOW, DENY, or INHERIT. INHERIT deletes the override. */
    val decision: String,
    /** Optional reason text shown in the audit log. */
    val reason: String? = null,
    /** Optional ISO-8601 timestamp for soft expiry. */
    val expiresAt: String? = null,
)

@Serializable
data class UpsertPermissionResponse(
    val toolName: String,
    val effectiveDecision: String,
    val persisted: Boolean,
)

fun Application.configurePermissionRoutes() {
    routing {
        authenticate("firebase") {
            permissionRoutes()
        }
    }
}

private fun Routing.permissionRoutes() {
    /** List all tools with their effective decisions for the current user. */
    get("/api/v1/permissions/tools") {
        val user = call.firebaseUser()
        if (user == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
            return@get
        }
        val userId = user.userId
        val repo = call.application.permissionRepository

        val overrides = repo.listUserPermissions(userId)
        val overrideByName = overrides.associateBy { it.toolName }

        // Union of (overridden tools) and (every known tool from
        // SMARTY_DEFAULT). Tools only in SMARTY_DEFAULT have
        // isOverridden=false and the static decision is returned.
        val knownTools: Set<String> = (
            ToolPermissionPolicy.SMARTY_DEFAULT.allowed +
                ToolPermissionPolicy.SMARTY_DEFAULT.denied
            ).toSet()

        val allNames = (knownTools + overrideByName.keys).sorted()

        val dtos = allNames.map { name ->
            val eff = overrideByName[name]
            if (eff != null) {
                ToolPermissionDto(
                    toolName = name,
                    decision = eff.decision.name,
                    isOverridden = eff.isOverridden,
                    overrideSource = eff.overrideSource,
                    overrideUpdatedAt = eff.overrideUpdatedAt?.toString(),
                    overrideExpiresAt = eff.overrideExpiresAt?.toString(),
                )
            } else {
                ToolPermissionDto(
                    toolName = name,
                    decision = ToolPermissionPolicy.SMARTY_DEFAULT.decide(name).name,
                    isOverridden = false,
                    overrideSource = null,
                    overrideUpdatedAt = null,
                    overrideExpiresAt = null,
                )
            }
        }

        val defaultSnapshot: Map<String, String> = knownTools.associateWith {
            ToolPermissionPolicy.SMARTY_DEFAULT.decide(it).name
        }

        call.respond(
            HttpStatusCode.OK,
            ListPermissionsResponse(tools = dtos, defaultPolicy = defaultSnapshot),
        )
    }

    /** Upsert a single tool's override. INHERIT deletes the row. */
    put("/api/v1/permissions/tools/{toolName}") {
        val user = call.firebaseUser()
        if (user == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
            return@put
        }
        val userId = user.userId
        val repo = call.application.permissionRepository
        val toolName = call.parameters["toolName"]?.trim().orEmpty()
        if (toolName.isBlank() || toolName.length > 64) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid tool name"))
            return@put
        }
        // Restrict to known tools so the user can't accidentally
        // create override rows for arbitrary strings. Unknown tools
        // are still allowed (in case the user wants to blacklist a
        // tool that doesn't exist in SMARTY_DEFAULT yet) but the
        // server logs a warning.
        if (!ToolPermissionPolicy.SMARTY_DEFAULT.allowed.contains(toolName.lowercase()) &&
            !ToolPermissionPolicy.SMARTY_DEFAULT.denied.contains(toolName.lowercase())
        ) {
            logger.warn("[Permissions] upsert for unknown tool '$toolName' by user=$userId")
        }

        val req = try {
            call.receive<UpsertPermissionRequest>()
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Malformed JSON: ${e.message}"))
            return@put
        }

        val decision = req.decision.uppercase()
        if (decision !in setOf("ALLOW", "DENY", "INHERIT")) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "decision must be one of ALLOW, DENY, INHERIT"),
            )
            return@put
        }

        // INHERIT → delete the override row (or upsert a no-op)
        if (decision == "INHERIT") {
            val ok = repo.setUserPermission(
                userId = userId,
                toolName = toolName,
                decision = "INHERIT",
                source = "user_request",
                reason = req.reason ?: "Reset to default",
            )
            if (!ok) {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    mapOf("error" to "Could not persist override (DB unavailable or invalid userId)"),
                )
                return@put
            }
            call.respond(
                HttpStatusCode.OK,
                UpsertPermissionResponse(
                    toolName = toolName,
                    effectiveDecision = ToolPermissionPolicy.SMARTY_DEFAULT.decide(toolName).name,
                    persisted = true,
                ),
            )
            return@put
        }

        val ok = repo.setUserPermission(
            userId = userId,
            toolName = toolName,
            decision = decision,
            source = "user_request",
            reason = req.reason,
        )
        if (!ok) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                mapOf("error" to "Could not persist override (DB unavailable or invalid userId)"),
            )
            return@put
        }
        // Audit the user's explicit override as a USER_APPROVED/USER_DENIED
        // style event so the audit log shows the policy change.
        val auditDecision = if (decision == "ALLOW") "USER_APPROVED" else "USER_DENIED"
        repo.logDecision(
            userId = userId,
            sessionId = null,
            toolName = toolName,
            decision = auditDecision,
            actor = "user",
            userFeedback = req.reason,
            metadata = mapOf("source" to "settings_ui"),
        )

        call.respond(
            HttpStatusCode.OK,
            UpsertPermissionResponse(
                toolName = toolName,
                effectiveDecision = decision,
                persisted = true,
            ),
        )
    }
}

private val logger = org.slf4j.LoggerFactory.getLogger("PermissionRoutes")
