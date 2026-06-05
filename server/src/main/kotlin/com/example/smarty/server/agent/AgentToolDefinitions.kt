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
            description = "Manage user's personal knowledge base. Actions: save, find, update, delete, remember.",
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
            description = "Manage calendar events. Actions: add, list, remove.",
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

    /**
     * Device tool for controlling phone apps and settings.
     */
    val deviceTool: ToolDefinition =
        ToolDefinition(
            name = "device",
            description = "Control phone. Actions: open, media, toggle, status, capture. If hardware fails, explicitly relay failure reason to user.",
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
     * Navigate tool for app navigation and sharing.
     */
    val navigateTool: ToolDefinition =
        ToolDefinition(
            name = "navigate",
            description = "Navigate within app (screens: home|calendar|stacks|archive|settings|guided_breathing|chess|coin_toss|tic_tac_toe) or share externally.",
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
            description = "Ask user multi-choice questions. Rules: 1+ questions, 2-5 distinct options per question. Concise. set allow_custom=true to allow free-text input.",
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
                                                        ToolProperty(
                                                            "boolean",
                                                            "Set true to allow free-text input. Default: false. Only set true when options may not cover the user's needs.",
                                                        ),
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
            description = "Retrieve specific note by ID. Embeds interactive card. Needs note_id and short snippet.",
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
            description = "Search user's chat history. Helpful to recall past context.",
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
            description = "Start calming guided breathing exercise overlay in UI.",
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
            guidedBreathingTool,
        )
}
