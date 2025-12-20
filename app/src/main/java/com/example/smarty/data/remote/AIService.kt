package com.example.smarty.data.remote

import android.util.Log
import com.example.smarty.data.local.AIProvider
import com.example.smarty.data.local.SecurePreferences
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ==================== Response Models ====================

data class AIResponse(
    val title: String,
    val category: String,
    val summary: String,
    val whySaved: String,
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
    val success: Boolean = true,
    val error: String? = null
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
    val maxTokens: Int = 300
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
 * - [AgentChatHandler]: Agent chat operations
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
    private val agentChatHandler = AgentChatHandler(orchestrator)

    /**
     * Analyzes content using available AI providers with fallback and retry logic.
     * Applies security filtering before sending to AI to prevent prompt injection.
     */
    suspend fun analyzeContent(content: String): AIResponse {
        return contentAnalyzer.analyzeContent(content)
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
     * Normal chat for conversational AI interactions.
     * Uses keys 2,3,4... (excludes agent key 1).
     */
    suspend fun agentChat(systemPrompt: String, userPrompt: String): String {
        return agentChatHandler.normalChat(systemPrompt, userPrompt)
    }

    /**
     * Agent REASONING API - Uses the FIRST API key for tool execution and reasoning.
     */
    suspend fun agentReasoning(systemPrompt: String, userPrompt: String): String {
        return agentChatHandler.agentReasoning(systemPrompt, userPrompt)
    }

    /**
     * Agent FINAL RESPONSE API - Uses the FIRST API key for final user-facing response.
     */
    suspend fun agentFinalResponse(systemPrompt: String, userPrompt: String): String {
        return agentChatHandler.agentFinalResponse(systemPrompt, userPrompt)
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
