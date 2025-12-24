package com.example.smarty.data.remote

import android.util.Log
import com.google.gson.JsonParser

/**
 * Utility object for parsing AI responses from various providers.
 *
 * This parser handles response formats from:
 * - Gemini API (Google)
 * - OpenAI-compatible APIs (DeepSeek, Groq, OpenAI, OpenRouter)
 * - HuggingFace Inference API
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
        "Learn", "Read", "Watch", "Idea", "Todo", "Buy", "Meet",
        "Code", "Quote", "Inspo", "Recipe", "Health", "Finance",
        "Work", "Play", "Note", "Legal"
    )

    // ==================== JSON Extraction ====================

    /**
     * Parse TOON format response (more token-efficient than JSON).
     * Format:
     * title: value
     * category: value
     * summary: multiline value
     * whySaved: value
     * todos: comma,separated,items or "none"
     */
    fun parseToonResponse(text: String): AIResponse? {
        if (text.isBlank()) return null

        try {
            val lines = text.trim().lines()
            var title: String? = null
            var category: String? = null
            var summary = StringBuilder()
            var whySaved: String? = null
            var todos: List<String> = emptyList()

            var currentField: String? = null
            var inSummary = false

            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue

                when {
                    trimmed.startsWith("title:", ignoreCase = true) -> {
                        title = trimmed.substringAfter(":").trim()
                        currentField = "title"
                        inSummary = false
                    }
                    trimmed.startsWith("category:", ignoreCase = true) -> {
                        category = trimmed.substringAfter(":").trim()
                        currentField = "category"
                        inSummary = false
                    }
                    trimmed.startsWith("summary:", ignoreCase = true) -> {
                        summary.append(trimmed.substringAfter(":").trim())
                        currentField = "summary"
                        inSummary = true
                    }
                    trimmed.startsWith("whySaved:", ignoreCase = true) ||
                    trimmed.startsWith("whysaved:", ignoreCase = true) -> {
                        whySaved = trimmed.substringAfter(":").trim()
                        currentField = "whySaved"
                        inSummary = false
                    }
                    trimmed.startsWith("todos:", ignoreCase = true) -> {
                        val todoStr = trimmed.substringAfter(":").trim()
                        todos = if (todoStr.equals("none", ignoreCase = true) || todoStr.isEmpty()) {
                            emptyList()
                        } else {
                            todoStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        }
                        currentField = "todos"
                        inSummary = false
                    }
                    inSummary && currentField == "summary" -> {
                        // Continuation of summary (multiline)
                        if (summary.isNotEmpty()) summary.append(" ")
                        summary.append(trimmed)
                    }
                }
            }

            // Validate required fields
            if (title.isNullOrBlank() || category.isNullOrBlank()) {
                Log.w(TAG, "TOON parse missing required fields: title=$title, category=$category")
                return null
            }

            return AIResponse(
                title = title.take(50), // Enforce max length
                category = validateCategory(category),
                summary = if (summary.isEmpty()) "Content saved." else cleanSummary(summary.toString(), 300),
                whySaved = whySaved?.take(20) ?: "Saved",
                success = true,
                todos = todos
            )
        } catch (e: Exception) {
            Log.e(TAG, "TOON parse error: ${e.message}")
            return null
        }
    }

    /**
     * Extract JSON from AI response text and parse it into AIResponse.
     *
     * Handles various AI output formats:
     * - Raw JSON: `{"category":"Learn",...}`
     * - Markdown code blocks: ```json\n{...}\n```
     * - Mixed text with embedded JSON
     *
     * @param text The raw AI response text
     * @return Parsed AIResponse or null if parsing fails
     */
    fun extractAndParseJson(text: String): AIResponse? {
        // Try TOON format first (more efficient)
        parseToonResponse(text)?.let { return it }
        // Fall back to JSON parsing
        // BUG-035: Truncate excessively long responses to prevent memory issues
        var cleanText = if (text.length > MAX_RESPONSE_LENGTH) {
            Log.w(TAG, "Response truncated from ${text.length} to $MAX_RESPONSE_LENGTH chars")
            text.take(MAX_RESPONSE_LENGTH)
        } else {
            text
        }.trim()

        // Handle ```json ... ``` format
        if (cleanText.contains("```json")) {
            cleanText = cleanText.substringAfter("```json").substringBefore("```")
        } else if (cleanText.contains("```")) {
            cleanText = cleanText.substringAfter("```").substringBefore("```")
        }

        // Find JSON object boundaries
        val jsonStart = cleanText.indexOf("{")
        val jsonEnd = cleanText.lastIndexOf("}")

        if (jsonStart == -1 || jsonEnd == -1 || jsonEnd <= jsonStart) {
            Log.w(TAG, "No valid JSON found in: ${cleanText.take(100)}")
            return null
        }

        val jsonStr = cleanText.substring(jsonStart, jsonEnd + 1)
        Log.d(TAG, "Extracted JSON: ${jsonStr.take(200)}")

        return try {
            val parsed = JsonParser.parseString(jsonStr).asJsonObject

            AIResponse(
                title = parsed.get("title")?.asString?.trim() ?: "Parsed Note",
                category = parsed.get("category")?.asString?.trim() ?: "Note",
                summary = parsed.get("summary")?.asString?.trim() ?: "Content saved for later.",
                whySaved = parsed.get("whySaved")?.asString?.trim() ?: "Reference",
                success = true
            )
        } catch (e: Exception) {
            Log.e(TAG, "JSON parse error: ${e.message}")
            null
        }
    }

    // ==================== Gemini Response Parsing ====================

    /**
     * Parse Gemini API response body.
     *
     * Gemini response structure:
     * ```json
     * {
     *   "candidates": [{
     *     "content": {
     *       "parts": [{ "text": "..." }]
     *     }
     *   }]
     * }
     * ```
     *
     * @param responseBody The raw HTTP response body from Gemini
     * @return Parsed AIResponse or null if parsing fails
     */
    fun parseGeminiResponse(responseBody: String?): AIResponse? {
        if (responseBody.isNullOrBlank()) {
            Log.e(TAG, "Empty Gemini response")
            return null
        }

        return try {
            Log.d(TAG, "Parsing Gemini response: ${responseBody.take(500)}")

            val json = JsonParser.parseString(responseBody).asJsonObject

            // Check for error in response
            if (json.has("error")) {
                val errorMsg = json.getAsJsonObject("error").get("message")?.asString
                Log.e(TAG, "Gemini API error: $errorMsg")
                return null
            }

            // Navigate to text content
            val candidates = json.getAsJsonArray("candidates")
            if (candidates == null || candidates.size() == 0) {
                Log.w(TAG, "No candidates in Gemini response")
                return null
            }

            val content = candidates[0].asJsonObject.getAsJsonObject("content")
            val parts = content.getAsJsonArray("parts")
            val text = parts[0].asJsonObject.get("text").asString

            Log.d(TAG, "Gemini text output: ${text.take(200)}")

            // Extract and parse JSON from response
            extractAndParseJson(text)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Gemini response: ${e.message}", e)
            null
        }
    }

    /**
     * Parse error message from Gemini API response.
     *
     * @param responseBody The error response body
     * @return Human-readable error message
     */
    fun parseGeminiError(responseBody: String?): String {
        return try {
            val json = JsonParser.parseString(responseBody).asJsonObject
            json.getAsJsonObject("error")?.get("message")?.asString ?: "Unknown error"
        } catch (e: Exception) {
            responseBody?.take(100) ?: "Unknown error"
        }
    }

    // ==================== OpenAI-Compatible Response Parsing ====================

    /**
     * Parse OpenAI-compatible API response.
     *
     * Works with DeepSeek, Groq, OpenAI, and OpenRouter APIs.
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
     * @param responseBody The raw HTTP response body
     * @param providerName Name of the provider for logging
     * @return Parsed AIResponse or null if parsing fails
     */
    fun parseOpenAIResponse(responseBody: String?, providerName: String): AIResponse? {
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

            extractAndParseJson(text ?: "")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse $providerName response: ${e.message}", e)
            null
        }
    }

    // ==================== HuggingFace Response Parsing ====================

    /**
     * Parse HuggingFace Inference API response.
     *
     * Response structure:
     * ```json
     * [{ "generated_text": "..." }]
     * ```
     *
     * @param responseBody The raw HTTP response body
     * @return Parsed AIResponse or null if parsing fails
     */
    fun parseHuggingFaceResponse(responseBody: String?): AIResponse? {
        if (responseBody.isNullOrBlank()) {
            Log.e(TAG, "Empty HuggingFace response")
            return null
        }

        return try {
            Log.d(TAG, "Parsing HuggingFace response: ${responseBody.take(500)}")

            val jsonArray = JsonParser.parseString(responseBody).asJsonArray
            if (jsonArray.size() == 0) {
                Log.w(TAG, "Empty HuggingFace array")
                return null
            }

            val generatedText = jsonArray[0].asJsonObject
                .get("generated_text")?.asString ?: ""

            Log.d(TAG, "HuggingFace text output: ${generatedText.take(200)}")

            extractAndParseJson(generatedText)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse HuggingFace response: ${e.message}", e)
            null
        }
    }

    // ==================== Document Analysis Parsing ====================

    /**
     * Parse document analysis response from Gemini API.
     *
     * @param responseBody The raw HTTP response body from Gemini
     * @return Parsed DocumentAnalysisResponse or null if parsing fails
     */
    fun parseDocumentAnalysisResponse(responseBody: String?): DocumentAnalysisResponse? {
        if (responseBody.isNullOrBlank()) return null

        return try {
            val json = JsonParser.parseString(responseBody).asJsonObject
            val candidates = json.getAsJsonArray("candidates")
            if (candidates == null || candidates.size() == 0) return null

            val text = candidates[0].asJsonObject
                .getAsJsonObject("content")
                .getAsJsonArray("parts")[0].asJsonObject
                .get("text").asString

            parseDocumentAnalysisFromText(text)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse document analysis response: ${e.message}")
            null
        }
    }

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
     * @param text The AI response text containing JSON
     * @return Parsed DocumentAnalysisResponse or null if parsing fails
     */
    fun parseDocumentAnalysisFromText(text: String?): DocumentAnalysisResponse? {
        if (text.isNullOrBlank()) return null

        // Clean up markdown code blocks
        var cleanText = text.trim()
        if (cleanText.contains("```json")) {
            cleanText = cleanText.substringAfter("```json").substringBefore("```")
        } else if (cleanText.contains("```")) {
            cleanText = cleanText.substringAfter("```").substringBefore("```")
        }

        val jsonStart = cleanText.indexOf("{")
        val jsonEnd = cleanText.lastIndexOf("}")

        if (jsonStart == -1 || jsonEnd == -1 || jsonEnd <= jsonStart) return null

        val jsonStr = cleanText.substring(jsonStart, jsonEnd + 1)

        return try {
            val parsed = JsonParser.parseString(jsonStr).asJsonObject

            val keyPoints = parsed.getAsJsonArray("keyPoints")?.map { it.asString } ?: emptyList()
            val actionItems = parsed.getAsJsonArray("actionItems")?.map { it.asString } ?: emptyList()

            DocumentAnalysisResponse(
                title = parsed.get("title")?.asString?.trim() ?: "Document",
                summary = parsed.get("summary")?.asString?.trim() ?: "Document saved.",
                keyPoints = keyPoints,
                category = parsed.get("category")?.asString?.trim() ?: "Note",
                actionItems = actionItems,
                userRelevance = parsed.get("userRelevance")?.asString?.trim() ?: "Saved for reference",
                success = true
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse document analysis JSON: ${e.message}")
            null
        }
    }

    // ==================== Agent Response Parsing ====================

    /**
     * Extract text content from Gemini response for agent chat.
     *
     * @param responseBody The raw HTTP response body
     * @return Extracted text content or null
     */
    fun extractGeminiAgentText(responseBody: String?): String? {
        if (responseBody.isNullOrBlank()) return null

        return try {
            val json = JsonParser.parseString(responseBody).asJsonObject

            if (json.has("error")) {
                Log.e(TAG, "Gemini agent error: ${json.getAsJsonObject("error")}")
                return null
            }

            val candidates = json.getAsJsonArray("candidates")
            if (candidates == null || candidates.size() == 0) return null

            candidates[0].asJsonObject
                .getAsJsonObject("content")
                .getAsJsonArray("parts")[0].asJsonObject
                .get("text").asString
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract Gemini agent text: ${e.message}")
            null
        }
    }

    /**
     * Extract text content from OpenAI-compatible response for agent chat.
     *
     * @param responseBody The raw HTTP response body
     * @param providerName Name of the provider for logging
     * @return Extracted text content or null
     */
    fun extractOpenAIAgentText(responseBody: String?, providerName: String): String? {
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
     * Extract text content from HuggingFace response for agent chat.
     *
     * @param responseBody The raw HTTP response body
     * @return Extracted text content or null
     */
    fun extractHuggingFaceAgentText(responseBody: String?): String? {
        if (responseBody.isNullOrBlank()) return null

        return try {
            val jsonArray = JsonParser.parseString(responseBody).asJsonArray
            if (jsonArray.size() == 0) return null

            jsonArray[0].asJsonObject.get("generated_text")?.asString
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract HuggingFace agent text: ${e.message}")
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
    fun inferCategoryFromText(text: String): String {
        val lower = text.lowercase()
        val urlCat = categorizeByUrl(lower)
        if (urlCat != null) return urlCat.first
        return categorizeByKeywords(lower).first
    }

    /**
     * Smart fallback categorization using keyword and URL analysis.
     * Used when all AI providers fail.
     *
     * Analyzes:
     * 1. URL patterns (YouTube, Twitter, GitHub, etc.)
     * 2. Keyword patterns (todo, idea, learn, buy, etc.)
     *
     * @param content The content to categorize
     * @return AIResponse with categorization and summary
     */
    fun smartFallbackCategorization(content: String): AIResponse {
        val lower = content.lowercase()
        
        // Generate a fallback title
        val fallbackTitle = com.example.smarty.util.ContentTypeDetector.extractTitle(
            content, 
            com.example.smarty.util.ContentTypeDetector.detectContentType(content)
        )

        // URL-based categorization
        val urlCategory = categorizeByUrl(lower)
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
        val keywordCategory = categorizeByKeywords(lower)
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
     * @param lower Lowercase content string
     * @return Triple of (category, summary, whySaved) or null
     */
    private fun categorizeByUrl(lower: String): Triple<String, String, String>? {
        return when {
            lower.contains("youtube.com") || lower.contains("youtu.be") ->
                Triple("Watch", "YouTube video saved for later viewing.", "Watch later")
            lower.contains("twitter.com") || lower.contains("x.com") ->
                Triple("Read", "Tweet or thread worth revisiting.", "Social insight")
            lower.contains("instagram.com") ->
                Triple("Inspo", "Instagram content captured for inspiration.", "Visual inspo")
            lower.contains("github.com") ->
                Triple("Code", "GitHub repository or code reference.", "Dev resource")
            lower.contains("stackoverflow.com") ->
                Triple("Code", "Stack Overflow solution for reference.", "Code help")
            lower.contains("reddit.com") ->
                Triple("Read", "Reddit discussion or post.", "Community take")
            lower.contains("medium.com") || lower.contains("substack.com") ->
                Triple("Read", "Article or blog post to read.", "Read later")
            lower.contains("amazon.") || lower.contains("ebay.") || lower.contains("flipkart.") ->
                Triple("Buy", "Product link saved for shopping.", "Want to buy")
            lower.contains("linkedin.com") ->
                Triple("Work", "LinkedIn content for professional reference.", "Career ref")
            lower.contains("spotify.com") || lower.contains("music.") ->
                Triple("Play", "Music or audio content.", "Listen later")
            lower.contains("netflix.com") || lower.contains("primevideo.") ->
                Triple("Watch", "Streaming content to watch.", "Watch later")
            lower.contains("http://") || lower.contains("https://") ->
                Triple("Read", "Web link saved for later.", "Read later")
            else -> null
        }
    }

    /**
     * Categorize content based on keyword patterns.
     *
     * @param lower Lowercase content string
     * @return Triple of (category, summary, whySaved)
     */
    private fun categorizeByKeywords(lower: String): Triple<String, String, String> {
        return when {
            // Task/Todo patterns
            TODO_PATTERN.containsMatchIn(lower) ->
                Triple("Todo", "Task or reminder captured.", "Action item")

            // Idea patterns
            IDEA_PATTERN.containsMatchIn(lower) ->
                Triple("Idea", "Creative thought or idea captured.", "Future ref")

            // Learning patterns
            LEARN_PATTERN.containsMatchIn(lower) ->
                Triple("Learn", "Educational content for learning.", "Skill up")

            // Shopping patterns
            BUY_PATTERN.containsMatchIn(lower) ->
                Triple("Buy", "Shopping note or wishlist item.", "Want this")

            // Meeting patterns
            MEET_PATTERN.containsMatchIn(lower) ->
                Triple("Meet", "Meeting or event reminder.", "Schedule")

            // Code patterns
            CODE_PATTERN.containsMatchIn(lower) ->
                Triple("Code", "Code snippet or technical note.", "Dev ref")

            // Quote patterns
            QUOTE_PATTERN.containsMatchIn(lower) ->
                Triple("Quote", "Quote or memorable words.", "Wisdom")

            // Health patterns
            HEALTH_PATTERN.containsMatchIn(lower) ->
                Triple("Health", "Health or fitness related note.", "Wellness")

            // Finance patterns
            FINANCE_PATTERN.containsMatchIn(lower) ->
                Triple("Finance", "Financial note or tracking.", "Money mgmt")

            // Recipe patterns
            RECIPE_PATTERN.containsMatchIn(lower) ->
                Triple("Recipe", "Recipe or cooking instructions.", "Cook this")

            // Work patterns
            WORK_PATTERN.containsMatchIn(lower) ->
                Triple("Work", "Work-related note or task.", "Professional")

            else ->
                Triple("Note", "Quick note captured for later.", "Saved")
        }
    }

    // ==================== Validation Helpers ====================

    /**
     * Validate that a category is in the allowed list.
     * Returns "Note" if invalid.
     *
     * @param category The category to validate
     * @return Valid category or "Note" as fallback
     */
    fun validateCategory(category: String?): String {
        return if (category != null && category in VALID_CATEGORIES) {
            category
        } else {
            "Note"
        }
    }

    /**
     * Clean and normalize summary text.
     * Removes excess whitespace and limits length.
     *
     * @param summary The summary to clean
     * @param maxLength Maximum length (default 500)
     * @return Cleaned summary
     */
    fun cleanSummary(summary: String?, maxLength: Int = 500): String {
        if (summary.isNullOrBlank()) return "Content saved for later."
        val cleaned = summary.trim().replace(WHITESPACE_PATTERN, " ")
        return if (cleaned.length > maxLength) {
            cleaned.take(maxLength - 3) + "..."
        } else {
            cleaned
        }
    }
}
