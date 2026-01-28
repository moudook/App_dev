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
import androidx.compose.material.icons.filled.HighlightOff
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Verified
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
            imageVector = Icons.Default.GraphicEq, // Creative: Voice Wave
            contentDescription = "Record voice note",
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
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
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
                    imageVector = Icons.Default.HighlightOff, // Creative: Dismiss
                    contentDescription = "Cancel recording",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Recording indicator and waveform
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // Pulsing red dot
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(Color.Red)
                )

                Spacer(modifier = Modifier.width(8.dp))

                // Simple amplitude visualization
                AmplitudeBar(amplitude = amplitude)

                Spacer(modifier = Modifier.width(12.dp))

                // Duration
                Text(
                    text = formatDuration(durationMs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // Stop/confirm button
            IconButton(
                onClick = onStop,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            ) {
                Icon(
                    imageVector = Icons.Default.Verified, // Creative: Verified
                    contentDescription = "Stop and save recording",
                    tint = MaterialTheme.colorScheme.onPrimary
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
    val bars = 5
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(bars) { index ->
            val threshold = (index + 1) / bars.toFloat()
            val isActive = amplitude >= threshold * 0.5f
            val barHeight = 8.dp + (16.dp * ((index + 1) / bars.toFloat()))

            val color by animateColorAsState(
                targetValue = if (isActive) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                },
                animationSpec = tween(100),
                label = "barColor$index"
            )

            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(barHeight)
                    .clip(RoundedCornerShape(2.dp))
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
                    is VoiceNoteRecorder.RecordingState.Recording -> "Recording..."
                    is VoiceNoteRecorder.RecordingState.Paused -> "Paused"
                    is VoiceNoteRecorder.RecordingState.Completed -> "Recording Saved"
                    is VoiceNoteRecorder.RecordingState.Error -> "Error"
                    else -> "Voice Note"
                },
                style = MaterialTheme.typography.headlineSmall
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
                        .background(MaterialTheme.colorScheme.errorContainer)
                ) {
                    Icon(
                        imageVector = Icons.Default.HighlightOff, // Creative: Dismiss
                        contentDescription = "Cancel",
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
                            Icons.Default.StopCircle // Creative: Stop
                        } else {
                            Icons.Default.GraphicEq // Creative: Mic
                        },
                        contentDescription = if (state is VoiceNoteRecorder.RecordingState.Recording) {
                            "Stop recording"
                        } else {
                            "Start recording"
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
                            imageVector = Icons.Default.Verified, // Creative: Verified
                            contentDescription = "Save recording",
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

    // Pulsing animation based on amplitude
    val pulseScale = if (shouldAnimate) {
        val infiniteTransition = rememberInfiniteTransition(label = "amplitude")
        val animatedScale by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1f + (amplitude * 0.3f),
            animationSpec = infiniteRepeatable(
                animation = tween(150, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse"
        )
        animatedScale
    } else {
        1f + (amplitude * 0.15f) // Static mid-scale when backgrounded
    }

    Box(
        modifier = modifier
            .size(120.dp)
            .graphicsLayer {
                scaleX = if (isRecording) pulseScale else 1f
                scaleY = if (isRecording) pulseScale else 1f
            }
            .clip(CircleShape)
            .background(
                if (isRecording) {
                    MaterialTheme.colorScheme.error.copy(alpha = 0.2f + amplitude * 0.3f)
                } else {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(
                    if (isRecording) {
                        MaterialTheme.colorScheme.error.copy(alpha = 0.4f + amplitude * 0.4f)
                    } else {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.GraphicEq, // Creative: Mic
                contentDescription = null,
                tint = if (isRecording) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.size(40.dp)
            )
        }
    }
}
