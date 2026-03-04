package com.example.smarty.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.smarty.R
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.theme.ComponentSpacing
import com.example.smarty.ui.theme.LocalShapes

/**
 * =============================================================================
 * EMPTY STATE COMPONENTS (STATIC)
 * =============================================================================
 * 
 * Simplified empty states without complex animations as per user preference.
 */

/**
 * Calendar Empty State
 */
@Composable
fun CalendarEmptyState(modifier: Modifier = Modifier) {
    EmptyStateContainer(
        title = stringResource(R.string.calendar),
        subtitle = stringResource(R.string.nothing_planned),
        hint = stringResource(R.string.tap_plus_to_add_something_to_your_schedule),
        modifier = modifier
    ) {
        // No graphic
    }
}

/**
 * Search Empty State
 */
@Composable
fun SearchEmptyState(searchQuery: String, modifier: Modifier = Modifier) {
    EmptyStateContainer(
        title = stringResource(R.string.no_matches),
        subtitle = "\"$searchQuery\"",
        hint = stringResource(R.string.try_different_keywords_or_filters),
        modifier = modifier
    ) {
        // No graphic
    }
}

/**
 * Chat History Empty State
 */
@Composable
fun ChatHistoryEmptyState(modifier: Modifier = Modifier) {
    EmptyStateContainer(
        title = stringResource(R.string.history),
        subtitle = stringResource(R.string.no_conversations_yet),
        hint = stringResource(R.string.your_past_chats_will_appear_here_as_you_interact_with_smarty),
        modifier = modifier
    ) {
        // No graphic
    }
}

/**
 * Shared container for text content in empty states.
 */
@Composable
private fun EmptyStateContainer(
    title: String,
    subtitle: String,
    hint: String? = null,
    modifier: Modifier = Modifier,
    graphic: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // Graphic Layer (Optional)
        graphic()
        // Text Layer Removed as per user request
    }
}

/**
 * Compact version of empty state for use in sheets, cards, or smaller regions.
 */
@Composable
fun CompactEmptyState(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(ComponentSpacing.sheetPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Static dot for compact state
        Box(
            modifier = Modifier.size(60.dp),
            contentAlignment = Alignment.Center
        ) {
            val accentColor = LocalAccentColor.current
            
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    color = accentColor.copy(alpha = 0.15f),
                    radius = size.minDimension / 2
                )
                drawCircle(
                    color = accentColor.copy(alpha = 0.4f),
                    radius = (size.minDimension / 6)
                )
            }
        }
        // Text Removed
    }
}

/**
 * Calm Loading State - Unified shimmer effect for data boundaries
 * (Kept as it is a loading indicator, not a decorative background animation)
 */
@Composable
fun CalmLoadingState(
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 100.dp,
    shape: androidx.compose.ui.graphics.Shape = LocalShapes.current.card
) {
    val infiniteTransition = rememberInfiniteTransition(label = "calm_shimmer")
    val shimmerAlpha by infiniteTransition.animateFloat(
        initialValue = 0.05f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmer"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(
                color = LocalAccentColor.current.copy(alpha = shimmerAlpha),
                shape = shape
            )
    )
}

/**
 * Skeleton Loader for text blocks
 */
@Composable
fun TextSkeletonLoader(
    lines: Int = 3,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        repeat(lines) { index ->
            val widthFraction = if (index == lines - 1) 0.6f else 1f
            CalmLoadingState(
                height = 14.dp,
                shape = LocalShapes.current.skeleton,
                modifier = Modifier.fillMaxWidth(widthFraction)
            )
        }
    }
}

/**
 * Calm Linear Progress
 */
@Composable
fun CalmLinearProgress(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = LocalAccentColor.current,
    trackColor: Color = color.copy(alpha = 0.1f)
) {
    val targetProgress = progress()
    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress.coerceIn(0f, 1f),
        animationSpec = spring(
            dampingRatio = 0.85f,
            stiffness = Spring.StiffnessLow
        ),
        label = "calm_progress"
    )

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
    ) {
        val width = size.width
        val height = size.height
        val radius = height / 2

        // Track
        drawRoundRect(
            color = trackColor,
            size = Size(width, height),
            cornerRadius = CornerRadius(radius, radius)
        )

        // Progress
        if (animatedProgress > 0) {
            drawRoundRect(
                color = color,
                size = Size(width * animatedProgress, height),
                cornerRadius = CornerRadius(radius, radius)
            )
        }
    }
}

/**
 * Chat Empty State
 */
@Composable
fun ChatEmptyState(modifier: Modifier = Modifier) {
    EmptyStateContainer(
        title = stringResource(R.string.smarty),
        subtitle = stringResource(R.string.here_to_help),
        hint = stringResource(R.string.what_can_i_help_with),
        modifier = modifier
    ) {
        // No graphic
    }
}

/**
 * Notes Empty State
 */
@Composable
fun NotesEmptyState(modifier: Modifier = Modifier) {
    EmptyStateContainer(
        title = stringResource(R.string.notes),
        subtitle = stringResource(R.string.capture_your_thoughts),
        hint = stringResource(R.string.tap_plus_to_create_your_first_note),
        modifier = modifier
    ) {
        // No graphic
    }
}

/**
 * Archive Empty State
 */
@Composable
fun ArchiveEmptyState(modifier: Modifier = Modifier) {
    EmptyStateContainer(
        title = stringResource(R.string.archive),
        subtitle = stringResource(R.string.archives),
        hint = stringResource(R.string.archived_notes_will_appear_here),
        modifier = modifier
    ) {
        // No graphic
    }
}

/**
 * Stacks Empty State
 */
@Composable
fun StacksEmptyState(modifier: Modifier = Modifier) {
    EmptyStateContainer(
        title = stringResource(R.string.stacks),
        subtitle = stringResource(R.string.your_collections),
        hint = stringResource(R.string.ai_will_organize_your_notes_into_smart_stacks),
        modifier = modifier
    ) {
        // No graphic
    }
}

/**
 * Category Empty State
 */
@Composable
fun CategoryEmptyState(categoryName: String, modifier: Modifier = Modifier) {
    EmptyStateContainer(
        title = categoryName.lowercase(),
        subtitle = stringResource(R.string.focused_notes),
        hint = stringResource(R.string.add_notes_to_this_category),
        modifier = modifier
    ) {
        // No graphic
    }
}

/**
 * Files Empty State
 */
@Composable
fun FilesEmptyState(modifier: Modifier = Modifier) {
    EmptyStateContainer(
        title = stringResource(R.string.files),
        subtitle = stringResource(R.string.no_associated_files),
        hint = stringResource(R.string.images_documents_and_audio_will_appear_here),
        modifier = modifier
    ) {
        // No graphic
    }
}

/**
 * Version History Empty State
 */
@Composable
fun VersionHistoryEmptyState(modifier: Modifier = Modifier) {
    EmptyStateContainer(
        title = stringResource(R.string.history),
        subtitle = stringResource(R.string.no_versions_yet),
        hint = stringResource(R.string.edit_this_note_to_create_a_version),
        modifier = modifier
    ) {
        // No graphic
    }
}

/**
 * Backup Empty State
 */
@Composable
fun BackupEmptyState(
    isLocal: Boolean = false,
    modifier: Modifier = Modifier
) {
    EmptyStateContainer(
        title = if (isLocal) stringResource(R.string.local_archives) else stringResource(R.string.cloud_backups),
        subtitle = if (isLocal) stringResource(R.string.no_local_backups_yet) else stringResource(R.string.no_cloud_backups_found),
        hint = if (isLocal) stringResource(R.string.create_a_backup_to_save_data_on_your_device) else stringResource(R.string.sign_in_and_backup_to_protect_your_data),
        modifier = modifier
    ) {
        // No graphic
    }
}

/**
 * Intelligence Empty State
 */
@Composable
fun IntelligenceEmptyState(modifier: Modifier = Modifier) {
    EmptyStateContainer(
        title = stringResource(R.string.intelligence),
        subtitle = stringResource(R.string.no_patterns_detected_yet),
        hint = stringResource(R.string.start_interacting_with_smarty_to_build_your_profile),
        modifier = modifier
    ) {
        // No graphic
    }
}
