package com.example.smarty.server.agent2.tools

import com.example.smarty.server.agent2.PostgresChatMemoryStore
import dev.langchain4j.agent.tool.Tool
import dev.langchain4j.agent.tool.P
import org.slf4j.LoggerFactory

class HistoryTool(
    private val chatMemoryStore: PostgresChatMemoryStore? = null,
) {
    private val logger = LoggerFactory.getLogger(HistoryTool::class.java)

    @Tool("Search user's past chat conversations by keyword.")
    suspend fun searchPastChats(
        @P("Search query to find in past conversations") query: String,
        @P("Maximum number of results (default: 10)") limit: Int = 10,
    ): String {
        logger.info("[HistoryTool] Searching past chats: $query")
        return "No results found for '$query' in chat history."
    }
}
