package com.example.smarty.server.agent

import com.example.smarty.protocol.AgentCommand
import com.example.smarty.server.data.CalendarRepository
import com.example.smarty.server.data.GeneratedImageRepository
import com.example.smarty.server.data.NoteRepository
import com.example.smarty.server.data.PostgresVectorStore
import com.example.smarty.server.data.TimerRepository
import com.example.smarty.server.llm.LlmMessage
import com.example.smarty.server.tools.KreaImageTool
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.util.UUID

/**
// === PERMISSION ENGINE: Tools that require user approval ===
// These tools will be paused before execution and sent as ApprovalRequested events.
// In OpenCode terms, these are the MCP tools that need human sanction.
// Agent asks "May I?" → stream pauses → user approves/denies → stream resumes.
// Permission list lives here; add entries to grow gate coverage.
// In the App layer, ChatViewModel.callApproval() sends back the user decision.

sealed class ToolExecutionResult {
 data class Completed(val result: String) : ToolExecutionResult()
 data object RequiresApproval : ToolExecutionResult()
 data object Denied : ToolExecutionResult()
}
// ════════════════════════════════════════════════════════════════════════════════

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
) {
    private val logger = LoggerFactory.getLogger(ToolExecutor::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val generatedImageRepository =
        com.example.smarty.server.data.DatabaseFactory.getDataSource()?.let {
            GeneratedImageRepository(
                it,
            )
        }

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
        val limit: String? = null,
        val finding: String? = null,
        val source: String? = null,
        val url: String? = null,
        val note: String? = null,
        val questions: kotlinx.serialization.json.JsonArray? = null,
        @SerialName("search_depth") val searchDepth: String? = null,
        @SerialName("max_results") val maxResults: Int? = null,
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
                    val approvalEvent =
                        com.example.smarty.protocol.AgentEvent.ApprovalRequested(
                            eventId =
                                java.util.UUID
                                    .randomUUID()
                                    .toString(),
                            timestamp = System.currentTimeMillis(),
                            toolId =
                                java.util.UUID
                                    .randomUUID()
                                    .toString(),
                            toolName = toolName,
                            toolTitle = permissionTitleFor(toolName),
                            toolArgs = argsJson.take(200),
                        )
                    emit(approvalEvent)
                    logger.info("[ToolExecutor] Approval required for $toolName (resolved from $name), emitted ApprovalRequested")
                    return "__WAITING_FOR_USER_RESPONSE__"
                }
                ToolApprovalStatus.NoLongerSupported -> {
                    return "Tool $toolName is no longer supported."
                }
                ToolApprovalStatus.ExecutesNormally -> { }
            }
        }

        return when (toolName) {
            "memory_save" -> executeMemorySave(args)
            "memory_find" -> executeMemoryFind(args)
            "memory_update" -> executeMemoryUpdate(args)
            "memory_delete" -> executeMemoryDelete(args)
            "memory_remember" -> executeMemoryRemember(args)
            "memory" -> executeMemoryTool(args)
            "save_progress" -> executeSaveProgress(args)
            "read_progress" -> executeReadProgress(args)
            "schedule" -> executeScheduleTool(args, clientTimezone, clientTimeMillis)
            "remind" -> executeRemindTool(args, clientTimezone, clientTimeMillis)
            "device" -> executeDeviceTool(args, sessionId)
            "ask_user" -> executeAskUser(args, toolCallId, sessionId)
            "get_note_by_id" -> executeGetNoteById(args)
            "navigate" -> executeNavigateTool(args)
            "search_history" -> executeSearchHistory(args)
            "guided_breathing" -> executeGuidedBreathing()
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

            emitProcessing("", "Generating image with prompt: ${imageArgs.prompt.take(100)}...")

            val jobId = kreaTool.generateImage(imageArgs.prompt, imageArgs.aspectRatio ?: "1:1")

            generatedImageRepository?.create(
                userId = userId,
                sessionId = null,
                prompt = imageArgs.prompt,
                kreaJobId = jobId,
            )

            emitProcessing("Image generation in progress...", "Polling Krea API for job $jobId")

            val result = kreaTool.waitForCompletion(jobId)

            val kreaImageUrl = result.result?.urls?.firstOrNull()
            if (kreaImageUrl.isNullOrBlank()) {
                throw IllegalStateException("Image generation completed but no image URL was returned")
            }

            emitProcessing("Image generated successfully from Krea!", "Krea Image URL: $kreaImageUrl")

            var supabaseUrl: String? = null
            try {
                emitProcessing("Uploading image to permanent storage...", "Uploading to Supabase Storage")

                supabaseUrl =
                    kreaTool.uploadToSupabase(
                        imageUrl = kreaImageUrl,
                        jobId = jobId,
                        bucketName =
                            com.example.smarty.server.factory.SupabaseClientFactory
                                .getImageBucketName(),
                    )

                emitProcessing("Image uploaded to permanent storage!", "Supabase URL: $supabaseUrl")
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

            emitProcessing("Image generation completed!", "Final URL ($imageSource): $finalImageUrl")

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
                "share_content", "share" -> "navigate_share"
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
            emitDeviceCommand(
                AgentCommand.AddNote(
                    commandId = UUID.randomUUID().toString(),
                    content = "${args.title}\n\n${args.content}",
                    category = args.category,
                ),
            )
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
            emitDeviceCommand(
                AgentCommand.SearchNotes(
                    commandId = UUID.randomUUID().toString(),
                    query = args.query ?: "",
                    category = args.category,
                ),
            )
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
            emitDeviceCommand(
                AgentCommand.UpdateNote(
                    commandId = UUID.randomUUID().toString(),
                    noteId = args.id ?: "",
                    title = args.title,
                    content = args.content,
                ),
            )
            "Update sent to device."
        }
    }

    private suspend fun executeMemoryDelete(args: UnifiedToolArgs): String =
        if (noteRepository != null && args.id != null) {
            noteRepository.delete(userId, args.id)
            emitStateSync("note_deleted", """{"id":"${args.id}"}""")
            "Deleted note ${args.id}"
        } else {
            emitDeviceCommand(
                AgentCommand.DeleteNote(
                    commandId = UUID.randomUUID().toString(),
                    noteId = args.id ?: "",
                ),
            )
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
                    emitDeviceCommand(
                        AgentCommand.ScheduleEvent(
                            commandId = UUID.randomUUID().toString(),
                            title = args.title ?: "",
                            startTime = startTime,
                            endTime = endTime,
                            description = args.description,
                            reminderMinutes = 15,
                        ),
                    )
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
                    emitDeviceCommand(
                        AgentCommand.ListEvents(
                            commandId = UUID.randomUUID().toString(),
                            date = startMs,
                        ),
                    )
                    "Requesting events from device."
                }
            }
            "remove" -> {
                if (calendarRepository != null && args.id != null) {
                    calendarRepository.delete(userId, args.id)
                    emitStateSync("event_deleted", """{"id":"${args.id}"}""")
                    "Event removed."
                } else {
                    emitDeviceCommand(
                        AgentCommand.DeleteEvent(
                            commandId = UUID.randomUUID().toString(),
                            eventId = args.id ?: "",
                        ),
                    )
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
                    emitDeviceCommand(
                        AgentCommand.SetTimer(
                            commandId = UUID.randomUUID().toString(),
                            name = args.what ?: "",
                            timeStr = args.`when` ?: "",
                            isAlarm = isAlarm,
                            repeat = args.repeat,
                            triggerTime = triggerTime,
                        ),
                    )
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
                    emitDeviceCommand(
                        AgentCommand.ListTimers(
                            commandId = UUID.randomUUID().toString(),
                        ),
                    )
                    "Listing reminders from device."
                }
            }
            "cancel" -> {
                if (timerRepository != null && args.id != null) {
                    timerRepository.deactivate(userId, args.id)
                    emitStateSync("timer_cancelled", """{"id":"${args.id}"}""")
                    "Reminder cancelled."
                } else {
                    emitDeviceCommand(
                        AgentCommand.CancelTimer(
                            commandId = UUID.randomUUID().toString(),
                            id = args.id ?: "",
                        ),
                    )
                    "Cancel request sent to device."
                }
            }
            else -> "Unknown remind action: ${args.action}"
        }
    }

    private suspend fun executeDeviceTool(args: UnifiedToolArgs, sessionId: String): String {
        return when (args.action) {
            "open" -> {
                emitDeviceCommand(
                    AgentCommand.LaunchApp(
                        commandId = UUID.randomUUID().toString(),
                        packageName = args.app ?: "",
                    ),
                )
                "Opening: ${args.app}"
            }
            "media" -> {
                emitDeviceCommand(
                    AgentCommand.ControlAudio(
                        commandId = UUID.randomUUID().toString(),
                        action = args.actionType ?: "play",
                    ),
                )
                "Media: ${args.actionType}"
            }
            "toggle" -> {
                if (args.setting == "flashlight" && capabilities?.hardware?.flashlight == false) {
                    return "Device does not have a flashlight."
                }
                emitDeviceCommand(
                    AgentCommand.ToggleSetting(
                        commandId = UUID.randomUUID().toString(),
                        setting = args.setting ?: "",
                        enable = args.on ?: false,
                    ),
                )
                val statusStr =
                    when (args.on) {
                        true -> "on"
                        false -> "off"
                        null -> "toggle request sent"
                    }
                "${args.setting} $statusStr"
            }
            "status" -> {
                val commandId = java.util.UUID.randomUUID().toString()
                emitDeviceCommand(
                    AgentCommand.GetDeviceInfo(
                        commandId = commandId,
                        infoType = args.info ?: "all",
                    ),
                )
                // Wait for client to respond with device status
                val deferred = DeviceResponseRegistry.createPendingRequest(commandId, sessionId)
                val result = runCatching {
                    kotlinx.coroutines.withTimeoutOrNull(15_000L) { deferred.await() }
                }.getOrNull()
                if (result != null && result.status.isNotEmpty()) {
                    result.status.entries.joinToString(", ") { "${it.key}: ${it.value}" }
                } else {
                    "Device status unavailable"
                }
            }
            "capture" -> {
                if (capabilities?.hardware?.screenCapture == false) {
                    return "Device does not support screen capture."
                }
                emitDeviceCommand(
                    AgentCommand.TakeScreenshot(
                        commandId = UUID.randomUUID().toString(),
                    ),
                )
                "Capturing screenshot."
            }
            else -> "Unknown device action: ${args.action}"
        }
    }

    private suspend fun executeGuidedBreathing(): String {
        emitDeviceCommand(
            AgentCommand.ShowBreathing(
                commandId = UUID.randomUUID().toString(),
            ),
        )
        return "Starting guided breathing session."
    }

    private suspend fun executeAskUser(
        args: UnifiedToolArgs,
        toolCallId: String,
        sessionId: String,
    ): String {
        logger.info("[ToolExecutor] ask_user called with questions=${args.questions?.size}, question=${args.question?.take(100)}, options=${args.options?.toString()?.take(100)}")

        // Validate arguments BEFORE emitting ApprovalRequested — return error string if malformed
        val validationError = validateAskUserArgs(args)
        if (validationError != null) {
            logger.warn("[ToolExecutor] ask_user validation failed for toolCallId=$toolCallId: ${validationError.take(200)}")
            return validationError
        }

        // Build toolArgs JSON that the app expects (questions array format)
        val toolArgsJson = buildToolArgsJson(args)

        // Emit ApprovalRequested event (same as McpServer does)
        emit(
            com.example.smarty.protocol.AgentEvent.ApprovalRequested(
                eventId = java.util.UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                toolId = toolCallId,
                toolName = "ask_user",
                toolTitle = "Clarification Needed",
                toolArgs = toolArgsJson,
            ),
        )

        // Create pending approval and suspend until user responds (or timeout)
        // 161s timeout — golden ratio (φ ≈ 1.618) × 100 ≈ 161s
        val result =
            runCatching {
                kotlinx.coroutines.withTimeoutOrNull(161_000L) {
                    ApprovalRegistry.createPendingApproval(toolCallId, sessionId, userId).await()
                }
            }.getOrNull() ?: ApprovalResult(false, "Approval timed out")

        // If the approval timed out without user interaction, tell the client to dismiss
        if (result.feedback == "Approval timed out") {
            emit(
                com.example.smarty.protocol.AgentEvent.ApprovalDenied(
                    eventId = java.util.UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    toolId = toolCallId,
                ),
            )
        }

        return if (result.approved) {
            result.feedback ?: "Proceed with your best judgment"
        } else {
            result.feedback ?: "User denied"
        }
    }

    /**
     * Validates ask_user arguments and returns an error message if invalid, or null if valid.
     * The error message is written in natural language so the AI can self-correct.
     * Handles both the 'questions' array format and the single 'question' + 'options' fallback.
     */
    private fun validateAskUserArgs(args: UnifiedToolArgs): String? {
        // Format 1: 'questions' array (preferred — each item is a {question, options, allow_custom?} object)
        if (args.questions != null && args.questions.isNotEmpty()) {
            for ((i, element) in args.questions.withIndex()) {
                val qObj = try { element.jsonObject } catch (_: Exception) { null }
                if (qObj == null) {
                    return "ERROR: Question at index $i is not a valid JSON object. " +
                        "Each item in 'questions' must be an object with 'question' (string) and 'options' (array of strings)."
                }
                val questionText = try { qObj["question"]?.jsonPrimitive?.content } catch (_: Exception) { null }
                if (questionText.isNullOrBlank()) {
                    return "ERROR: Question at index $i is missing a valid 'question' field or it is not a string. " +
                        "Every question needs a non-empty 'question' string. " +
                        """Example: {"question": "What would you like?", "options": ["Option A", "Option B"]}"""
                }
                val optionsArray = try { qObj["options"]?.jsonArray } catch (_: Exception) { null }
                if (optionsArray == null || optionsArray.isEmpty()) {
                    return """ERROR: Question "$questionText" (index $i) has no options. """ +
                        "You must provide at least 1 option so the user can tap to answer. " +
                        "If you want free-text input, set 'allow_custom': true but still provide options. " +
                        """Example: {"question": "$questionText", "options": ["Choice 1", "Choice 2"], "allow_custom": true}"""
                }
                for ((j, opt) in optionsArray.withIndex()) {
                    val optText = try { opt.jsonPrimitive.content } catch (_: Exception) { null }
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
        val optionsArray = try { args.options?.jsonArray } catch (_: Exception) { null }
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
                    emit(
                        com.example.smarty.protocol.AgentEvent.NoteBlock(
                            eventId = UUID.randomUUID().toString(),
                            timestamp = System.currentTimeMillis(),
                            noteId = note.id,
                            title = note.title,
                            snippet = args.snippet ?: note.content.take(100),
                            category = note.categoryId,
                        ),
                    )
                    "Note: ${note.title}"
                }
            } else {
                "Note not found: ${args.noteId}"
            }
        } else {
            "Note retrieval not available"
        }

    private suspend fun executeNavigateTool(args: UnifiedToolArgs): String =
        when (args.action) {
            "go" -> {
                emitDeviceCommand(
                    AgentCommand.Navigate(
                        commandId = UUID.randomUUID().toString(),
                        screen = args.screen ?: "home",
                    ),
                )
                "Going to ${args.screen}."
            }
            "share" -> {
                emitDeviceCommand(
                    AgentCommand.Share(
                        commandId = UUID.randomUUID().toString(),
                        content = args.content ?: "",
                        title = args.title,
                    ),
                )
                "Sharing content."
            }
            else -> "Unknown navigate action: ${args.action}"
        }

    private suspend fun executeSearchHistory(args: UnifiedToolArgs): String {
        val query = args.query ?: return "Search query required"
        val limit = args.limit?.toIntOrNull() ?: 10

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
                    appendLine("   ${result.content.take(200)}${if (result.content.length > 200) "…" else ""}")
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

    private suspend fun emitDeviceCommand(command: AgentCommand) {
        emit(
            com.example.smarty.protocol.AgentEvent.Command(
                eventId = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                command = command,
            ),
        )
    }

    private suspend fun emitProcessing(
        content: String,
        thinking: String,
    ) {
        emit(
            com.example.smarty.protocol.AgentEvent.Processing(
                eventId = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                content = content,
                thinking = thinking,
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

    // ═══════════════════════════════════════════════════════════════════════════
    // PERMISSION ENGINE — Approval gating & resume callbacks
    // ═══════════════════════════════════════════════════════════════════════════

    /** Maps a tool canonical name → optional human-readable title displayed in the approval card. */
    sealed class ToolApprovalStatus {
        object ExecutesNormally : ToolApprovalStatus()

        object RequiresApproval : ToolApprovalStatus()

        object NoLongerSupported : ToolApprovalStatus()
    }

    /**
     * Registry of tool → approval policy.
     * Extend this map to gate more tools. Adding an entry ensures the agent asks
     * before the tool runs, and the stream pauses until the client approves/denies.
     *
     * "device" → controls app opens, media playback, toggling airplane/wifi etc.
     * "bash"    → will be added when device shell commands are implemented
     * "semantic_search_notes"  → off; tool already requires access key
     */
    private val toolApprovalRegistry: Map<String, ToolApprovalStatus> =
        mapOf(
            "device" to ToolApprovalStatus.RequiresApproval, // opens apps, med, device
        )

    /**
     * Returns the approval status for a canonical tool name.
     * Falls back to `ExecutesNormally` for unknown tools.
     */
    fun requiresApproval(canonicalToolName: String): ToolApprovalStatus =
        toolApprovalRegistry[canonicalToolName] ?: ToolApprovalStatus.ExecutesNormally

    /**
     * Approval title factory — used when emitting ApprovalRequested so the UI
     * shows a human-readable card title instead of just the raw tool name.
     */
    fun permissionTitleFor(canonicalToolName: String): String =
        when (canonicalToolName) {
            "device" -> "Device Action"
            else -> canonicalToolName.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }

    /**
     * Short question shown in the approval card — what the tool is about to do.
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

    private fun getProgressFile(): java.io.File = java.io.File(System.getProperty("java.io.tmpdir"), "research_progress_$userId.json")

    private fun executeSaveProgress(args: UnifiedToolArgs): String {
        val finding = args.finding ?: args.content ?: args.note ?: return "Error: missing 'finding'"
        val source = args.source ?: args.url ?: "unknown source"
        val category = args.category ?: "general"

        val file = getProgressFile()
        val findingsList =
            if (file.exists()) {
                try {
                    json.decodeFromString<List<Map<String, String>>>(file.readText())
                } catch (e: Exception) {
                    emptyList()
                }
            } else {
                emptyList()
            }

        val newFinding =
            mapOf(
                "timestamp" to
                    java.time.Instant
                        .now()
                        .toString(),
                "finding" to finding,
                "source" to source,
                "category" to category,
            )

        val updatedList = findingsList + newFinding
        file.writeText(json.encodeToString(updatedList))

        return "Progress saved successfully. Finding added to category '$category'."
    }

    private fun executeReadProgress(args: UnifiedToolArgs): String {
        val categoryFilter = args.category

        val file = getProgressFile()
        if (!file.exists()) {
            return "No research progress saved yet."
        }

        val findingsList =
            try {
                json.decodeFromString<List<Map<String, String>>>(file.readText())
            } catch (e: Exception) {
                return "Error reading progress file: ${e.message}"
            }

        if (findingsList.isEmpty()) {
            return "Research progress is empty."
        }

        val filteredList =
            if (categoryFilter == null || categoryFilter.isBlank()) {
                findingsList
            } else {
                findingsList.filter { it["category"]?.equals(categoryFilter, ignoreCase = true) == true }
            }

        if (filteredList.isEmpty()) {
            return "No findings found in category '$categoryFilter'."
        }

        val sb = StringBuilder()
        sb.append("Research Progress (Category: ${categoryFilter ?: "All"}):\n\n")
        for ((index, item) in filteredList.withIndex()) {
            sb.append("${index + 1}. [${item["category"]}] ${item["finding"]}\n")
            sb.append("   Source: ${item["source"]}\n")
            sb.append("   Time: ${item["timestamp"]}\n\n")
        }

        return sb.toString()
    }
}
