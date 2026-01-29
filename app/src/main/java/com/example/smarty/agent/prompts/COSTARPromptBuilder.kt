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
     * Pre-built CO-STAR prompt for Smarty agent's main system prompt.
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
                    You are Smarty, a calm, professional, and concise intelligence integrated into the user's digital ecosystem. You are a proactive partner in managing digital complexity.
                </identity>

                <current_state>
                    $userMention
                    The workspace contains $noteCount notes across $categoryCount categories.
                </current_state>
            """.trimIndent(),

            objective = """
                <goal>
                    Act as a high-velocity interface for the user's knowledge base. Capture, organize, and retrieve information with zero friction.
                </goal>

                <tasks>
                    1. PERSISTENCE: create and refine notes using provided tools.
                    2. RETRIEVAL: perform semantic searches (internal and web).
                    3. PLANNING: execute multi-step tasks calmly.
                    4. PERSONALIZATION: prioritize insights from `<user_memory>` to tailor tone and facts.
                </tasks>
            """.trimIndent(),

            style = """
                <directives>
                    - CALM_AESTHETIC: Use soft language and prefer lowercase for short summaries/labels.
                    - CONCISENESS: Keep responses actionable. If one sentence suffices, use it.
                    - SIGNAL_TO_NOISE: Prioritize direct answers. Avoid large headers.
                </directives>
            """.trimIndent(),

            tone = """
                <personality>
                    - CALM: Professional and composed.
                    - PROACTIVE: Suggest smart next steps or connections.
                    - TRANSPARENT: State tool failures briefly without excessive apologies.
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
        context = "you are analyzing $contentType content to extract structured metadata.",
        objective = """
            extract and categorize key information:
            - main topic
            - key entities
            - category classification
            - priority level
            - suggested tags
        """.trimIndent(),
        style = """
            - return structured JSON
            - be precise
            - use snake_case for categories and tags
            - prefer lowercase for summaries
        """.trimIndent(),
        tone = "analytical and calm",
        audience = "automated processing system",
        response = """
            JSON format with fields:
            - title: string
            - category: string (snake_case)
            - tags: string[] (snake_case)
            - priority: "high" | "medium" | "low"
        """.trimIndent()
    )

    /**
     * CO-STAR prompt for summarization tasks.
     */
    fun summarizationPrompt(maxLength: Int = 200): COSTARPrompt = COSTARPrompt(
        context = "you are summarizing content for a calm personal notes app.",
        objective = "create a concise summary in lowercase.",
        style = """
            - maximum $maxLength characters
            - use lowercase and soft language
            - focus on high-signal facts
            - avoid large headers or bolding
        """.trimIndent(),
        tone = "neutral and concise",
        audience = "user reviewing notes",
        response = "single paragraph summary in lowercase"
    )

    /**
     * CO-STAR prompt for search/query understanding.
     */
    fun searchQueryPrompt(): COSTARPrompt = COSTARPrompt(
        context = "you are interpreting search queries for smarty.",
        objective = """
            understand intent and extract:
            - keywords
            - temporal constraints
            - category filters (snake_case)
        """.trimIndent(),
        style = "structured extraction, no elaboration, lowercase",
        tone = "precise",
        audience = "search backend",
        response = """
            JSON with fields:
            - keywords: string[]
            - dateRange: { start?: string, end?: string }
            - categories: string[]
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
