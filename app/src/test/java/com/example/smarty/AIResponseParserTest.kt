package com.example.smarty

import com.example.smarty.data.remote.AIResponseParser
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)

/**
 * Unit tests for AIResponseParser.
 * Verifies JSON extraction from various AI response formats.
 */
class AIResponseParserTest {

    // =========================================================================
    // RAW JSON EXTRACTION TESTS
    // =========================================================================

    @Test
    fun `extracts raw JSON response`() {
        val json = """{"category":"Learn","title":"Test Note","summary":"A test summary","whySaved":"Testing"}"""

        val result = AIResponseParser.extractAndParseJson(json)

        assertNotNull(result)
        assertEquals("Learn", result?.category)
        assertEquals("Test Note", result?.title)
        assertEquals("A test summary", result?.summary)
        assertEquals("Testing", result?.whySaved)
    }

    @Test
    fun `extracts JSON with whitespace`() {
        val json = """
            {
                "category": "Work",
                "title": "Meeting Notes",
                "summary": "Notes from standup",
                "whySaved": "Reference"
            }
        """.trimIndent()

        val result = AIResponseParser.extractAndParseJson(json)

        assertNotNull(result)
        assertEquals("Work", result?.category)
        assertEquals("Meeting Notes", result?.title)
    }

    // =========================================================================
    // MARKDOWN CODE BLOCK TESTS
    // =========================================================================

    @Test
    fun `extracts JSON from markdown json block`() {
        val response = """
            Here's the analysis:

            ```json
            {"category":"Idea","title":"New Feature","summary":"Great concept","whySaved":"Brainstorm"}
            ```

            Let me know if you need more details.
        """.trimIndent()

        val result = AIResponseParser.extractAndParseJson(response)

        assertNotNull(result)
        assertEquals("Idea", result?.category)
        assertEquals("New Feature", result?.title)
    }

    @Test
    fun `extracts JSON from generic markdown block`() {
        val response = """
            Analysis complete:

            ```
            {"category":"Code","title":"Algorithm","summary":"Sorting implementation","whySaved":"Dev ref"}
            ```
        """.trimIndent()

        val result = AIResponseParser.extractAndParseJson(response)

        assertNotNull(result)
        assertEquals("Code", result?.category)
    }

    // =========================================================================
    // MIXED TEXT AND JSON TESTS
    // =========================================================================

    @Test
    fun `extracts JSON from text with embedded JSON`() {
        val response = """
            Based on my analysis of your note, here is the categorization:

            {"category":"Recipe","title":"Pasta Carbonara","summary":"Italian dinner recipe","whySaved":"Cook this"}

            I've categorized this as a Recipe because it contains cooking instructions.
        """.trimIndent()

        val result = AIResponseParser.extractAndParseJson(response)

        assertNotNull(result)
        assertEquals("Recipe", result?.category)
        assertEquals("Pasta Carbonara", result?.title)
    }

    @Test
    fun `extracts JSON preceded by text`() {
        val response = "Here is the plan: {\"category\":\"Todo\",\"title\":\"Tasks\",\"summary\":\"Weekly tasks\",\"whySaved\":\"Organize\"}"

        val result = AIResponseParser.extractAndParseJson(response)

        assertNotNull(result)
        assertEquals("Todo", result?.category)
    }

    @Test
    fun `extracts JSON followed by text`() {
        val response = "{\"category\":\"Buy\",\"title\":\"Shopping\",\"summary\":\"Items to purchase\",\"whySaved\":\"Shopping list\"} Hope this helps!"

        val result = AIResponseParser.extractAndParseJson(response)

        assertNotNull(result)
        assertEquals("Buy", result?.category)
    }

    // =========================================================================
    // INVALID JSON TESTS
    // =========================================================================

    @Test
    fun `returns null for invalid JSON`() {
        val invalidJson = "{broken json missing quotes}"

        val result = AIResponseParser.extractAndParseJson(invalidJson)

        assertNull(result)
    }

    @Test
    fun `returns null for empty string`() {
        val result = AIResponseParser.extractAndParseJson("")

        assertNull(result)
    }

    @Test
    fun `returns null for text without JSON`() {
        val text = "This is just plain text without any JSON structure at all."

        val result = AIResponseParser.extractAndParseJson(text)

        assertNull(result)
    }

    @Test
    fun `returns null for incomplete JSON`() {
        val incomplete = "{\"category\":\"Test\""

        val result = AIResponseParser.extractAndParseJson(incomplete)

        assertNull(result)
    }

    // =========================================================================
    // DEFAULT VALUES TESTS
    // =========================================================================

    @Test
    fun `uses default values for missing fields`() {
        val minimalJson = "{\"category\":\"Work\"}"

        val result = AIResponseParser.extractAndParseJson(minimalJson)

        assertNotNull(result)
        assertEquals("Work", result?.category)
        assertEquals("Parsed Note", result?.title) // default
        assertEquals("Content saved for later.", result?.summary) // default
        assertEquals("Reference", result?.whySaved) // default
    }

    @Test
    fun `handles empty field values`() {
        val jsonWithEmpty = "{\"category\":\"\",\"title\":\"\"}"

        val result = AIResponseParser.extractAndParseJson(jsonWithEmpty)

        assertNotNull(result)
        // Empty strings should be trimmed to empty
        assertEquals("", result?.category)
    }

    // =========================================================================
    // SPECIAL CHARACTERS TESTS
    // =========================================================================

    @Test
    fun `handles JSON with special characters`() {
        val jsonWithSpecial = """{"category":"Note","title":"Test \"Quote\" Title","summary":"Line1\nLine2","whySaved":"Test"}"""

        val result = AIResponseParser.extractAndParseJson(jsonWithSpecial)

        assertNotNull(result)
        assertTrue(result?.title?.contains("Quote") == true)
    }

    @Test
    fun `handles JSON with unicode`() {
        val jsonWithUnicode = """{"category":"Note","title":"日本語タイトル","summary":"Summary","whySaved":"Saved"}"""

        val result = AIResponseParser.extractAndParseJson(jsonWithUnicode)

        assertNotNull(result)
        assertEquals("日本語タイトル", result?.title)
    }

    // =========================================================================
    // CATEGORY VALIDATION TESTS
    // =========================================================================

    @Test
    fun `VALID_CATEGORIES contains expected categories`() {
        val expected = listOf(
            "Learn", "Read", "Watch", "Idea", "Todo", "Buy", "Meet",
            "Code", "Quote", "Inspo", "Recipe", "Health", "Finance",
            "Work", "Play", "Note", "Legal"
        )

        expected.forEach { category ->
            assertTrue(
                "VALID_CATEGORIES should contain '$category'",
                AIResponseParser.VALID_CATEGORIES.contains(category)
            )
        }
    }

    @Test
    fun `validateCategory returns valid category`() {
        assertEquals("Work", AIResponseParser.validateCategory("Work"))
        assertEquals("Learn", AIResponseParser.validateCategory("Learn"))
    }

    @Test
    fun `validateCategory returns Note for invalid category`() {
        assertEquals("Note", AIResponseParser.validateCategory("InvalidCategory"))
        assertEquals("Note", AIResponseParser.validateCategory(null))
        assertEquals("Note", AIResponseParser.validateCategory(""))
    }

    // =========================================================================
    // SMART FALLBACK CATEGORIZATION TESTS
    // =========================================================================

    @Test
    fun `categorizes YouTube links as Watch`() {
        val content = "Check out this video: https://youtube.com/watch?v=abc123"

        val result = AIResponseParser.smartFallbackCategorization(content)

        assertEquals("Watch", result.category)
    }

    @Test
    fun `categorizes GitHub links as Code`() {
        val content = "Repository: https://github.com/user/repo"

        val result = AIResponseParser.smartFallbackCategorization(content)

        assertEquals("Code", result.category)
    }

    @Test
    fun `categorizes Amazon links as Buy`() {
        val content = "Found this on Amazon: https://amazon.com/product/123"

        val result = AIResponseParser.smartFallbackCategorization(content)

        assertEquals("Buy", result.category)
    }

    @Test
    fun `categorizes todo keywords as Todo`() {
        val content = "Remember to buy groceries tomorrow"

        val result = AIResponseParser.smartFallbackCategorization(content)

        assertEquals("Todo", result.category)
    }

    @Test
    fun `categorizes idea keywords as Idea`() {
        val content = "What if we could create an app that..."

        val result = AIResponseParser.smartFallbackCategorization(content)

        assertEquals("Idea", result.category)
    }

    @Test
    fun `categorizes code patterns as Code`() {
        val content = "function calculateSum() { return a + b; }"

        val result = AIResponseParser.smartFallbackCategorization(content)

        assertEquals("Code", result.category)
    }

    @Test
    fun `categorizes generic content as Note`() {
        val content = "Random text without specific patterns"

        val result = AIResponseParser.smartFallbackCategorization(content)

        assertEquals("Note", result.category)
    }

    // =========================================================================
    // CLEAN SUMMARY TESTS
    // =========================================================================

    @Test
    fun `cleanSummary returns default for null`() {
        assertEquals("Content saved for later.", AIResponseParser.cleanSummary(null))
    }

    @Test
    fun `cleanSummary returns default for blank`() {
        assertEquals("Content saved for later.", AIResponseParser.cleanSummary("   "))
    }

    @Test
    fun `cleanSummary normalizes whitespace`() {
        val input = "Multiple   spaces   here"
        assertEquals("Multiple spaces here", AIResponseParser.cleanSummary(input))
    }

    @Test
    fun `cleanSummary truncates long text`() {
        val longText = "x".repeat(600)
        val result = AIResponseParser.cleanSummary(longText)

        assertTrue(result.length <= 500)
        assertTrue(result.endsWith("..."))
    }

    // =========================================================================
    // INFER CATEGORY FROM TEXT TESTS
    // =========================================================================

    @Test
    fun `inferCategoryFromText detects learning content`() {
        assertEquals("Learn", AIResponseParser.inferCategoryFromText("How to learn Kotlin programming"))
    }

    @Test
    fun `inferCategoryFromText detects shopping content`() {
        assertEquals("Buy", AIResponseParser.inferCategoryFromText("I want to purchase new headphones"))
    }

    @Test
    fun `inferCategoryFromText detects meeting content`() {
        assertEquals("Meet", AIResponseParser.inferCategoryFromText("Schedule a meeting with the team"))
    }

    // =========================================================================
    // RESPONSE TRUNCATION TESTS
    // =========================================================================

    @Test
    fun `handles very long response gracefully`() {
        // Create a response larger than MAX_RESPONSE_LENGTH (100KB)
        val longPrefix = "x".repeat(110000)
        val json = """{"category":"Test","title":"Title","summary":"Sum","whySaved":"Why"}"""
        val response = longPrefix + json

        // Should not crash, may return null due to truncation
        val result = AIResponseParser.extractAndParseJson(response)

        // The result may be null because the JSON is beyond the truncation point
        // The important thing is it doesn't crash or throw OOM
        assertTrue(true) // If we get here, the truncation worked
    }

    // =========================================================================
    // GEMINI RESPONSE PARSING TESTS
    // =========================================================================

    @Test
    fun `parseGeminiResponse returns null for null input`() {
        val result = AIResponseParser.parseGeminiResponse(null)
        assertNull(result)
    }

    @Test
    fun `parseGeminiResponse returns null for blank input`() {
        val result = AIResponseParser.parseGeminiResponse("")
        assertNull(result)
    }

    // =========================================================================
    // OPENAI RESPONSE PARSING TESTS
    // =========================================================================

    @Test
    fun `parseOpenAIResponse returns null for null input`() {
        val result = AIResponseParser.parseOpenAIResponse(null, "OpenAI")
        assertNull(result)
    }

    @Test
    fun `parseOpenAIResponse returns null for blank input`() {
        val result = AIResponseParser.parseOpenAIResponse("", "DeepSeek")
        assertNull(result)
    }

    // =========================================================================
    // HUGGINGFACE RESPONSE PARSING TESTS
    // =========================================================================

    @Test
    fun `parseHuggingFaceResponse returns null for null input`() {
        val result = AIResponseParser.parseHuggingFaceResponse(null)
        assertNull(result)
    }

    @Test
    fun `parseHuggingFaceResponse returns null for blank input`() {
        val result = AIResponseParser.parseHuggingFaceResponse("")
        assertNull(result)
    }
}
