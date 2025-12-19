package com.example.smarty.data.remote

import android.util.Log
import com.example.smarty.data.local.AIProvider
import com.example.smarty.data.local.SecurePreferences
import com.example.smarty.data.remote.providers.AIProviderContract
import com.example.smarty.data.remote.providers.AnthropicProvider
import com.example.smarty.data.remote.providers.GeminiProvider
import com.example.smarty.data.remote.providers.HuggingFaceProvider
import com.example.smarty.data.remote.providers.OpenAICompatibleProvider
import com.example.smarty.data.remote.providers.OpenRouterProvider
import com.example.smarty.util.ContentSecurityFilter
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

// Response model
data class AIResponse(
    val title: String,
    val category: String,
    val summary: String,
    val whySaved: String,
    val success: Boolean = true,
    val error: String? = null
)

// Document analysis response model
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

// Gemini API models
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

// HuggingFace API models
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

// OpenAI-compatible API models (used by DeepSeek, Groq, OpenAI, OpenRouter)
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

/**
 * AI Service orchestrator that manages multiple AI providers.
 *
 * This service:
 * - Handles provider selection and fallback logic
 * - Applies security filtering before AI processing
 * - Manages retry logic with exponential backoff
 * - Delegates actual API calls to provider implementations
 *
 * Providers are tried in order: Gemini -> DeepSeek -> Groq -> OpenAI -> OpenRouter -> HuggingFace
 *
 * @property securePreferences Secure storage for API keys and settings
 * @see GeminiProvider
 * @see OpenAICompatibleProvider
 * @see OpenRouterProvider
 * @see HuggingFaceProvider
 */
class AIService(private val securePreferences: SecurePreferences) {

    private val gson = Gson()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // ==================== Provider Instances ====================

    /** Gemini API provider */
    private val geminiProvider: AIProviderContract = GeminiProvider(client, gson)

    /** DeepSeek API provider (OpenAI-compatible) */
    private val deepSeekProvider: AIProviderContract = OpenAICompatibleProvider.deepSeek(client, gson)

    /** Groq API provider (OpenAI-compatible) */
    private val groqProvider: AIProviderContract = OpenAICompatibleProvider.groq(client, gson)

    /** OpenAI API provider */
    private val openAIProvider: AIProviderContract = OpenAICompatibleProvider.openAI(client, gson)

    /** OpenRouter API provider */
    private val openRouterProvider: AIProviderContract = OpenRouterProvider(client, gson)

    /** Anthropic (Claude) API provider */
    private val anthropicProvider: AIProviderContract = AnthropicProvider(client, gson)

    /** HuggingFace Inference API provider */
    private val huggingFaceProvider: AIProviderContract = HuggingFaceProvider(client, gson)

    /**
     * Get the provider instance for an AIProvider enum value.
     */
    private fun getProvider(provider: AIProvider): AIProviderContract {
        return when (provider) {
            AIProvider.GEMINI -> geminiProvider
            AIProvider.DEEPSEEK -> deepSeekProvider
            AIProvider.GROQ -> groqProvider
            AIProvider.OPENAI -> openAIProvider
            AIProvider.ANTHROPIC -> anthropicProvider
            AIProvider.OPENROUTER -> openRouterProvider
            AIProvider.HUGGINGFACE -> huggingFaceProvider
        }
    }

    companion object {
        private const val TAG = "AIService"
        private const val MAX_RETRIES = 3
        private const val INITIAL_RETRY_DELAY_MS = 1000L

        /**
         * Optimized system prompt for note-taking app
         * Designed to be concise, clear, and produce consistent JSON output
         */
        private val SYSTEM_PROMPT = """
You are an AI assistant for a note-taking app called Cogni. Your job is to analyze user content, summarize it into a title, and categorize it.

TASK: Analyze the content and respond with a JSON object containing:
1. "title" - A short, descriptive title (4-8 words) summarizing the essence of the note.
2. "category" - A single word category (see list below)
3. "summary" - A 1-2 sentence summary capturing the key point
4. "whySaved" - 2-4 words describing why the user likely saved this

CATEGORIES (use exactly one):
- Learn: tutorials, courses, educational content
- Read: articles, blog posts, news
- Watch: videos, movies, streams
- Idea: thoughts, brainstorms, concepts
- Todo: tasks, reminders, action items
- Buy: shopping, products, wishlists
- Meet: contacts, appointments, events
- Code: programming, technical snippets
- Quote: memorable phrases, wisdom
- Inspo: creative inspiration, designs
- Recipe: food, cooking, ingredients
- Health: fitness, medical, wellness
- Finance: money, budgets, investments
- Work: professional, projects, career
- Play: entertainment, hobbies, fun
- Note: anything that doesn't fit above

RULES:
1. Respond with ONLY valid JSON, no other text
2. No markdown, no code blocks, no explanations
3. Category must be exactly one word from the list
4. Title should be punchy and clear (not "Note Title" or generic)
5. Summary should be informative and direct
6. whySaved should be brief (2-4 words)

EXAMPLE OUTPUT:
{"title":"Python Data Structures Basics","category":"Learn","summary":"Python tutorial covering data structures and algorithms basics.","whySaved":"Skill building"}

Analyze this content:
""".trimIndent()

        /**
         * Document analysis prompt for PDFs and long-form content
         * Provides comprehensive analysis with key points and action items
         */
        private val DOCUMENT_ANALYSIS_PROMPT = """
You are an intelligent document analyst for the Cogni note-taking app. Analyze the document content and provide a comprehensive summary.

TASK: Analyze the document and respond with a JSON object containing:
1. "title" - A concise descriptive title (5-10 words)
2. "summary" - A comprehensive 2-4 sentence summary of the main content
3. "keyPoints" - Array of 3-5 key takeaways (each 1 sentence)
4. "category" - One category from the list below
5. "actionItems" - Array of 0-3 potential action items the user might take based on this document
6. "userRelevance" - 1 sentence explaining why this document might be valuable to the user

CATEGORIES (use exactly one):
- Learn: educational content, tutorials, research papers
- Read: articles, reports, essays, books
- Work: business documents, reports, proposals
- Finance: financial documents, statements, budgets
- Health: medical documents, health guides
- Code: technical documentation, specifications
- Legal: contracts, agreements, legal documents
- Recipe: cooking instructions, food-related
- Note: general documents that don't fit above

RULES:
1. Respond with ONLY valid JSON, no other text
2. No markdown, no code blocks, no explanations outside JSON
3. Be insightful and identify the document's core purpose
4. Action items should be practical and specific
5. Consider what a typical user would want to remember or do with this

EXAMPLE OUTPUT:
{"title":"Q3 Financial Performance Report","summary":"Quarterly financial report showing 15% revenue growth with improved margins. The company exceeded targets in all key metrics.","keyPoints":["Revenue grew 15% YoY to $2.5M","Operating margins improved by 3%","Customer acquisition cost decreased 20%","New product line contributed 25% of revenue"],"category":"Finance","actionItems":["Review budget allocations for Q4","Schedule meeting to discuss growth strategy"],"userRelevance":"Important financial milestone showing positive business trajectory"}

Document content:
""".trimIndent()
    }

    /**
     * Get the selected model for a provider from SecurePreferences
     */
    private fun getModelForProvider(provider: AIProvider): String {
        return securePreferences.getSelectedModel(provider)
    }

    /**
     * Analyzes content using available AI providers with fallback and retry logic
     * Applies security filtering before sending to AI to prevent prompt injection
     */
    suspend fun analyzeContent(content: String): AIResponse = withContext(Dispatchers.IO) {
        Log.i(TAG, "=== Starting AI Analysis ===")
        Log.d(TAG, "Content length: ${content.length} chars")

        // SECURITY: Apply content filtering before AI processing
        val securityCheck = ContentSecurityFilter.sanitize(content)

        if (securityCheck.riskLevel == ContentSecurityFilter.RiskLevel.BLOCKED) {
            Log.w(TAG, "Content blocked by security filter")
            return@withContext AIResponse(
                title = "Content Blocked",
                category = "Note",
                summary = "Content could not be analyzed due to security concerns.",
                whySaved = "Saved note",
                success = true
            )
        }

        if (securityCheck.wasModified) {
            Log.i(TAG, "Security filter applied: ${securityCheck.detectedIssues.joinToString()}")
        }

        val sanitizedContent = securityCheck.sanitizedContent
        Log.d(TAG, "Sanitized content preview: ${sanitizedContent.take(100)}...")

        val configs = securePreferences.getAllProviderConfigs()

        // Debug: Log all provider configs
        configs.forEach { (provider, config) ->
            Log.i(TAG, "Provider $provider: enabled=${config.isEnabled}, keys=${config.apiKeys.size}")
        }

        // Try each provider in order (using user preference)
        val priority = securePreferences.getProviderPriority()
        val providersToTry = (priority + AIProvider.entries).distinct()

        for (provider in providersToTry) {
            val config = configs[provider]
            if (config == null || !config.isEnabled || config.apiKeys.isEmpty()) {
                Log.d(TAG, "$provider not available or disabled")
                continue
            }

            Log.i(TAG, "Attempting $provider API with ${config.apiKeys.size} key(s)")

            for ((index, apiKey) in config.apiKeys.withIndex()) {
                Log.d(TAG, "Trying $provider key #${index + 1}")

                val providerInstance = getProvider(provider)
                val model = getModelForProvider(provider)

                val result = tryWithRetry(MAX_RETRIES) {
                    providerInstance.analyzeContent(
                        content = sanitizedContent,
                        apiKey = apiKey,
                        model = model,
                        systemPrompt = SYSTEM_PROMPT
                    )
                }

                if (result != null && result.success) {
                    Log.i(TAG, "✓ $provider SUCCESS: category=${result.category}")
                    return@withContext result
                }
            }
            Log.w(TAG, "All $provider keys failed")
        }

        // All providers failed - use smart fallback
        Log.w(TAG, "⚠ All AI providers failed, using smart categorization")
        return@withContext smartFallbackCategorization(sanitizedContent)
    }

    /**
     * Analyzes document content (PDFs, long-form text) with comprehensive summarization
     * Returns detailed analysis including key points, action items, and relevance
     * Applies security filtering before sending to AI to prevent prompt injection
     *
     * @param documentText The extracted text from the document
     * @param fileName Optional filename for context
     * @param userContext Optional additional context about the user's intent
     */
    suspend fun analyzeDocument(
        documentText: String,
        fileName: String? = null,
        userContext: String? = null
    ): DocumentAnalysisResponse = withContext(Dispatchers.IO) {
        Log.i(TAG, "=== Starting Document Analysis ===")
        Log.d(TAG, "Document length: ${documentText.length} chars")
        Log.d(TAG, "Filename: $fileName")

        // SECURITY: Apply content filtering before AI processing
        val securityCheck = ContentSecurityFilter.sanitize(documentText)

        if (securityCheck.riskLevel == ContentSecurityFilter.RiskLevel.BLOCKED) {
            Log.w(TAG, "Document content blocked by security filter")
            return@withContext DocumentAnalysisResponse(
                title = fileName ?: "Document",
                summary = "Document could not be analyzed due to security concerns.",
                keyPoints = emptyList(),
                category = "Note",
                actionItems = emptyList(),
                userRelevance = "Document saved for reference",
                success = true
            )
        }

        if (securityCheck.wasModified) {
            Log.i(TAG, "Document security filter applied: ${securityCheck.detectedIssues.joinToString()}")
        }

        val sanitizedDocumentText = securityCheck.sanitizedContent

        // Also sanitize user context if provided
        val sanitizedUserContext = userContext?.let {
            val contextCheck = ContentSecurityFilter.sanitize(it)
            if (contextCheck.riskLevel == ContentSecurityFilter.RiskLevel.BLOCKED) null
            else contextCheck.sanitizedContent
        }

        val configs = securePreferences.getAllProviderConfigs()

        // Build enhanced prompt with context
        val contextPrefix = buildString {
            if (fileName != null) {
                // Sanitize filename to prevent injection via filename
                val safeFileName = fileName.replace(Regex("""[<>{}|\\^`\[\]]"""), "_")
                append("Document filename: $safeFileName\n")
            }
            if (sanitizedUserContext != null) {
                append("User's context/intent: $sanitizedUserContext\n")
            }
            append("\n")
        }

        val fullContent = contextPrefix + sanitizedDocumentText

        // Try each provider in order (using user preference)
        val priority = securePreferences.getProviderPriority()
        val providersToTry = (priority + AIProvider.entries).distinct()

        for (provider in providersToTry) {
            val config = configs[provider]
            if (config == null || !config.isEnabled || config.apiKeys.isEmpty()) {
                Log.d(TAG, "$provider not available for document analysis")
                continue
            }

            Log.i(TAG, "Attempting document analysis with $provider")

            for (apiKey in config.apiKeys) {
                val providerInstance = getProvider(provider)
                val model = getModelForProvider(provider)

                val result = tryDocumentAnalysisWithRetry(MAX_RETRIES) {
                    providerInstance.analyzeDocument(
                        content = fullContent,
                        apiKey = apiKey,
                        model = model,
                        systemPrompt = DOCUMENT_ANALYSIS_PROMPT
                    )
                }

                if (result != null && result.success) {
                    Log.i(TAG, "✓ Document analysis SUCCESS via $provider: ${result.title}")
                    return@withContext result
                }
            }
            Log.w(TAG, "All $provider keys failed for document analysis")
        }

        // Fallback response when AI is unavailable
        Log.w(TAG, "⚠ All providers failed for document analysis, using fallback")
        return@withContext DocumentAnalysisResponse(
            title = fileName ?: "Document",
            summary = "Document saved. AI analysis unavailable - please configure API keys in settings.",
            keyPoints = listOf("Document contains ${documentText.length} characters"),
            category = inferCategoryFromText(sanitizedDocumentText),
            actionItems = emptyList(),
            userRelevance = "Document saved for future reference",
            success = true
        )
    }

    /**
     * Retry helper for document analysis
     */
    private suspend fun tryDocumentAnalysisWithRetry(
        maxRetries: Int,
        action: suspend () -> DocumentAnalysisResponse?
    ): DocumentAnalysisResponse? {
        for (attempt in 1..maxRetries) {
            try {
                val result = action()
                if (result != null && result.success) {
                    return result
                }
            } catch (e: Exception) {
                Log.e(TAG, "Document analysis attempt $attempt failed: ${e.message}")
            }

            if (attempt < maxRetries) {
                delay(INITIAL_RETRY_DELAY_MS * attempt)
            }
        }
        return null
    }

    // Delegate document analysis response parsing to AIResponseParser
    private fun parseDocumentAnalysisResponse(responseBody: String?): DocumentAnalysisResponse? =
        AIResponseParser.parseDocumentAnalysisResponse(responseBody)

    // Delegate document analysis text parsing to AIResponseParser
    private fun parseDocumentAnalysisFromText(text: String?): DocumentAnalysisResponse? =
        AIResponseParser.parseDocumentAnalysisFromText(text)

    // Delegate category inference to AIResponseParser
    private fun inferCategoryFromText(text: String): String =
        AIResponseParser.inferCategoryFromText(text)

    /**
     * Retry helper with exponential backoff
     */
    private suspend fun tryWithRetry(maxRetries: Int, action: suspend () -> AIResponse?): AIResponse? {
        var lastException: Exception? = null

        for (attempt in 1..maxRetries) {
            try {
                val result = action()
                if (result != null && result.success) {
                    return result
                }
                if (result?.error != null) {
                    Log.w(TAG, "Attempt $attempt failed: ${result.error}")
                }
            } catch (e: Exception) {
                lastException = e
                Log.e(TAG, "Attempt $attempt exception: ${e.message}")
            }

            if (attempt < maxRetries) {
                val delayMs = INITIAL_RETRY_DELAY_MS * attempt
                Log.d(TAG, "Retrying in ${delayMs}ms...")
                delay(delayMs)
            }
        }

        lastException?.let { Log.e(TAG, "All retries failed", it) }
        return null
    }

    // Delegate fallback categorization to AIResponseParser
    private fun smartFallbackCategorization(content: String): AIResponse =
        AIResponseParser.smartFallbackCategorization(content)

    /**
     * Agent chat method for conversational AI interactions
     * Uses higher token limits and temperature for more natural responses
     * Returns raw AI response for AgentService to parse
     *
     * @param systemPrompt The system instructions for the agent
     * @param userPrompt The user message with context
     * @return Raw AI response string
     */
    suspend fun agentChat(systemPrompt: String, userPrompt: String): String = withContext(Dispatchers.IO) {
        Log.i(TAG, "=== Starting Agent Chat ===")
        Log.d(TAG, "User prompt length: ${userPrompt.length} chars")

        val configs = securePreferences.getAllProviderConfigs()

        // Try each provider in order (using user preference)
        val priority = securePreferences.getProviderPriority()
        val providersToTry = (priority + AIProvider.entries).distinct()

        for (provider in providersToTry) {
            val config = configs[provider]
            if (config == null || !config.isEnabled || config.apiKeys.isEmpty()) {
                Log.d(TAG, "$provider not available or disabled for agent chat")
                continue
            }

            Log.i(TAG, "Attempting agent chat with $provider")

            for (apiKey in config.apiKeys) {
                val providerInstance = getProvider(provider)
                val model = getModelForProvider(provider)

                val result = tryAgentChatWithRetry(MAX_RETRIES) {
                    providerInstance.chat(
                        systemPrompt = systemPrompt,
                        userPrompt = userPrompt,
                        apiKey = apiKey,
                        model = model
                    )
                }

                if (result != null) {
                    Log.i(TAG, "✓ Agent chat SUCCESS via $provider")
                    return@withContext result
                }
            }
            Log.w(TAG, "All $provider keys failed for agent chat")
        }

        // Fallback response when AI is unavailable
        Log.w(TAG, "⚠ All providers failed for agent chat")
        return@withContext """{"action": "ANSWER_QUESTION", "params": {"question": ""}, "response": "I'm sorry, I couldn't process your request. Please check your API configuration in settings."}"""
    }

    /**
     * Retry helper for agent chat
     */
    private suspend fun tryAgentChatWithRetry(
        maxRetries: Int,
        action: suspend () -> String?
    ): String? {
        for (attempt in 1..maxRetries) {
            try {
                val result = action()
                if (!result.isNullOrBlank()) {
                    return result
                }
            } catch (e: Exception) {
                Log.e(TAG, "Agent chat attempt $attempt failed: ${e.message}")
            }

            if (attempt < maxRetries) {
                delay(INITIAL_RETRY_DELAY_MS * attempt)
            }
        }
        return null
    }

    /**
     * Test if an API key is valid by making a simple analysis request.
     *
     * @param provider The AI provider to test
     * @param apiKey The API key to validate
     * @return True if the API key is valid and working
     */
    suspend fun testApiKey(provider: AIProvider, apiKey: String): Boolean = withContext(Dispatchers.IO) {
        Log.i(TAG, "Testing $provider API key...")

        try {
            val providerInstance = getProvider(provider)
            val model = getModelForProvider(provider)
            val testContent = "Test: Remember to buy groceries tomorrow"

            val result = providerInstance.analyzeContent(
                content = testContent,
                apiKey = apiKey,
                model = model,
                systemPrompt = SYSTEM_PROMPT
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
