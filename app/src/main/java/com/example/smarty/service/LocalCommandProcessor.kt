package com.example.smarty.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.util.Log
import com.example.smarty.data.model.AudioTrack
import com.example.smarty.data.model.Note
import com.example.smarty.data.model.NoteAttachment
import com.example.smarty.data.model.NoteType
import com.example.smarty.data.model.getAttachments
import java.util.Locale

/**
 * Result of local command processing.
 */
sealed class CommandResult {
    /**
     * Command was handled locally without LLM.
     */
    data class Handled(
        val response: String,
        val action: CommandAction? = null
    ) : CommandResult()

    /**
     * Command should be passed to the LLM for processing.
     */
    data object PassToLLM : CommandResult()

    /**
     * Command was handled locally but ALSO needs to be passed to LLM.
     * Use when query contains multiple tasks (e.g., "play music and create a note").
     * The local action is executed first, then the query goes to AI.
     */
    data class HandledAndPassToLLM(
        val response: String,
        val action: CommandAction? = null
    ) : CommandResult()

    /**
     * Save page/screen request - handled specially in AssistActivity.
     * @param titleHint Optional hint for the note title (text after "save this page")
     */
    data class SavePageRequest(
        val titleHint: String? = null
    ) : CommandResult()
}

/**
 * Actions that can be executed by local commands.
 */
sealed class CommandAction {
    data class LaunchApp(val packageName: String, val appName: String) : CommandAction()
    data class PlayAudio(val track: AudioTrack) : CommandAction()
}

/**
 * Processes commands locally without requiring LLM.
 *
 * Handles commands like:
 * - "open [app name]" - Opens an installed app
 * - "play [music name]" - Searches notes and plays audio
 *
 * This allows the assistant to work even when offline or without API keys.
 */
class LocalCommandProcessor(
    private val context: Context,
    private val getNotes: () -> List<Note>,
    private val onPlayAudio: (AudioTrack) -> Unit,
    private val onLaunchApp: (String) -> Unit
) {
    companion object {
        private const val TAG = "LocalCommandProcessor"

        // Command prefixes (case-insensitive) - ordered by specificity (longer first)
        private val OPEN_PREFIXES = listOf(
            "open up the ", "open up my ", "open up ",
            "open the ", "open my ", "open ",
            "launch the ", "launch my ", "launch ",
            "start the ", "start my ", "start ",
            "run the ", "run my ", "run ",
            "go to ", "switch to "
        )
        private val PLAY_PREFIXES = listOf("play ", "play me ", "play some ", "put on ")
        private val STOP_PREFIXES = listOf("stop ", "pause ", "stop playing", "pause music")

        // Task words that indicate the query has multiple tasks
        // If "play" + these words are found, audio is played AND query goes to AI
        private val TASK_WORDS = listOf(
            "create", "make", "write", "build", "generate", "draft", "prepare", "compose",
            "add", "save", "update", "edit", "modify", "delete", "remove",
            "set", "schedule", "remind", "timer", "alarm",
            "send", "share", "post", "upload",
            "search", "find", "look", "get", "fetch",
            "calculate", "convert", "translate",
            "summarize", "explain", "analyze",
            "then", "also", "and also", "after that", "next"
        )

        // Filler words to remove from app queries
        private val FILLER_WORDS = listOf(
            "app", "application", "the", "a", "an", "my", "please", "for me", "now"
        )
        // Keywords that indicate a "save page" command
        private val SAVE_PAGE_KEYWORDS = listOf(
            "save this page",
            "save this screen",
            "save the page",
            "save the screen",
            "save screen",
            "save page",
            "capture this",
            "capture screen",
            "capture page",
            "remember this page",
            "remember this screen",
            "remember this",
            "screenshot this",
            "screenshot",
            "take a screenshot",
            "take screenshot"
        )
    }

    /**
     * Extract title hint from a save page command.
     * Returns the part of the text that should be used as the title.
     */
    private fun extractSavePageHint(input: String): String? {
        val normalized = input.lowercase(Locale.getDefault())

        // Find which keyword was used and extract everything after it
        for (keyword in SAVE_PAGE_KEYWORDS) {
            val index = normalized.indexOf(keyword)
            if (index != -1) {
                // Get everything after the keyword
                val afterKeyword = input.substring(index + keyword.length).trim()
                Log.d(TAG, "Found keyword '$keyword', extracted hint: '$afterKeyword'")
                return afterKeyword.ifEmpty { null }
            }
        }
        return null
    }

    /**
     * Check if input is a save page command.
     */
    private fun isSavePageCommand(input: String): Boolean {
        val normalized = input.lowercase(Locale.getDefault())
        return SAVE_PAGE_KEYWORDS.any { keyword -> normalized.contains(keyword) }
    }

    /**
     * Process a command and return the result.
     *
     * @param input User's spoken/typed command
     * @return CommandResult indicating if handled locally or should pass to LLM
     */
    fun process(input: String): CommandResult {
        val normalizedInput = input.trim().lowercase(Locale.getDefault())

        // Check for "open" commands (must be at start)
        for (prefix in OPEN_PREFIXES) {
            if (normalizedInput.startsWith(prefix)) {
                val appQuery = normalizedInput.removePrefix(prefix).trim()
                if (appQuery.isNotEmpty()) {
                    return handleOpenCommand(appQuery)
                }
            }
        }

        // Check for "play" commands - can appear anywhere in the query
        // This allows queries like "hey can you play my workout playlist"
        val playResult = tryExtractAndPlayAudio(normalizedInput)
        if (playResult != null) {
            return playResult
        }

        // Check for "stop/pause" commands
        for (prefix in STOP_PREFIXES) {
            if (normalizedInput.startsWith(prefix) || normalizedInput == prefix.trim()) {
                return handleStopCommand()
            }
        }

        // Check for "save this page/screen" commands - use contains() for more flexibility
        if (isSavePageCommand(input)) {
            val titleHint = extractSavePageHint(input)
            Log.d(TAG, "Save page command detected! Input: '$input', Hint: '$titleHint'")
            return CommandResult.SavePageRequest(titleHint = titleHint)
        }

        // Not a local command, pass to LLM
        return CommandResult.PassToLLM
    }

    /**
     * Try to extract a "play {audio_name}" command from anywhere in the input.
     * The audio name is limited to 2-3 words after "play".
     *
     * If the query contains task words (create, make, etc.), the audio is played
     * AND the query is passed to AI for additional processing.
     *
     * Examples:
     * - "play my workout music" -> plays audio, returns Handled (no task words)
     * - "play relaxing songs and create a note" -> plays audio, returns HandledAndPassToLLM
     * - "hey can you create a note and play jazz" -> plays audio, returns HandledAndPassToLLM
     *
     * @return CommandResult if audio was found, null otherwise (to pass to LLM)
     */
    private fun tryExtractAndPlayAudio(normalizedInput: String): CommandResult? {
        // Find "play" keyword with various patterns
        val playPatterns = listOf(
            "play ", "play me ", "play some ", "put on ", "start playing "
        )

        for (pattern in playPatterns) {
            val playIndex = normalizedInput.indexOf(pattern)
            if (playIndex != -1) {
                // Extract text after the play keyword
                val afterPlay = normalizedInput.substring(playIndex + pattern.length).trim()
                if (afterPlay.isEmpty()) continue

                // Extract 1-3 words as the audio query (audio names are usually short)
                val words = afterPlay.split(Regex("\\s+"))
                val audioQuery = words.take(3).joinToString(" ").trim()

                if (audioQuery.isEmpty()) continue

                Log.d(TAG, "Detected play command in query. Audio query: '$audioQuery'")

                // Try to find matching audio in notes
                val notes = getNotes()
                val audioMatch = findBestAudioMatch(audioQuery, notes)

                if (audioMatch != null) {
                    val (track, noteName) = audioMatch
                    Log.d(TAG, "Found audio match: ${track.title} from note: $noteName")

                    onPlayAudio(track)

                    // Check if query contains task words (indicating multiple tasks)
                    val hasTaskWords = containsTaskWords(normalizedInput)
                    Log.d(TAG, "Query has task words: $hasTaskWords")

                    return if (hasTaskWords) {
                        // Play audio AND send to AI for additional tasks
                        CommandResult.HandledAndPassToLLM(
                            response = "Playing ${track.title}",
                            action = CommandAction.PlayAudio(track)
                        )
                    } else {
                        // Just play audio, no need for AI
                        CommandResult.Handled(
                            response = "Playing ${track.title}",
                            action = CommandAction.PlayAudio(track)
                        )
                    }
                } else {
                    // No audio found - don't handle, let AI process the query
                    Log.d(TAG, "No audio found for '$audioQuery', passing to LLM")
                    return null
                }
            }
        }

        return null // No play command found
    }

    /**
     * Check if the input contains any task words.
     * Task words indicate the user wants to do multiple things.
     */
    private fun containsTaskWords(input: String): Boolean {
        return TASK_WORDS.any { taskWord ->
            // Check for whole word match using word boundaries
            input.contains(Regex("\\b${Regex.escape(taskWord)}\\b"))
        }
    }

    /**
     * Handle "open [app]" command.
     * Searches installed apps thoroughly and launches the most relevant match.
     * Always finds a result if any apps are installed.
     */
    private fun handleOpenCommand(appQuery: String): CommandResult {
        Log.d(TAG, "Processing open command: '$appQuery'")

        // Clean the query by removing filler words
        val cleanedQuery = cleanAppQuery(appQuery)
        Log.d(TAG, "Cleaned query: '$cleanedQuery'")

        if (cleanedQuery.isEmpty()) {
            return CommandResult.Handled(response = "What app would you like me to open?")
        }

        val installedApps = getInstalledApps()
        Log.d(TAG, "Found ${installedApps.size} installed apps")

        if (installedApps.isEmpty()) {
            return CommandResult.Handled(response = "I couldn't find any apps on your device.")
        }

        val match = findBestAppMatch(cleanedQuery, installedApps)

        return if (match != null) {
            val (packageName, appName, confidence) = match
            Log.d(TAG, "Found app match: $appName ($packageName) with confidence: $confidence")

            // Launch the app
            try {
                onLaunchApp(packageName)
                val response = if (confidence >= 0.8f) {
                    "Opening $appName"
                } else {
                    "Opening $appName (best match for \"$appQuery\")"
                }
                CommandResult.Handled(
                    response = response,
                    action = CommandAction.LaunchApp(packageName, appName)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch app: ${e.message}")
                CommandResult.Handled(response = "Sorry, I couldn't open $appName")
            }
        } else {
            Log.w(TAG, "No app match found for query: '$cleanedQuery'")
            CommandResult.Handled(
                response = "I couldn't find an app matching \"$appQuery\" on your device."
            )
        }
    }

    /**
     * Clean up the app query by removing filler words and normalizing.
     */
    private fun cleanAppQuery(query: String): String {
        var cleaned = query.lowercase(Locale.getDefault()).trim()

        // Remove filler words
        for (filler in FILLER_WORDS) {
            // Remove as whole word (with word boundaries)
            cleaned = cleaned.replace(Regex("\\b${Regex.escape(filler)}\\b"), " ")
        }

        // Normalize whitespace
        cleaned = cleaned.replace(Regex("\\s+"), " ").trim()

        Log.d(TAG, "Cleaned app query: '$query' -> '$cleaned'")
        return cleaned
    }

    /**
     * Handle "stop/pause" command.
     * UI-001: Added try-catch for robust error handling.
     */
    private fun handleStopCommand(): CommandResult {
        Log.d(TAG, "Processing stop command")
        return try {
            AudioPlayerService.pause(context)
            CommandResult.Handled(response = "Paused playback")
        } catch (e: Exception) {
            Log.e(TAG, "Error in handleStopCommand: ${e.message}", e)
            CommandResult.Handled(response = "Couldn't pause playback")
        }
    }

    /**
     * Get list of installed launchable apps.
     */
    private fun getInstalledApps(): List<Pair<String, String>> {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val apps = pm.queryIntentActivities(mainIntent, 0)
        return apps.mapNotNull { resolveInfo ->
            try {
                val packageName = resolveInfo.activityInfo.packageName
                val appName = resolveInfo.loadLabel(pm).toString()
                packageName to appName
            } catch (e: Exception) {
                null
            }
        }.distinctBy { it.first }
    }

    /**
     * Find best matching app for the query.
     * Uses comprehensive scoring system with multiple matching strategies.
     * Always returns the best match with a confidence score.
     *
     * @return Triple of (packageName, appName, confidence) or null if no apps
     */
    private fun findBestAppMatch(
        query: String,
        apps: List<Pair<String, String>>
    ): Triple<String, String, Float>? {
        if (apps.isEmpty()) return null

        val normalizedQuery = query.lowercase(Locale.getDefault()).trim()
        val cleanQuery = normalizedQuery
            .removeSuffix(" app")
            .removeSuffix(" application")
            .trim()

        Log.d(TAG, "Searching for app: '$cleanQuery' among ${apps.size} installed apps")

        // Log first 10 apps for debugging
        apps.take(10).forEach { (pkg, name) ->
            Log.d(TAG, "  Available: $name ($pkg)")
        }

        // First, try to find by known package prefixes (most reliable for common apps)
        val packagePrefixMatch = findByPackagePrefix(cleanQuery, apps)
        if (packagePrefixMatch != null) {
            Log.d(TAG, "Found by package prefix: ${packagePrefixMatch.second} (${packagePrefixMatch.first})")
            return Triple(packagePrefixMatch.first, packagePrefixMatch.second, 1.0f)
        }

        // Second, try exact app name match (case insensitive)
        val exactNameMatch = apps.find { (_, appName) ->
            appName.lowercase(Locale.getDefault()) == cleanQuery
        }
        if (exactNameMatch != null) {
            Log.d(TAG, "Found exact name match: ${exactNameMatch.second}")
            return Triple(exactNameMatch.first, exactNameMatch.second, 1.0f)
        }

        // Third, try app name starts with query
        val startsWithMatch = apps.find { (_, appName) ->
            appName.lowercase(Locale.getDefault()).startsWith(cleanQuery)
        }
        if (startsWithMatch != null) {
            Log.d(TAG, "Found starts-with match: ${startsWithMatch.second}")
            return Triple(startsWithMatch.first, startsWithMatch.second, 0.95f)
        }

        // Fourth, try app name contains query (minimum 3 chars)
        if (cleanQuery.length >= 3) {
            val containsMatch = apps.find { (_, appName) ->
                appName.lowercase(Locale.getDefault()).contains(cleanQuery)
            }
            if (containsMatch != null) {
                Log.d(TAG, "Found contains match: ${containsMatch.second}")
                return Triple(containsMatch.first, containsMatch.second, 0.9f)
            }
        }

        // Fifth, try package name contains query
        if (cleanQuery.length >= 3) {
            val packageMatch = apps.find { (packageName, _) ->
                packageName.lowercase(Locale.getDefault()).contains(cleanQuery)
            }
            if (packageMatch != null) {
                Log.d(TAG, "Found package match: ${packageMatch.second} (${packageMatch.first})")
                return Triple(packageMatch.first, packageMatch.second, 0.85f)
            }
        }

        // Sixth, word-based matching for multi-word queries and app names
        val queryWords = cleanQuery.split(" ", "-", "_").filter { it.length >= 2 }
        if (queryWords.isNotEmpty()) {
            val wordMatch = apps.maxByOrNull { (packageName, appName) ->
                val normalizedName = appName.lowercase(Locale.getDefault())
                val lowerPackage = packageName.lowercase(Locale.getDefault())
                var matchCount = 0
                for (word in queryWords) {
                    if (normalizedName.contains(word) || lowerPackage.contains(word)) {
                        matchCount++
                    }
                }
                matchCount
            }
            if (wordMatch != null) {
                val normalizedName = wordMatch.second.lowercase(Locale.getDefault())
                val lowerPackage = wordMatch.first.lowercase(Locale.getDefault())
                val matchCount = queryWords.count { word ->
                    normalizedName.contains(word) || lowerPackage.contains(word)
                }
                if (matchCount > 0) {
                    val confidence = (matchCount.toFloat() / queryWords.size) * 0.8f
                    Log.d(TAG, "Found word match: ${wordMatch.second} ($matchCount/${queryWords.size} words)")
                    return Triple(wordMatch.first, wordMatch.second, confidence)
                }
            }
        }

        // Seventh, fuzzy matching using similarity score
        val scoredApps = apps.map { (packageName, appName) ->
            val normalizedName = appName.lowercase(Locale.getDefault())
            val similarity = calculateSimilarity(cleanQuery, normalizedName)
            Triple(packageName, appName, similarity)
        }.filter { it.third > 0.4f }  // Only consider matches with >40% similarity
            .sortedByDescending { it.third }

        // Log top matches for debugging
        scoredApps.take(3).forEach { (pkg, name, score) ->
            Log.d(TAG, "  Fuzzy candidate: $name ($pkg) similarity=${String.format("%.2f", score)}")
        }

        val bestMatch = scoredApps.firstOrNull()
        return bestMatch?.let { (packageName, appName, similarity) ->
            Log.d(TAG, "Selected by similarity: $appName ($packageName) = ${String.format("%.2f", similarity)}")
            Triple(packageName, appName, similarity * 0.7f)  // Lower confidence for fuzzy matches
        }
    }

    /**
     * Find app by known package prefix patterns.
     * This is the most reliable method for well-known apps.
     */
    private fun findByPackagePrefix(query: String, apps: List<Pair<String, String>>): Pair<String, String>? {
        val prefixes = getPackagePrefixes(query)
        if (prefixes.isEmpty()) return null

        Log.d(TAG, "Looking for package prefixes: $prefixes")

        for (prefix in prefixes) {
            val match = apps.find { (packageName, _) ->
                packageName.lowercase(Locale.getDefault()).startsWith(prefix) ||
                packageName.lowercase(Locale.getDefault()).contains(".$prefix.")
            }
            if (match != null) {
                return match
            }
        }
        return null
    }

    /**
     * Get package prefixes for common apps.
     * Uses prefixes instead of exact package names for better compatibility.
     */
    private fun getPackagePrefixes(query: String): List<String> {
        return when (query) {
            // YouTube
            "youtube", "yt", "you tube" -> listOf("com.google.android.youtube", "com.google.android.apps.youtube")

            // Instagram
            "instagram", "insta", "ig" -> listOf("com.instagram")

            // Facebook
            "facebook", "fb" -> listOf("com.facebook.katana", "com.facebook.lite", "com.facebook")

            // WhatsApp
            "whatsapp", "whats app", "wa" -> listOf("com.whatsapp")

            // Twitter/X
            "twitter", "x", "tw" -> listOf("com.twitter", "com.x")

            // Telegram
            "telegram", "tg" -> listOf("org.telegram")

            // Snapchat
            "snapchat", "snap" -> listOf("com.snapchat")

            // TikTok
            "tiktok", "tik tok", "tt" -> listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.tiktok")

            // Chrome
            "chrome" -> listOf("com.android.chrome", "com.chrome")

            // Gmail
            "gmail", "email", "mail" -> listOf("com.google.android.gm", "com.samsung.android.email")

            // Google Maps
            "maps", "google maps", "navigation" -> listOf("com.google.android.apps.maps")

            // Google Photos
            "photos", "google photos", "gallery" -> listOf("com.google.android.apps.photos", "com.sec.android.gallery", "com.samsung.android.gallery")

            // Camera
            "camera", "cam" -> listOf("camera", "com.android.camera", "com.google.android.GoogleCamera", "com.sec.android.camera")

            // Settings
            "settings", "setting" -> listOf("com.android.settings", "settings")

            // Clock
            "clock", "alarm", "timer" -> listOf("com.google.android.deskclock", "com.sec.android.app.clockpackage", "clock")

            // Calculator
            "calculator", "calc" -> listOf("com.google.android.calculator", "calculator", "calc")

            // Calendar
            "calendar", "cal" -> listOf("com.google.android.calendar", "calendar")

            // Messages/SMS
            "messages", "sms", "text", "messaging" -> listOf("com.google.android.apps.messaging", "com.samsung.android.messaging", "messaging", "messages")

            // Phone/Dialer
            "phone", "dialer", "call" -> listOf("com.google.android.dialer", "com.samsung.android.dialer", "dialer", "phone")

            // Contacts
            "contacts", "contact" -> listOf("com.google.android.contacts", "contacts")

            // Play Store
            "play store", "playstore", "store", "app store" -> listOf("com.android.vending")

            // Spotify
            "spotify", "music" -> listOf("com.spotify.music", "com.spotify")

            // Netflix
            "netflix" -> listOf("com.netflix")

            // Amazon
            "amazon", "amazon shopping" -> listOf("com.amazon")

            // Google
            "google", "google app" -> listOf("com.google.android.googlequicksearchbox")

            // Google Drive
            "drive", "google drive" -> listOf("com.google.android.apps.docs")

            // Files
            "files", "file manager", "my files" -> listOf("com.google.android.apps.nbu.files", "com.sec.android.app.myfiles", "files")

            // Notes
            "notes", "note" -> listOf("com.google.android.keep", "com.samsung.android.app.notes", "note")

            // Discord
            "discord" -> listOf("com.discord")

            // Reddit
            "reddit" -> listOf("com.reddit")

            // LinkedIn
            "linkedin" -> listOf("com.linkedin")

            // Pinterest
            "pinterest" -> listOf("com.pinterest")

            // Uber
            "uber" -> listOf("com.ubercab")

            // Zoom
            "zoom" -> listOf("us.zoom")

            // Browser (generic)
            "browser", "internet" -> listOf("browser", "com.android.chrome", "com.sec.android.app.sbrowser")

            else -> emptyList()
        }
    }

    /**
     * Get common aliases for app names.
     * Helps match "yt" to "youtube", "ig" to "instagram", etc.
     */
    private fun getAppAliases(query: String): List<String> {
        return when {
            query in listOf("yt", "youtube", "you tube") -> listOf("youtube")
            query in listOf("ig", "insta", "instagram") -> listOf("instagram")
            query in listOf("fb", "facebook") -> listOf("facebook")
            query in listOf("wa", "whatsapp", "whats app") -> listOf("whatsapp")
            query in listOf("tw", "twitter", "x") -> listOf("twitter", "x")
            query in listOf("tg", "telegram") -> listOf("telegram")
            query in listOf("snap", "snapchat") -> listOf("snapchat")
            query in listOf("tiktok", "tik tok", "tt") -> listOf("tiktok")
            query in listOf("msg", "messages", "sms") -> listOf("messages", "messaging", "sms")
            query in listOf("chrome", "browser") -> listOf("chrome", "browser")
            query in listOf("gmail", "email", "mail") -> listOf("gmail", "email", "mail")
            query in listOf("maps", "google maps", "navigation") -> listOf("maps")
            query in listOf("photos", "gallery", "pictures") -> listOf("photos", "gallery")
            query in listOf("camera", "cam") -> listOf("camera")
            query in listOf("settings", "setting") -> listOf("settings")
            query in listOf("clock", "alarm", "timer") -> listOf("clock", "alarm")
            query in listOf("calc", "calculator") -> listOf("calculator")
            query in listOf("calendar", "cal") -> listOf("calendar")
            query in listOf("music", "player") -> listOf("music", "player", "spotify", "youtube music")
            query in listOf("files", "file manager") -> listOf("files", "file")
            query in listOf("phone", "dialer", "call") -> listOf("phone", "dialer")
            query in listOf("contacts", "contact") -> listOf("contacts")
            query in listOf("notes", "note") -> listOf("notes", "keep")
            query in listOf("store", "play store", "playstore") -> listOf("play store", "market")
            else -> emptyList()
        }
    }

    /**
     * Find best matching audio from notes.
     */
    private fun findBestAudioMatch(
        query: String,
        notes: List<Note>
    ): Pair<AudioTrack, String>? {
        val normalizedQuery = query.lowercase(Locale.getDefault())
        val queryWords = normalizedQuery.split(" ", "-", "_").filter { it.length > 1 }

        // Collect all audio tracks from notes
        val audioTracks = mutableListOf<Triple<AudioTrack, String, Float>>() // track, noteName, score

        for (note in notes) {
            // Check new attachments system
            val attachments = note.getAttachments()
            for (attachment in attachments) {
                if (isAudioMimeType(attachment.mimeType)) {
                    val score = calculateAudioMatchScore(
                        query = normalizedQuery,
                        queryWords = queryWords,
                        fileName = attachment.fileName,
                        noteTitle = note.title,
                        noteContent = note.content,
                        noteTags = note.tagsJson
                    )
                    if (score > 0) {
                        val track = AudioTrack(
                            uri = attachment.uri,
                            title = attachment.fileName.substringBeforeLast("."),
                            fileName = attachment.fileName,
                            sourceNoteId = note.id,
                            sourceAttachmentId = attachment.id,
                            mimeType = attachment.mimeType
                        )
                        audioTracks.add(Triple(track, note.title, score))
                    }
                }
            }

            // Check legacy fileUri
            if (!note.fileUri.isNullOrEmpty() && isAudioMimeType(note.fileMimeType ?: "")) {
                val score = calculateAudioMatchScore(
                    query = normalizedQuery,
                    queryWords = queryWords,
                    fileName = note.fileName ?: "Unknown",
                    noteTitle = note.title,
                    noteContent = note.content,
                    noteTags = note.tagsJson
                )
                if (score > 0) {
                    val track = AudioTrack(
                        uri = note.fileUri!!,
                        title = (note.fileName ?: note.title).substringBeforeLast("."),
                        fileName = note.fileName,
                        sourceNoteId = note.id,
                        mimeType = note.fileMimeType
                    )
                    audioTracks.add(Triple(track, note.title, score))
                }
            }
        }

        // Sort by score (highest first) and return best match
        val bestMatch = audioTracks.maxByOrNull { it.third }
        return bestMatch?.let { (track, noteName, _) -> track to noteName }
    }

    /**
     * Calculate match score for audio content.
     */
    private fun calculateAudioMatchScore(
        query: String,
        queryWords: List<String>,
        fileName: String,
        noteTitle: String,
        noteContent: String,
        noteTags: String?
    ): Float {
        var score = 0f
        val normalizedFileName = fileName.lowercase(Locale.getDefault()).substringBeforeLast(".")
        val normalizedTitle = noteTitle.lowercase(Locale.getDefault())
        val normalizedContent = noteContent.lowercase(Locale.getDefault())
        val normalizedTags = noteTags?.lowercase(Locale.getDefault()) ?: ""

        // Exact match in filename (highest priority)
        if (normalizedFileName.contains(query)) {
            score += 10f
        }

        // Exact match in title
        if (normalizedTitle.contains(query)) {
            score += 8f
        }

        // Word-level matching in filename
        for (word in queryWords) {
            if (normalizedFileName.contains(word)) score += 3f
            if (normalizedTitle.contains(word)) score += 2f
            if (normalizedContent.contains(word)) score += 1f
            if (normalizedTags.contains(word)) score += 1.5f
        }

        // Fuzzy similarity
        val fileNameSimilarity = calculateSimilarity(query, normalizedFileName)
        if (fileNameSimilarity > 0.5f) {
            score += fileNameSimilarity * 5f
        }

        return score
    }

    /**
     * Check if mime type is audio.
     */
    private fun isAudioMimeType(mimeType: String): Boolean {
        return mimeType.startsWith("audio/") ||
                mimeType in listOf(
            "application/ogg",
            "application/x-ogg"
        )
    }

    /**
     * Calculate string similarity using Levenshtein-like approach.
     */
    private fun calculateSimilarity(s1: String, s2: String): Float {
        if (s1.isEmpty() || s2.isEmpty()) return 0f
        if (s1 == s2) return 1f

        // Count matching characters in order
        var matches = 0
        var s2Index = 0
        for (char in s1) {
            val foundIndex = s2.indexOf(char, s2Index)
            if (foundIndex >= 0) {
                matches++
                s2Index = foundIndex + 1
            }
        }

        return (2f * matches) / (s1.length + s2.length)
    }
}
