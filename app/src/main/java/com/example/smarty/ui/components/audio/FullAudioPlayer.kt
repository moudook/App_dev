package com.example.smarty.ui.components.audio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.smarty.data.model.AudioPlayerUiState
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.theme.ComponentSpacing
import com.example.smarty.ui.theme.IconSize
import com.example.smarty.ui.theme.Alpha

/**
 * Full-screen audio player modal with waveform visualization
 * Shows track info, waveform, time stamps, and playback controls
 * Uses app accent color (blue) for unified theming
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullAudioPlayer(
    state: AudioPlayerUiState,
    sheetState: SheetState,
    onPlayPauseClick: () -> Unit,
    onSeek: (Float) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val accentColor = LocalAccentColor.current // Use app accent color instead of pink

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = ComponentSpacing.sheetCornerRadius, topEnd = ComponentSpacing.sheetCornerRadius),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = {
            Surface(
                modifier = Modifier.padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = Alpha.moderate), // Subtle handle
                shape = RoundedCornerShape(2.dp)
            ) {
                Box(modifier = Modifier.size(width = ComponentSpacing.sheetDragHandleWidth, height = ComponentSpacing.sheetDragHandleHeight))
            }
        },
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ComponentSpacing.sheetPadding)
                .padding(bottom = 32.dp) // Moderate bottom padding
        ) {
            // Header: Icon + Info + Play Button (All in one row)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 1. Album Art (Compact Squircle)
                Surface(
                    modifier = Modifier.size(ComponentSpacing.albumArtSize),
                    shape = RoundedCornerShape(14.dp),
                    color = accentColor.copy(alpha = Alpha.moderate),
                    contentColor = accentColor
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            modifier = Modifier.size(IconSize.xxl)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                // 2. Title & Artist
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.currentTrack?.title ?: "Audio",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = state.currentTrack?.fileName ?: "Unknown",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // 3. Play/Pause Button (Inline)
                // Replaces the giant button with a standard functional icon button
                FilledIconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onPlayPauseClick()
                    },
                    modifier = Modifier.size(IconSize.container),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = accentColor,
                        contentColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                         modifier = Modifier.size(IconSize.xl)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 4. Waveform (Compact)
            AudioWaveform(
                waveformData = state.waveformData,
                progress = state.progress,
                onSeek = onSeek,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ComponentSpacing.waveformHeight), // Minimal height
                activeColor = accentColor,
                inactiveColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = Alpha.emphasis)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 5. Timestamps
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = state.currentPositionFormatted,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = Alpha.half)
                )
                Text(
                    text = state.durationFormatted,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = Alpha.half)
                )
            }
        }
    }
}
