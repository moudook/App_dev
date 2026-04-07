package com.example.smarty.server.agent

/**
 * Stores and retrieves examples of successful tool usage to guide the LLM.
 * Uses keyword-based relevance matching to inject the most helpful examples.
 */
class ToolExampleStore {
    data class Example(
        val userQuery: String,
        val toolName: String,
        val arguments: String,
        val reasoning: String,
        val keywords: Set<String>, // Keywords that trigger this example
    )

    private val examples =
        listOf(
            // ==================== NOTES ====================
            Example(
                userQuery = "Create a note about my meeting with John",
                toolName = "create_note",
                arguments = """{"title": "Meeting with John", "content": "Meeting notes with John - to be updated with details."}""",
                reasoning = "User explicitly asked to create/save/make a note. Always use create_note for this.",
                keywords = setOf("note", "save", "write", "create", "remember", "jot", "capture", "record"),
            ),
            Example(
                userQuery = "Save this recipe for pasta carbonara",
                toolName = "create_note",
                arguments = """{"title": "Pasta Carbonara Recipe", "content": "Pasta carbonara recipe from conversation.", "category": "Recipes"}""",
                reasoning = "User wants to save information. create_note is the right tool. I categorize it for organization.",
                keywords = setOf("save", "recipe", "keep", "store", "bookmark"),
            ),
            Example(
                userQuery = "Make a shopping list: milk, eggs, bread, butter",
                toolName = "create_note",
                arguments = """{"title": "Shopping List", "content": "- Milk\n- Eggs\n- Bread\n- Butter", "category": "Lists"}""",
                reasoning = "User wants a list created. I format it as a clean markdown list in a note.",
                keywords = setOf("list", "shopping", "todo", "checklist", "items"),
            ),
            Example(
                userQuery = "Find my notes about Python",
                toolName = "search_notes",
                arguments = """{"query": "Python"}""",
                reasoning = "User is looking for existing notes. search_notes finds relevant matches.",
                keywords = setOf("find", "search", "look", "where", "show me"),
            ),
            // ==================== CALENDAR ====================
            Example(
                userQuery = "Remind me to call Mom tomorrow at 5pm",
                toolName = "schedule_event",
                arguments = """{"title": "Call Mom", "startTime": <calculated_tomorrow_5pm_utc_ms>, "endTime": <calculated_tomorrow_6pm_utc_ms>, "description": "Remember to call Mom", "reminderMinutes": 15}""",
                reasoning = "User wants a reminder. I calculate the exact UTC timestamp for 'tomorrow at 5pm' using the time_context. Default 1 hour duration. Reminder notification set.",
                keywords = setOf("remind", "reminder", "schedule", "meeting", "appointment", "event", "calendar"),
            ),
            Example(
                userQuery = "What's on my calendar for today?",
                toolName = "list_events",
                arguments = """{"date": <current_day_start_utc_ms>}""",
                reasoning = "User wants to see their schedule. I pass the start of today (midnight) as the date parameter.",
                keywords = setOf("calendar", "schedule", "events", "today", "tomorrow", "agenda", "plans"),
            ),
            // ==================== TIMERS & ALARMS ====================
            Example(
                userQuery = "Set a timer for 10 minutes",
                toolName = "set_timer",
                arguments = """{"name": "Timer", "duration": "10 minutes"}""",
                reasoning = "User wants a countdown timer. I use set_timer with the duration as a readable string.",
                keywords = setOf("timer", "countdown", "minutes", "seconds", "hours"),
            ),
            Example(
                userQuery = "Wake me up at 7:30 AM",
                toolName = "set_alarm",
                arguments = """{"name": "Wake up", "time": "7:30 AM"}""",
                reasoning = "User wants an alarm at a specific time. set_alarm handles 'at X o'clock' style requests.",
                keywords = setOf("alarm", "wake", "morning"),
            ),
            // ==================== MEDIA ====================
            Example(
                userQuery = "Play some jazz music",
                toolName = "play_media",
                arguments = """{"query": "jazz music"}""",
                reasoning = "User wants to listen to music. play_media handles music playback with the exact query.",
                keywords = setOf("play", "music", "song", "listen", "audio", "track"),
            ),
            Example(
                userQuery = "Pause the music",
                toolName = "pause_media",
                arguments = "{}",
                reasoning = "User wants to pause current playback.",
                keywords = setOf("pause", "stop", "mute", "quiet"),
            ),
            // ==================== DEVICE ====================
            Example(
                userQuery = "Turn on the wifi",
                toolName = "toggle_setting",
                arguments = """{"setting": "wifi", "enable": true}""",
                reasoning = "User explicitly asked to turn on WiFi. toggle_setting handles device settings.",
                keywords = setOf("wifi", "bluetooth", "flashlight", "turn on", "turn off", "enable", "disable"),
            ),
            Example(
                userQuery = "Open YouTube",
                toolName = "launch_app",
                arguments = """{"packageName": "com.google.android.youtube"}""",
                reasoning = "User wants to open an app. I identify the package name for YouTube.",
                keywords = setOf("open", "launch", "start", "app"),
            ),
            // ==================== CONTEXT/MEMORY ====================
            Example(
                userQuery = "My favorite color is blue",
                toolName = "store_context",
                arguments = """{"content": "User's favorite color is blue", "type": "preference"}""",
                reasoning = "User stated a personal preference. I store it using store_context with type 'preference' for long-term personalization.",
                keywords = setOf("favorite", "prefer", "like", "love", "hate", "always", "never", "i am", "my name"),
            ),
            // ==================== WEB SEARCH ====================
            Example(
                userQuery = "What's the weather like in New York?",
                toolName = "web_search",
                arguments = """{"query": "current weather New York"}""",
                reasoning = "User wants real-time information. web_search provides current data from the internet.",
                keywords = setOf("weather", "news", "latest", "current", "what is", "who is", "how to", "price", "score"),
            ),
            // ==================== NAVIGATION ====================
            Example(
                userQuery = "Go to settings",
                toolName = "navigate",
                arguments = """{"screen": "settings"}""",
                reasoning = "User wants to navigate to a different screen in the app.",
                keywords = setOf("go to", "navigate", "show", "open settings", "open calendar", "home"),
            ),
            // ==================== SHARING ====================
            Example(
                userQuery = "Share this with my friend",
                toolName = "share",
                arguments = """{"content": "Content to share from the conversation", "title": "Shared from Smarty"}""",
                reasoning = "User wants to share content with other apps.",
                keywords = setOf("share", "send", "forward", "copy"),
            ),
        )

    // Common app package names the agent should know
    val commonApps =
        mapOf(
            "youtube" to "com.google.android.youtube",
            "chrome" to "com.android.chrome",
            "gmail" to "com.google.android.gm",
            "maps" to "com.google.android.apps.maps",
            "calendar" to "com.google.android.calendar",
            "camera" to "com.android.camera2",
            "settings" to "com.android.settings",
            "calculator" to "com.google.android.calculator",
            "clock" to "com.google.android.deskclock",
            "contacts" to "com.google.android.contacts",
            "messages" to "com.google.android.apps.messaging",
            "phone" to "com.google.android.dialer",
            "photos" to "com.google.android.apps.photos",
            "whatsapp" to "com.whatsapp",
            "instagram" to "com.instagram.android",
            "twitter" to "com.twitter.android",
            "spotify" to "com.spotify.music",
            "telegram" to "org.telegram.messenger",
        )

    /**
     * Retrieve relevant examples based on keyword matching with the user's query.
     * Always includes at least the create_note example since it's the most commonly needed.
     */
    fun getRelevantExamples(
        query: String,
        maxExamples: Int = 5,
    ): String {
        val lowerQuery = query.lowercase()
        val queryWords = lowerQuery.split(" ", ",", ".", "!", "?").filter { it.length > 2 }.toSet()

        // Score each example by keyword overlap
        val scored =
            examples.map { example ->
                val matchCount =
                    example.keywords.count { keyword ->
                        lowerQuery.contains(keyword) || queryWords.any { word -> keyword.contains(word) }
                    }
                example to matchCount
            }.sortedByDescending { it.second }

        // Take top matches, ensure at least 2 examples
        val relevant = scored.filter { it.second > 0 }.take(maxExamples).map { it.first }

        // If no keyword matches, provide general examples covering the most common actions
        val finalExamples =
            if (relevant.isEmpty()) {
                examples.filter { it.toolName in setOf("create_note", "schedule_event", "web_search") }
            } else {
                relevant
            }

        return buildString {
            append("=== TOOL USAGE EXAMPLES ===\n")
            append(
                "Study these examples carefully. When the user's request matches a pattern, call the corresponding tool IMMEDIATELY without narrating your plan.\n\n",
            )
            finalExamples.forEach { ex ->
                append("User: \"${ex.userQuery}\"\n")
                append("Reasoning: ${ex.reasoning}\n")
                append("Action: ${ex.toolName}(${ex.arguments})\n\n")
            }
            append("=== COMMON APP PACKAGE NAMES ===\n")
            append("When using launch_app, use these package names:\n")
            commonApps.entries.take(10).forEach { (name, pkg) ->
                append("- $name: $pkg\n")
            }
            append("\n")
        }
    }
}
