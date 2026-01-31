package com.example.smarty.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Assistant
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Note
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.Web
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.example.smarty.R
import com.example.smarty.data.model.MentionState
import com.example.smarty.data.model.MentionSuggestion
import com.example.smarty.data.model.NoteType
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.theme.LocalShapes
import com.example.smarty.ui.theme.MonoFont
import com.example.smarty.ui.theme.softCardShadow

// Type filter icon colors - Softened for Calm Aesthetic
private val AudioColor = Color(0xFFB39DDB)      // Soft Purple
private val DocumentColor = Color(0xFF90CAF9)   // Soft Blue
private val ImageColor = Color(0xFFA5D6A7)      // Soft Green
private val VideoColor = Color(0xFFF48FB1)      // Soft Pink
private val CodeColor = Color(0xFFFFCC80)       // Soft Orange
private val WebColor = Color(0xFF80DEEA)        // Soft Cyan
private val NoteColor = Color(0xFFB0BEC5)       // Soft Blue Gray

/**
 * Autocomplete dropdown for @mention suggestions.
 * Shows above the input field with glassmorphism styling.
 * Displays max 4 suggestions.
 */
@Composable
fun MentionDropdown(
    mentionState: MentionState,
    onSuggestionSelected: (MentionSuggestion) -> Unit,
    modifier: Modifier = Modifier,
    isDarkTheme: Boolean = false
) {
    AnimatedVisibility(
        visible = mentionState.isActive && mentionState.suggestions.isNotEmpty(),
        enter = fadeIn(spring(stiffness = Spring.StiffnessMedium)) +
                expandVertically(
                    animationSpec = spring(stiffness = Spring.StiffnessMedium),
                    expandFrom = Alignment.Bottom
                ),
        exit = fadeOut(spring(stiffness = Spring.StiffnessHigh)) +
                shrinkVertically(
                    animationSpec = spring(stiffness = Spring.StiffnessHigh),
                    shrinkTowards = Alignment.Bottom
                ),
        modifier = modifier
    ) {
        val cardShape = LocalShapes.current.cardMedium

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp)
                .softCardShadow(shape = cardShape),
            shape = cardShape,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 0.dp,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            )
        ) {
            Column(
                modifier = Modifier.padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Header
                Text(
                    text = if (mentionState.query.isBlank()) stringResource(R.string.quick_access) else stringResource(R.string.suggestions),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )

                // Suggestions (max 4)
                mentionState.suggestions.take(4).forEachIndexed { index, suggestion ->
                    MentionSuggestionItem(
                        suggestion = suggestion,
                        isHighlighted = index == mentionState.highlightedIndex,
                        onClick = { onSuggestionSelected(suggestion) },
                        isDarkTheme = isDarkTheme
                    )
                }
            }
        }
    }
}

/**
 * Individual suggestion item in the dropdown.
 * Styled to match NoteCard aesthetics.
 */
@Composable
private fun MentionSuggestionItem(
    suggestion: MentionSuggestion,
    isHighlighted: Boolean,
    onClick: () -> Unit,
    isDarkTheme: Boolean
) {
    val accentColor = LocalAccentColor.current
    val highlightColor = if (isHighlighted) {
        accentColor.copy(alpha = 0.08f) // Refined Soft Tech highlight opacity
    } else {
        Color.Transparent
    }
    // Use explicit shape for consistency with other inputs/bubbles
    val itemShape = RoundedCornerShape(16.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(itemShape)
            .background(highlightColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        when (suggestion) {
            is MentionSuggestion.NoteSuggestion -> {
                NoteSuggestionContent(suggestion, isDarkTheme)
            }
            is MentionSuggestion.CategorySuggestion -> {
                CategorySuggestionContent(suggestion, isDarkTheme)
            }
            is MentionSuggestion.TypeFilter -> {
                TypeFilterContent(suggestion, isDarkTheme)
            }
            is MentionSuggestion.SpecialFilter -> {
                SpecialFilterContent(suggestion, isDarkTheme)
            }
            is MentionSuggestion.CommandSuggestion -> {
                CommandSuggestionContent(suggestion, isDarkTheme)
            }
        }
    }
}

/**
 * Content for note suggestion item.
 */
@Composable
private fun NoteSuggestionContent(
    suggestion: MentionSuggestion.NoteSuggestion,
    isDarkTheme: Boolean
) {
    val accentColor = LocalAccentColor.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        // Note type icon
        Icon(
            imageVector = getNoteTypeIcon(suggestion.note.type),
            contentDescription = null,
            tint = getNoteTypeColor(suggestion.note.type),
            modifier = Modifier.size(20.dp)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            // Note title
            Text(
                text = suggestion.note.title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = MonoFont,
                    fontWeight = FontWeight.Medium
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )

            // Note type subtitle
            Text(
                text = suggestion.note.type.name.replace("_", " ").lowercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                maxLines = 1
            )
        }

        // match score indicator (subtle)
        if (suggestion.score >= 0.8) {
            Icon(
                imageVector = Icons.Default.Assistant,
                contentDescription = null,
                tint = accentColor.copy(alpha = 0.7f),
                modifier = Modifier.size(14.dp)
            )
        }
    }
}


/**
 * Content for category suggestion item.
 */
@Composable
private fun CategorySuggestionContent(
    suggestion: MentionSuggestion.CategorySuggestion,
    isDarkTheme: Boolean
) {
    val accentColor = LocalAccentColor.current
    
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        // Category icon (folder with accent color)
        Icon(
            imageVector = Icons.Default.Folder,
            contentDescription = null,
            tint = accentColor,
            modifier = Modifier.size(20.dp)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            // Category name
            Text(
                text = "@${suggestion.category.name}",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = MonoFont,
                    fontWeight = FontWeight.Medium
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )

            // Category subtitle
            Text(
                text = stringResource(R.string.category),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                maxLines = 1
            )
        }

        // match score indicator (subtle)
        if (suggestion.score >= 0.8) {
            Icon(
                imageVector = Icons.Default.Assistant,
                contentDescription = null,
                tint = accentColor.copy(alpha = 0.7f),
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

/**
 * Content for type filter suggestion item.
 */
@Composable
private fun TypeFilterContent(
    suggestion: MentionSuggestion.TypeFilter,
    isDarkTheme: Boolean
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        // Filter icon
        Icon(
            imageVector = suggestion.type?.let { getNoteTypeIcon(it) } ?: Icons.Default.Folder,
            contentDescription = null,
            tint = suggestion.type?.let { getNoteTypeColor(it) } ?: NoteColor,
            modifier = Modifier.size(20.dp)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            // Filter name
            Text(
                text = "@${suggestion.keyword}",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = MonoFont,
                    fontWeight = FontWeight.Medium
                ),
                color = MaterialTheme.colorScheme.onSurface
            )

            // Description
            Text(
                text = stringResource(R.string.filter_prefix, suggestion.displayName.lowercase()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }

        // Count badge
        CountBadge(count = suggestion.count, isDarkTheme = isDarkTheme)
    }
}

/**
 * Content for special filter suggestion item.
 */
@Composable
private fun SpecialFilterContent(
    suggestion: MentionSuggestion.SpecialFilter,
    isDarkTheme: Boolean
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        // Special filter icon
        Icon(
            imageVector = getSpecialFilterIcon(suggestion.filterName),
            contentDescription = null,
            tint = LocalAccentColor.current,
            modifier = Modifier.size(20.dp)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            // Filter name
            Text(
                text = "@${suggestion.filterName}",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = MonoFont,
                    fontWeight = FontWeight.Medium
                ),
                color = MaterialTheme.colorScheme.onSurface
            )

            // Description
            Text(
                text = suggestion.description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Count badge
        CountBadge(count = suggestion.count, isDarkTheme = isDarkTheme)
    }
}

/**
 * Content for command suggestion item (like @thinking).
 */
@Composable
private fun CommandSuggestionContent(
    suggestion: MentionSuggestion.CommandSuggestion,
    isDarkTheme: Boolean
) {
    val accentColor = LocalAccentColor.current

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        // Command icon
        Icon(
            imageVector = getCommandIcon(suggestion.icon),
            contentDescription = null,
            tint = accentColor,
            modifier = Modifier.size(20.dp)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            // Command name
            Text(
                text = "@${suggestion.commandName}",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = MonoFont,
                    fontWeight = FontWeight.SemiBold
                ),
                color = accentColor
            )

            // Description
            Text(
                text = suggestion.description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // ai badge
        Surface(
            shape = CircleShape,
            color = accentColor.copy(alpha = 0.15f),
            contentColor = accentColor
        ) {
            Text(
                text = stringResource(R.string.ai_badge),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                ),
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

/**
 * Get icon for command.
 */
private fun getCommandIcon(iconName: String): ImageVector {
    return when (iconName.lowercase()) {
        "thinking", "think" -> Icons.Default.Assistant
        "analytics", "analyze" -> Icons.Default.Analytics
        else -> Icons.Default.Assistant
    }
}

/**
 * Small count badge showing number of items.
 * Styled as pill to match NoteCard category chips.
 */
@Composable
private fun CountBadge(
    count: Int,
    isDarkTheme: Boolean
) {
    if (count > 0) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ) {
            Text(
                text = if (count > 99) "99+" else count.toString(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                ),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    }
}

/**
 * Get icon for special filter.
 */
private fun getSpecialFilterIcon(filterName: String): ImageVector {
    return when (filterName.lowercase()) {
        "recent" -> Icons.Default.History
        "pinned", "starred", "favorites" -> Icons.Default.Bookmark
        "all" -> Icons.Default.FilterList
        else -> Icons.Default.FilterList
    }
}
