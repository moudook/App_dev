package com.example.smarty.ui.components.chat

/**
 * Parses accordion sections from AI response text.
 * Format: [[[Title]]]\nContent\n[[[Title]]]...\n
 * Supports multiple independent accordion sections per response.
 * The format is unique and won't conflict with existing markdown.
 */
object AccordionParser {
    private val SECTION_PATTERN = Regex("\\[\\[\\[(.*?)\\]\\]\\]")
    
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
        
        val sections = mutableListOf<AccordionSection>()
        val matches = SECTION_PATTERN.findAll(text).toList()
        
        if (matches.isEmpty()) {
            return ParsedContent(text.trim(), emptyList())
        }
        
        var introText = ""
        
        matches.forEachIndexed { index, matchResult ->
            val startIndex = matchResult.range.first
            val title = matchResult.groupValues[1]
            
            val contentStart = matchResult.range.last + 1
            val nextMatch = matches.getOrNull(index + 1)
            val contentEnd = nextMatch?.range?.first ?: text.length
            
            val content = text.substring(contentStart, contentEnd).trim()
            
            if (index == 0 && startIndex > 0) {
                introText = text.substring(0, startIndex).trim()
            }
            
            if (title.isNotBlank() || content.isNotBlank()) {
                sections.add(AccordionSection(title.trim(), content))
            }
        }
        
        return ParsedContent(introText, sections)
    }
}