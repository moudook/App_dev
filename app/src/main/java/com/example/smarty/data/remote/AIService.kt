package com.example.smarty.data.remote

import android.app.Application
import android.util.Log
import com.example.smarty.data.local.AIProvider
import com.example.smarty.data.local.SecurePreferences
import com.example.smarty.util.HttpClientProvider
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.*
import java.io.IOException

// ==================== Response Models ====================

data class AIResponse(
    val title: String,
    val category: String,
    val summary: String,
    val whySaved: String,
    val tags: List<String> = emptyList(),  // AI-generated tags for the note
    val todos: List<String> = emptyList(),  // AI-extracted todo items
    val success: Boolean = true,
    val error: String? = null
)

data class DocumentAnalysisResponse(
    val title: String,
    val summary: String,
    val keyPoints: List<String>,
    val category: String,
    val actionItems: List<String>,
    val userRelevance: String,
    val references: DocumentReferences? = null,  // Formulas, key terms, recurring topics
    val success: Boolean = true,
    val error: String? = null
)

/**
 * References extracted from document analysis.
 * Contains formulas, key terms with definitions, and recurring topics.
 */
data class DocumentReferences(
    val formulas: List<String> = emptyList(),
    val keyTerms: List<KeyTerm> = emptyList(),
    val recurringTopics: List<String> = emptyList()
)

/**
 * A key term with its definition.
 */
data class KeyTerm(
    val term: String,
    val definition: String
)

// ==================== API Request/Response Models ====================

data class OpenAIRequest(
    val model: String,
    val messages: List<OpenAIMessage>,
    val temperature: Float = 0.4f,
    @SerializedName("max_tokens")
    val maxTokens: Int = 300,
    @SerializedName("enable_thinking")
    val enableThinking: Boolean? = null,
    val stream: Boolean? = null
)

data class OpenAIMessage(
    val role: String,
    val content: String
)

data class OpenAIResponse(
    val choices: List<OpenAIChoice>?
)

data class OpenAIChoice(
    val message: OpenAIMessageResponse?
)

data class OpenAIMessageResponse(
    val content: String?
)

// ==================== AI Service Facade ====================

/**
 * AI Service facade that coordinates AI operations.
 * Thin Client Version: Cloud operations are offloaded to the server.
 * Local operations are restricted to LOCAL_PC.
 *
 * This is a thin facade that delegates to specialized handlers:
 * - [AIProviderOrchestrator]: Provider management
 * - [ContentAnalyzer]: Content and document analysis
 *
 * @property securePreferences Secure storage for settings
 */
class AIService(private val application: Application, private val securePreferences: SecurePreferences) {

    companion object {
        private const val TAG = "AIService"
    }

    // Specialized handlers
    private val orchestrator = AIProviderOrchestrator(securePreferences)
    private val contentAnalyzer = ContentAnalyzer(application, orchestrator)

    /**
     * Analyzes content using available AI providers with fallback and retry logic.
     * Applies security filtering before sending to AI to prevent prompt injection.
     *
     * @param content The text content to analyze
     * @param attachmentMetadata Optional list of attachment metadata (file names and types only)
     */
    suspend fun analyzeContent(
        content: String,
        attachmentMetadata: List<com.example.smarty.data.model.AttachmentMetadata>? = null
    ): AIResponse {
        return contentAnalyzer.analyzeContent(content, attachmentMetadata)
    }

    /**
     * Analyzes document content (PDFs, long-form text) with comprehensive summarization.
     *
     * @param documentText The extracted text from the document
     * @param fileName Optional filename for context
     * @param userContext Optional additional context about the user's intent
     */
    suspend fun analyzeDocument(
        documentText: String,
        fileName: String? = null,
        userContext: String? = null
    ): DocumentAnalysisResponse {
        return contentAnalyzer.analyzeDocument(documentText, fileName, userContext)
    }

    /**
     * Simple chat for non-agent AI interactions (summarization, title compression, etc.).
     * Thin Client: Restricted to LOCAL_PC for local execution.
     *
     * @param systemPrompt The system instructions
     * @param userPrompt The user's message
     * @return The AI response text, or throws if no provider available
     */
    suspend fun simpleChat(systemPrompt: String, userPrompt: String): String = withContext(Dispatchers.IO) {
        // Use standardized read timeout from HttpClientProvider (AI responses can be slow)
        val timeoutMs = HttpClientProvider.READ_TIMEOUT_SECONDS * 1000
        try {
            withTimeout(timeoutMs) {
                val providers = orchestrator.getOrderedProviders()
                val configs = orchestrator.getAllProviderConfigs()

                // Thin Client only supports LOCAL_PC for local simpleChat
                val provider = providers.find { it == AIProvider.LOCAL_PC } ?: throw IllegalStateException("No local AI provider available")

                val config = configs[provider]

                // Respect the user's "Enabled" setting
                if (!orchestrator.isProviderAvailable(config)) {
                    throw IllegalStateException("Local AI provider is disabled")
                }

                val providerInstance = orchestrator.getProvider(provider)
                val model = orchestrator.getModelForProvider(provider)

                val keyToUse = "local_pc_no_key"

                Log.i(TAG, "simpleChat: Attempting $provider with model $model")

                val result = providerInstance.chat(
                    context = application,
                    systemPrompt = systemPrompt,
                    userPrompt = userPrompt,
                    apiKey = keyToUse,
                    model = model
                )

                if (result != null) {
                    Log.d(TAG, "simpleChat succeeded with $provider")
                    return@withTimeout result
                } else {
                    Log.w(TAG, "simpleChat: $provider returned null result")
                    throw IllegalStateException("Local AI provider returned no result")
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "simpleChat timed out after ${HttpClientProvider.READ_TIMEOUT_SECONDS} seconds")
            throw IOException("Request timed out. Please try again.")
        }
    }

    /**
     * Check if any AI provider is available for processing.
     * Thin Client: Checks if LOCAL_PC is enabled and available.
     */
    fun isAiAvailable(): Boolean {
        // Thin Client primarily relies on server-side AI, but locally we only care about LOCAL_PC
        val config = orchestrator.getAllProviderConfigs()[AIProvider.LOCAL_PC]
        return orchestrator.isProviderAvailable(config)
    }

    /**
     * Test if an API key is valid.
     * Thin Client: Not applicable for cloud providers on the client.
     */
    suspend fun testApiKey(provider: AIProvider, apiKey: String): Boolean = withContext(Dispatchers.IO) {
        if (provider != AIProvider.LOCAL_PC) return@withContext false

        Log.i(TAG, "Testing LOCAL_PC connection...")

        try {
            val providerInstance = orchestrator.getProvider(provider)
            val model = orchestrator.getModelForProvider(provider)
            val testContent = "Test connection"

            val result = providerInstance.analyzeContent(
                context = application,
                content = testContent,
                apiKey = apiKey,
                model = model,
                systemPrompt = "Respond with 'ok'"
            )

            val success = result?.success == true
            Log.i(TAG, "Connection test result: ${if (success) "SUCCESS" else "FAILED"}")
            success
        } catch (e: Exception) {
            Log.e(TAG, "Connection test failed: ${e.message}")
            false
        }
    }
}
