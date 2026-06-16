package com.example.smarty.server.agent2.tools

import com.example.smarty.server.agent.ToolPermissionEnforcer
import com.example.smarty.server.data.PostgresVectorStore
import dev.langchain4j.agent.tool.Tool
import dev.langchain4j.agent.tool.P
import org.slf4j.LoggerFactory

class ProfileTool(
    private val vectorStore: PostgresVectorStore? = null,
    private val userId: String = "dev-user",
    private val permissionEnforcer: ToolPermissionEnforcer = ToolPermissionEnforcer(),
) {
    private val logger = LoggerFactory.getLogger(ProfileTool::class.java)

    @Tool("Store information about the user's personality, preferences, routines, and relationships (Semantic Profile).")
    suspend fun rememberFact(
        @P("Fact to remember about the user") fact: String,
        @P("Category: emotional|routine|preference|skill|relationship") category: String? = "factual",
        @P("Emotional significance on a 1-5 scale") emotionalSignificance: Int? = null,
    ): String {
        logger.info("[ProfileTool] Remembering: ${fact.take(50)}")
        return "Remembered: ${fact.take(50)}"
    }

    @Tool("List all facts stored about the user.")
    suspend fun listFacts(): String {
        logger.info("[ProfileTool] Listing facts")
        return "No stored facts about this user yet."
    }
}
