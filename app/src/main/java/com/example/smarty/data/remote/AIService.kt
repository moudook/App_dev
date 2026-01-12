package com.example.smarty.data.remote

import android.util.Log
import com.example.smarty.data.local.AIProvider
import com.example.smarty.data.local.SecurePreferences
import com.google.gson.annotations.SerializedName
import com.example.smarty.util.HttpClientProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
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

data class GeminiRequest(
    val contents: List<GeminiContent>,
    val generationConfig: GeminiGenerationConfig = GeminiGenerationConfig()
)

data class GeminiContent(
    val parts: List<GeminiPart>
)

data class GeminiPart(
    val text: String
)

data class GeminiGenerationConfig(
    val temperature: Float = 0.4f,
    val maxOutputTokens: Int = 300,
    val topP: Float = 0.8f,
    val topK: Int = 40
)

data class HuggingFaceRequest(
    val inputs: String,
    val parameters: HuggingFaceParams = HuggingFaceParams()
)

data class HuggingFaceParams(
    @SerializedName("max_new_tokens")
    val maxNewTokens: Int = 300,
    val temperature: Float = 0.4f,
    @SerializedName("return_full_text")
    val returnFullText: Boolean = false
)

data class OpenAIRequest(
    val model: String,
    val messages: List<OpenAIMessage>,
    val temperature: Float = 0.4f,
    @SerializedName("max_tokens")
    val maxTokens: Int = 300,
    @SerializedName("enable_thinking")
    val enableThinking: Boolean? = null,  // For reasoning models like Falcon-H1R-7B
    val stream: Boolean? = null  // Enable SSE streaming for real-time token output
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
 *
 * This is a thin facade that delegates to specialized handlers:
 * - [AIProviderOrchestrator]: Provider management and key rotation
 * - [ContentAnalyzer]: Content and document analysis
 *
 * Note: Agent chat is now handled by JarvisAgent using Koog framework.
 *
 * @property securePreferences Secure storage for API keys and settings
 */
class AIService(private val securePreferences: SecurePreferences) {

    companion object {
        private const val TAG = "AIService"
    }

    // Specialized handlers
    private val orchestrator = AIProviderOrchestrator(securePreferences)
    private val contentAnalyzer = ContentAnalyzer(orchestrator)

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
     * Uses the first available provider with API key.
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

                for (provider in providers) {
                    val config = configs[provider]
                    
                    // Respect the user's "Enabled" setting
                    if (!orchestrator.isProviderAvailable(config)) {
                        continue
                    }

                    val providerInstance = orchestrator.getProvider(provider)
                    val model = orchestrator.getModelForProvider(provider)
                    
                    val keys = config?.apiKeys ?: emptyList()
                    val keyToUse = if (provider == AIProvider.LOCAL_PC) "local_pc_no_key" else keys.firstOrNull() ?: continue
                    
                    Log.i(TAG, "simpleChat: Attempting $provider with model $model")

                    val result = providerInstance.chat(
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
                    }
                }

                throw IllegalStateException("No AI provider available for chat")
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "simpleChat timed out after ${HttpClientProvider.READ_TIMEOUT_SECONDS} seconds")
            throw IOException("Request timed out. Please try again.")
        }
    }

    /**
     * Check if any AI provider is available for processing.
     * Returns true if at least one provider is enabled and available.
     * LOCAL_PC doesn't need API keys - it uses local server.
     */
    fun isAiAvailable(): Boolean {
        val providers = orchestrator.getOrderedProviders()
        val configs = orchestrator.getAllProviderConfigs()

        return providers.any { provider ->
            val config = configs[provider]
            // Use orchestrator's availability check which handles LOCAL_PC correctly
            orchestrator.isProviderAvailable(config)
        }
    }

    /**
     * Test if an API key is valid by making a simple analysis request.
     */
    suspend fun testApiKey(provider: AIProvider, apiKey: String): Boolean = withContext(Dispatchers.IO) {
        Log.i(TAG, "Testing $provider API key...")

        try {
            val providerInstance = orchestrator.getProvider(provider)
            val model = orchestrator.getModelForProvider(provider)
            val testContent = "Test: Remember to buy groceries tomorrow"

            val result = providerInstance.analyzeContent(
                content = testContent,
                apiKey = apiKey,
                model = model,
                systemPrompt = ContentAnalyzer.SYSTEM_PROMPT
            )

            val success = result?.success == true
            Log.i(TAG, "API key test result: ${if (success) "VALID" else "INVALID"}")
            success
        } catch (e: Exception) {
            Log.e(TAG, "API key test failed: ${e.message}")
            false
        }
    }
}
