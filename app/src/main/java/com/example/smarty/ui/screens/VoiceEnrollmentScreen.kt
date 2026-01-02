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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.voice.speaker.VoiceEnrollmentManager
import kotlinx.coroutines.delay
import com.example.smarty.ui.theme.SafetyOrange

/**
 * Voice Enrollment Screen - Stacks / NoteCard Design
 * 
 * Clean, card-based UI for biometric setup.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    val accentColor = LocalAccentColor.current

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
    
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Voice Setup") },
                navigationIcon = {
                    IconButton(onClick = { 
                        enrollmentManager.resetEnrollment()
                        onSkip() 
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Language Toggle - Pill Style
                    Surface(
                        onClick = { isHindi = !isHindi },
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isHindi) "EN" else "HI",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isHindi) "हिं" else "EN",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            
            // ═══════════════════════════════════════════════════════════════════
            // MAIN CARD (Stack Style)
            // ═══════════════════════════════════════════════════════════════════
            Card(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(32.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    when (val state = enrollmentState) {
                        is VoiceEnrollmentManager.EnrollmentState.NotStarted -> IntroOrb(accentColor)
                        is VoiceEnrollmentManager.EnrollmentState.WaitingToRecord -> WaitingOrb(accentColor)
                        is VoiceEnrollmentManager.EnrollmentState.Recording -> RecordingOrb(recordingAmplitude, accentColor)
                        is VoiceEnrollmentManager.EnrollmentState.Processing,
                        is VoiceEnrollmentManager.EnrollmentState.Finalizing -> ProcessingOrb(accentColor)
                        is VoiceEnrollmentManager.EnrollmentState.Complete -> SuccessOrb(state.quality, accentColor)
                        is VoiceEnrollmentManager.EnrollmentState.Error -> ErrorOrb()
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))

            // ═══════════════════════════════════════════════════════════════════
            // INSTRUCTIONS & CONTROLS
            // ═══════════════════════════════════════════════════════════════════
            
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
                modifier = Modifier.fillMaxWidth()
            ) {
                when (val state = enrollmentState) {
                    is VoiceEnrollmentManager.EnrollmentState.NotStarted -> {
                         Text(
                            text = "Voice Match",
                            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Teach the assistant to recognize your unique voice for secure hands-free access.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(32.dp))
                        
                        // Action Button
                        Button(
                            onClick = { enrollmentManager.startEnrollment() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = accentColor)
                        ) {
                            Text("Start Enrollment", style = MaterialTheme.typography.titleMedium)
                        }
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
                             style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                             color = MaterialTheme.colorScheme.onBackground,
                             textAlign = TextAlign.Center
                         )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = state.phrase.english,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(40.dp))
                        
                        // Floating Mic Button
                        Button(
                            onClick = { enrollmentManager.startRecording() },
                            modifier = Modifier.size(80.dp),
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = accentColor,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            elevation = ButtonDefaults.buttonElevation(6.dp)
                        ) {
                            Icon(Icons.Default.Mic, null, modifier = Modifier.size(32.dp))
                        }
                    }
                    is VoiceEnrollmentManager.EnrollmentState.Recording -> {
                        Text(
                            text = if (isHindi) currentPhrase?.hindi ?: "" else currentPhrase?.transliteration ?: "",
                            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onBackground,
                            textAlign = TextAlign.Center
                        )
                         Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Listening...",
                            style = MaterialTheme.typography.bodyLarge,
                            color = accentColor,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(40.dp))
                        // Placeholder to keep layout stable
                        Spacer(modifier = Modifier.height(80.dp)) 
                    }
                    is VoiceEnrollmentManager.EnrollmentState.Processing,
                    is VoiceEnrollmentManager.EnrollmentState.Finalizing -> {
                         Text(
                            text = "Processing...",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.height(120.dp))
                    }
                    is VoiceEnrollmentManager.EnrollmentState.Complete -> {
                         Text(
                            text = "Enrollment Complete",
                            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Your voices profile is ready.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.height(120.dp))
                    }
                     is VoiceEnrollmentManager.EnrollmentState.Error -> {
                        Text(
                            text = "Couldn't hear you",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.error
                        )
                         Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                         Spacer(modifier = Modifier.height(32.dp))
                         
                         Row(
                             modifier = Modifier.fillMaxWidth(),
                             horizontalArrangement = Arrangement.spacedBy(16.dp)
                         ) {
                             OutlinedButton(
                                 onClick = { enrollmentManager.resetEnrollment() },
                                 modifier = Modifier.weight(1f).height(56.dp),
                                 shape = RoundedCornerShape(16.dp),
                                 border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                             ) {
                                 Text("Cancel")
                             }
                             Button(
                                 onClick = { enrollmentManager.retryCurrentPhrase() },
                                 modifier = Modifier.weight(1f).height(56.dp),
                                 shape = RoundedCornerShape(16.dp),
                                 colors = ButtonDefaults.buttonColors(containerColor = accentColor)
                             ) {
                                 Text("Try Again")
                             }
                         }
                    }
                }

                // Progress Indicators (Pills)
                if (enrollmentState is VoiceEnrollmentManager.EnrollmentState.WaitingToRecord ||
                    enrollmentState is VoiceEnrollmentManager.EnrollmentState.Recording) {
                    
                    Spacer(modifier = Modifier.height(32.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        repeat(phrases.size) { index ->
                            val isActive = index == currentPhraseIndex
                            val isCompleted = index < currentPhraseIndex
                            
                            Box(
                                modifier = Modifier
                                    .width(if (isActive) 24.dp else 12.dp)
                                    .height(12.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        when {
                                            isActive -> accentColor
                                            isCompleted -> MaterialTheme.colorScheme.primaryContainer
                                            else -> MaterialTheme.colorScheme.surfaceContainerHighest
                                        }
                                    )
                                    .animateContentSize()
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
// VISUAL COMPONENTS (Clean Aesthetic)
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
fun IntroOrb(color: Color) {
    Box(contentAlignment = Alignment.Center) {
        // Outer ring
        Box(
            modifier = Modifier
                .size(200.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.1f))
        )
        // Core
        Icon(
            Icons.Filled.GraphicEq,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = color
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
            animation = tween(1500, easing = LinearEasing), // Smoother pulse
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
            .border(2.dp, color.copy(alpha = 0.2f), CircleShape),
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
            tint = color
        )
    }
}

@Composable
fun RecordingOrb(amplitude: Float, accentColor: Color) {
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
                .background(MaterialTheme.colorScheme.surface)
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
fun SuccessOrb(quality: Float, accentColor: Color) {
    val color = Color(0xFF00E676) // Bright Green
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.1f))
                .border(2.dp, color, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Check, null, tint = color, modifier = Modifier.size(64.dp))
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "Quality: ${(quality * 100).toInt()}%",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun ErrorOrb() {
    Icon(
        Icons.Default.Warning,
        contentDescription = null,
        modifier = Modifier.size(100.dp),
        tint = MaterialTheme.colorScheme.error
    )
}


