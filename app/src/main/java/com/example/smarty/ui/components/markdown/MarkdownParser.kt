package com.example.smarty.ui.components.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp

/**
 * Pre-compiled regex patterns for markdown parsing.
 * Supports: bold, italic, strikethrough, inline code, links, task lists, LaTeX math
 */
internal object MarkdownPatterns {
    // Escape character handling - must check for escaped characters first
    val escape = Regex("\\\\(.)")
    
    // Priority ordered patterns - longer/more specific first
    // Block elements take priority
    val blockMath = Regex("\\$\\$([\\s\\S]+?)\\$\\$|\\\\\\[([\\s\\S]+?)\\\\\\]")
    
    // Inline code - must be checked before bold/italic (backticks are literal)
    val inlineCode = Regex("`+([^`\n]+?)`+")
    
    // Links - must be checked before bold/italic (brackets are literal in link context)
    val link = Regex("\\[([^\\]\\\\]*(?:\\\\.[^\\]\\\\]*)*)\\]\\(([^)\\s]*(?:\\s+[^)\\s]+)*)\\)")
    
    // Autolinks
    val autolink = Regex("<([a-zA-Z][a-zA-Z0-9+.-]*://[^>]+|[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,})>")
    
    // Bold - **text** or __text__ - must check before italic
    val boldAsterisk = Regex("(?<![*])\\*\\*(.+?)\\*\\*(?![*])")
    val boldUnderscore = Regex("(?<![a-zA-Z])__(.+?)__(?![a-zA-Z])")
    
    // Italic - *text* or _text_ (but not ** or __ which are bold)
    val italicAsterisk = Regex("(?<!\\*)\\*([^*]+)\\*(?!\\*)")
    val italicUnderscore = Regex("(?<!_) _([^_]+) _(?!_)")
    
    // Strikethrough: ~~text~~
    val strikethrough = Regex("~~([^~]+)~~")
    
    // Task lists: - [ ] or - [x]
    val taskListItem = Regex("^(\\s*)[-*]\\s+\\[([ xX])\\]\\s+(.+)$", RegexOption.MULTILINE)
    
    // LaTeX inline math
    val inlineMath = Regex("(?<!\\$)\\$(?!\\$)([^\n$]+)\\$(?!\\$)")
    
    // Code block fence
    val codeFence = Regex("^```(\\w*)$", RegexOption.MULTILINE)
}

/**
 * Represents a segment of text that may contain LaTeX math.
 */
internal data class TextSegment(
    val content: String,
    val isLatex: Boolean = false,
    val isBlock: Boolean = false
)

/**
 * Splits text into alternating regular text and LaTeX math segments.
 */
internal fun parseTextWithInlineMath(text: String): List<TextSegment> {
    val segments = mutableListOf<TextSegment>()
    val inlinePattern = MarkdownPatterns.inlineMath  // Reuse precompiled pattern
    
    var lastEnd = 0
    inlinePattern.findAll(text).forEach { match ->
        // Add text before this math
        if (match.range.first > lastEnd) {
            segments.add(TextSegment(text.substring(lastEnd, match.range.first)))
        }
        // Add math segment
        segments.add(TextSegment(match.groupValues[1], isLatex = true))
        lastEnd = match.range.last + 1
    }
    // Add remaining text
    if (lastEnd < text.length) {
        segments.add(TextSegment(text.substring(lastEnd)))
    }
    
    return segments
}

/**
 * Parses markdown content into an [AnnotatedString] with proper styling.
 *
 * Handles: bold, italic, strikethrough, inline code, links, autolinks,
 * inline/block math, and escape characters.
 */
fun parseMarkdownToAnnotatedString(
    content: String,
    normalColor: Color,
    boldColor: Color,
    italicColor: Color,
    linkColor: Color,
    codeColor: Color,
    isStreaming: Boolean = false
): AnnotatedString {
    return buildAnnotatedString {
        val text = content

        data class MarkdownMatch(
            val range: IntRange,
            val displayText: String,
            val style: SpanStyle,
            val priority: Int,
            val isLink: Boolean = false,
            val url: String? = null,
            val isMath: Boolean = false,
            val isStrike: Boolean = false,
            val nestedStyles: List<SpanStyle> = emptyList()
        )

        val matches = mutableListOf<MarkdownMatch>()
        
        fun isPositionEscaped(pos: Int): Boolean {
            if (pos < 0 || pos >= text.length) return false
            var backslashCount = 0
            var i = pos - 1
            while (i >= 0 && text[i] == '\\') {
                backslashCount++
                i--
            }
            return backslashCount % 2 == 1
        }

        fun isInRange(pos: Int, range: IntRange): Boolean = pos in range

        fun findNestedStyles(outerText: String, outerPriority: Int): List<SpanStyle> {
            val nested = mutableListOf<SpanStyle>()
            if (outerText.length < 4) return nested
            
            val innerStart = if (outerText.startsWith("**")) 2 else if (outerText.startsWith("__")) 2 else if (outerText.startsWith("*")) 1 else if (outerText.startsWith("_")) 1 else 0
            val innerEnd = if (outerText.endsWith("**")) outerText.length - 2 else if (outerText.endsWith("__")) outerText.length - 2 else if (outerText.endsWith("*")) outerText.length - 1 else if (outerText.endsWith("_")) outerText.length - 1 else outerText.length
            val innerText = outerText.substring(innerStart until innerEnd)
            
            if (innerText.contains("`") && !innerText.startsWith("`") && !innerText.endsWith("`")) {
                nested.add(SpanStyle(
                    color = codeColor,
                    fontFamily = FontFamily.Monospace,
                    background = codeColor.copy(alpha = 0.15f)
                ))
            }
            
            if (innerText.contains("~~")) {
                nested.add(SpanStyle(
                    color = normalColor.copy(alpha = 0.6f),
                    textDecoration = TextDecoration.LineThrough
                ))
            }
            
            return nested
        }

        fun addMatchIfValid(match: MarkdownMatch): Boolean {
            if (match.range.first < 0 || match.range.last >= text.length) return false
            if (isPositionEscaped(match.range.first)) return false
            if (matches.any { it.range.first <= match.range.first && it.range.last >= match.range.last }) return false
            matches.add(match)
            return true
        }

        MarkdownPatterns.blockMath.findAll(text).forEach { m ->
            val contentMatch = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.trim() ?: ""
            addMatchIfValid(MarkdownMatch(
                range = m.range,
                displayText = contentMatch,
                style = SpanStyle(
                    color = codeColor,
                    fontFamily = FontFamily.Monospace,
                    background = codeColor.copy(alpha = 0.15f),
                    fontSize = 14.sp
                ),
                priority = 0,
                isMath = true
            ))
        }

        MarkdownPatterns.inlineMath.findAll(text).forEach { m ->
            val contentMatch = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.trim() ?: ""
            addMatchIfValid(MarkdownMatch(
                range = m.range,
                displayText = contentMatch,
                style = SpanStyle(
                    color = codeColor,
                    fontFamily = FontFamily.Monospace,
                    fontStyle = FontStyle.Italic,
                    background = codeColor.copy(alpha = 0.1f)
                ),
                priority = 1,
                isMath = true
            ))
        }

        MarkdownPatterns.inlineCode.findAll(text).forEach { m ->
            addMatchIfValid(MarkdownMatch(
                range = m.range,
                displayText = m.groupValues[1],
                style = SpanStyle(
                    color = codeColor,
                    fontFamily = FontFamily.Monospace,
                    background = codeColor.copy(alpha = 0.15f)
                ),
                priority = 2
            ))
        }

        MarkdownPatterns.link.findAll(text).forEach { m ->
            val url = m.groupValues.getOrNull(2) ?: ""
            addMatchIfValid(MarkdownMatch(
                range = m.range,
                displayText = m.groupValues[1],
                style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                priority = 3,
                isLink = true,
                url = url
            ))
        }

        MarkdownPatterns.autolink.findAll(text).forEach { m ->
            val url = m.groupValues[1]
            addMatchIfValid(MarkdownMatch(
                range = m.range,
                displayText = url,
                style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                priority = 4,
                isLink = true,
                url = url
            ))
        }

        MarkdownPatterns.boldAsterisk.findAll(text).forEach { m ->
            val displayText = m.groupValues[1]
            addMatchIfValid(MarkdownMatch(
                range = m.range,
                displayText = displayText,
                style = SpanStyle(color = boldColor, fontWeight = FontWeight.Bold),
                priority = 5,
                nestedStyles = findNestedStyles(m.value, 5)
            ))
        }

        MarkdownPatterns.boldUnderscore.findAll(text).forEach { m ->
            val displayText = m.groupValues[1]
            addMatchIfValid(MarkdownMatch(
                range = m.range,
                displayText = displayText,
                style = SpanStyle(color = boldColor, fontWeight = FontWeight.Bold),
                priority = 6,
                nestedStyles = findNestedStyles(m.value, 6)
            ))
        }

        MarkdownPatterns.italicAsterisk.findAll(text).forEach { m ->
            val displayText = m.groupValues[1]
            addMatchIfValid(MarkdownMatch(
                range = m.range,
                displayText = displayText,
                style = SpanStyle(color = italicColor, fontStyle = FontStyle.Italic),
                priority = 7,
                nestedStyles = findNestedStyles(m.value, 7)
            ))
        }

        MarkdownPatterns.italicUnderscore.findAll(text).forEach { m ->
            val displayText = m.groupValues[1]
            addMatchIfValid(MarkdownMatch(
                range = m.range,
                displayText = displayText,
                style = SpanStyle(color = italicColor, fontStyle = FontStyle.Italic),
                priority = 8,
                nestedStyles = findNestedStyles(m.value, 8)
            ))
        }

        MarkdownPatterns.strikethrough.findAll(text).forEach { m ->
            addMatchIfValid(MarkdownMatch(
                range = m.range,
                displayText = m.groupValues[1],
                style = SpanStyle(
                    color = normalColor.copy(alpha = 0.6f),
                    textDecoration = TextDecoration.LineThrough
                ),
                priority = 9,
                isStrike = true
            ))
        }

        val sortedByPosition = matches.sortedWith(
            compareBy({ it.range.first }, { -it.priority })
        )

        var currentIndex = 0
        for (match in sortedByPosition) {
            if (match.range.first > currentIndex) {
                val beforeText = text.substring(currentIndex, match.range.first)
                append(beforeText)
            }

            if (match.isLink && match.url != null) {
                pushStringAnnotation(tag = "URL", annotation = match.url)
                pushStyle(match.style)
                append(match.displayText)
                pop()
                pop()
            } else {
                pushStyle(match.style)
                if (match.nestedStyles.isNotEmpty()) {
                    match.nestedStyles.forEach { nested ->
                        pushStyle(nested)
                    }
                }
                append(match.displayText)
                repeat(match.nestedStyles.size) { pop() }
                pop()
            }

            currentIndex = match.range.last + 1
        }

        if (currentIndex < text.length) {
            append(text.substring(currentIndex))
        }
    }
}
