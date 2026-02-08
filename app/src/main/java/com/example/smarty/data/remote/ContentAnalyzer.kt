package com.example.smarty.data.remote

import android.app.Application
import android.util.Log
import com.example.smarty.R
import com.example.smarty.data.local.AIConnection
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
 * @property orchestrator Connection orchestrator for API calls
 */
class ContentAnalyzer(private val application: Application, private val orchestrator: AIConnectionOrchestrator) {

    companion object {
        private const val TAG = "ContentAnalyzer"

        /**
         * Optimized system prompt for note-taking app using JSON format.
         */
        val SYSTEM_PROMPT = """
            <identity>
                You are Smarty's Architect. Your goal is to transform raw notes into precise, searchable metadata with a calm, professional tone.
            </identity>

            <objective>
                Extract high-signal metadata. If content is low-value (gibberish, trivial), return "low_value" in the summary.
            </objective>

            <directives>
                1. SEARCHABLE_TITLE: 4-7 keywords the user would search for.
                2. SELECTIVE_SUMMARY: 1-3 lines of unique value. No fluff.
                3. PRECITE_CATEGORY: Single one-word category (e.g., react_native, finance, recipe). Use snake_case.
                4. ACTION_ITEMS: Extract tasks only if explicit; otherwise return empty array.
            </directives>

            <tone_and_style>
                - Prefer lowercase for summaries and category names.
                - NO markdown headers or bolding.
                - NO social commentary.
                - STRICT JSON format.
            </tone_and_style>

            <output_format>
                Return a single JSON object with these keys:
                {
                    "title": "[Keywords]",
                    "category": "[topic_name]",
                    "summary": "[insight in lowercase]",
                    "whySaved": "[purpose]",
                    "todos": ["task1", "task2"]
                }
            </output_format>

            <examples>
                <example>
                    Input: "How to fix the 404 error on nginx: check the config file in /etc/nginx/sites-available and ensure symbolik link exists."
                    Output:
                    {
                        "title": "Nginx 404 Error Configuration and Symbolic Links",
                        "category": "devops",
                        "summary": "troubleshooting steps for nginx 404 errors by verifying configs and symlink integrity.",
                        "whySaved": "Troubleshooting reference",
                        "todos": ["verify symlinks in production"]
                    }
                </example>
                <example>
                    Input: "asdfghjkl"
                    Output:
                    {
                        "title": "Unstructured Input",
                        "category": "note",
                        "summary": "low-value content",
                        "whySaved": "Junk filter",
                        "todos": []
                    }
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
                You are Smarty's Deep Analyst. You specialize in synthesizing complex documents into high-density insights with a calm, professional tone.
            </identity>

            <objective>
                Analyze the document and produce a structured JSON report that highlights technical depth and actionable takeaways.
            </objective>

            <directives>
                1. FORMULA_PRECISION: Explicitly extract ALL mathematical or chemical formulas.
                2. TECHNICAL_GLOSSARY: Identify 3-7 core technical terms and provide concise definitions.
                3. STYLE: Use lowercase for summaries and recurring topics. Avoid large headers.
            </directives>

            <constraints>
                - Output MUST be a single JSON object.
                - NO markdown code blocks (```json).
            </constraints>

            <output_template>
                {
                  "title": "Searchable Title",
                  "summary": "concise lowercase overview",
                  "keyPoints": ["takeaway one", "takeaway two"],
                  "category": "topic_name",
                  "actionItems": ["next step"],
                  "userRelevance": "strategic value",
                  "references": {
                    "formulas": ["..."],
                    "keyTerms": [{"term": "...", "definition": "..."}],
                    "recurringTopics": ["topic_a", "topic_b"]
                  }
                }
            </output_template>
        """.trimIndent()
    }

    /**
     * Analyzes content using available AI connections with fallback and retry logic.
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
                title = application.getString(R.string.content_blocked),
                category = application.getString(R.string.stack),
                summary = application.getString(R.string.error_security_blocked),
                whySaved = application.getString(R.string.save_note),
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

        // Thin Client: Only attempt LOCAL_PC if enabled
        if (orchestrator.getOrderedConnections().contains(AIConnection.LOCAL_PC)) {
            val connection = AIConnection.LOCAL_PC
            val connectionInstance = orchestrator.getConnection(connection)
            val model = orchestrator.getModelForConnection(connection)

            val result = orchestrator.executeWithContentAnalysisRetry(application) { connectionToken ->
                connectionInstance.analyzeContent(
                    context = application,
                    content = contentWithMetadata,
                    connectionToken = connectionToken,
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

        // All local connections (LOCAL_PC) failed or were disabled - use smart fallback
        Log.w(TAG, "Local AI unavailable, using smart categorization fallback")
        val fallbackResponse = AIResponseParser.smartFallbackCategorization(application, contentWithMetadata)
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
                title = fileName ?: application.getString(R.string.document),
                summary = application.getString(R.string.error_security_blocked),
                keyPoints = emptyList(),
                category = application.getString(R.string.stack),
                actionItems = emptyList(),
                userRelevance = application.getString(R.string.visit_original_source),
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

        // Thin Client: Only attempt LOCAL_PC if enabled
        if (orchestrator.getOrderedConnections().contains(AIConnection.LOCAL_PC)) {
            val connection = AIConnection.LOCAL_PC
            val connectionInstance = orchestrator.getConnection(connection)
            val model = orchestrator.getModelForConnection(connection)

            val result = orchestrator.executeWithDocumentAnalysisRetry(application) { connectionToken ->
                connectionInstance.analyzeDocument(
                    context = application,
                    content = fullContent,
                    connectionToken = connectionToken,
                    model = model,
                    systemPrompt = DOCUMENT_ANALYSIS_PROMPT
                )
            }

            if (result != null) {
                return@withContext result
            }
        }

        // Fallback response when local AI is unavailable
        Log.w(TAG, "Local AI unavailable for document analysis, using fallback")
        return@withContext DocumentAnalysisResponse(
            title = fileName ?: application.getString(R.string.document),
            summary = application.getString(R.string.error_ai_unavailable_connection),
            keyPoints = listOf(application.getString(R.string.x_attachments, 1)), // Use 1 as dummy count
            category = AIResponseParser.validateCategory(null), // Use validateCategory instead of infer
            actionItems = emptyList(),
            userRelevance = application.getString(R.string.visit_original_source),
            success = true
        )
    }
}
