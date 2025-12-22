package com.example.smarty.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarty.data.model.ChatMessage
import com.example.smarty.data.model.ChatRole
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.theme.LocalShapes
import com.example.smarty.ui.theme.MonoFont
import com.example.smarty.data.model.Note
import com.example.smarty.data.model.getAttachments
import kotlinx.coroutines.delay
import com.halilibo.richtext.markdown.Markdown
import com.halilibo.richtext.ui.RichText
import com.halilibo.richtext.ui.RichTextStyle
import com.halilibo.richtext.ui.string.RichTextStringStyle
import androidx.compose.animation.core.FastOutSlowInEasing

/**
 * Premium Chat Message Bubble Component
 * - Includes Avatars for User and AI
 * - Modern, rounded aesthetics
 * - Improved typography and spacing
 */
@Composable
fun ChatMessageItem(
    message: ChatMessage,
    modifier: Modifier = Modifier,
    getNote: (String) -> Note? = { null },
    onNoteClick: (Note) -> Unit = {},
    onSuggestionClick: (String) -> Unit = {}
) {
    val isUser = message.role == ChatRole.USER
    
    // Use rememberSaveable to persist animation state across scroll recycling
    var isVisible by rememberSaveable(message.id) { mutableStateOf(false) }

    // Animate entrance only once
    LaunchedEffect(message.id) {
        if (!isVisible) {
            delay(50) // Brief delay for stagger effect
            isVisible = true
        }
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(tween(200)) + 
                slideInHorizontally(
                    initialOffsetX = { if (isUser) it / 2 else -it / 2 },
                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                ) + 
                scaleIn(
                    initialScale = 0.9f, 
                    animationSpec = tween(300)
                )
    ) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp), // Reduced padding
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Bottom // Align avatars to bottom of message
        ) {
            // AI Avatar REMOVED per user request
            // if (!isUser) { ... }

            // Message Bubble Column
            Column(
                horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
                modifier = Modifier.widthIn(max = 300.dp) // Slightly increased max width since avatars are gone
            ) {
                // Name Label REMOVED per user request
                // Text(...)

                // The Bubble
                Surface(
                    shape = RoundedCornerShape(
                        topStart = 32.dp,
                        topEnd = 32.dp,
                        bottomStart = if (isUser) 32.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 32.dp
                    ),
                    color = if (isUser) {
                        // "Private Note" Tag Aesthetic: Pale Blue tint
                        LocalAccentColor.current.copy(alpha = 0.12f)
                    } else {
                        // AI Bubble: Use 'Surface' (CardWhite) to pop against 'Background' (SoftBackground)
                        // This ensures high contrast and a premium, elevated look.
                        MaterialTheme.colorScheme.surface
                    },
                    shadowElevation = 0.dp, // Flat like the tag
                    modifier = Modifier.padding(bottom = 2.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp) // More padding for pill look
                    ) {
                        // Message content
                        if (isUser) {
                            Text(
                                text = message.content,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    lineHeight = 22.sp,
                                    letterSpacing = 0.2.sp,
                                    fontWeight = FontWeight.Bold // Bolder text like the tag
                                ),
                                // "Private Note" Tag Aesthetic: Vivid Blue Text
                                color = LocalAccentColor.current
                            )
                        } else {
                            // Assistant messages - DARK MODE FIX
                            // Check if background is dark by examining the actual bubble color
                            val bubbleColor = MaterialTheme.colorScheme.surface
                            val isDarkBackground = bubbleColor.luminance() < 0.5f

                            // EXPLICIT text color - WHITE on dark, BLACK on light
                            val textColor = if (isDarkBackground) Color.White else Color.Black

                            val accentColor = LocalAccentColor.current

                            // Check if content has markdown (contains **, `, #, -, etc.)
                            val hasMarkdown = message.content.contains("**") ||
                                    message.content.contains("```") ||
                                    message.content.contains("`") ||
                                    message.content.startsWith("#") ||
                                    message.content.contains("\n- ") ||
                                    message.content.contains("\n* ")

                            if (hasMarkdown) {
                                // Use RichText for markdown content
                                val codeBackground = if (isDarkBackground) {
                                    Color.White.copy(alpha = 0.15f)
                                } else {
                                    Color.Black.copy(alpha = 0.08f)
                                }

                                val richTextStyle = RichTextStyle(
                                    stringStyle = RichTextStringStyle(
                                        codeStyle = SpanStyle(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 13.sp,
                                            background = codeBackground,
                                            color = textColor
                                        ),
                                        boldStyle = SpanStyle(fontWeight = FontWeight.Bold, color = textColor),
                                        italicStyle = SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, color = textColor),
                                        linkStyle = SpanStyle(color = accentColor, fontWeight = FontWeight.Bold)
                                    )
                                )

                                CompositionLocalProvider(
                                    LocalContentColor provides textColor,
                                    LocalTextStyle provides MaterialTheme.typography.bodyMedium.copy(
                                        color = textColor,
                                        lineHeight = 22.sp
                                    )
                                ) {
                                    RichText(style = richTextStyle) {
                                        Markdown(content = message.content)
                                    }
                                }
                            } else {
                                // Plain text - use Text directly for guaranteed color
                                Text(
                                    text = message.content,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        lineHeight = 22.sp,
                                        letterSpacing = 0.2.sp
                                    ),
                                    color = textColor  // EXPLICIT color - guaranteed to work
                                )
                            }
                        }

                        // Attachments Count
                        if (message.attachments.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Surface(
                                color = if (isUser) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.secondaryContainer,
                                shape = CircleShape
                            ) {
                                Text(
                                    text = "+ ${message.attachments.size} Attachment${if(message.attachments.size > 1) "s" else ""}",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }

                        // Action Results (Inside bubble for cleaner look)
                        if (!isUser && message.hasActions) {
                             Spacer(modifier = Modifier.height(12.dp))
                             
                             message.executedActions.forEach { actionResult ->
                                  Column(
                                      modifier = Modifier
                                          .fillMaxWidth()
                                          .background(
                                              MaterialTheme.colorScheme.surface.copy(alpha = 0.5f), 
                                              RoundedCornerShape(12.dp)
                                          )
                                          .border(
                                              1.dp, 
                                              MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), 
                                              RoundedCornerShape(12.dp)
                                          )
                                          .padding(8.dp)
                                  ) {
                                       ActionResultChip(
                                            actionName = actionResult.action,
                                            success = actionResult.success,
                                            summary = actionResult.resultSummary
                                       )
                                       
                                       // Affected Notes
                                       if (actionResult.affectedNoteIds.isNotEmpty()) {
                                            Spacer(modifier = Modifier.height(8.dp))
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
                                  Spacer(modifier = Modifier.height(8.dp))
                             }
                        }
                    }
                }
                
                // AI Suggestions (between bubble and timestamp)
                if (!isUser && message.hasSuggestions) {
                    Row(
                        modifier = Modifier
                            .padding(top = 8.dp, start = 4.dp)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        message.suggestions.take(2).forEach { suggestion ->
                            Surface(
                                onClick = { onSuggestionClick(suggestion) },
                                shape = RoundedCornerShape(16.dp),
                                color = LocalAccentColor.current.copy(alpha = 0.1f),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    LocalAccentColor.current.copy(alpha = 0.3f)
                                )
                            ) {
                                Text(
                                    text = suggestion,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Medium
                                    ),
                                    color = LocalAccentColor.current,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                // Timestamp Row (below suggestions/bubble)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, start = 4.dp, end = 4.dp),
                    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatTimestamp(message.timestamp),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Medium
                        ),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), // Significantly darker than before
                        fontSize = 11.sp
                    )

                    // Copy button (AI Only)
                    if (!isUser) {
                        val clipboardManager = LocalClipboardManager.current
                        var showCopied by remember { mutableStateOf(false) }

                        LaunchedEffect(showCopied) {
                            if (showCopied) {
                                delay(1500)
                                showCopied = false
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Icon(
                            imageVector = if (showCopied) Icons.Default.Check else Icons.Outlined.ContentCopy,
                            contentDescription = "Copy",
                            modifier = Modifier
                                .size(12.dp)
                                .clickable {
                                    clipboardManager.setText(AnnotatedString(message.content))
                                    showCopied = true
                                },
                            tint = if (showCopied) LocalAccentColor.current else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }

                // Relevant Notes (Outside Bubble for flow)
                if (!isUser && message.referencedNoteIds.isNotEmpty()) {
                    val actionNoteIds = message.executedActions.flatMap { it.affectedNoteIds }.toSet()
                    val relevantNotes = message.referencedNoteIds
                        .filter { it !in actionNoteIds }
                        .mapNotNull { getNote(it) }
                        .take(3)

                    if (relevantNotes.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Reference Content",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                        )
                        
                        relevantNotes.forEach { note ->
                            NoteCard(
                                  note = note,
                                  onClick = { onNoteClick(note) },
                                  onDelete = {}, 
                                  onOpenTodo = {},
                                  modifier = Modifier
                                      .fillMaxWidth()
                                      .shadow(2.dp, RoundedCornerShape(12.dp)),
                                  isSelectionMode = true
                             )
                             Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }
            
            // User Avatar REMOVED per user request
            // if (isUser) { ... }
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
            modifier = Modifier.size(20.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (success) Icons.Default.Check else Icons.Default.Error,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
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
