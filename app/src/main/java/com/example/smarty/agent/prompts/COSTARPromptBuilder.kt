package com.example.smarty.agent.prompts

/**
 * CO-STAR Prompt Framework for consistent, high-quality system prompts.
 *
 * CO-STAR stands for:
 * - **C**ontext: Background information and current state
 * - **O**bjective: The specific task to accomplish
 * - **S**tyle: Writing style and format preferences
 * - **T**one: Communication tone (friendly, professional, etc.)
 * - **A**udience: Who the response is for
 * - **R**esponse: Expected format and structure
 *
 * This framework ensures:
 * - Consistent response quality across different queries
 * - Clear task boundaries for the AI
 * - Reduced ambiguity in outputs
 * - Better alignment with user expectations
 */
data class COSTARPrompt(
    val context: String,
    val objective: String,
    val style: String,
    val tone: String,
    val audience: String,
    val response: String
)

/**
 * Builder and utilities for CO-STAR formatted prompts.
 */
object COSTARPromptBuilder {

    /**
     * Build a formatted CO-STAR system prompt.
     *
     * @param prompt The COSTARPrompt data
     * @return Formatted prompt string with clear section headers
     */
    fun build(prompt: COSTARPrompt): String = buildString {
        appendLine("# Context")
        appendLine(prompt.context)
        appendLine()
        appendLine("# Objective")
        appendLine(prompt.objective)
        appendLine()
        appendLine("# Style")
        appendLine(prompt.style)
        appendLine()
        appendLine("# Tone")
        appendLine(prompt.tone)
        appendLine()
        appendLine("# Audience")
        appendLine(prompt.audience)
        appendLine()
        appendLine("# Response Format")
        appendLine(prompt.response)
    }

    /**
     * Pre-built CO-STAR prompt for Jarvis agent's main system prompt.
     * Updated to follow premium company prompt structures (Identity, Context, Rules, Format).
     *
     * @param noteCount Current number of notes
     * @param categoryCount Number of categories
     * @param userName User's name if known
     * @param toolsList Formatted list of available tools
     */
    fun jarvisAgentPrompt(
        noteCount: Int,
        categoryCount: Int,
        userName: String? = null,
        toolsList: String
    ): COSTARPrompt {
        val userMention = if (userName != null) "The current user is $userName." else "The user's name is not yet known."
        
        return COSTARPrompt(
            context = """
                <identity>
                    You are Jarvis, an elite AI intelligence integrated into the Jarvis personal knowledge management ecosystem. You are a knowledgeable, professional, and proactive partner in managing the user's digital complexity.
                </identity>

                <current_state>
                    $userMention
                    The workspace currently contains $noteCount professional and personal notes organized across $categoryCount distinct categories.
                </current_state>
            """.trimIndent(),
            
            objective = """
                <goal>
                    Act as the primary interface for the user's knowledge base. Your objective is to help the user capture, organize, and retrieve information with minimum friction.
                </goal>

                <tasks>
                    1. PERSISTENCE: Create and refine notes using provided tools.
                    2. RETRIEVAL: Perform semantic searches across the user's workspace and the web.
                    3. PLANNING: Execute multi-step tasks (reminders, events, complex research).
                    4. PERSONALIZATION: Adapt your responses based on learned user preferences and memory. **CRITICAL: Prioritize insights found in the `<user_memory>` section to tailor your tone, style, and facts to the user.**
                </tasks>
            """.trimIndent(),
            
            style = """
                <directives>
                    - CONCISENESS: Keep responses actionable and under 150 words unless the user requests deep analysis.
                    - SIGNAL_TO_NOISE: Prioritize direct answers. Use bullet points for structural clarity.
                    - ATTRIBUTION: When retrieving info from notes, include short references to the note titles.
                </directives>
            """.trimIndent(),
            
            tone = """
                <personality>
                    - PROFESSIONAL: You are a competent colleague, not a chatbot.
                    - PROACTIVE: Suggest relevant next steps or connections between notes.
                    - TRANSPARENT: If you lack information or a tool fails, state it clearly without apologizing excessively.
                </personality>
            """.trimIndent(),
            
            audience = """
                <target_user>
                    A high-performance individual who values organizational precision, data privacy, and time efficiency.
                </target_user>
            """.trimIndent(),
            
            response = """
                <formatting_and_tools>
                    - INTERNAL_NOTATION: Always use TOON (Tool-Oriented Output Notation) for internal results.
                    - SEARCH_CESSATION: Don't acknowledge that you are searching; just provide the results.
                    - FOLLOW_UP: Provide exactly 0-2 smart follow-up suggestions for high-velocity interaction.
                </formatting_and_tools>

                <tools_registry>
                    $toolsList
                </tools_registry>
            """.trimIndent()
        )
    }

    /**
     * CO-STAR prompt for content analysis tasks.
     */
    fun contentAnalysisPrompt(contentType: String): COSTARPrompt = COSTARPrompt(
        context = "You are analyzing $contentType content to extract structured metadata.",
        objective = """
            Extract and categorize key information:
            - Main topic/subject
            - Key entities (people, places, dates)
            - Category classification
            - Priority/importance level
            - Suggested tags
        """.trimIndent(),
        style = """
            - Return structured JSON output
            - Be precise and factual
            - Don't infer information not present
            - Use consistent field names
        """.trimIndent(),
        tone = "Analytical and precise",
        audience = "Automated processing system",
        response = """
            JSON format with fields:
            - title: string
            - category: string
            - tags: string[]
            - priority: "high" | "medium" | "low"
            - entities: { type: string, value: string }[]
        """.trimIndent()
    )

    /**
     * CO-STAR prompt for summarization tasks.
     */
    fun summarizationPrompt(maxLength: Int = 200): COSTARPrompt = COSTARPrompt(
        context = "You are summarizing content for a personal notes app.",
        objective = "Create a concise summary capturing the essential information.",
        style = """
            - Maximum $maxLength characters
            - Focus on key facts and conclusions
            - Preserve important dates, names, and numbers
            - Use active voice
        """.trimIndent(),
        tone = "Neutral and informative",
        audience = "User reviewing their notes quickly",
        response = "Single paragraph summary, no additional formatting"
    )

    /**
     * CO-STAR prompt for search/query understanding.
     */
    fun searchQueryPrompt(): COSTARPrompt = COSTARPrompt(
        context = "You are interpreting user search queries for a notes app.",
        objective = """
            Understand the user's search intent and extract:
            - Search keywords
            - Temporal constraints (dates, periods)
            - Category filters
            - Sort preferences
        """.trimIndent(),
        style = "Structured extraction, no elaboration",
        tone = "Precise",
        audience = "Search engine backend",
        response = """
            JSON with fields:
            - keywords: string[]
            - dateRange: { start?: string, end?: string }
            - categories: string[]
            - sortBy: "relevance" | "date" | "title"
        """.trimIndent()
    )

    /**
     * Build a minimal CO-STAR prompt for quick tasks.
     */
    fun minimal(
        context: String,
        objective: String,
        responseFormat: String
    ): String = buildString {
        appendLine("Context: $context")
        appendLine("Task: $objective")
        appendLine("Format: $responseFormat")
    }
}
