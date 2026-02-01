package com.example.smarty.agent.executor

import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.ChatMessage
import ai.koog.prompt.llm.ChatRole

class ArceeFixingExecutor(
    private val delegate: PromptExecutor
) : PromptExecutor {

    // Intentionally wrong signature to provoke compiler into revealing the correct one
    override suspend fun execute(param: Any): Any {
        return delegate.toString()
    }
}
