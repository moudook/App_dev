package com.example.smarty.agent.transport

import com.example.smarty.agent.ClientCommandExecutor
import com.example.smarty.protocol.AgentCommand
import com.example.smarty.viewmodel.managers.AudioFeatureManager

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
    private val executor: ClientCommandExecutor
) : CommandTransport {

    override fun dispatch(command: AgentCommand) {
        when (command) {
            // === NOTE OPERATIONS ===
            is AgentCommand.AddNote -> {
                executor.addNote(command.content, command.category)
            }
            is AgentCommand.UpdateNote -> {
                executor.updateNote(command.noteId, command.title, command.content)
            }
            is AgentCommand.DeleteNote -> {
                executor.deleteNoteById(command.noteId)
            }
            is AgentCommand.ArchiveNote -> {
                executor.archiveNote(command.noteId)
            }
            is AgentCommand.SearchNotes -> {
                // Read operation - logged but not executed via transport
                // (requires result callback, should use direct calls)
            }
            is AgentCommand.GetActiveNotes -> {
                // Read operation - no-op in transport
            }

            // === SYSTEM & APP CONTROL ===
            is AgentCommand.LaunchApp -> {
                executor.launchApp(command.packageName)
            }
            is AgentCommand.GetSystemStatus -> {
                // Read operation - no-op in transport
            }
            is AgentCommand.GetScreenContext -> {
                // Read operation - no-op in transport
            }
            is AgentCommand.SetTimer -> {
                executor.setTimer(command.name, command.timeStr, command.isAlarm)
            }

            // === AUDIO CONTROL ===
            is AgentCommand.PlayAudio -> {
                val result = executor.findMatchingAudio(command.query)
                when (result) {
                    is AudioFeatureManager.AudioSearchResult.ExactMatch -> {
                        executor.requestAudioPlayback(result.track)
                    }
                    is AudioFeatureManager.AudioSearchResult.Fallback -> {
                        if (result.tracks.isNotEmpty()) {
                            executor.playAudioList(result.tracks)
                        }
                    }
                }
            }
            is AgentCommand.ControlAudio -> {
                when (command.action.lowercase()) {
                    "pause" -> executor.pauseAudioPlayback()
                    "resume" -> executor.resumeAudioPlayback()
                    "stop" -> executor.stopAudioPlayback()
                    "toggle" -> executor.toggleAudioPlayback()
                    "next" -> { /* Not implemented in current executor */ }
                    "prev" -> { /* Not implemented in current executor */ }
                }
            }

            // === CALENDAR ===
            is AgentCommand.AddCalendarEvent -> {
                executor.addCalendarEvent(
                    title = command.title,
                    startTimeStr = command.start,
                    endTimeStr = command.end,
                    description = command.description,
                    location = command.location,
                    isPrivate = false
                )
            }
            is AgentCommand.QueryCalendar -> {
                // Read operation - no-op in transport
            }

            // === UI NOTIFICATIONS ===
            // These are handled upstream in emit() and should not reach transport.
            // Included here for exhaustive when() but are no-ops.
            is AgentCommand.NotifyToolStarted -> { /* Handled in emit() */ }
            is AgentCommand.NotifyToolCompleted -> { /* Handled in emit() */ }
            is AgentCommand.NotifyStatus -> { /* Handled in emit() */ }
            is AgentCommand.NotifyCitations -> { /* Handled in emit() */ }
        }
    }
}
