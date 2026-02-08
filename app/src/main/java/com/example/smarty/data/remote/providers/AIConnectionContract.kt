package com.example.smarty.data.remote.providers

import com.example.smarty.data.remote.AIResponse
import com.example.smarty.data.remote.DocumentAnalysisResponse

/**
 * Contract interface for AI connection implementations.
 *
 * This interface defines the standard operations that all AI connections must support.
 * The primary implementation is CompatibleAIConnection for Local PC and server-managed LLMs.
 *
 * Usage:
 * ```kotlin
 * val connection: AIConnectionContract = CompatibleAIConnection.localPC(...)
 * val response = connection.analyzeContent(context, "Hello world", connectionToken, model, systemPrompt)
 * ```
 *
 * @see CompatibleAIConnection
 */
interface AIConnectionContract {

    /**
     * The display name of this connection.
     * Used for logging and debugging.
     */
    val connectionName: String

    /**
     * Analyze content and return categorization with summary.
     *
     * This is the primary analysis method used for note categorization.
     * The provider should parse the content and return:
     * - A category (Learn, Read, Watch, etc.)
     * - A summary of the content
     * - A "why saved" explanation
     *
     * BUG-005 FIX: Changed to suspend function to avoid runBlocking ANR risk.
     *
     * @param content The text content to analyze
     * @param connectionToken The connection token for authentication
     * @param model The model identifier to use
     * @param systemPrompt The system prompt with instructions
     * @return AIResponse with categorization, or null if failed
     */
    suspend fun analyzeContent(
        context: android.content.Context,
        content: String,
        connectionToken: String,
        model: String,
        systemPrompt: String
    ): AIResponse?

    /**
     * Analyze a document and return comprehensive analysis.
     *
     * Used for PDF and long-form document analysis.
     * Returns more detailed information including key points and action items.
     *
     * BUG-005 FIX: Changed to suspend function to avoid runBlocking ANR risk.
     *
     * @param context Android context for localization
     * @param content The document text to analyze
     * @param connectionToken The connection token for authentication
     * @param model The model identifier to use
     * @param systemPrompt The system prompt with document analysis instructions
     * @return DocumentAnalysisResponse with detailed analysis, or null if failed
     */
    suspend fun analyzeDocument(
        context: android.content.Context,
        content: String,
        connectionToken: String,
        model: String,
        systemPrompt: String
    ): DocumentAnalysisResponse?

    /**
     * Chat with the AI for conversational interactions.
     *
     * Used by the agent service for user conversations.
     * Returns raw text response for further processing.
     *
     * BUG-005 FIX: Changed to suspend function to avoid runBlocking ANR risk.
     *
     * @param context Android context for localization
     * @param systemPrompt The system instructions for the conversation
     * @param userPrompt The user's message with context
     * @param connectionToken The connection token for authentication
     * @param model The model identifier to use
     * @return Raw text response from the AI, or null if failed
     */
    suspend fun chat(
        context: android.content.Context,
        systemPrompt: String,
        userPrompt: String,
        connectionToken: String,
        model: String
    ): String?

    /**
     * Test if the provider connection is valid by making a simple request.
     *
     * @param context Android context for localization
     * @param connectionToken The connection token to test
     * @param model The model to use for testing
     * @return true if the connection is valid and the request succeeds
     */
    suspend fun testConnection(context: android.content.Context, connectionToken: String, model: String): Boolean {
        return try {
            val result = analyzeContent(
                context = context,
                content = "Test: Remember to buy groceries tomorrow",
                connectionToken = connectionToken,
                model = model,
                systemPrompt = "Respond with JSON: {\"category\":\"Todo\",\"summary\":\"Test\",\"whySaved\":\"Test\"}"
            )
            result?.success == true
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * Configuration for AI provider requests.
 *
 * Encapsulates common parameters used across providers.
 */
data class AIRequestConfig(
    /** Temperature for response randomness (0.0 = deterministic, 1.0 = creative) */
    val temperature: Float = 0.4f,
    /** Maximum tokens in the response */
    val maxTokens: Int = 300,
    /** Top-p nucleus sampling parameter */
    val topP: Float = 0.8f,
    /** Top-k sampling parameter */
    val topK: Int = 40
) {
    companion object {
        /** Default config for content analysis (more deterministic) */
        val ANALYSIS = AIRequestConfig(
            temperature = 0.4f,
            maxTokens = 300,
            topP = 0.8f,
            topK = 40
        )

        /** Config for document analysis (longer responses) */
        val DOCUMENT = AIRequestConfig(
            temperature = 0.3f,
            maxTokens = 800,
            topP = 0.8f,
            topK = 40
        )

        /** Config for chat/conversation (more creative) */
        val CHAT = AIRequestConfig(
            temperature = 0.7f,
            maxTokens = 1024,
            topP = 0.9f,
            topK = 40
        )
    }
}
