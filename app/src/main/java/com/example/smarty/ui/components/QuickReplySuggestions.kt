package com.example.smarty.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarty.R
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
    visible: Boolean = true,
) {
    val haptic = LocalHapticFeedback.current

    AnimatedVisibility(
        visible = visible && suggestions.isNotEmpty(),
        enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
        modifier = modifier,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            suggestions.forEachIndexed { index, suggestion ->
                val suggestionText =
                    when {
                        suggestion.textResId != 0 -> stringResource(suggestion.textResId)
                        else -> suggestion.text
                    }
                QuickReplyChip(
                    suggestion = suggestion,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSuggestionClick(suggestionText)
                    },
                    animationDelay = index * 50,
                )
            }
        }
    }
}

/**
 * Individual quick reply chip
 */
@Composable
private fun QuickReplyChip(
    suggestion: QuickReplySuggestion,
    onClick: () -> Unit,
    animationDelay: Int = 0,
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
        label = "chipScale",
    )

    val alpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(200),
        label = "chipAlpha",
    )

    val text =
        when {
            suggestion.textResId != 0 -> stringResource(suggestion.textResId)
            else -> suggestion.text
        }

    Surface(
        onClick = onClick,
        modifier =
            Modifier
                .scale(scale)
                .alpha(alpha),
        shape = RoundedCornerShape(20.dp),
        // Match user bubble aesthetic: Subtle accent tint + border
        color = accentColor.copy(alpha = 0.08f),
        border =
            androidx.compose.foundation.BorderStroke(
                width = 1.dp,
                color = accentColor.copy(alpha = 0.2f),
            ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            suggestion.icon?.let { icon ->
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(16.dp),
                )
            }

            Text(
                text = text.lowercase(), // Consistent lowercase style
                style =
                    MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.2.sp,
                    ),
                color = accentColor, // Use accent color for text to match border
            )
        }
    }
}

/**
 * Data class for a quick reply suggestion
 */
data class QuickReplySuggestion(
    val text: String = "",
    val textResId: Int = 0,
    val icon: ImageVector? = null,
    val category: SuggestionCategory = SuggestionCategory.GENERAL,
)

enum class SuggestionCategory {
    GENERAL,
    NOTES,
    CALENDAR,
    SEARCH,
    ACTIONS,
}

/**
 * Default suggestions for empty chat state.
 */
fun getDefaultSuggestions(isPrivateMode: Boolean = false): List<QuickReplySuggestion> {
    val hour =
        java.util.Calendar
            .getInstance()
            .get(java.util.Calendar.HOUR_OF_DAY)

    // Time-of-day aware greeting/task suggestions
    val timeAwareSuggestion =
        when {
            hour in 5..11 ->
                QuickReplySuggestion(
                    textResId = com.example.smarty.R.string.whats_on_my_calendar_today,
                    icon = Icons.Default.CalendarToday,
                    category = SuggestionCategory.CALENDAR,
                )
            hour in 12..17 ->
                QuickReplySuggestion(
                    textResId = com.example.smarty.R.string.whats_left_today,
                    icon = Icons.Default.CalendarToday,
                    category = SuggestionCategory.CALENDAR,
                )
            hour in 18..21 ->
                QuickReplySuggestion(
                    textResId = com.example.smarty.R.string.plan_for_tomorrow,
                    icon = Icons.Default.DateRange,
                    category = SuggestionCategory.CALENDAR,
                )
            else ->
                QuickReplySuggestion(
                    textResId = com.example.smarty.R.string.quick_note,
                    icon = Icons.AutoMirrored.Filled.NoteAdd,
                    category = SuggestionCategory.ACTIONS,
                )
        }

    return if (isPrivateMode) {
        // In private mode, don't suggest actions that could reveal note content
        listOf(
            timeAwareSuggestion,
            QuickReplySuggestion(
                textResId = com.example.smarty.R.string.create_a_new_note,
                icon = Icons.Default.Add,
                category = SuggestionCategory.ACTIONS,
            ),
        )
    } else {
        listOf(
            timeAwareSuggestion,
            QuickReplySuggestion(
                textResId = com.example.smarty.R.string.summarize_my_recent_notes,
                icon = Icons.Default.Summarize,
                category = SuggestionCategory.NOTES,
            ),
            QuickReplySuggestion(
                textResId = com.example.smarty.R.string.create_a_new_note,
                icon = Icons.Default.Add,
                category = SuggestionCategory.ACTIONS,
            ),
            QuickReplySuggestion(
                textResId = com.example.smarty.R.string.find_notes,
                icon = Icons.Default.Search,
                category = SuggestionCategory.SEARCH,
            ),
        )
    }
}

/**
 * Get contextual suggestions based on the last AI message.
 */
fun getContextualSuggestions(
    lastMessage: String?,
    isPrivateMode: Boolean = false,
): List<QuickReplySuggestion> {
    if (lastMessage.isNullOrBlank()) return getDefaultSuggestions(isPrivateMode)

    val lowercaseMessage = lastMessage.lowercase()

    return when {
        lowercaseMessage.contains("note") || lowercaseMessage.contains("created") -> {
            if (isPrivateMode) {
                listOf(
                    QuickReplySuggestion(
                        textResId = com.example.smarty.R.string.create_another_note,
                        icon = Icons.Default.Add,
                        category = SuggestionCategory.ACTIONS,
                    ),
                    QuickReplySuggestion(textResId = com.example.smarty.R.string.thanks, category = SuggestionCategory.GENERAL),
                )
            } else {
                listOf(
                    QuickReplySuggestion(
                        textResId = com.example.smarty.R.string.show_me_that_note,
                        icon = Icons.Default.Visibility,
                        category = SuggestionCategory.NOTES,
                    ),
                    QuickReplySuggestion(
                        textResId = com.example.smarty.R.string.edit_the_note,
                        icon = Icons.Default.Edit,
                        category = SuggestionCategory.ACTIONS,
                    ),
                    QuickReplySuggestion(
                        textResId = com.example.smarty.R.string.create_another_note,
                        icon = Icons.Default.Add,
                        category = SuggestionCategory.ACTIONS,
                    ),
                )
            }
        }
        lowercaseMessage.contains("calendar") || lowercaseMessage.contains("event") ->
            listOf(
                QuickReplySuggestion(
                    textResId = com.example.smarty.R.string.show_tomorrow,
                    icon = Icons.Default.CalendarToday,
                    category = SuggestionCategory.CALENDAR,
                ),
                QuickReplySuggestion(
                    textResId = com.example.smarty.R.string.add_a_new_event,
                    icon = Icons.Default.Add,
                    category = SuggestionCategory.ACTIONS,
                ),
                QuickReplySuggestion(
                    textResId = com.example.smarty.R.string.this_weeks_schedule,
                    icon = Icons.Default.DateRange,
                    category = SuggestionCategory.CALENDAR,
                ),
            )
        lowercaseMessage.contains("search") || lowercaseMessage.contains("found") -> {
            if (isPrivateMode) {
                listOf(
                    QuickReplySuggestion(textResId = com.example.smarty.R.string.thanks, category = SuggestionCategory.GENERAL),
                    QuickReplySuggestion(
                        textResId = com.example.smarty.R.string.new_topic,
                        icon = Icons.Default.Refresh,
                        category = SuggestionCategory.GENERAL,
                    ),
                )
            } else {
                listOf(
                    QuickReplySuggestion(
                        textResId = com.example.smarty.R.string.search_for_more,
                        icon = Icons.Default.Search,
                        category = SuggestionCategory.SEARCH,
                    ),
                    QuickReplySuggestion(
                        textResId = com.example.smarty.R.string.save_as_note,
                        icon = Icons.Default.Save,
                        category = SuggestionCategory.ACTIONS,
                    ),
                )
            }
        }
        else ->
            listOf(
                QuickReplySuggestion(textResId = com.example.smarty.R.string.tell_me_more, category = SuggestionCategory.GENERAL),
                QuickReplySuggestion(textResId = com.example.smarty.R.string.thanks, category = SuggestionCategory.GENERAL),
                QuickReplySuggestion(
                    textResId = com.example.smarty.R.string.new_topic,
                    icon = Icons.Default.Refresh,
                    category = SuggestionCategory.GENERAL,
                ),
            )
    }
}
