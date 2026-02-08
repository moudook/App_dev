package com.example.smarty.ui.components.audio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.example.smarty.R
import com.example.smarty.data.model.AudioPlayerUiState
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.components.CalmLinearProgress
import com.example.smarty.ui.theme.ComponentSpacing
import com.example.smarty.ui.theme.IconSize
import com.example.smarty.ui.theme.Alpha
import com.example.smarty.ui.theme.MonoFont
import com.example.smarty.ui.theme.softCardShadow

/**
 * Compact mini audio player bar
 * Redesigned to match the app's unified theme (blue accent).
 */
@Composable
fun MiniAudioPlayer(
    state: AudioPlayerUiState,
    onPlayPauseClick: () -> Unit,
    onExpandClick: () -> Unit,
    onCloseClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val accentColor = LocalAccentColor.current // Use app accent color
    
    // UI Constants for "Music Capsule" aesthetic
    val containerShape = RoundedCornerShape(26.dp)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(ComponentSpacing.miniPlayerHeight)
            .softCardShadow(
                shape = containerShape,
                elevation = 8.dp
            )
            .clip(containerShape)
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onExpandClick()
            },
        shape = containerShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
        border = BorderStroke(
            width = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
                // 1. Visual Anchor: Living Orb Visualizer (reacts to music amplitude)
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                LivingOrbVisualizer(
                    isPlaying = state.isPlaying,
                    progress = state.progress,
                    amplitude = state.currentAmplitude,
                    bass = state.bassAmplitude,
                    mid = state.midAmplitude,
                    treble = state.trebleAmplitude,
                    size = IconSize.container,
                    primaryColor = accentColor,
                    secondaryColor = accentColor.copy(alpha = Alpha.half),
                    backgroundColor = accentColor.copy(alpha = Alpha.soft)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // 2. Track Info & Progress (Middle)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.Center
            ) {
                // Title
                Text(
                    text = (state.currentTrack?.title ?: stringResource(R.string.not_playing)).lowercase(),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Progress Row: Bar + Time
                if (state.hasTrack) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                         // Slim Progress Bar
                         CalmLinearProgress(
                            progress = { state.progress },
                            modifier = Modifier
                                .weight(1f)
                                .height(3.dp), // Slightly thicker for visibility
                            color = accentColor,
                            trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = Alpha.moderate),
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        // Time Text
                        Text(
                            text = state.durationFormatted,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = MonoFont,
                                fontWeight = FontWeight.Medium,
                                fontSize = 11.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = Alpha.heavy)
                        )
                    }
                } else {
                     Text(
                        text = stringResource(R.string.select_a_track),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = MonoFont,
                            fontWeight = FontWeight.Medium
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // 3. Controls (Right)
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Play/Pause (Minimalist design)
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onPlayPauseClick()
                    },
                    modifier = Modifier.size(IconSize.massive)
                ) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(if (state.isPlaying) R.string.pause else R.string.play),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(IconSize.large)
                    )
                }

                Spacer(modifier = Modifier.width(2.dp))

                // Close (Subtle X)
                IconButton(
                    onClick = onCloseClick,
                    modifier = Modifier.size(IconSize.huge)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.close),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.size(IconSize.medium)
                    )
                }
            }
        }
    }
}

/**
 * Animated container for mini player with slide animation.
 * IMPORTANT: This container assumes it's placed in a Box/Coordinator.
 * To prevent overlap with input fields, the CONSUMER (MainActivity/Screen) must
 * adjust the input field's padding based on `visible` state.
 */
@Composable
fun AnimatedMiniPlayer(
    visible: Boolean,
    state: AudioPlayerUiState,
    onPlayPauseClick: () -> Unit,
    onExpandClick: () -> Unit,
    onCloseClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible && state.hasTrack,
        enter = slideInVertically(
            initialOffsetY = { it }, // Slide up from bottom
            animationSpec = spring(dampingRatio = 0.75f, stiffness = 300f)
        ) + fadeIn(),
        exit = slideOutVertically(
            targetOffsetY = { it }, // Slide down
            animationSpec = spring(dampingRatio = 0.8f, stiffness = 300f)
        ) + fadeOut(),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp) // Bottom margin floating above nav bar
        ) {
            MiniAudioPlayer(
                state = state,
                onPlayPauseClick = onPlayPauseClick,
                onExpandClick = onExpandClick,
                onCloseClick = onCloseClick
            )
        }
    }
}
