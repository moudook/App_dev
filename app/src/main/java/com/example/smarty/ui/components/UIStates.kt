package com.example.smarty.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Empty state component for when there's no data to display.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: () -> Unit = {},
    iconTint: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
    iconSize: Dp = 80.dp
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(iconSize))
        Spacer(modifier = Modifier.height(24.dp))
        Text(text = title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
        if (actionText != null) {
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onAction, modifier = Modifier.padding(horizontal = 32.dp)) { Text(text = actionText) }
        }
    }
}

@Composable
fun EmptyChatState(modifier: Modifier = Modifier, onCreateNewChat: () -> Unit = {}) {
    EmptyState(icon = Icons.Default.ChatBubbleOutline, title = "No conversations yet", description = "Start a new conversation to begin chatting with Smarty", actionText = "New chat", onAction = onCreateNewChat, modifier = modifier)
}

@Composable
fun EmptyNotesState(modifier: Modifier = Modifier, onCreateNote: () -> Unit = {}) {
    EmptyState(icon = Icons.Default.NoteAdd, title = "No notes yet", description = "Create your first note to get started", actionText = "Create note", onAction = onCreateNote, modifier = modifier)
}

@Composable
fun EmptySearchState(query: String, modifier: Modifier = Modifier) {
    EmptyState(icon = Icons.Default.SearchOff, title = "No results found", description = "No results found for \"$query\". Try a different search term.", modifier = modifier)
}

@Composable
fun EmptyCalendarState(modifier: Modifier = Modifier, onAddEvent: () -> Unit = {}) {
    EmptyState(icon = Icons.Default.EventNote, title = "No events scheduled", description = "Your calendar is clear. Add an event to get started.", actionText = "Add event", onAction = onAddEvent, modifier = modifier)
}

@Composable
fun ErrorState(message: String, modifier: Modifier = Modifier, retryText: String = "Retry", onRetry: () -> Unit = {}) {
    Column(modifier = modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(imageVector = Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(64.dp))
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry) { Text(text = retryText) }
    }
}

@Composable
fun ShimmerLoading(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(5) { SkeletonBox(height = 60.dp, fillMaxWidth = 1f - (it * 0.1f), shape = RoundedCornerShape(12.dp)) }
        }
    }
}
