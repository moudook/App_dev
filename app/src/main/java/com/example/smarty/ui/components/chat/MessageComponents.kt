package com.example.smarty.ui.components.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.theme.Alpha
import kotlinx.coroutines.delay

/**
 * MessageBubble - User message bubble component.
 * 
 * Single Responsibility: Only displays user message bubble.
 * DRY: Centralized bubble styling.
 */
@Composable
fun UserMessageBubble(
    content: String,
    timestamp: String,
    modifier: Modifier = Modifier,
    onCopy: () -> Unit = {}
) {
    // Inverted colors for user bubble: opposite of theme
    val isDark = MaterialTheme.colorScheme.surface.luminance() <= 0.5f
    val accentColor = LocalAccentColor.current
    val userBubbleBackground = if (isDark) Color(0xFFF5F5F5) else accentColor.copy(alpha = 0.2f)
    val userBubbleTextColor = if (isDark) {
        Color(0xFF1A1A1A)
    } else {
        Color.Black
    }
    
    var showCopied by remember { mutableStateOf(false) }
    
    LaunchedEffect(showCopied) {
        if (showCopied) {
            delay(1500)
            showCopied = false
        }
    }
    
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End
    ) {
        Surface(
            color = userBubbleBackground,
            shape = RoundedCornerShape(16.dp)
        ) {
            Box(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 16.sp,
                        lineHeight = 22.sp
                    ),
                    color = userBubbleTextColor
                )
            }
        }
        
        Spacer(modifier = Modifier.height(6.dp))
        
        // Copy icon and timestamp
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = timestamp,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 11.sp
                ),
                color = userBubbleTextColor.copy(alpha = 0.6f)
            )
            
            Icon(
                imageVector = if (showCopied) Icons.Default.CheckCircle else Icons.Default.ContentCopy,
                contentDescription = "Copy",
                modifier = Modifier
                    .size(16.dp)
                    .clickable { 
                        onCopy()
                        showCopied = true
                    },
                tint = if (showCopied) LocalAccentColor.current else userBubbleTextColor.copy(alpha = 0.6f)
            )
        }
    }
}

/**
 * AssistantMessageContainer - Container for assistant messages.
 * 
 * Single Responsibility: Only provides message container layout.
 */
@Composable
fun AssistantMessageContainer(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 640.dp)
    ) {
        content()
    }
}

/**
 * MessageTimestamp - Reusable timestamp component.
 * 
 * Single Responsibility: Only displays formatted timestamp.
 */
@Composable
fun MessageTimestamp(
    timestamp: Long,
    modifier: Modifier = Modifier,
    isUser: Boolean = false
) {
    val textColor = if (isUser) {
        MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.6f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    
    val formattedTimestamp = remember(timestamp) {
        formatTimestamp(timestamp)
    }
    
    Text(
        text = formattedTimestamp,
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp
        ),
        color = textColor,
        modifier = modifier
    )
}

/**
 * Format timestamp to readable string.
 */
private fun formatTimestamp(timestamp: Long): String {
    val dateFormat = java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault())
    return dateFormat.format(java.util.Date(timestamp))
}

/**
 * MessageActions - Copy, delete, regenerate actions.
 * 
 * Single Responsibility: Only handles message action buttons.
 */
@Composable
fun MessageActions(
    onCopy: () -> Unit,
    onDelete: () -> Unit = {},
    onRegenerate: () -> Unit = {},
    modifier: Modifier = Modifier,
    showDelete: Boolean = true,
    showRegenerate: Boolean = true
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        IconButton(onClick = onCopy, modifier = Modifier.size(24.dp)) {
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = "Copy",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = Alpha.half),
                modifier = Modifier.size(16.dp)
            )
        }
        
        if (showDelete) {
            IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                Icon(
                    imageVector = androidx.compose.material.icons.Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = Alpha.half),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        
        if (showRegenerate) {
            IconButton(onClick = onRegenerate, modifier = Modifier.size(24.dp)) {
                Icon(
                    imageVector = androidx.compose.material.icons.Icons.Default.Refresh,
                    contentDescription = "Regenerate",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = Alpha.half),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
