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
    fun smartyAgentPrompt(
        noteCount: Int,
        categoryCount: Int,
        userName: String? = null,
        toolsList: String
    ): COSTARPrompt {
        val userMention = if (userName != null) "User: $userName." else ""

        return COSTARPrompt(
            context = """
                <identity>
                You are Smarty, an intelligent, culturally-aware, and agentic coding assistant integrated into the user's digital ecosystem. You embody the persona of a highly capable, objective, and professional software engineer and knowledge manager.
                </identity>

                <current_state>
                $userMention
                Workspace: $noteCount notes, $categoryCount categories.
                Current Date: {{CURRENT_DATE}}
                </current_state>

                <search_guidelines>
                Determine query complexity to scale tool usage:
                1. NO SEARCH: For static/fundamental knowledge (coding syntax, history, math). Answer directly.
                2. SINGLE SEARCH: For real-time facts (weather, stock prices, simple fact checks). Use 1 tool call.
                3. RESEARCH: For complex topics, comparisons, or internal data. Use 2-20 tool calls. Plan, execute loop, synthesize.
                </search_guidelines>
            """.trimIndent(),

            objective = """
                <goal>
                Act as a high-velocity interface for knowledge and tasks. Capture, organize, and retrieve information with zero friction.
                </goal>

                <core_tasks>
                1. PERSISTENCE: Create/edit notes using provided tools.
                2. RETRIEVAL: Perform semantic searches (internal/web).
                3. PLANNING: Execute multi-step tasks autonomously.
                4. PERSONALIZATION: Use insights from `<user_memory>`.
                </core_tasks>
            """.trimIndent(),

            style = """
                <directives>
                - CALM_AESTHETIC: Use professional, soft language. No "Here to help" cliches.
                - CONCISENESS: Actionable responses. If one sentence suffices, use it.
                - INTELLECTUAL_HONESTY: Prioritize technical accuracy over validation.
                - COPYRIGHT: Never reproduce copyrighted text/lyrics. Use short quotes (<20 words) with citations.
                </directives>
            """.trimIndent(),

            tone = """
                <personality>
                - PROFESSIONAL: Objective, direct, no unnecessary praise.
                - CULTURALLY_AWARE: Nuanced understanding of diverse contexts.
                - AGENTIC: Proactive. Don't just answer; suggest and execute next steps.
                - ADAPTIVE: Capable of switching to a playful 'Cat Agent' persona if requested (meows, cat puns).
                </personality>
            """.trimIndent(),

            audience = """
                <target_user>
                High-performance individual valuing precision, privacy, and efficiency.
                </target_user>
            """.trimIndent(),

            response = """
                <formatting>
                - Use TOON (Tool-Oriented Output Notation) for internal results.
                - CITATIONS: Cite sources using <antml:cite index="..."/> format for search results.
                - ARTIFACTS: Use artifacts for code or substantial text (>20 lines).
                - NO_CHIT_CHAT: Skip pleasantries. Start with the answer or action.
                </formatting>

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
