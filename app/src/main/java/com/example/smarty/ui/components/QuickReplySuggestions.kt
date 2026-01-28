package com.example.smarty.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.DesignServices
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.smarty.ui.LocalAccentColor

/**
 * Quick reply suggestion chips that appear above the chat input.
 * Shows contextual suggestions based on conversation state.
 */
@Composable
fun QuickReplySuggestions(
    suggestions: List<QuickReplySuggestion>,
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    visible: Boolean = true
) {
    val haptic = LocalHapticFeedback.current
    
    AnimatedVisibility(
        visible = visible && suggestions.isNotEmpty(),
        enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            suggestions.forEachIndexed { index, suggestion ->
                QuickReplyChip(
                    suggestion = suggestion,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSuggestionClick(suggestion.text)
                    },
                    animationDelay = index * 50
                )
            }
        }
    }
}

@Composable
private fun QuickReplyChip(
    suggestion: QuickReplySuggestion,
    onClick: () -> Unit,
    animationDelay: Int = 0
) {
    val accentColor = LocalAccentColor.current
    var appeared by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(animationDelay.toLong())
        appeared = true
    }
    
    val scale by animateFloatAsState(
        targetValue = if (appeared) 1f else 0.8f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
        label = "chipScale"
    )
    
    val alpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(200),
        label = "chipAlpha"
    )
    
    Surface(
        onClick = onClick,
        modifier = Modifier
            .scale(scale)
            .alpha(alpha),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = accentColor.copy(alpha = 0.2f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            suggestion.icon?.let { icon ->
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(16.dp)
                )
            }
            
            Text(
                text = suggestion.text,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Medium
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Data class for a quick reply suggestion
 */
data class QuickReplySuggestion(
    val text: String,
    val icon: ImageVector? = null,
    val category: SuggestionCategory = SuggestionCategory.GENERAL
)

enum class SuggestionCategory {
    GENERAL,
    NOTES,
    CALENDAR,
    SEARCH,
    ACTIONS
}

/**
 * Default suggestions for empty chat state.
 *
 * BUG FIX (Issue #20): Added time-of-day awareness for more contextual suggestions.
 * BUG FIX (Issue #21): Added isPrivateMode parameter to suppress note-revealing suggestions.
 *
 * @param isPrivateMode When true, suggestions that could reveal note content are suppressed
 */
fun getDefaultSuggestions(isPrivateMode: Boolean = false): List<QuickReplySuggestion> {
    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)

    // Time-of-day aware greeting/task suggestions
    val timeAwareSuggestion = when {
        hour in 5..11 -> QuickReplySuggestion("What's on my calendar today?", Icons.Default.CalendarToday, SuggestionCategory.CALENDAR)
        hour in 12..17 -> QuickReplySuggestion("What's left today?", Icons.Default.CalendarToday, SuggestionCategory.CALENDAR)
        hour in 18..21 -> QuickReplySuggestion("Plan for tomorrow", Icons.Default.DateRange, SuggestionCategory.CALENDAR)
        else -> QuickReplySuggestion("Quick note", Icons.Default.AutoAwesome, SuggestionCategory.ACTIONS)
    }

    return if (isPrivateMode) {
        // In private mode, don't suggest actions that could reveal note content
        listOf(
            timeAwareSuggestion,
            QuickReplySuggestion("Create a new note", Icons.Default.AutoAwesome, SuggestionCategory.ACTIONS)
            // Intentionally omit "Summarize my notes" and "Find notes about..." in private mode
        )
    } else {
        listOf(
            timeAwareSuggestion,
            QuickReplySuggestion("Summarize my recent notes", Icons.Default.AutoGraph, SuggestionCategory.NOTES), // Creative: Graph/Insights
            QuickReplySuggestion("Create a new note", Icons.Default.AutoAwesome, SuggestionCategory.ACTIONS),
            QuickReplySuggestion("Find notes about...", Icons.Default.Explore, SuggestionCategory.SEARCH) // Creative: Explore
        )
    }
}

/**
 * Get contextual suggestions based on the last AI message.
 *
 * BUG FIX (Issue #21): Added isPrivateMode parameter to suppress note-revealing suggestions.
 *
 * @param lastMessage The last AI response to generate contextual suggestions from
 * @param isPrivateMode When true, suggestions that could reveal note content are suppressed
 */
fun getContextualSuggestions(lastMessage: String?, isPrivateMode: Boolean = false): List<QuickReplySuggestion> {
    if (lastMessage.isNullOrBlank()) return getDefaultSuggestions(isPrivateMode)

    val lowercaseMessage = lastMessage.lowercase()

    return when {
        lowercaseMessage.contains("note") || lowercaseMessage.contains("created") -> {
            if (isPrivateMode) {
                // In private mode, don't suggest viewing/editing potentially private notes
                listOf(
                    QuickReplySuggestion("Create another note", Icons.Default.AutoAwesome, SuggestionCategory.ACTIONS),
                    QuickReplySuggestion("Thanks!", category = SuggestionCategory.GENERAL)
                )
            } else {
                listOf(
                    QuickReplySuggestion("Show me that note", Icons.Default.Visibility, SuggestionCategory.NOTES),
                    QuickReplySuggestion("Edit the note", Icons.Default.DesignServices, SuggestionCategory.ACTIONS), // Creative: Design
                    QuickReplySuggestion("Create another note", Icons.Default.AutoAwesome, SuggestionCategory.ACTIONS)
                )
            }
        }
        lowercaseMessage.contains("calendar") || lowercaseMessage.contains("event") -> listOf(
            QuickReplySuggestion("Show tomorrow", Icons.Default.CalendarToday, SuggestionCategory.CALENDAR),
            QuickReplySuggestion("Add a new event", Icons.Default.AutoAwesome, SuggestionCategory.ACTIONS),
            QuickReplySuggestion("This week's schedule", Icons.Default.DateRange, SuggestionCategory.CALENDAR)
        )
        lowercaseMessage.contains("search") || lowercaseMessage.contains("found") -> {
            if (isPrivateMode) {
                listOf(
                    QuickReplySuggestion("Thanks!", category = SuggestionCategory.GENERAL),
                    QuickReplySuggestion("New topic", Icons.Default.Refresh, SuggestionCategory.GENERAL)
                )
            } else {
                listOf(
                    QuickReplySuggestion("Search for more", Icons.Default.Explore, SuggestionCategory.SEARCH), // Creative: Explore
                    QuickReplySuggestion("Save as note", Icons.Default.BookmarkBorder, SuggestionCategory.ACTIONS) // Creative: Bookmark
                )
            }
        }
        else -> listOf(
            QuickReplySuggestion("Tell me more", category = SuggestionCategory.GENERAL),
            QuickReplySuggestion("Thanks!", category = SuggestionCategory.GENERAL),
            QuickReplySuggestion("New topic", Icons.Default.Refresh, SuggestionCategory.GENERAL)
        )
    }
}
