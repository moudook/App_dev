package com.example.smarty.ui.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import com.example.smarty.ui.theme.MonoFont

/**
 * Markdown text parser utility.
 * 
 * Single Responsibility: Only parses markdown to AnnotatedString.
 * DRY: Centralized parsing logic to avoid duplication.
 */
object MarkdownTextParser {
    
    // Pre-compiled regex patterns
    private val BOLD_ASTERISK = Regex("(?<![*])\\*\\*(.+?)\\*\\*(?![*])")
    private val BOLD_UNDERSCORE = Regex("(?<![a-zA-Z])__(.+?)__(?![a-zA-Z])")
    private val ITALIC_ASTERISK = Regex("(?<!\\*)\\*([^*]+)\\*(?!\\*)")
    private val ITALIC_UNDERSCORE = Regex("(?<!_)_([^_]+)_(?!_)")
    private val STRIKETHROUGH = Regex("~~([^~]+)~~")
    private val INLINE_CODE = Regex("`+([^`\n]+?)`+")
    private val LINK = Regex("\\[([^\\]\\\\]*(?:\\\\.[^\\]\\\\]*)*)\\]\\(([^)\\s]*(?:\\s+[^)\\s]+)*)\\)")
    
    /**
     * Parse markdown text to AnnotatedString with styles.
     * 
     * @param text The markdown text to parse
     * @param normalColor Default text color
     * @param boldColor Color for bold text
     * @param codeColor Color for code spans
     * @param linkColor Color for links
     * @return AnnotatedString with applied styles
     */
    fun parseMarkdown(
        text: String,
        normalColor: Color,
        boldColor: Color,
        codeColor: Color,
        linkColor: Color
    ): androidx.compose.ui.text.AnnotatedString {
        return buildAnnotatedString {
            // Simple parsing - can be extended for more complex markdown
            var remaining = text
            
            // Process bold
            BOLD_ASTERISK.findAll(remaining).forEach { match ->
                val startIndex = match.range.first
                val endIndex = match.range.last + 1
                val content = match.groupValues[1]
                
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = boldColor)) {
                    append(content)
                }
            }
            
            // If no bold found, append as-is
            if (BOLD_ASTERISK.findAll(remaining).none()) {
                append(text)
            }
        }
    }
    
    /**
     * Check if text contains inline LaTeX math expressions.
     */
    fun hasInlineMath(text: String): Boolean {
        return INLINE_MATH_DETECT.containsMatchIn(text)
    }
    
    /**
     * Check if text contains code blocks.
     */
    fun hasCodeBlock(text: String): Boolean {
        return CODE_FENCE.containsMatchIn(text)
    }
    
    /**
     * Extract code blocks from text.
     */
    fun extractCodeBlocks(text: String): List<CodeBlock> {
        return CODE_FENCE.findAll(text).map { match ->
            val language = match.groupValues[1]
            val startIndex = match.range.last + 1
            val endIndex = text.indexOf("```", startIndex).takeIf { it > startIndex } ?: text.length
            val content = text.substring(startIndex, endIndex).trim()
            CodeBlock(language.ifEmpty { "text" }, content)
        }.toList()
    }
    
    /**
     * Remove THINK tags from content.
     */
    fun removeThinkTags(content: String): String {
        return THINK_TAG_REGEX.replace(content, "").trim()
    }
    
    /**
     * Check if content has partial THINK tag (still streaming).
     */
    fun hasPartialThinkTag(content: String): Boolean {
        return PARTIAL_THINK_REGEX.containsMatchIn(content)
    }
    
    // Pre-compiled regex for common patterns
    private val THINK_TAG_REGEX = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)
    private val PARTIAL_THINK_REGEX = Regex("<th?i?n?k?$")
    private val INLINE_MATH_DETECT = Regex("(?<!\\$)\\$(?!\\$)[^\\n]+\\$(?!\\$)")
    private val CODE_FENCE = Regex("^```(\\w*)", RegexOption.MULTILINE)
}

/**
 * Represents an extracted code block.
 */
data class CodeBlock(
    val language: String,
    val content: String
)
