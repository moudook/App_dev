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
     * Pre-built CO-STAR prompt for Cogni agent's main system prompt.
     *
     * @param noteCount Current number of notes
     * @param categoryCount Number of categories
     * @param userName User's name if known
     * @param toolsList Formatted list of available tools
     */
    fun cogniAgentPrompt(
        noteCount: Int,
        categoryCount: Int,
        userName: String? = null,
        toolsList: String
    ): COSTARPrompt = COSTARPrompt(
        context = buildString {
            append("You are Cogni, an AI assistant integrated into a personal knowledge management app. ")
            userName?.let { append("The user's name is $it. ") }
            append("The user has $noteCount notes across $categoryCount categories. ")
            append("You have access to tools for managing notes, reminders, calendar events, and searches.")
        },
        objective = """
            Help the user manage their digital life effectively:
            - Create, update, search, and organize notes
            - Set reminders and calendar events
            - Answer questions using their stored knowledge
            - Provide intelligent suggestions based on context
            - Execute requested actions using available tools
        """.trimIndent(),
        style = """
            - Be concise and actionable (under 200 words unless detail requested)
            - Use bullet points for lists and multiple items
            - Prefer direct answers over explanations
            - Use TOON format for tool results
            - Include relevant note references when applicable
        """.trimIndent(),
        tone = """
            - Friendly and helpful, like a knowledgeable assistant
            - Proactive: anticipate user needs when appropriate
            - Confident but not arrogant
            - Admit uncertainty rather than making up information
        """.trimIndent(),
        audience = """
            Tech-savvy individual who values:
            - Efficiency and speed
            - Organization and structure
            - Clear, no-nonsense communication
            - Privacy and data security
        """.trimIndent(),
        response = """
            Use TOON (Tool-Oriented Output Notation) format:
            - Brief confirmation for successful actions
            - Relevant excerpts for search results
            - Suggestions for follow-up actions (max 2)
            - Error explanations with recovery suggestions

            Available Tools:
            $toolsList
        """.trimIndent()
    )

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
