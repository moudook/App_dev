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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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
 * Enhanced Markdown Renderer
 * Supports: Headers, Lists, Links, Bold, Italic, Strikethrough, Code Blocks, LaTeX Math
 * 
 * @param content The markdown content to render
 * @param isUser Whether this is a user message (affects styling)
 * @param normalColor Base text color
 * @param boldColor Color for bold/headers
 * @param linkColor Color for links
 * @param codeColor Color for code elements
 * @param codeBackgroundColor Background for code blocks
 * @param codeBorderColor Border for code blocks
 * @param codeHeaderBg Header background for code blocks
 * @param isStreaming Whether content is still being streamed (affects incomplete markdown handling)
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
    // During streaming, handle incomplete code blocks specially
    val isIncompleteCodeBlock = isStreaming && content.contains("```") && content.split("```").size % 2 == 0
    
    val parts = if (isIncompleteCodeBlock) {
        // Remove the last incomplete fence from the content for display
        val lastFenceIndex = content.lastIndexOf("```")
        if (lastFenceIndex > 0) {
            content.substring(0, lastFenceIndex).split("```")
        } else {
            listOf(content)
        }
    } else {
        content.split("```")
    }

    parts.forEachIndexed { index, part ->
        if (index % 2 == 1) {
            // Code Block
            val lines = part.trim().lines()
            val language = if (lines.firstOrNull()?.all { it.isLetterOrDigit() } == true) lines.first() else ""
            val codeContent = if (language.isNotEmpty()) lines.drop(1).joinToString("\n") else part.trim()

            Spacer(modifier = Modifier.height(12.dp))
            CodeBlock(
                code = codeContent, 
                language = language,
                backgroundColor = codeBackgroundColor,
                borderColor = codeBorderColor,
                headerBgColor = codeHeaderBg
            )
            Spacer(modifier = Modifier.height(12.dp))
        } else {
            // Standard Text / Markdown
            if (part.isNotBlank()) {
                val lines = part.trim().lines()
                var i = 0
                while (i < lines.size) {
                    val originalLine = lines[i]
                    val trimmedLine = originalLine.trim()
                    
                    when {
                        // Headers - ElevenLabs Style: Tighter, bolder, closer to content
                        trimmedLine.startsWith("### ") -> {
                            Spacer(modifier = Modifier.height(20.dp))
                            Text(
                                text = parseMarkdownToAnnotatedString(
                                    trimmedLine.removePrefix("### "), boldColor, boldColor, normalColor, linkColor, codeColor
                                ),
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 20.sp,
                                    lineHeight = 28.sp,
                                    letterSpacing = (-0.1).sp
                                ),
                                color = boldColor
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            i++
                        }
                        trimmedLine.startsWith("## ") -> {
                            Spacer(modifier = Modifier.height(24.dp))
                            Text(
                                text = parseMarkdownToAnnotatedString(
                                    trimmedLine.removePrefix("## "), boldColor, boldColor, normalColor, linkColor, codeColor
                                ),
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 24.sp,
                                    lineHeight = 32.sp,
                                    letterSpacing = (-0.2).sp
                                ),
                                color = boldColor
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            i++
                        }
                        trimmedLine.startsWith("# ") -> {
                            Spacer(modifier = Modifier.height(28.dp))
                            Text(
                                text = parseMarkdownToAnnotatedString(
                                    trimmedLine.removePrefix("# "), boldColor, boldColor, normalColor, linkColor, codeColor
                                ),
                                style = MaterialTheme.typography.headlineSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 28.sp,
                                    lineHeight = 38.sp,
                                    letterSpacing = (-0.3).sp
                                ),
                                color = boldColor
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            i++
                        }
                        
                        // LaTeX Block Math: $$...$$ or \[...\]
                        trimmedLine.startsWith("$$") || trimmedLine.startsWith("\\[") -> {
                            val mathLines = mutableListOf<String>()
                            val isDoubleDollar = trimmedLine.startsWith("$$")
                            val startMarker = if (isDoubleDollar) "$$" else "\\["
                            val endMarker = if (isDoubleDollar) "$$" else "\\]"
                            
                            // Collect all math content
                            if (trimmedLine.length > 2) {
                                mathLines.add(trimmedLine.substring(2).trim())
                            }
                            i++
                            while (i < lines.size) {
                                val nextTrimmed = lines[i].trim()
                                if (nextTrimmed.endsWith("$$") || nextTrimmed.endsWith("\\]")) {
                                    mathLines.add(nextTrimmed.substring(0, nextTrimmed.length - 2).trim())
                                    i++
                                    break
                                }
                                mathLines.add(nextTrimmed)
                                i++
                            }
                            
                            val mathContent = mathLines.joinToString(" ")
                            if (mathContent.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(12.dp))
                                LaTeXView(
                                    latex = mathContent,
                                    isBlock = true,
                                    textColor = codeColor,
                                    backgroundColor = codeBackgroundColor.copy(alpha = 0.3f)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                            }
                        }
                        
                        // Task Lists: - [ ] or - [x]
                        trimmedLine.matches(RenderPatterns.taskUnchecked) || 
                        trimmedLine.matches(RenderPatterns.taskChecked) -> {
                            val taskItems = mutableListOf<Pair<Boolean, String>>()
                            
                            // Parse first item
                            val taskMatch = RenderPatterns.taskItem.find(trimmedLine)
                            if (taskMatch != null) {
                                val isChecked = taskMatch.groupValues[1].trim().isNotEmpty()
                                val taskText = taskMatch.groupValues[2]
                                taskItems.add(Pair(isChecked, taskText))
                            }
                            
                            i++
                            while (i < lines.size) {
                                val nextTrimmed = lines[i].trim()
                                val nextMatch = RenderPatterns.taskItem.find(nextTrimmed)
                                if (nextMatch != null) {
                                    val isChecked = nextMatch.groupValues[1].trim().isNotEmpty()
                                    val taskText = nextMatch.groupValues[2]
                                    taskItems.add(Pair(isChecked, taskText))
                                    i++
                                } else if (nextTrimmed.isBlank() || nextTrimmed.startsWith("- ") || nextTrimmed.startsWith("* ") || 
                                           nextTrimmed.startsWith("### ") || nextTrimmed.startsWith("## ") || nextTrimmed.startsWith("# ") || 
                                           nextTrimmed.startsWith("> ") || (nextTrimmed.firstOrNull()?.isDigit() == true && nextTrimmed.contains(". "))) {
                                    break
                                } else {
                                    // Continuation of previous task item
                                    if (taskItems.isNotEmpty()) {
                                        val lastTask = taskItems.last()
                                        taskItems[taskItems.lastIndex] = Pair(lastTask.first, lastTask.second + "\n" + nextTrimmed)
                                    }
                                    i++
                                }
                            }
                            
                            // Render task list
                            Column(modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp)) {
                                taskItems.forEach { (isChecked, taskText) ->
                                    Row(
                                        modifier = Modifier.padding(vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val checkboxColor = if (isChecked) Color(0xFF74AA9C) else MaterialTheme.colorScheme.outline
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = if (isChecked) checkboxColor.copy(alpha = 0.2f) else Color.Transparent,
                                            border = BorderStroke(1.5.dp, checkboxColor),
                                            modifier = Modifier.size(18.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                if (isChecked) {
                                                    Icon(
                                                        imageVector = Icons.Default.Check,
                                                        contentDescription = "Checked",
                                                        tint = checkboxColor,
                                                        modifier = Modifier.size(12.dp)
                                                    )
                                                }
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = parseMarkdownToAnnotatedString(
                                                taskText, normalColor, boldColor, normalColor, linkColor, codeColor
                                            ),
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontSize = 16.sp,
                                                lineHeight = 26.sp,
                                                color = if (isChecked) normalColor.copy(alpha = 0.6f) else normalColor
                                            )
                                        )
                                    }
                                }
                            }
                        }
                        
                        // Lists (Bullets)
                        trimmedLine.startsWith("- ") || trimmedLine.startsWith("* ") -> {
                            val itemLines = mutableListOf<String>()
                            itemLines.add(trimmedLine.substring(2))
                            i++
                            while (i < lines.size) {
                                val nextTrimmed = lines[i].trim()
                                if (nextTrimmed.isBlank() || nextTrimmed.startsWith("- ") || nextTrimmed.startsWith("* ") || 
                                    nextTrimmed.startsWith("### ") || nextTrimmed.startsWith("## ") || nextTrimmed.startsWith("# ") || 
                                    nextTrimmed.startsWith("> ") || nextTrimmed.matches(RenderPatterns.bulletTaskDetect) ||
                                    (nextTrimmed.firstOrNull()?.isDigit() == true && nextTrimmed.contains(". "))) {
                                    break
                                }
                                itemLines.add(nextTrimmed)
                                i++
                            }
                            Row(modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.Top) {
                                Text("•", style = MaterialTheme.typography.bodyMedium.copy(fontSize=16.sp), color = normalColor.copy(alpha = 0.7f))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = parseMarkdownToAnnotatedString(
                                        itemLines.joinToString("\n"), normalColor, boldColor, normalColor, linkColor, codeColor
                                    ),
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontSize = 16.sp,
                                        lineHeight = 26.sp,
                                        color = normalColor
                                    )
                                )
                            }
                        }
                        
                        // Lists (Numbered) - but not task lists which start with - [ ]
                        trimmedLine.firstOrNull()?.isDigit() == true && trimmedLine.contains(". ") && 
                        !trimmedLine.matches(RenderPatterns.numberedTaskDetect) -> {
                             val dotIndex = trimmedLine.indexOf(". ")
                             if (dotIndex in 1..3) {
                                 val prefix = trimmedLine.substring(0, dotIndex + 2)
                                 val itemLines = mutableListOf<String>()
                                 itemLines.add(trimmedLine.substring(dotIndex + 2))
                                 i++
                                 while (i < lines.size) {
                                     val nextTrimmed = lines[i].trim()
                                     if (nextTrimmed.isBlank() || nextTrimmed.startsWith("- ") || nextTrimmed.startsWith("* ") || nextTrimmed.startsWith("### ") || nextTrimmed.startsWith("## ") || nextTrimmed.startsWith("# ") || nextTrimmed.startsWith("> ") || (nextTrimmed.firstOrNull()?.isDigit() == true && nextTrimmed.contains(". "))) {
                                         break
                                     }
                                     itemLines.add(nextTrimmed)
                                     i++
                                 }
                                 Row(modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.Top) {
                                     Text(
                                         text = prefix,
                                         style = MaterialTheme.typography.bodyMedium.copy(
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Medium,
                                            fontFeatureSettings = "tnum" 
                                         ),
                                         color = normalColor.copy(alpha = 0.8f),
                                         modifier = Modifier.padding(top = 2.dp)
                                     )
                                     Spacer(modifier = Modifier.width(8.dp))
                                     Text(
                                         text = parseMarkdownToAnnotatedString(
                                             itemLines.joinToString("\n"), normalColor, boldColor, normalColor, linkColor, codeColor
                                         ),
                                         style = MaterialTheme.typography.bodyMedium.copy(
                                             fontSize = 16.sp,
                                             lineHeight = 26.sp,
                                             color = normalColor
                                         )
                                     )
                                 }
                             } else {
                                 val itemLines = mutableListOf<String>()
                                 itemLines.add(trimmedLine)
                                 i++
                                 while (i < lines.size) {
                                     val nextTrimmed = lines[i].trim()
                                     if (nextTrimmed.isBlank() || nextTrimmed.startsWith("- ") || nextTrimmed.startsWith("* ") || nextTrimmed.startsWith("### ") || nextTrimmed.startsWith("## ") || nextTrimmed.startsWith("# ") || nextTrimmed.startsWith("> ") || (nextTrimmed.firstOrNull()?.isDigit() == true && nextTrimmed.contains(". "))) {
                                         break
                                     }
                                     itemLines.add(nextTrimmed)
                                     i++
                                 }
                                 StandardText(itemLines.joinToString("\n"), normalColor, boldColor, linkColor, codeColor)
                             }
                        }
                        
                        // Tables
                        trimmedLine.startsWith("|") -> {
                            val tableLines = mutableListOf<String>()
                            tableLines.add(trimmedLine)
                            i++
                            while (i < lines.size) {
                                val nextTrimmed = lines[i].trim()
                                if (!nextTrimmed.startsWith("|")) {
                                    break
                                }
                                tableLines.add(nextTrimmed)
                                i++
                            }
                            MarkdownTable(tableLines, normalColor, boldColor, linkColor, codeColor)
                        }
                        
                        // Blockquote - ChatGPT Style
                        trimmedLine.startsWith("> ") -> {
                            val quoteLines = mutableListOf<String>()
                            quoteLines.add(trimmedLine.substring(2).trim())
                            i++
                            while (i < lines.size) {
                                val nextTrimmed = lines[i].trim()
                                if (nextTrimmed.isBlank() || nextTrimmed.startsWith("- ") || nextTrimmed.startsWith("* ") || nextTrimmed.startsWith("### ") || nextTrimmed.startsWith("## ") || nextTrimmed.startsWith("# ") || (nextTrimmed.firstOrNull()?.isDigit() == true && nextTrimmed.contains(". "))) {
                                    break
                                }
                                if (nextTrimmed.startsWith("> ")) {
                                    quoteLines.add(nextTrimmed.substring(2).trim())
                                } else {
                                    quoteLines.add(nextTrimmed)
                                }
                                i++
                            }
                            Box(
                                modifier = Modifier
                                    .padding(vertical = 6.dp)
                                    .fillMaxWidth()
                                    .drawBehind {
                                        drawLine(
                                            color = normalColor.copy(alpha = 0.3f), // Subtle left border
                                            start = Offset(0f, 0f),
                                            end = Offset(0f, size.height),
                                            strokeWidth = 4.dp.toPx()
                                        )
                                    }
                                    .padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                            ) {
                                Text(
                                    text = parseMarkdownToAnnotatedString(
                                        quoteLines.joinToString("\n"), normalColor, boldColor, normalColor, linkColor, codeColor
                                    ),
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontSize = 16.sp,
                                        lineHeight = 26.sp,
                                        fontStyle = FontStyle.Italic,
                                        color = normalColor.copy(alpha = 0.8f) // Faded text
                                    )
                                )
                            }
                        }
                        
                        // Horizontal Line
                        trimmedLine.matches(RenderPatterns.horizontalRule) -> {
                            Spacer(modifier = Modifier.height(16.dp))
                            HorizontalDivider(
                                color = normalColor.copy(alpha = 0.2f),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            i++
                        }
                        
                        trimmedLine.isBlank() -> {
                            Spacer(modifier = Modifier.height(12.dp)) // Added spacing for empty paragraphs
                            i++
                        }
                        
                        else -> {
                            val paragraphLines = mutableListOf<String>()
                            paragraphLines.add(trimmedLine)
                            i++
                            
                            var insideMath = trimmedLine.contains("$$") && !trimmedLine.substringAfter("$$").contains("$$")
                            if (!insideMath && trimmedLine.contains("\\[")) {
                                insideMath = !trimmedLine.substringAfter("\\[").contains("\\]")
                            }

                            while (i < lines.size) {
                                val nextTrimmed = lines[i].trim()
                                
                                if (insideMath) {
                                    paragraphLines.add(nextTrimmed)
                                    i++
                                    if (nextTrimmed.contains("$$") || nextTrimmed.contains("\\]")) {
                                        insideMath = false
                                    }
                                    continue
                                }

                                if (nextTrimmed.isBlank() || nextTrimmed.startsWith("### ") || nextTrimmed.startsWith("## ") || nextTrimmed.startsWith("# ") || nextTrimmed.startsWith("> ") || nextTrimmed.startsWith("- ") || nextTrimmed.startsWith("* ") || (nextTrimmed.firstOrNull()?.isDigit() == true && nextTrimmed.contains(". ")) || nextTrimmed.startsWith("|")) {
                                    break
                                }
                                
                                if (nextTrimmed.contains("$$") && !nextTrimmed.substringAfter("$$").contains("$$")) {
                                    insideMath = true
                                } else if (nextTrimmed.contains("\\[") && !nextTrimmed.substringAfter("\\[").contains("\\]")) {
                                    insideMath = true
                                }
                                
                                paragraphLines.add(nextTrimmed)
                                i++
                            }
                            StandardText(paragraphLines.joinToString("\n"), normalColor, boldColor, linkColor, codeColor)
                        }
                    }
                }
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
        if (index == 1 && line.replace(RenderPatterns.tableSeparator, "").isEmpty()) {
            null
        } else {
            line.split("|").map { it.trim() }.let {
                var list = it
                if (list.firstOrNull()?.isEmpty() == true) list = list.drop(1)
                if (list.lastOrNull()?.isEmpty() == true) list = list.dropLast(1)
                list
            }
        }
    }

    if (parsedRows.isEmpty()) return

    val isDark = MaterialTheme.colorScheme.surface.luminance() <= 0.5f
    val borderColor = if (isDark) Color(0xFF3F3F46) else Color(0xFFE4E4E7)
    val headerBgColor = if (isDark) Color(0xFF27272A) else Color(0xFFF4F4F5)
    val rowBgColorAlt = if (isDark) Color(0xFF18181B) else Color(0xFFFFFFFF)
    val rowBgColor = if (isDark) Color(0xFF27272A).copy(alpha = 0.5f) else Color(0xFFFAFAFA)
    
    val maxColumns = parsedRows.maxOfOrNull { it.size } ?: 1
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    
    // Calculate table properties to enforce equal column widths that align correctly
    val minTableWidth = maxOf(
        minOf((configuration.screenWidthDp - 48).dp, 640.dp),
        (maxColumns * 120).dp
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .horizontalScroll(rememberScrollState())
    ) {
        parsedRows.forEachIndexed { index, cells ->
            val isHeader = index == 0
            val bgColor = if (isHeader) headerBgColor else if (index % 2 == 0) rowBgColor else rowBgColorAlt

            Row(
                modifier = Modifier
                    .width(minTableWidth)
                    .background(bgColor)
                    .drawBehind {
                        if (index > 0) {
                            drawLine(
                                color = borderColor,
                                start = Offset(0f, 0f),
                                end = Offset(size.width, 0f),
                                strokeWidth = 1.dp.toPx()
                            )
                        }
                    }
            ) {
                // Generate maxColumns cells for consistent alignment
                for (cellIdx in 0 until maxColumns) {
                    val cellText = cells.getOrNull(cellIdx) ?: ""
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .drawBehind {
                                if (cellIdx > 0) {
                                    drawLine(
                                        color = borderColor,
                                        start = Offset(0f, 0f),
                                        end = Offset(0f, size.height),
                                        strokeWidth = 1.dp.toPx()
                                    )
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = parseMarkdownToAnnotatedString(
                                content = cellText,
                                normalColor = if (isHeader) boldColor else normalColor,
                                boldColor = boldColor,
                                italicColor = normalColor,
                                linkColor = linkColor,
                                codeColor = codeColor
                            ),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 14.sp,
                                fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal,
                                lineHeight = 20.sp
                            ),
                            color = if (isHeader) boldColor else normalColor
                        )
                    }
                }
            }
        }
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
    
    // Check if there's inline math in the text
    val hasInlineMath = text.contains(RenderPatterns.inlineMathDetect)
    
    if (hasInlineMath) {
        // Render with inline LaTeX support
        RichTextWithLatex(
            text = text,
            normalColor = normalColor,
            boldColor = boldColor,
            linkColor = linkColor,
            codeColor = codeColor,
            isStreaming = isStreaming
        )
    } else {
        Text(
            text = parseMarkdownToAnnotatedString(
                text, normalColor, boldColor, normalColor, linkColor, codeColor, isStreaming
            ),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 17.sp, 
                lineHeight = 28.sp, 
                letterSpacing = 0.sp,
                fontWeight = FontWeight.Medium,
                color = normalColor
            ),
            modifier = Modifier.padding(vertical = 4.dp)
        )
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
            var currentIndex = 0
            segments.forEach { segment ->
                if (!segment.isLatex) {
                    // Regular text with markdown parsing
                    append(
                        parseMarkdownToAnnotatedString(
                            segment.content, normalColor, boldColor, normalColor, linkColor, codeColor, isStreaming
                        )
                    )
                } else {
                    // Skip adding to annotated string for math - we'll render separately
                }
            }
        }
        
        // First pass: render non-math parts with proper line structure
        val textContent = segments.filter { !it.isLatex }.joinToString("") { it.content }
        if (textContent.isNotBlank()) {
            Text(
                text = annotatedString,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 16.sp, 
                    lineHeight = 26.sp, 
                    letterSpacing = 0.sp,
                    fontWeight = FontWeight.Normal,
                    color = normalColor
                )
            )
        }
        
        // Second pass: render inline LaTeX
        segments.filter { it.isLatex }.forEach { segment ->
            if (segment.content.isNotBlank()) {
                LaTeXView(
                    latex = segment.content,
                    isBlock = false,
                    textColor = codeColor,
                    backgroundColor = Color.Transparent,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}
