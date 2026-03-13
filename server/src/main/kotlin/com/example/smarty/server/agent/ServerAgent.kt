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
import java.time.Instant
import com.example.smarty.server.agent.ThinkingStorageManagerSingleton

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
        const val MAX_TOOL_CALLS = 100  // Allow extensive research with up to 100 tool calls
        const val MAX_ITERATIONS = 200 // Max LLM iterations for extensive research
    }

    private val tools = listOf(
        // ═══════════════════════════════════════════════════════════════════
        // GENERALIZED TOOLS FOR LONG-HORIZON TASKS
        // ═══════════════════════════════════════════════════════════════════

        ToolDefinition(
            name = "memory",
            description = """Manage user's personal knowledge base - notes, facts, and memories.

ACTIONS:
- save: Store new information (title, content, category optional)
- find: Search saved information (query, category optional)
- update: Modify existing entry (id, title/content optional)
- delete: Remove entry (id)
- remember: Store personal fact/preference (fact, type: preference|factual|episodic)

EXAMPLES:
- memory(action='save', title='WiFi', content='hungry-cat-42', category='home')
- memory(action='find', query='password')
- memory(action='remember', fact='User prefers dark mode', type='preference')

Use for: remembering, saving, searching, managing user's personal data.""",
            parameters = ToolParameters(
                properties = mapOf(
                    "action" to ToolProperty("string", "Action: save|find|update|delete|remember", enum = listOf("save", "find", "update", "delete", "remember")),
                    "title" to ToolProperty("string", "Title for saved content (save action)"),
                    "content" to ToolProperty("string", "Content to save (save action)"),
                    "category" to ToolProperty("string", "Optional category (save/find actions)"),
                    "query" to ToolProperty("string", "Search query (find action)"),
                    "id" to ToolProperty("string", "Entry ID (update/delete actions)"),
                    "fact" to ToolProperty("string", "Fact to remember (remember action)"),
                    "type" to ToolProperty("string", "Fact type: preference|factual|episodic", enum = listOf("preference", "factual", "episodic"))
                ),
                required = listOf("action")
            )
        ),

        ToolDefinition(
            name = "schedule",
            description = """Manage calendar events - add, list, or remove events.

ACTIONS:
- add: Create new event (title, when, duration optional, description optional)
- list: Show events (when: today|tomorrow|this week|next week)
- remove: Delete event (id)

EXAMPLES:
- schedule(action='add', title='Meeting', when='tomorrow 2pm', duration='1 hour')
- schedule(action='list', when='this week')
- schedule(action='remove', id='abc123')

Use for: scheduling, calendar, time management.""",
            parameters = ToolParameters(
                properties = mapOf(
                    "action" to ToolProperty("string", "Action: add|list|remove", enum = listOf("add", "list", "remove")),
                    "title" to ToolProperty("string", "Event name (add action)"),
                    "when" to ToolProperty("string", "When: natural language like 'tomorrow 2pm', 'Friday', 'Dec 25'"),
                    "duration" to ToolProperty("string", "Duration: '1 hour', '30 min' (add action)"),
                    "description" to ToolProperty("string", "Extra details (add action)"),
                    "id" to ToolProperty("string", "Event ID (remove action)")
                ),
                required = listOf("action")
            )
        ),

        ToolDefinition(
            name = "remind",
            description = """Set timers, alarms, and reminders.

ACTIONS:
- set: Create reminder (what, when, repeat optional)
- list: Show active reminders (no args)
- cancel: Remove reminder (id)

EXAMPLES:
- remind(action='set', what='Turn off stove', when='in 10 minutes')
- remind(action='set', what='Call mom', when='tomorrow 3pm')
- remind(action='set', what='Take vitamins', when='every day 8am', repeat='daily')

Use for: timers, alarms, recurring reminders.""",
            parameters = ToolParameters(
                properties = mapOf(
                    "action" to ToolProperty("string", "Action: set|list|cancel", enum = listOf("set", "list", "cancel")),
                    "what" to ToolProperty("string", "What to remind about (set action)"),
                    "when" to ToolProperty("string", "When: 'in 10 min', 'at 7am', 'tomorrow 3pm'"),
                    "repeat" to ToolProperty("string", "Repeat: daily|weekdays|weekly|monthly (optional)"),
                    "id" to ToolProperty("string", "Reminder ID (cancel action)")
                ),
                required = listOf("action")
            )
        ),

        ToolDefinition(
            name = "device",
            description = """Control phone - apps, media, settings, and device status.

ACTIONS:
- open: Launch app (app: name)
- media: Control playback (action: play|pause|stop|next|previous|volume_up|volume_down)
- toggle: Turn settings on/off (setting: wifi|bluetooth|flashlight|dnd|airplane, on: true|false)
- status: Get device info (info: battery|storage|network|all)
- capture: Take screenshot (no args)

EXAMPLES:
- device(action='open', app='spotify')
- device(action='media', actionType='play')
- device(action='toggle', setting='wifi', on=true)
- device(action='status', info='battery')
- device(action='capture')

Use for: opening apps, media control, settings, device status.""",
            parameters = ToolParameters(
                properties = mapOf(
                    "action" to ToolProperty("string", "Action: open|media|toggle|status|capture", enum = listOf("open", "media", "toggle", "status", "capture")),
                    "app" to ToolProperty("string", "App name to open (open action)"),
                    "actionType" to ToolProperty("string", "Media action: play|pause|stop|next|previous|volume_up|volume_down", enum = listOf("play", "pause", "resume", "stop", "next", "previous", "volume_up", "volume_down")),
                    "setting" to ToolProperty("string", "Setting: wifi|bluetooth|flashlight|dnd|airplane", enum = listOf("wifi", "bluetooth", "flashlight", "dnd", "airplane")),
                    "on" to ToolProperty("boolean", "true=ON, false=OFF (toggle action)"),
                    "info" to ToolProperty("string", "Info type: battery|storage|network|all", enum = listOf("battery", "storage", "network", "all"))
                ),
                required = listOf("action")
            )
        ),

        ToolDefinition(
            name = "search",
            description = """Search the internet for information.

ACTIONS:
- web: Search for anything (query)

PARALLEL SEARCH (RECOMMENDED):
To run MULTIPLE searches simultaneously, use this format in your query:
SEARCH: query 1
SEARCH: query 2
SEARCH: query 3

This executes all searches in PARALLEL and returns combined results.
Much faster than sequential searches for research tasks.

EXAMPLES:
- search(action='web', query='current weather in New York')
- search(action='web', query='SEARCH: AI advancements 2025\nSEARCH: machine learning breakthroughs\nSEARCH: neural network research')
- search(action='web', query='SEARCH: best productivity apps\nSEARCH: note-taking apps comparison')

Use for: web searches, weather, news, facts, current events, research.""",
            parameters = ToolParameters(
                properties = mapOf(
                    "action" to ToolProperty("string", "Action: web", enum = listOf("web")),
                    "query" to ToolProperty("string", "What to search for. For multiple parallel searches, use format:\nSEARCH: query 1\nSEARCH: query 2\nSEARCH: query 3")
                ),
                required = listOf("action", "query")
            )
        ),

        ToolDefinition(
            name = "navigate",
            description = """Navigate within app or share content externally.

ACTIONS:
- go: Navigate to screen (screen: home|calendar|stacks|archive|settings)
- share: Share content via other apps (content, title optional)

EXAMPLES:
- navigate(action='go', screen='calendar')
- navigate(action='share', content='Check this out!', title='Interesting')

Use for: screen navigation, sharing to other apps.""",
            parameters = ToolParameters(
                properties = mapOf(
                    "action" to ToolProperty("string", "Action: go|share", enum = listOf("go", "share")),
                    "screen" to ToolProperty("string", "Screen: home|calendar|stacks|archive|settings", enum = listOf("home", "calendar", "stacks", "archive", "settings")),
                    "content" to ToolProperty("string", "Content to share (share action)"),
                    "title" to ToolProperty("string", "Share title (share action, optional)")
                ),
                required = listOf("action")
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
        logger.info("Agent execution starting for query: $query (Session: $sessionId, User: $userId)")

        // Initialize Goal Memory Manager
        val goalMemoryManager = GoalMemoryManager(sessionId, query)
        goalMemoryManager.initializeWithGoal()

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
Your responses should be natural and conversational. For complex tasks, your LLM provider will handle reasoning internally. Just focus on providing clear, helpful, and engaging responses.

MATHEMATICAL FORMATTING:
When including mathematics, use LaTeX syntax:
- Inline math: ${'$'}E = mc^2${'$'} renders as inline equation
- Block math: $${'$'}E = mc^2$${'$'} renders as centered block equation
- Greek letters: ${'$'}\alpha${'$'}, ${'$'}\beta${'$'}, ${'$'}\gamma${'$'}
- Fractions: ${'$'}\frac{a}{b}${'$'}
- Sums/products: ${'$'}\sum_{i=1}^{n} x_i${'$'}, ${'$'}\prod_{i=1}^{n} x_i${'$'}
- Integrals: ${'$'}\int_{a}^{b} f(x) dx${'$'}
- Matrices: ${'$'}\begin{pmatrix} a & b \\ c & d \end{pmatrix}${'$'}
- Subscripts/superscripts: ${'$'}x_i^2${'$'}
- Square roots: ${'$'}\sqrt{x}${'$'} or ${'$'}\sqrt[n]{x}${'$'}
</output_format>
</think>

<final>
[What the user sees. This is your ONLY visible output - the polished final answer.]
</final>

RULES:
- ALWAYS include both tags in every response, no exceptions.
- <think> must contain genuine reasoning - minimum 1-2 lines, even for simple questions
- Users BENEFIT from seeing your thought process - it builds trust and understanding
- <final> is the ONLY thing rendered for the user. Make it direct and clean.
- NEVER write anything outside these two tags.

CHAIN BREAKING (CRITICAL):
- NEVER call the same tool more than 2 times with the same arguments
- If a tool call succeeds, do NOT call it again with the same parameters
- If a tool call fails twice, STOP and respond with what you have
- If you find yourself repeating actions, STOP and ask the user for clarification
- Each tool call should be UNIQUE and PURPOSEFUL — no redundant actions
- After saving a note/setting a timer/creating an event, you're DONE — don't repeat

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
<think>Empathize, keep it light.</think>

<final>One of those days, huh? Hope you can take it easy later. What's been draining you?</final>

User: "I just finished a big project at work"
<think>Celebrate with them.</think>

<final>That's a big deal — how are you feeling about it? Relief? Triumph? Ready to sleep for a week?</final>

User: "What do you think about pineapple on pizza?"
<think>Fun opinion question.</think>

<final>Honestly? It works. The **sweet-salty contrast** is underrated. But I get why people have strong feelings about it. Where do you stand?</final>

User: "Explain the quadratic formula"
<think>Standard math explanation with formula.</think>

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

PARALLEL SEARCH STRATEGY (RECOMMENDED FOR RESEARCH):
When researching a topic that requires multiple angles or sources:
1. Break the research into 2-5 focused sub-questions
2. Call search_web ONCE with all queries in this format:
   SEARCH: sub-question 1
   SEARCH: sub-question 2
   SEARCH: sub-question 3
3. All searches run in PARALLEL (much faster than sequential)
4. Synthesize the combined results into your answer

Example research workflow:
User: "Research AI advancements in 2025"
<think>
This needs multiple sources. I'll run parallel searches:
1. search_web(action='web', query='SEARCH: AI breakthroughs 2025\nSEARCH: machine learning advances 2025\nSEARCH: neural network research 2025')
2. Synthesize combined results
3. Present comprehensive answer with citations
</think>

<final>Running comprehensive research on AI advancements...</final>

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
| "search / look up / news / research" | search_web (use PARALLEL for multiple queries) |
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
${goalMemoryManager.getProgressContext()}
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
        
        // KOOG Optimization: LlmCache Check - only for action queries
        val isActionQuery = LlmCache.isActionQuery(query)
        val cacheKey = LlmCacheKey(messagesForAgent, tools, modelOverride, isActionQuery)
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

        // ═══════════════════════════════════════════════════════════════════
        // Thinking Section Storage - uses sessionId from runInternal parameter
        // ═══════════════════════════════════════════════════════════════════

        // Get thinking storage manager for this session
        val thinkingStorage = ThinkingStorageManagerSingleton.instance
        
        // State machine for <think> tag detection
        var inThinkingState = false
        var inFinalState = false
        
        var agentIteration = 0
        val maxAgentIterations = 50
        var lastFailedToolName: String? = null
        var consecutiveToolFailures = 0

        // Chain breaking: Track tool call patterns to detect loops
        val toolCallHistory = mutableListOf<Pair<String, String>>()
        val maxSameToolCalls = 3

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

                    // ═══════════════════════════════════════════════════════════
                    // REASONING CONTENT (from API reasoning_content field)
                    // ═══════════════════════════════════════════════════════════
                    if (!chunk.reasoning.isNullOrEmpty()) {
                        // Add to thinking storage
                        thinkingStorage.addReasoning(sessionId, chunk.reasoning)
                        
                        // Get current accumulated thinking for streaming UI
                        val currentThinking = thinkingStorage.getCompleteThinking(sessionId)
                        
                        // Emit thinking progress for UI
                        if (!isToolCallInProgress) {
                            emit(AgentEvent.Processing(
                                eventId = UUID.randomUUID().toString(),
                                timestamp = System.currentTimeMillis(),
                                content = "",
                                thinking = currentThinking
                            ))
                        }
                    }

                    // ═══════════════════════════════════════════════════════════
                    // CONTENT WITH <think> TAGS — fixed state machine
                    // ═══════════════════════════════════════════════════════════
                    if (!chunk.content.isNullOrEmpty()) {
                        val newContent = chunk.content

                        val hadThinkStart = newContent.contains("<think>") || newContent.contains("<thought>")
                        val hadThinkEnd   = newContent.contains("</think>") || newContent.contains("</thought>")
                        val hadFinalOpen  = newContent.contains("<final>")
                        val hadFinalClose = newContent.contains("</final>")

                        var cleanContent = ""
                        var thinkingPart = ""

                        when {
                            // Case 1: Chunk opens a thinking block
                            hadThinkStart -> {
                                inThinkingState = true
                                inFinalState = false
                                val parts = newContent.split(Regex("<(?:think|thought)>"), limit = 2)
                                cleanContent = parts.getOrElse(0) { "" }
                                val afterOpen = parts.getOrElse(1) { "" }
                                if (hadThinkEnd || hadFinalClose) {
                                    val endParts = afterOpen.split(Regex("</(?:think|thought|final)>|<final>"), limit = 2)
                                    thinkingPart = endParts.getOrElse(0) { "" }
                                    cleanContent += endParts.getOrElse(1) { "" }
                                    inThinkingState = false
                                    inFinalState = true
                                } else {
                                    thinkingPart = afterOpen
                                }
                            }
                            // Case 2: Inside thinking, chunk closes it
                            inThinkingState && (hadThinkEnd || hadFinalClose) -> {
                                val endParts = newContent.split(Regex("</(?:think|thought|final)>|<final>"), limit = 2)
                                thinkingPart = endParts.getOrElse(0) { "" }
                                cleanContent  = endParts.getOrElse(1) { "" }
                                inThinkingState = false
                                inFinalState = true
                            }
                            // Case 3: Pure reasoning mid-think
                            inThinkingState -> {
                                thinkingPart = newContent
                            }
                            // Case 4: Final answer chunk — either inFinalState already, or pre-think plain text
                            else -> {
                                cleanContent = newContent
                                    .replace(Regex("<(?:think|thought|final)>"), "")
                                    .replace(Regex("</(?:think|thought|final)>"), "")
                                if (hadFinalOpen) inFinalState = true
                            }
                        }

                        // Sanitize lingering tags
                        cleanContent = cleanContent
                            .replace(Regex("<(?:think|thought|final)>"), "")
                            .replace(Regex("</(?:think|thought|final)>"), "")
                        thinkingPart = thinkingPart
                            .replace(Regex("<(?:think|thought|final)>"), "")
                            .replace(Regex("</(?:think|thought|final)>"), "")

                        if (thinkingPart.isNotEmpty()) {
                            thinkingStorage.addReasoning(sessionId, thinkingPart)
                        }
                        if (cleanContent.isNotEmpty()) {
                            currentContent += cleanContent
                        }

                        if (!isToolCallInProgress) {
                            val currentThinking = thinkingStorage.getCompleteThinking(sessionId)
                            emit(AgentEvent.Processing(
                                eventId = UUID.randomUUID().toString(),
                                timestamp = System.currentTimeMillis(),
                                content = cleanContent,
                                thinking = currentThinking.takeIf { it.isNotEmpty() }
                            ))
                        }
                    }

                    // ═══════════════════════════════════════════════════════════
                    // TOOL CALL ACCUMULATION
                    // ═══════════════════════════════════════════════════════════
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
                    // CHAIN BREAKING: Detect repeated tool calls with EXACT SAME arguments
                    val argsHash = currentToolArgs.take(100).hashCode().toString() // Hash first 100 chars of args
                    
                    // Count how many times this exact tool+args combination was called
                    val sameCallCount = toolCallHistory.count { it.first == currentToolName && it.second == argsHash }
                    
                    // RESEARCH TOOLS (web search, tavily, etc.) - No blocking, allow unlimited different queries
                    val isResearchTool = currentToolName.lowercase().let {
                        it.contains("search") || it.contains("web") || it.contains("tavily") || 
                        it.contains("fetch") || it.contains("scrape") || it.contains("browser")
                    }
                    
                    // Allow research tools to have unlimited different queries for research purposes
                    // Only block if EXACT same query is repeated 3+ times
                    val shouldBlock = !isResearchTool && sameCallCount >= 3
                    
                    if (shouldBlock) {
                        logger.warn("TOOL BLOCKED: Tool $currentToolName called ${sameCallCount + 1} times with same query - informing AI")
                        emit(AgentEvent.ToolBlocked(
                            eventId = UUID.randomUUID().toString(),
                            timestamp = System.currentTimeMillis(),
                            toolName = currentToolName,
                            reason = "Same query repeated ${sameCallCount + 1} times. Try a different approach.",
                            code = "TOOL_BLOCKED_SAME_QUERY"
                        ))
                        // Return empty result so AI can try a different approach
                        return "I can't search for the same thing again. Let me try a different approach."
                    }

                    // Add to history AFTER checking (so we count current call too)
                    toolCallHistory.add(Pair(currentToolName, argsHash))
                    
                    toolCallCount++
                    if (toolCallCount > MAX_TOOL_CALLS) {
                        logger.warn("Tool call limit exceeded ($MAX_TOOL_CALLS) for user: $userId")
                        emit(AgentEvent.Error(
                            eventId = UUID.randomUUID().toString(),
                            timestamp = System.currentTimeMillis(),
                            message = "I've made too many actions in this session. Let me summarize what I've done.",
                            code = "TOOL_LIMIT_EXCEEDED"
                        ))
                        goalMemoryManager.markFailed("Tool limit exceeded: $toolCallCount calls")
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

                        // ═══════════════════════════════════════════════════════════
                        // RICH TOOL TRACING — stores query+result for UI Action Blocks
                        // ═══════════════════════════════════════════════════════════
                        val toolStatus = if (isToolError) "failed" else "completed"

                        // Extract a human-readable summary of what was sent to the tool
                        val inputSummary = extractInputSummary(currentToolName, currentToolArgs)

                        // Truncate result to keep the trace manageable (UI shows max ~800 chars)
                        val outputSummary = maskedToolResult.take(800)
                            .let { if (maskedToolResult.length > 800) "$it…" else it }

                        // For web search: extract individual query→result pairs
                        val searchPairs: List<Pair<String, String?>> =
                            if (isSearchTool(currentToolName)) {
                                val pairs = mutableListOf<Pair<String, String?>>()
                                if (maskedToolResult.contains("### Parallel Search Results")) {
                                    val queryBlocks = maskedToolResult.split("## Query: ")
                                    for (i in 1 until queryBlocks.size) { // skip index 0 which is header
                                        val block = queryBlocks[i]
                                        val newLineIdx = block.indexOf('\n')
                                        if (newLineIdx > 0) {
                                            val query = block.substring(0, newLineIdx).trim()
                                            // Take up to 1500 chars per result for the UI
                                            val resultStr = block.substring(newLineIdx + 1).trim()
                                            val result = resultStr.take(1500).let { if (resultStr.length > 1500) "$it…" else it }
                                            pairs.add(Pair(query, result))
                                        }
                                    }
                                }
                                if (pairs.isEmpty()) {
                                    pairs.add(Pair(inputSummary ?: currentToolArgs.take(300), outputSummary))
                                }
                                pairs
                            } else emptyList()

                        thinkingStorage.addToolCall(
                            sessionId = sessionId,
                            toolName = currentToolName,
                            status = toolStatus,
                            inputSummary = inputSummary,
                            outputSummary = outputSummary,
                            searchQueries = searchPairs
                        )
                        logger.info("Added rich tool call to thinking: $currentToolName ($toolStatus)")

                        // Emit rich ToolCall event so client can show it inside the Action Panel
                        emit(AgentEvent.ToolCall(
                            eventId = UUID.randomUUID().toString(),
                            timestamp = System.currentTimeMillis(),
                            toolName = currentToolName,
                            displayName = buildDisplayName(currentToolName, inputSummary),
                            status = if (isToolError) "failed" else "completed",
                            inputSummary = inputSummary,
                            outputSummary = outputSummary,
                            searchQueries = searchPairs.map { (q, r) ->
                                AgentEvent.SearchQueryResult(query = q, result = r)
                            }
                        ))

                        messagesForAgent += LlmMessage(
                            role = LlmMessage.Role.TOOL,
                            content = "[Tool Result for $currentToolName]: $maskedToolResult"
                        )

                        // Track progress in GoalMemoryManager
                        val stepDescription = "Executed $currentToolName"
                        if (isToolError) {
                            goalMemoryManager.addError("Tool $currentToolName failed: ${toolResult.take(200)}")
                        } else {
                            goalMemoryManager.markStepCompleted(
                                description = stepDescription,
                                toolUsed = currentToolName,
                                result = toolResult.take(500)
                            )
                        }

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

                        // Track error in GoalMemoryManager
                        goalMemoryManager.addError("Tool $currentToolName exception: ${e.message?.take(200)}")

                        persistenceManager.saveCheckpoint(sessionId, messagesForAgent, "error_$currentToolName")
                        continue
                    }
                } else if (currentContent.isNotEmpty()) {
                    LlmCache.put(cacheKey, currentContent, hadToolCalls = toolCallCount > 0)

                    // ═══════════════════════════════════════════════════════════
                    // FINAL THINKING EMSSION (COMPLETELY REWRITTEN)
                    // ═══════════════════════════════════════════════════════════
                    
                    // Finalize and get complete thinking (reasoning + all tool calls)
                    val finalThinking = thinkingStorage.finalizeAndGetThinking(sessionId)
                    
                    // Emit final thinking state
                    if (finalThinking.isNotEmpty()) {
                        emit(AgentEvent.Processing(
                            eventId = UUID.randomUUID().toString(),
                            timestamp = System.currentTimeMillis(),
                            content = "",
                            thinking = finalThinking
                        ))
                    }

                    // Emit result with final thinking
                    emit(AgentEvent.Result(
                        eventId = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        content = "",
                        thinking = finalThinking,
                        isFinal = true
                    ))
                    
                    // Clear thinking storage after emission
                    thinkingStorage.clear(sessionId)
                    
                    tracer.trace(AgentTraceEvent(
                        sessionId = sessionId,
                        stepType = AgentStepType.FINAL,
                        content = currentContent
                    ))
                    persistenceManager.clearCheckpoint(sessionId)

                    // Mark goal as completed
                    goalMemoryManager.markCompleted()

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

                // Mark goal as failed
                goalMemoryManager.markFailed(errorMsg)

                return ""
            }
        }

        // Max iterations reached
        logger.warn("Agent loop reached max iterations ($maxAgentIterations) for user: $userId")
        goalMemoryManager.markFailed("Max iterations reached: $maxAgentIterations")
        return "I completed several actions but reached my iteration limit."
    }

    /**
     * Execute a tool server-side and return the result string.
     * Server-side tools (notes, timers, events, search, context) execute directly on PostgreSQL.
     * Device-only tools (media, settings, launch, navigate, share) emit Command events as fire-and-forget.
     */
    private suspend fun executeTool(name: String, argsJson: String, history: List<LlmMessage>, clientTimezone: String? = null, clientTimeMillis: Long? = null): String {
        logger.info("Executing tool: $name with args: $argsJson")

        @Serializable
        data class UnifiedToolArgs(
            val action: String,
            val title: String? = null,
            val content: String? = null,
            val category: String? = null,
            val query: String? = null,
            val id: String? = null,
            val fact: String? = null,
            val type: String? = null,
            val `when`: String? = null,
            val duration: String? = null,
            val description: String? = null,
            val what: String? = null,
            val repeat: String? = null,
            val app: String? = null,
            val actionType: String? = null,
            val setting: String? = null,
            val on: Boolean? = null,
            val info: String? = null,
            val screen: String? = null
        )

        val args = try {
            json.decodeFromString<UnifiedToolArgs>(argsJson)
        } catch (e: Exception) {
            val firstJson = extractFirstJsonObject(argsJson)
            if (firstJson != null) {
                logger.warn("Malformed tool args (multiple JSON objects), using first: ${firstJson.take(100)}...")
                json.decodeFromString<UnifiedToolArgs>(firstJson)
            } else {
                throw e
            }
        }

        // Map old tool names to new unified tools
        val toolName = when (name) {
            "save_note", "create_note" -> "memory_save"
            "find_note", "search_notes" -> "memory_find"
            "edit_note", "update_note" -> "memory_update"
            "delete_note" -> "memory_delete"
            "remember_fact", "store_context" -> "memory_remember"
            "add_event", "schedule_event" -> "schedule_add"
            "show_events", "list_events" -> "schedule_list"
            "remove_event", "delete_event" -> "schedule_remove"
            "set_reminder" -> "remind_set"
            "open_app", "launch_app" -> "device_open"
            "control_music", "control_media" -> "device_media"
            "toggle_setting" -> "device_toggle"
            "get_device_info" -> "device_status"
            "take_screenshot" -> "device_capture"
            "search_web", "web_search" -> "search_web"
            "go_to_screen" -> "navigate_go"
            "share_content", "share" -> "navigate_share"
            else -> name
        }

        val result = try {
            when (toolName) {
                "memory_save" -> {
                    if (noteRepository != null && args.title != null && args.content != null) {
                        val noteId = noteRepository.create(userId, args.title, args.content, null)  // categoryId null - handled by Android
                        emitStateSync("note_created", """{"id":"$noteId","title":"${args.title}""")
                        "Saved: '${args.title}' (ID: $noteId)"
                    } else {
                        emitDeviceCommand(AgentCommand.AddNote(commandId = UUID.randomUUID().toString(), content = "${args.title}\n\n${args.content}", category = args.category))
                        "Saved to device: ${args.title}"
                    }
                }
                "memory_find" -> {
                    if (noteRepository != null && args.query != null) {
                        val results = noteRepository.search(userId, args.query)
                        if (results.isEmpty()) "No notes found for '${args.query}'."
                        else results.joinToString("\n") { "- [${it.id}] ${it.title}: ${it.content.take(80)}" }
                    } else {
                        emitDeviceCommand(AgentCommand.SearchNotes(commandId = UUID.randomUUID().toString(), query = args.query ?: "", category = args.category))
                        "Searching device for: ${args.query}"
                    }
                }
                "memory_update" -> {
                    if (noteRepository != null && args.id != null) {
                        noteRepository.update(userId, args.id, args.title, args.content, null)
                        emitStateSync("note_updated", """{"id":"${args.id}"}""")
                        "Updated note ${args.id}"
                    } else {
                        emitDeviceCommand(AgentCommand.UpdateNote(commandId = UUID.randomUUID().toString(), noteId = args.id ?: "", title = args.title, content = args.content))
                        "Update sent to device."
                    }
                }
                "memory_delete" -> {
                    if (noteRepository != null && args.id != null) {
                        noteRepository.delete(userId, args.id)
                        emitStateSync("note_deleted", """{"id":"${args.id}"}""")
                        "Deleted note ${args.id}"
                    } else {
                        emitDeviceCommand(AgentCommand.DeleteNote(commandId = UUID.randomUUID().toString(), noteId = args.id ?: ""))
                        "Delete sent to device."
                    }
                }
                "memory_remember" -> {
                    try {
                        val fact = args.fact ?: args.content ?: ""
                        vectorStore.store(userId, fact, mapOf("type" to (args.type ?: "factual")))
                        "Remembered: ${fact.take(50)}"
                    } catch (e: Exception) { "Failed: ${e.message}" }
                }
                else -> when (name) {
                    "memory" -> {
                when (args.action) {
                    "save" -> {
                        if (noteRepository != null && args.title != null && args.content != null) {
                            val noteId = noteRepository.create(userId, args.title, args.content, args.category)
                            emitStateSync("note_created", """{"id":"$noteId","title":"${args.title}"}""")
                            "Saved: '${args.title}' (ID: $noteId)"
                        } else {
                            emitDeviceCommand(AgentCommand.AddNote(commandId = UUID.randomUUID().toString(), content = "${args.title}\n\n${args.content}", category = args.category))
                            "Saved to device: ${args.title}"
                        }
                    }
                    "find" -> {
                        if (noteRepository != null && args.query != null) {
                            val results = noteRepository.search(userId, args.query)
                            if (results.isEmpty()) "No notes found for '${args.query}'."
                            else results.joinToString("\n") { "- [${it.id}] ${it.title}: ${it.content.take(80)}" }
                        } else {
                            emitDeviceCommand(AgentCommand.SearchNotes(commandId = UUID.randomUUID().toString(), query = args.query ?: "", category = args.category))
                            "Searching device for: ${args.query}"
                        }
                    }
                    "update" -> {
                        if (noteRepository != null && args.id != null) {
                            noteRepository.update(userId, args.id, args.title, args.content, null)
                            emitStateSync("note_updated", """{"id":"${args.id}"}""")
                            "Updated note ${args.id}"
                        } else {
                            emitDeviceCommand(AgentCommand.UpdateNote(commandId = UUID.randomUUID().toString(), noteId = args.id ?: "", title = args.title, content = args.content))
                            "Update sent to device."
                        }
                    }
                    "delete" -> {
                        if (noteRepository != null && args.id != null) {
                            noteRepository.delete(userId, args.id)
                            emitStateSync("note_deleted", """{"id":"${args.id}"}""")
                            "Deleted note ${args.id}"
                        } else {
                            emitDeviceCommand(AgentCommand.DeleteNote(commandId = UUID.randomUUID().toString(), noteId = args.id ?: ""))
                            "Delete sent to device."
                        }
                    }
                    "remember" -> {
                        try {
                            vectorStore.store(userId, args.fact ?: "", mapOf("type" to (args.type ?: "factual")))
                            "Remembered: ${args.fact?.take(50)}"
                        } catch (e: Exception) { "Failed: ${e.message}" }
                    }
                    else -> "Unknown memory action: ${args.action}"
                }
            }

            "schedule" -> {
                when (args.action) {
                    "add" -> {
                        val startTime = parseNaturalTime(args.`when` ?: "", clientTimezone, clientTimeMillis)
                        val durationMs = parseDurationToMs(args.duration ?: "1 hour")
                        val endTime = startTime + durationMs
                        if (calendarRepository != null && args.title != null) {
                            val eventId = calendarRepository.create(userId, args.title, startTime, endTime, args.description, 15)
                            emitStateSync("event_scheduled", """{"id":"$eventId","title":"${args.title}"}""")
                            "Event added: '${args.title}'"
                        } else {
                            emitDeviceCommand(AgentCommand.ScheduleEvent(commandId = UUID.randomUUID().toString(), title = args.title ?: "", startTime = startTime, endTime = endTime, description = args.description, reminderMinutes = 15))
                            "Event sent to device: ${args.title}"
                        }
                    }
                    "list" -> {
                        val (startMs, endMs) = parseTimeRange(args.`when` ?: "today", clientTimezone, clientTimeMillis)
                        if (calendarRepository != null) {
                            val events = calendarRepository.listUpcoming(userId).filter { it.startTime in startMs until endMs }
                            if (events.isEmpty()) "No events for ${args.`when`}."
                            else events.joinToString("\n") { "- [${it.id}] ${it.title}" }
                        } else {
                            emitDeviceCommand(AgentCommand.ListEvents(commandId = UUID.randomUUID().toString(), date = startMs))
                            "Requesting events from device."
                        }
                    }
                    "remove" -> {
                        if (calendarRepository != null && args.id != null) {
                            calendarRepository.delete(userId, args.id)
                            emitStateSync("event_deleted", """{"id":"${args.id}"}""")
                            "Event removed."
                        } else {
                            emitDeviceCommand(AgentCommand.DeleteEvent(commandId = UUID.randomUUID().toString(), eventId = args.id ?: ""))
                            "Remove request sent to device."
                        }
                    }
                    else -> "Unknown schedule action: ${args.action}"
                }
            }

            "remind" -> {
                when (args.action) {
                    "set" -> {
                        val whenStr = args.`when` ?: ""
                        val triggerTime = parseNaturalTime(whenStr, clientTimezone, clientTimeMillis)
                        val isAlarm = !whenStr.contains("in ") && !whenStr.contains("after ")
                        if (timerRepository != null && args.what != null) {
                            val timerId = timerRepository.create(userId, args.what, triggerAt = triggerTime, isAlarm = isAlarm)
                            emitStateSync("timer_set", """{"id":"$timerId"}""")
                            "${if (isAlarm) "Reminder" else "Timer"} set: '${args.what}'"
                        } else {
                            emitDeviceCommand(AgentCommand.SetTimer(commandId = UUID.randomUUID().toString(), name = args.what ?: "", timeStr = args.`when` ?: "", isAlarm = isAlarm))
                            "Reminder sent to device: ${args.what}"
                        }
                    }
                    "list" -> "Listing reminders..."
                    "cancel" -> {
                        if (timerRepository != null && args.id != null) {
                            timerRepository.delete(userId, args.id)
                            "Reminder cancelled."
                        } else "Cancel request sent to device."
                    }
                    else -> "Unknown remind action: ${args.action}"
                }
            }

            "device" -> {
                when (args.action) {
                    "open" -> {
                        val packageName = resolveAppPackage(args.app ?: "")
                        emitDeviceCommand(AgentCommand.LaunchApp(commandId = UUID.randomUUID().toString(), packageName = packageName))
                        "Opening: ${args.app}"
                    }
                    "media" -> {
                        emitDeviceCommand(AgentCommand.ControlAudio(commandId = UUID.randomUUID().toString(), action = args.actionType ?: "play"))
                        "Media: ${args.actionType}"
                    }
                    "toggle" -> {
                        emitDeviceCommand(AgentCommand.ToggleSetting(commandId = UUID.randomUUID().toString(), setting = args.setting ?: "", enable = args.on ?: false))
                        "${args.setting} ${if (args.on == true) "on" else "off"}"
                    }
                    "status" -> {
                        emitDeviceCommand(AgentCommand.GetDeviceInfo(commandId = UUID.randomUUID().toString(), infoType = args.info ?: "all"))
                        "Getting device ${args.info}..."
                    }
                    "capture" -> {
                        emitDeviceCommand(AgentCommand.TakeScreenshot(commandId = UUID.randomUUID().toString()))
                        "Capturing screenshot."
                    }
                    else -> "Unknown device action: ${args.action}"
                }
            }

            "search" -> {
                when (args.action) {
                    "web" -> {
                        val searchResult = tavilyTool.search(args.query ?: "")
                        if (searchResult.startsWith("Error")) "Search failed: $searchResult"
                        else searchResult
                    }
                    else -> "Unknown search action: ${args.action}"
                }
            }

            "navigate" -> {
                when (args.action) {
                    "go" -> {
                        emitDeviceCommand(AgentCommand.Navigate(commandId = UUID.randomUUID().toString(), screen = args.screen ?: "home"))
                        "Going to ${args.screen}."
                    }
                    "share" -> {
                        emitDeviceCommand(AgentCommand.Share(commandId = UUID.randomUUID().toString(), content = args.content ?: "", title = args.title))
                        "Sharing content."
                    }
                    else -> "Unknown navigate action: ${args.action}"
                }
            }

                    else -> "Unknown tool: $name"
                }
            }
        } catch (e: Exception) {
            "Error executing tool: ${e.message}"
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

    private fun extractFirstJsonObject(input: String): String? {
        var braceCount = 0
        var startIndex = -1
        for ((index, char) in input.withIndex()) {
            if (char == '{') {
                if (braceCount == 0) startIndex = index
                braceCount++
            } else if (char == '}') {
                braceCount--
                if (braceCount == 0 && startIndex >= 0) {
                    return input.substring(startIndex, index + 1)
                }
            }
        }
        return null
    }

    /** Returns true for tool names that perform web/internet searches. */
    private fun isSearchTool(toolName: String): Boolean =
        toolName.lowercase().let {
            it.contains("search") || it.contains("web") || it.contains("tavily") ||
            it.contains("fetch") || it.contains("scrape") || it.contains("browse")
        }

    /**
     * Extract a short human-readable description of the tool input.
     * For search tools this returns the query string.
     * For other tools it returns a trimmed representation of the key argument.
     */
    private fun extractInputSummary(toolName: String, argsJson: String): String? {
        return try {
            // Try to parse the "query" field first (used by search tools + find tools)
            val queryRegex = Regex(""""query"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"""")
            queryRegex.find(argsJson)?.groupValues?.get(1)?.let { return it }

            // For title-based tools (memory_save, schedule_add, etc.)
            val titleRegex = Regex(""""title"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"""")
            titleRegex.find(argsJson)?.groupValues?.get(1)?.let { return "\"$it\"" }

            // For "what" field (reminders)
            val whatRegex = Regex(""""what"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"""")
            whatRegex.find(argsJson)?.groupValues?.get(1)?.let { return it }

            // Fallback: take first 120 chars of args
            argsJson.take(120).let { if (argsJson.length > 120) "$it…" else it }
        } catch (e: Exception) {
            argsJson.take(120)
        }
    }

    /** Build a friendly display name for the action card header. */
    private fun buildDisplayName(toolName: String, inputSummary: String?): String {
        val base = when {
            toolName.contains("search", ignoreCase = true) ||
            toolName.contains("web", ignoreCase = true) ->
                if (inputSummary != null) "Searched: $inputSummary" else "Web Search"
            toolName.contains("memory", ignoreCase = true) ||
            toolName.contains("note", ignoreCase = true) ->
                if (inputSummary != null) "Saved: $inputSummary" else "Memory Action"
            toolName.contains("schedule", ignoreCase = true) ||
            toolName.contains("calendar", ignoreCase = true) ->
                if (inputSummary != null) "Scheduled: $inputSummary" else "Calendar Action"
            toolName.contains("remind", ignoreCase = true) ->
                if (inputSummary != null) "Reminder: $inputSummary" else "Reminder Set"
            toolName.contains("device", ignoreCase = true) ->
                "Device: ${toolName.substringAfter("_").replaceFirstChar { it.uppercase() }}"
            toolName.contains("navigate", ignoreCase = true) ->
                if (inputSummary != null) "Navigated to $inputSummary" else "Navigation"
            else -> toolName.replace("_", " ").replaceFirstChar { it.uppercase() }
        }
        return base.take(80)
    }
}
