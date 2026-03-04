package com.example.smarty.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import com.example.smarty.R
import com.example.smarty.core.domain.model.ChatMessage
import com.example.smarty.core.domain.model.ChatRole
import com.example.smarty.core.domain.model.Citation
import com.example.smarty.core.domain.model.ClarificationRequest
import com.example.smarty.core.domain.model.Note
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.components.viewers.FullScreenImageViewer
import com.example.smarty.ui.theme.Alpha
import com.example.smarty.ui.theme.ComponentSpacing
import com.example.smarty.ui.theme.IconSize
import com.example.smarty.ui.theme.LocalShapes
import com.example.smarty.ui.theme.MonoFont
import com.example.smarty.ui.theme.softCardShadow
import com.example.smarty.ui.components.LaTeXView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Pre-compiled regex patterns for markdown parsing.
 * Supports: bold, italic, strikethrough, inline code, links, task lists, LaTeX math
 */
private object MarkdownPatterns {
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
    
    // --- PRECOMPILED patterns (previously created inline on every recomposition) ---
    // Task list line detection
    val taskListUnchecked = Regex("^\\s*[-*]\\s+\\[\\s*\\]\\s+.*")
    val taskListChecked = Regex("^\\s*[-*]\\s+\\[\\s*[xX]\\s*\\]\\s+.*")
    val taskListParse = Regex("^\\s*[-*]\\s+\\[(\\s*[xX]?\\s*)\\]\\s+(.+)$")
    val taskListDetect = Regex("^\\s*[-*]\\s+\\[\\s*[xX]?\\s*\\]")
    // Numbered list detection (not task list)
    val numberedListNotTask = Regex("^\\s*\\d+\\.\\s+\\[.*")
    // Horizontal rule
    val horizontalRule = Regex("^(---+|\\*\\*\\*+|___+)$")
    // Table separator
    val tableSeparator = Regex("[|\\-:\\s]")
    // Inline math detection (for StandardText)
    val inlineMathDetect = Regex("(?<!\\$)\\$(?!\\$)[^\\n]+\\$(?!\\$)")
    // formatActionName regex
    val actionNameSplit = Regex("([A-Z])")
}

// Precompiled regex for cleanContent — called on every AI message render
private val THINK_TAG_REGEX = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)
private val THINK_OPEN_REGEX = Regex("<think>.*", RegexOption.DOT_MATCHES_ALL)
private val PARTIAL_FINAL_REGEX = Regex("<fi?n?a?l?$")
private val PARTIAL_THINK_REGEX = Regex("<th?i?n?k?$")

// Cached SimpleDateFormat for timestamp formatting (avoids re-creation per message)
private val timestampDateFormat by lazy {
    java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault())
}

private data class TextSegment(
    val content: String,
    val isLatex: Boolean = false,
    val isBlock: Boolean = false
)

private fun parseTextWithInlineMath(text: String): List<TextSegment> {
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
 * Position of a message within a grouped burst of messages.
 */
enum class MessageGroupPosition {
    SINGLE,  // Isolated message
    TOP,     // First in a group
    MIDDLE,  // Middle of a group
    BOTTOM   // Last in a group
}

/**
 * Unified Chat Message Bubble Component
 *
 * Design Philosophy: "Soft Tech"
 * - Organic Geometry: Continuous curvature (32dp) with small anchors
 * - Refined Depth: Subtle borders/shadows
 * - Chromatic Calm: Desaturated accents
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatMessageItem(
    message: ChatMessage,
    modifier: Modifier = Modifier,
    groupPosition: MessageGroupPosition = MessageGroupPosition.SINGLE,
    getNote: (String) -> Note? = { null },
    onNoteClick: (Note) -> Unit = {},
    onSuggestionClick: (String) -> Unit = {},
    onClarificationSubmit: (String) -> Unit = {},
    onCopyMessage: (String) -> Unit = {},
    onDeleteMessage: (String) -> Unit = {},
    onRegenerateMessage: (String) -> Unit = {},
    showActions: Boolean = true
) {
    val isUser = message.role == ChatRole.USER
    val accentColor = LocalAccentColor.current
    
    var showContextMenu by remember { mutableStateOf(false) }
    
    val isDark = MaterialTheme.colorScheme.surface.luminance() <= 0.5f
    val brandPrimary = Color(0xFF74AA9C)
    val normalColor = MaterialTheme.colorScheme.onSurface
    val boldColor = normalColor
    val textSubColor = MaterialTheme.colorScheme.onSurfaceVariant
    
    // Theme-aware code block colors
    val codeBackgroundColor = MaterialTheme.colorScheme.surfaceVariant
    val codeHeaderBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val codeTextColor = MaterialTheme.colorScheme.primary
    
    val codeBorderColor = MaterialTheme.colorScheme.outline
    val linkColor = MaterialTheme.colorScheme.primary

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = if (isUser) 16.dp else 0.dp, 
                vertical = 4.dp
            ),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        @Composable
        fun MessageContent() {
            var actionsExpanded by remember { mutableStateOf(false) }
            Column(
                modifier = Modifier.padding(
                    horizontal = if (isUser) 16.dp else 16.dp,
                    vertical = if (isUser) 16.dp else 16.dp
                )
            ) {
                if (!isUser) {
                    
                    // THINKING SECTION - Smarty style (shows ABOVE content)
                    // During streaming: shows live thinking with expandable view
                    // After completion: shows "Thought for X seconds" collapsed
                    if (message.hasThinking || (message.isStreaming && !message.thinking.isNullOrBlank())) {
                        var thinkingExpanded by remember { mutableStateOf(message.isStreaming) }
                        val thinkingText = message.thinking ?: ""
                        
                        Surface(
                            onClick = { thinkingExpanded = !thinkingExpanded },
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (message.isStreaming) {
                                        val infiniteTransition = rememberInfiniteTransition(label = "thinking_pulse")
                                        val pulseAlpha by infiniteTransition.animateFloat(
                                            initialValue = 0.4f, targetValue = 1f,
                                            animationSpec = infiniteRepeatable(
                                                animation = tween(500, easing = LinearEasing),
                                                repeatMode = RepeatMode.Reverse
                                            ), label = "pulse"
                                        )
                                        Icon(
                                            imageVector = Icons.Default.AutoAwesome,
                                            contentDescription = null,
                                            tint = accentColor.copy(alpha = pulseAlpha),
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(
                                            text = "Thinking...",
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                fontWeight = FontWeight.Medium
                                            ),
                                            color = accentColor
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.AutoAwesome,
                                            contentDescription = null,
                                            tint = accentColor.copy(alpha = 0.7f),
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(
                                            text = "Thought process",
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                fontWeight = FontWeight.Medium
                                            ),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Spacer(modifier = Modifier.weight(1f))
                                    Icon(
                                        imageVector = if (thinkingExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                
                                AnimatedVisibility(
                                    visible = thinkingExpanded,
                                    enter = expandVertically() + fadeIn(),
                                    exit = shrinkVertically() + fadeOut()
                                ) {
                                    Column {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Box(
                                            modifier = Modifier
                                                .padding(start = 8.dp)
                                                .drawBehind {
                                                    drawLine(
                                                        color = accentColor.copy(alpha = 0.3f),
                                                        start = Offset(0f, 0f),
                                                        end = Offset(0f, this@drawBehind.size.height),
                                                        strokeWidth = 2.dp.toPx()
                                                    )
                                                }
                                                .padding(start = 12.dp)
                                        ) {
                                            Text(
                                                text = thinkingText,
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontFamily = FontFamily.Monospace,
                                                    lineHeight = 22.sp
                                                ),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    
                    // Show thinking dots when streaming with no content yet and no thinking
                    if (message.isStreaming && message.content.isEmpty() && message.thinking.isNullOrBlank()) {
                        // OPTIMIZED: Single animation drives all 3 dots via phase offset math
                        val infiniteTransition = rememberInfiniteTransition(label = "thinking")
                        val thinkingProgress by infiniteTransition.animateFloat(
                            initialValue = 0f, targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1800, easing = LinearEasing),
                                repeatMode = RepeatMode.Restart
                            ), label = "thinkDots"
                        )
                        // Derive 3 dot alphas from single progress with 120° phase separation
                        val pi2 = 2f * Math.PI.toFloat()
                        val dotAlpha1 = (0.2f + 0.8f * ((kotlin.math.sin(thinkingProgress * pi2) + 1f) / 2f))
                        val dotAlpha2 = (0.2f + 0.8f * ((kotlin.math.sin(thinkingProgress * pi2 + pi2 / 3f) + 1f) / 2f))
                        val dotAlpha3 = (0.2f + 0.8f * ((kotlin.math.sin(thinkingProgress * pi2 + 2f * pi2 / 3f) + 1f) / 2f))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            Text(
                                text = "Thinking",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 14.sp,
                                    fontStyle = FontStyle.Italic
                                ),
                                color = textSubColor
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(".", color = MaterialTheme.colorScheme.primary.copy(alpha = dotAlpha1), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text(".", color = MaterialTheme.colorScheme.primary.copy(alpha = dotAlpha2), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text(".", color = MaterialTheme.colorScheme.primary.copy(alpha = dotAlpha3), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    
                    // MAIN CONTENT - Shows BELOW thinking (Smarty style)
                    if (message.content.isNotEmpty()) {
                        // normalColor and boldColor from outer scope

                        val rawContent = if (isUser) message.content else cleanContent(message.content)
                        val targetLength = rawContent.length
                        
                        // State that persists across content updates within same message
                        // Only resets when message.id changes (new message)
                        var displayPosition by remember(message.id) { 
                            mutableIntStateOf(if (message.isStreaming) 0 else targetLength) 
                        }
                        
                        // Run typewriter animation
                        LaunchedEffect(message.isStreaming, targetLength) {
                            if (message.isStreaming) {
                                // Continue from current position, not restart
                                val charsPerFrame = 2
                                while (displayPosition < targetLength) {
                                    val remaining = targetLength - displayPosition
                                    val step = minOf(charsPerFrame, remaining)
                                    displayPosition += step
                                    delay(33)
                                }
                            } else {
                                displayPosition = targetLength
                            }
                        }
                        
                        // Get visible content
                        val visibleContent = remember(displayPosition, rawContent) {
                            if (displayPosition >= rawContent.length) rawContent 
                            else rawContent.substring(0, displayPosition)
                        }

                        // Professional Markdown Rendering - Live Streaming with Typewriter Effect
                        MarkdownRenderer(
                            content = visibleContent,
                            isUser = isUser,
                            normalColor = normalColor,
                            boldColor = boldColor,
                            linkColor = linkColor,
                            codeColor = codeTextColor,
                            codeBackgroundColor = codeBackgroundColor,
                            codeBorderColor = codeBorderColor,
                            codeHeaderBg = codeHeaderBg,
                            isStreaming = message.isStreaming && displayPosition < targetLength
                        )
                        
                        if (message.isStreaming && displayPosition < targetLength) {
                            // Streaming Cursor Attachment
                            val infiniteTransition = rememberInfiniteTransition(label = "cursor")
                            val cursorAlpha by infiniteTransition.animateFloat(
                                initialValue = 1f, targetValue = 0f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(1000, easing = LinearEasing),
                                    repeatMode = RepeatMode.Restart
                                ), label = "cursor_opacity"
                            )
                            Box(modifier = Modifier.padding(top=4.dp)) {
                                Box(
                                    modifier = Modifier
                                        .padding(start = 2.dp)
                                        .size(width = 8.dp, height = 20.dp)
                                        .background(brandPrimary.copy(alpha = if (cursorAlpha > 0.5f) 1f else 0f))
                                )
                            }
                        }

                    // Inline Image Preview
                    if (!isUser && message.hasInlineImages) {
                        Spacer(modifier = Modifier.height(12.dp))

                        var showFullScreen by remember { mutableStateOf(false) }
                        var fullScreenIndex by rememberSaveable { mutableIntStateOf(0) }

                        InlineImagePreview(
                            images = message.inlineImages,
                            onExpandImage = { index ->
                                fullScreenIndex = index
                                showFullScreen = true
                            },
                            modifier = Modifier
                                .widthIn(max = ComponentSpacing.bubbleMaxWidth)
                                .clip(RoundedCornerShape(16.dp))
                        )

                        if (showFullScreen && message.inlineImages.isNotEmpty()) {
                            val currentImage = message.inlineImages.getOrNull(fullScreenIndex)
                                ?: message.inlineImages.first()
                            FullScreenImageViewer(
                                imageUri = currentImage.uri,
                                onDismiss = { showFullScreen = false },
                                contentDescription = currentImage.fileName
                            )
                        }
                    }

                    // Referenced Notes
                    if (!isUser && message.referencedNoteIds.isNotEmpty()) {
                        val actionNoteIds = message.executedActions.flatMap { it.affectedNoteIds }.toSet()
                        val relevantNotes = message.referencedNoteIds
                            .filter { it !in actionNoteIds }
                            .mapNotNull { getNote(it) }

                        if (relevantNotes.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            InlineNotePreview(
                                notes = relevantNotes,
                                onNoteClick = { onNoteClick(it) },
                                modifier = Modifier
                                    .widthIn(max = ComponentSpacing.bubbleMaxWidth)
                                    .clip(RoundedCornerShape(16.dp))
                            )
                        }
                    }

                    // User Images (Inline)
                    val imageAttachments = message.attachments.filter { it.getAttachmentType() == com.example.smarty.core.domain.model.AttachmentType.IMAGE }
                    if (isUser && imageAttachments.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        val inlineImages = imageAttachments.map {
                            com.example.smarty.core.domain.model.InlineChatImage(
                                uri = it.uri,
                                fileName = it.fileName,
                                noteTitle = ""
                            )
                        }

                        var showFullScreen by remember { mutableStateOf(false) }
                        var fullScreenIndex by rememberSaveable { mutableIntStateOf(0) }

                        InlineImagePreview(
                            images = inlineImages,
                            onExpandImage = { index ->
                                fullScreenIndex = index
                                showFullScreen = true
                            },
                            modifier = Modifier
                                .widthIn(max = ComponentSpacing.bubbleMaxWidth)
                                .clip(RoundedCornerShape(16.dp))
                        )

                        if (showFullScreen) {
                             val currentImage = inlineImages.getOrNull(fullScreenIndex) ?: inlineImages.first()
                             FullScreenImageViewer(
                                 imageUri = currentImage.uri,
                                 onDismiss = { showFullScreen = false },
                                 contentDescription = currentImage.fileName
                              )
                        }
                    }

                    // Other Attachments Count
                    val otherAttachments = message.attachments.filter { it.getAttachmentType() != com.example.smarty.core.domain.model.AttachmentType.IMAGE }
                    if (otherAttachments.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            color = if (isUser) accentColor.copy(alpha = Alpha.medium) else MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            val count = otherAttachments.size
                            Text(
                                text = if (count == 1) stringResource(R.string.one_attachment) else stringResource(R.string.x_attachments, count),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Medium,
                                    letterSpacing = 0.4.sp
                                ),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                color = if (isUser) accentColor else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Agent Activity (Collapsible)
                    if (!isUser && message.hasActions) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                // Clickable header
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { actionsExpanded = !actionsExpanded },
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Outlined.AutoAwesome,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "${message.executedActions.size} action${if (message.executedActions.size > 1) "s" else ""} performed",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Icon(
                                        imageVector = if (actionsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = if (actionsExpanded) "Collapse" else "Expand",
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                // Expandable content
                                AnimatedVisibility(visible = actionsExpanded) {
                                    Column(modifier = Modifier.padding(top = 8.dp)) {
                                        message.executedActions.forEach { actionResult ->
                                            ActionResultChip(
                                                actionName = actionResult.action,
                                                success = actionResult.success,
                                                summary = actionResult.resultSummary
                                            )
                                            if (actionResult.affectedNoteIds.isNotEmpty()) {
                                                Spacer(modifier = Modifier.height(6.dp))
                                                actionResult.affectedNoteIds.forEach { noteId ->
                                                    val note = getNote(noteId)
                                                    if (note != null) {
                                                        NoteCard(
                                                            note = note,
                                                            onClick = { onNoteClick(note) },
                                                            onDelete = {},
                                                            onOpenTodo = {},
                                                            modifier = Modifier.fillMaxWidth(),
                                                            isSelectionMode = true,
                                                            onLongPress = { }
                                                        )
                                                        Spacer(modifier = Modifier.height(4.dp))
                                                    }
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(6.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Clarification request
                    message.clarificationRequest?.let { request ->
                        Spacer(modifier = Modifier.height(12.dp))
                        ClarificationBubble(
                            request = request,
                            onSubmit = onClarificationSubmit,
                            accentColor = accentColor
                        )
                    }
                    } // end if (message.content.isNotEmpty())
                } // end if !isUser
            }
        }

        // Apply Bubble or Container (isDark already computed above)

        // Inverted colors for user bubble: opposite of theme
        val userBubbleBackground = if (isDark) Color(0xFFF5F5F5) else Color(0xFF1A1A1A)
        val userBubbleTextColor = if (isDark) Color(0xFF1A1A1A) else Color(0xFFF5F5F5)
        
        // Dynamic pulse-shaped bubble - corners adapt based on content length
        // Longer content = more rounded, shorter content = more pill-like
        val userBubbleShape = RoundedCornerShape(
            topStart = 26.dp,
            topEnd = 6.dp,
            bottomStart = 26.dp,
            bottomEnd = 26.dp
        )

if (isUser) {
            Box(
                modifier = Modifier
                    .widthIn(max = 640.dp)
            ) {
                Surface(
                    color = userBubbleBackground,
                    shape = userBubbleShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = {},
                            onLongClick = { if (showActions) showContextMenu = true }
                        )
                ) {
                    Box(
                        modifier = Modifier.padding(
                            horizontal = 16.dp,
                            vertical = 16.dp
                        )
                    ) {
                        val userTextColor = userBubbleTextColor
                        Text(
                            text = message.content,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.SansSerif,
                                fontSize = 16.sp,
                                lineHeight = 26.sp,
                                fontWeight = FontWeight.Medium,
                                letterSpacing = 0.sp
                            ),
                            color = userTextColor
                        )
                    }
                }
                DropdownMenu(
                    expanded = showContextMenu,
                    onDismissRequest = { showContextMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.copy)) },
                        onClick = {
                            showContextMenu = false
                            onCopyMessage(message.content)
                        },
                        leadingIcon = {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete)) },
                        onClick = {
                            showContextMenu = false
                            onDeleteMessage(message.id)
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { if (showActions) showContextMenu = true }
                    )
            ) {
                MessageContent()
            }
            DropdownMenu(
                expanded = showContextMenu,
                onDismissRequest = { showContextMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.copy)) },
                    onClick = {
                        showContextMenu = false
                        onCopyMessage(cleanContent(message.content))
                    },
                    leadingIcon = {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.delete)) },
                    onClick = {
                        showContextMenu = false
                        onDeleteMessage(message.id)
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.regenerate)) },
                    onClick = {
                        showContextMenu = false
                        onRegenerateMessage(message.id)
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                )
            }
        }

        // Suggestions
        if (!isUser && message.hasSuggestions) {
            Row(
                modifier = Modifier
                    .padding(top = 10.dp)
                    .widthIn(max = ComponentSpacing.bubbleMaxWidth),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                message.suggestions.take(2).forEach { suggestion ->
                    Surface(
                        onClick = { onSuggestionClick(suggestion) },
                        shape = RoundedCornerShape(14.dp),
                        color = accentColor.copy(alpha = Alpha.hint),
                        border = BorderStroke(1.dp, accentColor.copy(alpha = Alpha.moderate))
                    ) {
                        Text(
                            text = suggestion,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Medium
                            ),
                            color = accentColor,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            maxLines = 1
                        )
                    }
                }
            }
        }

        // Timestamp
        val timestampBubbleColor = if (isUser) userBubbleTextColor.copy(alpha = 0.6f) else textSubColor
        if (isUser) {
            Row(
                modifier = Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatTimestamp(message.timestamp),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Medium
                    ),
                    color = timestampBubbleColor,
                    fontSize = 11.sp
                )
            }
        } else {
            val clipboardManager = LocalClipboardManager.current
            var showCopied by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()

            LaunchedEffect(showCopied) {
                if (showCopied) {
                    delay(1500)
                    showCopied = false
                }
            }

            Row(
                modifier = Modifier
                    .padding(top = 6.dp, start = 12.dp, end = 4.dp)
                    .widthIn(max = ComponentSpacing.bubbleMaxWidth)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Actions (Copy on Left)
                Icon(
                    imageVector = if (showCopied) Icons.Default.CheckCircle else Icons.Default.ContentCopy,
                    contentDescription = stringResource(R.string.copy),
                    modifier = Modifier
                        .size(IconSize.small)
                        .clickable {
                            clipboardManager.setText(AnnotatedString(if (isUser) message.content else cleanContent(message.content)))
                            showCopied = true
                        },
                    tint = if (showCopied) accentColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = Alpha.half)
                )

                // Context (Time on Right)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (message.hasCitations) {
                        CitationsInline(
                            citations = message.citations,
                            accentColor = accentColor
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                    }

                    Text(
                        text = formatTimestamp(message.timestamp),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Medium
                        ),
                        color = textSubColor,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun CitationsInline(
    citations: List<Citation>,
    accentColor: Color
) {
    var showSelectionPopup by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current

    Surface(
        onClick = { showSelectionPopup = true },
        shape = RoundedCornerShape(50),
        color = accentColor.copy(alpha = 0.1f),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.2f)),
        modifier = Modifier.height(28.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Description,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = stringResource(R.string.sources_count, citations.size),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    letterSpacing = 0.5.sp
                ),
                color = accentColor
            )
        }
    }

    if (showSelectionPopup) {
        Popup(
            alignment = Alignment.Center,
            onDismissRequest = { showSelectionPopup = false },
            properties = PopupProperties(
                focusable = true,
                dismissOnBackPress = true,
                dismissOnClickOutside = true
            )
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 16.dp,
                modifier = Modifier
                    .widthIn(min = 300.dp, max = 360.dp)
                    .heightIn(max = 520.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 20.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = accentColor.copy(alpha = 0.1f),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    tint = accentColor,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.sources),
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp,
                                    letterSpacing = (-0.5).sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.references_found, citations.size),
                                style = MaterialTheme.typography.bodySmall.copy(letterSpacing = 0.2.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        modifier = Modifier.padding(bottom = 20.dp)
                    )

                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        citations.forEachIndexed { index, citation ->
                            SourceCard(
                                citation = citation,
                                index = index + 1,
                                onClick = {
                                    try {
                                        uriHandler.openUri(citation.url)
                                        showSelectionPopup = false
                                    } catch (e: Exception) { }
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Text(
                        text = stringResource(R.string.tap_outside_to_close),
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.4.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceCard(
    citation: Citation,
    index: Int,
    onClick: () -> Unit
) {
    val domain = try {
        java.net.URI(citation.url).host?.removePrefix("www.") ?: "link"
    } catch (e: Exception) { "link" }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
        modifier = Modifier
            .fillMaxWidth()
            .softCardShadow(shape = RoundedCornerShape(24.dp))
            .zIndex(1f)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(24.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = index.toString(),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Text(
                    text = citation.title.ifBlank { stringResource(R.string.untitled_source) }.lowercase(),
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 20.sp,
                        letterSpacing = 0.1.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(8.dp))

                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Launch,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(16.dp)
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = domain,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }

            if (citation.snippet.isNotBlank()) {
                 Text(
                    text = citation.snippet,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ActionResultChip(
    actionName: String,
    success: Boolean,
    summary: String
) {
    val isDark = MaterialTheme.colorScheme.surface.luminance() <= 0.5f
    // ElevenLabs 'Badge' Style: Thin border, subtle background, crisp text
    val backgroundColor = if (success) {
        if (isDark) Color(0xFF064E3B) else Color(0xFFECFDF5) // Green-900 / Green-50
    } else {
        if (isDark) Color(0xFF7F1D1D) else Color(0xFFFEF2F2) // Red-900 / Red-50
    }
    
    val contentColor = if (success) {
        if (isDark) Color(0xFF34D399) else Color(0xFF059669) // Green-400 / Green-600
    } else {
        if (isDark) Color(0xFFF87171) else Color(0xFFDC2626) // Red-400 / Red-600
    }

    val borderColor = contentColor.copy(alpha = 0.3f)

    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = backgroundColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Icon(
                imageVector = if (success) Icons.Default.Check else Icons.Default.Close,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = contentColor
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = formatActionName(actionName),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 11.sp,
                    letterSpacing = 0.sp
                ),
                color = contentColor
            )
            if (summary.isNotBlank()) {
                Spacer(modifier = Modifier.width(6.dp))
                // Separator dot
                Box(
                    modifier = Modifier
                        .size(3.dp)
                        .background(contentColor.copy(alpha = 0.5f), CircleShape)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = summary,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Normal
                    ),
                    color = contentColor.copy(alpha = 0.9f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun formatActionName(actionName: String): String {
    return actionName
        .replace("Action", "")
        .replace(MarkdownPatterns.actionNameSplit, " $1")
        .trim()
}

@Composable
fun CodeBlock(
    code: String,
    language: String,
    backgroundColor: Color,
    borderColor: Color,
    headerBgColor: Color = Color(0xFF343541)
) {
    val clipboardManager = LocalClipboardManager.current
    var isCopied by remember { mutableStateOf(false) }
    val isDark = MaterialTheme.colorScheme.surface.luminance() <= 0.5f

    // ElevenLabs Theme: Zinc colors passed from parent
    val textColor = if (isDark) Color(0xFFE4E4E7) else Color(0xFF18181B) // Zinc-200 / Zinc-950
    val headerColor = headerBgColor

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .background(backgroundColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(headerColor)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = language.ifBlank { "code" }.lowercase(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                ),
                color = if (isDark) Color(0xFFAAAAAA) else Color(0xFF666666)
            )

            Row(
                modifier = Modifier
                    .clickable {
                        clipboardManager.setText(AnnotatedString(code))
                        isCopied = true
                    }
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = if (isCopied) Icons.Default.Check else Icons.Default.ContentCopy,
                    contentDescription = "Copy code",
                    tint = if (isCopied) MaterialTheme.colorScheme.primary else if (isDark) Color(0xFFAAAAAA) else Color(0xFF666666),
                    modifier = Modifier.size(14.dp)
                )
                if (isCopied) {
                    Text(
                        text = "Copied",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    LaunchedEffect(Unit) {
                        delay(2000)
                        isCopied = false
                    }
                }
            }
        }

        Box(modifier = Modifier.padding(12.dp)) {
            Text(
                text = code,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 15.sp,
                    lineHeight = 23.sp
                ),
                color = textColor,
                modifier = Modifier.horizontalScroll(rememberScrollState())
            )
        }
    }
}

@Composable
private fun formatTimestamp(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60_000 -> stringResource(R.string.just_now)
        diff < 3600_000 -> stringResource(R.string.minutes_ago, diff / 60_000)
        diff < 86400_000 -> stringResource(R.string.hours_ago, diff / 3600_000)
        else -> {
            timestampDateFormat.format(java.util.Date(timestamp)).lowercase()
        }
    }
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
                        trimmedLine.matches(MarkdownPatterns.taskListUnchecked) || 
                        trimmedLine.matches(MarkdownPatterns.taskListChecked) -> {
                            val taskItems = mutableListOf<Pair<Boolean, String>>()
                            
                            // Parse first item
                            val taskMatch = MarkdownPatterns.taskListParse.find(trimmedLine)
                            if (taskMatch != null) {
                                val isChecked = taskMatch.groupValues[1].trim().isNotEmpty()
                                val taskText = taskMatch.groupValues[2]
                                taskItems.add(Pair(isChecked, taskText))
                            }
                            
                            i++
                            while (i < lines.size) {
                                val nextTrimmed = lines[i].trim()
                                val nextMatch = MarkdownPatterns.taskListParse.find(nextTrimmed)
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
                                    // Continuation of previous task item (no regex needed)
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
                                    nextTrimmed.startsWith("> ") || nextTrimmed.matches(MarkdownPatterns.taskListDetect) ||
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
                        !trimmedLine.matches(MarkdownPatterns.numberedListNotTask) -> {
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
                        
                        // Blockquote - ChatBot Style
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
                        trimmedLine.matches(MarkdownPatterns.horizontalRule) -> {
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
        if (index == 1 && line.replace(MarkdownPatterns.tableSeparator, "").isEmpty()) {
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
    val hasInlineMath = text.contains(MarkdownPatterns.inlineMathDetect)
    
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

@Composable
private fun ClarificationBubble(
    request: ClarificationRequest,
    onSubmit: (String) -> Unit,
    accentColor: Color
) {
    var customInput by remember { mutableStateOf("") }
    var isSubmitted by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.clarification_needed),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    ),
                    color = accentColor
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = request.question,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp,
                    lineHeight = 24.sp
                ),
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(16.dp))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                request.options.forEach { option ->
                    Surface(
                        onClick = {
                            if (!isSubmitted) {
                                isSubmitted = true
                                onSubmit(option)
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSubmitted) MaterialTheme.colorScheme.surfaceVariant else accentColor.copy(alpha = 0.1f),
                        border = BorderStroke(1.dp, if (isSubmitted) Color.Transparent else accentColor.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isSubmitted
                    ) {
                        Text(
                            text = option,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 15.sp
                            ),
                            color = if (isSubmitted) MaterialTheme.colorScheme.onSurfaceVariant else accentColor,
                            modifier = Modifier.padding(12.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }

            if (request.allowCustomInput) {
                Spacer(modifier = Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = customInput,
                        onValueChange = { customInput = it },
                        placeholder = { Text(stringResource(R.string.other), fontSize = 14.sp) },
                        modifier = Modifier.weight(1f),
                        textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        enabled = !isSubmitted,
                        trailingIcon = {
                            if (customInput.isNotBlank() && !isSubmitted) {
                                IconButton(
                                    onClick = {
                                        isSubmitted = true
                                        onSubmit(customInput)
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Send,
                                        contentDescription = stringResource(R.string.submit),
                                        tint = accentColor
                                    )
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

private fun cleanContent(raw: String): String {
    var text = raw
    text = text.replace(THINK_TAG_REGEX, "")
    text = text.replace(THINK_OPEN_REGEX, "")
    text = text.replace("<final>", "").replace("</final>", "")
    
    // Clean up partial tags when streaming
    text = text.replace(PARTIAL_FINAL_REGEX, "")
    text = text.replace(PARTIAL_THINK_REGEX, "")
    
    return text.trim()
}
