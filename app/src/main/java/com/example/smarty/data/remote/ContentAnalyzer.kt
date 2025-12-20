package com.example.smarty.data.remote

import android.util.Log
import com.example.smarty.data.cache.AIResponseCache
import com.example.smarty.data.model.AttachmentMetadata
import com.example.smarty.util.ContentSecurityFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Handles content and document analysis operations.
 *
 * Responsibilities:
 * - Note content analysis (title, category, summary)
 * - Document analysis (PDFs, long-form content)
 * - Security filtering before AI processing
 *
 * @property orchestrator Provider orchestrator for API calls
 */
class ContentAnalyzer(private val orchestrator: AIProviderOrchestrator) {

    companion object {
        private const val TAG = "ContentAnalyzer"

        /**
         * Optimized system prompt for note-taking app.
         * Designed to be concise, clear, and produce consistent JSON output.
         */
        val SYSTEM_PROMPT = """
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
         * Document analysis prompt for PDFs and long-form content.
         * Provides comprehensive analysis with key points and action items.
         */
        val DOCUMENT_ANALYSIS_PROMPT = """
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
     * Analyzes content using available AI providers with fallback and retry logic.
     * Applies security filtering before sending to AI to prevent prompt injection.
     * Uses caching to minimize redundant API calls for similar content.
     *
     * @param content The text content to analyze
     * @param attachmentMetadata Optional list of attachment metadata (file names and types only)
     */
    suspend fun analyzeContent(
        content: String,
        attachmentMetadata: List<AttachmentMetadata>? = null
    ): AIResponse = withContext(Dispatchers.IO) {
        Log.i(TAG, "=== Starting AI Analysis ===")
        Log.d(TAG, "Content length: ${content.length} chars")
        Log.d(TAG, "Attachments: ${attachmentMetadata?.size ?: 0}")

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

        // Build content with attachment metadata if available
        val contentWithMetadata = if (attachmentMetadata.isNullOrEmpty()) {
            sanitizedContent
        } else {
            val attachmentsSection = attachmentMetadata.mapIndexed { index, meta ->
                "${index + 1}. ${meta.fileName} (${meta.fileType})"
            }.joinToString("\n")

            "$sanitizedContent\n\n---\nAttached Files:\n$attachmentsSection"
        }

        // Check cache first to avoid redundant API calls
        val cacheKey = AIResponseCache.generateKey(contentWithMetadata)
        AIResponseCache.get(cacheKey)?.let { cachedResponse ->
            Log.i(TAG, "Returning cached AI response")
            return@withContext cachedResponse
        }

        Log.d(TAG, "Content preview: ${contentWithMetadata.take(100)}...")

        val configs = orchestrator.getAllProviderConfigs()

        // Debug: Log all provider configs
        configs.forEach { (provider, config) ->
            Log.i(TAG, "Provider $provider: enabled=${config.isEnabled}, keys=${config.apiKeys.size}")
        }

        // Try each provider in order
        for (provider in orchestrator.getOrderedProviders()) {
            val config = configs[provider] ?: continue
            if (!orchestrator.isProviderAvailable(config)) {
                Log.d(TAG, "$provider not available or disabled")
                continue
            }

            val providerInstance = orchestrator.getProvider(provider)
            val model = orchestrator.getModelForProvider(provider)

            val result = orchestrator.executeWithContentAnalysisRetry(provider, config) { apiKey ->
                providerInstance.analyzeContent(
                    content = contentWithMetadata,
                    apiKey = apiKey,
                    model = model,
                    systemPrompt = SYSTEM_PROMPT
                )
            }

            if (result != null) {
                // Cache successful response
                AIResponseCache.put(cacheKey, result)
                return@withContext result
            }
        }

        // All providers failed - use smart fallback
        Log.w(TAG, "⚠ All AI providers failed, using smart categorization")
        val fallbackResponse = AIResponseParser.smartFallbackCategorization(contentWithMetadata)
        // Cache fallback response too to avoid repeated failures
        AIResponseCache.put(cacheKey, fallbackResponse)
        return@withContext fallbackResponse
    }

    /**
     * Analyzes document content (PDFs, long-form text) with comprehensive summarization.
     * Returns detailed analysis including key points, action items, and relevance.
     * Applies security filtering before sending to AI to prevent prompt injection.
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

        val configs = orchestrator.getAllProviderConfigs()

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

        // Try each provider in order
        for (provider in orchestrator.getOrderedProviders()) {
            val config = configs[provider] ?: continue
            if (!orchestrator.isProviderAvailable(config)) {
                Log.d(TAG, "$provider not available for document analysis")
                continue
            }

            val providerInstance = orchestrator.getProvider(provider)
            val model = orchestrator.getModelForProvider(provider)

            val result = orchestrator.executeWithDocumentAnalysisRetry(provider, config) { apiKey ->
                providerInstance.analyzeDocument(
                    content = fullContent,
                    apiKey = apiKey,
                    model = model,
                    systemPrompt = DOCUMENT_ANALYSIS_PROMPT
                )
            }

            if (result != null) {
                return@withContext result
            }
        }

        // Fallback response when AI is unavailable
        Log.w(TAG, "⚠ All providers failed for document analysis, using fallback")
        return@withContext DocumentAnalysisResponse(
            title = fileName ?: "Document",
            summary = "Document saved. AI analysis unavailable - please configure API keys in settings.",
            keyPoints = listOf("Document contains ${documentText.length} characters"),
            category = AIResponseParser.inferCategoryFromText(sanitizedDocumentText),
            actionItems = emptyList(),
            userRelevance = "Document saved for future reference",
            success = true
        )
    }
}
