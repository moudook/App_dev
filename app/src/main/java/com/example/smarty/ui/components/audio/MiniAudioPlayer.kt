package com.example.smarty.ui.components.audio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.smarty.data.model.AudioPlayerUiState
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.theme.AudioPink

/**
 * Compact mini audio player bar
 * Height reduced to 52dp, positioned above input field
 */
/**
 * Compact mini audio player bar
 * Redesigned to match the "ChatSessionItem" aesthetic (Minimal, Squircle, Apple-like).
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
    val accentColor = AudioPink
    
    // UI Constants for "Music Capsule" aesthetic
    val containerShape = RoundedCornerShape(32.dp) // Fully rounded pill/capsule

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp) // Slightly taller to accommodate pill shape comfortable
            .clip(containerShape)
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onExpandClick()
            },
        shape = containerShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest, // Darker, rich container
        tonalElevation = 2.dp,
        shadowElevation = 4.dp
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
                    size = 48.dp,
                    primaryColor = accentColor,
                    secondaryColor = accentColor.copy(alpha = 0.5f),
                    backgroundColor = accentColor.copy(alpha = 0.1f)
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
                    text = state.currentTrack?.title ?: "Not Playing",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        fontSize = androidx.compose.ui.unit.TextUnit(15f, androidx.compose.ui.unit.TextUnitType.Sp)
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
                         LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier
                                .weight(1f)
                                .height(3.dp) // Slightly thicker for visibility
                                .clip(RoundedCornerShape(1.5.dp)),
                            color = accentColor,
                            trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                        )
                        
                        Spacer(modifier = Modifier.width(8.dp))
                        
                        // Time Text
                        Text(
                            text = state.durationFormatted, // Showing total duration like reference "2:49"
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp)
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                } else {
                     Text(
                        text = "Select a track...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // 3. Controls (Right)
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Play/Pause (Prominent Circle)
                FilledIconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onPlayPauseClick()
                    },
                    modifier = Modifier.size(44.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f), // Darker circle on dark background
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(22.dp)
                    )
                }
                
                Spacer(modifier = Modifier.width(4.dp))

                // Close (Subtle X)
                IconButton(
                    onClick = onCloseClick,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(18.dp)
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
