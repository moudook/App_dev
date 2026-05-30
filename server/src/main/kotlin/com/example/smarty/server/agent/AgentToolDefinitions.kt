package com.example.smarty.server.agent

import com.example.smarty.server.llm.ToolDefinition
import com.example.smarty.server.llm.ToolParameters
import com.example.smarty.server.llm.ToolProperty

/**
 * Tool Definitions for ServerAgent.
 *
 * Single Responsibility: Only defines tool schemas.
 * Extracted from ServerAgent.kt to reduce complexity.
 */
object AgentToolDefinitions {
    /**
     * Memory tool for managing user's personal knowledge base.
     */
    val memoryTool: ToolDefinition =
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
            parameters =
                ToolParameters(
                    properties =
                        mapOf(
                            "action" to
                                ToolProperty(
                                    "string",
                                    "Action: save|find|update|delete|remember",
                                    enum = listOf("save", "find", "update", "delete", "remember"),
                                ),
                            "title" to ToolProperty("string", "Title for saved content (save action)"),
                            "content" to ToolProperty("string", "Content to save (save action)"),
                            "category" to ToolProperty("string", "Optional category (save/find actions)"),
                            "query" to ToolProperty("string", "Search query (find action)"),
                            "id" to ToolProperty("string", "Entry ID (update/delete actions)"),
                            "fact" to ToolProperty("string", "Fact to remember (remember action)"),
                            "type" to
                                ToolProperty(
                                    "string",
                                    "Fact type: preference|factual|episodic",
                                    enum = listOf("preference", "factual", "episodic"),
                                ),
                        ),
                    required = listOf("action"),
                ),
        )

    /**
     * Schedule tool for managing calendar events.
     */
    val scheduleTool: ToolDefinition =
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
            parameters =
                ToolParameters(
                    properties =
                        mapOf(
                            "action" to ToolProperty("string", "Action: add|list|remove", enum = listOf("add", "list", "remove")),
                            "title" to ToolProperty("string", "Event name (add action)"),
                            "when" to ToolProperty("string", "When: natural language like 'tomorrow 2pm', 'Friday', 'Dec 25'"),
                            "duration" to ToolProperty("string", "Duration: '1 hour', '30 min' (add action)"),
                            "description" to ToolProperty("string", "Extra details (add action)"),
                            "id" to ToolProperty("string", "Event ID (remove action)"),
                        ),
                    required = listOf("action"),
                ),
        )

    /**
     * Remind tool for setting timers and alarms.
     */
    val remindTool: ToolDefinition =
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
            parameters =
                ToolParameters(
                    properties =
                        mapOf(
                            "action" to ToolProperty("string", "Action: set|list|cancel", enum = listOf("set", "list", "cancel")),
                            "what" to ToolProperty("string", "What to remind about (set action)"),
                            "when" to ToolProperty("string", "When: 'in 10 min', 'at 7am', 'tomorrow 3pm'"),
                            "repeat" to ToolProperty("string", "Repeat: daily|weekdays|weekly|monthly (optional)"),
                            "id" to ToolProperty("string", "Reminder ID (cancel action)"),
                        ),
                    required = listOf("action"),
                ),
        )

    /**
     * Device tool for controlling phone apps and settings.
     */
    val deviceTool: ToolDefinition =
        ToolDefinition(
            name = "device",
            description = """Control phone - apps, media, settings, and device status.

ACTIONS:
- open: Launch app (app: name)
- media: Control playback (action: play|pause|stop|next|previous|volume_up|volume_down)
- toggle: Turn settings on/off (setting: wifi|bluetooth|flashlight|dnd|airplane, on: true|false)
- status: Get device info (info: battery|all)
- capture: Take screenshot (no args)

EXAMPLES:
- device(action='open', app='spotify')
- device(action='media', actionType='play')
- device(action='toggle', setting='wifi', on=true)
- device(action='status', info='battery')
- device(action='capture')

Use for: opening apps, media control, settings, device status.""",
            parameters =
                ToolParameters(
                    properties =
                        mapOf(
                            "action" to
                                ToolProperty(
                                    "string",
                                    "Action: open|media|toggle|status|capture",
                                    enum = listOf("open", "media", "toggle", "status", "capture"),
                                ),
                            "app" to ToolProperty("string", "App name to open (open action)"),
                            "actionType" to
                                ToolProperty(
                                    "string",
                                    "Media action: play|pause|stop|next|previous|volume_up|volume_down",
                                    enum = listOf("play", "pause", "resume", "stop", "next", "previous", "volume_up", "volume_down"),
                                ),
                            "setting" to
                                ToolProperty(
                                    "string",
                                    "Setting: wifi|bluetooth|flashlight|dnd|airplane",
                                    enum = listOf("wifi", "bluetooth", "flashlight", "dnd", "airplane"),
                                ),
                            "on" to ToolProperty("boolean", "true=ON, false=OFF (toggle action)"),
                            "info" to ToolProperty("string", "Info type: battery|all", enum = listOf("battery", "all")),
                        ),
                    required = listOf("action"),
                ),
        )

    /**
     * Tavily Search tool for real-time web search.
     * Uses the Tavily Search API to find current and accurate information.
     */
    val tavilySearchTool: ToolDefinition =
        ToolDefinition(
            name = "tavily_search",
            description = """Search the internet for real-time information using the Tavily API.
Use for: web searches, weather, news, facts, current events, research, or anything requiring up-to-date internet access.""",
            parameters =
                ToolParameters(
                    properties =
                        mapOf(
                            "query" to ToolProperty("string", "What to search for"),
                            "search_depth" to
                                ToolProperty(
                                    "string",
                                    "Search depth: basic or advanced (default: basic)",
                                    enum = listOf("basic", "advanced"),
                                ),
                            "max_results" to ToolProperty("number", "Maximum number of results to return (default: 5)"),
                        ),
                    required = listOf("query"),
                ),
        )

    /**
     * Navigate tool for app navigation and sharing.
     */
    val navigateTool: ToolDefinition =
        ToolDefinition(
            name = "navigate",
            description = """Navigate within app or share content externally.

ACTIONS:
- go: Navigate to screen (screen: home|calendar|stacks|archive|settings|guided_breathing|chess|coin_toss|tic_tac_toe)
- share: Share content via other apps (content, title optional)

EXAMPLES:
- navigate(action='go', screen='calendar')
- navigate(action='share', content='Check this out!', title='Interesting')

Use for: screen navigation, sharing to other apps.""",
            parameters =
                ToolParameters(
                    properties =
                        mapOf(
                            "action" to ToolProperty("string", "Action: go|share", enum = listOf("go", "share")),
                            "screen" to
                                ToolProperty(
                                    "string",
                                    "Screen: home|calendar|stacks|archive|settings|guided_breathing|chess|coin_toss|tic_tac_toe",
                                    enum =
                                        listOf(
                                            "home",
                                            "calendar",
                                            "stacks",
                                            "archive",
                                            "settings",
                                            "guided_breathing",
                                            "chess",
                                            "coin_toss",
                                            "tic_tac_toe",
                                        ),
                                ),
                            "content" to ToolProperty("string", "Content to share (share action)"),
                            "title" to ToolProperty("string", "Share title (share action, optional)"),
                        ),
                    required = listOf("action"),
                ),
        )

    /**
     * Tool for generating images based on text prompts.
     */
    val generateImageTool: ToolDefinition =
        ToolDefinition(
            name = "generate_image",
            description = """Act as a Master Art Director to generate high-quality images using Krea AI.
            When the user asks for an image, imagine the scene, understand the story, and visualize the composition.
            Create a highly detailed, professional prompt specifying camera angles, lighting, realistic textures, and specific aesthetics to achieve a hyper-realistic, high-fashion, or candid look.
            
ACTIONS:
- generate: Create a new image (prompt, aspect_ratio optional)

ASPECT RATIOS:
- 1:1 (Square - default)
- 16:9 (Landscape)
- 9:16 (Portrait)
- 4:3
- 3:4

EXAMPLES:
- generate_image(prompt='A spontaneously captured iPhone-styled candid photo of a young woman with platinum hair casually lounging against a textured, slightly weathered Parisian stone wall... Soft shadows and delicate highlights reveal the delicate fabric fibers, realistic leather grain, and glass transparency...', aspect_ratio='16:9')
- generate_image(prompt='In a smoky late-70s diner bathed in glowing red neon, a Latino man lounges nonchalantly across a vinyl booth, his long wavy hair flowing casually over the gleam of a classic handlebar mustache. His mustard-hued leather jacket... —late-70s / early-80s cinematic photograph, authentic film grain.', aspect_ratio='16:9')
- generate_image(prompt='Caught in the cracked reflection of an old bedroom mirror, the freckled redhead girl leans in close, carefully applying a glossy lip gloss that gleams under the soft direct flash. Her velour pink tracksuit top is sprinkled with subtle rhinestone details... —casual candid early-2000s Y2K snapshot, grainy low-res softness', aspect_ratio='9:16')

Use for: generating images, creating artwork, visualizing scenes, drawing, painting.""",
            parameters =
                ToolParameters(
                    properties =
                        mapOf(
                            "prompt" to ToolProperty("string", "Detailed description of the image to generate"),
                            "aspect_ratio" to
                                ToolProperty(
                                    "string",
                                    "Aspect ratio: 1:1|16:9|9:16|4:3|3:4",
                                    enum = listOf("1:1", "16:9", "9:16", "4:3", "3:4"),
                                ),
                        ),
                    required = listOf("prompt"),
                ),
        )

    val askUserTool: ToolDefinition =
        ToolDefinition(
            name = "ask_user",
            description = """Ask the user one or more structured questions with multiple choice options.

UI BEHAVIOR:
When you call this tool, the user's input block transforms into an interactive question UI.
The question and options appear inside the input pill — no separate card.

RULES:
- Provide 1 or more questions in the 'questions' array.
- For each question, provide 2-5 clear, distinct, non-overlapping options.
- Keep questions concise (1 sentence).
- Set allow_custom=true ONLY if you want to allow free-text input alongside the options.
  By default allow_custom is false — the user can only pick from your options.
  Set it to true when the options might not cover what the user wants to say.

EXAMPLES:
- ask_user(questions=[{"question": "What type of notes do you want to create?", "options": ["Meeting notes", "Daily journal", "Research notes"], "allow_custom": false}])
- ask_user(questions=[{"question": "Which time works best?", "options": ["Morning", "Afternoon"], "allow_custom": false}, {"question": "How long should the session be?", "options": ["30 mins", "1 hour"], "allow_custom": true}])

Use for: clarification, preference gathering, scoping research, multi-choice decisions.""",
            parameters =
                ToolParameters(
                    properties =
                        mapOf(
                            "questions" to
                                ToolProperty(
                                    type = "array",
                                    description = "List of questions to ask sequentially.",
                                    items =
                                        ToolProperty(
                                            type = "object",
                                            properties =
                                                mapOf(
                                                    "question" to ToolProperty("string", "The question to ask the user"),
                                                    "options" to
                                                        ToolProperty(
                                                            "array",
                                                            "List of 2-5 multiple choice options",
                                                            items = ToolProperty("string"),
                                                        ),
                                                    "allow_custom" to
                                                        ToolProperty("boolean", "Set true to allow free-text input. Default: false. Only set true when options may not cover the user's needs."),
                                                ),
                                            required = listOf("question", "options"),
                                        ),
                                ),
                        ),
                    required = listOf("questions"),
                ),
        )

    /**
     * Get note by ID tool for embedding clickable note cards in AI responses.
     * Sends a NoteBlock event to the client which renders interactive note cards.
     * The note card can be tapped to open full note details in a bottom sheet.
     */
    val getNoteByIdTool: ToolDefinition =
        ToolDefinition(
            name = "get_note_by_id",
            description = """Retrieve a specific note by its ID to embed as a clickable card in your response.

Use this when you want to reference a specific note in your response. The client will 
display an interactive note card that users can tap to view full note details.

RULES:
- Only use for notes that exist and belong to the user
- Provide a brief snippet/summary that helps users understand the note content
- Use for relevant, helpful references - don't overuse

EXAMPLES:
- get_note_by_id(note_id='abc-123', snippet='Meeting notes from the team standup')
- get_note_by_id(note_id='xyz-456', snippet='Recipe for homemade pasta sauce')

Use for: referencing specific notes, showing relevant saved information, citing user data.""",
            parameters =
                ToolParameters(
                    properties =
                        mapOf(
                            "note_id" to ToolProperty("string", "The unique ID of the note to retrieve"),
                            "snippet" to ToolProperty("string", "Brief summary or excerpt from the note (1-2 sentences)"),
                        ),
                    required = listOf("note_id", "snippet"),
                ),
        )

    /**
     * Search chat history tool for finding previous conversations.
     * Allows the agent to search through the user's entire chat history.
     */
    val searchHistoryTool: ToolDefinition =
        ToolDefinition(
            name = "search_history",
            description = """Search through the user's chat history and previous conversations.

Use this when you need to:
- Find previous discussions or context from earlier chats
- Look up something the user mentioned in a different conversation
- Reference past decisions or information
- Find notes, facts, or details the user previously shared

EXAMPLES:
- search_history(query='project deadline')
- search_history(query='meeting notes from last week')
- search_history(query='password')
- search_history(query='doctor appointment')

This tool searches across ALL chat sessions, not just the current one.
Results are ranked by relevance.

Use for: finding past context, recalling previous discussions, looking up user history.""",
            parameters =
                ToolParameters(
                    properties =
                        mapOf(
                            "query" to ToolProperty("string", "Search query to find in chat history"),
                            "limit" to ToolProperty("number", "Maximum number of results to return (default: 10)"),
                        ),
                    required = listOf("query"),
                ),
        )

    /**
     * Guided breathing tool for starting a calming breathing exercise.
     */
    val guidedBreathingTool: ToolDefinition =
        ToolDefinition(
            name = "guided_breathing",
            description = """Start a guided breathing exercise for the user.
Shows a calming breathing overlay in the chat with a 3-cycle exercise.
The user can dismiss it and continue chatting.

Use when: user asks to breathe, relax, calm down, meditate, or de-stress.""",
            parameters =
                ToolParameters(
                    properties = emptyMap(),
                    required = emptyList(),
                ),
        )

    /**
     * Get all standard tools.
     */
    fun getAllTools(): List<ToolDefinition> =
        listOf(
            memoryTool,
            scheduleTool,
            remindTool,
            deviceTool,
            navigateTool,
            generateImageTool,
            askUserTool,
            getNoteByIdTool,
            searchHistoryTool,
            tavilySearchTool,
            guidedBreathingTool,
        )
}
