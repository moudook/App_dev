package com.example.smarty.ui.components.markdown

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarty.ui.components.LaTeXView

/**
 * Pre-compiled regex patterns used during markdown rendering.
 * Compiled once at class-load time to avoid per-line re-allocation.
 */
private object RenderPatterns {
    val taskUnchecked = Regex("^\\s*[-*]\\s+\\[\\s*\\]\\s+.*")
    val taskChecked = Regex("^\\s*[-*]\\s+\\[\\s*[xX]\\s*\\]\\s+.*")
    val taskItem = Regex("^\\s*[-*]\\s+\\[(\\s*[xX]?\\s*)\\]\\s+(.+)$")
    val bulletTaskDetect = Regex("^\\s*[-*]\\s+\\[\\s*[xX]?\\s*\\]")
    val numberedTaskDetect = Regex("^\\s*\\d+\\.\\s+\\[.*")
    val horizontalRule = Regex("^(---+|\\*\\*\\*+|___+)$")
    val tableSeparator = Regex("[|\\-:\\s]")
    val inlineMathDetect = Regex("(?<!\\$)\\$(?!\\$)[^\n$]+\\$(?!\\$)")
}

/**
 * Premium Markdown Renderer — beautiful, comfortable, properly spaced.
 *
 * Design principles:
 * - Generous whitespace for breathing room
 * - Clear visual hierarchy with decorative accents
 * - Subtle backgrounds and rounded corners
 * - Consistent spacing scale (4, 8, 12, 16, 20, 24, 28, 32)
 * - Proper line heights for readability (1.6x for body)
 */
@Composable
fun MarkdownRenderer(
    content: String,
    isUser: Boolean,
    normalColor: Color,
    boldColor: Color,
    linkColor: Color,
    codeColor: Color,
    codeBackgroundColor: Color,
    codeBorderColor: Color,
    codeHeaderBg: Color = Color(0xFF343541),
    isStreaming: Boolean = false
) {
    val processedContent = preprocessContent(content)

    val isIncompleteCodeBlock = isStreaming && processedContent.contains("```") &&
            processedContent.split("```").size % 2 == 0

    val parts = if (isIncompleteCodeBlock) {
        val lastFenceIndex = processedContent.lastIndexOf("```")
        if (lastFenceIndex > 0) processedContent.substring(0, lastFenceIndex).split("```")
        else listOf(processedContent)
    } else {
        processedContent.split("```")
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        parts.forEachIndexed { index, part ->
            if (index % 2 == 1) {
                val lines = part.trim().lines()
                val language = if (lines.firstOrNull()?.all { it.isLetterOrDigit() } == true) lines.first() else ""
                val codeContent = if (language.isNotEmpty()) lines.drop(1).joinToString("\n") else part.trim()

                Spacer(modifier = Modifier.height(12.dp))
                com.example.smarty.ui.components.CodeBlock(
                    code = codeContent,
                    language = language,
                    backgroundColor = codeBackgroundColor,
                    borderColor = codeBorderColor,
                    headerBgColor = codeHeaderBg
                )
                Spacer(modifier = Modifier.height(12.dp))
            } else {
                if (part.isNotBlank()) {
                    RenderMarkdownBlock(
                        text = part.trim(),
                        normalColor = normalColor,
                        boldColor = boldColor,
                        linkColor = linkColor,
                        codeColor = codeColor,
                        codeBackgroundColor = codeBackgroundColor,
                        isStreaming = isStreaming
                    )
                }
            }
        }
    }
}

/**
 * Renders a block of markdown text with beautiful spacing and visual hierarchy.
 */
@Composable
private fun RenderMarkdownBlock(
    text: String,
    normalColor: Color,
    boldColor: Color,
    linkColor: Color,
    codeColor: Color,
    codeBackgroundColor: Color,
    isStreaming: Boolean
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val trimmedLine = lines[i].trim()

            when {
                // ── H1 ──
                trimmedLine.startsWith("# ") && !trimmedLine.startsWith("## ") -> {
                    Spacer(modifier = Modifier.height(28.dp))
                    MarkdownText(
                        content = trimmedLine.removePrefix("# "),
                        normalColor = boldColor, boldColor = boldColor,
                        linkColor = linkColor, codeColor = codeColor,
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold, fontSize = (28 * 1.6180339f).sp,
                            lineHeight = (38 * 1.6180339f).sp, letterSpacing = (-0.3).sp, color = boldColor
                        )
                    )
                    // Decorative underline
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.3f)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(boldColor.copy(alpha = 0.3f))
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    i++
                }

                // ── H2 ──
                trimmedLine.startsWith("## ") && !trimmedLine.startsWith("### ") -> {
                    Spacer(modifier = Modifier.height(24.dp))
                    MarkdownText(
                        content = trimmedLine.removePrefix("## "),
                        normalColor = boldColor, boldColor = boldColor,
                        linkColor = linkColor, codeColor = codeColor,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold, fontSize = (24 * 1.6180339f).sp,
                            lineHeight = (32 * 1.6180339f).sp, letterSpacing = (-0.2).sp, color = boldColor
                        )
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    i++
                }

                // ── H3 ──
                trimmedLine.startsWith("### ") -> {
                    Spacer(modifier = Modifier.height(20.dp))
                    MarkdownText(
                        content = trimmedLine.removePrefix("### "),
                        normalColor = boldColor, boldColor = boldColor,
                        linkColor = linkColor, codeColor = codeColor,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold, fontSize = (20 * 1.6180339f).sp,
                            lineHeight = (28 * 1.6180339f).sp, letterSpacing = (-0.1).sp, color = boldColor
                        )
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    i++
                }

                // ── H4 ──
                trimmedLine.startsWith("#### ") -> {
                    Spacer(modifier = Modifier.height(18.dp))
                    MarkdownText(
                        content = trimmedLine.removePrefix("#### "),
                        normalColor = boldColor, boldColor = boldColor,
                        linkColor = linkColor, codeColor = codeColor,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold, fontSize = (18 * 1.6180339f).sp,
                            lineHeight = (28 * 1.6180339f).sp, letterSpacing = (-0.1).sp, color = boldColor
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    i++
                }

                // ── H5 ──
                trimmedLine.startsWith("##### ") -> {
                    Spacer(modifier = Modifier.height(16.dp))
                    MarkdownText(
                        content = trimmedLine.removePrefix("##### "),
                        normalColor = boldColor, boldColor = boldColor,
                        linkColor = linkColor, codeColor = codeColor,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.Medium, fontSize = (16 * 1.6180339f).sp,
                            lineHeight = (26 * 1.6180339f).sp, fontStyle = FontStyle.Italic,
                            letterSpacing = 0.sp, color = boldColor.copy(alpha = 0.85f)
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    i++
                }

                // ── H6 ──
                trimmedLine.startsWith("###### ") -> {
                    Spacer(modifier = Modifier.height(16.dp))
                    MarkdownText(
                        content = trimmedLine.removePrefix("###### "),
                        normalColor = boldColor, boldColor = boldColor,
                        linkColor = linkColor, codeColor = codeColor,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Medium, fontSize = (14 * 1.6180339f).sp,
                            lineHeight = (24 * 1.6180339f).sp, letterSpacing = 0.5.sp,
                            color = boldColor.copy(alpha = 0.7f)
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    i++
                }

                // ── LaTeX Block Math ──
                trimmedLine.startsWith("$$") || trimmedLine.startsWith("\\[") -> {
                    val mathLines = mutableListOf<String>()
                    if (trimmedLine.length > 2) mathLines.add(trimmedLine.substring(2).trim())
                    i++
                    while (i < lines.size) {
                        val next = lines[i].trim()
                        if (next.endsWith("$$") || next.endsWith("\\]")) {
                            mathLines.add(next.substring(0, next.length - 2).trim())
                            i++; break
                        }
                        mathLines.add(next); i++
                    }
                    val mathContent = mathLines.joinToString(" ")
                    if (mathContent.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = codeBackgroundColor.copy(alpha = 0.25f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                                LaTeXView(latex = mathContent, isBlock = true,
                                    textColor = codeColor, backgroundColor = Color.Transparent)
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }

                // ── Task Lists ──
                trimmedLine.matches(RenderPatterns.taskUnchecked) ||
                trimmedLine.matches(RenderPatterns.taskChecked) -> {
                    val tasks = mutableListOf<Pair<Boolean, String>>()
                    RenderPatterns.taskItem.find(trimmedLine)?.let { m ->
                        tasks.add(Pair(m.groupValues[1].trim().isNotEmpty(), m.groupValues[2]))
                    }
                    i++
                    while (i < lines.size) {
                        val next = lines[i].trim()
                        val match = RenderPatterns.taskItem.find(next)
                        if (match != null) {
                            tasks.add(Pair(match.groupValues[1].trim().isNotEmpty(), match.groupValues[2]))
                            i++
                        } else if (isBlockBreak(next)) { break }
                        else {
                            if (tasks.isNotEmpty()) {
                                val last = tasks.last()
                                tasks[tasks.lastIndex] = Pair(last.first, last.second + "\n" + next)
                            }
                            i++
                        }
                    }
                    TaskListView(tasks, normalColor, boldColor, linkColor, codeColor, linkColor)
                }

                // ── Bullet Lists ──
                trimmedLine.startsWith("- ") || trimmedLine.startsWith("* ") -> {
                    val itemText = collectContinuationLines(trimmedLine.substring(2), lines, i + 1).also { i = it.second }.first
                    Row(modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.Top) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .align(Alignment.CenterVertically)
                                .clip(RoundedCornerShape(1.dp))
                                .background(normalColor.copy(alpha = 0.5f))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        MarkdownText(
                            content = itemText, normalColor = normalColor, boldColor = boldColor,
                            linkColor = linkColor, codeColor = codeColor,
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 16.sp, lineHeight = 26.sp, color = normalColor)
                        )
                    }
                }

                // ── Numbered Lists ──
                trimmedLine.firstOrNull()?.isDigit() == true && trimmedLine.contains(". ") &&
                !trimmedLine.matches(RenderPatterns.numberedTaskDetect) -> {
                    val dotIndex = trimmedLine.indexOf(". ")
                    if (dotIndex in 1..3) {
                        val prefix = trimmedLine.substring(0, dotIndex + 1)
                        val itemText = collectContinuationLines(trimmedLine.substring(dotIndex + 2), lines, i + 1).also { i = it.second }.first
                        Row(modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.Top) {
                            Text(
                                text = prefix,
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
                                color = normalColor.copy(alpha = 0.8f),
                                modifier = Modifier.padding(top = 2.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            MarkdownText(
                                content = itemText, normalColor = normalColor, boldColor = boldColor,
                                linkColor = linkColor, codeColor = codeColor,
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 16.sp, lineHeight = 26.sp, color = normalColor)
                            )
                        }
                    } else {
                        val itemText = collectContinuationLines(trimmedLine, lines, i + 1).also { i = it.second }.first
                        StandardText(itemText, normalColor, boldColor, linkColor, codeColor)
                    }
                }

                // ── Tables ──
                trimmedLine.startsWith("|") -> {
                    val tableLines = mutableListOf(trimmedLine)
                    i++
                    while (i < lines.size) {
                        val next = lines[i].trim()
                        if (!next.startsWith("|")) break
                        tableLines.add(next); i++
                    }
                    MarkdownTable(tableLines, normalColor, boldColor, linkColor, codeColor)
                }

                // ── S-TIER EDITORIAL BLOCKQUOTE ──
                trimmedLine.startsWith(">") -> {
                    val quoteLines = mutableListOf(trimmedLine.removePrefix(">").trimStart())
                    i++
                    while (i < lines.size) {
                        val next = lines[i].trim()
                        if (next.startsWith(">")) {
                            quoteLines.add(next.removePrefix(">").trimStart())
                        } else if (isBlockBreak(next)) {
                            break
                        } else {
                            quoteLines.add(next)
                        }
                        i++
                    }
                    
                    val isDark = MaterialTheme.colorScheme.surface.luminance() <= 0.5f
                    val cardBg = if (isDark) Color(0xFF1C1C1E) else Color(0xFFF5F5F7) // Apple System Gray 6
                    val borderColor = if (isDark) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.03f)
                    
                    Box(
                        modifier = Modifier
                            .padding(vertical = 12.dp)
                            .fillMaxWidth()
                            .graphicsLayer {
                                shadowElevation = if (isDark) 0f else 12.dp.toPx()
                                ambientShadowColor = linkColor.copy(alpha = 0.04f)
                                spotShadowColor = linkColor.copy(alpha = 0.08f)
                                shape = RoundedCornerShape(20.dp)
                                clip = true
                            }
                            .background(cardBg)
                            .border(1.dp, borderColor, RoundedCornerShape(20.dp))
                    ) {
                        // Massive Editorial Watermark
                        Text(
                            text = "\"",
                            fontSize = 140.sp,
                            fontFamily = FontFamily.Serif,
                            fontWeight = FontWeight.Black,
                            color = linkColor.copy(alpha = if (isDark) 0.08f else 0.05f),
                            modifier = Modifier
                                .offset(x = (-10).dp, y = (-36).dp)
                                .layout { measurable, constraints ->
                                    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                                    // Report 0 size so the watermark doesn't dictate the card's height
                                    layout(0, 0) {
                                        placeable.place(0, 0)
                                    }
                                }
                        )
                        
                        Row(
                            modifier = Modifier
                                .height(IntrinsicSize.Min)
                                .padding(horizontal = 20.dp, vertical = 20.dp)
                        ) {
                            // Liquid Accent Pill
                            Box(
                                modifier = Modifier
                                    .width(4.dp)
                                    .fillMaxHeight()
                                    .clip(CircleShape)
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(linkColor, linkColor.copy(alpha = 0.3f))
                                        )
                                    )
                            )
                            
                            Spacer(modifier = Modifier.width(16.dp))
                            
                            Box(modifier = Modifier.weight(1f)) {
                                MarkdownText(
                                    content = quoteLines.joinToString("\n"),
                                    normalColor = normalColor,
                                    boldColor = boldColor,
                                    linkColor = linkColor,
                                    codeColor = codeColor,
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontSize = 16.sp,
                                        lineHeight = 26.sp,
                                        fontFamily = FontFamily.Serif, // Sophisticated Editorial Look
                                        fontStyle = FontStyle.Italic,
                                        fontWeight = FontWeight.Medium,
                                        letterSpacing = 0.2.sp,
                                        color = if (isDark) Color.White.copy(alpha = 0.85f) else Color(0xFF1D1D1F).copy(alpha = 0.8f)
                                    )
                                )
                            }
                        }
                    }
                }

                // ── Horizontal Rule ──
                trimmedLine.matches(RenderPatterns.horizontalRule) -> {
                    Spacer(modifier = Modifier.height(96.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .drawBehind {
                                val width = size.width
                                val gradient = Brush.horizontalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        normalColor.copy(alpha = 0.2f),
                                        normalColor.copy(alpha = 0.35f),
                                        normalColor.copy(alpha = 0.2f),
                                        Color.Transparent
                                    ),
                                    startX = 0f,
                                    endX = width
                                )
                                drawRect(gradient)
                            }
                    )
                    Spacer(modifier = Modifier.height(96.dp))
                    i++
                }

                // ── Blank line → spacer ──
                trimmedLine.isBlank() -> { Spacer(modifier = Modifier.height(12.dp)); i++ }

                // ── Plain paragraph ──
                else -> {
                    val paragraphText = collectParagraphLines(trimmedLine, lines, i + 1).also { i = it.second }.first
                    StandardText(paragraphText, normalColor, boldColor, linkColor, codeColor)
                }
            }
        }
    }
}

// ── Helpers ──────────────────────────────────────────────────────────────

/** Returns true if a line signals the start of a new block element. */
private fun isBlockBreak(line: String): Boolean {
    val t = line.trim()
    return t.isBlank() || t.startsWith("- ") || t.startsWith("* ") ||
            t.startsWith("### ") || t.startsWith("## ") || t.startsWith("# ") ||
            t.startsWith("#### ") || t.startsWith("##### ") || t.startsWith("###### ") ||
            t.startsWith("> ") || t.startsWith("|") ||
            (t.firstOrNull()?.isDigit() == true && t.contains(". "))
}

/** Collects continuation lines for a list item until a block break. Returns (text, nextIndex). */
private fun collectContinuationLines(firstLine: String, lines: List<String>, startIdx: Int): Pair<String, Int> {
    val result = mutableListOf(firstLine)
    var i = startIdx
    while (i < lines.size) {
        val next = lines[i].trim()
        if (isBlockBreak(next) || next.matches(RenderPatterns.bulletTaskDetect)) break
        result.add(next); i++
    }
    return Pair(result.joinToString("\n"), i)
}

/** Collects paragraph continuation lines, handling inline math blocks. Returns (text, nextIndex). */
private fun collectParagraphLines(firstLine: String, lines: List<String>, startIdx: Int): Pair<String, Int> {
    val result = mutableListOf(firstLine)
    var i = startIdx
    var insideMath = firstLine.contains("$$") && !firstLine.substringAfter("$$").contains("$$")
    if (!insideMath && firstLine.contains("\\[")) insideMath = !firstLine.substringAfter("\\[").contains("\\]")

    while (i < lines.size) {
        val next = lines[i].trim()
        if (insideMath) {
            result.add(next); i++
            if (next.contains("$$") || next.contains("\\]")) insideMath = false
            continue
        }
        if (isBlockBreak(next)) break
        if (next.contains("$$") && !next.substringAfter("$$").contains("$$")) insideMath = true
        else if (next.contains("\\[") && !next.substringAfter("\\[").contains("\\]")) insideMath = true
        result.add(next); i++
    }
    return Pair(result.joinToString("\n"), i)
}

// ── Composable building blocks ──────────────────────────────────────────

/**
 * Renders a single piece of markdown text with native link support.
 */
@Composable
internal fun MarkdownText(
    content: String,
    normalColor: Color,
    boldColor: Color,
    linkColor: Color,
    codeColor: Color,
    style: TextStyle,
    modifier: Modifier = Modifier
) {
    Text(
        text = parseMarkdownToAnnotatedString(content, normalColor, boldColor, normalColor, linkColor, codeColor),
        style = style,
        modifier = modifier
    )
}

/**
 * Renders standard text with optional inline LaTeX support.
 */
@Composable
fun StandardText(
    text: String,
    normalColor: Color,
    boldColor: Color,
    linkColor: Color,
    codeColor: Color,
    isStreaming: Boolean = false
) {
    if (text.isBlank()) return

    val hasInlineMath = text.contains(RenderPatterns.inlineMathDetect)

    if (hasInlineMath) {
        RichTextWithLatex(text, normalColor, boldColor, linkColor, codeColor, isStreaming)
    } else {
        Text(
            text = parseMarkdownToAnnotatedString(text, normalColor, boldColor, normalColor, linkColor, codeColor, isStreaming),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 17.sp, lineHeight = 28.sp, letterSpacing = 0.sp,
                fontWeight = FontWeight.Medium, color = normalColor
            ),
            modifier = Modifier.padding(vertical = 4.dp)
        )
    }
}

/** Task list rendering with theme-aware custom checkboxes and golden ratio spacing. */
@Composable
private fun TaskListView(
    tasks: List<Pair<Boolean, String>>,
    normalColor: Color, boldColor: Color, linkColor: Color, codeColor: Color,
    accentColor: Color
) {
    Column(modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp)) {
        tasks.forEach { (isChecked, taskText) ->
            Row(
                modifier = Modifier.padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Theme-aware checkbox: filled when checked, visible outlined when unchecked
                val isDark = MaterialTheme.colorScheme.surface.luminance() <= 0.5f
                val checkColor = if (isChecked) accentColor else if (isDark) Color(0xFF6B6B6B) else MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
                Surface(
                    shape = RoundedCornerShape(5.dp),
                    color = if (isChecked) accentColor else Color.Transparent,
                    border = BorderStroke(1.5.dp, checkColor),
                    modifier = Modifier.size(18.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (isChecked) Icon(
                            Icons.Default.Check, "Checked",
                            tint = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                MarkdownText(
                    content = taskText, normalColor = normalColor, boldColor = boldColor,
                    linkColor = linkColor, codeColor = codeColor,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 15.sp, lineHeight = 22.sp,
                        color = if (isChecked) normalColor.copy(alpha = 0.5f) else normalColor
                    )
                )
            }
        }
    }
}

/**
 * Renders a markdown table with Apple-style alternating row contrast, rounded corners, and scrollable width.
 * Alternating rows use subtle opacity shifts for readability without harsh color boundaries.
 */
@Composable
fun MarkdownTable(
    tableLines: List<String>,
    normalColor: Color,
    boldColor: Color,
    linkColor: Color,
    codeColor: Color
) {
    if (tableLines.size < 2) {
        StandardText(tableLines.joinToString("\n"), normalColor, boldColor, linkColor, codeColor)
        return
    }

    val parsedRows = tableLines.mapIndexedNotNull { index, line ->
        if (index == 1 && line.replace(RenderPatterns.tableSeparator, "").isEmpty()) null
        else line.split("|").map { it.trim() }.let {
            var list = it
            if (list.firstOrNull()?.isEmpty() == true) list = list.drop(1)
            if (list.lastOrNull()?.isEmpty() == true) list = list.dropLast(1)
            list
        }
    }

    if (parsedRows.isEmpty()) return

    val isDark = MaterialTheme.colorScheme.surface.luminance() <= 0.5f

    // Apple-style: subtle alternating contrast, not harsh color bands
    val borderColor = if (isDark) Color(0xFF38383D) else Color(0xFFD1D1D6)
    val headerBg = if (isDark) Color(0xFF2C2C2E) else Color(0xFFF2F2F7)
    // Even rows: slightly elevated surface; Odd rows: base surface
    val rowEven = if (isDark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)
    val rowOdd = if (isDark) Color(0xFF222224) else Color(0xFFFAFAFA)
    val headerBorderColor = if (isDark) Color(0xFF48484A) else Color(0xFFC6C6C8)

    val maxColumns = parsedRows.maxOfOrNull { it.size } ?: 1

    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
    ) {
        val minTableWidth = maxOf(maxWidth, (maxColumns * 120).dp)

        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, borderColor, RoundedCornerShape(10.dp))
                .horizontalScroll(rememberScrollState())
        ) {
            parsedRows.forEachIndexed { index, cells ->
                val isHeader = index == 0
                val bg = when {
                    isHeader -> headerBg
                    index % 2 == 0 -> rowEven
                    else -> rowOdd
                }

                Row(
                    modifier = Modifier
                        .width(minTableWidth)
                        .background(bg)
                        .drawBehind {
                            // Header bottom border: thicker, more prominent
                            if (isHeader) {
                                drawLine(
                                    headerBorderColor,
                                    Offset(0f, size.height),
                                    Offset(size.width, size.height),
                                    1.dp.toPx()
                                )
                            }
                            // Row separator: subtle hairline for non-header rows
                            if (index > 0 && !isHeader) {
                                drawLine(
                                    borderColor.copy(alpha = 0.5f),
                                    Offset(16.dp.toPx(), 0f),
                                    Offset(size.width - 16.dp.toPx(), 0f),
                                    0.5.dp.toPx()
                                )
                            }
                        }
                ) {
                    for (cellIdx in 0 until maxColumns) {
                        val cellText = cells.getOrNull(cellIdx) ?: ""
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .drawBehind {
                                    // Vertical column separators: subtle hairlines
                                    if (cellIdx > 0) {
                                        drawLine(
                                            borderColor.copy(alpha = 0.4f),
                                            Offset(0f, 4.dp.toPx()),
                                            Offset(0f, size.height - 4.dp.toPx()),
                                            0.5.dp.toPx()
                                        )
                                    }
                                }
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            val hasMath = cellText.contains(RenderPatterns.inlineMathDetect)
                            if (hasMath) {
                                RichTextWithLatex(cellText, if (isHeader) boldColor else normalColor, boldColor, linkColor, codeColor)
                            } else {
                                MarkdownText(
                                    content = cellText,
                                    normalColor = if (isHeader) boldColor else normalColor,
                                    boldColor = boldColor, linkColor = linkColor, codeColor = codeColor,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontSize = 13.sp,
                                        fontWeight = if (isHeader) FontWeight.SemiBold else FontWeight.Normal,
                                        lineHeight = 18.sp,
                                        color = if (isHeader) boldColor else normalColor
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Renders text that contains interleaved regular markdown and inline LaTeX.
 */
@Composable
private fun RichTextWithLatex(
    text: String,
    normalColor: Color,
    boldColor: Color,
    linkColor: Color,
    codeColor: Color,
    isStreaming: Boolean = false
) {
    val segments = parseTextWithInlineMath(text)

    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        segments.forEach { segment ->
            if (segment.isLatex) {
                if (segment.content.isNotBlank()) {
                    LaTeXView(
                        latex = segment.content, isBlock = false,
                        textColor = codeColor, backgroundColor = Color.Transparent,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            } else {
                if (segment.content.isNotBlank()) {
                    Text(
                        text = parseMarkdownToAnnotatedString(
                            segment.content, normalColor, boldColor, normalColor, linkColor, codeColor, isStreaming
                        ),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 16.sp, lineHeight = 26.sp, letterSpacing = 0.sp,
                            fontWeight = FontWeight.Normal, color = normalColor
                        )
                    )
                }
            }
        }
    }
}
