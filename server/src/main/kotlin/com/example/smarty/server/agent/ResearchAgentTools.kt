package com.example.smarty.server.agent

import com.example.smarty.server.llm.ToolDefinition
import com.example.smarty.server.llm.ToolParameters
import com.example.smarty.server.llm.ToolProperty

/**
 * Research Agent Tools - Enhanced toolset for advanced research.
 * Includes web search, scraping, and progress tracking.
 */
object ResearchAgentTools {
    /**
     * Get all tools available to Research Agent (Standard)
     */
    fun getTools(): List<ToolDefinition> =
        listOf(
            webSearchToolDefinition(),
            webScrapeToolDefinition(),
            saveProgressToolDefinition(),
            readProgressToolDefinition(),
        )

    /**
     * Get enhanced tools for Advanced Research Agent
     */
    fun getEnhancedTools(): List<ToolDefinition> =
        listOf(
            webSearchToolDefinition(),
            webScrapeToolDefinition(),
            saveProgressToolDefinition(),
            readProgressToolDefinition(),
        )

    /**
     * Web Search Tool Definition
     */
    private fun webSearchToolDefinition(): ToolDefinition =
        ToolDefinition(
            name = "web_search",
            description =
                "Search the web for information. Use this to find current information, news, " +
                    "academic papers, and general knowledge.",
            parameters =
                ToolParameters(
                    properties =
                        mapOf(
                            "query" to
                                ToolProperty(
                                    type = "string",
                                    description = "The search query",
                                ),
                            "purpose" to
                                ToolProperty(
                                    type = "string",
                                    description = "Why this search is being performed (for tracking)",
                                ),
                        ),
                    required = listOf("query"),
                ),
        )

    /**
     * Web Scrape Tool Definition
     */
    private fun webScrapeToolDefinition(): ToolDefinition =
        ToolDefinition(
            name = "web_scrape",
            description =
                "Extract full text content from a specific URL. Use this after finding a " +
                    "relevant URL to get detailed information.",
            parameters =
                ToolParameters(
                    properties =
                        mapOf(
                            "url" to
                                ToolProperty(
                                    type = "string",
                                    description = "The URL to scrape",
                                ),
                            "purpose" to
                                ToolProperty(
                                    type = "string",
                                    description = "Why this page is being scraped (for tracking)",
                                ),
                        ),
                    required = listOf("url"),
                ),
        )

    /**
     * Save Progress Tool Definition - Track findings in progress file
     */
    private fun saveProgressToolDefinition(): ToolDefinition =
        ToolDefinition(
            name = "save_progress",
            description =
                "Save important findings to the research progress file. Use this to track " +
                    "useful information during long research sessions.",
            parameters =
                ToolParameters(
                    properties =
                        mapOf(
                            "finding" to
                                ToolProperty(
                                    type = "string",
                                    description = "The key finding or information to save",
                                ),
                            "source" to
                                ToolProperty(
                                    type = "string",
                                    description = "The source URL or reference for this finding",
                                ),
                            "category" to
                                ToolProperty(
                                    type = "string",
                                    description = "Category or theme for this finding (e.g., 'background', 'methodology', 'results')",
                                ),
                        ),
                    required = listOf("finding", "source"),
                ),
        )

    /**
     * Read Progress Tool Definition - Read saved findings from progress file
     */
    private fun readProgressToolDefinition(): ToolDefinition =
        ToolDefinition(
            name = "read_progress",
            description =
                "Read previously saved findings from the research progress file. Use this when " +
                    "context is exceeded or to review accumulated knowledge.",
            parameters =
                ToolParameters(
                    properties =
                        mapOf(
                            "category" to
                                ToolProperty(
                                    type = "string",
                                    description = "Optional category to filter findings (leave empty for all)",
                                ),
                        ),
                    required = emptyList(),
                ),
        )
}
