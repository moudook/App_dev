package com.example.smarty.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarty.ui.components.GeometricGradientBackground
import com.example.smarty.voice.speaker.VoiceEnrollmentManager
import kotlinx.coroutines.delay
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import kotlin.math.sin

/**
 * Voice Enrollment Screen - Geometric Glass Design
 * 
 * Futuristic "AI Soul" biometric setup.
 */
@Composable
fun VoiceEnrollmentScreen(
    enrollmentManager: VoiceEnrollmentManager,
    onEnrollmentComplete: () -> Unit,
    onSkip: () -> Unit
) {
    val enrollmentState by enrollmentManager.enrollmentState.collectAsState()
    val currentPhraseIndex by enrollmentManager.currentPhraseIndex.collectAsState()
    val recordingAmplitude by enrollmentManager.recordingAmplitude.collectAsState()
    
    val phrases = enrollmentManager.phrases
    val currentPhrase = enrollmentManager.getCurrentPhrase()

    var isHindi by remember { mutableStateOf(true) }

    // Handle completion
    LaunchedEffect(enrollmentState) {
        if (enrollmentState is VoiceEnrollmentManager.EnrollmentState.Complete) {
            delay(2000)
            onEnrollmentComplete()
        }
    }

    BackHandler {
        enrollmentManager.resetEnrollment()
        onSkip()
    }

    val accentColor = Color(0xFF4FACFE) // Electric Blue
    
    // 50-50 THEME MIX
    // Background: Atmospheric (from Image)
    // Components: Standard App Theme (Cogni Design System)
    
    GeometricGradientBackground(
        modifier = Modifier.fillMaxSize()
    ) {
        // TOP CONTROLS - Standard Material App Bar style
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Cancel Button - Standard Material 3
            FilledIconButton(
                onClick = { 
                    enrollmentManager.resetEnrollment()
                    onSkip() 
                },
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.2f),
                    contentColor = Color.White
                )
            ) {
                Icon(Icons.Default.Close, contentDescription = "Cancel")
            }

            // Language Toggle - Standard Choice Chip style
            Surface(
                onClick = { isHindi = !isHindi },
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.2f),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.3f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isHindi) "EN" else "HI",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isHindi) "हिं" else "EN",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.weight(1f))
            
            // ═══════════════════════════════════════════════════════════════════
            // CENTRAL VISUALIZATION
            // ═══════════════════════════════════════════════════════════════════
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(300.dp)
                    .weight(2f)
            ) {
                when (val state = enrollmentState) {
                    is VoiceEnrollmentManager.EnrollmentState.NotStarted -> IntroOrb(accentColor)
                    is VoiceEnrollmentManager.EnrollmentState.WaitingToRecord -> WaitingOrb(accentColor)
                    is VoiceEnrollmentManager.EnrollmentState.Recording -> RecordingOrb(recordingAmplitude, accentColor)
                    is VoiceEnrollmentManager.EnrollmentState.Processing,
                    is VoiceEnrollmentManager.EnrollmentState.Finalizing -> ProcessingOrb(accentColor)
                    is VoiceEnrollmentManager.EnrollmentState.Complete -> SuccessOrb(state.quality)
                    is VoiceEnrollmentManager.EnrollmentState.Error -> ErrorOrb()
                }
            }
            
            Spacer(modifier = Modifier.weight(0.5f))

            // ═══════════════════════════════════════════════════════════════════
            // CONTROLS & TEXT - COGNI DESIGN SYSTEM
            // ═══════════════════════════════════════════════════════════════════
            // Using standard Column but with specific text styles from Theme
            
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
                modifier = Modifier.fillMaxWidth()
            ) {
                when (val state = enrollmentState) {
                    is VoiceEnrollmentManager.EnrollmentState.NotStarted -> {
                         Text(
                            text = "Voice Match",
                            style = MaterialTheme.typography.headlineMedium,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Teach the AI to recognize your unique voice for secure hands-free access.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White.copy(alpha = 0.8f),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(32.dp))
                        
                        // STANDARD THEMED BUTTON
                        com.example.smarty.ui.components.CogniButton(
                            text = "Start Enrollment",
                            onClick = { enrollmentManager.startEnrollment() }
                        )
                    }
                    is VoiceEnrollmentManager.EnrollmentState.WaitingToRecord -> {
                         Text(
                             text = "Say this aloud",
                             style = MaterialTheme.typography.labelLarge,
                             color = accentColor
                         )
                         Spacer(modifier = Modifier.height(16.dp))
                         Text(
                             text = if (isHindi) state.phrase.hindi else state.phrase.transliteration,
                             style = MaterialTheme.typography.headlineMedium,
                             color = Color.White,
                             textAlign = TextAlign.Center
                         )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = state.phrase.english,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(40.dp))
                        
                        // Floating Action Button style for Record
                        FilledIconButton(
                            onClick = { enrollmentManager.startRecording() },
                            modifier = Modifier.size(80.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = accentColor,
                                contentColor = Color.White
                            )
                        ) {
                            Icon(Icons.Default.Mic, null, modifier = Modifier.size(32.dp))
                        }
                    }
                    is VoiceEnrollmentManager.EnrollmentState.Recording -> {
                        Text(
                            text = if (isHindi) currentPhrase?.hindi ?: "" else currentPhrase?.transliteration ?: "",
                            style = MaterialTheme.typography.headlineMedium,
                            color = Color.White,
                            textAlign = TextAlign.Center
                        )
                         Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Listening...",
                            style = MaterialTheme.typography.bodyLarge,
                            color = accentColor
                        )
                    }
                    is VoiceEnrollmentManager.EnrollmentState.Processing,
                    is VoiceEnrollmentManager.EnrollmentState.Finalizing -> {
                         Text(
                            text = "Processing...",
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White
                        )
                    }
                    is VoiceEnrollmentManager.EnrollmentState.Complete -> {
                         Text(
                            text = "Enrollment Complete",
                            style = MaterialTheme.typography.headlineMedium,
                            color = Color.White
                        )
                        Text(
                            text = "Your voice profile is ready.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }
                     is VoiceEnrollmentManager.EnrollmentState.Error -> {
                        Text(
                            text = "Couldn't hear you",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                         Spacer(modifier = Modifier.height(24.dp))
                         Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                             OutlinedButton(
                                 onClick = { enrollmentManager.resetEnrollment() },
                                 border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)),
                                 colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                             ) {
                                 Text("Cancel")
                             }
                             com.example.smarty.ui.components.CogniButton(
                                 text = "Try Again",
                                 onClick = { enrollmentManager.retryCurrentPhrase() },
                                 modifier = Modifier.width(120.dp)
                             )
                         }
                    }
                }

                // Standard Progress Indicators
                if (enrollmentState is VoiceEnrollmentManager.EnrollmentState.WaitingToRecord ||
                    enrollmentState is VoiceEnrollmentManager.EnrollmentState.Recording) {
                    
                    Spacer(modifier = Modifier.height(48.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        repeat(phrases.size) { index ->
                            val isActive = index == currentPhraseIndex
                            val isCompleted = index < currentPhraseIndex
                            
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when {
                                            isActive -> accentColor
                                            isCompleted -> Color.White
                                            else -> Color.White.copy(alpha = 0.3f)
                                        }
                                    )
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// VISUAL COMPONENTS (Future-Sci Aesthetic)
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
fun IntroOrb(color: Color) {
    Box(contentAlignment = Alignment.Center) {
        // Outer glow
        Box(
            modifier = Modifier
                .size(200.dp)
                .clip(CircleShape)
                .background(
                    androidx.compose.ui.graphics.Brush.radialGradient(
                        colors = listOf(color.copy(alpha = 0.2f), Color.Transparent)
                    )
                )
        )
        // Core
        Icon(
            Icons.Filled.GraphicEq,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = Color.White
        )
    }
}

@Composable
fun WaitingOrb(color: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Box(
        modifier = Modifier
            .size(180.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .border(1.dp, color.copy(alpha = 0.5f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(140.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.1f))
        )
        Icon(
            Icons.Default.MicNone,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = Color.White.copy(alpha = 0.8f)
        )
    }
}

@Composable
fun RecordingOrb(amplitude: Float, accentColor: Color) {
    // Dynamic visualization of voice
    // Multiple rings expanding based on amplitude
    
    Box(contentAlignment = Alignment.Center) {
        // Layer 3 (Large faint)
        Box(
            modifier = Modifier
                .size(200.dp + (100.dp * amplitude)) // Expands with voice
                .clip(CircleShape)
                .background(accentColor.copy(alpha = 0.1f))
        )
        // Layer 2 (Medium)
        Box(
            modifier = Modifier
                .size(160.dp + (60.dp * amplitude))
                .clip(CircleShape)
                .background(accentColor.copy(alpha = 0.2f))
        )
        // Layer 1 (Core)
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(Color.White)
                .border(4.dp, accentColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
             Icon(
                Icons.Default.Mic,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = accentColor
            )
        }
    }
}

@Composable
fun ProcessingOrb(color: Color) {
     val infiniteTransition = rememberInfiniteTransition(label = "spin")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    Box(contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(120.dp).rotate(rotation)) {
            drawArc(
                color = color,
                startAngle = 0f,
                sweepAngle = 270f,
                useCenter = false,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 8f, cap = StrokeCap.Round)
            )
        }
    }
}

@Composable
fun SuccessOrb(quality: Float) {
    val color = Color(0xFF00E676) // Bright Green
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.2f))
                .border(2.dp, color, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Check, null, tint = color, modifier = Modifier.size(64.dp))
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "Quality: ${(quality * 100).toInt()}%",
            color = color,
            style = MaterialTheme.typography.titleMedium
        )
    }
}

@Composable
fun ErrorOrb() {
    Icon(
        Icons.Default.Warning,
        contentDescription = null,
        modifier = Modifier.size(100.dp),
        tint = Color(0xFFFF5252)
    )
}


