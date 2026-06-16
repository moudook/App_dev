package com.example.smarty.server.agent2

data class ContextBudget(
    val toolSchemasTokens: Int = 5_000,
    val maxResponseTokens: Int = 4_096,
    val maxThinkingTokens: Int = 2_048,
    val compactionOverhead: Int = 1_000,
    val safetyMargin: Int = 500,
) {
    val totalOverhead: Int
        get() = toolSchemasTokens + maxResponseTokens + maxThinkingTokens +
                compactionOverhead + safetyMargin

    val compactTriggerOverhead: Int
        get() = compactionOverhead + safetyMargin
}

class ContextWindowManager(
    private val modelContextWindowProvider: ModelContextWindowProvider,
) {
    fun getChatMemoryLimit(contextWindow: Int, budget: ContextBudget = ContextBudget()): Int {
        return contextWindow - budget.totalOverhead
    }

    fun getCompactTrigger(contextWindow: Int, budget: ContextBudget = ContextBudget()): Int {
        return getChatMemoryLimit(contextWindow, budget) - budget.compactTriggerOverhead
    }

    suspend fun getChatMemoryLimitForModel(modelId: String, budget: ContextBudget = ContextBudget()): Int {
        val contextWindow = modelContextWindowProvider.getContextWindow(modelId)
        return getChatMemoryLimit(contextWindow, budget)
    }

    suspend fun getCompactTriggerForModel(modelId: String, budget: ContextBudget = ContextBudget()): Int {
        val contextWindow = modelContextWindowProvider.getContextWindow(modelId)
        return getCompactTrigger(contextWindow, budget)
    }
}
