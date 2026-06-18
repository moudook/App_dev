package com.example.smarty.server.agent2.tools

import com.example.smarty.server.agent.ToolPermissionEnforcer
import com.example.smarty.server.tools.KreaImageTool
import dev.langchain4j.agent.tool.Tool
import dev.langchain4j.agent.tool.P
import org.slf4j.LoggerFactory

class ImageTool(
    private val kreaTool: KreaImageTool = KreaImageTool.shared,
    private val userId: String = "dev-user",
    private val permissionEnforcer: ToolPermissionEnforcer = ToolPermissionEnforcer(),
) {
    private val logger = LoggerFactory.getLogger(ImageTool::class.java)

    @Tool("Generate images using Krea AI. Provide highly detailed prompts with camera angles, lighting, styles.")
    suspend fun generateImage(
        @P("Detailed description of the image to generate") prompt: String,
        @P("Aspect ratio: 1:1|16:9|9:16|4:3|3:4") aspectRatio: String? = "1:1",
    ): String {
        logger.info("[ImageTool] Generating image: ${prompt.take(100)}")
        return """{"type": "image", "url": "pending", "source": "krea", "prompt": "${prompt.take(200)}"}"""
    }
}
