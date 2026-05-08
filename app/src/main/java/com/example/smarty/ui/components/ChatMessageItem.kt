package com.example.smarty.ui.components

import android.util.Log
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
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

// Import extracted chat components for DRY principle
import com.example.smarty.ui.components.chat.ThinkingSection
import com.example.smarty.ui.components.chat.ThinkingDots
import com.example.smarty.ui.components.chat.TextEffectPerWord
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import com.example.smarty.R
import com.example.smarty.core.domain.model.ChatMessage
import com.example.smarty.core.domain.model.ChatRole
import com.example.smarty.core.domain.model.Citation
import com.example.smarty.core.domain.model.ClarificationRequest
import com.example.smarty.core.domain.model.Note
import com.example.smarty.core.domain.model.NoteReference
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.components.viewers.FullScreenImageViewer
import com.example.smarty.ui.theme.Alpha
import com.example.smarty.ui.theme.ComponentSpacing
import com.example.smarty.ui.theme.IconSize
import com.example.smarty.ui.theme.LocalShapes
import com.example.smarty.ui.theme.MonoFont
import com.example.smarty.ui.theme.thinkingColors
import com.example.smarty.ui.theme.smartyShapes
import com.example.smarty.ui.theme.softCardShadow
import com.example.smarty.ui.components.LaTeXView
// Single source of truth for all markdown rendering (DRY)
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "ChatMessageItem"

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

// Precompiled regex for cleanContent â€” called on every AI message render
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
    onNoteClickById: (String) -> Unit = {},
    onEventClickById: (String) -> Unit = {},
    onSuggestionClick: (String) -> Unit = {},
    onClarificationSubmit: (String) -> Unit = {},
    onCopyMessage: (String) -> Unit = {},
    onDeleteMessage: (String) -> Unit = {},
    onEditMessage: ((com.example.smarty.core.domain.model.ChatMessage) -> Unit)? = null,
    onRegenerateMessage: (String) -> Unit = {},
    showActions: Boolean = true
) {
    val isUser = message.role == ChatRole.USER
    val accentColor = LocalAccentColor.current
    
    var showContextMenu by remember { mutableStateOf(false) }
    
    val isDark = MaterialTheme.colorScheme.surface.luminance() <= 0.5f
    val textSubColor = MaterialTheme.colorScheme.onSurfaceVariant

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
            Column(
                modifier = Modifier.padding(
                    horizontal = if (isUser) 16.dp else 16.dp,
                    vertical = if (isUser) 16.dp else 16.dp
                ),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                if (!isUser) {

                    // ACTION PANEL - Unified Thinking + Tool Calls (DRY: extracted ThinkingSection)
                    // Shows during streaming: live thinking with tool-call action cards interleaved
                    // After done: collapsed "Thoughts (N actions)" header, expandable
                    if (message.hasActionPanel || (message.isStreaming && !message.thinking.isNullOrBlank())) {
                        var thinkingExpanded by remember { mutableStateOf(message.isStreaming || message.hasActionPanel) }
                        val thinkingText = message.thinking ?: ""

                        ThinkingSection(
                            thinkingText   = thinkingText,
                            isExpanded     = thinkingExpanded,
                            isStreaming    = message.isStreaming,
                            onExpandToggle = { thinkingExpanded = !thinkingExpanded },
                            toolCalls      = message.toolCalls
                        )
                    }

                    // Show thinking dots when streaming with no content yet and no thinking
                    if (message.isStreaming && message.content.isEmpty() && message.thinking.isNullOrBlank()) {
                        ThinkingDots()
                    }
                    
// MAIN CONTENT - Shows BELOW thinking (Smarty style)
                     if (message.content.isNotEmpty()) {
                         val rawContent = if (isUser) message.content else cleanContent(message.content)
                         val parsedAccordion = com.example.smarty.ui.components.chat.AccordionParser.parse(rawContent)
                         
                         if (parsedAccordion.accordions.isNotEmpty()) {
                             com.example.smarty.ui.components.chat.AccordionResponse(
                                 parsedContent = parsedAccordion,
                                 modifier = Modifier.fillMaxWidth()
                             )
} else {
                              TextEffectPerWord(
                                  text = rawContent,
                                  textStyle = MaterialTheme.typography.bodyMedium.copy(
                                      fontFamily = FontFamily.SansSerif,
                                      fontSize = 16.sp,
                                      lineHeight = 22.sp,
                                      fontWeight = FontWeight.Medium,
                                      letterSpacing = 0.sp
                                  ),
                                  normalColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                                  boldColor = MaterialTheme.colorScheme.onSurface,
                                  linkColor = accentColor,
                                  codeColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                              )
                          }

                        // Krea Image Generation direct inline display
                        val generateImageCall = message.toolCalls.find { it.toolName == "generate_image" }
                        if (generateImageCall != null) {
                            Spacer(modifier = Modifier.height(16.dp))
                            val state = when (generateImageCall.status) {
                                "completed" -> com.example.smarty.ui.components.krea.ImageGenState.Completed
                                "failed", "error" -> com.example.smarty.ui.components.krea.ImageGenState.Error
                                else -> com.example.smarty.ui.components.krea.ImageGenState.Thinking
                            }

                            // The URL might be stored in outputSummary once available
                            val imageUrl = generateImageCall.outputSummary?.let { summary ->
                                android.util.Log.d("ChatMessageItem", "Image generation outputSummary: $summary")
                                if (summary.startsWith("http")) summary
                                else if (summary.startsWith("{") && summary.contains("\"url\"")) {
                                    // Robust extraction of url from JSON string without adding heavy dependencies
                                    val extracted = summary.substringAfter("\"url\":").substringAfter("\"").substringBefore("\"")
                                    android.util.Log.d("ChatMessageItem", "Extracted image URL: $extracted")
                                    extracted
                                } else {
                                    android.util.Log.w("ChatMessageItem", "Could not extract image URL from summary: $summary")
                                    null
                                }
                            }
                            android.util.Log.d("ChatMessageItem", "Final imageUrl: $imageUrl")
                            
                            com.example.smarty.ui.components.krea.ImageGenerationCard(
                                state = state,
                                mode = if (generateImageCall.displayName.contains("Direct", ignoreCase = true)) 
                                    com.example.smarty.ui.components.krea.ImageGenMode.Direct
                                else 
                                    com.example.smarty.ui.components.krea.ImageGenMode.Agent,
                                prompt = generateImageCall.inputSummary ?: message.content.takeIf { it.isNotBlank() } ?: "Generating image...",
                                imageUrl = imageUrl,
                                onRemix = { onRegenerateMessage(message.id) },
                                onRetry = { onRegenerateMessage(message.id) }
                            )
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

                    // Clarification request removed from here - it's now handled in the input block area

                    // Note references (clickable note cards)
                    if (message.noteReferences.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            message.noteReferences.forEach { noteRef ->
                                NoteBlockCard(
                                    noteReference = noteRef,
                                    onClick = { onNoteClickById(noteRef.noteId) },
                                    accentColor = accentColor
                                )
                            }
                        }
                    }

                    // Event references (clickable event cards)
                    if (message.eventReferences.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            message.eventReferences.forEach { eventRef ->
                                EventBlockCard(
                                    eventReference = eventRef,
                                    onClick = { onEventClickById(eventRef.eventId) },
                                    accentColor = accentColor
                                )
                            }
                        }
                    }
                    } // end if (message.content.isNotEmpty())
                } // end if !isUser
            }
        }

        // Apply Bubble or Container (isDark already computed above)

        // Inverted colors for user bubble
        // TODO: For Dark Theme - currently using off-white, consider reducing intensity or using a different shade later
        val userBubbleBackground = if (isDark) Color(0xFFF5F5F5) else accentColor.copy(alpha = 0.2f)
        val userBubbleTextColor = if (isDark) {
            Color(0xFF1A1A1A)
        } else {
            Color.Black
        }
        
        // Pill-shaped bubble for user messages
        val userBubbleShape = RoundedCornerShape(20.dp)

if (isUser) {
            Box(
                modifier = Modifier
                    .padding(start = 48.dp)
                    .widthIn(max = 640.dp)
            ) {
                Surface(
                    color = userBubbleBackground,
                    shape = userBubbleShape,
                ) {
                    Box(
                        modifier = Modifier.padding(
                            horizontal = 12.dp,
                            vertical = 10.dp
                        )
                    ) {
                        val userTextColor = userBubbleTextColor
                        SelectionContainer {
                            Text(
                                text = message.content,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = FontFamily.SansSerif,
                                    fontSize = 16.sp,
                                    lineHeight = 22.sp,
                                    fontWeight = FontWeight.Medium,
                                    letterSpacing = 0.sp
                                ),
                                color = userTextColor
                            )
                        }
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
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        onClick = {
                            showContextMenu = false
                            onEditMessage?.invoke(message)
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    )
                }
            }
        } else {
            // Assistant message â€” SelectionContainer enables text selection
            SelectionContainer {
                Box(
                    modifier = Modifier
                        .widthIn(max = 640.dp)
                        .fillMaxWidth()
                ) {
                    MessageContent()
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

                        val serverConfidence = message.confidence
                        val sourceCount = message.citations.size
                        val confidenceColor = when (serverConfidence) {
                            "verified" -> androidx.compose.ui.graphics.Color(0xFF4CAF50)
                            "moderate" -> androidx.compose.ui.graphics.Color(0xFFFFA726)
                            else -> androidx.compose.ui.graphics.Color(0xFF9E9E9E)
                        }
                        val confidenceText = when (serverConfidence) {
                            "verified" -> "Verified"
                            "moderate" -> "Moderate"
                            "model_knowledge" -> "AI Knowledge"
                            else -> when {
                                sourceCount >= 3 -> "Verified"
                                sourceCount >= 2 -> "Moderate"
                                else -> "Unverified"
                            }
                        }

                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = confidenceColor.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, confidenceColor.copy(alpha = 0.25f)),
                            modifier = Modifier.height(24.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Icon(
                                    imageVector = if (sourceCount >= 3) Icons.Default.CheckCircle else Icons.Default.Info,
                                    contentDescription = null,
                                    tint = confidenceColor,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = confidenceText,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 10.sp
                                    ),
                                    color = confidenceColor
                                )
                            }
                        }

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
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Failed to open citation URL: ${citation.url}", e)
                                    }
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

// NOTE: MarkdownRenderer, MarkdownTable, StandardText, RichTextWithLatex,
// and parseMarkdownToAnnotatedString have been consolidated into
// com.example.smarty.ui.components.markdown.MarkdownRenderer
// and com.example.smarty.ui.components.markdown.MarkdownParser
// to eliminate code duplication. See those files for the canonical implementations.


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

@Composable
private fun NoteBlockCard(
    noteReference: NoteReference,
    onClick: () -> Unit,
    accentColor: Color
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = accentColor.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = noteReference.title,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = accentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = noteReference.snippet,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Launch,
                contentDescription = stringResource(R.string.open_note),
                tint = accentColor.copy(alpha = 0.6f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun EventBlockCard(
    eventReference: com.example.smarty.core.domain.model.EventReference,
    onClick: () -> Unit,
    accentColor: Color
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = accentColor.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = eventReference.title,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = accentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = eventReference.timeSnippet,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Launch,
                contentDescription = stringResource(R.string.open_note),
                tint = accentColor.copy(alpha = 0.6f),
                modifier = Modifier.size(16.dp)
            )
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

