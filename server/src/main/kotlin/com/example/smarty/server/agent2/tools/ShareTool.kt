package com.example.smarty.server.agent2.tools

import dev.langchain4j.agent.tool.Tool
import dev.langchain4j.agent.tool.P
import org.slf4j.LoggerFactory

class ShareTool {
    private val logger = LoggerFactory.getLogger(ShareTool::class.java)

    @Tool("Triggers the system share sheet with specified content on the user's device.")
    fun shareContent(
        @P("Content to share") content: String,
        @P("Optional share title") title: String? = null,
    ): String {
        logger.info("[ShareTool] Sharing: ${content.take(50)}")
        return "Sharing: $content"
    }
}
