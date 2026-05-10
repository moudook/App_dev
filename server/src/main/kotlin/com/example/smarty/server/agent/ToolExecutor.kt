package com.example.smarty.server.agent

import com.example.smarty.protocol.AgentCommand
import com.example.smarty.server.data.CalendarRepository
import com.example.smarty.server.data.GeneratedImageRepository
import com.example.smarty.server.data.NoteRepository
import com.example.smarty.server.data.PostgresVectorStore
import com.example.smarty.server.data.TimerRepository
import com.example.smarty.server.llm.LlmMessage
import com.example.smarty.server.tools.KreaImageTool
import com.example.smarty.server.tools.TavilySearchTool
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Extracted tool execution logic from ServerAgent.kt
 * Handles all tool execution, parameter parsing, and result formatting
 */
class ToolExecutor(
    private val userId: String,
    private val llmProvider: com.example.smarty.server.llm.LlmProvider,
    private val tavilyTool: TavilySearchTool,
    private val vectorStore: PostgresVectorStore,
    private val noteRepository: NoteRepository?,
    private val timerRepository: TimerRepository?,
    private val calendarRepository: CalendarRepository?,
    private val eventEmitter: suspend (com.example.smarty.protocol.AgentEvent) -> Unit,
    private val noteService: com.example.smarty.server.services.NoteService? = null,
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
        val noteId: String? = null,
        val snippet: String? = null,
        val limit: String? = null,
    )

    suspend fun executeTool(
        name: String,
        argsJson: String,
        history: List<LlmMessage>,
        clientTimezone: String? = null,
        clientTimeMillis: Long? = null,
    ): String {
        logger.info("Executing tool: $name with args: $argsJson")

        if (name == "generate_image") {
            return executeGenerateImage(argsJson)
        }

        val args = parseUnifiedArgs(argsJson)
        val toolName = mapOldToolNames(name)

        return when (toolName) {
            "memory_save" -> executeMemorySave(args)
            "memory_find" -> executeMemoryFind(args)
            "memory_update" -> executeMemoryUpdate(args)
            "memory_delete" -> executeMemoryDelete(args)
            "memory_remember" -> executeMemoryRemember(args)
            "memory" -> executeMemoryTool(args)
            "schedule" -> executeScheduleTool(args, clientTimezone, clientTimeMillis)
            "remind" -> executeRemindTool(args, clientTimezone, clientTimeMillis)
            "device" -> executeDeviceTool(args)
            "search" -> executeSearchTool(args)
            "ask_user" -> executeAskUser(args)
            "get_note_by_id" -> executeGetNoteById(args)
            "navigate" -> executeNavigateTool(args)
            "search_history" -> executeSearchHistory(args)
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
                        bucketName = com.example.smarty.server.factory.SupabaseClientFactory.getImageBucketName(),
                    )

                if (supabaseUrl != null) {
                    emitProcessing("Image uploaded to permanent storage!", "Supabase URL: $supabaseUrl")
                }
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
            "Failed to generate image: ${e.message}"
        }
    }

    private fun parseUnifiedArgs(argsJson: String): UnifiedToolArgs {
        return try {
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
    }

    private fun mapOldToolNames(name: String): String {
        return when (name) {
            "save_note", "create_note" -> "memory_save"
            "find_note", "search_notes" -> "memory_find"
            "edit_note", "update_note" -> "memory_update"
            "delete_note" -> "memory_delete"
            "remember_fact", "store_context" -> "memory_remember"
            "add_event", "schedule_event" -> "schedule_add"
            "show_events", "list_events" -> "schedule_list"
            "remove_event", "delete_event" -> "schedule_remove"
            "set_reminder" -> "remind_set"
            "open_app", "launch_app" -> "device_open"
            "control_music", "control_media" -> "device_media"
            "toggle_setting" -> "device_toggle"
            "get_device_info" -> "device_status"
            "take_screenshot" -> "device_capture"
            "search_web", "web_search" -> "search_web"
            "go_to_screen" -> "navigate_go"
            "share_content", "share" -> "navigate_share"
            else -> name
        }
    }

    private suspend fun executeMemorySave(args: UnifiedToolArgs): String {
        return if (noteService != null && args.title != null && args.content != null) {
            val noteId = noteService.createNote(userId, args.title, args.content, args.category)
            emitStateSync("note_created", """{"id":"$noteId","title":"${args.title}"}""")
            "Saved: '${args.title}' (ID: $noteId). AI enrichment started in background."
        } else if (noteRepository != null && args.title != null && args.content != null) {
            val noteInfo = com.example.smarty.protocol.NoteInfo(
                id = "",
                title = args.title,
                content = args.content,
                categoryId = args.category,
                processingStatus = "COMPLETED",
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
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
    }

    private suspend fun executeMemoryFind(args: UnifiedToolArgs): String {
        return if (noteService != null && args.query != null) {
            val results = noteService.searchNotes(userId, args.query)
            if (results.isEmpty()) {
                "No notes found for '${args.query}'."
            } else {
                results.joinToString("\n") { "- [${it.id}] ${it.title}: ${it.summary ?: it.content.take(80)}" }
            }
        } else if (noteRepository != null && args.query != null) {
            val results = noteRepository.listByUser(userId, limit = 100).filter {
                it.title.contains(args.query, ignoreCase = true) || it.content.contains(args.query, ignoreCase = true)
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
    }

    private suspend fun executeMemoryUpdate(args: UnifiedToolArgs): String {
        return if (noteRepository != null && args.id != null) {
            val existing = noteRepository.getById(userId, args.id) ?: return "Note not found: ${args.id}"
            val updatedNote = existing.copy(
                title = args.title ?: existing.title,
                content = args.content ?: existing.content,
                updatedAt = System.currentTimeMillis()
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

    private suspend fun executeMemoryDelete(args: UnifiedToolArgs): String {
        return if (noteRepository != null && args.id != null) {
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
    }

    private suspend fun executeMemoryRemember(args: UnifiedToolArgs): String {
        return try {
            val fact = args.fact ?: args.content ?: ""
            vectorStore.store(userId, fact, mapOf("type" to (args.type ?: "factual")))
            "Remembered: ${fact.take(50)}"
        } catch (e: Exception) {
            "Failed: ${e.message}"
        }
    }

    private suspend fun executeMemoryTool(args: UnifiedToolArgs): String {
        return when (args.action) {
            "save" -> executeMemorySave(args)
            "find" -> executeMemoryFind(args)
            "update" -> executeMemoryUpdate(args)
            "delete" -> executeMemoryDelete(args)
            "remember" -> executeMemoryRemember(args)
            else -> "Unknown memory action: ${args.action}"
        }
    }

    private suspend fun executeScheduleTool(
        args: UnifiedToolArgs,
        clientTimezone: String?,
        clientTimeMillis: Long?,
    ): String {
        return when (args.action) {
            "add" -> {
                val startTime = parseNaturalTime(args.`when` ?: "", clientTimezone, clientTimeMillis)
                val durationMs = parseDurationToMs(args.duration ?: "1 hour")
                val endTime = startTime + durationMs
                if (calendarRepository != null && args.title != null) {
                    val eventInfo = com.example.smarty.protocol.CalendarEventInfo(
                        id = "",
                        title = args.title,
                        startTime = startTime,
                        endTime = endTime,
                        description = args.description,
                        reminderMinutes = 15,
                        linkedNoteId = null,
                        googleEventId = null,
                        isEventPrivate = false,
                        createdAt = System.currentTimeMillis()
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
                    val events = calendarRepository.listAllEvents(userId).filter { it.startTime in startMs until endMs }
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
                val isAlarm = !whenStr.contains("in ") && !whenStr.contains("after ")
                if (timerRepository != null && args.what != null) {
                    val timerId = timerRepository.create(userId, args.what, triggerAt = triggerTime, isAlarm = isAlarm)
                    emitStateSync("timer_set", """{"id":"$timerId"}""")
                    "${if (isAlarm) "Reminder" else "Timer"} set: '${args.what}'"
                } else {
                    emitDeviceCommand(
                        AgentCommand.SetTimer(
                            commandId = UUID.randomUUID().toString(),
                            name = args.what ?: "",
                            timeStr = args.`when` ?: "",
                            isAlarm = isAlarm,
                        ),
                    )
                    "Reminder sent to device: ${args.what}"
                }
            }
            "list" -> "Listing reminders..."
            "cancel" -> {
                if (timerRepository != null && args.id != null) {
                    timerRepository.delete(userId, args.id)
                    "Reminder cancelled."
                } else {
                    "Cancel request sent to device."
                }
            }
            else -> "Unknown remind action: ${args.action}"
        }
    }

    private suspend fun executeDeviceTool(args: UnifiedToolArgs): String {
        return when (args.action) {
            "open" -> {
                val packageName = resolveAppPackage(args.app ?: "")
                emitDeviceCommand(
                    AgentCommand.LaunchApp(
                        commandId = UUID.randomUUID().toString(),
                        packageName = packageName,
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
                emitDeviceCommand(
                    AgentCommand.ToggleSetting(
                        commandId = UUID.randomUUID().toString(),
                        setting = args.setting ?: "",
                        enable = args.on ?: false,
                    ),
                )
                "${args.setting} ${if (args.on == true) "on" else "off"}"
            }
            "status" -> {
                emitDeviceCommand(
                    AgentCommand.GetDeviceInfo(
                        commandId = UUID.randomUUID().toString(),
                        infoType = args.info ?: "all",
                    ),
                )
                "Getting device ${args.info}..."
            }
            "capture" -> {
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

    private suspend fun executeSearchTool(args: UnifiedToolArgs): String {
        return when (args.action) {
            "web" -> {
                val searchResult = tavilyTool.search(args.query ?: "")
                if (searchResult.startsWith("Error")) {
                    "Search failed: $searchResult"
                } else {
                    searchResult
                }
            }
            else -> "Unknown search action: ${args.action}"
        }
    }

    private suspend fun executeAskUser(args: UnifiedToolArgs): String {
        val question = args.question ?: "What would you like?"
        val options = args.options?.let { element ->
            when (element) {
                is kotlinx.serialization.json.JsonArray -> element.map { it.jsonPrimitive.content }
                is kotlinx.serialization.json.JsonPrimitive -> element.content.split("\n").map { it.trim() }.filter { it.isNotBlank() }
                else -> emptyList()
            }
        } ?: emptyList()
        val allowCustom = args.allowCustom ?: false
        emit(
            com.example.smarty.protocol.AgentEvent.Question(
                eventId = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                question = question,
                options = options,
                allowCustom = allowCustom,
            ),
        )
        return "__WAITING_FOR_USER_RESPONSE__"
    }

    private suspend fun executeGetNoteById(args: UnifiedToolArgs): String {
        return if (noteRepository != null && args.noteId != null) {
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
    }

    private suspend fun executeNavigateTool(args: UnifiedToolArgs): String {
        return when (args.action) {
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
    }

    private suspend fun executeSearchHistory(args: UnifiedToolArgs): String {
        val query = args.query ?: return "Search query required"
        val limit = args.limit?.toIntOrNull() ?: 10

        val dataSource = com.example.smarty.server.data.DatabaseFactory.getDataSource() ?: return "Database not available"
        val chatRepo =
            com.example.smarty.server.data.ChatRepository(
                dataSource,
                com.example.smarty.server.data.ChatMessageNotesRepository(dataSource),
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
    ): Long {
        val now = clientTimeMillis ?: System.currentTimeMillis()
        val tz =
            try {
                java.time.ZoneId.of(clientTimezone ?: "UTC")
            } catch (e: Exception) {
                java.time.ZoneId.of("UTC")
            }
        val zonedNow = java.time.Instant.ofEpochMilli(now).atZone(tz)
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
        var targetDay: Int? = null
        for ((day, offset) in dayOffsets) {
            if (cleanExpr.contains(day)) {
                val currentDayOfWeek = zonedNow.dayOfWeek.value
                var daysUntil = offset - currentDayOfWeek
                if (daysUntil <= 0) daysUntil += 7
                targetDay = daysUntil
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

        var resultTime = zonedNow.withHour(hour).withMinute(minute).withSecond(0).withNano(0)

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
        val zonedNow = java.time.Instant.ofEpochMilli(now).atZone(tz)

        return when (whenStr.lowercase()) {
            "today" -> {
                val start = zonedNow.withHour(0).withMinute(0).withSecond(0).withNano(0)
                val end = start.plusDays(1)
                start.toInstant().toEpochMilli() to end.toInstant().toEpochMilli()
            }
            "tomorrow" -> {
                val start = zonedNow.plusDays(1).withHour(0).withMinute(0).withSecond(0).withNano(0)
                val end = start.plusDays(1)
                start.toInstant().toEpochMilli() to end.toInstant().toEpochMilli()
            }
            "week" -> {
                val start = zonedNow.withHour(0).withMinute(0).withSecond(0).withNano(0)
                val end = start.plusWeeks(1)
                start.toInstant().toEpochMilli() to end.toInstant().toEpochMilli()
            }
            else -> {
                val point = parseNaturalTime(whenStr, clientTimezone, clientTimeMillis)
                point to point + 86400000
            }
        }
    }

    private fun resolveAppPackage(appName: String): String {
        return when (appName.lowercase().trim()) {
            "spotify" -> "com.spotify.music"
            "youtube", "yt" -> "com.google.android.youtube"
            "chrome" -> "com.android.chrome"
            "maps", "google maps" -> "com.google.android.apps.maps"
            "gmail" -> "com.google.android.gm"
            "camera" -> "com.android.camera"
            "settings" -> "com.android.settings"
            "calendar" -> "com.google.android.calendar"
            "clock", "alarm" -> "com.google.android.deskclock"
            "messages", "sms" -> "com.google.android.apps.messaging"
            "phone", "dialer" -> "com.google.android.dialer"
            else -> appName
        }
    }

    fun truncateToolResult(result: String): String {
        return if (result.length > 4000) {
            result.take(4000) + "\n... (truncated, full result available in context)"
        } else {
            result
        }
    }
}
