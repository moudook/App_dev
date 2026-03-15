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
    val memoryTool: ToolDefinition = ToolDefinition(
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
    )
    
    /**
     * Schedule tool for managing calendar events.
     */
    val scheduleTool: ToolDefinition = ToolDefinition(
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
    )
    
    /**
     * Remind tool for setting timers and alarms.
     */
    val remindTool: ToolDefinition = ToolDefinition(
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
    )
    
    /**
     * Device tool for controlling phone apps and settings.
     */
    val deviceTool: ToolDefinition = ToolDefinition(
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
    )
    
    /**
     * Search tool for web searches.
     */
    val searchTool: ToolDefinition = ToolDefinition(
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
    )
    
    /**
     * Navigate tool for app navigation and sharing.
     */
    val navigateTool: ToolDefinition = ToolDefinition(
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
    
    /**
     * Tool for generating images based on text prompts.
     */
    val generateImageTool: ToolDefinition = ToolDefinition(
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
        parameters = ToolParameters(
            properties = mapOf(
                "prompt" to ToolProperty("string", "Detailed description of the image to generate"),
                "aspect_ratio" to ToolProperty("string", "Aspect ratio: 1:1|16:9|9:16|4:3|3:4", enum = listOf("1:1", "16:9", "9:16", "4:3", "3:4"))
            ),
            required = listOf("prompt")
        )
    )
    
    /**
     * Get all standard tools.
     */
    fun getAllTools(): List<ToolDefinition> = listOf(
        memoryTool,
        scheduleTool,
        remindTool,
        deviceTool,
        searchTool,
        navigateTool,
        generateImageTool
    )
}
