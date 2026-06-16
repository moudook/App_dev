package com.example.smarty.server.agent2.tools

import dev.langchain4j.agent.tool.Tool
import dev.langchain4j.agent.tool.P
import org.slf4j.LoggerFactory

class WebSearchTool {
    private val logger = LoggerFactory.getLogger(WebSearchTool::class.java)

    @Tool("Information Retrieval via web search. Search the web for current information.")
    fun webSearch(
        @P("List of search queries (max 3)") queries: List<String>,
    ): String {
        logger.info("[WebSearchTool] Searching for: $queries")
        val queriesStr = queries.joinToString(", ")
        return "[WEB_SEARCH_STUB] Queries: $queriesStr. (Live Tavily integration pending)"
    }
}
