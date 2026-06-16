package com.example.smarty.server.agent2.tools

import dev.langchain4j.agent.tool.Tool
import dev.langchain4j.agent.tool.P
import org.slf4j.LoggerFactory

class ScratchpadTool {
    private val logger = LoggerFactory.getLogger(ScratchpadTool::class.java)

    @Tool("Iterative Working Memory. For deeply complex logic, coding tasks, or heavy deep research.")
    fun scratchpad(
        @P("Scratchpad content to store") content: String,
        @P("Iteration number (1-10)") iteration: Int,
    ): String {
        logger.info("[ScratchpadTool] Iteration $iteration recorded (${content.length} chars)")
        return "Scratchpad iteration $iteration recorded (${content.length} chars)."
    }
}
