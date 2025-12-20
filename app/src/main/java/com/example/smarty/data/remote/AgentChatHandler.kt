package com.example.smarty.data.remote

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Handles agent chat operations with dual-key architecture.
 *
 * Key Architecture:
 * - Key 1 (Agent Key): Reserved for agent reasoning and tool execution
 * - Keys 2,3,4...: Used for normal chat operations
 *
 * @property orchestrator Provider orchestrator for API calls
 */
class AgentChatHandler(private val orchestrator: AIProviderOrchestrator) {

    companion object {
        private const val TAG = "AgentChatHandler"

        private const val FALLBACK_NORMAL = """{"action": "ANSWER_QUESTION", "params": {"question": ""}, "response": "I'm sorry, I couldn't process your request. Please check your API configuration in settings."}"""
        private const val FALLBACK_REASONING = """{"action": "ANSWER_QUESTION", "params": {}, "response": "I'm having trouble processing your request. Please check your API keys and try again."}"""
        private const val FALLBACK_FINAL = """{"action": "ANSWER_QUESTION", "params": {}, "response": "I completed the tasks but couldn't generate a final summary."}"""
    }

    /**
     * Normal chat method for conversational AI interactions.
     * Uses keys 2,3,4... (excludes agent key 1).
     *
     * @param systemPrompt The system instructions for the agent
     * @param userPrompt The user message with context
     * @return Raw AI response string
     */
    suspend fun normalChat(systemPrompt: String, userPrompt: String): String = withContext(Dispatchers.IO) {
        Log.i(TAG, "=== Starting Normal Chat (Uses Keys 2,3,4... - EXCLUDES agent key) ===")
        Log.d(TAG, "User prompt length: ${userPrompt.length} chars")

        val configs = orchestrator.getAllProviderConfigs()
        var totalKeysTried = 0

        for (provider in orchestrator.getOrderedProviders()) {
            val config = configs[provider]
            if (!orchestrator.isProviderAvailable(config)) {
                Log.d(TAG, "$provider not available or disabled")
                continue
            }

            val providerInstance = orchestrator.getProvider(provider)
            val model = orchestrator.getModelForProvider(provider)

            val result = orchestrator.executeWithNormalKeys(provider, config!!) { apiKey ->
                totalKeysTried++
                providerInstance.chat(
                    systemPrompt = systemPrompt,
                    userPrompt = userPrompt,
                    apiKey = apiKey,
                    model = model
                )
            }

            if (result != null) {
                return@withContext result
            }
        }

        // Fallback response when AI is unavailable
        Log.w(TAG, "⚠ All $totalKeysTried normal keys failed")
        return@withContext FALLBACK_NORMAL
    }

    /**
     * Agent REASONING API - Uses the FIRST API key for tool execution and reasoning.
     * This is the "thinking" API that handles the agent loop iterations.
     *
     * The reasoning API:
     * - Executes during the agent loop
     * - Handles tool calls (web search, note operations, etc.)
     * - Continues until no more actions are needed
     *
     * @param systemPrompt The system instructions for the agent
     * @param userPrompt The user message with context
     * @return Raw AI response string for parsing actions
     */
    suspend fun agentReasoning(systemPrompt: String, userPrompt: String): String = withContext(Dispatchers.IO) {
        Log.i(TAG, "=== Agent REASONING (Uses FIRST key ONLY - dedicated agent key) ===")
        Log.d(TAG, "Reasoning prompt length: ${userPrompt.length} chars")

        val configs = orchestrator.getAllProviderConfigs()

        for (provider in orchestrator.getOrderedProviders()) {
            val config = configs[provider]
            if (!orchestrator.isProviderAvailable(config)) {
                continue
            }

            val providerInstance = orchestrator.getProvider(provider)
            val model = orchestrator.getModelForProvider(provider)

            val result = orchestrator.executeWithAgentKey(provider, config!!) { apiKey ->
                providerInstance.chat(
                    systemPrompt = systemPrompt,
                    userPrompt = userPrompt,
                    apiKey = apiKey,
                    model = model
                )
            }

            if (result != null) {
                return@withContext result
            }
        }

        Log.w(TAG, "⚠ Agent key failed for all providers")
        return@withContext FALLBACK_REASONING
    }

    /**
     * Agent FINAL RESPONSE API - Uses the FIRST API key for final user-facing response.
     * This is the "response" API that generates the polished answer after tool execution.
     *
     * The final response API:
     * - Called AFTER all tool executions are complete
     * - Generates a coherent, user-friendly response
     * - Incorporates all tool results into the answer
     *
     * @param systemPrompt The system instructions for the agent
     * @param userPrompt Context including original query and tool results
     * @return Raw AI response string (final answer to user)
     */
    suspend fun agentFinalResponse(systemPrompt: String, userPrompt: String): String = withContext(Dispatchers.IO) {
        Log.i(TAG, "=== Agent FINAL RESPONSE (Uses FIRST key ONLY - dedicated agent key) ===")
        Log.d(TAG, "Response prompt length: ${userPrompt.length} chars")

        val configs = orchestrator.getAllProviderConfigs()

        for (provider in orchestrator.getOrderedProviders()) {
            val config = configs[provider]
            if (!orchestrator.isProviderAvailable(config)) {
                continue
            }

            val providerInstance = orchestrator.getProvider(provider)
            val model = orchestrator.getModelForProvider(provider)

            val result = orchestrator.executeWithAgentKey(provider, config!!) { apiKey ->
                providerInstance.chat(
                    systemPrompt = systemPrompt,
                    userPrompt = userPrompt,
                    apiKey = apiKey,
                    model = model
                )
            }

            if (result != null) {
                return@withContext result
            }
        }

        Log.w(TAG, "⚠ Agent key failed for final response on all providers")
        return@withContext FALLBACK_FINAL
    }
}
