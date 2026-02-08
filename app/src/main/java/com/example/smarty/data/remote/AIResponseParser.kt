package com.example.smarty.data.remote

import android.util.Log
import com.google.gson.JsonParser

/**
 * Utility object for parsing AI responses from standardized compatible providers.
 *
 * This parser primarily handles responses from the Local LLM server and
 * other standardized endpoints used in the Thin Client architecture.
 *
 * It extracts JSON from AI response text, handles markdown code blocks,
 * and provides fallback categorization when AI is unavailable.
 *
 * Usage:
 * ```kotlin
 * val response = AIResponseParser.extractAndParseJson(aiText)
 * // Returns AIResponse with category, summary, whySaved
 *
 * val docAnalysis = AIResponseParser.parseDocumentAnalysisFromText(docText)
 * // Returns DocumentAnalysisResponse with title, summary, keyPoints, etc.
 * ```
 */
object AIResponseParser {

    private const val TAG = "AIResponseParser"

    // BUG-035: Maximum response length to prevent memory issues
    private const val MAX_RESPONSE_LENGTH = 100_000 // 100KB should be more than enough for any response

    // Pre-compiled regex patterns for performance
    private val WHITESPACE_PATTERN = Regex("\\s+")
    private val TODO_PATTERN = Regex("""(?i)\b(todo|task|remind|remember|don'?t forget|need to|must|should|deadline|due)\b""")
    private val IDEA_PATTERN = Regex("""(?i)\b(idea|thought|maybe|what if|could|concept|brainstorm|imagine)\b""")
    private val LEARN_PATTERN = Regex("""(?i)\b(learn|study|tutorial|course|lesson|how to|guide|understand)\b""")
    private val BUY_PATTERN = Regex("""(?i)\b(buy|purchase|order|price|shop|cart|deal|discount|sale)\b""")
    private val MEET_PATTERN = Regex("""(?i)\b(meet|meeting|call|schedule|appointment|calendar|event)\b""")
    private val CODE_PATTERN = Regex("""(?i)(```|function|class |def |const |let |var |import |export |=>|->|\{\{|\}\})""")
    private val QUOTE_PATTERN = Regex("""(?i)[""].*[""]|[''].*['']|\bsaid\b|\bquote\b""")
    private val HEALTH_PATTERN = Regex("""(?i)\b(health|workout|exercise|diet|fitness|calories|sleep|medicine|doctor)\b""")
    private val FINANCE_PATTERN = Regex("""(?i)\b(money|budget|invest|savings|expense|income|stock|crypto|bank)\b""")
    private val RECIPE_PATTERN = Regex("""(?i)\b(recipe|cook|ingredient|bake|tablespoon|cup|oven|minutes at)\b""")
    private val WORK_PATTERN = Regex("""(?i)\b(project|client|deadline|report|presentation|manager|team|office)\b""")

    // ==================== Valid Categories ====================

    /**
     * List of valid categories that AI should use.
     * Used for validation and fallback logic.
     */
    val VALID_CATEGORIES = setOf(
        "learn", "read", "watch", "idea", "todo", "buy", "meet",
        "code", "quote", "inspo", "recipe", "health", "finance",
        "work", "play", "note", "legal"
    )

    // ==================== JSON Extraction ====================

    /**
     * Helper to extract valid JSON string from text that might contain markdown or extra whitespace.
     */
    private fun extractJsonObject(text: String): String? {
        var cleanText = text.trim()

        // Handle ```json ... ``` format
        if (cleanText.contains("```json")) {
            cleanText = cleanText.substringAfter("```json").substringBefore("```")
        } else if (cleanText.contains("```")) {
            cleanText = cleanText.substringAfter("```").substringBefore("```")
        }

        val jsonStart = cleanText.indexOf("{")
        val jsonEnd = cleanText.lastIndexOf("}")

        if (jsonStart == -1 || jsonEnd == -1 || jsonEnd <= jsonStart) {
            return null
        }

        return cleanText.substring(jsonStart, jsonEnd + 1)
    }

    /**
     * Extract JSON from AI response text and parse it into AIResponse.
     *
     * Handles various AI output formats:
     * - Raw JSON: `{"category":"Learn",...}`
     * - Markdown code blocks: ```json\n{...}\n```
     * - Mixed text with embedded JSON
     *
     * @param context Android context for localization
     * @param text The raw AI response text
     * @return Parsed AIResponse or null if parsing fails
     */
    fun extractAndParseJson(context: android.content.Context, text: String): AIResponse? {
        // BUG-035: Truncate excessively long responses to prevent memory issues
        val safeText = if (text.length > MAX_RESPONSE_LENGTH) {
            Log.w(TAG, "Response truncated from ${text.length} to $MAX_RESPONSE_LENGTH chars")
            text.take(MAX_RESPONSE_LENGTH)
        } else {
            text
        }

        val jsonStr = extractJsonObject(safeText) ?: run {
            Log.w(TAG, "No valid JSON found in: ${safeText.take(100)}")
            return null
        }

        Log.d(TAG, "Extracted JSON: ${jsonStr.take(200)}")

        return try {
            val parsed = JsonParser.parseString(jsonStr).asJsonObject

            // Extract todos array safely
            val todos = try {
                parsed.getAsJsonArray("todos")?.map { it.asString } ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }

            AIResponse(
                title = parsed.get("title")?.asString?.trim() ?: context.getString(com.example.smarty.R.string.untitled_note),
                category = validateCategory(parsed.get("category")?.asString?.trim()),
                summary = cleanSummary(context, parsed.get("summary")?.asString?.trim()),
                whySaved = parsed.get("whySaved")?.asString?.trim() ?: context.getString(com.example.smarty.R.string.mock_ai_intent_note),
                success = true,
                todos = todos
            )
        } catch (e: Exception) {
            Log.e(TAG, "JSON parse error: ${e.message}")
            null
        }
    }

    // ==================== Compatible Response Parsing ====================

    /**
     * Parse standardized API response.
     *
     * Works with Local PC and server-managed LLMs.
     *
     * Response structure:
     * ```json
     * {
     *   "choices": [{
     *     "message": { "content": "..." }
     *   }]
     * }
     * ```
     *
     * @param context Android context for localization
     * @param responseBody The raw HTTP response body
     * @param providerName Name of the provider for logging
     * @return Parsed AIResponse or null if parsing fails
     */
    fun parseCompatibleResponse(context: android.content.Context, responseBody: String?, providerName: String): AIResponse? {
        if (responseBody.isNullOrBlank()) {
            Log.e(TAG, "Empty $providerName response")
            return null
        }

        return try {
            Log.d(TAG, "Parsing $providerName response: ${responseBody.take(500)}")

            val json = JsonParser.parseString(responseBody).asJsonObject

            // Check for error
            if (json.has("error")) {
                val errorMsg = json.getAsJsonObject("error")?.get("message")?.asString
                Log.e(TAG, "$providerName API error: $errorMsg")
                return null
            }

            val choices = json.getAsJsonArray("choices")
            if (choices == null || choices.size() == 0) {
                Log.w(TAG, "No choices in $providerName response")
                return null
            }

            val message = choices[0].asJsonObject.getAsJsonObject("message")
            val text = message?.get("content")?.asString

            Log.d(TAG, "$providerName text output: ${text?.take(200)}")

            extractAndParseJson(context, text ?: "")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse $providerName response: ${e.message}", e)
            return null
        }
    }

    // ==================== Document Analysis Parsing ====================

    /**
     * Parse document analysis JSON from AI response text.
     *
     * Expected JSON structure:
     * ```json
     * {
     *   "title": "Document Title",
     *   "summary": "Summary of the document...",
     *   "keyPoints": ["Point 1", "Point 2"],
     *   "category": "Work",
     *   "actionItems": ["Action 1"],
     *   "userRelevance": "Why this matters..."
     * }
     * ```
     *
     * @param context Android context for localization
     * @param text The AI response text containing JSON
     * @return Parsed DocumentAnalysisResponse or null if parsing fails
     */
    fun parseDocumentAnalysisFromText(context: android.content.Context, text: String?): DocumentAnalysisResponse? {
        if (text.isNullOrBlank()) return null

        val jsonStr = extractJsonObject(text) ?: return null

        return try {
            val parsed = JsonParser.parseString(jsonStr).asJsonObject

            val keyPoints = parsed.getAsJsonArray("keyPoints")?.map { it.asString } ?: emptyList()
            val actionItems = parsed.getAsJsonArray("actionItems")?.map { it.asString } ?: emptyList()

            DocumentAnalysisResponse(
                title = parsed.get("title")?.asString?.trim() ?: context.getString(com.example.smarty.R.string.untitled_note),
                summary = parsed.get("summary")?.asString?.trim() ?: context.getString(com.example.smarty.R.string.mock_ai_summary_note),
                keyPoints = keyPoints,
                category = parsed.get("category")?.asString?.trim() ?: "note",
                actionItems = actionItems,
                userRelevance = parsed.get("userRelevance")?.asString?.trim() ?: context.getString(com.example.smarty.R.string.mock_ai_intent_note),
                success = true
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse document analysis JSON: ${e.message}")
            null
        }
    }

    // ==================== Agent Response Parsing ====================

    /**
     * Extract text content from compatible response for agent chat.
     *
     * @param responseBody The raw HTTP response body
     * @param providerName Name of the provider for logging
     * @return Extracted text content or null
     */
    fun extractCompatibleAgentText(responseBody: String?, providerName: String): String? {
        if (responseBody.isNullOrBlank()) return null

        return try {
            val json = JsonParser.parseString(responseBody).asJsonObject

            if (json.has("error")) {
                Log.e(TAG, "$providerName agent error: ${json.getAsJsonObject("error")}")
                return null
            }

            val choices = json.getAsJsonArray("choices")
            if (choices == null || choices.size() == 0) return null

            choices[0].asJsonObject
                .getAsJsonObject("message")
                ?.get("content")?.asString
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract $providerName agent text: ${e.message}")
            null
        }
    }

    /**
     * Infer category from document text.
     * Used by AIService for simple category inference.
     *
     * @param text The text to analyze
     * @return Inferred category name
     */
    fun inferCategoryFromText(context: android.content.Context, text: String): String {
        val lower = text.lowercase()
        val urlCat = categorizeByUrl(context, lower)
        if (urlCat != null) return urlCat.first
        return categorizeByKeywords(context, lower).first
    }

    /**
     * Smart fallback categorization using keyword and URL analysis.
     * Used when all AI providers fail.
     *
     * Analyzes:
     * 1. Common URL patterns
     * 2. Keyword patterns (todo, idea, learn, buy, etc.)
     *
     * @param context Android context for resource resolution
     * @param content The content to categorize
     * @return AIResponse with categorization and summary
     */
    fun smartFallbackCategorization(context: android.content.Context, content: String): AIResponse {
        val lower = content.lowercase()

        // Generate a fallback title
        val fallbackTitle = com.example.smarty.util.ContentTypeDetector.extractTitle(
            context,
            content,
            com.example.smarty.util.ContentTypeDetector.detectContentType(content)
        )

        // URL-based categorization
        val urlCategory = categorizeByUrl(context, lower)
        if (urlCategory != null) {
            return AIResponse(
                title = fallbackTitle,
                category = urlCategory.first,
                summary = urlCategory.second,
                whySaved = urlCategory.third,
                success = true
            )
        }

        // Keyword-based categorization
        val keywordCategory = categorizeByKeywords(context, lower)
        return AIResponse(
            title = fallbackTitle,
            category = keywordCategory.first,
            summary = keywordCategory.second,
            whySaved = keywordCategory.third,
            success = true
        )
    }

    /**
     * Categorize content based on URL patterns.
     *
     * @param context Android context for localization
     * @param lower Lowercase content string
     * @return Triple of (category, summary, whySaved) or null
     */
    private fun categorizeByUrl(context: android.content.Context, lower: String): Triple<String, String, String>? {
        return when {
            lower.contains("youtube.com") || lower.contains("youtu.be") ->
                Triple(
                    context.getString(com.example.smarty.R.string.mock_ai_tag_watch),
                    context.getString(com.example.smarty.R.string.mock_ai_summary_watch),
                    context.getString(com.example.smarty.R.string.mock_ai_intent_watch)
                )
            lower.contains("twitter.com") || lower.contains("x.com") ->
                Triple(
                    context.getString(com.example.smarty.R.string.mock_ai_tag_tweet),
                    context.getString(com.example.smarty.R.string.mock_ai_summary_tweet),
                    context.getString(com.example.smarty.R.string.mock_ai_intent_tweet)
                )
            lower.contains("instagram.com") ->
                Triple(
                    context.getString(com.example.smarty.R.string.mock_ai_tag_inspo),
                    context.getString(com.example.smarty.R.string.mock_ai_summary_inspo),
                    context.getString(com.example.smarty.R.string.mock_ai_intent_inspo)
                )
            lower.contains("github.com") || lower.contains("stackoverflow.com") ->
                Triple(
                    context.getString(com.example.smarty.R.string.mock_ai_tag_code),
                    context.getString(com.example.smarty.R.string.mock_ai_summary_code),
                    context.getString(com.example.smarty.R.string.mock_ai_intent_code)
                )
            lower.contains("reddit.com") || lower.contains("medium.com") || lower.contains("substack.com") ->
                Triple(
                    context.getString(com.example.smarty.R.string.mock_ai_tag_read),
                    context.getString(com.example.smarty.R.string.mock_ai_summary_read),
                    context.getString(com.example.smarty.R.string.mock_ai_intent_read)
                )
            lower.contains("amazon.") || lower.contains("ebay.") || lower.contains("flipkart.") ->
                Triple(
                    context.getString(com.example.smarty.R.string.mock_ai_tag_buy),
                    context.getString(com.example.smarty.R.string.mock_ai_summary_buy),
                    context.getString(com.example.smarty.R.string.mock_ai_intent_buy)
                )
            lower.contains("linkedin.com") ->
                Triple(
                    context.getString(com.example.smarty.R.string.mock_ai_tag_work),
                    context.getString(com.example.smarty.R.string.mock_ai_summary_work),
                    context.getString(com.example.smarty.R.string.mock_ai_intent_work)
                )
            lower.contains("spotify.com") || lower.contains("music.") ->
                Triple(
                    context.getString(com.example.smarty.R.string.mock_ai_tag_play),
                    context.getString(com.example.smarty.R.string.mock_ai_summary_play),
                    context.getString(com.example.smarty.R.string.mock_ai_intent_play)
                )
            lower.contains("netflix.com") || lower.contains("primevideo.") ->
                Triple(
                    context.getString(com.example.smarty.R.string.mock_ai_tag_watch),
                    context.getString(com.example.smarty.R.string.mock_ai_summary_watch),
                    context.getString(com.example.smarty.R.string.mock_ai_intent_watch)
                )
            lower.contains("http://") || lower.contains("https://") ->
                Triple(
                    context.getString(com.example.smarty.R.string.mock_ai_tag_read),
                    context.getString(com.example.smarty.R.string.mock_ai_summary_read),
                    context.getString(com.example.smarty.R.string.mock_ai_intent_read)
                )
            else -> null
        }
    }

    /**
     * Categorize content based on keyword patterns.
     *
     * @param context Android context for localization
     * @param lower Lowercase content string
     * @return Triple of (category, summary, whySaved)
     */
    private fun categorizeByKeywords(context: android.content.Context, lower: String): Triple<String, String, String> {
        return when {
            // Task/Todo patterns
            TODO_PATTERN.containsMatchIn(lower) ->
                Triple(
                    context.getString(com.example.smarty.R.string.mock_ai_tag_todo),
                    context.getString(com.example.smarty.R.string.mock_ai_summary_todo),
                    context.getString(com.example.smarty.R.string.mock_ai_intent_todo)
                )

            // Idea patterns
            IDEA_PATTERN.containsMatchIn(lower) ->
                Triple(
                    context.getString(com.example.smarty.R.string.mock_ai_tag_idea),
                    context.getString(com.example.smarty.R.string.mock_ai_summary_idea),
                    context.getString(com.example.smarty.R.string.mock_ai_intent_idea)
                )

            // Learning patterns
            LEARN_PATTERN.containsMatchIn(lower) || lower.contains("tutorial") || lower.contains("how to") ->
                Triple(
                    context.getString(com.example.smarty.R.string.mock_ai_tag_learn),
                    context.getString(com.example.smarty.R.string.mock_ai_summary_learn),
                    context.getString(com.example.smarty.R.string.mock_ai_intent_learn)
                )

            // Shopping patterns
            BUY_PATTERN.containsMatchIn(lower) ->
                Triple(
                    context.getString(com.example.smarty.R.string.mock_ai_tag_buy),
                    context.getString(com.example.smarty.R.string.mock_ai_summary_buy),
                    context.getString(com.example.smarty.R.string.mock_ai_intent_buy)
                )

            // Meeting patterns
            MEET_PATTERN.containsMatchIn(lower) ->
                Triple(
                    context.getString(com.example.smarty.R.string.mock_ai_tag_meet),
                    context.getString(com.example.smarty.R.string.mock_ai_summary_meet),
                    context.getString(com.example.smarty.R.string.mock_ai_intent_meet)
                )

            // Code patterns
            CODE_PATTERN.containsMatchIn(lower) ->
                Triple(
                    context.getString(com.example.smarty.R.string.mock_ai_tag_code),
                    context.getString(com.example.smarty.R.string.mock_ai_summary_code),
                    context.getString(com.example.smarty.R.string.mock_ai_intent_code)
                )

            // Quote patterns
            QUOTE_PATTERN.containsMatchIn(lower) ->
                Triple(
                    context.getString(com.example.smarty.R.string.mock_ai_tag_quote),
                    context.getString(com.example.smarty.R.string.mock_ai_summary_quote),
                    context.getString(com.example.smarty.R.string.mock_ai_intent_quote)
                )

            // Health patterns
            HEALTH_PATTERN.containsMatchIn(lower) ->
                Triple(
                    context.getString(com.example.smarty.R.string.mock_ai_tag_health),
                    context.getString(com.example.smarty.R.string.mock_ai_summary_health),
                    context.getString(com.example.smarty.R.string.mock_ai_intent_health)
                )

            // Finance patterns
            FINANCE_PATTERN.containsMatchIn(lower) ->
                Triple(
                    context.getString(com.example.smarty.R.string.mock_ai_tag_finance),
                    context.getString(com.example.smarty.R.string.mock_ai_summary_finance),
                    context.getString(com.example.smarty.R.string.mock_ai_intent_finance)
                )

            // Recipe patterns
            RECIPE_PATTERN.containsMatchIn(lower) ->
                Triple(
                    context.getString(com.example.smarty.R.string.mock_ai_tag_recipe),
                    context.getString(com.example.smarty.R.string.mock_ai_summary_recipe),
                    context.getString(com.example.smarty.R.string.mock_ai_intent_recipe)
                )

            // Work patterns
            WORK_PATTERN.containsMatchIn(lower) ->
                Triple(
                    context.getString(com.example.smarty.R.string.mock_ai_tag_work),
                    context.getString(com.example.smarty.R.string.mock_ai_summary_work),
                    context.getString(com.example.smarty.R.string.mock_ai_intent_work)
                )

            else ->
                Triple(
                    context.getString(com.example.smarty.R.string.mock_ai_tag_note),
                    context.getString(com.example.smarty.R.string.mock_ai_summary_note),
                    context.getString(com.example.smarty.R.string.mock_ai_intent_note)
                )
        }
    }

    // ==================== Validation Helpers ====================

    /**
     * Validate and sanitize a category.
     * Allows dynamic categories (Stacks) instead of restricting to a fixed list.
     * Sanitizes input to ensure clean UI display (Title Case, max length, no special chars).
     *
     * @param category The category to validate
     * @return Validated category or "Note" as fallback
     */
    fun validateCategory(category: String?): String {
        if (category.isNullOrBlank()) return "note"

        // check if it is one of the standard categories (fast path)
        val standardMatch = VALID_CATEGORIES.find { it.equals(category, ignoreCase = true) }
        if (standardMatch != null) return standardMatch

        // For dynamic categories:
        // 1. Remove special chars (keep spaces/hyphens for multi-word stacks like "side project")
        // 2. Limit length to prevent UI issues
        // 3. Lowercase (Consistent with Calm Aesthetic)
        val clean = category.trim()
            .replace(Regex("[^a-zA-Z0-9\\s\\-]"), "")
            .take(20)
            .lowercase()

        return if (clean.isNotEmpty()) {
            clean
        } else {
            "note"
        }
    }

    /**
     * Clean and normalize summary text.
     * Removes excess whitespace and limits length.
     *
     * @param context Android context for localization
     * @param summary The summary to clean
     * @param maxLength Maximum length (default 500)
     * @return Cleaned summary
     */
    fun cleanSummary(context: android.content.Context, summary: String?, maxLength: Int = 500): String {
        if (summary.isNullOrBlank()) return context.getString(com.example.smarty.R.string.mock_ai_summary_note)
        val cleaned = summary.trim().replace(WHITESPACE_PATTERN, " ").lowercase()
        return if (cleaned.length > maxLength) {
            cleaned.take(maxLength - 3) + "..."
        } else {
            cleaned
        }
    }
}
