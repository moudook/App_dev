package com.example.smarty.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.smarty.R
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.theme.appleShapes
import com.example.smarty.ui.theme.softCardShadow

/**
 * Filter chip that shows the active category filter.
 * Tap to clear the filter and show all notes.
 * Refined for Soft Minimalist aesthetic.
 */
@Composable
fun CategoryFilterChip(
    categoryName: String,
    noteCount: Int = 0,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accentColor = LocalAccentColor.current
    val isDark =
        !MaterialTheme.colorScheme.surface
            .luminance()
            .let { it > 0.5f }
    val shapes = MaterialTheme.appleShapes

    // Soft minimalist colors
    // Use a very subtle surface color, not just transparency
    val chipBackground =
        if (isDark) {
            accentColor.copy(alpha = 0.15f) // Dark mode needs a bit more opacity
        } else {
            MaterialTheme.colorScheme.surface // Light mode uses clean surface
        }

    val borderColor =
        if (isDark) {
            accentColor.copy(alpha = 0.3f)
        } else {
            accentColor.copy(alpha = 0.2f)
        }

    Surface(
        onClick = onClear,
        modifier =
            modifier.softCardShadow(
                elevation = 2.dp,
                shape = shapes.large,
                spotColor = accentColor.copy(alpha = 0.1f),
            ),
        shape = shapes.large,
        color = chipBackground,
        contentColor = accentColor,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Row(
            modifier =
                Modifier.padding(
                    start = 16.dp,
                    end = 8.dp,
                    top = 8.dp,
                    bottom = 8.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Category name
            Text(
                text = categoryName,
                style =
                    MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                color = accentColor,
            )

            // Note count badge
            if (noteCount > 0) {
                Surface(
                    shape = shapes.small,
                    color = accentColor.copy(alpha = 0.1f),
                ) {
                    Text(
                        text = noteCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = accentColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }

            // Close button - cleaner integration
            // Just the icon, no nested surface circle to reduce visual noise
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.clear_filter),
                tint = accentColor.copy(alpha = 0.7f),
                modifier =
                    Modifier
                        .size(20.dp)
                        .padding(2.dp), // Touch target padding
            )
        }
    }
}

/**
 * Animated container for the filter chip.
 * Shows/hides with animation.
 */
@Composable
fun AnimatedCategoryFilterChip(
    categoryName: String?,
    noteCount: Int = 0,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = categoryName != null,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
        modifier = modifier,
    ) {
        categoryName?.let {
            CategoryFilterChip(
                categoryName = it,
                noteCount = noteCount,
                onClear = onClear,
            )
        }
    }
}


