package com.example.smarty.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.ui.Alignment
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.animation.ShimmerDirection
import com.example.smarty.ui.animation.directionalShimmer
import com.example.smarty.ui.theme.LocalShapes

/**
 * Skeleton loader for category cards with halftone shimmer effect.
 * Premium loading animation following the app's design language.
 */
@Composable
fun CategoryCardSkeleton(
    modifier: Modifier = Modifier
) {
    val shapes = LocalShapes.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(100.dp)
            .clip(shapes.cardMedium)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))
            .directionalShimmer(
                isVisible = true,
                color = MaterialTheme.colorScheme.onSurface,
                direction = ShimmerDirection.LEFT_TO_RIGHT
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Title placeholder
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .height(20.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            )
            
            // Subtitle placeholder
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.4f)
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
            )
        }
    }
}

/**
 * Skeleton loader for note cards with shimmer effect.
 */
@Composable
fun NoteCardSkeleton(
    modifier: Modifier = Modifier
) {
    val shapes = LocalShapes.current
    val accentColor = LocalAccentColor.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(80.dp)
            .clip(shapes.cardMedium)
            .background(MaterialTheme.colorScheme.surface)
            .directionalShimmer(
                isVisible = true,
                color = accentColor,
                direction = ShimmerDirection.LEFT_TO_RIGHT
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Icon placeholder
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceEvenly
            ) {
                // Title placeholder
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .height(16.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                )

                // Description placeholder
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .height(12.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))
                )
            }
        }
    }
}

/**
 * Loading state with multiple note card skeletons.
 */
@Composable
fun NotesLoadingState(
    count: Int = 5,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        repeat(count) { index ->
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(
                    animationSpec = tween(
                        durationMillis = 300,
                        delayMillis = index * 50
                    )
                )
            ) {
                NoteCardSkeleton()
            }
        }
    }
}

/**
 * Loading state with category card skeletons for StacksScreen.
 */
@Composable
fun CategoriesLoadingState(
    count: Int = 4,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        repeat(count) { index ->
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(
                    animationSpec = tween(
                        durationMillis = 300,
                        delayMillis = index * 80
                    )
                ) + slideInVertically(
                    animationSpec = tween(
                        durationMillis = 300,
                        delayMillis = index * 80
                    ),
                    initialOffsetY = { it / 4 }
                )
            ) {
                CategoryCardSkeleton()
            }
        }
    }
}

/**
 * Skeleton loader for backup list items.
 */
@Composable
fun BackupCardSkeleton(
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .directionalShimmer(
                isVisible = true,
                color = MaterialTheme.colorScheme.onSurface,
                direction = ShimmerDirection.LEFT_TO_RIGHT
            ),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Icon placeholder
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    // Date placeholder
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.4f)
                            .height(16.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    // Stats placeholder
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .height(12.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                    )
                }
            }
        }
    }
}

@Composable
fun BackupsLoadingState(
    count: Int = 2,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        repeat(count) {
            BackupCardSkeleton()
        }
    }
}

/**
 * Skeleton loader for chat sessions.
 */
@Composable
fun ChatSessionSkeleton(
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .directionalShimmer(
                isVisible = true,
                color = MaterialTheme.colorScheme.onSurface,
                direction = ShimmerDirection.LEFT_TO_RIGHT
            ),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        // Icon Circle
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
        )

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            // Title placeholder
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.5f)
                    .height(16.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            )
            Spacer(modifier = Modifier.height(6.dp))
            // Message preview placeholder
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .height(12.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
            )
        }
    }
}

/**
 * Loading state for chat history list.
 */
@Composable
fun ChatHistoryLoadingState(
    count: Int = 6,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        repeat(count) {
            ChatSessionSkeleton()
        }
    }
}

/**
 * Skeleton loader for the main settings screen view.
 * Shows placeholders for profile header and settings sections.
 */
@Composable
fun SettingsMainSkeleton(
    modifier: Modifier = Modifier
) {
    val shapes = LocalShapes.current
    val accentColor = LocalAccentColor.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .directionalShimmer(
                isVisible = true,
                color = MaterialTheme.colorScheme.onSurface,
                direction = ShimmerDirection.LEFT_TO_RIGHT
            ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // Profile Header Skeleton
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            )
            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .width(100.dp)
                    .height(24.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
            )
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .width(180.dp)
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
            )
            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .width(80.dp)
                    .height(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            )
        }

        // Settings Section Skeletons
        repeat(3) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Section Title placeholder
                Box(
                    modifier = Modifier
                        .width(80.dp)
                        .height(16.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                )
                
                // Rows
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(shapes.cardMedium)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f))
                        .padding(bottom = 1.dp)
                ) {
                    repeat(2) { rowIdx ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Box(
                                    modifier = Modifier
                                        .width(120.dp)
                                        .height(16.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Box(
                                    modifier = Modifier
                                        .width(160.dp)
                                        .height(12.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f))
                                )
                            }
                        }
                        if (rowIdx == 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 52.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                            )
                        }
                    }
                }
            }
        }
    }
}
