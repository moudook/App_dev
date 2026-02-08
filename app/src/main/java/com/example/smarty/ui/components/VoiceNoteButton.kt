package com.example.smarty.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import com.example.smarty.ui.utils.AnimationLifecycleState
import com.example.smarty.ui.utils.rememberAnimationLifecycleState
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.theme.MonoFont
import com.example.smarty.R
import com.example.smarty.voice.VoiceNoteRecorder

/**
 * Compact voice note recording button.
 * Shows a microphone icon that expands to a recording interface when pressed.
 */
@Composable
fun VoiceNoteButton(
    recorder: VoiceNoteRecorder,
    onRecordingComplete: (filePath: String, durationMs: Long) -> Unit,
    onPermissionRequired: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by recorder.state.collectAsState()
    val amplitude by recorder.amplitude.collectAsState()
    val durationMs by recorder.durationMs.collectAsState()

    val isRecording = state is VoiceNoteRecorder.RecordingState.Recording

    when (state) {
        is VoiceNoteRecorder.RecordingState.Idle,
        is VoiceNoteRecorder.RecordingState.Error -> {
            // Show mic button
            MicButton(
                onClick = {
                    if (recorder.hasRecordingPermission()) {
                        recorder.startRecording()
                    } else {
                        onPermissionRequired()
                    }
                },
                modifier = modifier
            )
        }

        is VoiceNoteRecorder.RecordingState.Recording,
        is VoiceNoteRecorder.RecordingState.Paused -> {
            // Show recording UI
            RecordingInterface(
                durationMs = durationMs,
                amplitude = amplitude,
                onStop = {
                    val filePath = recorder.stopRecording()
                    if (filePath != null) {
                        onRecordingComplete(filePath, durationMs)
                    }
                    recorder.reset()
                },
                onCancel = {
                    recorder.cancelRecording()
                },
                formatDuration = { recorder.formatDuration(it) },
                modifier = modifier
            )
        }

        is VoiceNoteRecorder.RecordingState.Completed -> {
            // Auto-handled by onRecordingComplete callback
            MicButton(
                onClick = {
                    recorder.reset()
                    if (recorder.hasRecordingPermission()) {
                        recorder.startRecording()
                    } else {
                        onPermissionRequired()
                    }
                },
                modifier = modifier
            )
        }
    }
}

@Composable
private fun MicButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Mic,
            contentDescription = stringResource(R.string.record_voice_note),
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(24.dp)
        )
    }
}

/**
 * OPTIMIZED: Recording interface with lifecycle-aware animation
 */
@Composable
private fun RecordingInterface(
    durationMs: Long,
    amplitude: Float,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    formatDuration: (Long) -> String,
    modifier: Modifier = Modifier
) {
    // LIFECYCLE-AWARE: Only animate when app is in foreground
    val lifecycleState by rememberAnimationLifecycleState()
    val shouldAnimate = lifecycleState == AnimationLifecycleState.RUNNING

    // Pulsing animation for recording indicator
    val pulseScale = if (shouldAnimate) {
        val infiniteTransition = rememberInfiniteTransition(label = "recording")
        val animatedScale by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.2f,
            animationSpec = infiniteRepeatable(
                animation = tween(500, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse"
        )
        animatedScale
    } else {
        1.1f // Mid-scale when backgrounded
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f), // Calmer background
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Cancel button
            IconButton(
                onClick = onCancel,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.cancel_recording),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }

            // Recording indicator and waveform
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // Pulsing dot - Using Accent color instead of bright Red
                val accentColor = LocalAccentColor.current
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = 0.8f))
                )

                Spacer(modifier = Modifier.width(12.dp))

                // Simple amplitude visualization
                AmplitudeBar(amplitude = amplitude)

                Spacer(modifier = Modifier.width(16.dp))

                // Duration
                Text(
                    text = formatDuration(durationMs),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = MonoFont,
                        letterSpacing = 0.5.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // Stop/confirm button
            IconButton(
                onClick = onStop,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(LocalAccentColor.current)
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = stringResource(R.string.stop_and_save_recording),
                    tint = Color.White
                )
            }
        }
    }
}

@Composable
private fun AmplitudeBar(
    amplitude: Float,
    modifier: Modifier = Modifier
) {
    // Simple bar visualization that grows with amplitude
    val bars = 8 // More bars for smoother look
    val accentColor = LocalAccentColor.current
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(bars) { index ->
            val threshold = (index + 1) / bars.toFloat()
            val isActive = amplitude >= threshold * 0.4f
            val barHeight = 6.dp + (14.dp * ((index + 1) / bars.toFloat()))

            val color by animateColorAsState(
                targetValue = if (isActive) {
                    accentColor.copy(alpha = 0.6f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
                },
                animationSpec = tween(150),
                label = "barColor$index"
            )

            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(barHeight)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(color)
            )
        }
    }
}

/**
 * Full-screen voice recording overlay.
 * Use this for more prominent recording experience.
 */
@Composable
fun VoiceRecordingOverlay(
    recorder: VoiceNoteRecorder,
    onDismiss: () -> Unit,
    onRecordingComplete: (filePath: String, durationMs: Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by recorder.state.collectAsState()
    val amplitude by recorder.amplitude.collectAsState()
    val durationMs by recorder.durationMs.collectAsState()

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Title
            Text(
                text = when (state) {
                    is VoiceNoteRecorder.RecordingState.Recording -> stringResource(R.string.thinking).replace("…", "")
                    is VoiceNoteRecorder.RecordingState.Paused -> "paused"
                    is VoiceNoteRecorder.RecordingState.Completed -> stringResource(R.string.recording_saved)
                    is VoiceNoteRecorder.RecordingState.Error -> "error"
                    else -> stringResource(R.string.type_audio)
                }.lowercase(),
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.5.sp
                )
            )

            // Duration display
            Text(
                text = recorder.formatDuration(durationMs),
                style = MaterialTheme.typography.displayMedium,
                color = if (state is VoiceNoteRecorder.RecordingState.Recording) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )

            // Large amplitude visualization
            LargeAmplitudeVisualizer(
                amplitude = amplitude,
                isRecording = state is VoiceNoteRecorder.RecordingState.Recording
            )

            // Controls
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Cancel
                IconButton(
                    onClick = {
                        recorder.cancelRecording()
                        onDismiss()
                    },
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.cancel),
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(28.dp)
                    )
                }

                // Record/Stop button
                IconButton(
                    onClick = {
                        when (state) {
                            is VoiceNoteRecorder.RecordingState.Idle -> {
                                recorder.startRecording()
                            }
                            is VoiceNoteRecorder.RecordingState.Recording -> {
                                val filePath = recorder.stopRecording()
                                if (filePath != null) {
                                    onRecordingComplete(filePath, durationMs)
                                }
                                recorder.reset()
                                onDismiss()
                            }
                            else -> {}
                        }
                    },
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(
                            if (state is VoiceNoteRecorder.RecordingState.Recording) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        )
                ) {
                    Icon(
                        imageVector = if (state is VoiceNoteRecorder.RecordingState.Recording) {
                            Icons.Default.StopCircle
                        } else {
                            Icons.Default.Mic
                        },
                        contentDescription = if (state is VoiceNoteRecorder.RecordingState.Recording) {
                            "stop_recording"
                        } else {
                            "start_recording"
                        },
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }

                // Confirm (only when recording)
                if (state is VoiceNoteRecorder.RecordingState.Recording) {
                    IconButton(
                        onClick = {
                            val filePath = recorder.stopRecording()
                            if (filePath != null) {
                                onRecordingComplete(filePath, durationMs)
                            }
                            recorder.reset()
                            onDismiss()
                        },
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = stringResource(R.string.save_recording),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.size(56.dp))
                }
            }
        }
    }
}

/**
 * OPTIMIZED: Large amplitude visualizer with lifecycle-aware animation
 */
@Composable
private fun LargeAmplitudeVisualizer(
    amplitude: Float,
    isRecording: Boolean,
    modifier: Modifier = Modifier
) {
    // LIFECYCLE-AWARE: Only animate when app is in foreground
    val lifecycleState by rememberAnimationLifecycleState()
    val shouldAnimate = lifecycleState == AnimationLifecycleState.RUNNING

    val accentColor = LocalAccentColor.current
    val pulseScale = if (shouldAnimate) {
        val infiniteTransition = rememberInfiniteTransition(label = "amplitude")
        val animatedScale by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1f + (amplitude * 0.25f), // Slightly reduced for calm
            animationSpec = infiniteRepeatable(
                animation = tween(150, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse"
        )
        animatedScale
    } else {
        1f + (amplitude * 0.12f)
    }

    Box(
        modifier = modifier
            .size(110.dp)
            .graphicsLayer {
                scaleX = if (isRecording) pulseScale else 1f
                scaleY = if (isRecording) pulseScale else 1f
            }
            .clip(CircleShape)
            .background(
                if (isRecording) {
                    accentColor.copy(alpha = 0.15f + amplitude * 0.2f)
                } else {
                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(
                    if (isRecording) {
                        accentColor.copy(alpha = 0.3f + amplitude * 0.3f)
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = null,
                tint = if (isRecording) {
                    accentColor
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                },
                modifier = Modifier.size(32.dp)
            )
        }
    }
}
