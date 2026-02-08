package com.example.smarty.agent.transport

import com.example.smarty.agent.ClientCommandExecutor
import com.example.smarty.protocol.AgentCommand
import com.example.smarty.protocol.ClientEvent
import com.example.smarty.viewmodel.managers.AudioFeatureManager
import com.example.smarty.viewmodel.managers.RecallResult
import com.example.smarty.data.model.AIMemory
import com.example.smarty.data.model.CalendarEvent
import kotlinx.coroutines.CoroutineScope

/**
 * Local command transport - executes commands directly on-device.
 *
 * This is the default implementation with no networking.
 * Depends only on ClientCommandExecutor for command execution.
 *
 * Pure delivery:
 * - No validation (handled by emit())
 * - No logging (handled by emit())
 * - No retries or acknowledgments
 *
 * Note: UI notification commands (NotifyToolStarted, NotifyStatus, etc.)
 * are handled upstream in emit() and do not flow through this transport.
 */
class LocalCommandTransport(
    private val executor: ClientCommandExecutor,
    private val scope: CoroutineScope
) : CommandTransport {

    override suspend fun dispatch(command: AgentCommand): ClientEvent? {
        return try {
            when (command) {
                // === DATA RETRIEVAL (Specialized Responses) ===
                is AgentCommand.GetActiveNotes -> {
                    val notes = executor.getActiveNotes()
                    ClientEvent.ActiveNotesResponse(
                        commandId = command.commandId,
                        notes = notes,
                        timestamp = System.currentTimeMillis()
                    )
                }
                is AgentCommand.SearchNotes -> {
                    val results = executor.searchNotes(
                        command.query,
                        command.category,
                        timeRange = command.timeRange,
                        limit = command.limit
                    )
                    ClientEvent.SearchResultsResponse(
                        commandId = command.commandId,
                        results = results.map {
                            com.example.smarty.protocol.ProtocolSearchResult(
                                id = it.note.id,
                                title = it.note.title,
                                content = it.note.content,
                                score = it.score.toDouble()
                            )
                        },
                        timestamp = System.currentTimeMillis()
                    )
                }
                is AgentCommand.GetSystemStatus -> {
                    val status = executor.getSystemStatus()
                    ClientEvent.SystemStatusResponse(
                        commandId = command.commandId,
                        status = status,
                        timestamp = System.currentTimeMillis()
                    )
                }
                is AgentCommand.SearchKnowledge -> {
                    val results = executor.searchKnowledge(command.query, command.filter)
                    ClientEvent.RecallResultsResponse(
                        commandId = command.commandId,
                        results = results,
                        timestamp = System.currentTimeMillis()
                    )
                }
                is AgentCommand.RetrieveMemories -> {
                    val memories = executor.retrieveMemories(command.query, command.limit)
                    ClientEvent.MemoriesResponse(
                        commandId = command.commandId,
                        memories = memories,
                        timestamp = System.currentTimeMillis()
                    )
                }
                is AgentCommand.ListEvents -> {
                    val events = executor.listEvents(command.date)
                    ClientEvent.CalendarEventsResponse(
                        commandId = command.commandId,
                        events = events,
                        timestamp = System.currentTimeMillis()
                    )
                }
                is AgentCommand.QueryCalendar -> {
                    val events = executor.queryCalendarEvents(command.query)
                    ClientEvent.CalendarEventsResponse(
                        commandId = command.commandId,
                        events = events,
                        timestamp = System.currentTimeMillis()
                    )
                }
                is AgentCommand.GetScreenContext -> {
                    val context = executor.getScreenContext()
                    ClientEvent.ScreenContextResponse(
                        commandId = command.commandId,
                        context = context,
                        timestamp = System.currentTimeMillis()
                    )
                }

                // === ACTION COMMANDS (Generic ToolResult) ===
                else -> {
                    val resultString = executeAction(command)
                    if (resultString != null) {
                        ClientEvent.ToolResult(
                            commandId = command.commandId,
                            result = resultString,
                            isError = false,
                            timestamp = System.currentTimeMillis()
                        )
                    } else {
                        null
                    }
                }
            }
        } catch (e: Exception) {
            ClientEvent.ToolResult(
                commandId = command.commandId,
                result = "Error: ${e.message}",
                isError = true,
                timestamp = System.currentTimeMillis()
            )
        }
    }

    private suspend fun executeAction(command: AgentCommand): String? {
        return when (command) {
            // === NOTE & KNOWLEDGE OPERATIONS ===
            is AgentCommand.CaptureKnowledge -> {
                executor.captureKnowledge(command.title, command.content, command.source, command.category)
                "Knowledge captured: ${command.title}"
            }
            is AgentCommand.AddNote -> {
                executor.addNote(command.content, command.category)
                "Note added"
            }
            is AgentCommand.UpdateNote -> {
                executor.updateNote(command.noteId, command.title, command.content)
                "Note updated: ${command.noteId}"
            }
            is AgentCommand.DeleteNote -> {
                executor.deleteNoteById(command.noteId)
                "Note deleted: ${command.noteId}"
            }
            is AgentCommand.ArchiveNote -> {
                executor.archiveNote(command.noteId)
                "Note archived: ${command.noteId}"
            }

            // === MEMORY OPERATIONS ===
            is AgentCommand.StoreMemory -> {
                executor.storeMemory(command.content, command.scope)
                "Memory stored"
            }
            is AgentCommand.UpdateMemory -> {
                val success = executor.updateMemory(command.id, command.content, command.type)
                if (success) "Memory updated" else "Memory update failed"
            }
            is AgentCommand.DeleteMemory -> {
                val success = executor.deleteMemory(command.id)
                if (success) "Memory deleted" else "Memory deletion failed"
            }

            // === SYSTEM & APP CONTROL ===
            is AgentCommand.LaunchApp -> {
                executor.launchApp(command.packageName)
                "Launched ${command.packageName}"
            }
            is AgentCommand.TakeScreenshot -> {
                executor.takeScreenshot(command.save)
                "Screenshot taken"
            }
            is AgentCommand.ToggleSetting -> {
                executor.toggleSetting(command.setting, command.enable)
                "Setting ${command.setting} set to ${command.enable}"
            }
            is AgentCommand.SetTimer -> {
                executor.setTimer(command.name, command.timeStr, command.isAlarm)
                "Timer set: ${command.name}"
            }

            // === AUDIO CONTROL ===
            is AgentCommand.PlayAudio -> {
                val result = executor.findMatchingAudio(command.query)
                when (result) {
                    is AudioFeatureManager.AudioSearchResult.ExactMatch -> {
                        executor.requestAudioPlayback(result.track)
                        "Playing: ${result.track.title}"
                    }
                    is AudioFeatureManager.AudioSearchResult.Fallback -> {
                        if (result.tracks.isNotEmpty()) {
                            executor.playAudioList(result.tracks)
                            "Playing ${result.tracks.size} tracks found for query"
                        } else {
                            "No matching audio found for: ${command.query}"
                        }
                    }
                }
            }
            is AgentCommand.ControlAudio -> {
                executor.controlAudio(command.action)
                "Audio action executed: ${command.action}"
            }
            is AgentCommand.SeekAudio -> {
                executor.seekAudio(command.positionMs)
                "Seeked to ${command.positionMs}ms"
            }

            // === CALENDAR ===
            is AgentCommand.ScheduleEvent -> {
                executor.scheduleEvent(command.title, command.startTime, command.endTime, command.description)
                "Event scheduled: ${command.title}"
            }
            is AgentCommand.DeleteEvent -> {
                executor.deleteEvent(command.eventId)
                "Event deleted: ${command.eventId}"
            }
            is AgentCommand.Navigate -> {
                executor.navigateTo(command.screen)
                "Navigated to ${command.screen}"
            }
            is AgentCommand.Share -> {
                executor.shareContent(command.content, command.title)
                "Content shared"
            }
            is AgentCommand.AddCalendarEvent -> {
                executor.addCalendarEvent(
                    title = command.title,
                    startTimeStr = command.start,
                    endTimeStr = command.end,
                    description = command.description,
                    location = command.location,
                    isPrivate = false
                )
                "Calendar event added: ${command.title}"
            }

            // === UI NOTIFICATIONS ===
            else -> null
        }
    }
}
