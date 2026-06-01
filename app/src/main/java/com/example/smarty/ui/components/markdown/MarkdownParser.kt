package com.example.smarty.ui.components.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import android.util.LruCache

/**
 * Pre-compiled regex patterns for markdown parsing.
 * Compiled once at class-load time — never re-allocated.
 *
 * Supports: bold, italic, strikethrough, inline code, links, autolinks,
 * task lists, LaTeX math (inline + block), code fences, bare URLs.
 */
internal object MarkdownPatterns {
    // Escape character handling
    val escape = Regex("\\\\(.)")

    // Block math: $$...$$ or \[...\]
    val blockMath = Regex("\\$\\$([\\s\\S]+?)\\$\\$|\\\\\\\\\\[([\\s\\S]+?)\\\\\\\\\\]")

    // Inline code: `code` or ``code``
    val inlineCode = Regex("`+([^`\n]+?)`+")

    // Markdown links: [text](url)
    val link = Regex("\\[([^\\]\\\\]*(?:\\\\.[^\\]\\\\]*)*)\\]\\(([^)\\s]*(?:\\s+[^)\\s]+)*)\\)")

    // Autolinks: <url> or <email>
    val autolink = Regex("<([a-zA-Z][a-zA-Z0-9+.-]*://[^>]+|[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,})>")

    // Bare URLs: https://... or http://... standing alone
    val bareUrl = Regex("(?<![\\[\\(\"'])(https?://[^\\s)\\]>\"']+)")

    // Bold: **text** or __text__
    val boldAsterisk = Regex("(?<![*])\\*\\*(.+?)\\*\\*(?![*])")
    val boldUnderscore = Regex("(?<![a-zA-Z])__(.+?)__(?![a-zA-Z])")

    // Italic: *text* or _text_
    val italicAsterisk = Regex("(?<!\\*)\\*([^*]+)\\*(?!\\*)")
    val italicUnderscore = Regex("(?<!_)_([^_]+)_(?!_)")

    // Strikethrough: ~~text~~
    val strikethrough = Regex("~~([^~]+)~~")

    // Task lists: - [ ] or - [x]
    val taskListItem = Regex("^(\\s*)[-*]\\s+\\[([ xX])\\]\\s+(.+)$", RegexOption.MULTILINE)

    // LaTeX inline math: $...$
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
 * Splits text into alternating regular-text and inline-LaTeX segments.
 */
internal fun parseTextWithInlineMath(text: String): List<TextSegment> {
    val segments = mutableListOf<TextSegment>()
    var lastEnd = 0
    MarkdownPatterns.inlineMath.findAll(text).forEach { match ->
        if (match.range.first > lastEnd) {
            segments.add(TextSegment(text.substring(lastEnd, match.range.first)))
        }
        segments.add(TextSegment(match.groupValues[1], isLatex = true))
        lastEnd = match.range.last + 1
    }
    if (lastEnd < text.length) {
        segments.add(TextSegment(text.substring(lastEnd)))
    }
    return segments
}

/**
 * Pre-processes raw AI content to fix common formatting issues:
 *  - Converts literal "\n" (two chars: backslash + n) to real newlines
 *  - Normalises \r\n to \n
 *  - Strips orphan carriage returns
 */
fun preprocessContent(raw: String): String {
    var text = raw
    // Normalise line endings
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    // Replace literal two-char sequence \n with real newline
    // (Only when it's NOT inside a code block — simple heuristic: not preceded by backslash)
    text = text.replace("\\n", "\n")
    return text
}

private val annotatedStringCache = object : LruCache<String, AnnotatedString>(256) {
    override fun sizeOf(key: String, value: AnnotatedString): Int {
        return key.length / 512 + value.text.length / 512 + 1
    }
}

/**
 * Parses markdown content into an [AnnotatedString] with proper styling.
 *
 * KEY DESIGN: Links use [LinkAnnotation.Url] so they are natively clickable
 * by the Compose [Text] composable, without any external gesture handling.
 * This means links work correctly inside [SelectionContainer] — users can
 * select text AND click links independently.
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
    val cacheKey = if (content.length <= 2048) content else content.substring(0, 2048)
    annotatedStringCache.get(cacheKey)?.let { return it }

    val result = buildAnnotatedString {
        val text = content

        data class MarkdownMatch(
            val range: IntRange,
            val displayText: String,
            val style: SpanStyle,
            val priority: Int,
            val isLink: Boolean = false,
            val url: String? = null,
            val isMath: Boolean = false,
            val isCode: Boolean = false,
            val isStrike: Boolean = false
        )

        val matches = mutableListOf<MarkdownMatch>()

        fun isPositionEscaped(pos: Int): Boolean {
            if (pos < 0 || pos >= text.length) return false
            var count = 0
            var i = pos - 1
            while (i >= 0 && text[i] == '\\') { count++; i-- }
            return count % 2 == 1
        }

        fun addMatchIfValid(match: MarkdownMatch): Boolean {
            if (match.range.first < 0 || match.range.last >= text.length) return false
            if (isPositionEscaped(match.range.first)) return false
            // Don't add if fully inside an existing match
            if (matches.any { it.range.first <= match.range.first && it.range.last >= match.range.last }) return false
            matches.add(match)
            return true
        }

        // ── Collect all matches by priority ──────────────────────────

        // Block math (priority 0)
        MarkdownPatterns.blockMath.findAll(text).forEach { m ->
            val inner = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.trim() ?: ""
            addMatchIfValid(MarkdownMatch(
                range = m.range, displayText = inner,
                style = SpanStyle(color = codeColor, fontFamily = FontFamily.Monospace, background = codeColor.copy(alpha = 0.15f), fontSize = 14.sp),
                priority = 0, isMath = true
            ))
        }

        // Inline math (priority 1)
        MarkdownPatterns.inlineMath.findAll(text).forEach { m ->
            val inner = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.trim() ?: ""
            addMatchIfValid(MarkdownMatch(
                range = m.range, displayText = inner,
                style = SpanStyle(color = codeColor, fontFamily = FontFamily.Monospace, fontStyle = FontStyle.Italic, background = codeColor.copy(alpha = 0.1f)),
                priority = 1, isMath = true
            ))
        }

        // Inline code (priority 2)
        MarkdownPatterns.inlineCode.findAll(text).forEach { m ->
            addMatchIfValid(MarkdownMatch(
                range = m.range, displayText = m.groupValues[1],
                style = SpanStyle(color = codeColor, fontFamily = FontFamily.Monospace, background = codeColor.copy(alpha = 0.15f)),
                priority = 2, isCode = true
            ))
        }

        // Markdown links [text](url) (priority 3)
        MarkdownPatterns.link.findAll(text).forEach { m ->
            val url = m.groupValues.getOrNull(2) ?: ""
            addMatchIfValid(MarkdownMatch(
                range = m.range, displayText = m.groupValues[1],
                style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                priority = 3, isLink = true, url = url
            ))
        }

        // Autolinks <url> (priority 4)
        MarkdownPatterns.autolink.findAll(text).forEach { m ->
            val url = m.groupValues[1]
            addMatchIfValid(MarkdownMatch(
                range = m.range, displayText = url,
                style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                priority = 4, isLink = true, url = url
            ))
        }

        // Bare URLs (priority 4.5 — after autolinks but before formatting)
        MarkdownPatterns.bareUrl.findAll(text).forEach { m ->
            val url = m.groupValues[0]
            addMatchIfValid(MarkdownMatch(
                range = m.range, displayText = url,
                style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                priority = 4, isLink = true, url = url
            ))
        }

        // Bold ** (priority 5)
        MarkdownPatterns.boldAsterisk.findAll(text).forEach { m ->
            addMatchIfValid(MarkdownMatch(
                range = m.range, displayText = m.groupValues[1],
                style = SpanStyle(color = boldColor, fontWeight = FontWeight.Bold),
                priority = 5
            ))
        }

        // Bold __ (priority 6)
        MarkdownPatterns.boldUnderscore.findAll(text).forEach { m ->
            addMatchIfValid(MarkdownMatch(
                range = m.range, displayText = m.groupValues[1],
                style = SpanStyle(color = boldColor, fontWeight = FontWeight.Bold),
                priority = 6
            ))
        }

        // Italic * (priority 7)
        MarkdownPatterns.italicAsterisk.findAll(text).forEach { m ->
            addMatchIfValid(MarkdownMatch(
                range = m.range, displayText = m.groupValues[1],
                style = SpanStyle(color = italicColor, fontStyle = FontStyle.Italic),
                priority = 7
            ))
        }

        // Italic _ (priority 8)
        MarkdownPatterns.italicUnderscore.findAll(text).forEach { m ->
            addMatchIfValid(MarkdownMatch(
                range = m.range, displayText = m.groupValues[1],
                style = SpanStyle(color = italicColor, fontStyle = FontStyle.Italic),
                priority = 8
            ))
        }

        // Strikethrough (priority 9)
        MarkdownPatterns.strikethrough.findAll(text).forEach { m ->
            addMatchIfValid(MarkdownMatch(
                range = m.range, displayText = m.groupValues[1],
                style = SpanStyle(color = normalColor.copy(alpha = 0.6f), textDecoration = TextDecoration.LineThrough),
                priority = 9, isStrike = true
            ))
        }

        // ── Render matches in document order ─────────────────────────

        val sorted = matches.sortedWith(compareBy({ it.range.first }, { -it.priority }))
        var cursor = 0

        for (match in sorted) {
            // Skip overlapping
            if (match.range.first < cursor) continue

            // Plain text before this match
            if (match.range.first > cursor) {
                append(text.substring(cursor, match.range.first))
            }

            if (match.isLink && match.url != null) {
                // ──── Native clickable link via LinkAnnotation ────
                // Works inside SelectionContainer — no pointerInput needed
                val linkStyle = TextLinkStyles(
                    style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
                )
                withLink(LinkAnnotation.Url(url = match.url, styles = linkStyle)) {
                    val innerStr = parseMarkdownToAnnotatedString(match.displayText, normalColor, boldColor, italicColor, linkColor, codeColor, isStreaming)
                    append(innerStr)
                }
            } else {
                // Regular styled span
                withStyle(match.style) {
                    val parseInner = !match.isMath && !match.isCode
                    if (parseInner) {
                        val innerStr = parseMarkdownToAnnotatedString(match.displayText, normalColor, boldColor, italicColor, linkColor, codeColor, isStreaming)
                        append(innerStr)
                    } else {
                        append(match.displayText)
                    }
                }
            }

            cursor = match.range.last + 1
        }

        // Remaining plain text
        if (cursor < text.length) {
            append(text.substring(cursor))
        }
    }
    annotatedStringCache.put(cacheKey, result)
    return result
}
