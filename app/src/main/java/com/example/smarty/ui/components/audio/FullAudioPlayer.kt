package com.example.smarty.ui.components.audio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarty.R
import com.example.smarty.core.domain.model.AudioPlayerUiState
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.theme.Alpha
import com.example.smarty.ui.theme.ComponentSpacing
import com.example.smarty.ui.theme.MonoFont
import com.example.smarty.ui.theme.softCardShadow

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
    modifier: Modifier = Modifier,
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
                shape = RoundedCornerShape(2.dp),
            ) {
                Box(
                    modifier =
                        Modifier.size(
                            width = ComponentSpacing.sheetDragHandleWidth,
                            height = ComponentSpacing.sheetDragHandleHeight,
                        ),
                )
            }
        },
        modifier = modifier,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
                    .padding(bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(240.dp)
                        .softCardShadow(shape = CircleShape)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = Alpha.emphasis),
                            shape = CircleShape,
                        ),
                contentAlignment = Alignment.Center,
            ) {
                LivingOrbVisualizer(
                    isPlaying = state.isPlaying,
                    progress = state.progress,
                    amplitude = state.currentAmplitude,
                    bass = state.bassAmplitude,
                    mid = state.midAmplitude,
                    treble = state.trebleAmplitude,
                    size = 180.dp,
                    primaryColor = accentColor,
                    secondaryColor = accentColor.copy(alpha = Alpha.half),
                    backgroundColor = accentColor.copy(alpha = Alpha.soft),
                )
            }

            Spacer(modifier = Modifier.height(40.dp))

            // 2. Track Info
            Text(
                text = (state.currentTrack?.title ?: stringResource(R.string.type_audio)).uppercase(),
                style =
                    MaterialTheme.typography.headlineMedium.copy(
                        fontFamily = MonoFont,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                    ),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = (state.currentTrack?.fileName ?: stringResource(R.string.unknown)).lowercase(),
                style =
                    MaterialTheme.typography.bodySmall.copy(
                        fontFamily = MonoFont,
                    ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(48.dp))

            // 3. Progress Slider
            Slider(
                value = state.progress,
                onValueChange = onSeek,
                colors =
                    SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = Alpha.soft),
                    ),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = state.currentPositionFormatted,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFont),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = state.durationFormatted,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFont),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(48.dp))

            // 4. Controls
            Surface(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onPlayPauseClick()
                },
                modifier =
                    Modifier
                        .size(72.dp)
                        .softCardShadow(shape = CircleShape),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.onSurface,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(if (state.isPlaying) R.string.pause else R.string.play),
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.surface,
                    )
                }
            }
        }
    }
}
