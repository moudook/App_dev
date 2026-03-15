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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
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
 * Enhanced Markdown Renderer — single source of truth for all markdown display.
 *
 * All text composables rendered by this function are plain [Text] with
 * [LinkAnnotation.Url]-powered links. This means:
 * - Links are natively clickable (no gesture interception)
 * - Works perfectly inside [SelectionContainer]
 * - Users can select text AND click links independently
 *
 * Content is pre-processed via [preprocessContent] to handle literal `\n` etc.
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
    // Pre-process content: fix literal \n, normalise line endings
    val processedContent = preprocessContent(content)

    // Handle incomplete code blocks during streaming
    val isIncompleteCodeBlock = isStreaming && processedContent.contains("```") &&
            processedContent.split("```").size % 2 == 0

    val parts = if (isIncompleteCodeBlock) {
        val lastFenceIndex = processedContent.lastIndexOf("```")
        if (lastFenceIndex > 0) processedContent.substring(0, lastFenceIndex).split("```")
        else listOf(processedContent)
    } else {
        processedContent.split("```")
    }

    parts.forEachIndexed { index, part ->
        if (index % 2 == 1) {
            // ── Code Block ──
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
            // ── Markdown Text ──
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

/**
 * Renders a block of markdown text line-by-line, handling
 * headers, lists, tables, blockquotes, task lists, math, and plain paragraphs.
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
    val lines = text.lines()
    var i = 0
    while (i < lines.size) {
        val trimmedLine = lines[i].trim()

        when {
            // ── Headers ──
            trimmedLine.startsWith("### ") -> {
                Spacer(modifier = Modifier.height(20.dp))
                MarkdownText(
                    content = trimmedLine.removePrefix("### "),
                    normalColor = boldColor, boldColor = boldColor,
                    linkColor = linkColor, codeColor = codeColor,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold, fontSize = 20.sp,
                        lineHeight = 28.sp, letterSpacing = (-0.1).sp, color = boldColor
                    )
                )
                Spacer(modifier = Modifier.height(4.dp))
                i++
            }
            trimmedLine.startsWith("## ") -> {
                Spacer(modifier = Modifier.height(24.dp))
                MarkdownText(
                    content = trimmedLine.removePrefix("## "),
                    normalColor = boldColor, boldColor = boldColor,
                    linkColor = linkColor, codeColor = codeColor,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold, fontSize = 24.sp,
                        lineHeight = 32.sp, letterSpacing = (-0.2).sp, color = boldColor
                    )
                )
                Spacer(modifier = Modifier.height(6.dp))
                i++
            }
            trimmedLine.startsWith("# ") -> {
                Spacer(modifier = Modifier.height(28.dp))
                MarkdownText(
                    content = trimmedLine.removePrefix("# "),
                    normalColor = boldColor, boldColor = boldColor,
                    linkColor = linkColor, codeColor = codeColor,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold, fontSize = 28.sp,
                        lineHeight = 38.sp, letterSpacing = (-0.3).sp, color = boldColor
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
                    LaTeXView(latex = mathContent, isBlock = true,
                        textColor = codeColor, backgroundColor = codeBackgroundColor.copy(alpha = 0.3f))
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
                TaskListView(tasks, normalColor, boldColor, linkColor, codeColor)
            }

            // ── Bullet Lists ──
            trimmedLine.startsWith("- ") || trimmedLine.startsWith("* ") -> {
                val itemText = collectContinuationLines(trimmedLine.substring(2), lines, i + 1).also { i = it.second }.first
                Row(modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.Top) {
                    Text("•", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 16.sp), color = normalColor.copy(alpha = 0.7f))
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
                    val prefix = trimmedLine.substring(0, dotIndex + 2)
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

            // ── Blockquote ──
            trimmedLine.startsWith("> ") -> {
                val quoteLines = mutableListOf(trimmedLine.substring(2).trim())
                i++
                while (i < lines.size) {
                    val next = lines[i].trim()
                    if (isBlockBreak(next)) break
                    quoteLines.add(if (next.startsWith("> ")) next.substring(2).trim() else next)
                    i++
                }
                Box(
                    modifier = Modifier
                        .padding(vertical = 6.dp)
                        .fillMaxWidth()
                        .drawBehind {
                            drawLine(normalColor.copy(alpha = 0.3f),
                                Offset(0f, 0f), Offset(0f, size.height), 4.dp.toPx())
                        }
                        .padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                ) {
                    MarkdownText(
                        content = quoteLines.joinToString("\n"),
                        normalColor = normalColor, boldColor = boldColor,
                        linkColor = linkColor, codeColor = codeColor,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 16.sp, lineHeight = 26.sp,
                            fontStyle = FontStyle.Italic, color = normalColor.copy(alpha = 0.8f)
                        )
                    )
                }
            }

            // ── Horizontal Rule ──
            trimmedLine.matches(RenderPatterns.horizontalRule) -> {
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = normalColor.copy(alpha = 0.2f), modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(16.dp))
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

// ── Helpers ──────────────────────────────────────────────────────────────

/** Returns true if a line signals the start of a new block element. */
private fun isBlockBreak(line: String): Boolean {
    val t = line.trim()
    return t.isBlank() || t.startsWith("- ") || t.startsWith("* ") ||
            t.startsWith("### ") || t.startsWith("## ") || t.startsWith("# ") ||
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
 *
 * Uses [parseMarkdownToAnnotatedString] which embeds [LinkAnnotation.Url]
 * for links — no gesture interception needed. Works in [SelectionContainer].
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

/** Task list rendering. */
@Composable
private fun TaskListView(
    tasks: List<Pair<Boolean, String>>,
    normalColor: Color, boldColor: Color, linkColor: Color, codeColor: Color
) {
    Column(modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp)) {
        tasks.forEach { (isChecked, taskText) ->
            Row(modifier = Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                val color = if (isChecked) Color(0xFF74AA9C) else MaterialTheme.colorScheme.outline
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = if (isChecked) color.copy(alpha = 0.2f) else Color.Transparent,
                    border = BorderStroke(1.5.dp, color),
                    modifier = Modifier.size(18.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (isChecked) Icon(Icons.Default.Check, "Checked", tint = color, modifier = Modifier.size(12.dp))
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                MarkdownText(
                    content = taskText, normalColor = normalColor, boldColor = boldColor,
                    linkColor = linkColor, codeColor = codeColor,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 16.sp, lineHeight = 26.sp,
                        color = if (isChecked) normalColor.copy(alpha = 0.6f) else normalColor
                    )
                )
            }
        }
    }
}

/**
 * Renders a markdown table with alternating row colors and scrollable width.
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
    val borderColor = if (isDark) Color(0xFF3F3F46) else Color(0xFFE4E4E7)
    val headerBg = if (isDark) Color(0xFF27272A) else Color(0xFFF4F4F5)
    val rowBgAlt = if (isDark) Color(0xFF18181B) else Color(0xFFFFFFFF)
    val rowBg = if (isDark) Color(0xFF27272A).copy(alpha = 0.5f) else Color(0xFFFAFAFA)

    val maxColumns = parsedRows.maxOfOrNull { it.size } ?: 1
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val minTableWidth = maxOf(minOf((config.screenWidthDp - 48).dp, 640.dp), (maxColumns * 120).dp)

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            .clip(RoundedCornerShape(8.dp)).border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .horizontalScroll(rememberScrollState())
    ) {
        parsedRows.forEachIndexed { index, cells ->
            val isHeader = index == 0
            val bg = if (isHeader) headerBg else if (index % 2 == 0) rowBg else rowBgAlt

            Row(
                modifier = Modifier.width(minTableWidth).background(bg).drawBehind {
                    if (index > 0) drawLine(borderColor, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
                }
            ) {
                for (cellIdx in 0 until maxColumns) {
                    val cellText = cells.getOrNull(cellIdx) ?: ""
                    Box(
                        modifier = Modifier.weight(1f).drawBehind {
                            if (cellIdx > 0) drawLine(borderColor, Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx())
                        }.padding(horizontal = 16.dp, vertical = 10.dp)
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
                                    fontSize = 14.sp,
                                    fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal,
                                    lineHeight = 20.sp,
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
        val annotatedString = buildAnnotatedString {
            segments.forEach { segment ->
                if (!segment.isLatex) {
                    append(parseMarkdownToAnnotatedString(
                        segment.content, normalColor, boldColor, normalColor, linkColor, codeColor, isStreaming
                    ))
                }
            }
        }

        val textContent = segments.filter { !it.isLatex }.joinToString("") { it.content }
        if (textContent.isNotBlank()) {
            Text(
                text = annotatedString,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 16.sp, lineHeight = 26.sp, letterSpacing = 0.sp,
                    fontWeight = FontWeight.Normal, color = normalColor
                )
            )
        }

        segments.filter { it.isLatex }.forEach { segment ->
            if (segment.content.isNotBlank()) {
                LaTeXView(
                    latex = segment.content, isBlock = false,
                    textColor = codeColor, backgroundColor = Color.Transparent,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}
