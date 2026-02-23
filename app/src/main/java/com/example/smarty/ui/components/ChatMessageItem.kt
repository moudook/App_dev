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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AutoAwesome
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Pre-compiled regex patterns for markdown parsing.
 */
private object MarkdownPatterns {
    val bold = Regex("\\*\\*([\\s\\S]+?)\\*\\*")
    val italic = Regex("(?<!\\*)\\*(?!\\*)([\\s\\S]+?)(?<!\\*)\\*(?!\\*)")
    val inlineCode = Regex("`([\\s\\S]+?)`")
    val link = Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)")
    val underline = Regex("__([\\s\\S]+?)__")
    val italicUnderscore = Regex("(?<!_)_(?!_)([\\s\\S]+?)(?<!_)_(?!_)")
    
    // LaTeX math patterns
    val inlineMath = Regex("\\$([^$]+)\\$")
    val blockMath = Regex("\\$\\$([\\s\\S]+?)\\$\\$")
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
@Composable
fun ChatMessageItem(
    message: ChatMessage,
    modifier: Modifier = Modifier,
    groupPosition: MessageGroupPosition = MessageGroupPosition.SINGLE,
    getNote: (String) -> Note? = { null },
    onNoteClick: (Note) -> Unit = {},
    onSuggestionClick: (String) -> Unit = {},
    onClarificationSubmit: (String) -> Unit = {}
) {
    val isUser = message.role == ChatRole.USER
    val accentColor = LocalAccentColor.current
    
    // Modern AIGBT Theme Colors
    val isDark = isSystemInDarkTheme()
    val brandPrimary = Color(0xFF74AA9C)
    val normalColor = MaterialTheme.colorScheme.onSurface
    val boldColor = normalColor
    val textSubColor = MaterialTheme.colorScheme.onSurfaceVariant
    
    val codeBackgroundColor = Color(0xFF181818) // Constant charcoal dark for code blocks
    val codeHeaderBg = Color(0xFF343541)
    
    val codeBorderColor = if (isDark) Color(0xFF27272A) else Color(0xFFE4E4E7)
    val linkColor = if (isDark) Color(0xFF60A5FA) else Color(0xFF2563EB)
    
    // Bubble Geometry based on spec (Removed)
    val bubbleShape = RoundedCornerShape(0.dp) // Removed bubble shapes entirely

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = if (isUser) 16.dp else 0.dp, 
                vertical = 4.dp
            ),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        // Content Logic
        @Composable
        fun MessageContent() {
            var actionsExpanded by remember { mutableStateOf(false) }
            Column(
                modifier = Modifier.padding(
                    horizontal = if (isUser) 16.dp else 16.dp,
                    vertical = if (isUser) 16.dp else 16.dp
                )
            ) {
                // User messages are rendered in the outer wrapper with bubble styling
                // Only render AI content here
                if (!isUser) {
                    
                    // THINKING SECTION - ChatGPT style (shows ABOVE content)
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
                        val infiniteTransition = rememberInfiniteTransition(label = "thinking")
                        val dotAlpha1 by infiniteTransition.animateFloat(
                            initialValue = 0.2f, targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(600, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse
                            ), label = "dot1"
                        )
                        val dotAlpha2 by infiniteTransition.animateFloat(
                            initialValue = 0.2f, targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(600, delayMillis = 200, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse
                            ), label = "dot2"
                        )
                        val dotAlpha3 by infiniteTransition.animateFloat(
                            initialValue = 0.2f, targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(600, delayMillis = 400, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse
                            ), label = "dot3"
                        )
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
                    
                    // MAIN CONTENT - Shows BELOW thinking (ChatGPT style)
                    if (message.content.isNotEmpty()) {
                        val normalColor = MaterialTheme.colorScheme.onSurface
                        val boldColor = normalColor

                        // Professional Markdown Rendering
                        MarkdownRenderer(
                            content = if (isUser) message.content else cleanContent(message.content),
                            isUser = isUser,
                            normalColor = normalColor,
                            boldColor = boldColor, // Strict uniform color per spec
                            linkColor = linkColor,
                            codeColor = brandPrimary,
                            codeBackgroundColor = codeBackgroundColor,
                            codeBorderColor = codeHeaderBg,
                            codeHeaderBg = codeHeaderBg
                        )
                        
                        if (message.isStreaming) {
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

        // Apply Bubble or Container
        val isDark = isSystemInDarkTheme()

        // Inverted colors for user bubble: opposite of theme
        val userBubbleBackground = if (isDark) Color(0xFFF5F5F5) else Color(0xFF1A1A1A)
        val userBubbleTextColor = if (isDark) Color(0xFF1A1A1A) else Color(0xFFF5F5F5)
        val userBubbleShape = RoundedCornerShape(
            topStart = 20.dp,
            topEnd = 4.dp,
            bottomStart = 20.dp,
            bottomEnd = 20.dp
        )

        if (isUser) {
            Box(
                modifier = Modifier
                    .widthIn(max = 640.dp)
            ) {
                Surface(
                    color = userBubbleBackground,
                    shape = userBubbleShape,
                    modifier = Modifier.fillMaxWidth()
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
                                fontWeight = FontWeight.Normal,
                                letterSpacing = 0.sp
                            ),
                            color = userTextColor
                        )
                    }
                }
            }
        } else {
            // AI Response uses bg-bubble as a subtle container according to AIGBT specification
            Box(
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
            ) {
                MessageContent()
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
    val isDark = isSystemInDarkTheme()
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
        .replace(Regex("([A-Z])"), " $1")
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
    val isDark = isSystemInDarkTheme()

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
            val date = java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault())
            date.format(java.util.Date(timestamp)).lowercase()
        }
    }
}

/**
 * Enhanced Markdown Renderer
 * Supports: Headers, Lists, Links, Bold, Italic, and Code Blocks
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
    codeHeaderBg: Color = Color(0xFF343541)
) {
    val parts = content.split("```")

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
                        
                        // Lists (Bullets)
                        trimmedLine.startsWith("- ") || trimmedLine.startsWith("* ") -> {
                            val itemLines = mutableListOf<String>()
                            itemLines.add(trimmedLine.substring(2))
                            i++
                            while (i < lines.size) {
                                val nextTrimmed = lines[i].trim()
                                if (nextTrimmed.isBlank() || nextTrimmed.startsWith("- ") || nextTrimmed.startsWith("* ") || nextTrimmed.startsWith("### ") || nextTrimmed.startsWith("## ") || nextTrimmed.startsWith("# ") || nextTrimmed.startsWith("> ") || (nextTrimmed.firstOrNull()?.isDigit() == true && nextTrimmed.contains(". "))) {
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
                        
                        // Lists (Numbered)
                        trimmedLine.firstOrNull()?.isDigit() == true && trimmedLine.contains(". ") -> {
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
                        
                        trimmedLine.isBlank() -> {
                            Spacer(modifier = Modifier.height(12.dp)) // Added spacing for empty paragraphs
                            i++
                        }
                        
                        else -> {
                            val paragraphLines = mutableListOf<String>()
                            paragraphLines.add(trimmedLine)
                            i++
                            while (i < lines.size) {
                                val nextTrimmed = lines[i].trim()
                                if (nextTrimmed.isBlank() || nextTrimmed.startsWith("### ") || nextTrimmed.startsWith("## ") || nextTrimmed.startsWith("# ") || nextTrimmed.startsWith("> ") || nextTrimmed.startsWith("- ") || nextTrimmed.startsWith("* ") || (nextTrimmed.firstOrNull()?.isDigit() == true && nextTrimmed.contains(". "))) {
                                    break
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
fun StandardText(
    text: String,
    normalColor: Color,
    boldColor: Color,
    linkColor: Color,
    codeColor: Color
) {
    if (text.isBlank()) return
    Text(
        text = parseMarkdownToAnnotatedString(
            text, normalColor, boldColor, normalColor, linkColor, codeColor
        ),
        style = MaterialTheme.typography.bodyMedium.copy(
            fontSize = 16.sp, 
            lineHeight = 26.sp, 
            letterSpacing = 0.sp,
            fontWeight = FontWeight.Normal,
            color = normalColor
        ),
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

fun parseMarkdownToAnnotatedString(
    content: String,
    normalColor: Color,
    boldColor: Color,
    italicColor: Color,
    linkColor: Color,
    codeColor: Color
): AnnotatedString {
    return buildAnnotatedString {
        var text = content
        var currentIndex = 0

        data class MarkdownMatch(
            val range: IntRange,
            val displayText: String,
            val style: SpanStyle,
            val isLink: Boolean = false,
            val url: String? = null,
            val isMath: Boolean = false
        )

        val matches = mutableListOf<MarkdownMatch>()

        // LaTeX block math ($$...$$) - render as monospace with special styling
        MarkdownPatterns.blockMath.findAll(text).forEach { match ->
            matches.add(MarkdownMatch(
                range = match.range,
                displayText = match.groupValues[1].trim(),
                style = SpanStyle(
                    color = codeColor,
                    fontFamily = FontFamily.Monospace,
                    background = codeColor.copy(alpha = 0.15f),
                    fontSize = 14.sp
                ),
                isMath = true
            ))
        }

        // LaTeX inline math ($...$) - render as italic monospace
        MarkdownPatterns.inlineMath.findAll(text).forEach { match ->
            val overlaps = matches.any { it.range.first <= match.range.last && it.range.last >= match.range.first }
            if (!overlaps) {
                matches.add(MarkdownMatch(
                    range = match.range,
                    displayText = match.groupValues[1].trim(),
                    style = SpanStyle(
                        color = codeColor,
                        fontFamily = FontFamily.Monospace,
                        fontStyle = FontStyle.Italic,
                        background = codeColor.copy(alpha = 0.1f)
                    ),
                    isMath = true
                ))
            }
        }

        MarkdownPatterns.bold.findAll(text).forEach { match ->
            val overlaps = matches.any { it.range.first <= match.range.last && it.range.last >= match.range.first }
            if (!overlaps) {
                matches.add(MarkdownMatch(
                    range = match.range,
                    displayText = match.groupValues[1],
                    style = SpanStyle(color = boldColor, fontWeight = FontWeight.Bold)
                ))
            }
        }

        MarkdownPatterns.italic.findAll(text).forEach { match ->
            val overlaps = matches.any { it.range.first <= match.range.last && it.range.last >= match.range.first }
            if (!overlaps) {
                matches.add(MarkdownMatch(
                    range = match.range,
                    displayText = match.groupValues[1],
                    style = SpanStyle(color = italicColor, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                ))
            }
        }

        MarkdownPatterns.inlineCode.findAll(text).forEach { match ->
            val overlaps = matches.any { it.range.first <= match.range.last && it.range.last >= match.range.first }
            if (!overlaps) {
                matches.add(MarkdownMatch(
                    range = match.range,
                    displayText = match.groupValues[1],
                    style = SpanStyle(
                        color = codeColor,
                        fontFamily = FontFamily.Monospace,
                        background = codeColor.copy(alpha = 0.1f)
                    )
                ))
            }
        }

        MarkdownPatterns.link.findAll(text).forEach { match ->
            val overlaps = matches.any { it.range.first <= match.range.last && it.range.last >= match.range.first }
            if (!overlaps) {
                matches.add(MarkdownMatch(
                    range = match.range,
                    displayText = match.groupValues[1],
                    style = SpanStyle(
                        color = linkColor,
                        fontWeight = FontWeight.SemiBold,
                        textDecoration = TextDecoration.Underline
                    ),
                    isLink = true,
                    url = match.groupValues[2]
                ))
            }
        }

        MarkdownPatterns.underline.findAll(text).forEach { match ->
            val overlaps = matches.any { it.range.first <= match.range.last && it.range.last >= match.range.first }
            if (!overlaps) {
                matches.add(MarkdownMatch(
                    range = match.range,
                    displayText = match.groupValues[1],
                    style = SpanStyle(
                        color = boldColor,
                        fontWeight = FontWeight.Bold
                    )
                ))
            }
        }

        MarkdownPatterns.italicUnderscore.findAll(text).forEach { match ->
            val overlaps = matches.any { it.range.first <= match.range.last && it.range.last >= match.range.first }
            if (!overlaps) {
                matches.add(MarkdownMatch(
                    range = match.range,
                    displayText = match.groupValues[1],
                    style = SpanStyle(color = italicColor, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                ))
            }
        }

        val sortedMatches = matches.sortedBy { it.range.first }

        for (match in sortedMatches) {
            if (match.range.first > currentIndex) {
                withStyle(SpanStyle(color = normalColor)) {
                    append(text.substring(currentIndex, match.range.first))
                }
            }

            if (match.isMath) {
                // Render math with visual prefix for clarity
                withStyle(match.style) {
                    append(match.displayText)
                }
            } else if (match.isLink && match.url != null) {
                withLink(LinkAnnotation.Url(match.url)) {
                    withStyle(match.style) {
                        append(match.displayText)
                    }
                }
            } else {
                withStyle(match.style) {
                    append(match.displayText)
                }
            }

            currentIndex = match.range.last + 1
        }

        if (currentIndex < text.length) {
            withStyle(SpanStyle(color = normalColor)) {
                append(text.substring(currentIndex))
            }
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
    text = text.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "")
    text = text.replace(Regex("<think>.*", RegexOption.DOT_MATCHES_ALL), "")
    text = text.replace("<final>", "").replace("</final>", "")
    
    // Clean up partial tags when streaming
    text = text.replace(Regex("<fi?n?a?l?$"), "")
    text = text.replace(Regex("<th?i?n?k?$"), "")
    
    return text.trim()
}
