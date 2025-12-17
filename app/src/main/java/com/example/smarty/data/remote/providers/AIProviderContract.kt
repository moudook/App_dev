package com.example.smarty.data.remote.providers

import com.example.smarty.data.remote.AIResponse
import com.example.smarty.data.remote.DocumentAnalysisResponse

/**
 * Contract interface for AI provider implementations.
 *
 * This interface defines the standard operations that all AI providers must support.
 * Each provider (Gemini, OpenAI, DeepSeek, Groq, OpenRouter, HuggingFace) implements
 * this contract with their specific API details.
 *
 * Usage:
 * ```kotlin
 * val provider: AIProviderContract = GeminiProvider(client, gson)
 * val response = provider.analyzeContent("Hello world", apiKey, model)
 * ```
 *
 * @see GeminiProvider
 * @see OpenAICompatibleProvider
 * @see HuggingFaceProvider
 */
interface AIProviderContract {

    /**
     * The display name of this provider.
     * Used for logging and debugging.
     */
    val providerName: String

    /**
     * Analyze content and return categorization with summary.
     *
     * This is the primary analysis method used for note categorization.
     * The provider should parse the content and return:
     * - A category (Learn, Read, Watch, etc.)
     * - A summary of the content
     * - A "why saved" explanation
     *
     * @param content The text content to analyze
     * @param apiKey The API key for authentication
     * @param model The model identifier to use
     * @param systemPrompt The system prompt with instructions
     * @return AIResponse with categorization, or null if failed
     */
    fun analyzeContent(
        content: String,
        apiKey: String,
        model: String,
        systemPrompt: String
    ): AIResponse?

    /**
     * Analyze a document and return comprehensive analysis.
     *
     * Used for PDF and long-form document analysis.
     * Returns more detailed information including key points and action items.
     *
     * @param content The document text to analyze
     * @param apiKey The API key for authentication
     * @param model The model identifier to use
     * @param systemPrompt The system prompt with document analysis instructions
     * @return DocumentAnalysisResponse with detailed analysis, or null if failed
     */
    fun analyzeDocument(
        content: String,
        apiKey: String,
        model: String,
        systemPrompt: String
    ): DocumentAnalysisResponse?

    /**
     * Chat with the AI for conversational interactions.
     *
     * Used by the agent service for user conversations.
     * Returns raw text response for further processing.
     *
     * @param systemPrompt The system instructions for the conversation
     * @param userPrompt The user's message with context
     * @param apiKey The API key for authentication
     * @param model The model identifier to use
     * @return Raw text response from the AI, or null if failed
     */
    fun chat(
        systemPrompt: String,
        userPrompt: String,
        apiKey: String,
        model: String
    ): String?

    /**
     * Test if an API key is valid by making a simple request.
     *
     * @param apiKey The API key to test
     * @param model The model to use for testing
     * @return true if the key is valid and the request succeeds
     */
    fun testApiKey(apiKey: String, model: String): Boolean {
        return try {
            val result = analyzeContent(
                content = "Test: Remember to buy groceries tomorrow",
                apiKey = apiKey,
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
