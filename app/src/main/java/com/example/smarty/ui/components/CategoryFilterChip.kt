package com.example.smarty.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.smarty.ui.LocalAccentColor

/**
 * Filter chip that shows the active category filter.
 * Tap to clear the filter and show all notes.
 */
@Composable
fun CategoryFilterChip(
    categoryName: String,
    noteCount: Int = 0,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accentColor = LocalAccentColor.current

    Surface(
        onClick = onClear,
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = accentColor.copy(alpha = 0.12f),
        contentColor = accentColor
    ) {
        Row(
            modifier = Modifier.padding(
                start = 16.dp,
                end = 8.dp,
                top = 8.dp,
                bottom = 8.dp
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Category name
            Text(
                text = categoryName,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.SemiBold
                ),
                color = accentColor
            )

            // Note count badge
            if (noteCount > 0) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = accentColor.copy(alpha = 0.2f)
                ) {
                    Text(
                        text = noteCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = accentColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            // Close button
            Surface(
                shape = androidx.compose.foundation.shape.CircleShape,
                color = accentColor.copy(alpha = 0.15f),
                modifier = Modifier.size(24.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Clear filter",
                        tint = accentColor,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
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
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = categoryName != null,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
        modifier = modifier
    ) {
        categoryName?.let {
            CategoryFilterChip(
                categoryName = it,
                noteCount = noteCount,
                onClear = onClear
            )
        }
    }
}
