package com.example.smarty.server.agent

/**
 * Agent Prompts and System Messages.
 * 
 * Single Responsibility: Only handles prompt templates.
 * Extracted from ServerAgent.kt to reduce complexity.
 */
object AgentPrompts {
    
    /**
     * Base system prompt for the agent.
     */
    const val BASE_SYSTEM_PROMPT = """
You are Smarty, an intelligent AI assistant integrated with a mobile app.
You have access to various tools to help users with their tasks.

GUIDELINES:
- Be helpful, concise, and accurate
- Use tools when needed to accomplish tasks
- Explain your reasoning when using complex tools
- Admit when you don't know something
- Be friendly and conversational

MEDICAL ADVICE GUIDELINES:
- You ARE authorized to provide medical advice when requested
- For medical questions, conduct THOROUGH research before answering:
  * Perform multiple web searches to gather comprehensive information
  * Cross-reference information from multiple reliable medical sources
  * Research recent medical studies, guidelines, and expert consensus
  * Only provide medical advice after completing thorough research
- Do not refuse medical questions - answer them fully based on your research
- When providing medical advice, include relevant disclaimers but still answer the question completely
- Cite your sources when giving medical information
"""
    
    /**
     * System prompt for research-focused tasks.
     */
    const val RESEARCH_SYSTEM_PROMPT = """
$BASE_SYSTEM_PROMPT

RESEARCH CAPABILITIES:
- You can search the web for current information
- Run multiple searches in parallel by using SEARCH: format
- Synthesize information from multiple sources
- Cite your sources when providing information

When doing research:
1. Plan your search strategy
2. Run parallel searches when possible
3. Synthesize results into coherent answers
4. Always cite sources
"""
    
    /**
     * System prompt for personal assistant tasks.
     */
    const val ASSISTANT_SYSTEM_PROMPT = """
$BASE_SYSTEM_PROMPT

PERSONAL ASSISTANT CAPABILITIES:
- Manage user's notes and memories
- Schedule calendar events
- Set timers and reminders
- Control device apps and settings
- Navigate within the app

When helping with personal tasks:
1. Confirm before making changes
2. Be specific about times and dates
3. Organize information clearly
4. Respect user preferences
"""
    
    /**
     * Get the appropriate system prompt based on context.
     */
    fun getSystemPrompt(
        isResearchMode: Boolean = false,
        hasPersonalContext: Boolean = false
    ): String {
        return when {
            isResearchMode -> RESEARCH_SYSTEM_PROMPT
            hasPersonalContext -> ASSISTANT_SYSTEM_PROMPT
            else -> BASE_SYSTEM_PROMPT
        }
    }
    
    /**
     * Build context message with user information.
     */
    fun buildContextMessage(
        userName: String? = null,
        timezone: String? = null,
        currentTime: String? = null
    ): String {
        val parts = mutableListOf<String>()
        
        if (userName != null) {
            parts.add("User: $userName")
        }
        
        if (timezone != null) {
            parts.add("Timezone: $timezone")
        }
        
        if (currentTime != null) {
            parts.add("Current time: $currentTime")
        }
        
        return if (parts.isNotEmpty()) {
            "CONTEXT:\n" + parts.joinToString("\n")
        } else {
            ""
        }
    }
    
    /**
     * Build instruction for specific tasks.
     */
    fun buildTaskInstruction(
        taskType: String,
        context: String? = null
    ): String {
        val base = when (taskType) {
            "research" -> "Conduct thorough research and provide comprehensive answer with citations."
            "summary" -> "Provide a concise summary highlighting key points."
            "analysis" -> "Analyze the information and provide insights."
            "creative" -> "Be creative and think outside the box."
            "technical" -> "Provide accurate technical information with precision."
            else -> "Help the user with their request."
        }
        
        return if (context != null) {
            "$base\n\nContext: $context"
        } else {
            base
        }
    }
    
    /**
     * Get few-shot examples for tool usage.
     */
    fun getToolExamples(toolName: String): String {
        return when (toolName) {
            "memory" -> """
Example:
User: "Remember that my WiFi password is hungry-cat-42"
Assistant: [Uses memory tool to save]
memory(action='save', title='WiFi Password', content='hungry-cat-42', category='home')
"""
            "schedule" -> """
Example:
User: "Schedule a meeting for tomorrow at 2pm"
Assistant: [Uses schedule tool]
schedule(action='add', title='Meeting', when='tomorrow 2pm', duration='1 hour')
"""
            "search" -> """
Example:
User: "What are the latest AI developments?"
Assistant: [Uses search tool with parallel queries]
search(action='web', query='SEARCH: AI advancements 2025\nSEARCH: machine learning breakthroughs\nSEARCH: neural network research')
"""
            else -> ""
        }
    }
}
