package com.example.smarty.ui.components.common

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Loading/Empty/Data State Component.
 * 
 * Single Responsibility: Only handles state-based content display.
 * DRY: Replaces repeated loading/empty patterns in 8+ screens.
 * 
 * Usage:
 * ```
 * LoadingEmptyContent(
 *     isLoading = isLoading,
 *     isEmpty = items.isEmpty(),
 *     emptyTitle = "No items",
 *     emptySubtitle = "Add your first item",
 *     emptyIcon = Icons.Default.Add
 * ) {
 *     LazyColumn {
 *         items(items) { item -> ... }
 *     }
 * }
 * ```
 */
@Composable
fun <T> LoadingEmptyContent(
    isLoading: Boolean,
    isEmpty: Boolean,
    emptyTitle: String,
    emptySubtitle: String,
    emptyIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    loadingCount: Int = 5,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    when {
        isLoading -> {
            LoadingState(count = loadingCount, modifier = modifier)
        }
        isEmpty -> {
            EmptyState(
                title = emptyTitle,
                subtitle = emptySubtitle,
                icon = emptyIcon,
                modifier = modifier
            )
        }
        else -> {
            content()
        }
    }
}

/**
 * Loading state with shimmer effect.
 */
@Composable
fun LoadingState(
    count: Int = 5,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        repeat(count) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .shimmerEffect()
                )
            }
        }
    }
}

/**
 * Empty state with icon and text.
 */
@Composable
fun EmptyState(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    modifier: Modifier = Modifier,
    action: @Composable () -> Unit = {}
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(80.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(40.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
        
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        if (action != {}) {
            Spacer(modifier = Modifier.height(24.dp))
            action()
        }
    }
}

/**
 * Shimmer effect for loading states.
 */
@Composable
private fun Modifier.shimmerEffect(): Modifier {
    return this
        .drawWithContent {
            drawContent()
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = 0.1f),
                        Color.Transparent
                    )
                ),
                alpha = 0.3f
            )
        }
}
