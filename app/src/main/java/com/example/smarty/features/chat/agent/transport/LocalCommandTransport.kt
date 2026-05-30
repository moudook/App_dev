package com.example.smarty.features.chat.agent.transport

import com.example.smarty.features.audio.domain.AudioFeatureManager
import com.example.smarty.features.chat.agent.ClientCommandExecutor
import com.example.smarty.protocol.AgentCommand
import com.example.smarty.protocol.ClientEvent
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
    private val scope: CoroutineScope,
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
                        timestamp = System.currentTimeMillis(),
                    )
                }
                is AgentCommand.SearchNotes -> {
                    val results =
                        executor.searchNotes(
                            command.query,
                            command.category,
                            timeRange = command.timeRange,
                            limit = command.limit,
                        )
                    ClientEvent.SearchResultsResponse(
                        commandId = command.commandId,
                        results =
                            results.map {
                                com.example.smarty.protocol.ProtocolSearchResult(
                                    id = it.note.id,
                                    title = it.note.title,
                                    content = it.note.content,
                                    score = it.score.toDouble(),
                                )
                            },
                        timestamp = System.currentTimeMillis(),
                    )
                }
                is AgentCommand.GetSystemStatus -> {
                    val status = executor.getSystemStatus()
                    ClientEvent.SystemStatusResponse(
                        commandId = command.commandId,
                        status = status,
                        timestamp = System.currentTimeMillis(),
                    )
                }
                is AgentCommand.GetDeviceInfo -> {
                    // Map GetDeviceInfo to GetSystemStatus as per ToolTax doc
                    val status = executor.getSystemStatus()
                    ClientEvent.SystemStatusResponse(
                        commandId = command.commandId,
                        status = status,
                        timestamp = System.currentTimeMillis(),
                    )
                }
                is AgentCommand.GetScreenContext -> {
                    val context = executor.getScreenContext()
                    ClientEvent.ScreenContextResponse(
                        commandId = command.commandId,
                        context = context,
                        timestamp = System.currentTimeMillis(),
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
                            timestamp = System.currentTimeMillis(),
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
                timestamp = System.currentTimeMillis(),
            )
        }
    }

    private suspend fun executeAction(command: AgentCommand): String? {
        return when (command) {
            // === NOTE OPERATIONS ===
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

            // === SYSTEM & APP CONTROL ===
            is AgentCommand.LaunchApp -> {
                val pkg = executor.findPackageName(command.packageName) ?: command.packageName
                executor.launchApp(pkg)
                "Launched $pkg"
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
                executor.setTimer(command.name, command.timeStr, command.isAlarm, command.repeat, command.triggerTime)
                "Timer set: ${command.name}"
            }
            is AgentCommand.ListTimers -> {
                executor.listTimers()
                "Timers listed"
            }
            is AgentCommand.CancelTimer -> {
                executor.cancelTimer(command.id)
                "Timer canceled: ${command.id}"
            }

            // === AUDIO CONTROL ===
            is AgentCommand.PlayAudio -> {
                val result = executor.findMatchingAudio(command.query)
                @Suppress("DEPRECATION")
                when (result) {
                    is AudioFeatureManager.AudioSearchResult.ExactMatch -> {
                        executor.requestAudioPlayback(result.track)
                        "Playing: ${result.track.title}"
                    }
                    is AudioFeatureManager.AudioSearchResult.Suggestions -> {
                        if (result.tracks.isNotEmpty()) {
                            executor.playAudioList(result.tracks)
                            "Playing ${result.tracks.size} tracks found for query"
                        } else {
                            "No matching audio found for: ${command.query}"
                        }
                    }
                    is AudioFeatureManager.AudioSearchResult.FuzzyMatch -> {
                        executor.requestAudioPlayback(result.track)
                        "Playing: ${result.track.title}"
                    }
                    is AudioFeatureManager.AudioSearchResult.NoMatch -> {
                        result.reason
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

            // === CONTEXT / PERSONALIZATION ===
            is AgentCommand.StoreContext -> {
                executor.storeContext(command.content, command.type)
                "Context stored"
            }
            is AgentCommand.UpdateContext -> {
                executor.updateContext(command.id, command.content, command.type)
                "Context updated"
            }
            is AgentCommand.DeleteContext -> {
                executor.deleteContext(command.id)
                "Context deleted"
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
                    isPrivate = false,
                )
                "Calendar event added: ${command.title}"
            }

            is AgentCommand.ShowBreathing -> {
                executor.showBreathing()
                "Starting guided breathing session"
            }

            // === UI NOTIFICATIONS ===
            else -> null
        }
    }
}
