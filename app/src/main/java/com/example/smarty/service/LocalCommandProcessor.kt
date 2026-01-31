package com.example.smarty.service

import android.content.Context
import android.util.Log
import com.example.smarty.data.model.AudioTrack
import com.example.smarty.data.model.Note
import com.example.smarty.viewmodel.managers.SystemFeatureManager
import com.example.smarty.viewmodel.managers.AudioFeatureManager.AudioSearchResult
import com.example.smarty.R
import java.util.Locale

/**
 * Result of local command processing.
 */
sealed class CommandResult {
    data class Handled(val response: String, val action: CommandAction? = null) : CommandResult()
    data object PassToLLM : CommandResult()
    data class HandledAndPassToLLM(val response: String, val action: CommandAction? = null) : CommandResult()
    data class SavePageRequest(val titleHint: String? = null) : CommandResult()
}

sealed class CommandAction {
    data class LaunchApp(val packageName: String, val appName: String) : CommandAction()
    data class PlayAudio(val track: AudioTrack) : CommandAction()
}

/**
 * Processes commands locally using high-speed regex and heuristic matching.
 * This represents the "FAST-PATH" in the universal dispatcher.
 */
class LocalCommandProcessor(
    private val context: Context,
    private val getNotes: () -> List<Note>,
    private val systemFeatureManager: SystemFeatureManager,
    private val getDeviceAudio: () -> List<AudioTrack> = { emptyList() }
) {
    companion object {
        private const val TAG = "LocalCommandProcessor"

        private val OPEN_PREFIXES = listOf(
            "open up the ", "open up my ", "open up ", "open the ", "open my ", "open ",
            "launch the ", "launch my ", "launch ", "start the ", "start my ", "start ",
            "run the ", "run my ", "run ", "go to ", "switch to "
        )

        private val PLAY_PREFIXES = listOf(
            "play me some ", "play me the ", "play me a ", "play me ", "play some ", "play the ", "play a ", "play ",
            "put on some ", "put on the ", "put on ", "start playing ", "begin playing ",
            "listen to some ", "listen to the ", "listen to ", "hear some ", "hear the ", "hear ",
            "i want to hear ", "i want to listen to ", "let me hear ", "can you play ", "please play ",
            "music ", "audio ", "song ", "track ", "gaana ", "bajao "
        )

        private val STOP_PREFIXES = listOf(
            "stop ", "pause ", "stop playing", "pause music", "halt ", "end ", "band karo", "roko"
        )

        private val TASK_WORDS = listOf(
            "create", "make", "write", "build", "add", "save", "update", "set", "schedule", "remind",
            "send", "share", "search", "find", "calculate", "summarize", "analyze", "then", "also"
        )

        private val FILLER_WORDS = listOf("app", "application", "the", "a", "an", "my", "please", "for me", "now")

        private val SAVE_PAGE_KEYWORDS = listOf(
            "save this page", "save this screen", "save the page", "save the screen",
            "save screen", "save page", "capture this", "capture screen", "screenshot"
        )
    }

    fun process(input: String): CommandResult {
        val normalizedInput = input.trim().lowercase(Locale.getDefault())

        // 1. Check for "open" or "go to"
        for (prefix in OPEN_PREFIXES) {
            if (normalizedInput.startsWith(prefix)) {
                val appQuery = normalizedInput.removePrefix(prefix).trim()
                if (appQuery.isNotEmpty()) {
                    val internalScreenMatch = matchInternalScreen(appQuery)
                    if (internalScreenMatch != null) {
                        systemFeatureManager.navigateTo(internalScreenMatch)
                        return CommandResult.Handled(response = context.getString(R.string.opening_app, appQuery))
                    }
                    return handleOpenCommand(appQuery)
                }
            }
        }

        // 2. Check for "play"
        val playResult = tryExtractAndPlayAudio(normalizedInput)
        if (playResult != null) return playResult

        // 3. Check for "stop/pause"
        for (prefix in STOP_PREFIXES) {
            if (normalizedInput.startsWith(prefix) || normalizedInput == prefix.trim()) {
                return handleStopCommand()
            }
        }

        // 4. Check for "save page"
        if (isSavePageCommand(normalizedInput)) {
            val titleHint = extractSavePageHint(input)
            return CommandResult.SavePageRequest(titleHint = titleHint)
        }

        // 5. Simple Greetings/Conversational
        handleConversationalQuery(normalizedInput)?.let {
            return CommandResult.Handled(response = it)
        }

        return CommandResult.PassToLLM
    }

    private fun handleOpenCommand(appQuery: String): CommandResult {
        val cleanedQuery = cleanAppQuery(appQuery)
        val packageName = systemFeatureManager.findPackageName(cleanedQuery)

        return if (packageName != null) {
            val appName = try {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            } catch (e: Exception) { appQuery }

            systemFeatureManager.launchApp(packageName)
            CommandResult.Handled(
                response = context.getString(R.string.opening_app, appName),
                action = CommandAction.LaunchApp(packageName, appName)
            )
        } else {
            CommandResult.Handled(response = context.getString(R.string.error_app_not_found_query, appQuery))
        }
    }

    private fun tryExtractAndPlayAudio(normalizedInput: String): CommandResult? {
        for (pattern in PLAY_PREFIXES) {
            // STRICT RULE: Input MUST start with the play command
            if (normalizedInput.startsWith(pattern)) {
                val afterPlay = normalizedInput.substring(pattern.length).trim()
                val audioQuery = afterPlay.split(Regex("\\s+")).take(3).joinToString(" ").trim()
                if (audioQuery.isEmpty()) continue

                val deviceAudio = getDeviceAudio()
                val result = systemFeatureManager.findMatchingAudio(audioQuery, deviceAudio)

                when (result) {
                    is AudioSearchResult.ExactMatch -> {
                        val track = result.track
                        systemFeatureManager.playAudio(track)
                        val hasTaskWords = TASK_WORDS.any { normalizedInput.contains(Regex("\\b${Regex.escape(it)}\\b")) }

                        return if (hasTaskWords) {
                            CommandResult.HandledAndPassToLLM(context.getString(R.string.playing_track, track.title), CommandAction.PlayAudio(track))
                        } else {
                            CommandResult.Handled(context.getString(R.string.playing_track, track.title), CommandAction.PlayAudio(track))
                        }
                    }
                    is AudioSearchResult.Fallback -> {
                        // No exact match found, but we could play the first fallback track
                        if (result.tracks.isNotEmpty()) {
                            val track = result.tracks.first()
                            systemFeatureManager.playAudio(track)
                            val hasTaskWords = TASK_WORDS.any { normalizedInput.contains(Regex("\\b${Regex.escape(it)}\\b")) }

                            return if (hasTaskWords) {
                                CommandResult.HandledAndPassToLLM(context.getString(R.string.playing_track, track.title), CommandAction.PlayAudio(track))
                            } else {
                                CommandResult.Handled(context.getString(R.string.playing_track, track.title), CommandAction.PlayAudio(track))
                            }
                        }
                    }
                }
            }
        }
        return null
    }

    private fun handleStopCommand(): CommandResult {
        return try {
            AudioPlayerService.pause(context)
            CommandResult.Handled(response = context.getString(R.string.playback_paused_success))
        } catch (e: Exception) {
            CommandResult.Handled(response = context.getString(R.string.error_playback_pause_failed))
        }
    }

    private fun handleConversationalQuery(input: String): String? {
        return when {
            input in listOf("hi", "hello", "hey") -> context.getString(R.string.greeting_response)
            input.contains("what can you do") || input == "help" -> context.getString(R.string.help_response)
            input in listOf("thank you", "thanks") -> context.getString(R.string.thanks_response)
            input in listOf("bye", "goodbye") -> context.getString(R.string.goodbye_response)
            else -> null
        }
    }

    private fun matchInternalScreen(query: String): String? {
        val normalized = query.lowercase().trim()
        return when {
            normalized.contains("setting") -> "settings"
            normalized.contains("calendar") -> "calendar"
            normalized.contains("stack") || normalized.contains("categor") -> "stacks"
            normalized.contains("archive") -> "archive"
            normalized.contains("home") -> "input_stream"
            else -> null
        }
    }

    private fun isSavePageCommand(input: String): Boolean = SAVE_PAGE_KEYWORDS.any { input.contains(it) }

    private fun extractSavePageHint(input: String): String? {
        val normalized = input.lowercase()
        for (keyword in SAVE_PAGE_KEYWORDS) {
            val index = normalized.indexOf(keyword)
            if (index != -1) return input.substring(index + keyword.length).trim().ifEmpty { null }
        }
        return null
    }

    private fun cleanAppQuery(query: String): String {
        var cleaned = query.lowercase().trim()
        for (filler in FILLER_WORDS) {
            cleaned = cleaned.replace(Regex("\\b${Regex.escape(filler)}\\b"), " ")
        }
        return cleaned.replace(Regex("\\s+"), " ").trim()
    }
}
