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
    data class NavigateTo(val route: String) : CommandResult()
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
    private val getActiveNoteId: () -> String?,
    private val systemFeatureManager: SystemFeatureManager,
    private val getDeviceAudio: suspend () -> List<AudioTrack> = { emptyList() }
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

        private val RESUME_PREFIXES = listOf(
            "resume", "unpause", "continue playing", "continue", "resume music"
        )

        private val NEXT_PREFIXES = listOf(
            "next", "skip", "next song", "next track", "skip this", "skip track", "skip song"
        )

        private val PREV_PREFIXES = listOf(
            "previous", "prev", "back", "previous song", "previous track", "go back", "last track", "last song"
        )

        private val THEME_KEYWORDS = listOf(
            "dark mode", "dark theme", "light mode", "light theme", "night mode", "day mode"
        )

        private val TASK_WORDS = listOf(
            "create", "make", "write", "build", "add", "save", "update", "set", "schedule", "remind",
            "send", "share", "search", "find", "calculate", "summarize", "analyze", "then", "also", "and"
        )

        private val GAME_COIN_KEYWORDS = listOf("flip a coin", "toss a coin", "heads or tails", "coin toss", "coin flip")

        private val GAME_TIC_TAC_TOE_KEYWORDS = listOf("play tic tac toe", "noughts and crosses", "tic tac toe", "tictactoe")

        private val FILLER_WORDS = listOf("app", "application", "the", "a", "an", "my", "please", "for me", "now")

        private val SAVE_PAGE_KEYWORDS = listOf(
            "save this page", "save this screen", "save the page", "save the screen",
            "save screen", "save page", "capture this", "capture screen", "screenshot"
        )

        private val SHARE_PREFIXES = listOf(
            "share this", "share the note", "share my note", "share note", "share my context", "send this"
        )

        private val TIMER_PREFIXES = listOf(
            "set a timer for ", "set timer for ", "start a timer for ", "start timer for ",
            "timer for ", "remind me in ", "remind me after "
        )

        private val ALARM_PREFIXES = listOf(
            "set an alarm for ", "set alarm for ", "alarm for ", "wake me up at "
        )

        private val CANCEL_TIMER_KEYWORDS = listOf(
            "stop the timer", "stop timer", "cancel the timer", "cancel timer",
            "stop the alarm", "stop alarm", "cancel the alarm", "cancel alarm",
            "turn off the alarm", "turn off alarm", "stop all timers", "cancel all timers"
        )

        private val WAKE_WORDS = listOf(
            "hey smarty ", "hey smarty, ", "smarty ", "smarty, ", "hey ", "ok smarty ", "ok smarty, "
        )

        private val TIME_KEYWORDS = listOf(
            "what time is it", "what's the time", "current time", "tell me the time", "time please"
        )

        private val DATE_KEYWORDS = listOf(
            "what's the date", "what is the date", "today's date", "tell me the date", "current date", "what day is it"
        )

        private val BATTERY_KEYWORDS = listOf(
            "battery level", "battery status", "battery percentage", "how much battery", "check battery"
        )

        private val FLASHLIGHT_KEYWORDS = listOf(
            "flashlight on", "turn on flashlight", "torch on", "turn on torch", "enable flashlight",
            "flashlight off", "turn off flashlight", "torch off", "turn off torch", "disable flashlight",
            "flashlight", "torch"
        )

        private val VOLUME_KEYWORDS = listOf(
            "volume up", "increase volume", "louder", "make it louder", "turn it up",
            "volume down", "decrease volume", "softer", "quieter", "make it quieter", "turn it down",
            "mute", "unmute", "silent", "silence"
        )
    }

    suspend fun process(input: String): CommandResult {
        val cleanedInput = preprocessInput(input)
        val normalizedInput = cleanedInput.lowercase(Locale.getDefault())

        // 0. Check for Time/Date
        if (TIME_KEYWORDS.any { normalizedInput.contains(it) }) {
            val time = java.text.SimpleDateFormat("h:mm a", Locale.getDefault()).format(java.util.Date())
            val response = context.getString(R.string.current_time_response, time)
            return if (hasChainingIntent(normalizedInput)) {
                CommandResult.HandledAndPassToLLM(response)
            } else {
                CommandResult.Handled(response)
            }
        }
        if (DATE_KEYWORDS.any { normalizedInput.contains(it) }) {
            val date = java.text.SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(java.util.Date())
            val response = context.getString(R.string.current_date_response, date)
            return if (hasChainingIntent(normalizedInput)) {
                CommandResult.HandledAndPassToLLM(response)
            } else {
                CommandResult.Handled(response)
            }
        }

        // 0a. Check for Battery
        if (BATTERY_KEYWORDS.any { normalizedInput.contains(it) }) {
            val level = systemFeatureManager.getBatteryLevel()
            val response = context.getString(R.string.battery_status_response, level)
            return if (hasChainingIntent(normalizedInput)) {
                CommandResult.HandledAndPassToLLM(response)
            } else {
                CommandResult.Handled(response)
            }
        }

        // 0b. Check for Flashlight
        if (FLASHLIGHT_KEYWORDS.any { normalizedInput.contains(it) }) {
            val isOn = normalizedInput.contains("on") || normalizedInput.contains("enable") || !normalizedInput.contains("off")
            val success = systemFeatureManager.toggleFlashlight(isOn)
            if (success) {
                val response = context.getString(if (isOn) R.string.flashlight_on else R.string.flashlight_off)
                return if (hasChainingIntent(normalizedInput)) {
                    CommandResult.HandledAndPassToLLM(response)
                } else {
                    CommandResult.Handled(response)
                }
            } else {
                return CommandResult.Handled(response = context.getString(R.string.error_flashlight_failed))
            }
        }

        // 0c. Check for Volume
        if (VOLUME_KEYWORDS.any { normalizedInput.contains(it) }) {
            val direction = when {
                normalizedInput.contains("up") || normalizedInput.contains("increase") || normalizedInput.contains("louder") -> 1
                normalizedInput.contains("down") || normalizedInput.contains("decrease") || normalizedInput.contains("softer") || normalizedInput.contains("quieter") -> -1
                normalizedInput.contains("mute") || normalizedInput.contains("unmute") || normalizedInput.contains("silent") -> 0
                else -> null
            }
            if (direction != null) {
                systemFeatureManager.adjustVolume(direction)
                val response = when (direction) {
                    1 -> context.getString(R.string.volume_increased)
                    -1 -> context.getString(R.string.volume_decreased)
                    else -> context.getString(R.string.volume_toggled)
                }
                return if (hasChainingIntent(normalizedInput)) {
                    CommandResult.HandledAndPassToLLM(response)
                } else {
                    CommandResult.Handled(response = response)
                }
            }
        }

        // 0d. Check for Games
        if (GAME_COIN_KEYWORDS.any { normalizedInput.contains(it) }) {
            return CommandResult.NavigateTo("coin_toss")
        }
        if (GAME_TIC_TAC_TOE_KEYWORDS.any { normalizedInput.contains(it) }) {
            return CommandResult.NavigateTo("tic_tac_toe")
        }

        // 0e. Check for Cancel Timer/Alarm (More specific than general stop)
        if (CANCEL_TIMER_KEYWORDS.any { normalizedInput.contains(it) }) {
            systemFeatureManager.cancelAllTimers()
            return CommandResult.Handled(response = context.getString(R.string.timers_cancelled_success))
        }

        // 1. Check for "open" or "go to"
        for (prefix in OPEN_PREFIXES) {
            if (normalizedInput.startsWith(prefix)) {
                val appQuery = normalizedInput.removePrefix(prefix).trim()
                if (appQuery.isNotEmpty()) {
                    val internalScreenMatch = matchInternalScreen(appQuery)
                    if (internalScreenMatch != null) {
                        systemFeatureManager.navigateTo(internalScreenMatch)
                        return CommandResult.Handled(response = context.getString(R.string.navigating_success, appQuery))
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
                systemFeatureManager.pauseAudio()
                return CommandResult.Handled(response = context.getString(R.string.playback_paused_success))
            }
        }

        // 3a. Check for "resume"
        if (RESUME_PREFIXES.any { normalizedInput == it || normalizedInput.startsWith("$it ") }) {
            systemFeatureManager.resumeAudio()
            return CommandResult.Handled(response = context.getString(R.string.playback_resumed))
        }

        // 3b. Check for "next/previous"
        if (NEXT_PREFIXES.any { normalizedInput == it || normalizedInput.startsWith("$it ") }) {
            systemFeatureManager.nextTrack()
            return CommandResult.Handled(response = context.getString(R.string.playing_next_track))
        }
        if (PREV_PREFIXES.any { normalizedInput == it || normalizedInput.startsWith("$it ") }) {
            systemFeatureManager.previousTrack()
            return CommandResult.Handled(response = context.getString(R.string.playing_previous_track))
        }

        // 4. Check for "save page"
        if (isSavePageCommand(normalizedInput)) {
            val titleHint = extractSavePageHint(input)
            return CommandResult.SavePageRequest(titleHint = titleHint)
        }

        // 4b. Check for "share"
        for (prefix in SHARE_PREFIXES) {
            if (normalizedInput.startsWith(prefix)) {
                val activeNoteId = getActiveNoteId()
                if (activeNoteId != null) {
                    val note = getNotes().find { it.id == activeNoteId }
                    if (note != null) {
                        val shareText = "${note.title}\n\n${note.content}"
                        systemFeatureManager.shareContent(shareText, note.title)
                        return CommandResult.Handled(response = context.getString(R.string.sharing_note, note.title))
                    }
                }
                // If no active note, let LLM handle sharing logic (could be sharing screen, etc.)
                return CommandResult.PassToLLM
            }
        }

        // 4c. Check for Timers
        for (prefix in TIMER_PREFIXES) {
            if (normalizedInput.startsWith(prefix)) {
                val timeQuery = normalizedInput.substring(prefix.length).trim()
                if (timeQuery.isNotEmpty()) {
                    val success = systemFeatureManager.setTimer(name = "Timer", timeStr = timeQuery, isAlarm = false)
                    if (success) {
                        val hasTaskWords = TASK_WORDS.any { normalizedInput.contains(Regex("\\b${Regex.escape(it)}\\b")) }
                        return if (hasTaskWords) {
                            CommandResult.HandledAndPassToLLM(context.getString(R.string.timer_set_success, timeQuery))
                        } else {
                            CommandResult.Handled(response = context.getString(R.string.timer_set_success, timeQuery))
                        }
                    }
                }
            }
        }

        // 4d. Check for Alarms
        for (prefix in ALARM_PREFIXES) {
            if (normalizedInput.startsWith(prefix)) {
                val timeQuery = normalizedInput.substring(prefix.length).trim()
                if (timeQuery.isNotEmpty()) {
                    val success = systemFeatureManager.setTimer(name = "Alarm", timeStr = timeQuery, isAlarm = true)
                    if (success) {
                        val hasTaskWords = TASK_WORDS.any { normalizedInput.contains(Regex("\\b${Regex.escape(it)}\\b")) }
                        return if (hasTaskWords) {
                            CommandResult.HandledAndPassToLLM(context.getString(R.string.alarm_set_success, timeQuery))
                        } else {
                            CommandResult.Handled(response = context.getString(R.string.alarm_set_success, timeQuery))
                        }
                    }
                }
            }
        }

        // 4e. Check for Settings/Theme
        if (normalizedInput.contains("dark mode") || normalizedInput.contains("dark theme")) {
            systemFeatureManager.toggleTheme(true)
            return CommandResult.Handled(response = context.getString(R.string.theme_updated_dark))
        }
        if (normalizedInput.contains("light mode") || normalizedInput.contains("light theme")) {
            systemFeatureManager.toggleTheme(false)
            return CommandResult.Handled(response = context.getString(R.string.theme_updated_light))
        }

        // 4c. Check for Cache
        if (normalizedInput.contains("clear cache") || normalizedInput.contains("clean cache")) {
            systemFeatureManager.clearCache()
            return CommandResult.Handled(response = context.getString(R.string.clearing_cache))
        }

        // 5. Simple Greetings/Conversational
        handleConversationalQuery(normalizedInput)?.let {
            return CommandResult.Handled(response = it)
        }

        return CommandResult.PassToLLM
    }

    private fun preprocessInput(input: String): String {
        var cleaned = input.trim().lowercase(Locale.getDefault())

        // Strip trailing punctuation
        while (cleaned.isNotEmpty() && (cleaned.endsWith("?") || cleaned.endsWith("!") || cleaned.endsWith("."))) {
            cleaned = cleaned.substring(0, cleaned.length - 1).trim()
        }

        // Strip wake words from the beginning
        for (wake in WAKE_WORDS) {
            if (cleaned.startsWith(wake)) {
                cleaned = cleaned.substring(wake.length).trim()
                break
            }
        }

        // Strip "please" from beginning or end
        if (cleaned.startsWith("please ")) {
            cleaned = cleaned.substring(7).trim()
        }
        if (cleaned.endsWith(" please")) {
            cleaned = cleaned.substring(0, cleaned.length - 7).trim()
        }

        return cleaned
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

    private suspend fun tryExtractAndPlayAudio(normalizedInput: String): CommandResult? {
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

    private fun hasChainingIntent(input: String): Boolean {
        return TASK_WORDS.any { input.contains(Regex("\\b${Regex.escape(it)}\\b")) }
    }

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
