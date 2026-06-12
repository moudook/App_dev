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
    val manageNotesTool: ToolDefinition =
        ToolDefinition(
            name = "manage_notes",
            description = "Manage user's personal knowledge base (RAG). Actions: save, find, update, delete.",
            parameters =
                ToolParameters(
                    properties =
                        mapOf(
                            "action" to
                                ToolProperty(
                                    "string",
                                    "Action: save|find|update|delete",
                                    enum = listOf("save", "find", "update", "delete"),
                                ),
                            "title" to ToolProperty("string", "Title for saved content (save action)"),
                            "content" to ToolProperty("string", "Content to save (save action)"),
                            "category" to ToolProperty("string", "Optional category (save/find actions)"),
                            "query" to ToolProperty("string", "Search query (find action)"),
                            "id" to ToolProperty("string", "Entry ID (update/delete actions)"),
                            "limit" to ToolProperty("number", "Maximum results (find action, default 20)"),
                        ),
                    required = listOf("action"),
                ),
        )

    val updateUserProfileTool: ToolDefinition =
        ToolDefinition(
            name = "update_user_profile",
            description = "Semantic Profile (Dispositional Memory). Stores abstract personality facts.",
            parameters =
                ToolParameters(
                    properties =
                        mapOf(
                            "action" to ToolProperty("string", "Action: remember|forget|list", enum = listOf("remember", "forget", "list")),
                            "fact" to ToolProperty("string", "Fact to remember (remember action)"),
                            "category" to
                                ToolProperty(
                                    "string",
                                    "Category: emotional|routine|preference|skill|relationship",
                                    enum = listOf("emotional", "routine", "preference", "skill", "relationship"),
                                ),
                            "emotional_significance" to ToolProperty("number", "1-5 scale of emotional significance"),
                        ),
                    required = listOf("action"),
                ),
        )

    val manageCalendarTool: ToolDefinition =
        ToolDefinition(
            name = "manage_calendar",
            description = "Manage calendar events (Duration-Based). Actions: add, list, remove.",
            parameters =
                ToolParameters(
                    properties =
                        mapOf(
                            "action" to ToolProperty("string", "Action: add|list|remove", enum = listOf("add", "list", "remove")),
                            "title" to ToolProperty("string", "Event name (add action)"),
                            "when" to
                                ToolProperty("string", "When: natural language like 'tomorrow 2pm', 'Friday', 'Dec 25' (add/list action)"),
                            "duration" to ToolProperty("string", "Duration: '1 hour', '30 min' (add action)"),
                            "description" to ToolProperty("string", "Extra details (add action)"),
                            "id" to ToolProperty("string", "Event ID (remove action)"),
                        ),
                    required = listOf("action"),
                ),
        )

    val setTimerAlarmTool: ToolDefinition =
        ToolDefinition(
            name = "set_timer_alarm",
            description = "Set timers, alarms, and reminders. Actions: set, list, cancel.",
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

    val launchUiTool: ToolDefinition =
        ToolDefinition(
            name = "launch_ui",
            description = "Unified Intent Router. Open internal screens or external apps.",
            parameters =
                ToolParameters(
                    properties =
                        mapOf(
                            "intent" to
                                ToolProperty(
                                    "string",
                                    "Intent name: home|calendar|stacks|archive|settings|guided_breathing|chess|coin_toss|tic_tac_toe or an external app name like 'spotify'",
                                ),
                        ),
                    required = listOf("intent"),
                ),
        )

    val shareContentTool: ToolDefinition =
        ToolDefinition(
            name = "share_content",
            description = "Triggers the Android system share sheet with specified content.",
            parameters =
                ToolParameters(
                    properties =
                        mapOf(
                            "content" to ToolProperty("string", "Content to share"),
                            "title" to ToolProperty("string", "Share title (optional)"),
                        ),
                    required = listOf("content"),
                ),
        )

    val webSearchTool: ToolDefinition =
        ToolDefinition(
            name = "web_search",
            description = "Information Retrieval via Tavily API.",
            parameters =
                ToolParameters(
                    properties =
                        mapOf(
                            "queries" to ToolProperty("array", "List of search queries (max 3)", items = ToolProperty("string")),
                        ),
                    required = listOf("queries"),
                ),
        )

    val codeInterpreterTool: ToolDefinition =
        ToolDefinition(
            name = "code_interpreter",
            description = "Sandboxed Code Execution (QuickJS). Run JS for math or logic.",
            parameters =
                ToolParameters(
                    properties =
                        mapOf(
                            "code" to ToolProperty("string", "JavaScript code to execute"),
                            "language" to ToolProperty("string", "Programming language (default: javascript)"),
                        ),
                    required = listOf("code"),
                ),
        )

    val scratchpadTool: ToolDefinition =
        ToolDefinition(
            name = "scratchpad",
            description = "Iterative Working Memory. For deeply complex logic, coding tasks, or heavy deep research.",
            parameters =
                ToolParameters(
                    properties =
                        mapOf(
                            "content" to ToolProperty("string", "Scratchpad content"),
                            "iteration" to ToolProperty("number", "Iteration number (1-10)"),
                        ),
                    required = listOf("content", "iteration"),
                ),
        )

    val generateImageTool: ToolDefinition =
        ToolDefinition(
            name = "generate_image",
            description = "Generate images using Krea AI. Provide highly detailed prompts with camera angles, lighting, styles. Aspect ratios: 1:1, 16:9, 9:16, 4:3, 3:4.",
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
            description =
                "PAUSE EXECUTION AND ASK THE USER. Use this tool ONLY when you need clarification, preferences, or input from the user before continuing. " +
                "This tool will stop the agent, show a UI to the user, wait for their answer, and then resume execution with their response. " +
                "Do NOT just talk about asking - CALL THIS TOOL when you need user input.",
            parameters =
                ToolParameters(
                    properties =
                        mapOf(
                            "questions" to
                                ToolProperty(
                                    type = "array",
                                    description = "Array of questions to ask the user. Each question must have a 'question' string and an 'options' array of 2-5 choices. Example: {\"questions\": [{\"question\": \"What do you want?\", \"options\": [\"Option A\", \"Option B\"]}]}",
                                    items =
                                        ToolProperty(
                                            type = "object",
                                            properties =
                                                mapOf(
                                                    "question" to ToolProperty("string", "The exact question text to show the user"),
                                                    "options" to
                                                        ToolProperty(
                                                            "array",
                                                            "Array of 2-5 answer choices the user can tap",
                                                            items = ToolProperty("string"),
                                                        ),
                                                    "allow_custom" to ToolProperty("boolean", "Whether the user can type a custom answer (default false)"),
                                                ),
                                            required = listOf("question", "options"),
                                        ),
                                ),
                        ),
                    required = listOf("questions"),
                ),
        )

    val searchPastChatsTool: ToolDefinition =
        ToolDefinition(
            name = "search_past_chats",
            description = "Episodic Chat History Search. Search user's raw conversation history.",
            parameters =
                ToolParameters(
                    properties =
                        mapOf(
                            "query" to ToolProperty("string", "Search query"),
                            "limit" to ToolProperty("number", "Maximum results (default: 10)"),
                        ),
                    required = listOf("query"),
                ),
        )

    fun getAllTools(): List<ToolDefinition> =
        listOf(
            manageNotesTool,
            updateUserProfileTool,
            manageCalendarTool,
            setTimerAlarmTool,
            launchUiTool,
            shareContentTool,
            webSearchTool,
            codeInterpreterTool,
            scratchpadTool,
            generateImageTool,
            askUserTool,
            searchPastChatsTool,
        )
}
