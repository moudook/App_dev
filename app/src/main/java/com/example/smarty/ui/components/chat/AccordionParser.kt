package com.example.smarty.ui.components.chat

/**
 * Parses accordion sections from AI response text.
 *
 * Format: [[[Title]]]\nContent\n[[[Title]]]...\n
 *
 * The AI is instructed to use this exact syntax (no quotes around titles).
 * The parser is resilient to common AI mistakes:
 * - Strips surrounding quotes from titles: [[["Title"]]] → "Title"
 * - Handles extra whitespace
 * - Handles empty titles or content
 * - Falls back to plain text if no valid sections found
 */
object AccordionParser {
    private val SECTION_PATTERN = Regex("\\[\\[\\[\\s*(.*?)\\s*\\]\\]\\]")

    data class AccordionSection(
        val title: String,
        val content: String
    )

    data class ParsedContent(
        val introText: String,
        val accordions: List<AccordionSection>
    )

    fun parse(text: String): ParsedContent {
        if (text.isBlank()) return ParsedContent("", emptyList())

        val matches = SECTION_PATTERN.findAll(text).toList()

        if (matches.isEmpty()) {
            return ParsedContent(text.trim(), emptyList())
        }

        val sections = mutableListOf<AccordionSection>()
        var introText = ""

        matches.forEachIndexed { index, matchResult ->
            val startIndex = matchResult.range.first
            val rawTitle = matchResult.groupValues[1].trim()
            val title = stripQuotes(rawTitle)

            val contentStart = matchResult.range.last + 1
            val nextMatch = matches.getOrNull(index + 1)
            val contentEnd = nextMatch?.range?.first ?: text.length

            val content = text.substring(contentStart, contentEnd).trim()

            if (index == 0 && startIndex > 0) {
                introText = text.substring(0, startIndex).trim()
            }

            if (title.isNotBlank() || content.isNotBlank()) {
                sections.add(AccordionSection(title, content))
            }
        }

        if (sections.isEmpty()) {
            return ParsedContent(text.trim(), emptyList())
        }

        return ParsedContent(introText, sections)
    }

    private fun stripQuotes(s: String): String {
        var result = s
        if ((result.startsWith("\"") && result.endsWith("\"")) ||
            (result.startsWith("'") && result.endsWith("'")) ||
            (result.startsWith("`") && result.endsWith("`"))) {
            result = result.substring(1, result.length - 1)
        }
        return result.trim()
    }
}
