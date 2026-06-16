package com.example.smarty.server.agent2.tools

import dev.langchain4j.agent.tool.Tool
import dev.langchain4j.agent.tool.P
import org.slf4j.LoggerFactory
import java.util.UUID

class LaunchTool {
    private val logger = LoggerFactory.getLogger(LaunchTool::class.java)

    @Tool("Unified Intent Router. Open internal screens or external apps on the user's device.")
    fun launchUi(
        @P("Intent name: home|calendar|stacks|archive|settings|guided_breathing|chess|coin_toss|tic_tac_toe or an external app name") intent: String,
    ): String {
        logger.info("[LaunchTool] Launching: $intent")
        return "Navigating to $intent."
    }
}
