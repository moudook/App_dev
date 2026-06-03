package com.example.smarty.features.chat.domain

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import com.example.smarty.features.chat.agent.AgentEventSink
import com.example.smarty.protocol.AgentCommand
import com.example.smarty.protocol.*
import com.example.smarty.core.domain.model.*
import com.example.smarty.features.chat.agent.models.ImageDisplayItem
import com.example.smarty.features.chat.agent.models.WebCitation
import com.example.smarty.features.chat.agent.transport.CommandTransport
import com.example.smarty.data.repository.SmartyRepository
import com.example.smarty.service.AlarmScheduler
import com.example.smarty.data.remote.RemoteAgentService
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.CopyOnWriteArrayList
import com.example.smarty.features.chat.domain.ChatFeatureManager.AgentActivity

class ChatAgentEventSinkImpl(
    private val scope: CoroutineScope,
    private val repository: SmartyRepository,
    private val alarmScheduler: AlarmScheduler,
    private val remoteAgentServiceProvider: () -> RemoteAgentService,
    private val commandTransport: CommandTransport,
    private val currentSessionId: StateFlow<String?>,
    private val _agentActivity: MutableStateFlow<AgentActivity?>,
    private val pendingActions: CopyOnWriteArrayList<AgentActionResult>,
    private val onCitationsFoundCallback: (List<WebCitation>) -> Unit,
    private val onDisplayImagesCallback: (List<ImageDisplayItem>) -> Unit,
    private val validateCommandCallback: (AgentCommand) -> ChatCommandValidator.CommandValidationResult,
    private val logCommandCallback: (AgentCommand, Boolean, String?) -> Unit,
    private val getCommandSummaryCallback: (AgentCommand) -> String
) : AgentEventSink {
    companion object { private const val TAG = "ChatAgentEventSinkImpl" }
        override fun onToolExecutionStarted(
            toolName: String,
            toolDisplayName: String,
        ) {
            // Show tool execution in UI
            val activityType =
                when {
                    toolName.contains("search", ignoreCase = true) -> AgentActivity.Type.SEARCHING
                    toolName.contains("analyze", ignoreCase = true) -> AgentActivity.Type.ANALYZING
                    else -> AgentActivity.Type.TOOL_RUNNING
                }
            _agentActivity.value =
                AgentActivity(
                    type = activityType,
                    displayText = toolDisplayName,
                    toolName = toolName,
                )
        }

        override fun onToolExecutionCompleted(toolName: String) {
            // Clear activity when tool completes
            if (_agentActivity.value?.toolName == toolName) {
                _agentActivity.value = null
            }
        }

        override fun onStatusUpdate(status: String) {
            // Show thinking/processing status
            val activityType =
                when {
                    status.contains("search", ignoreCase = true) -> AgentActivity.Type.SEARCHING
                    status.contains("analyz", ignoreCase = true) -> AgentActivity.Type.ANALYZING
                    else -> AgentActivity.Type.THINKING
                }
            _agentActivity.value =
                AgentActivity(
                    type = activityType,
                    displayText = status,
                )
        }

        override fun onCitationsFound(citations: List<WebCitation>) {
            onCitationsFoundCallback(citations)
        }

        override fun onDisplayImages(images: List<ImageDisplayItem>) {
            onDisplayImagesCallback(images)
        }

        override fun onPlanStatusChanged(status: String?) {
            // AI planning status is disabled to reduce visual clutter
            // _aiPlanStatus is no longer updated
        }

        override fun onStateSync(
            syncType: String,
            data: String,
        ) {
            scope.launch {
                try {
                    when (syncType) {
                        "note_created" -> {
                            val info = Json.decodeFromString<NoteInfo>(data)
                            val category = null // TODO: resolve categoryId to Category object
                            val note =
                                Note(
                                    id = info.id,
                                    title = info.title,
                                    content = info.content,
                                    categoryId = info.categoryId,
                                    categoryName = null, // TODO: resolve categoryId to name
                                    type = NoteType.BRAIN_DUMP,
                                    createdAt = info.createdAt,
                                    updatedAt = info.updatedAt,
                                    isArchived = info.isArchived,
                                )
                            repository.insertNote(note)
                        }
                        "timer_set" -> {
                            val info = Json.decodeFromString<TimerInfo>(data)
                            val timer =
                                SmartyTimer(
                                    id = info.id,
                                    name = info.name,
                                    triggerTime = info.triggerAt,
                                    isAlarm = info.isAlarm,
                                    isActive = info.isActive,
                                    createdAt = info.createdAt,
                                    repeatDays = null, // Server doesn't support recurring yet
                                )
                            alarmScheduler.scheduleTimer(timer)
                        }
                        "event_scheduled" -> {
                            val info = Json.decodeFromString<CalendarEventInfo>(data)
                            val event =
                                CalendarEvent(
                                    id = info.id,
                                    title = info.title,
                                    startTime = info.startTime,
                                    endTime = info.endTime,
                                    description = info.description,
                                    reminderMinutes = info.reminderMinutes,
                                    isEventPrivate = false,
                                )
                            repository.insertCalendarEvent(event)
                        }
                        else -> Log.w(TAG, "Unknown state sync type: $syncType")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to handle state sync: $syncType", e)
                }
            }
        }

        //
        // TASK 7: Route commands through transport abstraction
        // Smarty notifications are handled here; action commands go through transport
        //
        override fun emit(command: AgentCommand) {
            // Task 6: Validate command before execution
            val validation = validateCommandCallback(command)

            if (validation is ChatCommandValidator.CommandValidationResult.Invalid) {
                // Log rejected command with reason (remains observable)
                logCommandCallback(command, true, validation.toLogString())
                // Do not execute - silent rejection, no exception, no feedback to Agent
                return
            }

            // Task 5: Log valid command with safe summaries (no user content)
            logCommandCallback(command, false, null)

            // Task 7: Route commands through transport abstraction
            // UI notifications are handled here; action commands go through transport
            when (command) {
                // === UI NOTIFICATIONS (handled locally, not through transport) ===
                is AgentCommand.NotifyToolStarted -> {
                    onToolExecutionStarted(command.toolName, command.displayName)
                }
                is AgentCommand.NotifyToolCompleted -> {
                    onToolExecutionCompleted(command.toolName)
                }
                is AgentCommand.NotifyStatus -> {
                    onStatusUpdate(command.status)
                }
                is AgentCommand.NotifyCitations -> {
                    val citations =
                        command.citations.map { proto ->
                            WebCitation(proto.title, proto.url, proto.snippet)
                        }
                    onCitationsFound(citations)
                }

                // === ALL OTHER COMMANDS (delegated to transport) ===
                else -> {
                    scope.launch {
                        // Track this command as an executed action
                        val actionName = command::class.simpleName ?: "Unknown"
                        pendingActions.add(
                            AgentActionResult(
                                action = actionName,
                                success = true,
                                resultSummary = getCommandSummaryCallback(command),
                            ),
                        )

                        val result = commandTransport.dispatch(command)
                        // Send device status responses back to server
                        if (result is com.example.smarty.protocol.ClientEvent.SystemStatusResponse) {
                            val sessionId = currentSessionId.value
                            if (sessionId != null) {
                                remoteAgentServiceProvider().sendEvent(sessionId, result)
                            }
                        }
                    }
                }
            }
        }
}
