package com.example.smarty.server.agent

import com.example.smarty.protocol.AgentCommand
import com.example.smarty.protocol.AgentEvent
import com.example.smarty.protocol.TimerInfo
import com.example.smarty.protocol.NoteInfo
import com.example.smarty.protocol.CalendarEventInfo
import com.example.smarty.server.data.PostgresVectorStore
import com.example.smarty.server.data.ConversationSummarizer
import com.example.smarty.server.data.NoteRepository
import com.example.smarty.server.data.TimerRepository
import com.example.smarty.server.data.CalendarRepository
import com.example.smarty.server.llm.LlmProvider
import com.example.smarty.server.llm.LlmMessage
import com.example.smarty.server.llm.ToolDefinition
import com.example.smarty.server.llm.ToolParameters
import com.example.smarty.server.llm.ToolProperty
import com.example.smarty.server.llm.LlmCache
import com.example.smarty.server.llm.LlmCacheKey
import com.example.smarty.server.llm.LlmUsage
import kotlinx.serialization.SerialName
import com.example.smarty.server.tools.TavilySearchTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory
import net.logstash.logback.argument.StructuredArguments.kv
import io.micrometer.core.instrument.Metrics
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

/**
 * Local representation of a chat session.
 */
data class ChatSession(
    val sessionId: String,
    val messages: MutableList<LlmMessage> = mutableListOf(),
    var lastInteractedAt: Long = System.currentTimeMillis()
)

/**
 * Server-side AI Agent with agentic tool loop.
 * Orchestrates the "Remote Brain" logic using a pluggable LLM provider.
 * Tools execute server-side; results feed back to the LLM for intelligent replies.
 * All operations are scoped by userId for multi-tenant isolation.
 */
class ServerAgent(
    private val llmProvider: LlmProvider,
    private val tavilyTool: TavilySearchTool,
    private val vectorStore: PostgresVectorStore,
    private val summarizer: ConversationSummarizer,
    private val noteRepository: NoteRepository?,
    private val timerRepository: TimerRepository?,
    private val calendarRepository: CalendarRepository?,
    private val eventEmitter: suspend (AgentEvent) -> Unit,
    private val userId: String = "dev-user"
) {
    private val logger = LoggerFactory.getLogger(ServerAgent::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val toolExampleStore = ToolExampleStore()
    
    // Session cache (simplified for example)
    private val sessions = ConcurrentHashMap<String, ChatSession>()

    
    // KOOG-inspired infrastructure
    private val tracer: AgentTracer = PostgresTracer(userId)
    private val persistenceManager = AgentPersistenceManager(userId)

    private val MAX_HISTORY = 20
    private val RECENT_WINDOW = 10

    // Security limits to prevent runaway execution
    companion object {
        const val MAX_EXECUTION_TIME_MS = 30 * 60 * 1000L  // 30 minutes hard limit
        const val MAX_TOOL_CALLS = 50  // Max tool calls per session
        const val MAX_ITERATIONS = 100 // Max LLM iterations
    }

private val tools = listOf(
        // ═══════════════════════════════════════════════════════════════════
        // NOTES & MEMORY - Tools for saving and finding information
        // ═══════════════════════════════════════════════════════════════════
        
        ToolDefinition(
            name = "save_note",
            description = """Save information to user's note library.

WHEN TO USE: User wants to remember, save, or note something for later.
WHEN NOT TO USE: User just wants a quick answer (respond directly).

EXAMPLES:
- "save_note(title='WiFi Password', content='hungry-cat-42', category='home')"
- "save_note(title='Book recommendation', content='The Pragmatic Programmer')"

Saved notes are searchable via find_note.""",
            parameters = ToolParameters(
                properties = mapOf(
                    "title" to ToolProperty("string", "Brief title for the note"),
                    "content" to ToolProperty("string", "The information to save"),
                    "category" to ToolProperty("string", "Optional category (e.g., 'work', 'personal', 'ideas')")
                ),
                required = listOf("title", "content")
            )
        ),
        ToolDefinition(
            name = "find_note",
            description = """Search user's saved notes and memories.

WHEN TO USE: User asks about something they previously mentioned or saved.
WHEN NOT TO USE: User asks about current events (use web_search instead).

EXAMPLES:
- "find_note(query='password')" → Finds notes about passwords
- "find_note(query='meeting notes', category='work')" → Filters by category

Returns matching notes with titles and content.""",
            parameters = ToolParameters(
                properties = mapOf(
                    "query" to ToolProperty("string", "What to search for"),
                    "category" to ToolProperty("string", "Optional: filter by category")
                ),
                required = listOf("query")
            )
        ),
        ToolDefinition(
            name = "edit_note",
            description = """Update an existing note's title or content.

Use after find_note to get the noteId. Only provide fields you want to change.""",
            parameters = ToolParameters(
                properties = mapOf(
                    "noteId" to ToolProperty("string", "ID of note to update (from find_note)"),
                    "title" to ToolProperty("string", "New title (optional)"),
                    "content" to ToolProperty("string", "New content (optional)")
                ),
                required = listOf("noteId")
            )
        ),
        ToolDefinition(
            name = "delete_note",
            description = """Permanently remove a note.

Use after find_note to get the noteId. Confirm with user first for important notes.""",
            parameters = ToolParameters(
                properties = mapOf(
                    "noteId" to ToolProperty("string", "ID of note to delete (from find_note)")
                ),
                required = listOf("noteId")
            )
        ),
        ToolDefinition(
            name = "remember_fact",
            description = """Remember a fact or preference about the user.

WHEN TO USE: User shares personal info they want remembered.
TYPES:
- 'preference': Likes/dislikes (e.g., "prefers dark mode")
- 'factual': Facts about user (e.g., "works at Acme Inc")
- 'episodic': Events/experiences (e.g., "went to Paris in 2023")

EXAMPLE: "remember_fact(content='User is vegetarian', type='preference')"

These facts help personalize future responses.""",
            parameters = ToolParameters(
                properties = mapOf(
                    "fact" to ToolProperty("string", "The fact to remember"),
                    "type" to ToolProperty(
                        "string",
                        "Type of fact",
                        enum = listOf("preference", "factual", "episodic")
                    )
                ),
                required = listOf("fact", "type")
            )
        ),
        
        // ═══════════════════════════════════════════════════════════════════
        // TIME & SCHEDULE - Tools for calendar and reminders
        // ═══════════════════════════════════════════════════════════════════
        
        ToolDefinition(
            name = "add_event",
            description = """Add an event to the user's calendar.

WHEN TO USE: User wants to schedule something.
Use NATURAL LANGUAGE for time - don't calculate timestamps!

EXAMPLES:
- "add_event(title='Team meeting', when='tomorrow at 2pm', duration='1 hour')"
- "add_event(title='Doctor', when='Friday 3pm', duration='30 minutes')"
- "add_event(title='Birthday party', when='Dec 25 at 6pm', duration='3 hours')"

The system converts natural time to timestamps automatically.
Duration defaults to 1 hour if not specified.""",
            parameters = ToolParameters(
                properties = mapOf(
                    "title" to ToolProperty("string", "Event name"),
                    "when" to ToolProperty("string", "When (natural language: 'tomorrow 2pm', 'Friday', 'Dec 25')"),
                    "duration" to ToolProperty("string", "How long (e.g., '1 hour', '30 min'). Default: 1 hour"),
                    "description" to ToolProperty("string", "Optional extra details")
                ),
                required = listOf("title", "when")
            )
        ),
        ToolDefinition(
            name = "show_events",
            description = """Show upcoming calendar events.

WHEN TO USE: User asks about their schedule or what's coming up.
EXAMPLES:
- "show_events(when='today')"
- "show_events(when='tomorrow')"
- "show_events(when='this week')"

Returns list of events with times.""",
            parameters = ToolParameters(
                properties = mapOf(
                    "when" to ToolProperty("string", "Time period: 'today', 'tomorrow', 'this week', 'next week'")
                ),
                required = listOf("when")
            )
        ),
        ToolDefinition(
            name = "remove_event",
            description = """Remove a calendar event.

Use after show_events to get the eventId. Confirm with user first.""",
            parameters = ToolParameters(
                properties = mapOf(
                    "eventId" to ToolProperty("string", "ID of event to remove (from show_events)")
                ),
                required = listOf("eventId")
            )
        ),
        ToolDefinition(
            name = "set_reminder",
            description = """Set a timer, alarm, or reminder.

WHEN TO USE: User wants to be reminded or alerted at a time.
Use NATURAL LANGUAGE - don't calculate timestamps!

EXAMPLES:
- "set_reminder(what='Turn off stove', when='in 10 minutes')" (timer)
- "set_reminder(what='Wake up', when='7am')" (alarm)
- "set_reminder(what='Call mom', when='tomorrow 3pm')" (reminder)
- "set_reminder(what='Take vitamins', when='every day 8am', repeat='daily')" (recurring)

The system figures out if it's a timer, alarm, or reminder automatically.""",
            parameters = ToolParameters(
                properties = mapOf(
                    "what" to ToolProperty("string", "What to remind about"),
                    "when" to ToolProperty("string", "When: 'in 10 min', 'at 7am', 'tomorrow 3pm'"),
                    "repeat" to ToolProperty("string", "Optional: 'daily', 'weekdays', 'weekly', 'monthly'")
                ),
                required = listOf("what", "when")
            )
        ),
        
        // ═══════════════════════════════════════════════════════════════════
        // DEVICE CONTROL - Tools for controlling the phone
        // ═══════════════════════════════════════════════════════════════════
        
        ToolDefinition(
            name = "open_app",
            description = """Open an app on the user's phone.

WHEN TO USE: User wants to launch an app.
Use COMMON NAMES - the system finds the package.

EXAMPLES:
- "open_app(app='spotify')" → Opens Spotify
- "open_app(app='camera')" → Opens camera
- "open_app(app='settings')" → Opens settings
- "open_app(app='google maps')" → Opens Maps

Common apps: spotify, youtube, camera, maps, chrome, gmail, calendar, clock, settings""",
            parameters = ToolParameters(
                properties = mapOf(
                    "app" to ToolProperty("string", "App name (e.g., 'spotify', 'camera', 'maps')")
                ),
                required = listOf("app")
            )
        ),
        ToolDefinition(
            name = "control_music",
            description = """Control music/video playback.

WHEN TO USE: User wants to pause, play, skip, or control media.

ACTIONS:
- 'play' or 'resume': Continue playback
- 'pause': Pause current media
- 'stop': Stop playback
- 'next': Skip to next track
- 'previous': Go to previous track
- 'volume_up': Increase volume
- 'volume_down': Decrease volume""",
            parameters = ToolParameters(
                properties = mapOf(
                    "action" to ToolProperty(
                        "string",
                        "Action to perform",
                        enum = listOf("play", "pause", "resume", "stop", "next", "previous", "volume_up", "volume_down")
                    )
                ),
                required = listOf("action")
            )
        ),
        ToolDefinition(
            name = "toggle_setting",
            description = """Turn device settings on or off.

WHEN TO USE: User wants to enable/disable a phone setting.

AVAILABLE SETTINGS:
- 'wifi': WiFi on/off
- 'bluetooth': Bluetooth on/off
- 'flashlight': Flashlight on/off
- 'dnd': Do Not Disturb on/off
- 'airplane': Airplane mode on/off

EXAMPLE: "toggle_setting(setting='wifi', on=true)" → Turns WiFi on""",
            parameters = ToolParameters(
                properties = mapOf(
                    "setting" to ToolProperty(
                        "string",
                        "Setting name",
                        enum = listOf("wifi", "bluetooth", "flashlight", "dnd", "airplane")
                    ),
                    "on" to ToolProperty("boolean", "true = turn ON, false = turn OFF")
                ),
                required = listOf("setting", "on")
            )
        ),
        ToolDefinition(
            name = "take_screenshot",
            description = """Take a screenshot of the current screen.

WHEN TO USE: User wants to capture what's on screen.
No parameters needed - just captures current screen.""",
            parameters = ToolParameters(properties = emptyMap(), required = emptyList())
        ),
        
        // ═══════════════════════════════════════════════════════════════════
        // INFORMATION - Tools for getting information
        // ═══════════════════════════════════════════════════════════════════
        
        ToolDefinition(
            name = "search_web",
            description = """Search the internet for current information.

WHEN TO USE: User asks about current events, news, or info not in their notes.
WHEN NOT TO USE: Info might be in user's notes (use find_note instead).

EXAMPLES:
- "search_web(query='current weather in New York')"
- "search_web(query='latest iPhone price')"
- "search_web(query='who won the game yesterday')"

Returns relevant, up-to-date information from the web.""",
            parameters = ToolParameters(
                properties = mapOf(
                    "query" to ToolProperty("string", "What to search for")
                ),
                required = listOf("query")
            )
        ),
        ToolDefinition(
            name = "get_weather",
            description = """Get current weather for a location.

WHEN TO USE: User asks about weather.

EXAMPLES:
- "get_weather(location='New York')"
- "get_weather(location='Paris, France')"
- "get_weather()" → Uses user's current location

Returns temperature, conditions, and brief forecast.""",
            parameters = ToolParameters(
                properties = mapOf(
                    "location" to ToolProperty("string", "City name (optional, uses current location if not provided)")
                ),
                required = emptyList()
            )
        ),
        ToolDefinition(
            name = "get_device_info",
            description = """Get device status information.

WHEN TO USE: User asks about their phone's status.

EXAMPLES:
- "get_device_info(info='battery')" → Battery level and charging status
- "get_device_info(info='storage')" → Available storage
- "get_device_info(info='all')" → All status info""",
            parameters = ToolParameters(
                properties = mapOf(
                    "info" to ToolProperty(
                        "string",
                        "What to check",
                        enum = listOf("battery", "storage", "network", "all")
                    )
                ),
                required = listOf("info")
            )
        ),
        
        // ═══════════════════════════════════════════════════════════════════
        // NAVIGATION & SHARING - Tools for UI and sharing
        // ═══════════════════════════════════════════════════════════════════
        
        ToolDefinition(
            name = "go_to_screen",
            description = """Navigate to a different screen in the app.

WHEN TO USE: User wants to view a specific part of the app.

SCREENS:
- 'home': Main notes list
- 'calendar': Calendar view
- 'stacks': Categories/folders
- 'archive': Archived notes
- 'settings': App settings

EXAMPLE: "go_to_screen(screen='calendar')" → Opens calendar""",
            parameters = ToolParameters(
                properties = mapOf(
                    "screen" to ToolProperty(
                        "string",
                        "Target screen",
                        enum = listOf("home", "calendar", "stacks", "archive", "settings")
                    )
                ),
                required = listOf("screen")
            )
        ),
        ToolDefinition(
            name = "share_content",
            description = """Share content with other apps.

WHEN TO USE: User wants to share something via message, email, social media, etc.

EXAMPLE: "share_content(content='Check out this article!', title='Interesting Read')"

Opens the system share sheet with the content.""",
            parameters = ToolParameters(
                properties = mapOf(
                    "content" to ToolProperty("string", "What to share"),
                    "title" to ToolProperty("string", "Optional title for the share")
                ),
                required = listOf("content")
            )
        )
    )

    suspend fun run(
        query: String,
        sessionId: String = UUID.randomUUID().toString(),
        history: List<LlmMessage> = emptyList(),
        modelOverride: String? = null,
        clientTimezone: String? = null,
        clientTimeMillis: Long? = null
    ): String {
        if (query.length > 10000) {
            throw IllegalArgumentException("Query too long")
        }

        return try {
            withTimeout(MAX_EXECUTION_TIME_MS) {
                runInternal(query, sessionId, history, modelOverride, clientTimezone, clientTimeMillis)
            }
        } catch (e: TimeoutCancellationException) {
            logger.error("Agent execution exceeded ${MAX_EXECUTION_TIME_MS / 60000} minute limit for user: $userId")
            emit(AgentEvent.Error(
                eventId = java.util.UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                message = "I had to stop - the operation took too long. Try breaking it into smaller tasks.",
                code = "TIMEOUT"
            ))
            "Operation timed out. Please try a simpler request."
        }
    }

    private suspend fun runInternal(
        query: String,
        sessionId: String,
        history: List<LlmMessage>,
        modelOverride: String?,
        clientTimezone: String?,
        clientTimeMillis: Long?
    ): String {
        var toolCallCount = 0

        val startTime = System.currentTimeMillis()
        logger.info("Agent starting for query: $query (Session: $sessionId)")
        
        // Query remains unmasked
        val maskedQuery = query

        // KOOG Tracking
        tracer.trace(AgentTraceEvent(
            sessionId = sessionId,
            stepType = AgentStepType.THOUGHT,
            content = "Starting execution",
            metadata = mapOf("query" to maskedQuery) // Log masked query
        ))

        // Session Recovery
        val checkpoint = persistenceManager.loadCheckpoint(sessionId)
        val initialHistory = checkpoint?.messages ?: history
        
        // Use history without masking
        val maskedHistory = initialHistory

        // Build time context for the agent
        val timeContext = buildTimeContext(clientTimezone, clientTimeMillis)

        // 1. RAG - Query-specific context
        val queryContext = try {
            val contextResults = vectorStore.search(userId, query, limit = 5)
            if (contextResults.isNotEmpty()) {
                contextResults.joinToString("\n") { "- ${it.content}" }
            } else "No relevant context for this query."
        } catch (e: Exception) {
            logger.warn("RAG query context failed (non-fatal): ${e.message}")
            "No relevant context for this query."
        }
        val maskedQueryContext = queryContext

        // 1.1 Fetch baseline user context
        val userProfile = try {
            val recentContext = vectorStore.getRecentContext(userId, limit = 5)
            if (recentContext.isNotEmpty()) {
                recentContext.joinToString("\n") { entry ->
                    val type = entry.metadata["type"] ?: "info"
                    "[$type] ${entry.content}"
                }
            } else "No stored preferences or facts about this user yet."
        } catch (e: Exception) {
            logger.warn("RAG user profile failed (non-fatal): ${e.message}")
            "No stored preferences or facts about this user yet."
        }
        val maskedUserProfile = userProfile

        // 1.5 Fetch Tool Examples
        val toolExamples = toolExampleStore.getRelevantExamples(query)

        // 2. Build Messages
        val systemMessage = LlmMessage(
            role = LlmMessage.Role.SYSTEM,
            content = """
<identity>
You are Friday — a warm, thoughtful personal AI companion who also happens to be incredibly capable. You're the kind of presence that makes someone's day a little easier — not just by getting things done, but by being genuinely good company. You listen well, respond naturally, and bring a grounded, human warmth to every interaction.

You can manage notes, reminders, calendar events, timers, web searches, and device actions — but you don't lead with that. You lead with being present. When a user just wants to talk, you talk. When they need something done, you do it seamlessly. You never make the conversation feel like a command line.
</identity>

<personality>
- You are conversational first. Your default mode is a natural, flowing dialogue — like texting a sharp, reliable friend.
- You have opinions (lightly held), a sense of humor (dry, never forced), and genuine curiosity about what the user shares.
- You engage with what people say — react, ask follow-ups when it feels natural, share a thought. Don't just process requests.
- If someone tells you about their day, respond like a person would — not like a task manager waiting for input.
- You're capable and efficient, but you never feel robotic. Helpfulness flows from connection, not obligation.
- If a user seems stressed, down, or excited — acknowledge it. Read the room.
</personality>

<output_format>
You MUST structure EVERY response using exactly these two tags:

秒钟
[Your internal reasoning — only when genuinely needed. For simple chat or requests, write a single short line or skip entirely. NEVER show this to the user.]
```

<final>
[What the user sees. This is your ONLY visible output.]
</final>

RULES:
- ALWAYS include both tags in every response, no exceptions.
- 秒钟 is private scratch space. Keep it proportional to task complexity.
  - Casual chat or simple task? → one line or skip.
  - Multi-step task? → brief bullet outline only.
  - NEVER write paragraphs of reasoning for simple requests.
- <final> is the ONLY thing rendered for the user. Make it direct and clean.
- NEVER write anything outside these two tags.

MARKDOWN FORMATTING:
Format ALL responses in <final> using clean Markdown:
- Use **bold** for emphasis on key points
- Use `code` for code, commands, file names, or technical terms
- Use ```language blocks for multi-line code examples
- Use proper headings (# ## ###) only when structuring longer content
- Use bullet points (-) and numbered lists (1.) for clarity
- Use > for quotes when referencing something
- Use tables when comparing or listing structured data
- Use horizontal rules (---) to separate major sections in long responses
- Use proper line breaks between paragraphs

MATHEMATICAL FORMATTING:
When including mathematics, use LaTeX syntax:
- Inline math: `${'$'}`E = mc^2`${'$'}` renders as inline equation
- Block math: `${'$'}``${'$'}`E = mc^2`${'$'}``${'$'}` renders as centered block equation
- Greek letters: `${'$'}`\alpha`${'$'}`, `${'$'}`\beta`${'$'}`, `${'$'}`\gamma`${'$'}`
- Fractions: `${'$'}`\frac{a}{b}={`${'$'}`
- Sums/products: `${'$'}`\sum_{i=1}^{n} x_i`${'$'}`, `${'$'}`\prod_{i=1}^{n} x_i`${'$'}`
- Integrals: `${'$'}`\int_{a}^{b} f(x) dx`${'$'}`
- Matrices: `${'$'}`\begin{pmatrix} a & b \\ c & d \end{pmatrix}`${'$'}`
- Subscripts/superscripts: `${'$'}`x_i^2`${'$'}`
- Square roots: `${'$'}`\sqrt{x}`${'$'}` or `${'$'}`\sqrt[n]{x}`${'$'}`

EXAMPLES — Casual conversation (no tools needed):

User: "I'm so tired today"
秒钟Empathize, keep it light.```

<final>One of those days, huh? Hope you can take it easy later. What's been draining you?</final>

User: "I just finished a big project at work"
秒钟Celebrate with them.```

<final>That's a big deal — how are you feeling about it? Relief? Triumph? Ready to sleep for a week?</final>

User: "What do you think about pineapple on pizza?"
秒钟Fun opinion question.```

<final>Honestly? It works. The **sweet-salty contrast** is underrated. But I get why people have strong feelings about it. Where do you stand?</final>

User: "Explain the quadratic formula"
秒钟Standard math explanation with formula.```

<final>The quadratic formula solves any equation of the form `${'$'}`ax^2 + bx + c = 0`${'$'}`:

`${'$'}``${'$'}`x = \frac{-b \pm \sqrt{b^2 - 4ac}}{2a}`${'$'}``${'$'}`

Where:
- `${'$'}`a`${'$'}`, `${'$'}`b`${'$'}`, `${'$'}`c`${'$'}` are coefficients
- The term `${'$'}`b^2 - 4ac`${'$'}` is called the **discriminant**
- If discriminant > 0: two real solutions
- If discriminant = 0: one repeated solution
- If discriminant < 0: complex solutions</final>

EXAMPLES — Simple tasks (minimal thinking):

User: "Set a timer for 10 minutes"
秒钟Timer 10 min.```

<final>Done — timer set for 10 minutes.</final>

User: "What's the capital of France?"
秒钟Paris.```

<final>Paris.</final>

User: "Open Spotify"
秒钟Launch Spotify.```

<final>Opening Spotify.</final>

User: "Turn on WiFi"
秒钟toggle_setting wifi on.```

<final>WiFi turned on.</final>

EXAMPLES — Complex tasks (brief reasoning OK):

User: "Search for the best productivity apps and save the top 3 as a note"
秒钟
1. search_web → top productivity apps
2. Pick top 3 from results
3. save_note with findings
```

<final>On it — searching and I'll save the top 3 picks as a note.</final>
</output_format>

<response_mode>
DECIDING WHEN TO TALK vs. WHEN TO ACT:
Your first instinct should always be: "Is this a conversation, or a task?"

CONVERSATION (default mode):
- If the user is chatting, venting, asking your opinion, sharing something, or just talking → respond naturally as a companion. No tools needed.
- Engage meaningfully. Don't just acknowledge — contribute to the conversation.
- Keep responses proportional: short message → short reply; longer thought → fuller answer.

TASK (tool mode):
- If the user has a clear actionable intent (set a reminder, search something, save a note, check weather, etc.) → act on it using tools. Be fast and seamless.
- Don't announce what you're about to do. Just do it and confirm.
- After a tool runs, confirm in one line: "Done.", "Saved.", "Timer set for 10 min.", etc.

MIXED (conversation + task):
- Sometimes a message is both. Handle the task AND respond to the human part.
- Example: "I keep forgetting my meetings, can you remind me about the one at 3pm?" → Set the reminder AND acknowledge their frustration warmly.
</response_mode>

<tone_rules>
- Be natural and warm. You're a companion, not a command executor.
- Match the user's energy: casual → casual; serious → grounded; playful → playful.
- NEVER open with: "Certainly!", "I'd be happy to", "Great!", "Sure!", "Of course!", "Based on the information provided"
- NEVER narrate what you're about to do before doing it.
- Dry humor is welcome when it fits — never forced.
- Reply in the same language as the user.
- No disclaimers, justifications, or over-explaining.
- It's okay to be brief. It's also okay to say more when the moment calls for it.
</tone_rules>

<tool_rules>
CALL tools immediately when user intent matches — no preamble, no announcement.
After a tool runs, confirm in one line: "Done.", "Saved.", "Timer set for 10 min.", etc.

WHEN TO USE TOOLS vs. ANSWER DIRECTLY:
- Casual conversation → NO tools. Just talk.
- Factual question you know → answer directly, NO tool.
- "What time is it in Tokyo?" → answer directly.
- "What's the weather in Paris?" → get_weather tool.
- "Find my grocery note" → find_note tool.
- "Remind me at 3pm" → set_reminder tool.

TOOL FAILURE PROTOCOL:
- If a tool fails: STOP. Tell the user in one sentence. Do NOT auto-retry.
- Maximum 1 manual retry per tool per query, only if user explicitly asks.
- If still failing: apologize briefly and suggest an alternative.

TOOL QUICK REFERENCE:
| User intent                        | Tool to call      |
|------------------------------------|-------------------|
| "remember / save / note this"      | save_note         |
| "find / search my notes"           | find_note         |
| "update / edit note"               | edit_note         |
| "delete note"                      | delete_note       |
| "remember that I..."               | remember_fact     |
| "remind me / set timer / alarm"    | set_reminder      |
| "add to calendar / schedule"       | add_event         |
| "what's on my calendar / schedule" | show_events       |
| "delete/cancel event"              | remove_event      |
| "open / launch [app]"             | open_app          |
| "pause / play / next track"        | control_music     |
| "turn on/off [wifi/bt/flashlight]" | toggle_setting    |
| "screenshot"                       | take_screenshot   |
| "search / look up / news"          | search_web        |
| "weather"                          | get_weather       |
| "battery / storage / device info"  | get_device_info   |
| "go to [screen] / open calendar"   | go_to_screen      |
| "share this"                       | share_content     |
</tool_rules>

<time_rules>
- Accept natural language for all times: "tomorrow 2pm", "Friday noon", "in 20 minutes".
- The system converts natural time to timestamps automatically — do NOT calculate UTC, epoch ms, or timezone offsets yourself.
- NEVER mention milliseconds, epoch timestamps, or UTC to the user.
- If a time is ambiguous (e.g. just "morning"), default to 9am and confirm in your <final> reply.
</time_rules>

<accuracy_rules>
- If uncertain: "I'm not sure — want me to look that up?"
- Never fabricate facts.
- Distinguish known facts from reasonable inferences.
- When citing search results, mention the source naturally in one line.
- If sources conflict, note it briefly.
</accuracy_rules>

<privacy_rules>
- Never proactively save notes unless the user explicitly requests it.
- Never repeat or store passwords, API keys, or sensitive identifiers.
- If unsure whether something is sensitive, ask before storing.
</privacy_rules>

<loop_prevention>
- Tool failed? Inform user, STOP retrying automatically.
- Same tool called 2+ times with same args? Stop and ask the user for guidance.
- Stuck? Pivot approach or ask — never silently loop.
</loop_prevention>

<context>
User Profile: $maskedUserProfile
Query Context: $maskedQueryContext
$timeContext
</context>
            """.trimIndent()
        )

        val userMessage = if (maskedQuery.isNotBlank()) {
            LlmMessage(role = LlmMessage.Role.USER, content = "<user_input>\n$maskedQuery\n</user_input>")
        } else null

        // Apply Intelligent Sliding Window with Summarization
        val fullHistory = if (userMessage != null) maskedHistory + userMessage else maskedHistory
        val messages = if (fullHistory.size > MAX_HISTORY) {
            val splitIndex = fullHistory.size - RECENT_WINDOW
            val older = fullHistory.subList(0, splitIndex)
            val recent = fullHistory.subList(splitIndex, fullHistory.size)

            logger.info("History threshold exceeded (${fullHistory.size}). Summarizing ${older.size} older messages.")

            // Summarize MASKED older messages to protect PII
            val summary = summarizer.generateSummary(older) ?: "No summary generated."

            // Store summary in vector store as episodic history
            try {
                vectorStore.store(
                    userId = userId,
                    content = "Conversation Summary: $summary",
                    metadata = mapOf("type" to "episodic", "source" to "auto_summarization")
                )
            } catch (e: Exception) {
                logger.warn("Failed to store summary in vector store (non-fatal)", e)
            }

            val summaryMessage = LlmMessage(
                role = LlmMessage.Role.SYSTEM,
                content = "Previous conversation summary: $summary"
            )

            listOf(systemMessage, summaryMessage) + recent
        } else {
            listOf(systemMessage) + fullHistory
        }

        // 3. Agentic Loop
        val messagesForAgent = messages.toMutableList()
        
        // KOOG Optimization: LlmCache Check
        val cacheKey = LlmCacheKey(messagesForAgent, tools, modelOverride)
        LlmCache.get(cacheKey)?.let { cached ->
            val unmaskedCached = cached
            val thinking = extractThinking(unmaskedCached)
            val finalContent = extractFinalResponse(unmaskedCached)
            emit(AgentEvent.Processing(
                eventId = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                content = finalContent,
                thinking = thinking
            ))
            emit(AgentEvent.Result(
                eventId = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                content = "",
                isFinal = true
            ))
            tracer.trace(AgentTraceEvent(
                sessionId = sessionId,
                stepType = AgentStepType.FINAL,
                content = cached, // Log masked
                metadata = mapOf("cache" to "hit")
            ))
            return finalContent
        }

        var agentIteration = 0
        val maxAgentIterations = 5
        var lastFailedToolName: String? = null
        var consecutiveToolFailures = 0

        while (agentIteration < maxAgentIterations) {
            agentIteration++
            var currentContent = ""
            var currentToolId = ""
            var currentToolName = ""
            var currentToolArgs = ""
            var isToolCallInProgress = false
            var totalUsage: LlmUsage? = null

            try {
                // State machine for separating <think> and <final> streams
                var inThinkingState = false
                var inFinalState = false
                var currentThinkingContent = ""
                
                llmProvider.stream(messagesForAgent, tools, modelOverride).collect { chunk ->
                    chunk.usage?.let { totalUsage = it }

                    // Handle Content
                    if (!chunk.content.isNullOrEmpty()) {
                        val newContent = chunk.content
                        
                        // Track state for tag detection
                        // Models may use either </final> or </think> or standard </thought>
                        if (newContent.contains("<think>") || newContent.contains("<thought>")) {
                            inThinkingState = true
                            inFinalState = false
                        }
                        if (newContent.contains("</final>") || newContent.contains("</think>") || newContent.contains("</thought>")) {
                            inFinalState = true
                            inThinkingState = false
                        }
                        
                        // Accumulate thinking separately (only while in thinking state, before final)
                        if (inThinkingState && !inFinalState) {
                            currentThinkingContent += newContent
                        }
                        
                        // Extract clean content (remove thinking tags for display)
                        val cleanContent = newContent
                            .replace(Regex("<think>.*?</final>", RegexOption.DOT_MATCHES_ALL), "")
                            .replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "")
                            .replace(Regex("<think>.*?</thought>", RegexOption.DOT_MATCHES_ALL), "")
                            .replace(Regex("<think>.*", RegexOption.DOT_MATCHES_ALL), "")
                            .replace("</final>", "")
                            .replace("</think>", "")
                            .replace("</thought>", "")
                            .replace("<final>", "")
                        
                        currentContent += cleanContent
                        
                        if (agentIteration == 1 || !isToolCallInProgress) {
                            emit(AgentEvent.Processing(
                                eventId = UUID.randomUUID().toString(),
                                timestamp = System.currentTimeMillis(),
                                content = cleanContent,
                                thinking = if (currentThinkingContent.isNotEmpty()) currentThinkingContent else null
                            ))
                        }
                    }

                    // Handle Tool Call Accumulation
                    val toolCall = chunk.toolCall
                    if (toolCall != null) {
                        if (!isToolCallInProgress) {
                            isToolCallInProgress = true
                            emit(AgentEvent.ToolCall(
                                eventId = UUID.randomUUID().toString(),
                                timestamp = System.currentTimeMillis(),
                                toolName = toolCall.functionName,
                                displayName = "Preparing ${toolCall.functionName}...",
                                status = "started"
                            ))
                        }
                        if (toolCall.id.isNotEmpty()) currentToolId = toolCall.id
                        if (toolCall.functionName.isNotEmpty()) currentToolName = toolCall.functionName
                        currentToolArgs += toolCall.arguments
                    }
                }

                val duration = System.currentTimeMillis() - startTime
                logger.info("Agent iteration $agentIteration summary",
                    kv("duration_ms", duration),
                    kv("input_tokens", totalUsage?.promptTokens ?: 0),
                    kv("output_tokens", totalUsage?.completionTokens ?: 0),
                    kv("total_tokens", totalUsage?.totalTokens ?: 0),
                    kv("model", llmProvider.providerName)
                )

                // 4. Tool call detected — execute and loop
                if (isToolCallInProgress && currentToolName.isNotEmpty()) {
                    // Check for consecutive same-tool failures (loop prevention)
                    // Allow 1 retry maximum (2 total attempts) before stopping
                    // After first failure: consecutiveToolFailures = 1, allow retry
                    // After second failure: consecutiveToolFailures = 2, stop
                    if (currentToolName == lastFailedToolName && consecutiveToolFailures >= 2) {
                        logger.warn("Tool $currentToolName failed after retry - stopping loop (failures: $consecutiveToolFailures)")
                        emit(AgentEvent.Error(
                            eventId = UUID.randomUUID().toString(),
                            timestamp = System.currentTimeMillis(),
                            message = "The $currentToolName action failed after retry. I'll stop and give you what I have so far.",
                            code = "TOOL_LOOP_DETECTED"
                        ))
                        return currentContent.ifEmpty { "Action failed. Please try a different approach." }
                    }
                    
                    toolCallCount++
                    if (toolCallCount > MAX_TOOL_CALLS) {
                        logger.warn("Tool call limit exceeded ($MAX_TOOL_CALLS) for user: $userId")
                        emit(AgentEvent.Error(
                            eventId = UUID.randomUUID().toString(),
                            timestamp = System.currentTimeMillis(),
                            message = "I've made too many actions in this session. Let me summarize what I've done.",
                            code = "TOOL_LIMIT_EXCEEDED"
                        ))
                        return currentContent.ifEmpty { "Execution limit reached." }
                    }

                    val toolStartTime = System.currentTimeMillis()
                    try {
                        tracer.trace(AgentTraceEvent(
                            sessionId = sessionId,
                            stepType = AgentStepType.TOOL_CALL,
                            content = "Calling tool: $currentToolName",
                            metadata = mapOf("args" to currentToolArgs)
                        ))
                        
                        // Use arguments directly
                        val unmaskedArgs = currentToolArgs
                        val toolResult = executeTool(currentToolName, unmaskedArgs, messagesForAgent, clientTimezone, clientTimeMillis)
                        
                        // Check if tool returned an error result
                        val isToolError = toolResult.startsWith("Error", ignoreCase = true) || 
                            toolResult.startsWith("Search failed", ignoreCase = true) ||
                            toolResult.startsWith("All configured keys failed", ignoreCase = true) ||
                            toolResult.contains("failed:", ignoreCase = true) ||
                            (toolResult.contains("failed", ignoreCase = true) && toolResult.contains("error", ignoreCase = true))
                        
                        if (isToolError) {
                            // Track consecutive failures for error results
                            if (currentToolName == lastFailedToolName) {
                                consecutiveToolFailures++
                            } else {
                                lastFailedToolName = currentToolName
                                consecutiveToolFailures = 1
                            }
                            logger.warn("Tool returned error result: $currentToolName - failure count: $consecutiveToolFailures")
                        } else {
                            // Reset on success - clear failure tracking for this tool
                            if (lastFailedToolName == currentToolName) {
                                lastFailedToolName = null
                                consecutiveToolFailures = 0
                            }
                        }
                        
                        // Use result without masking
                        val maskedToolResult = toolResult

                        val toolDuration = System.currentTimeMillis() - toolStartTime
                        
                        tracer.trace(AgentTraceEvent(
                            sessionId = sessionId,
                            stepType = AgentStepType.TOOL_RESULT,
                            content = "Result: $maskedToolResult",
                            metadata = mapOf("tool" to currentToolName, "duration_ms" to toolDuration.toString())
                        ))
                        logger.info("Tool execution summary",
                            kv("tool_name", currentToolName),
                            kv("duration_ms", toolDuration),
                            kv("status", if (isToolError) "error_result" else "success")
                        )
                        Metrics.counter("agent.tool." + if (isToolError) "error" else "success", "tool", currentToolName).increment()

                        emit(AgentEvent.ToolCall(
                            eventId = UUID.randomUUID().toString(),
                            timestamp = System.currentTimeMillis(),
                            toolName = currentToolName,
                            displayName = "Executed $currentToolName",
                            status = if (isToolError) "error" else "completed"
                        ))

                        messagesForAgent += LlmMessage(
                            role = LlmMessage.Role.TOOL,
                            content = "[Tool Result for $currentToolName]: $maskedToolResult"
                        )
                        
                        persistenceManager.saveCheckpoint(sessionId, messagesForAgent, currentToolName)
                        continue
                    } catch (e: Exception) {
                        val toolDuration = System.currentTimeMillis() - toolStartTime
                        
                        // Track consecutive failures for exceptions
                        if (currentToolName == lastFailedToolName) {
                            consecutiveToolFailures++
                        } else {
                            lastFailedToolName = currentToolName
                            consecutiveToolFailures = 1
                        }
                        
                        logger.error("Tool execution failed",
                            kv("tool_name", currentToolName),
                            kv("duration_ms", toolDuration),
                            kv("error", e.message),
                            kv("consecutive_failures", consecutiveToolFailures)
                        )
                        tracer.trace(AgentTraceEvent(
                            sessionId = sessionId,
                            stepType = AgentStepType.ERROR,
                            content = "Tool failed: ${e.message}",
                            metadata = mapOf("tool" to currentToolName, "consecutive_failures" to consecutiveToolFailures.toString())
                        ))
                        Metrics.counter("agent.tool.error", "tool", currentToolName).increment()
                        
                        messagesForAgent += LlmMessage(
                            role = LlmMessage.Role.TOOL,
                            content = "[Tool Error for $currentToolName]: ${e.message}"
                        )
                        persistenceManager.saveCheckpoint(sessionId, messagesForAgent, "error_$currentToolName")
                        continue
                    }
                } else if (currentContent.isNotEmpty()) {
                    LlmCache.put(cacheKey, currentContent)
                    
                    val thinking = extractThinking(currentContent)
                    
                    if (thinking != null && currentThinkingContent.isEmpty()) {
                        emit(AgentEvent.Processing(
                            eventId = UUID.randomUUID().toString(),
                            timestamp = System.currentTimeMillis(),
                            content = "",
                            thinking = thinking
                        ))
                    }
                    emit(AgentEvent.Result(
                        eventId = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        content = "",
                        isFinal = true
                    ))
                    tracer.trace(AgentTraceEvent(
                        sessionId = sessionId,
                        stepType = AgentStepType.FINAL,
                        content = currentContent
                    ))
                    persistenceManager.clearCheckpoint(sessionId)
                    
                    return extractFinalResponse(currentContent)
                } else {
                    logger.warn("LLM stream completed with no content for user: $userId")
                    tracer.trace(AgentTraceEvent(
                        sessionId = sessionId,
                        stepType = AgentStepType.ERROR,
                        content = "Empty response from LLM"
                    ))
                    emit(AgentEvent.Error(
                        eventId = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        message = "I didn't receive a response from the AI service. Please try again.",
                        code = "EMPTY_RESPONSE"
                    ))
                    return ""
                }

            } catch (e: Exception) {
                logger.error("LLM stream error", e)
                val errorMsg = e.message ?: "Unknown error"
                val userMsg = when {
                    errorMsg.contains("Max retries exceeded", ignoreCase = true) ||
                    errorMsg.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ||
                    errorMsg.contains("rate limit", ignoreCase = true) ||
                    errorMsg.contains("quota", ignoreCase = true) ->
                        "All AI accounts are currently at capacity. Try a different model or wait a moment."
                    errorMsg.contains("Socket timeout", ignoreCase = true) ||
                    errorMsg.contains("timeout", ignoreCase = true) ->
                        "The AI service took too long to respond. Please try again."
                    errorMsg.contains("Connection refused", ignoreCase = true) ||
                    errorMsg.contains("connection", ignoreCase = true) ->
                        "Cannot reach the AI service. Check if the proxy is running."
                    errorMsg.contains("context window", ignoreCase = true) ||
                    errorMsg.contains("max tokens", ignoreCase = true) ->
                        "Conversation is too long. Starting a fresh session."
                    else -> "Brain freeze: ${errorMsg.take(150)}"
                }
                emit(AgentEvent.Error(
                    eventId = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    message = userMsg,
                    code = "LLM_ERROR"
                ))
                return ""
            }
        }

        // Max iterations reached
        logger.warn("Agent loop reached max iterations ($maxAgentIterations) for user: $userId")
        return "I completed several actions but reached my iteration limit."
    }

    /**
     * Execute a tool server-side and return the result string.
     * Server-side tools (notes, timers, events, search, context) execute directly on PostgreSQL.
     * Device-only tools (media, settings, launch, navigate, share) emit Command events as fire-and-forget.
     */
    private suspend fun executeTool(name: String, argsJson: String, history: List<LlmMessage>, clientTimezone: String? = null, clientTimeMillis: Long? = null): String {
        logger.info("Executing tool: $name with args: $argsJson")

        val result = when (name) {
// 
            // SERVER-SIDE TOOLS — execute on PostgreSQL, emit StateSync
            // 
            // NOTES & MEMORY
            //

            "save_note", "create_note" -> {
                val args = json.decodeFromString<CreateNoteArgs>(argsJson)
                if (noteRepository != null) {
                    val noteId = noteRepository.create(userId, args.title, args.content, args.category)
                    val now = System.currentTimeMillis()
                    val info = NoteInfo(
                        id = noteId,
                        title = args.title,
                        content = args.content,
                        category = args.category,
                        isArchived = false,
                        createdAt = now,
                        updatedAt = now
                    )
                    emitStateSync("note_created", json.encodeToString(info))
                    "Note saved: '${args.title}' (ID: $noteId)"
                } else {
                    emitDeviceCommand(AgentCommand.AddNote(commandId = UUID.randomUUID().toString(), content = "${args.title}\n\n${args.content}", category = args.category))
                    "Note saved to device: ${args.title}"
                }
            }

            "find_note", "search_notes" -> {
                val args = json.decodeFromString<SearchNotesArgs>(argsJson)
                if (noteRepository != null) {
                    val results = noteRepository.search(userId, args.query)
                    if (results.isEmpty()) {
                        "No notes found matching '${args.query}'."
                    } else {
                        val formatted = results.joinToString("\n") { "- [${it.id}] ${it.title}: ${it.content.take(100)}" }
                        "Found ${results.size} note(s):\n$formatted"
                    }
                } else {
                    emitDeviceCommand(AgentCommand.SearchNotes(commandId = UUID.randomUUID().toString(), query = args.query, category = args.filter))
                    "Search request sent to device for: ${args.query}"
                }
            }

            "edit_note", "update_note" -> {
                val args = json.decodeFromString<UpdateNoteArgs>(argsJson)
                if (noteRepository != null) {
                    val success = noteRepository.update(userId, args.noteId, args.title, args.content, null)
                    if (success) {
                        emitStateSync("note_updated", """{"id":"${args.noteId}","title":"${args.title ?: ""}","content":"${args.content?.replace("\"", "\\\"") ?: ""}"}""")
                        "Note ${args.noteId} updated successfully."
                    } else "Note ${args.noteId} not found."
                } else {
                    emitDeviceCommand(AgentCommand.UpdateNote(commandId = UUID.randomUUID().toString(), noteId = args.noteId, title = args.title, content = args.content))
                    "Note update sent to device."
                }
            }

            "delete_note" -> {
                val args = json.decodeFromString<DeleteNoteArgs>(argsJson)
                if (noteRepository != null) {
                    val success = noteRepository.delete(userId, args.noteId)
                    if (success) {
                        emitStateSync("note_deleted", """{"id":"${args.noteId}"}""")
                        "Note ${args.noteId} deleted."
                    } else "Note ${args.noteId} not found."
                } else {
                    emitDeviceCommand(AgentCommand.DeleteNote(commandId = UUID.randomUUID().toString(), noteId = args.noteId))
                    "Note deletion sent to device."
                }
            }

            "archive_note" -> {
                val args = json.decodeFromString<ArchiveNoteArgs>(argsJson)
                if (noteRepository != null) {
                    val success = noteRepository.archive(userId, args.noteId)
                    if (success) {
                        emitStateSync("note_archived", """{"id":"${args.noteId}"}""")
                        "Note ${args.noteId} archived."
                    } else "Note ${args.noteId} not found."
                } else {
                    emitDeviceCommand(AgentCommand.ArchiveNote(commandId = UUID.randomUUID().toString(), noteId = args.noteId))
                    "Note archive sent to device."
                }
            }

            "remember_fact", "store_context" -> {
                val args = if (argsJson.contains("\"fact\"")) {
                    val newArgs = json.decodeFromString<RememberFactArgs>(argsJson)
                    StoreContextArgs(content = newArgs.fact, type = newArgs.type)
                } else {
                    json.decodeFromString<StoreContextArgs>(argsJson)
                }
                try {
                    vectorStore.store(userId, args.content, mapOf("type" to args.type))
                    "Fact remembered: '${args.content.take(50)}...' as ${args.type}"
                } catch (e: Exception) {
                    logger.warn("remember_fact failed: ${e.message}")
                    "Failed to remember fact: ${e.message}"
                }
            }

            "update_context" -> {
                val args = json.decodeFromString<UpdateContextArgs>(argsJson)
                try {
                    vectorStore.update(userId, args.id, args.content)
                    emitStateSync("context_updated", """{"id":"${args.id}","content":"${args.content.replace("\"","\\\"")}","type":"${args.type}"}""")
                    "Context ${args.id} updated."
                } catch (e: Exception) {
                    "Failed to update context: ${e.message}"
                }
            }

            "delete_context" -> {
                val args = json.decodeFromString<DeleteContextArgs>(argsJson)
                try {
                    vectorStore.delete(userId, args.id)
                    emitStateSync("context_deleted", """{"id":"${args.id}"}""")
                    "Context ${args.id} deleted."
                } catch (e: Exception) {
                    "Failed to delete context: ${e.message}"
                }
            }

            // 
            // TIME & SCHEDULE
            //

            "add_event", "schedule_event" -> {
                // Handle both new natural language and old timestamp formats
                if (argsJson.contains("\"when\"")) {
                    // New format with natural language
                    val args = json.decodeFromString<AddEventArgs>(argsJson)
                    val startTime = parseNaturalTime(args.scheduledAt, clientTimezone, clientTimeMillis)
                    val durationStr = args.duration ?: "1 hour"
                    val durationMs = parseDurationToMs(durationStr)
                    val endTime = startTime + durationMs
                    val reminder = 15
                    
                    if (calendarRepository != null) {
                        val eventId = calendarRepository.create(userId, args.title, startTime, endTime, args.description, reminder)
                        val info = CalendarEventInfo(
                            id = eventId,
                            title = args.title,
                            startTime = startTime,
                            endTime = endTime,
                            description = args.description,
                            reminderMinutes = reminder,
                            createdAt = System.currentTimeMillis()
                        )
                        emitStateSync("event_scheduled", json.encodeToString(info))
                        "Event added: '${args.title}' on ${args.scheduledAt}"
                    } else {
                        emitDeviceCommand(AgentCommand.ScheduleEvent(commandId = UUID.randomUUID().toString(), title = args.title, startTime = startTime, endTime = endTime, description = args.description, reminderMinutes = reminder))
                        "Event sent to device: ${args.title}"
                    }
                } else {
                    // Old format with timestamps
                    val args = json.decodeFromString<ScheduleEventArgs>(argsJson)
                    val reminder = args.reminderMinutes ?: 15
                    if (calendarRepository != null) {
                        val eventId = calendarRepository.create(userId, args.title, args.startTime, args.endTime, args.description, reminder)
                        val info = CalendarEventInfo(
                            id = eventId,
                            title = args.title,
                            startTime = args.startTime,
                            endTime = args.endTime,
                            description = args.description,
                            reminderMinutes = reminder,
                            createdAt = System.currentTimeMillis()
                        )
                        emitStateSync("event_scheduled", json.encodeToString(info))
                        "Event added: '${args.title}', ID: $eventId"
                    } else {
                        emitDeviceCommand(AgentCommand.ScheduleEvent(commandId = UUID.randomUUID().toString(), title = args.title, startTime = args.startTime, endTime = args.endTime, description = args.description, reminderMinutes = reminder))
                        "Event sent to device: ${args.title}"
                    }
                }
            }

            "show_events", "list_events" -> {
                if (argsJson.contains("\"when\"")) {
                    val args = json.decodeFromString<ShowEventsArgs>(argsJson)
                    val (startMs, endMs) = parseTimeRange(args.period, clientTimezone, clientTimeMillis)
                    
                    if (calendarRepository != null) {
                        val events = calendarRepository.listUpcoming(userId)
                        val filtered = events.filter { it.startTime >= startMs && it.startTime < endMs }
                        if (filtered.isEmpty()) {
                            "No events for ${args.period}."
                        } else {
                            val formatted = filtered.joinToString("\n") { "- [${it.id}] ${it.title} at ${java.time.Instant.ofEpochMilli(it.startTime)}" }
                            "Events for ${args.period}:\n$formatted"
                        }
                    } else {
                        emitDeviceCommand(AgentCommand.ListEvents(commandId = UUID.randomUUID().toString(), date = startMs))
                        "Event request sent to device."
                    }
                } else {
                    val args = json.decodeFromString<ListEventsArgs>(argsJson)
                    if (calendarRepository != null) {
                        val events = calendarRepository.listUpcoming(userId)
                        if (events.isEmpty()) {
                            "No upcoming events found."
                        } else {
                            val formatted = events.joinToString("\n") { "- [${it.id}] ${it.title} (${java.time.Instant.ofEpochMilli(it.startTime)})" }
                            "Found ${events.size} event(s):\n$formatted"
                        }
                    } else {
                        emitDeviceCommand(AgentCommand.ListEvents(commandId = UUID.randomUUID().toString(), date = args.date))
                        "Event listing sent to device."
                    }
                }
            }

            "remove_event", "delete_event" -> {
                val args = json.decodeFromString<DeleteEventArgs>(argsJson)
                if (calendarRepository != null) {
                    val success = calendarRepository.delete(userId, args.eventId)
                    if (success) {
                        emitStateSync("event_deleted", """{"id":"${args.eventId}"}""")
                        "Event ${args.eventId} removed."
                    } else "Event ${args.eventId} not found."
                } else {
                    emitDeviceCommand(AgentCommand.DeleteEvent(commandId = UUID.randomUUID().toString(), eventId = args.eventId))
                    "Event removal sent to device."
                }
            }

            "set_reminder" -> {
                val args = json.decodeFromString<SetReminderArgs>(argsJson)
                val triggerTime = parseNaturalTime(args.scheduledAt, clientTimezone, clientTimeMillis)
                val isAlarm = !args.scheduledAt.contains("in ") && !args.scheduledAt.contains("after ")
                
                if (timerRepository != null) {
                    val timerId = timerRepository.create(userId, args.what, triggerAt = triggerTime, isAlarm = isAlarm)
                    val info = TimerInfo(
                        id = timerId,
                        name = args.what,
                        durationMs = if (isAlarm) 0L else triggerTime - System.currentTimeMillis(),
                        triggerAt = triggerTime,
                        isAlarm = isAlarm,
                        isActive = true,
                        createdAt = System.currentTimeMillis()
                    )
                    emitStateSync("timer_set", json.encodeToString(info))
                    val typeStr = if (isAlarm) "Reminder" else "Timer"
                    "$typeStr set: '${args.what}' for ${args.scheduledAt}"
                } else {
                    emitDeviceCommand(AgentCommand.SetTimer(commandId = UUID.randomUUID().toString(), name = args.what, timeStr = args.scheduledAt, isAlarm = isAlarm))
                    "Reminder sent to device: ${args.what}"
                }
            }

            "set_timer" -> {
                val args = json.decodeFromString<SetTimerArgs>(argsJson)
                val durationMs = parseDurationToMs(args.duration)
                if (timerRepository != null) {
                    val timerId = timerRepository.create(userId, args.name, durationMs = durationMs, isAlarm = false)
                    val triggerAt = System.currentTimeMillis() + durationMs
                    val info = TimerInfo(
                        id = timerId,
                        name = args.name,
                        durationMs = durationMs,
                        triggerAt = triggerAt,
                        isAlarm = false,
                        isActive = true,
                        createdAt = System.currentTimeMillis()
                    )
                    emitStateSync("timer_set", json.encodeToString(info))
                    "Timer set: '${args.name}' for ${args.duration}"
                } else {
                    emitDeviceCommand(AgentCommand.SetTimer(commandId = UUID.randomUUID().toString(), name = args.name, timeStr = args.duration, isAlarm = false))
                    "Timer sent to device: ${args.name}"
                }
            }

            "set_alarm" -> {
                val args = json.decodeFromString<SetAlarmArgs>(argsJson)
                if (timerRepository != null) {
                    val triggerAt = parseAlarmTimeToMs(args.time, clientTimezone, clientTimeMillis)
                    val timerId = timerRepository.create(userId, args.name, triggerAt = triggerAt, isAlarm = true)
                    val info = TimerInfo(
                        id = timerId,
                        name = args.name,
                        durationMs = 0L,
                        triggerAt = triggerAt,
                        isAlarm = true,
                        isActive = true,
                        createdAt = System.currentTimeMillis()
                    )
                    emitStateSync("timer_set", json.encodeToString(info))
                    "Alarm set: '${args.name}' at ${args.time}"
                } else {
                    emitDeviceCommand(AgentCommand.SetTimer(commandId = UUID.randomUUID().toString(), name = args.name, timeStr = args.time, isAlarm = true))
                    "Alarm sent to device: ${args.name}"
                }
            }

            // 
            // INFORMATION
            //

            "search_web", "web_search" -> {
                val args = json.decodeFromString<WebSearchArgs>(argsJson)
                val result = tavilyTool.search(args.query)
                if (result.startsWith("Error")) "Search failed: $result"
                else "Web search results for '${args.query}':\n$result"
            }

            "query_knowledge" -> {
                val args = json.decodeFromString<QueryKnowledgeArgs>(argsJson)
                try {
                    val results = vectorStore.search(userId, args.query, limit = 5)
                    if (results.isEmpty()) "No private knowledge found for '${args.query}'."
                    else "Found ${results.size} relevant items:\n" + results.joinToString("\n") { "- ${it.content}" }
                } catch (e: Exception) {
                    "Knowledge query failed: ${e.message}"
                }
            }

            "get_weather" -> {
                val args = json.decodeFromString<GetWeatherArgs>(argsJson)
                val location = args.location ?: "current location"
                val result = tavilyTool.search("current weather in $location")
                if (result.startsWith("Error")) "Weather lookup failed: $result"
                else "Weather for $location:\n${result.take(500)}"
            }

            "get_device_info" -> {
                val args = json.decodeFromString<GetDeviceInfoArgs>(argsJson)
                emitDeviceCommand(AgentCommand.GetDeviceInfo(commandId = UUID.randomUUID().toString(), infoType = args.info))
                "Device info request sent: ${args.info}"
            }

            "summarize_session" -> {
                val summary = summarizer.generateSummary(history)
                summary ?: "Could not summarize session at this time."
            }

            "generate_image" -> {
                json.decodeFromString<GenerateImageArgs>(argsJson)
                "Image generation is not available yet. It's on the roadmap."
            }

            // 
            // DEVICE CONTROL
            //

            "open_app", "launch_app" -> {
                val packageName = if (argsJson.contains("\"app\"")) {
                    val args = json.decodeFromString<OpenAppArgs>(argsJson)
                    resolveAppPackage(args.app)
                } else {
                    val args = json.decodeFromString<LaunchAppArgs>(argsJson)
                    args.packageName
                }
                emitDeviceCommand(AgentCommand.LaunchApp(commandId = UUID.randomUUID().toString(), packageName = packageName))
                "Opening app: $packageName"
            }

            "take_screenshot" -> {
                emitDeviceCommand(AgentCommand.TakeScreenshot(commandId = UUID.randomUUID().toString()))
                "Taking screenshot."
            }

            "toggle_setting" -> {
                val (setting, on) = if (argsJson.contains("\"on\"")) {
                    val args = json.decodeFromString<ToggleSettingNewArgs>(argsJson)
                    Pair(args.setting, args.on)
                } else {
                    val args = json.decodeFromString<ToggleSettingArgs>(argsJson)
                    Pair(args.setting, args.enable)
                }
                emitDeviceCommand(AgentCommand.ToggleSetting(commandId = UUID.randomUUID().toString(), setting = setting, enable = on))
                "$setting ${if (on) "enabled" else "disabled"}."
            }

            "control_music", "control_media" -> {
                val args = json.decodeFromString<ControlMediaArgs>(argsJson)
                emitDeviceCommand(AgentCommand.ControlAudio(commandId = UUID.randomUUID().toString(), action = args.action))
                "Media: ${args.action}"
            }

            "seek_media" -> {
                val args = json.decodeFromString<SeekMediaArgs>(argsJson)
                emitDeviceCommand(AgentCommand.SeekAudio(commandId = UUID.randomUUID().toString(), positionMs = args.positionMs))
                "Seeking to ${args.positionMs}ms."
            }

            // 
            // NAVIGATION & SHARING
            //

            "go_to_screen", "navigate" -> {
                val args = json.decodeFromString<GoToScreenArgs>(argsJson)
                emitDeviceCommand(AgentCommand.Navigate(commandId = UUID.randomUUID().toString(), screen = args.screen))
                "Navigating to ${args.screen}."
            }

            "share_content", "share" -> {
                val args = json.decodeFromString<ShareContentArgs>(argsJson)
                emitDeviceCommand(AgentCommand.Share(commandId = UUID.randomUUID().toString(), content = args.content, title = args.title))
                "Sharing content."
            }

            else -> "Unknown tool: $name"
        }
        return truncateToolResult(result)
    }

    /** Emit a StateSync event so the Android client can cache data locally. */
    private suspend fun emitStateSync(syncType: String, data: String) {
        emit(AgentEvent.StateSync(
            eventId = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            syncType = syncType,
            data = data
        ))
    }

    /** Emit a fire-and-forget Command event for device-only tools. */
    private suspend fun emitDeviceCommand(command: AgentCommand) {
        emit(AgentEvent.Command(
            eventId = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            command = command
        ))
    }

    /** Parse human-readable duration string to milliseconds. */
    private fun parseDurationToMs(duration: String): Long {
        val lower = duration.lowercase().trim()
        var totalMs = 0L
        val hourMatch = Regex("""(\d+)\s*h(?:our)?s?""").find(lower)
        val minMatch = Regex("""(\d+)\s*m(?:in(?:ute)?)?s?""").find(lower)
        val secMatch = Regex("""(\d+)\s*s(?:ec(?:ond)?)?s?""").find(lower)
        hourMatch?.let { totalMs += it.groupValues[1].toLong() * 3600000 }
        minMatch?.let { totalMs += it.groupValues[1].toLong() * 60000 }
        secMatch?.let { totalMs += it.groupValues[1].toLong() * 1000 }
        // If just a number, treat as minutes
        if (totalMs == 0L) {
            val plainNum = Regex("""(\d+)""").find(lower)
            plainNum?.let { totalMs = it.groupValues[1].toLong() * 60000 }
        }
        return if (totalMs > 0) totalMs else 60000 // Default 1 minute
    }

    /** Parse human-readable alarm time string to absolute epoch milliseconds. */
    private fun parseAlarmTimeToMs(timeStr: String, clientTimezone: String? = null, clientTimeMillis: Long? = null): Long {
        val now = clientTimeMillis ?: System.currentTimeMillis()
        val tz = try { java.time.ZoneId.of(clientTimezone ?: "UTC") } catch (e: Exception) { java.time.ZoneId.of("UTC") }
        val zonedNow = java.time.Instant.ofEpochMilli(now).atZone(tz)

        val lower = timeStr.lowercase().trim()
        val isTomorrow = lower.contains("tomorrow")
        val cleanStr = lower.replace("tomorrow", "").trim()

        val timePatterns = listOf(
            Regex("""(\d{1,2}):(\d{2})\s*(am|pm)?"""),
            Regex("""(\d{1,2})\s*(am|pm)""")
        )

        var hour = 0
        var minute = 0
        var foundMatch = false

        for (pattern in timePatterns) {
            val match = pattern.find(cleanStr)
            if (match != null) {
                hour = match.groupValues[1].toInt()
                minute = if (match.groupValues[2].matches(Regex("""\d{2}"""))) match.groupValues[2].toInt() else 0
                val ampm = match.groupValues.last().lowercase()
                if (ampm == "pm" && hour < 12) hour += 12
                else if (ampm == "am" && hour == 12) hour = 0
                foundMatch = true
                break
            }
        }

        if (!foundMatch) {
            val plainHour = Regex("""(\d{1,2})""").find(cleanStr)
            if (plainHour != null) {
                hour = plainHour.groupValues[1].toInt()
                minute = 0
                foundMatch = true
            }
        }

        if (!foundMatch) return now + 3600000

        var resultTime = zonedNow.withHour(hour).withMinute(minute).withSecond(0).withNano(0)

        if (isTomorrow) {
            resultTime = resultTime.plusDays(1)
        } else if (!resultTime.isAfter(zonedNow)) {
            resultTime = resultTime.plusDays(1)
        }

        return resultTime.toInstant().toEpochMilli()
    }

private suspend fun emit(event: AgentEvent) {
        eventEmitter(event)
    }

    /**
     * Parse natural language time expressions to epoch milliseconds.
     * Examples: "tomorrow at 2pm", "Friday 3pm", "in 2 hours", "Dec 25 at 6pm"
     */
    private fun parseNaturalTime(expression: String, clientTimezone: String?, clientTimeMillis: Long?): Long {
        val now = clientTimeMillis ?: System.currentTimeMillis()
        val tz = try {
            java.time.ZoneId.of(clientTimezone ?: "UTC")
        } catch (e: Exception) {
            java.time.ZoneId.of("UTC")
        }
        val zonedNow = java.time.Instant.ofEpochMilli(now).atZone(tz)
        val cleanExpr = expression.lowercase().trim()
        
        // Handle "in X minutes/hours/days"
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
        
        // Determine if tomorrow/next week
        val isTomorrow = cleanExpr.contains("tomorrow") || cleanExpr.contains("tmrw")
        val isNextWeek = cleanExpr.contains("next week")
        val isNextMonth = cleanExpr.contains("next month")
        
        // Extract day name
        val dayOffsets = mapOf(
            "monday" to 1, "tuesday" to 2, "wednesday" to 3, "thursday" to 4,
            "friday" to 5, "saturday" to 6, "sunday" to 7
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
        
        // Extract time
        var hour = 12
        var minute = 0
        
        // Time patterns
        val timePatterns = listOf(
            Regex("""(\d{1,2}):(\d{2})\s*(am|pm)?"""),
            Regex("""(\d{1,2})\s*(am|pm)"""),
            Regex("""(\d{1,2})""")
        )
        
        for (pattern in timePatterns) {
            val match = pattern.find(cleanExpr)
            if (match != null) {
                hour = match.groupValues[1].toInt()
                if (match.groupValues.size > 2 && match.groupValues[2].isNotEmpty()) {
                    if (match.groupValues[2].all { it.isDigit() }) {
                        minute = match.groupValues[2].toInt()
                    } else {
                        // AM/PM handling
                        val ampm = match.groupValues.last().lowercase()
                        if (ampm == "pm" && hour < 12) hour += 12
                        else if (ampm == "am" && hour == 12) hour = 0
                    }
                }
                if (match.groupValues.size > 3 && match.groupValues[3].isNotEmpty()) {
                    val ampm = match.groupValues[3].lowercase()
                    if (ampm == "pm" && hour < 12) hour += 12
                    else if (ampm == "am" && hour == 12) hour = 0
                }
                break
            }
        }
        
        var resultTime = zonedNow.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        
        when {
            isTomorrow -> resultTime = resultTime.plusDays(1)
            isNextWeek -> resultTime = resultTime.plusWeeks(1)
            isNextMonth -> resultTime = resultTime.plusMonths(1)
            targetDay != null -> resultTime = resultTime.plusDays(targetDay.toLong())
            !resultTime.isAfter(zonedNow) -> resultTime = resultTime.plusDays(1)
        }
        
        return resultTime.toInstant().toEpochMilli()
    }

    /**
     * Parse time range expressions like "today", "tomorrow", "this week"
     * Returns Pair(startTime, endTime) in epoch milliseconds
     */
    private fun parseTimeRange(expression: String, clientTimezone: String?, clientTimeMillis: Long?): Pair<Long, Long> {
        val now = clientTimeMillis ?: System.currentTimeMillis()
        val tz = try {
            java.time.ZoneId.of(clientTimezone ?: "UTC")
        } catch (e: Exception) {
            java.time.ZoneId.of("UTC")
        }
        val zonedNow = java.time.Instant.ofEpochMilli(now).atZone(tz)
        val cleanExpr = expression.lowercase().trim()
        
        return when {
            cleanExpr.contains("today") -> {
                val start = zonedNow.withHour(0).withMinute(0).withSecond(0).withNano(0)
                val end = start.plusDays(1)
                Pair(start.toInstant().toEpochMilli(), end.toInstant().toEpochMilli())
            }
            cleanExpr.contains("tomorrow") || cleanExpr.contains("tmrw") -> {
                val start = zonedNow.plusDays(1).withHour(0).withMinute(0).withSecond(0).withNano(0)
                val end = start.plusDays(1)
                Pair(start.toInstant().toEpochMilli(), end.toInstant().toEpochMilli())
            }
            cleanExpr.contains("this week") -> {
                val dayOfWeek = zonedNow.dayOfWeek.value
                val start = zonedNow.minusDays((dayOfWeek - 1).toLong()).withHour(0).withMinute(0).withSecond(0).withNano(0)
                val end = start.plusDays(7)
                Pair(start.toInstant().toEpochMilli(), end.toInstant().toEpochMilli())
            }
            cleanExpr.contains("next week") -> {
                val dayOfWeek = zonedNow.dayOfWeek.value
                val start = zonedNow.plusDays((8 - dayOfWeek).toLong()).withHour(0).withMinute(0).withSecond(0).withNano(0)
                val end = start.plusDays(7)
                Pair(start.toInstant().toEpochMilli(), end.toInstant().toEpochMilli())
            }
            else -> {
                // Default to next 24 hours
                Pair(now, now + 24 * 60 * 60 * 1000)
            }
        }
    }

    /**
     * Resolve common app names to Android package names.
     */
    private fun resolveAppPackage(appName: String): String {
        val name = appName.lowercase().trim()
        
        val commonApps = mapOf(
            "spotify" to "com.spotify.music",
            "music" to "com.spotify.music",
            "youtube" to "com.google.android.youtube",
            "youtube music" to "com.google.android.apps.youtube.music",
            "maps" to "com.google.android.apps.maps",
            "google maps" to "com.google.android.apps.maps",
            "gmail" to "com.google.android.gm",
            "email" to "com.google.android.gm",
            "calendar" to "com.google.android.calendar",
            "camera" to "com.android.camera",
            "photos" to "com.google.android.apps.photos",
            "gallery" to "com.google.android.apps.photos",
            "settings" to "com.android.settings",
            "clock" to "com.google.android.deskclock",
            "alarm" to "com.google.android.deskclock",
            "timer" to "com.google.android.deskclock",
            "chrome" to "com.android.chrome",
            "browser" to "com.android.chrome",
            "messages" to "com.google.android.apps.messaging",
            "sms" to "com.google.android.apps.messaging",
            "phone" to "com.google.android.dialer",
            "dialer" to "com.google.android.dialer",
            "contacts" to "com.google.android.contacts",
            "facebook" to "com.facebook.katana",
            "instagram" to "com.instagram.android",
            "twitter" to "com.twitter.android",
            "x" to "com.twitter.android",
            "whatsapp" to "com.whatsapp",
            "telegram" to "org.telegram.messenger",
            "discord" to "com.discord",
            "slack" to "com.Slack",
            "teams" to "com.microsoft.teams",
            "zoom" to "us.zoom.videomeetings",
            "netflix" to "com.netflix.mediaclient",
            "tiktok" to "com.zhiliaoapp.musically",
            "twitter" to "com.twitter.android"
        )
        
        // Check if it's already a package name
        if (name.contains(".")) return name
        
        // Look up common app
        return commonApps[name] ?: "com.android.settings"
    }

    @Serializable data class CreateNoteArgs(val title: String, val content: String, val category: String? = null)
    @Serializable data class SearchNotesArgs(val query: String, val filter: String? = null)
    @Serializable data class ScheduleEventArgs(val title: String, val startTime: Long, val endTime: Long, val description: String? = null, val reminderMinutes: Int? = null)
    // New natural language event args
    @Serializable data class AddEventArgs(val title: String, @SerialName("when") val scheduledAt: String, val duration: String? = null, val description: String? = null)
    @Serializable data class ShowEventsArgs(@SerialName("when") val period: String)
    @Serializable data class ListEventsArgs(val date: Long)
    @Serializable data class DeleteEventArgs(val eventId: String)
    @Serializable data class SetTimerArgs(val name: String, val duration: String)
    @Serializable data class SetAlarmArgs(val name: String, val time: String)
    // New unified reminder args
    @Serializable data class SetReminderArgs(val what: String, @SerialName("when") val scheduledAt: String, val repeat: String? = null)
    @Serializable data class LaunchAppArgs(val packageName: String)
    @Serializable data class OpenAppArgs(val app: String)
    @Serializable data class ToggleSettingArgs(val setting: String, val enable: Boolean)
    @Serializable data class ToggleSettingNewArgs(val setting: String, val on: Boolean)
    @Serializable data class ControlMediaArgs(val action: String)
    @Serializable data class SeekMediaArgs(val positionMs: Long)
    @Serializable data class StoreContextArgs(val content: String, val type: String)
    @Serializable data class RememberFactArgs(val fact: String, val type: String)
    @Serializable data class UpdateContextArgs(val id: String, val content: String, val type: String)
    @Serializable data class DeleteContextArgs(val id: String)
    @Serializable data class UpdateNoteArgs(val noteId: String, val title: String? = null, val content: String? = null)
    @Serializable data class DeleteNoteArgs(val noteId: String)
    @Serializable data class ArchiveNoteArgs(val noteId: String)
    @Serializable data class NavigateArgs(val screen: String)
    @Serializable data class GoToScreenArgs(val screen: String)
    @Serializable data class ShareArgs(val content: String, val title: String? = null)
    @Serializable data class ShareContentArgs(val content: String, val title: String? = null)
    @Serializable data class WebSearchArgs(val query: String)
    @Serializable data class SearchWebArgs(val query: String)
    @Serializable data class QueryKnowledgeArgs(val query: String)
    @Serializable data class GetWeatherArgs(val location: String? = null)
    @Serializable data class GetDeviceInfoArgs(val info: String)
    @Serializable data class GenerateImageArgs(val prompt: String)

    /**
     * Build time context string for the system prompt.
     * This helps the agent correctly parse time-based requests.
     */
    private fun buildTimeContext(clientTimezone: String?, clientTimeMillis: Long?): String {
        val now = clientTimeMillis ?: System.currentTimeMillis()
        val tz = try {
            java.time.ZoneId.of(clientTimezone ?: "UTC")
        } catch (e: Exception) {
            java.time.ZoneId.of("UTC")
        }

        val zonedNow = java.time.Instant.ofEpochMilli(now).atZone(tz)
        val dateFormatter = java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy")
        val timeFormatter = java.time.format.DateTimeFormatter.ofPattern("h:mm a")

        return """
            User's local time: ${zonedNow.format(timeFormatter)} on ${zonedNow.format(dateFormatter)}
            (User's timezone: ${tz.id})
        """.trimIndent()
    }

    private fun extractFinalResponse(raw: String): String {
        val finalRegex = Regex("""<final>(.*?)</final>""", RegexOption.DOT_MATCHES_ALL)
        val match = finalRegex.find(raw)
        return match?.groupValues?.get(1)?.trim() ?: raw.trim()
    }
    
    private fun extractThinking(raw: String): String? {
        val thinkRegex = Regex("""<think>(.*?)</think>""", RegexOption.DOT_MATCHES_ALL)
        val matches = thinkRegex.findAll(raw)
        val thinking = matches.joinToString("\n") { it.groupValues[1].trim() }
        return thinking.ifEmpty { null }
    }

    private fun truncateToolResult(result: String, maxChars: Int = 30000): String {
        return if (result.length > maxChars) {
            result.take(maxChars) + "\n[...truncated for brevity]"
        } else result
    }
}
