package com.example.smarty.ui.components.markdown

import com.example.smarty.ui.components.LaTeXView
import com.example.smarty.ui.components.LaTeXViewInline
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
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

private const val MAX_ACCORDION_DEPTH = 2

/**
 * Premium Markdown Renderer — beautiful, comfortable, properly spaced.
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
    isStreaming: Boolean = false,
    depth: Int = 0
) {
    val preprocessed = remember(content) { preprocessContent(content) }
    val astNodes = remember(preprocessed) {
        if (depth >= MAX_ACCORDION_DEPTH) {
            MarkdownAstParser.parse(preprocessed, skipAccordions = true)
        } else {
            MarkdownAstParser.parse(preprocessed)
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        astNodes.forEachIndexed { index, node ->
            key("${node::class.simpleName}:${node.hashCode()}") {
                when (node) {
                    is MarkdownNode.CodeBlock -> MarkdownCodeBlock(
                        node, codeBackgroundColor, codeBorderColor, codeHeaderBg
                    )
                    is MarkdownNode.LatexBlock -> MarkdownLatexBlock(
                        node, codeBackgroundColor, codeColor
                    )
                    is MarkdownNode.AccordionGroup -> MarkdownAccordionGroup(node, depth)
                    is MarkdownNode.Header -> MarkdownHeader(
                        node, boldColor, linkColor, codeColor
                    )
                    is MarkdownNode.Blockquote -> MarkdownBlockquote(
                        node, normalColor, boldColor, linkColor, codeColor
                    )
                    is MarkdownNode.HorizontalRule -> MarkdownHorizontalRule(normalColor)
                    is MarkdownNode.Table -> MarkdownTable(
                        node.rows, normalColor, boldColor, linkColor, codeColor
                    )
                    is MarkdownNode.TaskList -> TaskListView(
                        node.tasks, normalColor, boldColor, linkColor, codeColor, linkColor
                    )
                    is MarkdownNode.BulletItem -> MarkdownBulletItem(
                        node, normalColor, boldColor, linkColor, codeColor
                    )
                    is MarkdownNode.NumberedItem -> MarkdownNumberedItem(
                        node, normalColor, boldColor, linkColor, codeColor
                    )
                    is MarkdownNode.Paragraph -> StandardText(
                        node.text, normalColor, boldColor, linkColor, codeColor,
                        isStreaming && index == astNodes.lastIndex
                    )
                }
            }
        }
    }
}

@Composable
private fun MarkdownCodeBlock(
    node: MarkdownNode.CodeBlock,
    codeBackgroundColor: Color,
    codeBorderColor: Color,
    codeHeaderBg: Color
) {
    Spacer(modifier = Modifier.height(48.dp))
    com.example.smarty.ui.components.CodeBlock(
        code = node.code,
        language = node.language,
        backgroundColor = codeBackgroundColor,
        borderColor = codeBorderColor,
        headerBgColor = codeHeaderBg
    )
    Spacer(modifier = Modifier.height(48.dp))
}

@Composable
private fun MarkdownLatexBlock(
    node: MarkdownNode.LatexBlock,
    codeBackgroundColor: Color,
    codeColor: Color
) {
    Spacer(modifier = Modifier.height(12.dp))
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = codeBackgroundColor.copy(alpha = 0.25f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            LaTeXView(latex = node.math, isBlock = true,
                textColor = codeColor, backgroundColor = Color.Transparent)
        }
    }
    Spacer(modifier = Modifier.height(12.dp))
}

@Composable
private fun MarkdownAccordionGroup(node: MarkdownNode.AccordionGroup, depth: Int = 0) {
    if (node.sections.isEmpty()) return
    Spacer(modifier = Modifier.height(12.dp))
    val uiSections = node.sections.map {
        com.example.smarty.ui.components.chat.AccordionParser.AccordionSection(it.title, it.content)
    }
    com.example.smarty.ui.components.chat.AccordionGroup(sections = uiSections, depth = depth)
    Spacer(modifier = Modifier.height(12.dp))
}

@Composable
private fun MarkdownHeader(
    node: MarkdownNode.Header,
    boldColor: Color,
    linkColor: Color,
    codeColor: Color
) {
    when (node.level) {
        1 -> {
            Spacer(modifier = Modifier.height(28.dp))
            MarkdownText(
                content = node.text,
                normalColor = boldColor, boldColor = boldColor, linkColor = linkColor, codeColor = codeColor,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold, fontSize = 45.3f.sp,
                    lineHeight = 61.5f.sp, letterSpacing = (-0.3).sp, color = boldColor
                )
            )
            Box(modifier = Modifier.fillMaxWidth(0.3f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(boldColor.copy(alpha = 0.3f)))
            Spacer(modifier = Modifier.height(8.dp))
        }
        2 -> {
            Spacer(modifier = Modifier.height(24.dp))
            MarkdownText(
                content = node.text,
                normalColor = boldColor, boldColor = boldColor, linkColor = linkColor, codeColor = codeColor,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold, fontSize = 38.8f.sp,
                    lineHeight = 51.8f.sp, letterSpacing = (-0.2).sp, color = boldColor
                )
            )
            Spacer(modifier = Modifier.height(6.dp))
        }
        3 -> {
            Spacer(modifier = Modifier.height(20.dp))
            MarkdownText(
                content = node.text,
                normalColor = boldColor, boldColor = boldColor, linkColor = linkColor, codeColor = codeColor,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold, fontSize = 32.4f.sp,
                    lineHeight = 45.3f.sp, letterSpacing = (-0.1).sp, color = boldColor
                )
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
        4 -> {
            Spacer(modifier = Modifier.height(18.dp))
            MarkdownText(
                content = node.text,
                normalColor = boldColor, boldColor = boldColor, linkColor = linkColor, codeColor = codeColor,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.SemiBold, fontSize = 29.1f.sp,
                    lineHeight = 45.3f.sp, letterSpacing = (-0.1).sp, color = boldColor
                )
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        5 -> {
            Spacer(modifier = Modifier.height(16.dp))
            MarkdownText(
                content = node.text,
                normalColor = boldColor, boldColor = boldColor, linkColor = linkColor, codeColor = codeColor,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Medium, fontSize = 25.9f.sp,
                    lineHeight = 42.1f.sp, fontStyle = FontStyle.Italic, letterSpacing = 0.sp, color = boldColor.copy(alpha = 0.85f)
                )
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        else -> {
            Spacer(modifier = Modifier.height(16.dp))
            MarkdownText(
                content = node.text,
                normalColor = boldColor, boldColor = boldColor, linkColor = linkColor, codeColor = codeColor,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Medium, fontSize = 22.7f.sp,
                    lineHeight = 38.8f.sp, letterSpacing = 0.5.sp, color = boldColor.copy(alpha = 0.7f)
                )
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun MarkdownBlockquote(
    node: MarkdownNode.Blockquote,
    normalColor: Color,
    boldColor: Color,
    linkColor: Color,
    codeColor: Color
) {
    val isDark = MaterialTheme.colorScheme.surface.luminance() <= 0.5f
    val cardBg = if (isDark) Color(0xFF1C1C1E) else Color(0xFFF5F5F7)
    val borderColor = if (isDark) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.03f)

    Box(
        modifier = Modifier
            .padding(vertical = 48.dp)
            .fillMaxWidth()
            .shadow(
                elevation = if (isDark) 0.dp else 12.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = linkColor.copy(alpha = 0.04f),
                spotColor = linkColor.copy(alpha = 0.08f)
            )
            .drawWithCache {
                val cornerRadius = androidx.compose.ui.geometry.CornerRadius(20.dp.toPx())
                val barWidth = 4.dp.toPx()
                val vertPadding = 20.dp.toPx()
                val horizPadding = 20.dp.toPx()
                val barGradient = androidx.compose.ui.graphics.Brush.verticalGradient(
                    colors = listOf(linkColor, linkColor.copy(alpha = 0.3f)),
                    startY = vertPadding,
                    endY = size.height - vertPadding
                )
                onDrawBehind {
                    drawRoundRect(color = cardBg, size = size, cornerRadius = cornerRadius)
                    drawRoundRect(color = borderColor, size = size, cornerRadius = cornerRadius, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()))
                    drawRoundRect(brush = barGradient, topLeft = androidx.compose.ui.geometry.Offset(horizPadding, vertPadding), size = androidx.compose.ui.geometry.Size(barWidth, size.height - (vertPadding * 2)), cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f))
                }
            }
            .padding(start = 40.dp, top = 20.dp, bottom = 20.dp, end = 20.dp)
    ) {
        Text(
            text = "\"",
            fontSize = 140.sp,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Black,
            color = linkColor.copy(alpha = if (isDark) 0.08f else 0.05f),
            modifier = Modifier
                .offset(x = (-30).dp, y = (-56).dp)
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    layout(0, 0) { placeable.place(0, 0) }
                }
        )
        MarkdownText(
            content = node.text,
            normalColor = normalColor, boldColor = boldColor, linkColor = linkColor, codeColor = codeColor,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 16.sp, lineHeight = 26.sp, fontFamily = FontFamily.Serif,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, fontWeight = FontWeight.Medium,
                letterSpacing = 0.2.sp, color = if (isDark) Color.White.copy(alpha = 0.85f) else Color(0xFF1D1D1F).copy(alpha = 0.8f)
            )
        )
    }
}

@Composable
private fun MarkdownHorizontalRule(normalColor: Color) {
    Spacer(modifier = Modifier.height(96.dp))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .drawBehind {
                val gradient = Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, normalColor.copy(alpha = 0.2f), normalColor.copy(alpha = 0.35f), normalColor.copy(alpha = 0.2f), Color.Transparent),
                    startX = 0f, endX = size.width
                )
                drawRect(gradient)
            }
    )
    Spacer(modifier = Modifier.height(96.dp))
}

@Composable
private fun MarkdownBulletItem(
    node: MarkdownNode.BulletItem,
    normalColor: Color,
    boldColor: Color,
    linkColor: Color,
    codeColor: Color
) {
    Row(modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.Top) {
        Box(modifier = Modifier.size(6.dp).align(Alignment.CenterVertically).clip(RoundedCornerShape(1.dp)).background(normalColor.copy(alpha = 0.5f)))
        Spacer(modifier = Modifier.width(8.dp))
        MarkdownText(
            content = node.text, normalColor = normalColor, boldColor = boldColor, linkColor = linkColor, codeColor = codeColor,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 16.sp, lineHeight = 26.sp, color = normalColor)
        )
    }
}

@Composable
private fun MarkdownNumberedItem(
    node: MarkdownNode.NumberedItem,
    normalColor: Color,
    boldColor: Color,
    linkColor: Color,
    codeColor: Color
) {
    Row(modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.Top) {
        Text(
            text = node.prefix,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
            color = normalColor.copy(alpha = 0.8f),
            modifier = Modifier.padding(top = 2.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        MarkdownText(
            content = node.text, normalColor = normalColor, boldColor = boldColor, linkColor = linkColor, codeColor = codeColor,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 16.sp, lineHeight = 26.sp, color = normalColor)
        )
    }
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
    if (content.contains(RenderPatterns.inlineMathDetect)) {
        RichTextWithLatex(content, normalColor, boldColor, linkColor, codeColor)
    } else {
        Text(
            text = parseMarkdownToAnnotatedString(content, normalColor, boldColor, normalColor, linkColor, codeColor),
            style = style,
            modifier = modifier
        )
    }
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

    val parsedRows = remember(tableLines) {
        tableLines.mapIndexedNotNull { index, line ->
            if (index == 1 && line.replace(RenderPatterns.tableSeparator, "").isEmpty()) null
            else line.split("(?<!\\\\)\\|".toRegex()).map { it.trim().replace("\\|", "|") }.let {
                var list = it
                if (list.firstOrNull()?.isEmpty() == true) list = list.drop(1)
                if (list.lastOrNull()?.isEmpty() == true) list = list.dropLast(1)
                list
            }
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

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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

    // Bypass FlowRow for single segments — eliminates multi-pass layout overhead
    if (segments.size <= 1) {
        segments.forEach { segment ->
            if (segment.isLatex) {
                if (segment.content.isNotBlank()) {
                    LaTeXViewInline(
                        latex = segment.content,
                        textColor = codeColor,
                        modifier = Modifier.padding(vertical = 4.dp, horizontal = 2.dp)
                    )
                }
            } else {
                if (segment.content.isNotEmpty()) {
                    Text(
                        text = parseMarkdownToAnnotatedString(
                            segment.content, normalColor, boldColor, normalColor, linkColor, codeColor, isStreaming
                        ),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 16.sp, lineHeight = 26.sp, letterSpacing = 0.sp,
                            fontWeight = FontWeight.Normal, color = normalColor
                        ),
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
        }
    } else {
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier.padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            segments.forEach { segment ->
                if (segment.isLatex) {
                    if (segment.content.isNotBlank()) {
                        LaTeXViewInline(
                            latex = segment.content,
                            textColor = codeColor,
                            modifier = Modifier.align(Alignment.CenterVertically).padding(horizontal = 2.dp)
                        )
                    }
                } else {
                    if (segment.content.isNotEmpty()) {
                        Text(
                            text = parseMarkdownToAnnotatedString(
                                segment.content, normalColor, boldColor, normalColor, linkColor, codeColor, isStreaming
                            ),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 16.sp, lineHeight = 26.sp, letterSpacing = 0.sp,
                                fontWeight = FontWeight.Normal, color = normalColor
                            ),
                            modifier = Modifier.align(Alignment.CenterVertically)
                        )
                    }
                }
            }
        }
    }
}
