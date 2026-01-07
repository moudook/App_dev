package com.example.smarty.data.remote

import android.util.Log
import com.example.smarty.data.cache.AIResponseCache
import com.example.smarty.data.model.AttachmentMetadata
import com.example.smarty.util.ContentSecurityFilter
import com.example.smarty.util.api.ApiMetrics
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
         * Optimized system prompt for note-taking app using TOON format.
         * More compact and token-efficient than JSON.
         */
        /**
         * Optimized system prompt for note-taking app using TOON format.
         * More compact and token-efficient than JSON.
         */
        val SYSTEM_PROMPT = """
            <identity>
                You are a High-Signal Information Architect. Your goal is to transform raw user notes into precise, searchable, and structured metadata.
            </identity>

            <objective>
                Extract metadata that maximizes the utility of the note in a long-term knowledge base.
                If the content is low-value junk (gibberish, random keys, trivial single words), you MUST return "Low-value content" in the summary.
            </objective>

            <metadata_directives>
                1. SEARCHABLE_TITLE: Create a 4-7 word title using keywords the user would naturally search for. Avoid generic phrases.
                2. SELECTIVE_SUMMARY: Extract the "meat" only. 1-3 lines focusing on unique value. Ignore fluff.
                3. PRECITE_CATEGORY: Single one-word category (e.g., React, Linux, Finance, Recipe). Default to 'Note' only if absolutely no topic exists.
                4. ACTION_IDENTIFICATION: Extract comma-separated tasks ONLY if explicitly stated; otherwise return "none".
            </metadata_directives>

            <constraints>
                - NO markdown headers or bolding.
                - NO social commentary or affirmations.
                - STRICT adherence to the field format below.
            </constraints>

            <output_format>
                title: [Keywords For Search]
                category: [Topic]
                summary: [High-signal insight]
                whySaved: [Strategic purpose]
                todos: [Actionable tasks/none]
            </output_format>

            <examples>
                <example>
                    Input: "How to fix the 404 error on nginx: check the config file in /etc/nginx/sites-available and ensure symbolik link exists."
                    Output:
                    title: Nginx 404 Error Configuration and Symbolic Links
                    category: DevOps
                    summary: Troubeshooting steps for Nginx 404 errors by verifying site-available configs and symlink integrity.
                    whySaved: Troubleshooting reference
                    todos: verify symlinks in production
                </example>
                <example>
                    Input: "asdfghjkl"
                    Output:
                    title: Unstructured Input
                    category: Note
                    summary: Low-value content
                    whySaved: Junk filter
                    todos: none
                </example>
            </examples>
        """.trimIndent()

        /**
         * Document analysis prompt for PDFs and long-form content.
         * Provides comprehensive analysis with key points, action items,
         * and explicit references (formulas, key terms, recurring topics).
         */
        val DOCUMENT_ANALYSIS_PROMPT = """
            <identity>
                You are a Deep Context Document Analyst. You specialize in synthesizing complex documents into high-density insights for a professional knowledge base.
            </identity>

            <objective>
                Analyze the document and produce a structured JSON report that highlights technical depth, recurring patterns, and actionable takeaways.
            </objective>

            <extraction_rules>
                1. FORMULA_PRECISION: Explicitly extract ALL mathematical or chemical formulas (e.g., "E = mc²", "ΔH = ...").
                2. SIGNAL_T0_NOISE: The summary and key points must reflect the document's unique value, not its table of contents.
                3. TECHNICAL_GLOSSARY: Identify 3-7 core technical terms and provide concise, functional definitions.
                4. RECURRING_THEMES: Identify patterns mentioned in multiple sections of the document.
            </extraction_rules>

            <constraints>
                - Output MUST be a single JSON object.
                - NO markdown code blocks (```json).
                - NO pre/post-text.
            </constraints>

            <output_template>
                {
                  "title": "Searchable, Descriptive Title",
                  "summary": "2-4 sentence high-signal overview",
                  "keyPoints": ["Takeaway 1", "Takeaway 2"],
                  "category": "OneWordTopic",
                  "actionItems": ["Next Step 1"],
                  "userRelevance": "Strategic value for the user",
                  "references": {
                    "formulas": ["..."],
                    "keyTerms": [{"term": "...", "definition": "..."}],
                    "recurringTopics": ["..."]
                  }
                }
            </output_template>

            <example>
                Input Document: [Technical whitepaper on Solid State Batteries...]
                Output: {
                  "title": "Solid State Battery Electrolyte Efficiency and Thermal Stability",
                  "summary": "An analysis of sulfide-based solid electrolytes in high-capacity batteries, focusing on interface stability and ionic conductivity improvements.",
                  "keyPoints": ["Interface resistance is the primary barrier to high discharge rates", "Sulfide electrolytes offer 10x conductivity over polymers"],
                  "category": "Physics",
                  "actionItems": ["Research sulfide interface coatings"],
                  "userRelevance": "Core reference for energy storage projects",
                  "references": {
                    "formulas": ["σ = Ae^(-Ea/kT)"],
                    "keyTerms": [{"term": "Solid Electrolyte Interphase", "definition": "Passivation layer formed on the electrode"}],
                    "recurringTopics": ["Thermal management", "Ionic conductivity", "Manufacturing scalability"]
                  }
                }
            </example>
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
            ApiMetrics.recordCacheResult(true)
            return@withContext cachedResponse
        }
        ApiMetrics.recordCacheResult(false)

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
