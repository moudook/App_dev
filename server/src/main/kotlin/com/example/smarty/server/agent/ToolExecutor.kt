package com.example.smarty.server.agent

import com.example.smarty.server.data.CalendarRepository
import com.example.smarty.server.data.DatabaseFactory
import com.example.smarty.server.data.GeneratedImageRepository
import com.example.smarty.server.data.NoteRepository
import com.example.smarty.server.data.PostgresVectorStore
import com.example.smarty.server.data.TimerRepository
import com.example.smarty.server.data.ToolSessionPayload
import com.example.smarty.server.data.ToolSessionRepository
import com.example.smarty.server.llm.LlmMessage
import com.example.smarty.server.tools.KreaImageTool
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import com.example.smarty.protocol.AgentEvent
import com.example.smarty.protocol.AskUserQuestion
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.*

/**
// === PERMISSION ENGINE: Tools that require user approval ===
// These tools will be paused before execution and sent as ApprovalRequested events.
// In OpenCode terms, these are the MCP tools that need human sanction.
// Agent asks "May I?" â†’ stream pauses â†’ user approves/denies â†’ stream resumes.
// Permission list lives here; add entries to grow gate coverage.
// In the App layer, ChatViewModel.callApproval() sends back the user decision.

sealed class ToolExecutionResult {
 data class Completed(val result: String) : ToolExecutionResult()
 data object RequiresApproval : ToolExecutionResult()
 data object Denied : ToolExecutionResult()
}
// â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

 * Extracted tool execution logic from ServerAgent.kt
 * Handles all tool execution, parameter parsing, and result formatting
 *
 * NOTE: Web search is handled natively by OpenCode CLI's built-in websearch.
 * The `search` tool returns a directive for the LLM to use its internal websearch.
 */
class ToolExecutor(
    private val userId: String,
    private val llmProvider: com.example.smarty.server.llm.LlmProvider,
    private val vectorStore: PostgresVectorStore,
    private val noteRepository: NoteRepository?,
    private val timerRepository: TimerRepository?,
    private val calendarRepository: CalendarRepository?,
    private val eventEmitter: suspend (com.example.smarty.protocol.AgentEvent) -> Unit,
    private val noteService: com.example.smarty.server.services.NoteService? = null,
    private val capabilities: com.example.smarty.protocol.DeviceCapabilities? = null,
    private val fcmService: com.example.smarty.server.services.FcmNotificationService? = null,
    private val toolPermissionEnforcer: ToolPermissionEnforcer = ToolPermissionEnforcer(),
) {
    private val logger = LoggerFactory.getLogger(ToolExecutor::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val generatedImageRepository =
        DatabaseFactory.getDataSource()?.let {
            GeneratedImageRepository(it)
        }

    /** DB-backed session store for ask_user turn-taking (§2.2 of agent_architecture.md). */
    private val toolSessionRepository =
        DatabaseFactory.getDataSource()?.let { ToolSessionRepository(it) }

    @Serializable
    data class GenerateImageArgs(
        val prompt: String,
        @SerialName("aspect_ratio") val aspectRatio: String? = null,
    )

    @Serializable
    data class UnifiedToolArgs(
        val action: String? = null,
        val title: String? = null,
        val content: String? = null,
        val category: String? = null,
        val query: String? = null,
        val id: String? = null,
        val fact: String? = null,
        val type: String? = null,
        val `when`: String? = null,
        val duration: String? = null,
        val description: String? = null,
        val what: String? = null,
        val repeat: String? = null,
        val app: String? = null,
        val actionType: String? = null,
        val setting: String? = null,
        val on: Boolean? = null,
        val info: String? = null,
        val screen: String? = null,
        val question: String? = null,
        val options: kotlinx.serialization.json.JsonElement? = null,
        @SerialName("allow_custom") val allowCustom: Boolean? = null,
        @SerialName("note_id") val noteId: String? = null,
        val snippet: String? = null,
        val limit: kotlinx.serialization.json.JsonElement? = null,
        val finding: String? = null,
        val source: String? = null,
        val url: String? = null,
        val note: String? = null,
        val questions: kotlinx.serialization.json.JsonArray? = null,
        @SerialName("search_depth") val searchDepth: String? = null,
        @SerialName("max_results") val maxResults: Int? = null,
        // === Atomic tool fields (Phase 2 additions) ===
        val intent: String? = null,
        val code: String? = null,
        val language: String? = null,
        val iteration: Int? = null,
        val queries: kotlinx.serialization.json.JsonArray? = null,
        @SerialName("emotional_significance") val emotionalSignificance: Int? = null,
    )

    suspend fun executeTool(
        name: String,
        argsJson: String,
        history: List<LlmMessage>,
        clientTimezone: String? = null,
        clientTimeMillis: Long? = null,
        skipApprovalGate: Boolean = false,
        toolCallId: String = "tool-${java.util.UUID.randomUUID()}",
        sessionId: String = "",
        section: String? = null,
    ): String {
        logger.info("Executing tool: $name with args: $argsJson")

        if (name == "generate_image") {
            return executeGenerateImage(argsJson)
        }

        val originalArgs = parseUnifiedArgs(argsJson)
        val mappedName = mapOldToolNames(name)

        val (toolName, args) =
            when (mappedName) {
                "schedule_add" -> "schedule" to originalArgs.copy(action = "add")
                "schedule_list" -> "schedule" to originalArgs.copy(action = "list")
                "schedule_remove" -> "schedule" to originalArgs.copy(action = "remove")
                "device_open" -> "device" to originalArgs.copy(action = "open")
                "device_media" -> "device" to originalArgs.copy(action = "media")
                "device_toggle" -> "device" to originalArgs.copy(action = "toggle")
                "device_status" -> "device" to originalArgs.copy(action = "status")
                "device_capture" -> "device" to originalArgs.copy(action = "capture")
                "navigate_go" -> "navigate" to originalArgs.copy(action = "go")
                "navigate_share" -> "navigate" to originalArgs.copy(action = "share")
                else -> mappedName to originalArgs
            }

        // === PERMISSION GATE ===
        // Check if the resolved canonical tool name requires user approval.
        if (!skipApprovalGate) {
            when (requiresApproval(toolName)) {
                ToolApprovalStatus.RequiresApproval -> {
                    logger.info("[ToolExecutor] Approval required for $toolName (resolved from $name)")
                    return "__WAITING_FOR_USER_RESPONSE__"
                }
                ToolApprovalStatus.NoLongerSupported -> {
                    return "Tool $toolName is no longer supported."
                }
                ToolApprovalStatus.ExecutesNormally -> { }
            }
        }

        return when (toolName) {
            // === Atomic Tool Set (Phase 2) ===
            "manage_notes" -> {
                when (args.action) {
                    "save" -> {
                        if (section?.lowercase() == "notes") {
                            return """{"error":"Notes section is refinement-only. The agent cannot create new notes here. Use 'manage_notes' with action='update' to refine the existing note."}"""
                        }
                        executeMemorySave(args)
                    }
                    "find" -> executeMemoryFind(args)
                    "update" -> executeMemoryUpdate(args)
                    "delete" -> {
                        if (section?.lowercase() == "notes") {
                            return """{"error":"Notes section is refinement-only. Notes cannot be deleted from here."}"""
                        }
                        executeMemoryDelete(args)
                    }
                    else -> "Unknown manage_notes action: ${args.action}"
                }
            }
            "update_user_profile" -> executeMemoryRemember(args)
            "manage_calendar" -> executeScheduleTool(args, clientTimezone, clientTimeMillis)
            "set_timer_alarm" -> executeRemindTool(args, clientTimezone, clientTimeMillis)
            "launch_ui" -> executeNavigateTool(args.copy(action = "go", screen = args.intent ?: args.screen), sessionId)
            "share_content" -> executeNavigateTool(args.copy(action = "share"), sessionId)
            "web_search" -> {
                val queries = args.queries?.joinToString(", ") {
                    try { it.jsonPrimitive.content } catch (_: Exception) { it.toString() }
                } ?: args.query ?: return "No search query provided"
                "[WEB_SEARCH_STUB] Queries: $queries. (Live Tavily integration pending)"
            }
            "code_interpreter" -> {
                val code = args.code ?: return "No code provided to execute"
                "[CODE_INTERPRETER_STUB] Language: ${args.language ?: "kotlin"}, code received (${code.length} chars). (Chicory/QuickJS integration pending)"
            }
            "scratchpad" -> {
                val content = args.content ?: args.note ?: ""
                "Scratchpad iteration ${args.iteration ?: 0} recorded (${content.length} chars)."
            }
            "search_past_chats" -> executeSearchHistory(args)

            // === Legacy Tool Set (still fully supported) ===
            "memory_save" -> {
                if (section?.lowercase() == "notes") {
                    return """{"error":"Notes section is refinement-only. The agent cannot create new notes here. Use 'memory_update' to refine the existing note, or move to Chat to create new notes."}"""
                }
                executeMemorySave(args)
            }
            "memory_find" -> executeMemoryFind(args)
            "memory_update" -> executeMemoryUpdate(args)
            "memory_delete" -> {
                if (section?.lowercase() == "notes") {
                    return """{"error":"Notes section is refinement-only. Notes cannot be deleted from here."}"""
                }
                executeMemoryDelete(args)
            }
            "memory_remember" -> executeMemoryRemember(args)
            "memory" -> executeMemoryTool(args)

            "schedule" -> executeScheduleTool(args, clientTimezone, clientTimeMillis)
            "remind" -> executeRemindTool(args, clientTimezone, clientTimeMillis)
            "device" -> executeDeviceTool(args, sessionId)
            "ask_user" -> executeAskUser(args, toolCallId, sessionId)
            "get_note_by_id" -> executeGetNoteById(args)
            "navigate" -> executeNavigateTool(args, sessionId)
            "search_history" -> executeSearchHistory(args)
            "guided_breathing" -> executeGuidedBreathing(sessionId)
            else -> "Unknown tool: $name"
        }
    }

    private suspend fun executeGenerateImage(argsJson: String): String {
        val imageArgs =
            try {
                json.decodeFromString<GenerateImageArgs>(argsJson)
            } catch (e: Exception) {
                val firstJson = extractFirstJsonObject(argsJson)
                if (firstJson != null) {
                    logger.warn("Malformed generate_image args, using first JSON object: ${firstJson.take(100)}...")
                    json.decodeFromString<GenerateImageArgs>(firstJson)
                } else {
                    throw e
                }
            }

        return try {
            val kreaTool = KreaImageTool()

            logger.info("Generating image with prompt: ${imageArgs.prompt.take(100)}...")

            val jobId = kreaTool.generateImage(imageArgs.prompt, imageArgs.aspectRatio ?: "1:1")

            generatedImageRepository?.create(
                userId = userId,
                sessionId = null,
                prompt = imageArgs.prompt,
                kreaJobId = jobId,
            )

            val result = kreaTool.waitForCompletion(jobId)

            val kreaImageUrl = result.result?.urls?.firstOrNull()
            if (kreaImageUrl.isNullOrBlank()) {
                throw IllegalStateException("Image generation completed but no image URL was returned")
            }

            var supabaseUrl: String? = null
            try {
                supabaseUrl =
                    kreaTool.uploadToSupabase(
                        imageUrl = kreaImageUrl,
                        jobId = jobId,
                        bucketName =
                            com.example.smarty.server.factory.SupabaseClientFactory
                                .getImageBucketName(),
                    )

            } catch (e: Exception) {
                logger.warn("Supabase upload failed, will use Krea URL: ${e.message}")
            }

            try {
                generatedImageRepository?.updateImageUrls(
                    kreaJobId = jobId,
                    imageUrl = kreaImageUrl,
                    supabaseUrl = supabaseUrl,
                )
                logger.info("Database updated with image URLs")
            } catch (e: Exception) {
                logger.warn("Failed to update database with image URLs: ${e.message}")
            }

            val finalImageUrl = supabaseUrl ?: kreaImageUrl
            val imageSource = if (supabaseUrl != null) "supabase" else "krea"

            """{"type": "image", "url": "$finalImageUrl", "source": "$imageSource", "prompt": "${imageArgs.prompt.replace(
                "\"",
                "\\\"",
            ).take(200)}", "jobId": "$jobId"}"""
        } catch (e: Exception) {
            logger.error("Image generation failed", e)
            try {
                generatedImageRepository?.clearQueuedForUser(userId)
                logger.info("Cleared queued images for user $userId after error")
            } catch (cleanupError: Exception) {
                logger.warn("Failed to clear queued images: ${cleanupError.message}")
            }
            "Failed to generate image: ${e.message}"
        }
    }

    private fun parseUnifiedArgs(argsJson: String): UnifiedToolArgs =
        try {
            json.decodeFromString<UnifiedToolArgs>(argsJson)
        } catch (e: Exception) {
            val firstJson = extractFirstJsonObject(argsJson)
            if (firstJson != null) {
                logger.warn("Malformed tool args (multiple JSON objects), using first: ${firstJson.take(100)}...")
                json.decodeFromString<UnifiedToolArgs>(firstJson)
            } else {
                throw e
            }
        }

    companion object {
        fun mapOldToolNames(name: String): String =
            when (name) {
                // Legacy → legacy canonical
                "save_note", "create_note" -> "memory_save"
                "find_note", "search_notes" -> "memory_find"
                "edit_note", "update_note" -> "memory_update"
                "delete_note" -> "memory_delete"
                "remember_fact", "store_context" -> "memory_remember"
                "add_event", "schedule_event" -> "schedule_add"
                "show_events", "list_events" -> "schedule_list"
                "remove_event", "delete_event" -> "schedule_remove"
                "set_reminder" -> "remind"
                "open_app", "launch_app" -> "device_open"
                "control_music", "control_media" -> "device_media"
                "toggle_setting" -> "device_toggle"
                "get_device_info" -> "device_status"
                "take_screenshot" -> "device_capture"
                "go_to_screen" -> "navigate_go"
                "share" -> "navigate_share"
                // Atomic → route directly (no rename needed, handled in when block)
                "manage_notes", "update_user_profile", "manage_calendar",
                "set_timer_alarm", "launch_ui", "share_content",
                "web_search", "code_interpreter", "scratchpad",
                "search_past_chats" -> name
                else -> name
            }
    }

    private suspend fun executeMemorySave(args: UnifiedToolArgs): String =
        if (noteService != null && args.title != null && args.content != null) {
            val noteId = noteService.createNote(userId, args.title, args.content, args.category, isAiCreated = true)
            emitStateSync("note_created", """{"id":"$noteId","title":"${args.title}"}""")
            "Saved: '${args.title}' (ID: $noteId). AI enrichment started in background."
        } else if (noteRepository != null && args.title != null && args.content != null) {
            val noteInfo =
                com.example.smarty.protocol.NoteInfo(
                    id = "",
                    title = args.title,
                    content = args.content,
                    categoryId = args.category,
                    processingStatus = "COMPLETED",
                    isAiCreated = true,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                )
            val noteId = noteRepository.create(userId, noteInfo)
            emitStateSync("note_created", """{"id":"$noteId","title":"${args.title}"}""")
            "Saved: '${args.title}' (ID: $noteId)"
        } else {
            logger.info("Device note save requested: ${args.title}")
            "Saved to device: ${args.title}"
        }

    private suspend fun executeMemoryFind(args: UnifiedToolArgs): String =
        if (noteService != null && args.query != null) {
            val results = noteService.searchNotes(userId, args.query)
            if (results.isEmpty()) {
                "No notes found for '${args.query}'."
            } else {
                results.joinToString("\n") { "- [${it.id}] ${it.title}: ${it.summary ?: it.content.take(80)}" }
            }
        } else if (noteRepository != null && args.query != null) {
            val results =
                noteRepository
                    .listByUser(userId, limit = 100)
                    .filter {
                        !it.isArchived &&
                            !it.isFullPrivacy &&
                            !it.excludeFromAiChat &&
                            (it.title.contains(args.query, ignoreCase = true) || it.content.contains(args.query, ignoreCase = true))
                    }.take(20)
            if (results.isEmpty()) {
                "No notes found for '${args.query}'."
            } else {
                results.joinToString("\n") { "- [${it.id}] ${it.title}: ${it.content.take(80)}" }
            }
        } else {
            logger.info("Device note search requested: ${args.query}")
            "Searching device for: ${args.query}"
        }

    private suspend fun executeMemoryUpdate(args: UnifiedToolArgs): String {
        return if (noteRepository != null && args.id != null) {
            val existing = noteRepository.getById(userId, args.id) ?: return "Note not found: ${args.id}"
            val updatedNote =
                existing.copy(
                    title = args.title ?: existing.title,
                    content = args.content ?: existing.content,
                    updatedAt = System.currentTimeMillis(),
                )
            val success = noteRepository.update(userId, updatedNote)
            emitStateSync("note_updated", """{"id":"${args.id}"}""")
            if (success) "Updated note ${args.id}" else "Failed to update note ${args.id}"
        } else {
            logger.info("Device note update requested: ${args.id}")
            "Update sent to device."
        }
    }

    private suspend fun executeMemoryDelete(args: UnifiedToolArgs): String =
        if (noteRepository != null && args.id != null) {
            noteRepository.delete(userId, args.id)
            emitStateSync("note_deleted", """{"id":"${args.id}"}""")
            "Deleted note ${args.id}"
        } else {
            logger.info("Device note delete requested: ${args.id}")
            "Delete sent to device."
        }

    private suspend fun executeMemoryRemember(args: UnifiedToolArgs): String =
        try {
            val fact = args.fact ?: args.content ?: ""
            vectorStore.store(userId, fact, mapOf("type" to (args.type ?: "factual")))
            "Remembered: ${fact.take(50)}"
        } catch (e: Exception) {
            "Failed: ${e.message}"
        }

    private suspend fun executeMemoryTool(args: UnifiedToolArgs): String =
        when (args.action) {
            "save" -> executeMemorySave(args)
            "find" -> executeMemoryFind(args)
            "update" -> executeMemoryUpdate(args)
            "delete" -> executeMemoryDelete(args)
            "remember" -> executeMemoryRemember(args)
            else -> "Unknown memory action: ${args.action}"
        }

    private suspend fun executeScheduleTool(
        args: UnifiedToolArgs,
        clientTimezone: String?,
        clientTimeMillis: Long?,
    ): String =
        when (args.action) {
            "add" -> {
                val startTime = parseNaturalTime(args.`when` ?: "", clientTimezone, clientTimeMillis) ?: System.currentTimeMillis()
                val durationMs = parseDurationToMs(args.duration ?: "1 hour")
                val endTime = startTime + durationMs
                if (calendarRepository != null && args.title != null) {
                    val eventInfo =
                        com.example.smarty.protocol.CalendarEventInfo(
                            id = "",
                            title = args.title,
                            startTime = startTime,
                            endTime = endTime,
                            description = args.description,
                            reminderMinutes = 15,
                            linkedNoteId = null,
                            googleEventId = null,
                            isEventPrivate = false,
                            createdAt = System.currentTimeMillis(),
                        )
                    val eventId = calendarRepository.create(userId, eventInfo)
                    emitStateSync("event_scheduled", """{"id":"$eventId","title":"${args.title}"}""")
                    "Event added: '${args.title}'"
                } else {
                    logger.info("Device event scheduled: ${args.title}")
                    "Event sent to device: ${args.title}"
                }
            }
            "list" -> {
                val (startMs, endMs) = parseTimeRange(args.`when` ?: "today", clientTimezone, clientTimeMillis)
                if (calendarRepository != null) {
                    val events = calendarRepository.listEventsInRange(userId, startMs, endMs)
                    if (events.isEmpty()) {
                        "No events for ${args.`when`}."
                    } else {
                        events.joinToString("\n") { "- [${it.id}] ${it.title}" }
                    }
                } else {
                    logger.info("Device event list requested")
                    "Requesting events from device."
                }
            }
            "remove" -> {
                if (calendarRepository != null && args.id != null) {
                    calendarRepository.delete(userId, args.id)
                    emitStateSync("event_deleted", """{"id":"${args.id}"}""")
                    "Event removed."
                } else {
                    logger.info("Device event delete requested: ${args.id}")
                    "Remove request sent to device."
                }
            }
            else -> "Unknown schedule action: ${args.action}"
        }

    private suspend fun executeRemindTool(
        args: UnifiedToolArgs,
        clientTimezone: String?,
        clientTimeMillis: Long?,
    ): String {
        return when (args.action) {
            "set" -> {
                val whenStr = args.`when` ?: ""
                val triggerTime = parseNaturalTime(whenStr, clientTimezone, clientTimeMillis)
                if (triggerTime == null) {
                    return "Could not understand the time '$whenStr'. Please specify a valid time or duration."
                }
                val isAlarm =
                    !whenStr.contains("in ") &&
                        !whenStr.contains("after ") &&
                        !whenStr.contains("from now") &&
                        !whenStr.matches(Regex("^(\\d+)\\s*(m(?:in)?|h(?:our|r)?|s(?:ec)?)\\b.*"))
                if (timerRepository != null && args.what != null) {
                    val durationMs = maxOf(0L, triggerTime - System.currentTimeMillis())
                    val timerId =
                        timerRepository.create(
                            userId,
                            args.what,
                            durationMs = durationMs,
                            triggerAt = triggerTime,
                            isAlarm = isAlarm,
                            repeat = args.repeat,
                        )
                    emitStateSync("timer_set", """{"id":"$timerId"}""")
                    "${if (isAlarm) "Reminder" else "Timer"} set: '${args.what}'"
                } else {
                    logger.info("Device timer set: ${args.what}")
                    "Reminder sent to device: ${args.what}"
                }
            }
            "list" -> {
                if (timerRepository != null) {
                    val timers = timerRepository.listActive(userId)
                    if (timers.isEmpty()) {
                        "No active timers or reminders."
                    } else {
                        timers.joinToString("\n") { "- [${it.id}] ${it.name} at ${java.time.Instant.ofEpochMilli(it.triggerAt)}" }
                    }
                } else {
                    logger.info("Device timer list requested")
                    "Listing reminders from device."
                }
            }
            "cancel" -> {
                if (timerRepository != null && args.id != null) {
                    timerRepository.deactivate(userId, args.id)
                    emitStateSync("timer_cancelled", """{"id":"${args.id}"}""")
                    "Reminder cancelled."
                } else {
                    logger.info("Device timer cancel requested: ${args.id}")
                    "Cancel request sent to device."
                }
            }
            else -> "Unknown remind action: ${args.action}"
        }
    }

    private suspend fun executeDeviceTool(
        args: UnifiedToolArgs,
        sessionId: String,
    ): String {
        // Issue #16: Real device dispatch via DeviceResponseRegistry + WebSocket
        val commandId = "devcmd-${java.util.UUID.randomUUID()}"
        val deferred = com.example.smarty.server.agent.DeviceResponseRegistry.createPendingRequest(commandId, sessionId)

        val deviceCommand =
            com.example.smarty.protocol.AgentEvent.DeviceCommand(
                eventId = java.util.UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                sessionId = sessionId,
                commandId = commandId,
                action = args.action ?: "unknown",
                setting = args.setting,
                on = args.on,
                app = args.app,
                actionType = args.actionType,
                info = args.info,
            )
        eventEmitter(deviceCommand)
        logger.info("[ToolExecutor] Dispatched device command: $commandId (action=${args.action}) to session=$sessionId")

        val result =
            try {
                kotlinx.coroutines.withTimeout(15_000) { deferred.await() }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                com.example.smarty.server.agent.DeviceResponseRegistry.resolveRequest(commandId, emptyMap())
                return "Device command timed out after 15s. Make sure your device is connected."
            }

        val status = result.status
        if (status.isEmpty()) {
            return when (args.action) {
                "status" -> "No device status available. Is your device connected?"
                "capture" -> "Screenshot request sent but no response from device."
                "open" -> "App open request sent but no response from device."
                "media" -> "Media control sent but no response from device."
                "toggle" -> "${args.setting} request sent but no response from device."
                else -> "Device command sent but no response."
            }
        }

        return when (args.action) {
            "open" -> "Opened ${args.app}: ${status.entries.joinToString { "${it.key}=${it.value}" }}"
            "media" -> "Media ${args.actionType}: ${status.entries.joinToString { "${it.key}=${it.value}" }}"
            "toggle" -> {
                val reported = status[args.setting]
                if (reported != null) "${args.setting} = $reported" else "${args.setting} toggled"
            }
            "status" -> status.entries.joinToString { "${it.key}: ${it.value}" }
            "capture" -> "Screenshot captured: ${status["path"] ?: status["url"] ?: "ok"}"
            else -> "Device: ${status.entries.joinToString { "${it.key}=${it.value}" }}"
        }
    }

    private suspend fun executeGuidedBreathing(sessionId: String): String {
        logger.info("Device breathing session requested")
        eventEmitter(
            AgentEvent.DeviceCommand(
                eventId = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                action = "guided_breathing",
                sessionId = sessionId,
                commandId = UUID.randomUUID().toString()
            )
        )
        return "Starting guided breathing session."
    }

    /**
     * DB-backed ask_user interactive session (§2.2 of agent_architecture.md).
     *
     * ARCHITECTURE MANDATE: We do NOT use CompletableDeferred or any in-memory
     * suspend pattern here. The full flow is:
     *   1. Validate args (return error string to LLM on bad schema).
     *   2. Persist minimal delta state to `tool_sessions` table (< 8 KB).
     *   3. Emit `AskUserRequest` SSE event to client.
     *   4. Return `__ASK_USER_TURN_COMPLETE__` — agent loop exits this SSE turn cleanly.
     *   5. Client posts answers to /webhook/ask_user_response.
     *   6. Webhook handler injects TOOL message into history and resumes agent.
     */
    private suspend fun executeAskUser(
        args: UnifiedToolArgs,
        toolCallId: String,
        sessionId: String,
    ): String {
        logger.info(
            "[ToolExecutor] ask_user called (DB-backed): questions=${args.questions?.size}, " +
            "question=${args.question?.take(100)}, options=${args.options?.toString()?.take(100)}"
        )

        // 1. Validate — return error to LLM if malformed so it can self-correct
        val validationError = validateAskUserArgs(args)
        if (validationError != null) {
            logger.warn("[ToolExecutor] ask_user validation failed toolCallId=$toolCallId: ${validationError.take(200)}")
            return validationError
        }

        // 2. Build typed question list
        val questions: List<AskUserQuestion> = buildQuestionList(args)
        if (questions.isEmpty()) {
            return "ERROR: ask_user requires at least one question with options."
        }

        // 3. Persist session to DB (no coroutine blocked — turn-taking via webhook)
        val ttlMinutes = 30
        val expiresAt = Instant.now().plus(ttlMinutes.toLong(), ChronoUnit.MINUTES)
        val payload = ToolSessionPayload(
            chatSessionId = sessionId,
            toolCallId = toolCallId,
            userId = userId,
            questionSummaries = questions.map { q -> q.question.take(120) },
            expiresAt = expiresAt.toString(),
        )
        val dbId = toolSessionRepository?.createPendingSession(payload)
        if (toolSessionRepository != null && dbId == null) {
            logger.error("[ToolExecutor] ask_user DB persistence failed for toolCallId=$toolCallId")
            return "ERROR: Failed to persist ask_user session state. The database may be unavailable. Please try again."
        }
        logger.info("[ToolExecutor] ask_user: session persisted dbId=$dbId toolCallId=$toolCallId sessionId=$sessionId")

        // 4. Emit AskUserRequest SSE event — client renders question UI + activates mic
        eventEmitter(
            AgentEvent.AskUserRequest(
                eventId = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                toolId = toolCallId,
                sessionId = sessionId,
                questions = questions,
                toolCallId = toolCallId,
                ttlMinutes = ttlMinutes,
            )
        )

        // 5. Trigger FCM Wakeup (Push Notification)
        fcmService?.let { service ->
            logger.info("[ToolExecutor] ask_user: sending FCM wakeup for sessionId=$sessionId")
            try {
                // Ensure launch runs in its own scope so it doesn't block
                kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    service.sendDataMessage(
                        userId = userId,
                        data = mapOf(
                            "type" to "ask_user_wakeup",
                            "sessionId" to sessionId,
                            "toolCallId" to toolCallId
                        )
                    )
                }
            } catch (e: Exception) {
                logger.error("[ToolExecutor] ask_user: failed to dispatch FCM wakeup", e)
            }
        }

        // 6. Return sentinel — ServerAgent/AgentRunManager checks this string and ends the SSE turn.
        //    The agent will be resumed by the webhook injecting a TOOL role message into history.
        logger.info("[ToolExecutor] ask_user: SSE turn ending cleanly, awaiting /webhook/ask_user_response for toolCallId=$toolCallId")
        return "__ASK_USER_TURN_COMPLETE__"
    }

    /**
     * Build a typed AskUserQuestion list from UnifiedToolArgs.
     * Handles 'questions' array format (preferred) and legacy single 'question' + 'options'.
     */
    private fun buildQuestionList(args: UnifiedToolArgs): List<AskUserQuestion> {
        if (args.questions != null && args.questions.isNotEmpty()) {
            return args.questions.mapNotNull { element ->
                try {
                    val qObj = element.jsonObject
                    val questionText = qObj["question"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val optionsArr = qObj["options"]?.jsonArray ?: return@mapNotNull null
                    val options = optionsArr.mapNotNull { opt ->
                        try { opt.jsonPrimitive.content } catch (_: Exception) { null }
                    }
                    val allowCustom = qObj["allow_custom"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                    val inputMode = qObj["input_mode"]?.jsonPrimitive?.content ?: "choice"
                    AskUserQuestion(question = questionText, options = options, allowCustom = allowCustom, inputMode = inputMode)
                } catch (_: Exception) { null }
            }
        }
        // Legacy single-question format
        val questionText = args.question ?: return emptyList()
        val optionsArr = try { args.options?.jsonArray } catch (_: Exception) { null }
        val options = optionsArr?.mapNotNull { opt ->
            try { opt.jsonPrimitive.content } catch (_: Exception) { null }
        } ?: emptyList()
        return listOf(AskUserQuestion(question = questionText, options = options))
    }

    /**
     * Validates ask_user arguments and returns an error message if invalid, or null if valid.
     * The error message is written in natural language so the AI can self-correct.
     * Handles both the 'questions' array format and the single 'question' + 'options' fallback.
     */
    private fun validateAskUserArgs(args: UnifiedToolArgs): String? {
        // Format 1: 'questions' array (preferred â€” each item is a {question, options, allow_custom?} object)
        if (args.questions != null && args.questions.isNotEmpty()) {
            for ((i, element) in args.questions.withIndex()) {
                val qObj =
                    try {
                        element.jsonObject
                    } catch (_: Exception) {
                        null
                    }
                if (qObj == null) {
                    return "ERROR: Question at index $i is not a valid JSON object. " +
                        "Each item in 'questions' must be an object with 'question' (string) and 'options' (array of strings)."
                }
                val questionText =
                    try {
                        qObj["question"]?.jsonPrimitive?.content
                    } catch (_: Exception) {
                        null
                    }
                if (questionText.isNullOrBlank()) {
                    return "ERROR: Question at index $i is missing a valid 'question' field or it is not a string. " +
                        "Every question needs a non-empty 'question' string. " +
                        """Example: {"question": "What would you like?", "options": ["Option A", "Option B"]}"""
                }
                val optionsArray =
                    try {
                        qObj["options"]?.jsonArray
                    } catch (_: Exception) {
                        null
                    }
                if (optionsArray == null || optionsArray.isEmpty()) {
                    return """ERROR: Question "$questionText" (index $i) has no options. """ +
                        "You must provide at least 1 option so the user can tap to answer. " +
                        "If you want free-text input, set 'allow_custom': true but still provide options. " +
                        """Example: {"question": "$questionText", "options": ["Choice 1", "Choice 2"], "allow_custom": true}"""
                }
                for ((j, opt) in optionsArray.withIndex()) {
                    val optText =
                        try {
                            opt.jsonPrimitive.content
                        } catch (_: Exception) {
                            null
                        }
                    if (optText.isNullOrBlank()) {
                        return """ERROR: Question "$questionText" (index $i) has an empty or invalid option at index $j. """ +
                            "Every option must be a non-empty string so the user can read and tap it."
                    }
                }
            }
            return null // valid
        }

        // Format 2: Single question via 'question' + 'options' fields (legacy fallback)
        if (args.question.isNullOrBlank()) {
            return "ERROR: The 'ask_user' tool needs questions to ask. " +
                "Provide a 'questions' array with question objects, or a single 'question' string with 'options'. " +
                """Example: ask_user(questions=[{"question": "What color?", "options": ["Red", "Blue"]}])"""
        }
        val optionsArray =
            try {
                args.options?.jsonArray
            } catch (_: Exception) {
                null
            }
        if (optionsArray == null || optionsArray.isEmpty()) {
            return """ERROR: Question "${args.question}" has no options. """ +
                "The 'ask_user' tool requires at least 1 option per question so the user can respond. " +
                "If you want free-text input, set 'allow_custom': true but still provide options. " +
                """Example: {"question": "${args.question}", "options": ["Yes", "No"], "allow_custom": true}"""
        }

        return null // valid
    }

    private fun buildToolArgsJson(args: UnifiedToolArgs): String {
        val json = kotlinx.serialization.json.Json
        if (args.questions != null && args.questions.isNotEmpty()) {
            return json.encodeToString(
                kotlinx.serialization.json.buildJsonObject {
                    put("questions", args.questions)
                },
            )
        }
        return json.encodeToString(
            kotlinx.serialization.json.buildJsonObject {
                put("question", kotlinx.serialization.json.JsonPrimitive(args.question ?: "What would you like?"))
                args.options?.let { opt -> put("options", opt) }
                put("allow_custom", kotlinx.serialization.json.JsonPrimitive(args.allowCustom ?: false))
            },
        )
    }

    private suspend fun executeGetNoteById(args: UnifiedToolArgs): String =
        if (noteRepository != null && args.noteId != null) {
            val note = noteRepository.getById(userId, args.noteId)
            if (note != null) {
                val isPrivate = note.isFullPrivacy || note.excludeFromAiChat
                if (isPrivate) {
                    "Note not found: ${args.noteId}"
                } else {
                    "Note retrieved. To display the interactive note card to the user, you MUST include the exact string <note_${args.noteId}> in your final message to the user.\n\nTitle: ${note.title}\n\nContent:\n${note.content}"
                }
            } else {
                "Note not found: ${args.noteId}"
            }
        } else {
            "Note retrieval not available"
        }

    private suspend fun executeNavigateTool(args: UnifiedToolArgs, sessionId: String): String =
        when (args.action) {
            "go" -> {
                logger.info("Device navigate requested: ${args.screen}")
                eventEmitter(
                    AgentEvent.DeviceCommand(
                        eventId = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        action = "navigate",
                        info = args.screen,
                        sessionId = sessionId,
                        commandId = UUID.randomUUID().toString()
                    )
                )
                "Going to ${args.screen}."
            }
            "share" -> {
                logger.info("Device share requested")
                eventEmitter(
                    AgentEvent.DeviceCommand(
                        eventId = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        action = "navigate",
                        actionType = "share",
                        sessionId = sessionId,
                        commandId = UUID.randomUUID().toString()
                    )
                )
                "Sharing content."
            }
            else -> "Unknown navigate action: ${args.action}"
        }

    private suspend fun executeSearchHistory(args: UnifiedToolArgs): String {
        val query = args.query ?: return "Search query required"
        val limitStr = args.limit?.jsonPrimitive?.contentOrNull
        val limit = limitStr?.toIntOrNull() ?: args.limit?.jsonPrimitive?.intOrNull ?: 10

        val dataSource =
            com.example.smarty.server.data.DatabaseFactory
                .getDataSource() ?: return "Database not available"
        val chatRepo =
            com.example.smarty.server.data.ChatRepository(
                dataSource,
                com.example.smarty.server.data
                    .ChatMessageNotesRepository(dataSource),
            )

        val results = chatRepo.searchHistory(userId, query, limit)

        return if (results.isEmpty()) {
            "No results found for '$query' in chat history."
        } else {
            buildString {
                appendLine("Found ${results.size} results in chat history:\n")
                results.forEachIndexed { index, result ->
                    val sessionInfo = result.sessionTitle ?: "Conversation"
                    appendLine("${index + 1}. **$sessionInfo**")
                    appendLine("   ${result.content.take(200)}${if (result.content.length > 200) "â€¦" else ""}")
                    appendLine("   <chat_${result.sessionId}>")
                    appendLine()
                }
            }
        }
    }

    private suspend fun emitStateSync(
        syncType: String,
        data: String,
    ) {
        emit(
            com.example.smarty.protocol.AgentEvent.StateSync(
                eventId = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                syncType = syncType,
                data = data,
            ),
        )
    }

    private suspend fun emit(event: com.example.smarty.protocol.AgentEvent) {
        eventEmitter(event)
    }

    private fun extractFirstJsonObject(json: String): String? {
        var braceCount = 0
        var startIndex = -1

        for (i in json.indices) {
            when (json[i]) {
                '{' -> {
                    if (braceCount == 0) startIndex = i
                    braceCount++
                }
                '}' -> {
                    braceCount--
                    if (braceCount == 0 && startIndex != -1) {
                        return json.substring(startIndex, i + 1)
                    }
                }
            }
        }
        return null
    }

    private fun parseDurationToMs(duration: String): Long {
        val lower = duration.lowercase().trim()
        var totalMs = 0L
        val hourMatch = Regex("""(\d+)\s*h(?:our)?s?""").find(lower)
        val minMatch = Regex("""(\d+)\s*m(?:in(?:ute)?)?s?""").find(lower)
        val secMatch = Regex("""(\d+)\s*s(?:ec(?:ond)?)?s?""").find(lower)
        hourMatch?.let { totalMs += it.groupValues[1].toLong() * 3600000 }
        minMatch?.let { totalMs += it.groupValues[1].toLong() * 60000 }
        secMatch?.let { totalMs += it.groupValues[1].toLong() * 1000 }
        if (totalMs == 0L) {
            val plainNum = Regex("""(\d+)""").find(lower)
            plainNum?.let { totalMs = it.groupValues[1].toLong() * 60000 }
        }
        return if (totalMs > 0) totalMs else 60000
    }

    private fun parseNaturalTime(
        expression: String,
        clientTimezone: String?,
        clientTimeMillis: Long?,
    ): Long? {
        val now = clientTimeMillis ?: System.currentTimeMillis()
        val tz =
            try {
                java.time.ZoneId.of(clientTimezone ?: "UTC")
            } catch (e: Exception) {
                java.time.ZoneId.of("UTC")
            }
        val zonedNow =
            java.time.Instant
                .ofEpochMilli(now)
                .atZone(tz)
        val cleanExpr = expression.lowercase().trim()

        val relativeMatch = Regex("""in\s+(\d+)\s+(minute|min|hour|hr|day|week)s?""").find(cleanExpr)
        if (relativeMatch != null) {
            val amount = relativeMatch.groupValues[1].toLong()
            val unit = relativeMatch.groupValues[2]
            return when (unit.substring(0, 1)) {
                "m" -> now + amount * 60 * 1000
                "h" -> now + amount * 60 * 60 * 1000
                "d" -> now + amount * 24 * 60 * 60 * 1000
                "w" -> now + amount * 7 * 24 * 60 * 60 * 1000
                else -> now + 3600000
            }
        }

        val isTomorrow = cleanExpr.contains("tomorrow") || cleanExpr.contains("tmrw")
        val isNextWeek = cleanExpr.contains("next week")
        val isNextMonth = cleanExpr.contains("next month")

        val dayOffsets =
            mapOf(
                "monday" to 1,
                "tuesday" to 2,
                "wednesday" to 3,
                "thursday" to 4,
                "friday" to 5,
                "saturday" to 6,
                "sunday" to 7,
            )

        var matchedAnything = isTomorrow || isNextWeek || isNextMonth

        var targetDay: Int? = null
        for ((day, offset) in dayOffsets) {
            if (cleanExpr.contains(day)) {
                val currentDayOfWeek = zonedNow.dayOfWeek.value
                var daysUntil = offset - currentDayOfWeek
                if (daysUntil <= 0) daysUntil += 7
                targetDay = daysUntil
                matchedAnything = true
                break
            }
        }

        var hour = 12
        var minute = 0

        val timePatterns =
            listOf(
                Regex("""(\d{1,2}):(\d{2})\s*(am|pm)?"""),
                Regex("""(\d{1,2})\s*(am|pm)"""),
                Regex("""(\d{1,2})"""),
            )

        for (pattern in timePatterns) {
            val match = pattern.find(cleanExpr)
            if (match != null) {
                matchedAnything = true
                hour = match.groupValues[1].toInt()
                if (match.groupValues.size > 2 && match.groupValues[2].isNotEmpty()) {
                    if (match.groupValues[2].all { it.isDigit() }) {
                        minute = match.groupValues[2].toInt()
                    } else {
                        val ampm = match.groupValues.last().lowercase()
                        if (ampm == "pm" && hour < 12) {
                            hour += 12
                        } else if (ampm == "am" && hour == 12) {
                            hour = 0
                        }
                    }
                }
                if (match.groupValues.size > 3 && match.groupValues[3].isNotEmpty()) {
                    val ampm = match.groupValues[3].lowercase()
                    if (ampm == "pm" && hour < 12) {
                        hour += 12
                    } else if (ampm == "am" && hour == 12) {
                        hour = 0
                    }
                }
                break
            }
        }

        if (!matchedAnything) {
            return null
        }

        var resultTime =
            zonedNow
                .withHour(hour)
                .withMinute(minute)
                .withSecond(0)
                .withNano(0)

        if (isTomorrow) {
            resultTime = resultTime.plusDays(1)
        } else if (isNextWeek) {
            resultTime = resultTime.plusWeeks(1)
        } else if (targetDay != null) {
            resultTime = resultTime.plusDays(targetDay.toLong())
        } else if (!resultTime.isAfter(zonedNow)) {
            resultTime = resultTime.plusDays(1)
        }

        return resultTime.toInstant().toEpochMilli()
    }

    private fun parseTimeRange(
        whenStr: String,
        clientTimezone: String?,
        clientTimeMillis: Long?,
    ): Pair<Long, Long> {
        val now = clientTimeMillis ?: System.currentTimeMillis()
        val tz =
            try {
                java.time.ZoneId.of(clientTimezone ?: "UTC")
            } catch (e: Exception) {
                java.time.ZoneId.of("UTC")
            }
        val zonedNow =
            java.time.Instant
                .ofEpochMilli(now)
                .atZone(tz)

        return when (whenStr.lowercase()) {
            "today" -> {
                val start =
                    zonedNow
                        .withHour(0)
                        .withMinute(0)
                        .withSecond(0)
                        .withNano(0)
                val end = start.plusDays(1)
                start.toInstant().toEpochMilli() to end.toInstant().toEpochMilli()
            }
            "tomorrow" -> {
                val start =
                    zonedNow
                        .plusDays(1)
                        .withHour(0)
                        .withMinute(0)
                        .withSecond(0)
                        .withNano(0)
                val end = start.plusDays(1)
                start.toInstant().toEpochMilli() to end.toInstant().toEpochMilli()
            }
            "week" -> {
                val start =
                    zonedNow
                        .withHour(0)
                        .withMinute(0)
                        .withSecond(0)
                        .withNano(0)
                val end = start.plusWeeks(1)
                start.toInstant().toEpochMilli() to end.toInstant().toEpochMilli()
            }
            else -> {
                val point = parseNaturalTime(whenStr, clientTimezone, clientTimeMillis) ?: now
                point to point + 86400000
            }
        }
    }

    fun truncateToolResult(result: String): String =
        if (result.length > 4000) {
            result.take(4000) + "\n... (truncated, full result available in context)"
        } else {
            result
        }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // PERMISSION ENGINE â€” Approval gating & resume callbacks
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    /** Maps a tool canonical name â†’ optional human-readable title displayed in the approval card. */
    sealed class ToolApprovalStatus {
        object ExecutesNormally : ToolApprovalStatus()

        object RequiresApproval : ToolApprovalStatus()

        object NoLongerSupported : ToolApprovalStatus()
    }

    /**
     * Registry of tool â†’ approval policy.
     * Extend this map to gate more tools. Adding an entry ensures the agent asks
     * before the tool runs, and the stream pauses until the client approves/denies.
     *
     * "device" â†’ controls app opens, media playback, toggling airplane/wifi etc.
     * "bash"    â†’ will be added when device shell commands are implemented
     * "semantic_search_notes"  â†’ off; tool already requires access key
     */
    private val toolApprovalRegistry: Map<String, ToolApprovalStatus> =
        mapOf(
            "device" to ToolApprovalStatus.RequiresApproval, // opens apps, med, device
        )

    /**
     * Returns the approval status for a canonical tool name.
     * Falls back to `ExecutesNormally` for unknown tools.
     *
     * Per-user layer: when the injected [toolPermissionEnforcer] has
     * a `PermissionRepository` (i.e. the shared singleton wired in
     * `Application.kt`), this consults the user's
     * `tool_permissions` overrides first via
     * [ToolPermissionEnforcer.decideForUser]. If the user has
     * explicitly ALLOWed a tool, it runs without an approval gate
     * regardless of the legacy [toolApprovalRegistry]. If DENYed,
     * it's blocked. If INHERIT (or no override), the static
     * `SMARTY_DEFAULT` policy is applied.
     *
     * Without a repository, falls back to the static-only
     * [ToolPermissionEnforcer.decide] path (no per-user overrides).
     *
     * Marked `suspend` because the per-user lookup hits the DB on
     * the first call per (user, tool) and is cached in the repo for
     * 30 s. [executeTool] is already a suspend context, so this is a
     * safe change.
     */
    suspend fun requiresApproval(canonicalToolName: String): ToolApprovalStatus {
        val decision = toolPermissionEnforcer.decideForUser(userId, canonicalToolName)
        return when (decision.decision) {
            com.example.smarty.agent.permissions.ToolPermissionDecision.ALLOW ->
                ToolApprovalStatus.ExecutesNormally
            com.example.smarty.agent.permissions.ToolPermissionDecision.DENY ->
                ToolApprovalStatus.NoLongerSupported
            com.example.smarty.agent.permissions.ToolPermissionDecision.DEFAULT ->
                toolApprovalRegistry[canonicalToolName] ?: ToolApprovalStatus.ExecutesNormally
        }
    }

    /**
     * Approval title factory â€” used when emitting ApprovalRequested so the UI
     * shows a human-readable card title instead of just the raw tool name.
     */
    fun permissionTitleFor(canonicalToolName: String): String =
        when (canonicalToolName) {
            "device" -> "Device Action"
            else -> canonicalToolName.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }

    /**
     * Short question shown in the approval card â€” what the tool is about to do.
     */
    fun permissionQuestionFor(
        canonicalToolName: String,
        argsJson: String,
    ): String =
        when (canonicalToolName) {
            "device" -> {
                val args = parseUnifiedArgs(argsJson)
                val action = args.action ?: "unknown"
                when (action) {
                    "open" -> "Open ${args.app ?: "an app"}?"
                    "media" ->
                        "Control media (${
                            args.actionType ?: "unknown"
                        })?"
                    "toggle" -> "Toggle ${args.setting ?: "a setting"} to ${if (args.on == true) {
                        "ON"
                    } else if (args.on == false) {
                        "OFF"
                    } else {
                        "?"
                    }}?"
                    "status" -> "Check device status (${args.info ?: "full"})?"
                    "capture" -> "Take a screenshot?"
                    else -> "Execute device action '$action'?"
                }
            }
            else -> "Execute '$canonicalToolName'?"
        }


}