package com.example.smarty.agent.routing

import com.example.smarty.agent.SmartyAgentProvider.ExecutorResult
import com.example.smarty.data.local.AIProvider
import ai.koog.prompt.llm.LLModel

/**
 * Registry to classify models into functional tiers for the "Tiered Model Architecture".
 * - **Ingestion Tier**: High context (1M+), low cost. Used for reading large search results.
 * - **Reasoning Tier**: High intelligence, complex instruction following. Used for planning and synthesis.
 * - **Fast Tier**: Low latency. Used for quick tools or simple chats.
 */
object ModelTierRegistry {

    enum class Tier {
        INGESTION,
        REASONING,
        FAST,
        GENERAL
    }

    /**
     * Get the best available executor for "Ingestion" tasks (large context reading).
     * Priority: Gemini 1.5 Flash > Gemini 1.5 Pro > Claude 3 Haiku > Others
     */
    fun getIngestionExecutor(executors: List<ExecutorResult.Success>): ExecutorResult.Success? {
        return executors.firstOrNull { result ->
            val modelId = result.model.id.lowercase()
            // Preference 1: Gemini 1.5/2.0 Flash (Ideal balance of context & speed)
            (result.provider == AIProvider.GEMINI && modelId.contains("flash"))
        } ?: executors.firstOrNull { result ->
            // Preference 2: Any Gemini (Usually has 1M+ context)
            result.provider == AIProvider.GEMINI
        } ?: executors.firstOrNull { result ->
            // Preference 3: Claude models (Usually 200k context)
            result.provider == AIProvider.ANTHROPIC
        } ?: executors.firstOrNull {
            // Fallback: Any model with large context claim
            it.model.contextLength >= 100_000
        }
    }

    /**
     * Get the best available executor for "Reasoning" tasks (planning, synthesis).
     * Priority: GPT-4o / Claude 3.5 Sonnet > Gemini Pro > Others
     */
    fun getReasoningExecutor(executors: List<ExecutorResult.Success>): ExecutorResult.Success? {
        return executors.firstOrNull { result ->
            val modelId = result.model.id.lowercase()
            // Preference 1: Top-tier reasoning models
            (result.provider == AIProvider.OPENAI && modelId.contains("gpt-4")) ||
            (result.provider == AIProvider.ANTHROPIC && modelId.contains("sonnet")) ||
            (result.provider == AIProvider.GITHUB && modelId.contains("gpt-4"))
        } ?: executors.firstOrNull { result ->
            // Preference 2: Gemini Pro
            result.provider == AIProvider.GEMINI && result.model.id.contains("pro")
        } ?: executors.firstOrNull { result ->
            // Preference 3: DeepSeek
            result.provider == AIProvider.DEEPSEEK
        } ?: executors.firstOrNull {
            // Fallback: Any generic provider
            true
        }
    }

    /**
     * Classify a specific model into a tier.
     */
    fun classify(result: ExecutorResult.Success): Tier {
        val modelId = result.model.id.lowercase()
        val provider = result.provider

        return when {
            // Ingestion Champions
            provider == AIProvider.GEMINI && modelId.contains("flash") -> Tier.INGESTION
            
            // Reasoning Champions
            modelId.contains("gpt-4") -> Tier.REASONING
            modelId.contains("sonnet") -> Tier.REASONING
            modelId.contains("opus") -> Tier.REASONING
            modelId.contains("deepseek-reasoner") -> Tier.REASONING
            
            // Fast Models
            modelId.contains("haiku") -> Tier.FAST
            modelId.contains("mini") -> Tier.FAST
            modelId.contains("instant") -> Tier.FAST
            
            else -> Tier.GENERAL
        }
    }
}
