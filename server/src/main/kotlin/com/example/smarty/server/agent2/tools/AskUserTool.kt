package com.example.smarty.server.agent2.tools

import dev.langchain4j.agent.tool.Tool
import dev.langchain4j.agent.tool.P
import org.slf4j.LoggerFactory

data class AskUserQuestionInput(
    val question: String,
    val options: List<String> = emptyList(),
    val allowCustom: Boolean = false,
)

class AskUserTool(
    private val userId: String = "dev-user",
) {
    private val logger = LoggerFactory.getLogger(AskUserTool::class.java)

    @Tool("PAUSE execution and ask the user a question. Use this when you need clarification, preferences, or input. The user will see a UI with your question and options, and the conversation will resume when they answer.")
    suspend fun askUser(
        @P("Array of questions to ask. Each must have 'question' text and 'options' array of 2-5 choices.") questions: List<AskUserQuestionInput>,
    ): String {
        logger.info("[AskUserTool] Asking ${questions.size} question(s)")
        return "__ASK_USER_TURN_COMPLETE__"
    }
}
