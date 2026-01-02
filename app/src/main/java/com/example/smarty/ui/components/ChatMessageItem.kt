package com.example.smarty.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarty.data.model.ChatMessage
import com.example.smarty.data.model.ChatRole
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.theme.LocalShapes
import com.example.smarty.ui.theme.MonoFont
import com.example.smarty.ui.theme.ComponentSpacing
import com.example.smarty.ui.theme.IconSize
import com.example.smarty.ui.theme.AnimationDuration
import com.example.smarty.ui.theme.Alpha
import com.example.smarty.data.model.Note
import com.example.smarty.data.model.getAttachments
import com.example.smarty.ui.components.viewers.FullScreenImageViewer
import com.example.smarty.ui.components.InlineNotePreview
import com.halilibo.richtext.markdown.Markdown
import com.halilibo.richtext.ui.RichText
import com.halilibo.richtext.ui.RichTextStyle
import com.halilibo.richtext.ui.string.RichTextStringStyle
import androidx.compose.animation.core.FastOutSlowInEasing
import com.example.smarty.data.model.ClarificationRequest

/**
 * Pre-compiled regex patterns for markdown parsing.
 * Moved to file level to avoid recreation on every recomposition.
 * This is a critical performance optimization - regex compilation is O(n) expensive.
 */
private object MarkdownPatterns {
    val bold = Regex("\\*\\*(.+?)\\*\\*")
    val italic = Regex("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)")
    val inlineCode = Regex("`([^`]+)`")
    val link = Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)")
    val underline = Regex("__(.+?)__")
}

/**
 * Unified Chat Message Bubble Component
 *
 * Design Philosophy:
 * - Centralized, cohesive look matching the rest of the app
 * - User messages: Accent-tinted pill with clean typography
 * - AI messages: Elevated card with subtle border, professional appearance
 * - Consistent spacing, padding, and visual hierarchy
 */
@Composable
fun ChatMessageItem(
    message: ChatMessage,
    modifier: Modifier = Modifier,
    getNote: (String) -> Note? = { null },
    onNoteClick: (Note) -> Unit = {},
    onSuggestionClick: (String) -> Unit = {},
    onClarificationSubmit: (String) -> Unit = {}
) {
    val isUser = message.role == ChatRole.USER
    val accentColor = LocalAccentColor.current

    // No animations - direct rendering for performance
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        // Message Bubble
        Surface(
            shape = RoundedCornerShape(
                topStart = ComponentSpacing.bubbleCornerLarge,
                topEnd = ComponentSpacing.bubbleCornerLarge,
                bottomStart = if (isUser) ComponentSpacing.bubbleCornerLarge else ComponentSpacing.bubbleCornerSmall,
                bottomEnd = if (isUser) ComponentSpacing.bubbleCornerSmall else ComponentSpacing.bubbleCornerLarge
            ),
            color = if (isUser) {
                // User bubble: Accent tint with subtle presence
                accentColor.copy(alpha = Alpha.soft)
            } else {
                // AI bubble: Clean surface with elevation
                MaterialTheme.colorScheme.surface
            },
            border = if (!isUser) {
                BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = Alpha.hint))
            } else null,
            shadowElevation = if (!isUser) 2.dp else 0.dp,
            modifier = Modifier
                .widthIn(max = ComponentSpacing.bubbleMaxWidth)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = ComponentSpacing.bubblePadding, vertical = ComponentSpacing.bubblePaddingVertical)
            ) {
                // Message content
                if (isUser) {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 16.sp,
                            lineHeight = 24.sp,
                            letterSpacing = 0.15.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = accentColor
                    )
                } else {
                    // Assistant messages - ALL text is BLUE in both light and dark mode
                    // Custom markdown parser - bypasses buggy RichText library

                    val boldColor = accentColor // Bright blue for bold text
                    // Slightly darker shade for regular text/numbers
                    val normalColor = Color(
                        red = (accentColor.red * 0.75f).coerceIn(0f, 1f),
                        green = (accentColor.green * 0.75f).coerceIn(0f, 1f),
                        blue = (accentColor.blue * 0.85f).coerceIn(0f, 1f),
                        alpha = 1f
                    )
                    // Links use purple/violet - distinct from blue
                    val linkColor = Color(0xFF9C27B0) // Material Purple 500
                    val codeColor = normalColor // Code uses darker blue

                    // Custom markdown parser with full control over colors
                    val annotatedText = parseMarkdownToAnnotatedString(
                        content = message.content,
                        normalColor = normalColor,
                        boldColor = boldColor,
                        italicColor = normalColor,
                        linkColor = linkColor,
                        codeColor = codeColor
                    )

                    // Use ClickableText to make links work
                    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                    @Suppress("DEPRECATION")
                    androidx.compose.foundation.text.ClickableText(
                        text = annotatedText,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 16.sp,
                            lineHeight = 24.sp,
                            letterSpacing = 0.15.sp
                        ),
                        onClick = { offset ->
                        annotatedText.getStringAnnotations(tag = "URL", start = offset, end = offset)
                            .firstOrNull()?.let { annotation ->
                                try {
                                    uriHandler.openUri(annotation.item)
                                } catch (e: Exception) {
                                    // Handle invalid URLs gracefully
                                }
                            }
                        }
                    )
                }

                // Inline Image Preview (for AI messages with images from ViewImageTool)
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

                    // Full-screen viewer dialog
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

                // Referenced Notes (Inline)
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

                // Attachments Count
                if (message.attachments.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        color = if (isUser) accentColor.copy(alpha = Alpha.medium) else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "+ ${message.attachments.size} Attachment${if(message.attachments.size > 1) "s" else ""}",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            color = if (isUser) accentColor else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Action Results (Inside bubble for cleaner look)
                if (!isUser && message.hasActions) {
                    Spacer(modifier = Modifier.height(12.dp))

                    message.executedActions.forEach { actionResult ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = Alpha.half),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = Alpha.soft))
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                ActionResultChip(
                                    actionName = actionResult.action,
                                    success = actionResult.success,
                                    summary = actionResult.resultSummary
                                )

                                // Affected Notes
                                if (actionResult.affectedNoteIds.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(10.dp))
                                    actionResult.affectedNoteIds.forEach { noteId ->
                                        val note = getNote(noteId)
                                        if (note != null) {
                                            NoteCard(
                                                note = note,
                                                onClick = { onNoteClick(note) },
                                                onDelete = {},
                                                onOpenTodo = {},
                                                modifier = Modifier.fillMaxWidth(),
                                                isSelectionMode = true
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                // Clarification request (Inside bubble)
                message.clarificationRequest?.let { request ->
                    Spacer(modifier = Modifier.height(12.dp))
                    ClarificationBubble(
                        request = request,
                        onSubmit = onClarificationSubmit,
                        accentColor = accentColor
                    )
                }
            }
        }

        // AI Suggestions (below bubble)
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

        // Timestamp Row with Citations inline (below suggestions/bubble)
        // AI: [Timestamp] [Citations] ............. [Copy]
        // User: ........................ [Timestamp]
        if (isUser) {
            // User message: just timestamp on the right
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = Alpha.prominent),
                    fontSize = 11.sp
                )
            }
        } else {
            // AI message: [Timestamp + Citations] on left, [Copy] on right
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
                    .padding(top = 6.dp)
                    .widthIn(max = ComponentSpacing.bubbleMaxWidth)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left side: Timestamp + Citations
                Row(
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatTimestamp(message.timestamp),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Medium
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = Alpha.prominent),
                        fontSize = 11.sp
                    )

                    // Citations inline - next to timestamp
                    if (message.hasCitations) {
                        Spacer(modifier = Modifier.width(10.dp))
                        CitationsInline(
                            citations = message.citations,
                            accentColor = accentColor
                        )
                    }
                }

                // Right side: Copy button (always on right edge)
                Icon(
                    imageVector = if (showCopied) Icons.Default.Check else Icons.Outlined.ContentCopy,
                    contentDescription = "Copy",
                    modifier = Modifier
                        .size(IconSize.small)
                        .clickable {
                            clipboardManager.setText(AnnotatedString(message.content))
                            showCopied = true
                            scope.launch {
                                delay(30000)
                                clipboardManager.setText(AnnotatedString(""))
                            }
                        },
                    tint = if (showCopied) accentColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = Alpha.half)
                )
            }
        }

        
    }
}

/**
 * Inline citations with overlapping circles that open a selection popup
 * Shows max 3 circles in overlapping style, last shows "3+" if more citations
 */
@Composable
private fun CitationsInline(
    citations: List<com.example.smarty.data.model.Citation>,
    accentColor: Color
) {
    var showSelectionPopup by remember { mutableStateOf(false) }
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current

    // Always show max 3 circles
    val circleCount = minOf(citations.size, 3)
    val hasMore = citations.size > 3
    val circleSize = 18.dp
    val overlap = 5.dp // How much circles overlap

    Box(
        modifier = Modifier
            .clickable { showSelectionPopup = true }
            .height(circleSize)
            .width(circleSize * circleCount - overlap * (circleCount - 1).coerceAtLeast(0))
    ) {
        // Show up to 3 overlapping circles
        repeat(circleCount) { index ->
            val isLastCircle = index == circleCount - 1
            val circleText = when {
                // Last circle shows "3+" if more than 3 citations
                isLastCircle && hasMore -> "3+"
                else -> ""
            }

            Surface(
                shape = CircleShape,
                color = accentColor,
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.surface),
                modifier = Modifier
                    .size(circleSize)
                    .offset(x = (circleSize - overlap) * index)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (circleText.isNotEmpty()) {
                        Text(
                            text = circleText,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 8.sp
                            ),
                            color = Color.White
                        )
                    }
                }
            }
        }
    }

    // Pinterest-inspired citation viewer - centered, clean, systematic
    if (showSelectionPopup) {
        androidx.compose.ui.window.Popup(
            alignment = Alignment.Center,
            onDismissRequest = { showSelectionPopup = false },
            properties = androidx.compose.ui.window.PopupProperties(
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
                    .heightIn(max = 480.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Header with centered title
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Icon badge
                        Surface(
                            shape = CircleShape,
                            color = accentColor.copy(alpha = Alpha.soft),
                            modifier = Modifier.size(48.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    tint = accentColor,
                                    modifier = Modifier.size(IconSize.xl)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "Sources",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Text(
                            text = "${citations.size} reference${if (citations.size > 1) "s" else ""} found",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = Alpha.heavy)
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Subtle divider
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.3f)
                            .height(2.dp)
                            .background(
                                accentColor.copy(alpha = Alpha.moderate),
                                RoundedCornerShape(1.dp)
                            )
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // Scrollable citation list
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        citations.forEachIndexed { index, citation ->
                            // Pinterest-style card
                            Surface(
                                onClick = {
                                    try {
                                        uriHandler.openUri(citation.url)
                                        showSelectionPopup = false
                                    } catch (e: Exception) {
                                        // Handle invalid URL
                                    }
                                },
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                border = BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.outline.copy(alpha = Alpha.hint)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Number badge - prominent
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = accentColor,
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = "${index + 1}",
                                                style = MaterialTheme.typography.titleSmall.copy(
                                                    fontWeight = FontWeight.Bold
                                                ),
                                                color = Color.White
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(14.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        // Title
                                        Text(
                                            text = citation.title.ifBlank { "Untitled Source" },
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontWeight = FontWeight.SemiBold,
                                                lineHeight = 20.sp
                                            ),
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 2,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                        )

                                        Spacer(modifier = Modifier.height(4.dp))

                                        // Domain with link indicator
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(4.dp)
                                                    .background(accentColor, CircleShape)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = try {
                                                    java.net.URI(citation.url).host?.removePrefix("www.") ?: citation.url
                                                } catch (e: Exception) {
                                                    citation.url
                                                },
                                                style = MaterialTheme.typography.labelMedium,
                                                color = accentColor.copy(alpha = Alpha.mostlyOpaque),
                                                maxLines = 1
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(8.dp))

                                    // Arrow indicator
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowUp,
                                        contentDescription = "Open",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                        modifier = Modifier
                                            .size(IconSize.standard)
                                            .rotate(90f)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Close hint
                    Text(
                        text = "Tap outside to close",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = Alpha.half)
                    )
                }
            }
        }
    }
}

/**
 * Modern chip for action execution results
 */
@Composable
private fun ActionResultChip(
    actionName: String,
    success: Boolean,
    summary: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = CircleShape,
            color = if (success) Color(0xFFE8F5E9) else Color(0xFFFFEBEE), // Light Green / Light Red
            modifier = Modifier.size(IconSize.standard)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (success) Icons.Default.Check else Icons.Default.Error,
                    contentDescription = null,
                    modifier = Modifier.size(IconSize.micro),
                    tint = if (success) Color(0xFF2E7D32) else Color(0xFFC62828)
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = formatActionName(actionName),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            if (summary.isNotBlank()) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
        }
    }
}

/**
 * Format action name for display
 */
private fun formatActionName(actionName: String): String {
    return actionName
        .replace("Action", "")
        .replace(Regex("([A-Z])"), " $1")
        .trim()
}

/**
 * Format timestamp for display
 */
private fun formatTimestamp(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60_000 -> "Just now"
        diff < 3600_000 -> "${diff / 60_000}m ago"
        diff < 86400_000 -> "${diff / 3600_000}h ago"
        else -> {
            val date = java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault())
            date.format(java.util.Date(timestamp))
        }
    }
}

/**
 * Custom markdown parser that gives full control over text colors.
 * Supports: **bold**, *italic*, `code`, [links](url), __underline__
 * This bypasses the buggy compose-richtext library.
 */
private fun parseMarkdownToAnnotatedString(
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

        // Define markdown patterns with their styles
        // Order matters: check longer patterns first (** before *)
        data class MarkdownMatch(
            val range: IntRange,
            val displayText: String,
            val style: SpanStyle,
            val isLink: Boolean = false,
            val url: String? = null
        )

        // Find all markdown elements
        val matches = mutableListOf<MarkdownMatch>()

        // Bold: **text** (using pre-compiled pattern)
        MarkdownPatterns.bold.findAll(text).forEach { match ->
            matches.add(MarkdownMatch(
                range = match.range,
                displayText = match.groupValues[1],
                style = SpanStyle(color = boldColor, fontWeight = FontWeight.Bold)
            ))
        }

        // Italic: *text* (but not **) - using pre-compiled pattern
        MarkdownPatterns.italic.findAll(text).forEach { match ->
            // Check if this overlaps with any bold match
            val overlaps = matches.any { it.range.first <= match.range.last && it.range.last >= match.range.first }
            if (!overlaps) {
                matches.add(MarkdownMatch(
                    range = match.range,
                    displayText = match.groupValues[1],
                    style = SpanStyle(color = italicColor, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                ))
            }
        }

        // Inline code: `code` - using pre-compiled pattern
        MarkdownPatterns.inlineCode.findAll(text).forEach { match ->
            // Check if this overlaps with any bold or italic match
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

        // Links: [text](url) - using pre-compiled pattern
        MarkdownPatterns.link.findAll(text).forEach { match ->
            // Check overlaps
            val overlaps = matches.any { it.range.first <= match.range.last && it.range.last >= match.range.first }
            if (!overlaps) {
                matches.add(MarkdownMatch(
                    range = match.range,
                    displayText = match.groupValues[1],
                    style = SpanStyle(
                        color = linkColor,
                        fontWeight = FontWeight.SemiBold,
                        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                    ),
                    isLink = true,
                    url = match.groupValues[2]
                ))
            }
        }

        // Underline: __text__ - using pre-compiled pattern
        MarkdownPatterns.underline.findAll(text).forEach { match ->
            val overlaps = matches.any { it.range.first <= match.range.last && it.range.last >= match.range.first }
            if (!overlaps) {
                matches.add(MarkdownMatch(
                    range = match.range,
                    displayText = match.groupValues[1],
                    style = SpanStyle(
                        color = normalColor,
                        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                    )
                ))
            }
        }

        // Sort matches by start index to process sequentially
        val sortedMatches = matches.sortedBy { it.range.first }

        // Build the annotated string
        for (match in sortedMatches) {
            // Add normal text before this match
            if (match.range.first > currentIndex) {
                withStyle(SpanStyle(color = normalColor)) {
                    append(text.substring(currentIndex, match.range.first))
                }
            }

            // Add the styled text
            if (match.isLink && match.url != null) {
                pushStringAnnotation(tag = "URL", annotation = match.url)
                withStyle(match.style) {
                    append(match.displayText)
                }
                pop()
            } else {
                withStyle(match.style) {
                    append(match.displayText)
                }
            }

            currentIndex = match.range.last + 1
        }

        // Add remaining text
        if (currentIndex < text.length) {
            withStyle(SpanStyle(color = normalColor)) {
                append(text.substring(currentIndex))
            }
        }
    }
}

/**
 * Bubble for interactive clarification requests.
 * Contains: Question, Option Chips, and Optional Text Input.
 */
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
            // Question Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Clarification Needed",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = accentColor
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = request.question,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Options list
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
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isSubmitted) MaterialTheme.colorScheme.onSurfaceVariant else accentColor,
                            modifier = Modifier.padding(12.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }

            // Custom Input Field
            if (request.allowCustomInput) {
                Spacer(modifier = Modifier.height(12.dp))
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.OutlinedTextField(
                        value = customInput,
                        onValueChange = { customInput = it },
                        placeholder = { Text("Other...", fontSize = 14.sp) },
                        modifier = Modifier.weight(1f),
                        textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        enabled = !isSubmitted,
                        trailingIcon = {
                            if (customInput.isNotBlank() && !isSubmitted) {
                                androidx.compose.material3.IconButton(
                                    onClick = {
                                        isSubmitted = true
                                        onSubmit(customInput)
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Submit",
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
