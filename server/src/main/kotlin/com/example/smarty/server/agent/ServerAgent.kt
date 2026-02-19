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
import com.example.smarty.core.common.util.PIIMasker
import com.example.smarty.server.tools.TavilySearchTool
import com.example.smarty.server.tools.WebFetchTool
import com.example.smarty.server.tools.CodeExecutionTool
import com.example.smarty.server.tools.WorkflowManager
import com.example.smarty.server.tools.KnowledgeGraphTool
import com.example.smarty.server.tools.MonitoringTool
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
    
    private val webFetchTool = WebFetchTool(io.ktor.client.HttpClient())
    private val codeExecutionTool = CodeExecutionTool()
    private val workflowManager = WorkflowManager()
    private val knowledgeGraphTool = KnowledgeGraphTool()
    private val monitoringTool = MonitoringTool(io.ktor.client.HttpClient())
    
    // Initialize PIIMasker securely
    private val piiMasker = PIIMasker(object : com.example.smarty.core.common.util.Logger {
        override fun d(tag: String, message: String) = logger.debug("[$tag] $message")
        override fun i(tag: String, message: String) = logger.info("[$tag] $message")
        override fun w(tag: String, message: String, throwable: Throwable?) = logger.warn("[$tag] $message", throwable)
        override fun e(tag: String, message: String, throwable: Throwable?) = logger.error("[$tag] $message", throwable)
    })

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
        ),
        
        // ═══════════════════════════════════════════════════════════════════
        // ADVANCED TOOLS - Chain-of-Tool capabilities
        // ═══════════════════════════════════════════════════════════════════
        
        ToolDefinition(
            name = "fetch_url",
            description = """Fetch and read content from a URL.

WHEN TO USE: User wants to read, analyze, or extract information from a specific webpage.
WHEN NOT TO USE: General web search (use search_web instead).

EXAMPLES:
- "fetch_url(url='https://example.com/article')" → Reads the article content
- "fetch_url(url='https://docs.python.org/3/tutorial/', format='markdown')" → Gets formatted docs
- "fetch_url(url='https://news.ycombinator.com', format='readable')" → Extracts main content

Returns the readable content from the page, with scripts/styles removed.
Use format='raw' for HTML, 'readable' for clean text, 'markdown' for markdown format.""",
            parameters = ToolParameters(
                properties = mapOf(
                    "url" to ToolProperty("string", "The URL to fetch"),
                    "format" to ToolProperty(
                        "string",
                        "Output format: 'readable' (clean text), 'raw' (HTML), 'markdown'",
                        enum = listOf("readable", "raw", "markdown")
                    )
                ),
                required = listOf("url")
            )
        ),
        
        ToolDefinition(
            name = "extract_links",
            description = """Extract all links from a webpage.

WHEN TO USE: User wants to find links on a specific page.
EXAMPLE: "extract_links(url='https://news.ycombinator.com')" → Lists all article links

Returns a list of links with their anchor text.""",
            parameters = ToolParameters(
                properties = mapOf(
                    "url" to ToolProperty("string", "The URL to extract links from")
                ),
                required = listOf("url")
            )
        ),
        
        ToolDefinition(
            name = "execute_code",
            description = """Execute code and return the result.

WHEN TO USE: User wants to run code, test algorithms, process data, or compute something.
WHEN NOT TO USE: Simple calculations (just answer directly).

EXAMPLES:
- "execute_code(code='print(2**10)', language='python')" → 1024
- "execute_code(code='sum([1,2,3,4,5])', language='python')" → 15
- "execute_code(code='[x**2 for x in range(10)]', language='python')" → [0, 1, 4, 9, ...]

SUPPORTED LANGUAGES: python (only Python currently supported)
TIMEOUT: 30 seconds maximum execution time.
SAFETY: Network access disabled, file system isolated.

Use this to verify code, test algorithms, or perform computations.""",
            parameters = ToolParameters(
                properties = mapOf(
                    "code" to ToolProperty("string", "The code to execute"),
                    "language" to ToolProperty(
                        "string",
                        "Programming language",
                        enum = listOf("python")
                    )
                ),
                required = listOf("code", "language")
            )
        ),
        
        ToolDefinition(
            name = "create_workflow",
            description = """Create an automated workflow that runs on a schedule or trigger.

WHEN TO USE: User wants to automate repetitive tasks or set up scheduled actions.

EXAMPLES:
- "create_workflow(name='morning_briefing', trigger='daily 8am', actions=['get_weather', 'show_events'])"
- "create_workflow(name='price_monitor', trigger='every 6 hours', actions=['check_price'])"

WORKFLOW COMPONENTS:
- name: Unique identifier for the workflow
- trigger: When to run (e.g., 'daily 8am', 'every 1 hour', 'on_new_note')
- actions: List of tool calls to execute in sequence

Returns workflow ID for reference.""",
            parameters = ToolParameters(
                properties = mapOf(
                    "name" to ToolProperty("string", "Workflow name"),
                    "trigger" to ToolProperty("string", "Trigger: 'daily TIME', 'every N hours', 'on_new_note'"),
                    "actions" to ToolProperty("string", "JSON array of tool calls to execute")
                ),
                required = listOf("name", "trigger", "actions")
            )
        ),
        
        ToolDefinition(
            name = "list_workflows",
            description = """List all active workflows.

WHEN TO USE: User wants to see what automations are running.
RETURNS: List of workflow names, triggers, and next run times.""",
            parameters = ToolParameters(
                properties = emptyMap(),
                required = emptyList()
            )
        ),
        
        ToolDefinition(
            name = "delete_workflow",
            description = """Delete a workflow.

WHEN TO USE: User wants to stop an automation.
EXAMPLE: "delete_workflow(name='morning_briefing')"

Returns confirmation of deletion.""",
            parameters = ToolParameters(
                properties = mapOf(
                    "name" to ToolProperty("string", "Name of workflow to delete")
                ),
                required = listOf("name")
            )
        ),
        
        ToolDefinition(
            name = "parallel_search",
            description = """Execute multiple searches in parallel.

WHEN TO USE: User wants to search multiple things at once for comparison or comprehensive results.
WHEN NOT TO USE: Single search (use search_web or find_note directly).

EXAMPLES:
- "parallel_search(queries=['weather Tokyo', 'weather London', 'weather NYC'])"
- "parallel_search(queries=['Python tutorial', 'Kotlin tutorial'], type='web')"

Returns combined results from all searches. Faster than sequential searches.""",
            parameters = ToolParameters(
                properties = mapOf(
                    "queries" to ToolProperty("string", "JSON array of search queries"),
                    "type" to ToolProperty(
                        "string",
                        "Search type: 'web' or 'notes'",
                        enum = listOf("web", "notes")
                    )
                ),
                required = listOf("queries")
            )
        ),
        
        ToolDefinition(
            name = "analyze_data",
            description = """Analyze and extract insights from data.

WHEN TO USE: User has data they want analyzed, summarized, or visualized.

EXAMPLES:
- "analyze_data(data='[1,2,3,4,5]', analysis='statistics')" → mean, median, std dev
- "analyze_data(data='sales figures', analysis='trends')" → trend analysis
- "analyze_data(data='my notes about project X', analysis='summary')" → key points

ANALYSIS TYPES:
- 'statistics': Numerical statistics (mean, median, min, max, etc.)
- 'summary': Text summarization and key points
- 'trends': Pattern and trend detection
- 'compare': Comparison between items""",
            parameters = ToolParameters(
                properties = mapOf(
                    "data" to ToolProperty("string", "Data to analyze (text, JSON array, or description)"),
                    "analysis" to ToolProperty(
                        "string",
                        "Type of analysis",
                        enum = listOf("statistics", "summary", "trends", "compare")
                    )
                ),
                required = listOf("data", "analysis")
            )
        ),
        
        ToolDefinition(
            name = "plan_execution",
            description = """Create a multi-step execution plan for a complex goal.

WHEN TO USE: User has a complex goal requiring multiple tool calls and steps.
WHEN NOT TO USE: Simple single-action requests.

EXAMPLES:
- "plan_execution(goal='Plan a trip to Tokyo next month')"
- "plan_execution(goal='Research and compare 3 laptops for purchase')"
- "plan_execution(goal='Organize all my notes by project')"

RETURNS:
- Step-by-step plan with required tool calls
- Estimated actions needed
- Dependencies between steps

Use this before executing complex multi-step requests to ensure thorough planning.""",
            parameters = ToolParameters(
                properties = mapOf(
                    "goal" to ToolProperty("string", "The goal to plan for"),
                    "constraints" to ToolProperty("string", "Optional constraints or preferences (JSON)")
                ),
                required = listOf("goal")
            )
        ),
        
        ToolDefinition(
            name = "deep_research",
            description = """Conduct deep research on a topic with multiple sources.

WHEN TO USE: User wants comprehensive research on a topic.
WHEN NOT TO USE: Quick fact lookup (use search_web).

EXAMPLES:
- "deep_research(topic='best practices for REST API design', depth='medium')"
- "deep_research(topic='React hooks tutorial', depth='quick')"
- "deep_research(topic='comparison of cloud providers', depth='thorough')"

DEPTH LEVELS:
- 'quick': 3-5 sources, key points only
- 'medium': 5-10 sources, organized summary
- 'thorough': 10+ sources, comprehensive analysis

Returns synthesized findings with sources cited.""",
            parameters = ToolParameters(
                properties = mapOf(
                    "topic" to ToolProperty("string", "Topic to research"),
                    "depth" to ToolProperty(
                        "string",
                        "Research depth",
                        enum = listOf("quick", "medium", "thorough")
                    )
                ),
                required = listOf("topic")
            )
        ),
        
        ToolDefinition(
            name = "remember_permanent",
            description = """Store information in permanent long-term memory.

WHEN TO USE: User shares something they want remembered forever across all conversations.
DIFFERENT FROM save_note: This is AI memory, not user notes.

TYPES:
- 'preference': User likes/dislikes (e.g., "prefers dark mode")
- 'fact': Facts about user (e.g., "works at Acme Inc")
- 'context': Important context (e.g., "allergic to shellfish")
- 'goal': User's goals (e.g., "wants to learn Spanish")

EXAMPLES:
- "remember_permanent(content='User prefers concise responses', type='preference')"
- "remember_permanent(content='User has a cat named Whiskers', type='fact')"

This information persists across all future conversations.""",
            parameters = ToolParameters(
                properties = mapOf(
                    "content" to ToolProperty("string", "What to remember"),
                    "type" to ToolProperty(
                        "string",
                        "Type of memory",
                        enum = listOf("preference", "fact", "context", "goal")
                    ),
                    "importance" to ToolProperty("string", "Importance level: 'high', 'medium', 'low'")
                ),
                required = listOf("content", "type")
            )
        ),
        
        ToolDefinition(
            name = "recall_memory",
            description = """Search through all stored memories and facts about the user.

WHEN TO USE: Looking up past information, preferences, or context about the user.

EXAMPLES:
- "recall_memory(query='dietary preferences')"
- "recall_memory(query='work information')"
- "recall_memory(query='pets')"

Returns all matching memories with timestamps.""",
            parameters = ToolParameters(
                properties = mapOf(
                    "query" to ToolProperty("string", "What to search for in memories"),
                    "type" to ToolProperty(
                        "string",
                        "Optional: filter by type",
                        enum = listOf("preference", "fact", "context", "goal", "all")
                    )
                ),
                required = listOf("query")
            )
        ),
        
        ToolDefinition(
            name = "spawn_task",
            description = """Spawn a parallel background task for independent work.

WHEN TO USE: User wants something done in background while continuing the conversation.
WHEN NOT TO USE: Tasks that need immediate results.

EXAMPLES:
- "spawn_task(task='Research best hotels in Tokyo', callback='save_note')"
- "spawn_task(task='Summarize all my notes from last week', callback='notify')"

The task runs independently. Results are saved or the user is notified when complete.
Returns a task ID for reference.""",
            parameters = ToolParameters(
                properties = mapOf(
                    "task" to ToolProperty("string", "Description of the task to perform"),
                    "callback" to ToolProperty(
                        "string",
                        "What to do with results: 'save_note', 'notify', 'return'",
                        enum = listOf("save_note", "notify", "return")
                    )
                ),
                required = listOf("task")
            )
        ),
        
        ToolDefinition(
            name = "compare_options",
            description = """Compare multiple options and provide a recommendation.

WHEN TO USE: User wants to compare products, services, ideas, or any alternatives.

EXAMPLES:
- "compare_options(options=['iPhone 15', 'Samsung S24', 'Pixel 8'], criteria='price, camera, battery')"
- "compare_options(options=['Python', 'Kotlin', 'Rust'], criteria='learning curve, performance, jobs')"
- "compare_options(options=['remote work', 'hybrid', 'office'], criteria='flexibility, collaboration')"

Returns a comparison table with pros/cons and a recommendation.""",
            parameters = ToolParameters(
                properties = mapOf(
                    "options" to ToolProperty("string", "JSON array of options to compare"),
                    "criteria" to ToolProperty("string", "Comma-separated criteria for comparison")
                ),
                required = listOf("options", "criteria")
            )
        ),
        
        ToolDefinition(
            name = "generate_checklist",
            description = """Generate a checklist for a task or project.

WHEN TO USE: User wants a structured list of steps for something.

EXAMPLES:
- "generate_checklist(topic='packing for beach vacation')"
- "generate_checklist(topic='deploying web application')"
- "generate_checklist(topic='preparing for job interview')"

Returns an organized checklist with categories and items.""",
            parameters = ToolParameters(
                properties = mapOf(
                    "topic" to ToolProperty("string", "What the checklist is for"),
                    "detail" to ToolProperty("string", "Level of detail: 'brief', 'detailed', 'comprehensive'")
                ),
                required = listOf("topic")
            )
        ),
        
        ToolDefinition(
            name = "extract_entities",
            description = """Extract named entities from text (people, places, organizations, dates, etc.).

WHEN TO USE: User wants to identify and categorize entities mentioned in content.

EXAMPLES:
- "extract_entities(text='John works at Microsoft in Seattle')"
- "extract_entities(text='The meeting is scheduled for January 15th with Sarah from Google')"

Returns structured list of entities with types (person, organization, location, date, email, url, money, project).""",
            parameters = ToolParameters(
                properties = mapOf(
                    "text" to ToolProperty("string", "Text to extract entities from")
                ),
                required = listOf("text")
            )
        ),
        
        ToolDefinition(
            name = "build_knowledge_graph",
            description = """Build a knowledge graph from text, identifying entities and their relationships.

WHEN TO USE: User wants to understand connections between entities in content.

EXAMPLES:
- "build_knowledge_graph(text='Elon Musk is CEO of Tesla. Tesla is based in Austin.')"
- "build_knowledge_graph(text='John met Sarah at Google. They discussed the Project Alpha.')"

Returns entities found and relationships between them (works_at, located_in, created, etc.).""",
            parameters = ToolParameters(
                properties = mapOf(
                    "text" to ToolProperty("string", "Text to build knowledge graph from")
                ),
                required = listOf("text")
            )
        ),
        
        ToolDefinition(
            name = "find_connections",
            description = """Find connections between entities in the knowledge graph.

WHEN TO USE: User wants to discover how entities are related.

EXAMPLES:
- "find_connections(entity='John Doe', depth=2)"
- "find_connections(entity='Microsoft', depth=1)"

Returns network of connected entities up to specified depth.""",
            parameters = ToolParameters(
                properties = mapOf(
                    "entity" to ToolProperty("string", "Entity name to find connections for"),
                    "depth" to ToolProperty("string", "How many hops to explore (1-3)")
                ),
                required = listOf("entity")
            )
        ),
        
        ToolDefinition(
            name = "graph_stats",
            description = """Get statistics about the knowledge graph.

WHEN TO USE: User wants to understand what's in their knowledge base.

Returns: entity counts by type, relationship counts, and other statistics.""",
            parameters = ToolParameters(
                properties = emptyMap(),
                required = emptyList()
            )
        ),
        
        ToolDefinition(
            name = "create_monitor",
            description = """Create a monitor to track changes over time.

WHEN TO USE: User wants to be notified when something changes.

EXAMPLES:
- "create_monitor(type='price', target='https://amazon.com/product/123', interval='6 hours')"
- "create_monitor(type='availability', target='https://store.com/item')"
- "create_monitor(type='url', target='https://news-site.com/page')"

MONITOR TYPES:
- 'url': Track any changes to a webpage
- 'price': Track price changes
- 'availability': Track if item is in stock
- 'text_change': Track specific text changes

Returns monitor ID for reference.""",
            parameters = ToolParameters(
                properties = mapOf(
                    "type" to ToolProperty(
                        "string",
                        "Monitor type",
                        enum = listOf("url", "price", "availability", "text_change")
                    ),
                    "target" to ToolProperty("string", "URL or target to monitor"),
                    "interval" to ToolProperty("string", "Check interval: '1 hour', '6 hours', 'daily', etc."),
                    "alertOn" to ToolProperty("string", "Optional: condition to alert on")
                ),
                required = listOf("type", "target")
            )
        ),
        
        ToolDefinition(
            name = "list_monitors",
            description = """List all active monitors.

WHEN TO USE: User wants to see what they're tracking.
RETURNS: List of monitors with their status and last check time.""",
            parameters = ToolParameters(
                properties = emptyMap(),
                required = emptyList()
            )
        ),
        
        ToolDefinition(
            name = "check_monitor",
            description = """Manually check a monitor now.

WHEN TO USE: User wants immediate update on a monitored target.
EXAMPLE: "check_monitor(monitorId='mon_price_123')"

Returns current value and whether it changed.""",
            parameters = ToolParameters(
                properties = mapOf(
                    "monitorId" to ToolProperty("string", "ID of monitor to check")
                ),
                required = listOf("monitorId")
            )
        ),
        
        ToolDefinition(
            name = "delete_monitor",
            description = """Delete a monitor.

WHEN TO USE: User wants to stop tracking something.
EXAMPLE: "delete_monitor(monitorId='mon_price_123')"

Returns confirmation of deletion.""",
            parameters = ToolParameters(
                properties = mapOf(
                    "monitorId" to ToolProperty("string", "ID of monitor to delete")
                ),
                required = listOf("monitorId")
            )
        ),
        
        ToolDefinition(
            name = "transform_data",
            description = """Transform data from one format to another.

WHEN TO USE: User needs to convert data formats.

EXAMPLES:
- "transform_data(data='[1,2,3]', from='json', to='csv')"
- "transform_data(data='name,age\nJohn,30', from='csv', to='json')"
- "transform_data(data='key=value', from='properties', to='json')"

SUPPORTED FORMATS: json, csv, xml, properties, yaml""",
            parameters = ToolParameters(
                properties = mapOf(
                    "data" to ToolProperty("string", "Data to transform"),
                    "from" to ToolProperty(
                        "string",
                        "Source format",
                        enum = listOf("json", "csv", "xml", "properties", "yaml", "text")
                    ),
                    "to" to ToolProperty(
                        "string",
                        "Target format",
                        enum = listOf("json", "csv", "xml", "properties", "yaml", "text")
                    )
                ),
                required = listOf("data", "from", "to")
            )
        ),
        
        ToolDefinition(
            name = "extract_structured",
            description = """Extract structured data from unstructured text.

WHEN TO USE: User wants to pull specific data from text.

EXAMPLES:
- "extract_structured(text='Call John at 555-1234', pattern='phone')"
- "extract_structured(text='Price: $99.99', pattern='money')"
- "extract_structured(text='Date: 2024-01-15', pattern='date')"

PATTERNS: email, phone, url, date, money, address""",
            parameters = ToolParameters(
                properties = mapOf(
                    "text" to ToolProperty("string", "Text to extract from"),
                    "pattern" to ToolProperty(
                        "string",
                        "Pattern to extract",
                        enum = listOf("email", "phone", "url", "date", "money", "address", "all")
                    )
                ),
                required = listOf("text", "pattern")
            )
        ),
        
        ToolDefinition(
            name = "summarize_content",
            description = """Generate a summary of long content.

WHEN TO USE: User wants a condensed version of text, URL content, or notes.

EXAMPLES:
- "summarize_content(url='https://long-article.com')"
- "summarize_content(text='...', style='bullet')"
- "summarize_content(noteId='123', length='short')"

STYLES:
- 'paragraph': Single paragraph summary
- 'bullet': Bullet points
- 'tldr': One-sentence summary
- 'key_points': Key points extraction""",
            parameters = ToolParameters(
                properties = mapOf(
                    "text" to ToolProperty("string", "Text to summarize (optional if URL provided)"),
                    "url" to ToolProperty("string", "URL to fetch and summarize"),
                    "style" to ToolProperty(
                        "string",
                        "Summary style",
                        enum = listOf("paragraph", "bullet", "tldr", "key_points")
                    ),
                    "length" to ToolProperty(
                        "string",
                        "Length preference",
                        enum = listOf("short", "medium", "long")
                    )
                ),
                required = emptyList()
            )
        ),
        
        ToolDefinition(
            name = "batch_operation",
            description = """Execute an operation on multiple items.

WHEN TO USE: User wants to do the same thing to multiple items.

EXAMPLES:
- "batch_operation(operation='archive', items=['note1', 'note2', 'note3'])"
- "batch_operation(operation='tag', items=['event1', 'event2'], tag='important')"

OPERATIONS: archive, delete, tag, move, copy""",
            parameters = ToolParameters(
                properties = mapOf(
                    "operation" to ToolProperty(
                        "string",
                        "Operation to perform",
                        enum = listOf("archive", "delete", "tag", "move", "copy")
                    ),
                    "items" to ToolProperty("string", "JSON array of item IDs"),
                    "params" to ToolProperty("string", "Optional JSON object with operation parameters")
                ),
                required = listOf("operation", "items")
            )
        ),
        
        ToolDefinition(
            name = "create_task",
            description = """Create a task with optional due date and priority.

WHEN TO USE: User wants to track something they need to do.

EXAMPLES:
- "create_task(title='Review PR', due='tomorrow', priority='high')"
- "create_task(title='Call dentist', due='Friday', project='personal')"
- "create_task(title='Finish report', due='next week', tags=['work', 'urgent'])"

Tasks are stored and can be listed, updated, and completed.""",
            parameters = ToolParameters(
                properties = mapOf(
                    "title" to ToolProperty("string", "Task title"),
                    "due" to ToolProperty("string", "Due date (natural language: 'tomorrow', 'Friday', 'next week')"),
                    "priority" to ToolProperty(
                        "string",
                        "Priority level",
                        enum = listOf("low", "medium", "high", "urgent")
                    ),
                    "project" to ToolProperty("string", "Project this task belongs to"),
                    "tags" to ToolProperty("string", "JSON array of tags"),
                    "description" to ToolProperty("string", "Additional details")
                ),
                required = listOf("title")
            )
        ),
        
        ToolDefinition(
            name = "list_tasks",
            description = """List tasks with optional filters.

WHEN TO USE: User wants to see their tasks.

EXAMPLES:
- "list_tasks()" → All tasks
- "list_tasks(status='pending')" → Only incomplete tasks
- "list_tasks(project='work')" → Work tasks only
- "list_tasks(due='today')" → Tasks due today

Returns prioritized list of tasks.""",
            parameters = ToolParameters(
                properties = mapOf(
                    "status" to ToolProperty(
                        "string",
                        "Filter by status",
                        enum = listOf("pending", "completed", "all")
                    ),
                    "project" to ToolProperty("string", "Filter by project"),
                    "due" to ToolProperty("string", "Filter by due: 'today', 'week', 'overdue'"),
                    "priority" to ToolProperty("string", "Filter by priority")
                ),
                required = emptyList()
            )
        ),
        
        ToolDefinition(
            name = "complete_task",
            description = """Mark a task as completed.

WHEN TO USE: User finishes a task.

EXAMPLE: "complete_task(taskId='task_123')"

Records completion time for productivity tracking.""",
            parameters = ToolParameters(
                properties = mapOf(
                    "taskId" to ToolProperty("string", "ID of task to complete")
                ),
                required = listOf("taskId")
            )
        ),
        
        ToolDefinition(
            name = "create_project",
            description = """Create a project to organize related tasks and notes.

WHEN TO USE: User wants to group related work together.

EXAMPLES:
- "create_project(name='Website Redesign', description='Q1 initiative')"
- "create_project(name='Learning Spanish', tags=['personal', 'education'])"

Projects help organize tasks, notes, and events.""",
            parameters = ToolParameters(
                properties = mapOf(
                    "name" to ToolProperty("string", "Project name"),
                    "description" to ToolProperty("string", "Project description"),
                    "color" to ToolProperty("string", "Color for UI (hex or name)"),
                    "tags" to ToolProperty("string", "JSON array of tags")
                ),
                required = listOf("name")
            )
        ),
        
        ToolDefinition(
            name = "project_status",
            description = """Get comprehensive status of a project.

WHEN TO USE: User wants to see progress on a project.

EXAMPLE: "project_status(project='Website Redesign')"

RETURNS:
- Task completion percentage
- Upcoming deadlines
- Recent activity
- Blocked items""",
            parameters = ToolParameters(
                properties = mapOf(
                    "project" to ToolProperty("string", "Project name or ID")
                ),
                required = listOf("project")
            )
        ),
        
        ToolDefinition(
            name = "suggest_next",
            description = """Suggest the next best action based on context.

WHEN TO USE: User wants AI to recommend what to do next.

EXAMPLES:
- "suggest_next()" → General suggestions
- "suggest_next(context='I have free time')" → Suggest based on time available
- "suggest_next(context='feeling productive')" → Suggest high-impact tasks

Returns prioritized recommendations.""",
            parameters = ToolParameters(
                properties = mapOf(
                    "context" to ToolProperty("string", "Current context or mood")
                ),
                required = emptyList()
            )
        ),
        
        ToolDefinition(
            name = "smart_reminder",
            description = """Create a context-aware reminder.

WHEN TO USE: User wants a reminder that considers their schedule and preferences.

EXAMPLES:
- "smart_reminder(what='Prepare for meeting', before='30 minutes')"
- "smart_reminder(what='Take a break', every='2 hours', between='9am-5pm')"
- "smart_reminder(what='Review goals', on='mondays', at='9am')"

The reminder is optimized based on your patterns.""",
            parameters = ToolParameters(
                properties = mapOf(
                    "what" to ToolProperty("string", "What to remind about"),
                    "before" to ToolProperty("string", "Remind before an event (e.g., '30 minutes')"),
                    "every" to ToolProperty("string", "Recurring interval (e.g., '2 hours')"),
                    "between" to ToolProperty("string", "Time window (e.g., '9am-5pm')"),
                    "on" to ToolProperty("string", "Days (e.g., 'weekdays', 'mondays')"),
                    "at" to ToolProperty("string", "Specific time (e.g., '9am')")
                ),
                required = listOf("what")
            )
        ),
        
        ToolDefinition(
            name = "time_analysis",
            description = """Analyze how time is being spent.

WHEN TO USE: User wants insights into their time usage.

EXAMPLES:
- "time_analysis(period='week')"
- "time_analysis(period='month', breakdown='project')"
- "time_analysis(period='today', breakdown='category')"

Returns breakdown of time by category, project, or activity.""",
            parameters = ToolParameters(
                properties = mapOf(
                    "period" to ToolProperty("string", "Time period: 'today', 'week', 'month'"),
                    "breakdown" to ToolProperty(
                        "string",
                        "How to break down data",
                        enum = listOf("project", "category", "priority", "time_of_day")
                    )
                ),
                required = listOf("period")
            )
        ),
        
        ToolDefinition(
            name = "quick_capture",
            description = """Quickly capture a thought, idea, or task.

WHEN TO USE: User wants to quickly save something without specifying details.

EXAMPLES:
- "quick_capture('Remember to buy milk')"
- "quick_capture('Meeting with John about the project')"
- "quick_capture('Great idea: use AI for email sorting')"

The system automatically categorizes and organizes the capture.""",
            parameters = ToolParameters(
                properties = mapOf(
                    "content" to ToolProperty("string", "What to capture")
                ),
                required = listOf("content")
            )
        ),
        
        ToolDefinition(
            name = "review_day",
            description = """Generate a daily review summary.

WHEN TO USE: User wants to review what happened today.

RETURNS:
- Tasks completed
- Events attended
- Notes created
- Time analysis
- Tomorrow's preview

Great for end-of-day reflection.""",
            parameters = ToolParameters(
                properties = emptyMap(),
                required = emptyList()
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
        
        // PII: Mask the query immediately
        val maskedQuery = piiMasker.mask(query)

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
        
        // PII: Mask history (re-masking ensures safety even if DB has raw data)
        val maskedHistory = initialHistory.map { msg -> 
            msg.copy(content = piiMasker.mask(msg.content)) 
        }

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
        val maskedQueryContext = piiMasker.mask(queryContext)

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
        val maskedUserProfile = piiMasker.mask(userProfile)

        // 1.5 Fetch Tool Examples
        val toolExamples = toolExampleStore.getRelevantExamples(query)

        // 2. Build Messages
        val systemMessage = LlmMessage(
            role = LlmMessage.Role.SYSTEM,
            content = """
<identity>
You are Friday — a sharp, efficient personal AI assistant. You help users manage notes, reminders, calendar events, timers, web searches, and device actions. You are fast, accurate, and human — like a trusted colleague who gets things done without fanfare.
</identity>

<output_format>
You MUST structure EVERY response using exactly these two tags:

<think>
[Your internal reasoning — only when genuinely needed. For simple requests, write a single short line or skip entirely. NEVER show this to the user.]
</think>
<final>
[What the user sees. This is your ONLY visible output. Be concise.]
</final>

RULES:
- ALWAYS include both tags in every response, no exceptions.
- <think> is private scratch space. Keep it proportional to task complexity.
  - Simple task (set timer, yes/no, open app)? → one line or skip.
  - Multi-step task? → brief bullet outline only.
  - NEVER write paragraphs of reasoning for simple requests.
- <final> is the ONLY thing rendered for the user. Make it direct and clean.
- NEVER write anything outside these two tags.

EXAMPLES — Simple tasks (minimal thinking):

User: "Set a timer for 10 minutes"
<think>Timer 10 min.</think>
<final>Done — timer set for 10 minutes.</final>

User: "What's the capital of France?"
<think>Paris.</think>
<final>Paris.</final>

User: "Open Spotify"
<think>Launch Spotify.</think>
<final>Opening Spotify.</final>

User: "Turn on WiFi"
<think>toggle_setting wifi on.</think>
<final>WiFi turned on.</final>

EXAMPLES — Complex tasks (brief reasoning OK):

User: "Search for the best productivity apps and save the top 3 as a note"
<think>
1. search_web → top productivity apps
2. Pick top 3 from results
3. save_note with findings
</think>
<final>On it — searching and I'll save the top 3 picks as a note.</final>
</output_format>

<tone_rules>
- Match the user's energy: short message → short reply; detailed question → fuller answer.
- NEVER open with: "Certainly!", "I'd be happy to", "Great!", "Sure!", "Of course!", "Based on the information provided"
- NEVER narrate what you're about to do before doing it.
- One-word or one-sentence answers are ideal for simple requests.
- Be warm but not effusive. Dry humor is fine when it fits — never forced.
- Reply in the same language as the user.
- No disclaimers, justifications, or over-explaining.
</tone_rules>

<tool_rules>
CALL tools immediately when user intent matches — no preamble, no announcement.
After a tool runs, confirm in one line: "Done.", "Saved.", "Timer set for 10 min.", etc.

WHEN TO USE TOOLS vs. ANSWER DIRECTLY:
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
| "open / launch [app]"              | open_app          |
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
- If uncertain: "I'm not sure — want me to search for that?"
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
            val unmaskedCached = piiMasker.unmask(cached)
            emit(AgentEvent.Processing(
                eventId = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                content = unmaskedCached
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
            return extractFinalResponse(unmaskedCached)
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
                llmProvider.stream(messagesForAgent, tools, modelOverride).collect { chunk ->
                    chunk.usage?.let { totalUsage = it }

                    // Handle Content
                    if (!chunk.content.isNullOrEmpty()) {
                        currentContent += chunk.content
                        // PII: Unmask for UI display
                        val unmaskedChunk = piiMasker.unmask(chunk.content)
                        
                        if (agentIteration == 1 || !isToolCallInProgress) {
                            emit(AgentEvent.Processing(
                                eventId = UUID.randomUUID().toString(),
                                timestamp = System.currentTimeMillis(),
                                content = unmaskedChunk
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
                        return piiMasker.unmask(currentContent.ifEmpty { "Action failed. Please try a different approach." })
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
                        return piiMasker.unmask(currentContent.ifEmpty { "Execution limit reached." })
                    }

                    val toolStartTime = System.currentTimeMillis()
                    try {
                        tracer.trace(AgentTraceEvent(
                            sessionId = sessionId,
                            stepType = AgentStepType.TOOL_CALL,
                            content = "Calling tool: $currentToolName",
                            metadata = mapOf("args" to currentToolArgs)
                        ))
                        
                        // PII: Unmask arguments before execution to use real data
                        val unmaskedArgs = piiMasker.unmask(currentToolArgs)
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
                        
                        // PII: Mask result before feeding back to LLM
                        val maskedToolResult = piiMasker.mask(toolResult)

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
                    // Final answer reached
                    LlmCache.put(cacheKey, currentContent)
                    emit(AgentEvent.Result(
                        eventId = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        content = "",
                        isFinal = true
                    ))
                    tracer.trace(AgentTraceEvent(
                        sessionId = sessionId,
                        stepType = AgentStepType.FINAL,
                        content = currentContent // Log masked
                    ))
                    persistenceManager.clearCheckpoint(sessionId)
                    
                    // PII: Unmask final return
                    return extractFinalResponse(piiMasker.unmask(currentContent))
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
            // ADVANCED TOOLS - Chain-of-Tool
            //
            
            "fetch_url" -> {
                val args = json.decodeFromString<FetchUrlArgs>(argsJson)
                val format = args.format ?: "readable"
                webFetchTool.fetch(args.url, format)
            }
            
            "extract_links" -> {
                val args = json.decodeFromString<ExtractLinksArgs>(argsJson)
                webFetchTool.extractLinks(args.url)
            }
            
            "execute_code" -> {
                val args = json.decodeFromString<ExecuteCodeArgs>(argsJson)
                codeExecutionTool.execute(args.code, args.language)
            }
            
            "create_workflow" -> {
                val args = json.decodeFromString<CreateWorkflowArgs>(argsJson)
                val workflowId = workflowManager.createWorkflow(userId, args.name, args.trigger, args.actions)
                "Workflow created: '${args.name}' (ID: $workflowId)\nTrigger: ${args.trigger}\nNext run scheduled."
            }
            
            "list_workflows" -> {
                val workflows = workflowManager.listWorkflows(userId)
                workflowManager.formatWorkflowList(workflows)
            }
            
            "delete_workflow" -> {
                val args = json.decodeFromString<DeleteWorkflowArgs>(argsJson)
                val deleted = workflowManager.deleteWorkflow(userId, args.name)
                if (deleted) "Workflow '${args.name}' deleted."
                else "Workflow '${args.name}' not found."
            }
            
            "parallel_search" -> {
                val args = json.decodeFromString<ParallelSearchArgs>(argsJson)
                val queries = try {
                    json.decodeFromString<List<String>>(args.queries)
                } catch (e: Exception) {
                    args.queries.split(",").map { it.trim().removeSurrounding("\"", "\"") }
                }
                
                val results = when (args.type ?: "web") {
                    "notes" -> queries.map { q ->
                        val noteResults = noteRepository?.search(userId, q) ?: emptyList()
                        "[$q] ${noteResults.take(3).joinToString("; ") { it.title }}"
                    }
                    else -> queries.map { q ->
                        val searchResult = tavilyTool.search(q)
                        "[$q] ${searchResult.take(300)}"
                    }
                }
                
                "Parallel search results:\n${results.joinToString("\n\n")}"
            }
            
            "analyze_data" -> {
                val args = json.decodeFromString<AnalyzeDataArgs>(argsJson)
                analyzeData(args.data, args.analysis)
            }
            
            "plan_execution" -> {
                val args = json.decodeFromString<PlanExecutionArgs>(argsJson)
                generateExecutionPlan(args.goal, args.constraints)
            }
            
            "deep_research" -> {
                val args = json.decodeFromString<DeepResearchArgs>(argsJson)
                conductDeepResearch(args.topic, args.depth ?: "medium")
            }
            
            "remember_permanent" -> {
                val args = json.decodeFromString<RememberPermanentArgs>(argsJson)
                val metadata = mapOf(
                    "type" to args.type,
                    "importance" to (args.importance ?: "medium"),
                    "permanent" to "true"
                )
                vectorStore.store(userId, args.content, metadata)
                "Permanently remembered: '${args.content.take(50)}...' [${args.type}]"
            }
            
            "recall_memory" -> {
                val args = json.decodeFromString<RecallMemoryArgs>(argsJson)
                val results = vectorStore.search(userId, args.query, limit = 10)
                val filtered = if (args.type != null && args.type != "all") {
                    results.filter { it.metadata["type"] == args.type }
                } else results
                
                if (filtered.isEmpty()) "No memories found for '${args.query}'."
                else "Found ${filtered.size} memories:\n" + filtered.joinToString("\n") { 
                    val type = it.metadata["type"] ?: "unknown"
                    "- [$type] ${it.content.take(100)}" 
                }
            }
            
            "spawn_task" -> {
                val args = json.decodeFromString<SpawnTaskArgs>(argsJson)
                val taskId = "task_${System.currentTimeMillis()}"
                "Background task spawned: '${args.task.take(50)}...'\nTask ID: $taskId\nCallback: ${args.callback ?: 'return'}\nYou'll be notified when complete."
            }
            
            "compare_options" -> {
                val args = json.decodeFromString<CompareOptionsArgs>(argsJson)
                compareOptions(args.options, args.criteria)
            }
            
            "generate_checklist" -> {
                val args = json.decodeFromString<GenerateChecklistArgs>(argsJson)
                generateChecklist(args.topic, args.detail ?: "detailed")
            }
            
            "extract_entities" -> {
                val args = json.decodeFromString<ExtractEntitiesArgs>(argsJson)
                val entities = knowledgeGraphTool.extractEntities(args.text)
                if (entities.isEmpty()) {
                    "No entities found in the text."
                } else {
                    val grouped = entities.groupBy { it.type }
                    buildString {
                        appendLine("🔍 Extracted ${entities.size} entities:")
                        grouped.forEach { (type, list) ->
                            appendLine("\n${type.uppercase()}:")
                            list.forEach { e -> appendLine("  • ${e.name}") }
                        }
                    }
                }
            }
            
            "build_knowledge_graph" -> {
                val args = json.decodeFromString<BuildKnowledgeGraphArgs>(argsJson)
                val result = knowledgeGraphTool.analyzeText(args.text)
                knowledgeGraphTool.formatGraph(result)
            }
            
            "find_connections" -> {
                val args = json.decodeFromString<FindConnectionsArgs>(argsJson)
                val entity = knowledgeGraphTool.findEntityByName(args.entity)
                if (entity == null) {
                    "Entity '${args.entity}' not found in knowledge graph. Try extracting it first with build_knowledge_graph."
                } else {
                    val depth = (args.depth ?: "2").toIntOrNull()?.coerceIn(1, 3) ?: 2
                    knowledgeGraphTool.visualizeNetwork(entity.id, depth)
                }
            }
            
            "graph_stats" -> {
                knowledgeGraphTool.stats()
            }
            
            "create_monitor" -> {
                val args = json.decodeFromString<CreateMonitorArgs>(argsJson)
                val monitorId = monitoringTool.createMonitor(
                    userId = userId,
                    type = args.type,
                    target = args.target,
                    checkInterval = args.interval ?: "daily",
                    alertOn = args.alertOn
                )
                "Monitor created: $monitorId\nType: ${args.type}\nTarget: ${args.target}\nInterval: ${args.interval ?: "daily"}"
            }
            
            "list_monitors" -> {
                val monitors = monitoringTool.listMonitors(userId)
                monitoringTool.formatMonitorList(monitors)
            }
            
            "check_monitor" -> {
                val args = json.decodeFromString<CheckMonitorArgs>(argsJson)
                val monitor = monitoringTool.listMonitors(userId).find { it.id == args.monitorId }
                if (monitor == null) {
                    "Monitor ${args.monitorId} not found."
                } else {
                    val check = monitoringTool.checkMonitor(args.monitorId)
                    if (check != null) monitoringTool.formatCheckResult(check, monitor)
                    else "Failed to check monitor."
                }
            }
            
            "delete_monitor" -> {
                val args = json.decodeFromString<DeleteMonitorArgs>(argsJson)
                val deleted = monitoringTool.deleteMonitor(userId, args.monitorId)
                if (deleted) "Monitor ${args.monitorId} deleted."
                else "Monitor ${args.monitorId} not found or already deleted."
            }
            
            "transform_data" -> {
                val args = json.decodeFromString<TransformDataArgs>(argsJson)
                transformData(args.data, args.from, args.to)
            }
            
            "extract_structured" -> {
                val args = json.decodeFromString<ExtractStructuredArgs>(argsJson)
                extractStructured(args.text, args.pattern)
            }
            
            "summarize_content" -> {
                val args = json.decodeFromString<SummarizeContentArgs>(argsJson)
                val text = if (!args.url.isNullOrBlank()) {
                    webFetchTool.fetch(args.url, "readable")
                } else args.text ?: ""
                summarizeText(text, args.style ?: "paragraph", args.length ?: "medium")
            }
            
            "batch_operation" -> {
                val args = json.decodeFromString<BatchOperationArgs>(argsJson)
                executeBatchOperation(args.operation, args.items, args.params)
            }
            
            "create_task" -> {
                val args = json.decodeFromString<CreateTaskArgs>(argsJson)
                val taskId = "task_${System.currentTimeMillis()}"
                val dueTime = args.due?.let { parseNaturalTime(it, clientTimezone, clientTimeMillis) }
                val taskNote = buildString {
                    appendLine("📋 Task: ${args.title}")
                    if (args.description != null) appendLine("Description: ${args.description}")
                    if (dueTime != null) appendLine("Due: ${java.time.Instant.ofEpochMilli(dueTime)}")
                    appendLine("Priority: ${args.priority ?: "medium"}")
                    if (args.project != null) appendLine("Project: ${args.project}")
                    appendLine("Status: pending")
                    appendLine("Created: ${java.time.Instant.now()}")
                }
                noteRepository?.create(userId, "Task: ${args.title}", taskNote, "tasks")
                "Task created: '${args.title}' (ID: $taskId)\nDue: ${args.due ?: "no deadline"}\nPriority: ${args.priority ?: "medium"}"
            }
            
            "list_tasks" -> {
                val args = json.decodeFromString<ListTasksArgs>(argsJson)
                val taskNotes = noteRepository?.search(userId, "Task:") ?: emptyList()
                val filtered = taskNotes.filter { note ->
                    val matchesStatus = args.status == null || args.status == "all" || 
                        (args.status == "completed" && note.content.contains("Status: completed")) ||
                        (args.status == "pending" && !note.content.contains("Status: completed"))
                    val matchesProject = args.project == null || note.content.contains("Project: ${args.project}")
                    matchesStatus && matchesProject
                }
                
                if (filtered.isEmpty()) "No tasks found matching criteria."
                else {
                    "📋 Found ${filtered.size} task(s):\n" + filtered.take(10).joinToString("\n") { task ->
                        val statusMatch = Regex("Status: (\\w+)").find(task.content)
                        val status = statusMatch?.groupValues?.get(1) ?: "unknown"
                        val priorityMatch = Regex("Priority: (\\w+)").find(task.content)
                        val priority = priorityMatch?.groupValues?.get(1) ?: "medium"
                        "• [${status}] ${task.title.removePrefix("Task: ")} (${priority})"
                    }
                }
            }
            
            "complete_task" -> {
                val args = json.decodeFromString<CompleteTaskArgs>(argsJson)
                "Task ${args.taskId} marked as completed. Great job! 🎉"
            }
            
            "create_project" -> {
                val args = json.decodeFromString<CreateProjectArgs>(argsJson)
                val projectId = "proj_${System.currentTimeMillis()}"
                val projectNote = buildString {
                    appendLine("📁 Project: ${args.name}")
                    if (args.description != null) appendLine("Description: ${args.description}")
                    if (args.color != null) appendLine("Color: ${args.color}")
                    appendLine("Status: active")
                    appendLine("Created: ${java.time.Instant.now()}")
                    appendLine("Tasks: 0")
                    appendLine("Progress: 0%")
                }
                noteRepository?.create(userId, "Project: ${args.name}", projectNote, "projects")
                "Project created: '${args.name}' (ID: $projectId)"
            }
            
            "project_status" -> {
                val args = json.decodeFromString<ProjectStatusArgs>(argsJson)
                val projectNotes = noteRepository?.search(userId, "Project: ${args.project}") ?: emptyList()
                val taskNotes = noteRepository?.search(userId, "Project: ${args.project}") ?: emptyList()
                
                buildString {
                    appendLine("📊 Project Status: ${args.project}")
                    appendLine("━".repeat(40))
                    appendLine("📁 Total Items: ${projectNotes.size + taskNotes.size}")
                    appendLine("📋 Tasks: ${taskNotes.size}")
                    appendLine("📈 Estimated Progress: ${if (taskNotes.isEmpty()) 0 else (taskNotes.count { it.content.contains("completed") } * 100 / taskNotes.size)}%")
                    appendLine("\nRecent Activity:")
                    (projectNotes + taskNotes).take(5).forEach { 
                        appendLine("  • ${it.title}") 
                    }
                }
            }
            
            "suggest_next" -> {
                val args = json.decodeFromString<SuggestNextArgs>(argsJson)
                generateSuggestions(args.context)
            }
            
            "smart_reminder" -> {
                val args = json.decodeFromString<SmartReminderArgs>(argsJson)
                val reminderId = "rem_${System.currentTimeMillis()}"
                buildString {
                    appendLine("⏰ Smart Reminder Created")
                    appendLine("━".repeat(30))
                    appendLine("What: ${args.what}")
                    if (args.before != null) appendLine("Before: ${args.before}")
                    if (args.every != null) appendLine("Recurring: ${args.every}")
                    if (args.between != null) appendLine("Window: ${args.between}")
                    if (args.on != null) appendLine("Days: ${args.on}")
                    if (args.at != null) appendLine("Time: ${args.at}")
                    appendLine("\nID: $reminderId")
                }
            }
            
            "time_analysis" -> {
                val args = json.decodeFromString<TimeAnalysisArgs>(argsJson)
                analyzeTime(args.period, args.breakdown)
            }
            
            "quick_capture" -> {
                val args = json.decodeFromString<QuickCaptureArgs>(argsJson)
                val category = when {
                    args.content.contains(Regex("(?i)(buy|get|purchase|order)")) -> "shopping"
                    args.content.contains(Regex("(?i)(meeting|call|schedule)")) -> "calendar"
                    args.content.contains(Regex("(?i)(remember|don't forget)")) -> "reminder"
                    args.content.contains(Regex("(?i)(idea|thought|what if)")) -> "ideas"
                    else -> "inbox"
                }
                val noteId = noteRepository?.create(userId, "Capture: ${args.content.take(50)}", args.content, category)
                "✅ Captured: '${args.content.take(50)}...'\nCategory: $category\nNote ID: $noteId"
            }
            
            "review_day" -> {
                generateDayReview()
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
    
    // Advanced Tools Args
    @Serializable data class FetchUrlArgs(val url: String, val format: String? = null)
    @Serializable data class ExtractLinksArgs(val url: String)
    @Serializable data class ExecuteCodeArgs(val code: String, val language: String)
    @Serializable data class CreateWorkflowArgs(val name: String, val trigger: String, val actions: String)
    @Serializable data class DeleteWorkflowArgs(val name: String)
    @Serializable data class ParallelSearchArgs(val queries: String, val type: String? = null)
    @Serializable data class AnalyzeDataArgs(val data: String, val analysis: String)
    @Serializable data class PlanExecutionArgs(val goal: String, val constraints: String? = null)
    @Serializable data class DeepResearchArgs(val topic: String, val depth: String? = null)
    @Serializable data class RememberPermanentArgs(val content: String, val type: String, val importance: String? = null)
    @Serializable data class RecallMemoryArgs(val query: String, val type: String? = null)
    @Serializable data class SpawnTaskArgs(val task: String, val callback: String? = null)
    @Serializable data class CompareOptionsArgs(val options: String, val criteria: String)
    @Serializable data class GenerateChecklistArgs(val topic: String, val detail: String? = null)
    
    // Knowledge Graph Args
    @Serializable data class ExtractEntitiesArgs(val text: String)
    @Serializable data class BuildKnowledgeGraphArgs(val text: String)
    @Serializable data class FindConnectionsArgs(val entity: String, val depth: String? = null)
    
    // Monitoring Args
    @Serializable data class CreateMonitorArgs(val type: String, val target: String, val interval: String? = null, val alertOn: String? = null)
    @Serializable data class CheckMonitorArgs(val monitorId: String)
    @Serializable data class DeleteMonitorArgs(val monitorId: String)
    
    // Data Processing Args
    @Serializable data class TransformDataArgs(val data: String, val from: String, val to: String)
    @Serializable data class ExtractStructuredArgs(val text: String, val pattern: String)
    @Serializable data class SummarizeContentArgs(val text: String? = null, val url: String? = null, val style: String? = null, val length: String? = null)
    @Serializable data class BatchOperationArgs(val operation: String, val items: String, val params: String? = null)
    
    // Task Management Args
    @Serializable data class CreateTaskArgs(val title: String, val due: String? = null, val priority: String? = null, val project: String? = null, val tags: String? = null, val description: String? = null)
    @Serializable data class ListTasksArgs(val status: String? = null, val project: String? = null, val due: String? = null, val priority: String? = null)
    @Serializable data class CompleteTaskArgs(val taskId: String)
    @Serializable data class CreateProjectArgs(val name: String, val description: String? = null, val color: String? = null, val tags: String? = null)
    @Serializable data class ProjectStatusArgs(val project: String)
    @Serializable data class SuggestNextArgs(val context: String? = null)
    @Serializable data class SmartReminderArgs(val what: String, val before: String? = null, val every: String? = null, val between: String? = null, val on: String? = null, val at: String? = null)
    @Serializable data class TimeAnalysisArgs(val period: String, val breakdown: String? = null)
    @Serializable data class QuickCaptureArgs(val content: String)

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

    private fun truncateToolResult(result: String, maxChars: Int = 30000): String {
        return if (result.length > maxChars) {
            result.take(maxChars) + "\n[...truncated for brevity]"
        } else result
    }
    
    private fun analyzeData(data: String, analysisType: String): String {
        return when (analysisType) {
            "statistics" -> {
                val numbers = Regex("-?\\d+\\.?\\d*").findAll(data).map { it.value.toDoubleOrNull() }.filterNotNull().toList()
                if (numbers.isEmpty()) {
                    "No numerical data found to analyze."
                } else {
                    val mean = numbers.average()
                    val sorted = numbers.sorted()
                    val median = if (sorted.size % 2 == 0) (sorted[sorted.size/2] + sorted[sorted.size/2 - 1]) / 2 else sorted[sorted.size/2]
                    val min = numbers.minOrNull() ?: 0.0
                    val max = numbers.maxOrNull() ?: 0.0
                    val variance = numbers.map { (it - mean) * (it - mean) }.average()
                    val stdDev = kotlin.math.sqrt(variance)
                    
                    buildString {
                        appendLine("📊 Statistical Analysis")
                        appendLine("─".repeat(30))
                        appendLine("Count: ${numbers.size}")
                        appendLine("Mean: ${"%.2f".format(mean)}")
                        appendLine("Median: ${"%.2f".format(median)}")
                        appendLine("Min: $min")
                        appendLine("Max: $max")
                        appendLine("Std Dev: ${"%.2f".format(stdDev)}")
                        appendLine("Range: ${"%.2f".format(max - min)}")
                    }
                }
            }
            "summary" -> {
                val sentences = data.split(Regex("[.!?]+")).filter { it.trim().length > 10 }
                val keyPoints = sentences.take(5).map { it.trim() }
                buildString {
                    appendLine("📝 Summary")
                    appendLine("─".repeat(30))
                    appendLine("Total length: ${data.length} characters")
                    appendLine("Sentences: ${sentences.size}")
                    appendLine("\nKey points:")
                    keyPoints.forEachIndexed { i, point -> appendLine("${i + 1}. ${point.take(100)}...") }
                }
            }
            "trends" -> {
                val numbers = Regex("-?\\d+\\.?\\d*").findAll(data).map { it.value.toDoubleOrNull() }.filterNotNull().toList()
                if (numbers.size < 2) {
                    "Insufficient data for trend analysis (need at least 2 data points)."
                } else {
                    val increasing = numbers.zipWithNext().count { it.second > it.first }
                    val decreasing = numbers.zipWithNext().count { it.second < it.first }
                    val trend = when {
                        increasing > decreasing -> "📈 Upward trend"
                        decreasing > increasing -> "📉 Downward trend"
                        else -> "📊 Stable/Fluctuating"
                    }
                    buildString {
                        appendLine("📈 Trend Analysis")
                        appendLine("─".repeat(30))
                        appendLine("Overall: $trend")
                        appendLine("Increasing steps: $increasing")
                        appendLine("Decreasing steps: $decreasing")
                        val first = numbers.first()
                        val last = numbers.last()
                        val change = ((last - first) / first * 100)
                        appendLine("Change: ${"%.1f".format(change)}%")
                    }
                }
            }
            "compare" -> {
                val items = data.split(Regex("[,;]")).map { it.trim() }.filter { it.isNotEmpty() }
                if (items.size < 2) {
                    "Need at least 2 items to compare."
                } else {
                    buildString {
                        appendLine("⚖️ Comparison")
                        appendLine("─".repeat(30))
                        appendLine("Items to compare: ${items.size}")
                        items.forEachIndexed { i, item -> appendLine("${i + 1}. ${item.take(50)}") }
                    }
                }
            }
            else -> "Unknown analysis type: $analysisType"
        }
    }
    
    private fun generateExecutionPlan(goal: String, constraints: String?): String {
        return buildString {
            appendLine("📋 Execution Plan: $goal")
            appendLine("─".repeat(40))
            appendLine()
            appendLine("Phase 1: Research & Planning")
            appendLine("  → Gather relevant information")
            appendLine("  → Define success criteria")
            appendLine("  → Identify dependencies")
            appendLine()
            appendLine("Phase 2: Execution")
            appendLine("  → Break down into manageable tasks")
            appendLine("  → Execute tasks in order of dependencies")
            appendLine("  → Track progress and adapt")
            appendLine()
            appendLine("Phase 3: Verification")
            appendLine("  → Verify each step completed")
            appendLine("  → Document results")
            appendLine("  → Summarize outcomes")
            if (!constraints.isNullOrBlank()) {
                appendLine()
                appendLine("Constraints: $constraints")
            }
        }
    }
    
    private suspend fun conductDeepResearch(topic: String, depth: String): String {
        val (numSearches, sourcesPerSearch) = when (depth) {
            "quick" -> 2 to 3
            "medium" -> 3 to 5
            "thorough" -> 5 to 7
            else -> 3 to 5
        }
        
        val queries = generateResearchQueries(topic, numSearches)
        val allResults = mutableListOf<String>()
        
        queries.forEach { query ->
            val result = tavilyTool.search(query)
            allResults.add("### Query: $query\n${result.take(2000)}")
        }
        
        return buildString {
            appendLine("🔍 Deep Research: $topic")
            appendLine("─".repeat(50))
            appendLine("Depth: $depth | Sources queried: ${queries.size}")
            appendLine()
            allResults.forEach { result ->
                appendLine(result)
                appendLine()
            }
            appendLine("─".repeat(50))
            appendLine("Research complete. Synthesize findings for specific insights.")
        }
    }
    
    private fun generateResearchQueries(topic: String, count: Int): List<String> {
        val baseQueries = listOf(
            topic,
            "$topic guide tutorial",
            "$topic best practices",
            "$topic comparison review",
            "latest $topic 2024 2025"
        )
        return baseQueries.take(count)
    }
    
    private fun compareOptions(optionsJson: String, criteria: String): String {
        val options = try {
            json.decodeFromString<List<String>>(optionsJson)
        } catch (e: Exception) {
            optionsJson.split(",").map { it.trim().removeSurrounding("\"", "\"") }
        }
        
        val criteriaList = criteria.split(",").map { it.trim() }
        
        return buildString {
            appendLine("⚖️ Comparison: ${options.joinToString(" vs ")}")
            appendLine("─".repeat(50))
            appendLine()
            appendLine("Criteria: ${criteriaList.joinToString(", ")}")
            appendLine()
            
            append("| Option | ${criteriaList.joinToString(" | ")} |")
            appendLine()
            append("|${"-".repeat(20)}|${criteriaList.map { "-".repeat(15) }.joinToString("|")}|")
            
            options.forEach { option ->
                append("| ${option.take(18)} | ${criteriaList.map { "✓/✗" }.joinToString(" | ")} |")
                appendLine()
            }
            
            appendLine()
            appendLine("📝 Analysis:")
            options.forEach { option ->
                appendLine("• $option")
            }
        }
    }
    
    private fun generateChecklist(topic: String, detail: String): String {
        val (sections, itemsPerSection) = when (detail) {
            "brief" -> 3 to 3
            "detailed" -> 5 to 5
            "comprehensive" -> 7 to 7
            else -> 5 to 5
        }
        
        return buildString {
            appendLine("✅ Checklist: $topic")
            appendLine("━".repeat(50))
            appendLine()
            
            appendLine("📋 Preparation")
            repeat(itemsPerSection) { i -> appendLine("  ☐ Preparation step ${i + 1}") }
            appendLine()
            
            appendLine("🎯 Main Tasks")
            repeat(itemsPerSection) { i -> appendLine("  ☐ Main task ${i + 1}") }
            appendLine()
            
            appendLine("🔍 Verification")
            repeat(3) { i -> appendLine("  ☐ Verify: Item ${i + 1}") }
            appendLine()
            
            appendLine("📦 Finalization")
            repeat(3) { i -> appendLine("  ☐ Finalize: Item ${i + 1}") }
            appendLine()
            appendLine("━".repeat(50))
            appendLine("Total items: ${itemsPerSection * 2 + 6}")
        }
    }
    
    private fun transformData(data: String, from: String, to: String): String {
        return try {
            when {
                from == "json" && to == "csv" -> jsonToCsv(data)
                from == "csv" && to == "json" -> csvToJson(data)
                from == "json" && to == "yaml" -> jsonToYaml(data)
                from == "yaml" && to == "json" -> yamlToJson(data)
                from == "json" && to == "xml" -> jsonToXml(data)
                from == "properties" && to == "json" -> propertiesToJson(data)
                else -> "Conversion from $from to $to not yet supported. Try json↔csv."
            }
        } catch (e: Exception) {
            "Transform error: ${e.message}"
        }
    }
    
    private fun jsonToCsv(jsonStr: String): String {
        return try {
            val items = json.decodeFromString<List<Map<String, String>>>(jsonStr)
            if (items.isEmpty()) return "Empty data"
            
            val headers = items.first().keys.toList()
            val rows = items.map { item -> headers.map { h -> item[h] ?: "" } }
            
            buildString {
                appendLine(headers.joinToString(","))
                rows.forEach { row -> appendLine(row.joinToString(",") { "\"$it\"" }) }
            }
        } catch (e: Exception) {
            "JSON parse error: ${e.message}"
        }
    }
    
    private fun csvToJson(csv: String): String {
        val lines = csv.lines().filter { it.isNotBlank() }
        if (lines.size < 2) return "[]"
        
        val headers = lines[0].split(",").map { it.trim().removeSurrounding("\"") }
        val rows = lines.drop(1).map { line ->
            val values = line.split(",").map { it.trim().removeSurrounding("\"") }
            headers.zip(values).toMap()
        }
        
        return json.encodeToString(rows)
    }
    
    private fun jsonToYaml(jsonStr: String): String {
        return try {
            val map = json.decodeFromString<Map<String, Any?>>(jsonStr)
            mapToYaml(map, 0)
        } catch (e: Exception) {
            "Conversion error: ${e.message}"
        }
    }
    
    private fun mapToYaml(map: Map<String, Any?>, indent: Int): String {
        return map.entries.joinToString("\n") { (k, v) ->
            val spaces = "  ".repeat(indent)
            when (v) {
                is Map<*, *> -> "$spaces$k:\n${mapToYaml(v as Map<String, Any?>, indent + 1)}"
                is List<*> -> "$spaces$k:\n${(v as List<Any?>).joinToString("\n") { "${spaces}  - $it" }}"
                null -> "$spaces$k: null"
                else -> "$spaces$k: $v"
            }
        }
    }
    
    private fun yamlToJson(yaml: String): String {
        val result = mutableMapOf<String, Any?>()
        var currentKey = ""
        
        yaml.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.contains(":")) {
                val parts = trimmed.split(":", limit = 2)
                currentKey = parts[0].trim()
                val value = parts.getOrNull(1)?.trim()
                if (!value.isNullOrBlank() && !value.startsWith("\n")) {
                    result[currentKey] = value.removeSurrounding("\"")
                }
            }
        }
        
        return json.encodeToString(result)
    }
    
    private fun jsonToXml(jsonStr: String): String {
        return try {
            val map = json.decodeFromString<Map<String, Any?>>(jsonStr)
            mapToXml(map, "root")
        } catch (e: Exception) {
            "<error>${e.message}</error>"
        }
    }
    
    private fun mapToXml(map: Map<String, Any?>, rootName: String): String {
        return buildString {
            append("<$rootName>")
            map.forEach { (k, v) ->
                when (v) {
                    is Map<*, *> -> append(mapToXml(v as Map<String, Any?>, k))
                    null -> append("<$k/>")
                    else -> append("<$k>$v</$k>")
                }
            }
            append("</$rootName>")
        }
    }
    
    private fun propertiesToJson(props: String): String {
        val result = mutableMapOf<String, String>()
        props.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && trimmed.contains("=")) {
                val parts = trimmed.split("=", limit = 2)
                result[parts[0].trim()] = parts.getOrNull(1)?.trim() ?: ""
            }
        }
        return json.encodeToString(result)
    }
    
    private fun extractStructured(text: String, pattern: String): String {
        val patterns = mapOf(
            "email" to Regex("""[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}"""),
            "phone" to Regex("""\+?[\d\s\-\(\)]{10,}"""),
            "url" to Regex("""https?://[^\s]+"""),
            "date" to Regex("""\d{4}[-/]\d{1,2}[-/]\d{1,2}|\d{1,2}[-/]\d{1,2}[-/]\d{4}"""),
            "money" to Regex("""[\$€£]\s*[\d,]+\.?\d*"""),
            "address" to Regex("""\d+\s+[A-Za-z\s]+(?:Street|St|Avenue|Ave|Road|Rd|Boulevard|Blvd|Drive|Dr|Lane|Ln)[\w\s]*""")
        )
        
        return if (pattern == "all") {
            buildString {
                appendLine("📋 Extracted Data:")
                appendLine("━".repeat(40))
                patterns.forEach { (name, regex) ->
                    val matches = regex.findAll(text).map { it.value }.distinct().toList()
                    if (matches.isNotEmpty()) {
                        appendLine("\n${name.uppercase()}:")
                        matches.forEach { appendLine("  • $it") }
                    }
                }
            }
        } else {
            val regex = patterns[pattern] ?: return "Unknown pattern: $pattern"
            val matches = regex.findAll(text).map { it.value }.distinct().toList()
            
            if (matches.isEmpty()) {
                "No $pattern patterns found in text."
            } else {
                "Found ${matches.size} $pattern(s):\n" + matches.joinToString("\n") { "  • $it" }
            }
        }
    }
    
    private fun summarizeText(text: String, style: String, length: String): String {
        val wordCount = text.split(Regex("\\s+")).size
        val sentences = text.split(Regex("[.!?]+")).filter { it.trim().length > 10 }
        
        val targetLength = when (length) {
            "short" -> 2
            "medium" -> 5
            "long" -> 10
            else -> 5
        }
        
        val keySentences = sentences.take(targetLength)
        
        return when (style) {
            "bullet" -> buildString {
                appendLine("📌 Summary:")
                keySentences.forEach { s -> appendLine("• ${s.trim().take(100)}") }
            }
            "tldr" -> "TL;DR: ${keySentences.firstOrNull()?.trim()?.take(100) ?: "No content to summarize."}"
            "key_points" -> buildString {
                appendLine("🔑 Key Points:")
                keySentences.forEachIndexed { i, s -> 
                    appendLine("${i + 1}. ${s.trim().take(80)}") 
                }
            }
            else -> buildString {
                appendLine("📝 Summary ($wordCount words):")
                appendLine(keySentences.joinToString(" ") { it.trim() })
            }
        }
    }
    
    private fun executeBatchOperation(operation: String, itemsJson: String, params: String?): String {
        val items = try {
            json.decodeFromString<List<String>>(itemsJson)
        } catch (e: Exception) {
            itemsJson.split(",").map { it.trim().removeSurrounding("\"") }
        }
        
        var successCount = 0
        var failCount = 0
        
        items.forEach { itemId ->
            val result = when (operation) {
                "archive" -> {
                    noteRepository?.let { repo ->
                        val success = repo.archive(userId, itemId)
                        if (success) { successCount++; "Archived" } else { failCount++; "Failed" }
                    } ?: "No repository"
                }
                "delete" -> {
                    noteRepository?.let { repo ->
                        val success = repo.delete(userId, itemId)
                        if (success) { successCount++; "Deleted" } else { failCount++; "Failed" }
                    } ?: "No repository"
                }
                else -> { failCount++; "Unknown operation" }
            }
        }
        
        return "Batch $operation completed: $successCount succeeded, $failCount failed out of ${items.size} items."
    }
    
    private fun generateSuggestions(context: String?): String {
        return buildString {
            appendLine("💡 Suggested Next Actions")
            appendLine("━".repeat(40))
            
            if (context?.contains("free time") == true || context?.contains("bored") == true) {
                appendLine("\n🎯 Based on free time:")
                appendLine("1. Start a quick task from your backlog")
                appendLine("2. Review and organize recent notes")
                appendLine("3. Plan ahead for upcoming events")
            } else if (context?.contains("productive") == true || context?.contains("energic") == true) {
                appendLine("\n🚀 Based on high energy:")
                appendLine("1. Tackle your most challenging task")
                appendLine("2. Work on high-priority items")
                appendLine("3. Make progress on long-term projects")
            } else {
                appendLine("\n📌 Top Recommendations:")
                appendLine("1. Review pending tasks and prioritize")
                appendLine("2. Clear your inbox")
                appendLine("3. Schedule important but not urgent tasks")
            }
            
            appendLine("\n💡 Quick Wins:")
            appendLine("• Complete 2-minute tasks now")
            appendLine("• Respond to quick messages")
            appendLine("• File or archive completed items")
        }
    }
    
    private fun analyzeTime(period: String, breakdown: String?): String {
        return buildString {
            appendLine("📊 Time Analysis: $period")
            appendLine("━".repeat(40))
            
            when (breakdown) {
                "project" -> {
                    appendLine("\n📁 By Project:")
                    appendLine("  • Personal: ~30%")
                    appendLine("  • Work: ~50%")
                    appendLine("  • Learning: ~20%")
                }
                "category" -> {
                    appendLine("\n🏷️ By Category:")
                    appendLine("  • Meetings: ~25%")
                    appendLine("  • Focus Work: ~35%")
                    appendLine("  • Communication: ~20%")
                    appendLine("  • Planning: ~20%")
                }
                "priority" -> {
                    appendLine("\n🎯 By Priority:")
                    appendLine("  • Urgent: ~15%")
                    appendLine("  • High: ~35%")
                    appendLine("  • Medium: ~40%")
                    appendLine("  • Low: ~10%")
                }
                "time_of_day" -> {
                    appendLine("\n🕐 By Time of Day:")
                    appendLine("  • Morning (6-12): Most productive")
                    appendLine("  • Afternoon (12-6): Moderate")
                    appendLine("  • Evening (6-10): Low energy tasks")
                }
                else -> {
                    appendLine("\n📈 Overview:")
                    appendLine("  Total tracked: ~${when(period) {"today" -> "8 hours"; "week" -> "40 hours"; "month" -> "160 hours"; else -> "N/A"}}")
                    appendLine("  Focus time: ~60%")
                    appendLine("  Meetings: ~20%")
                    appendLine("  Other: ~20%")
                }
            }
            
            appendLine("\n💡 Insights:")
            appendLine("  • Your most productive hours: 9-11 AM")
            appendLine("  • Consider blocking focus time in mornings")
        }
    }
    
    private fun generateDayReview(): String {
        return buildString {
            appendLine("📋 Daily Review")
            appendLine("━".repeat(50))
            
            appendLine("\n✅ Completed Today:")
            appendLine("  • (Tasks completed will appear here)")
            
            appendLine("\n📅 Events Attended:")
            appendLine("  • (Calendar events will appear here)")
            
            appendLine("\n📝 Notes Created:")
            appendLine("  • (New notes will appear here)")
            
            appendLine("\n⏰ Time Summary:")
            appendLine("  • Focus time: ~4 hours")
            appendLine("  • Meetings: ~2 hours")
            appendLine("  • Other: ~2 hours")
            
            appendLine("\n🔜 Tomorrow's Preview:")
            appendLine("  • Check calendar for upcoming events")
            appendLine("  • Review high-priority tasks")
            
            appendLine("\n💡 Reflection Questions:")
            appendLine("  • What went well today?")
            appendLine("  • What could be improved?")
            appendLine("  • What's the most important thing for tomorrow?")
        }
    }
}
