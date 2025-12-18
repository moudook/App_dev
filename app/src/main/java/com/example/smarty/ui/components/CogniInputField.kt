package com.example.smarty.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.smarty.data.model.Attachment
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.animation.CogniEasing
import com.example.smarty.ui.animation.CogniMotion
import com.example.smarty.ui.theme.CogniShadow
import com.example.smarty.ui.theme.ComponentSpacing
import com.example.smarty.ui.theme.LocalShapes
import com.example.smarty.ui.theme.MonoFont
import com.example.smarty.ui.theme.SafetyOrange
import com.example.smarty.ui.theme.softCardShadow
import com.example.smarty.util.SpeechRecognitionHelper
import kotlinx.coroutines.delay

/**
 * Animated input field with focus state animations and attachment support
 * - Border glow animation on focus
 * - Animated send button with press feedback
 * - Expandable attachment type selector panel
 * - Attachment previews
 * - Smooth color transitions
 * - Chat mode with visual indicator
 * - AI exclusion indicator for private notes
 */
@Composable
fun CogniInputField(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Brain dump...",
    // Attachment support
    attachments: List<Attachment> = emptyList(),
    onPickImage: () -> Unit = {},
    onPickVideo: () -> Unit = {},
    onPickDocument: () -> Unit = {},
    onPickAudio: () -> Unit = {},
    onPickFile: () -> Unit = {},
    onPickLink: () -> Unit = {},
    onRemoveAttachment: (String) -> Unit = {},
    // Chat mode support
    isChatMode: Boolean = false,
    chatPlaceholder: String = "Ask anything...",
    isProcessing: Boolean = false,
    onOpenChatHistory: () -> Unit = {},
    // AI exclusion support
    isAiExcluded: Boolean = false,
    // Search mode support
    isSearchMode: Boolean = false,
    onToggleSearch: () -> Unit = {}
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }

    // Speech recognition
    val speechRecognizer = remember { SpeechRecognitionHelper(context) }
    val isListening by speechRecognizer.isListening.collectAsState()
    val partialResult by speechRecognizer.partialResult.collectAsState()
    val rmsLevel by speechRecognizer.rmsLevel.collectAsState()
    val speechError by speechRecognizer.error.collectAsState()

    // Permission launcher for microphone
    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasAudioPermission = granted
        if (granted) {
            speechRecognizer.startListening { recognizedText ->
                // Append recognized text to current value
                val newValue = if (value.isBlank()) recognizedText
                else "$value $recognizedText"
                onValueChange(newValue)
            }
        }
    }

    // Cleanup speech recognizer
    DisposableEffect(Unit) {
        onDispose {
            speechRecognizer.destroy()
        }
    }

    // Start/stop speech recognition
    fun toggleSpeechRecognition() {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        if (isListening) {
            speechRecognizer.stopListening()
        } else {
            if (hasAudioPermission) {
                speechRecognizer.startListening { recognizedText ->
                    val newValue = if (value.isBlank()) recognizedText
                    else "$value $recognizedText"
                    onValueChange(newValue)
                }
            } else {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
    var isFocused by remember { mutableStateOf(false) }
    var isButtonPressed by remember { mutableStateOf(false) }
    var isAddPressed by remember { mutableStateOf(false) }
    var showAttachmentPanel by remember { mutableStateOf(false) }

    // Clear focus when submitting
    val handleSubmit: () -> Unit = {
        focusManager.clearFocus()
        onSubmit()
    }

    // Show attachment panel when input is focused, hide with delay when unfocused
    LaunchedEffect(isFocused) {
        if (isFocused) {
            showAttachmentPanel = true
        } else {
            // Delay hiding to allow button taps
            delay(300)
            if (!isFocused) {
                showAttachmentPanel = false
            }
        }
    }

    // Focus state animations - Modern Soft Minimalist
    val borderColor by animateColorAsState(
        targetValue = when {
            isFocused -> LocalAccentColor.current
            else -> androidx.compose.ui.graphics.Color.Transparent
        },
        animationSpec = tween(200, easing = CogniEasing.appleEaseOut),
        label = "borderColor"
    )

    val borderWidth by animateFloatAsState(
        targetValue = if (isFocused) 2f else 0f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "borderWidth"
    )

    // Shadow elevation for focus glow
    val shadowElevation by animateFloatAsState(
        targetValue = if (isFocused) CogniShadow.glowElevation.value else 0f,
        animationSpec = tween(200),
        label = "shadowElevation"
    )

    // Magic Prompt animation - Breathes when waiting for input
    val basePromptScale by animateFloatAsState(
        targetValue = if (isFocused || value.isNotEmpty()) 1.1f else 1f,
        animationSpec = CogniMotion.bouncy,
        label = "basePromptScale"
    )
    // Add breathing effect on top of base scale
    val breathingScale = com.example.smarty.ui.animation.rememberBreathingScale(periodMs = 1500)
    val promptScale = if (isFocused) basePromptScale * breathingScale else basePromptScale

    // Send button animations - enabled if text or attachments present
    val buttonEnabled = value.isNotBlank() || attachments.isNotEmpty()
    val buttonScale by animateFloatAsState(
        targetValue = when {
            isButtonPressed && buttonEnabled -> 0.85f
            buttonEnabled -> 1f
            else -> 0.9f
        },
        animationSpec = CogniMotion.quick,
        label = "buttonScale"
    )

    val buttonRotation by animateFloatAsState(
        targetValue = if (isButtonPressed && buttonEnabled) -25f else 0f, // More dramatic swoosh
        animationSpec = CogniMotion.veryBouncy,
        label = "buttonRotation"
    )

    val buttonAlpha by animateFloatAsState(
        targetValue = if (buttonEnabled) 1f else 0.5f,
        animationSpec = tween(150),
        label = "buttonAlpha"
    )

    // Add button animations - now opens file picker directly
    val addButtonScale by animateFloatAsState(
        targetValue = if (isAddPressed) 0.85f else 1f,
        animationSpec = CogniMotion.quick,
        label = "addButtonScale"
    )

    val addButtonColor by animateColorAsState(
        targetValue = if (attachments.isNotEmpty()) LocalAccentColor.current else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(200),
        label = "addButtonColor"
    )

    // Chat mode animations - Using surfaceVariant for input background
    val surfaceColor by animateColorAsState(
        targetValue = if (isChatMode) {
            LocalAccentColor.current.copy(alpha = 0.1f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        animationSpec = tween(300, easing = CogniEasing.appleEaseOut),
        label = "surfaceColor"
    )

    val chatIndicatorColor by animateColorAsState(
        targetValue = if (isChatMode) LocalAccentColor.current else MaterialTheme.colorScheme.outline,
        animationSpec = tween(200),
        label = "chatIndicatorColor"
    )

    // Use appropriate placeholder based on mode
    val currentPlaceholder = when {
        isSearchMode -> "Search notes..."
        isChatMode -> chatPlaceholder
        else -> placeholder
    }

    Column(modifier = modifier.fillMaxWidth()) {
        val shapes = LocalShapes.current

        // Chat mode indicator
        AnimatedVisibility(
            visible = isChatMode,
            enter = slideInVertically(initialOffsetY = { 20 }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { 20 }) + fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier.clickable { onOpenChatHistory() },
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = LocalAccentColor.current.copy(alpha = 0.8f), // Increased opacity (+50%)
                    border = androidx.compose.foundation.BorderStroke(
                        width = 1.dp,
                        color = LocalAccentColor.current.copy(alpha = 0.25f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.ChatBubble,
                            contentDescription = null,
                            tint = androidx.compose.ui.graphics.Color.White,
                            modifier = Modifier.size(14.dp)
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        Text(
                            text = "Chat Mode",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                            ),
                            color = androidx.compose.ui.graphics.Color.White
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        // Subtle divider
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(10.dp)
                                .background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.3f))
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        Text(
                            text = "Tap for history",
                            style = MaterialTheme.typography.labelMedium,
                            color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }

        // AI exclusion indicator (shows when typing and AI excluded)
        AnimatedVisibility(
            visible = isAiExcluded && value.isNotBlank() && !isChatMode,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, SafetyOrange.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                    .background(SafetyOrange.copy(alpha = 0.1f)) // Subtle warning tint
                    .padding(vertical = 8.dp, horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.VisibilityOff,
                    contentDescription = null,
                    tint = SafetyOrange,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Private Note",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                    color = SafetyOrange
                )
                Text(
                    text = " • AI excluded",
                    style = MaterialTheme.typography.labelMedium,
                    color = SafetyOrange.copy(alpha = 0.8f)
                )
            }
        }

        // Attachment previews (above the panel)
        AttachmentPreviewRow(
            attachments = attachments,
            onRemoveAttachment = onRemoveAttachment
        )

        // Attachment type selector panel (above input)
        AttachmentTypeSelector(
            visible = showAttachmentPanel && !isChatMode,
            onSelectImage = {
                onPickImage()
                showAttachmentPanel = false
            },
            onSelectVideo = {
                onPickVideo()
                showAttachmentPanel = false
            },
            onSelectDocument = {
                onPickDocument()
                showAttachmentPanel = false
            },
            onSelectAudio = {
                onPickAudio()
                showAttachmentPanel = false
            },
            onSelectFile = {
                onPickFile()
                showAttachmentPanel = false
            },
            onSelectLink = {
                onPickLink()
                showAttachmentPanel = false
            }
        )

        // Main input surface with focus glow and high contrast
        
        // Unified background color (consistent aesthetic)
        // Use Surface (White) for high contrast and clean look in all modes
        val inputBackgroundColor = MaterialTheme.colorScheme.surface
        
        // Permanent border that highlights on focus
        // Always Blue (Accent Color) - Higher opacity when focused, subtle when inactive
        val currentBorderColor = if (isFocused) {
            LocalAccentColor.current // 100% opacity when active
        } else {
            LocalAccentColor.current.copy(alpha = 0.5f) // 50% opacity when inactive (Always Blue)
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .softCardShadow(shape = androidx.compose.foundation.shape.RoundedCornerShape(32.dp), elevation = if(isFocused) 8.dp else 4.dp) // Floating effect
                .border(
                    width = if (isFocused) 1.5.dp else 1.dp,
                    color = currentBorderColor,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(32.dp)
                ),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(32.dp),
            color = inputBackgroundColor,
            shadowElevation = 0.dp // Using custom soft shadow
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Animated Mode Toggle (Write/Search) OR Microphone (Chat Mode)
                // In chat mode: Mic icon for speech-to-text
                // In main page: Arrow/Search icon for search toggle
                val leftIcon = when {
                    isChatMode && isListening -> Icons.Default.MicOff
                    isChatMode -> Icons.Default.Mic
                    isSearchMode -> Icons.Default.Search
                    else -> Icons.AutoMirrored.Filled.KeyboardArrowRight
                }

                // Mic button animations for chat mode
                val micPulseTransition = rememberInfiniteTransition(label = "left_mic_pulse")
                val leftMicPulseScale by micPulseTransition.animateFloat(
                    initialValue = 1f,
                    targetValue = 1.15f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(600, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "leftMicPulse"
                )

                val leftButtonColor by animateColorAsState(
                    targetValue = when {
                        isChatMode && isListening -> SafetyOrange
                        else -> LocalAccentColor.current
                    },
                    animationSpec = tween(200),
                    label = "leftButtonColor"
                )

                IconButton(
                    onClick = {
                        if (isChatMode) {
                            // In chat mode, trigger speech recognition
                            toggleSpeechRecognition()
                        } else {
                            // In main page, toggle search
                            onToggleSearch()
                        }
                    },
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .scale(if (isChatMode && isListening) leftMicPulseScale else promptScale)
                        .size(32.dp) // Slightly larger touch target
                ) {

                    if (isChatMode && isListening) {
                        // Dynamic Waveform Visualizer
                        WaveformVisualizer(
                            rmsLevel = rmsLevel,
                            color = leftButtonColor,
                            modifier = Modifier.size(24.dp)
                        )
                    } else {
                        // Standard Icon
                        AnimatedContent(
                            targetState = leftIcon,
                            transitionSpec = {
                                (fadeIn(animationSpec = tween(220, delayMillis = 90)) +
                                    scaleIn(initialScale = 0.92f, animationSpec = tween(220, delayMillis = 90)))
                                    .togetherWith(fadeOut(animationSpec = tween(90)))
                            },
                            label = "leftModeIcon"
                        ) { icon ->
                            Icon(
                                imageVector = icon,
                                contentDescription = when {
                                    isChatMode -> "Voice Input"
                                    isSearchMode -> "Search Mode"
                                    else -> "Write Mode"
                                },
                                tint = leftButtonColor,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }


                // Input field with focus tracking
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 16.dp) // Increased padding for clearer UI
                ) {
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .onFocusChanged { focusState ->
                                isFocused = focusState.isFocused
                            },
                        textStyle = TextStyle(
                            fontFamily = MonoFont,
                            fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(LocalAccentColor.current),
                        singleLine = false,
                        maxLines = 4
                    )

                    // Animated placeholder
                    androidx.compose.animation.AnimatedVisibility(
                        visible = value.isEmpty() && attachments.isEmpty(),
                        enter = fadeIn(tween(150)),
                        exit = fadeOut(tween(100))
                    ) {
                        Text(
                            text = currentPlaceholder,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = MonoFont
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }

                    // Processing indicator for chat mode
                    if (isProcessing) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Custom Smooth Spinner (Exact 0-360 loop, no jumping)
                            val spinnerTransition = rememberInfiniteTransition(label = "thinking_spinner")
                            val angle by spinnerTransition.animateFloat(
                                initialValue = 0f,
                                targetValue = 360f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(1000, easing = LinearEasing),
                                    repeatMode = RepeatMode.Restart
                                ),
                                label = "angle"
                            )
                            val spinnerColor = LocalAccentColor.current

                            Canvas(modifier = Modifier.size(16.dp)) {
                                rotate(angle) {
                                    drawArc(
                                        color = spinnerColor,
                                        startAngle = 0f,
                                        sweepAngle = 270f,
                                        useCenter = false,
                                        style = Stroke(
                                            width = 2.dp.toPx(),
                                            cap = StrokeCap.Round
                                        )
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Thinking...",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = MonoFont
                                ),
                                color = LocalAccentColor.current.copy(alpha = 0.7f)
                            )
                        }
                    }
                }



                Spacer(modifier = Modifier.width(4.dp))

                // RMS level indicator (shows when listening in chat mode - visual feedback on left button)
                // The microphone button is now on the left side in chat mode

                // Animated send button - Filled circle style
                val sendButtonContainerColor by animateColorAsState(
                    targetValue = if (buttonEnabled) LocalAccentColor.current else androidx.compose.ui.graphics.Color.Transparent,
                    animationSpec = tween(200),
                    label = "sendBtnContainer"
                )

                val sendButtonContentColor by animateColorAsState(
                    targetValue = if (buttonEnabled) androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    animationSpec = tween(200),
                    label = "sendBtnContent"
                )

                Surface(
                    modifier = Modifier
                        .size(36.dp) // Compact, premium circle
                        .scale(buttonScale)
                        .graphicsLayer {
                            rotationZ = buttonRotation
                            alpha = buttonAlpha
                        }
                        .pointerInput(buttonEnabled) {
                            if (buttonEnabled) {
                                detectTapGestures(
                                    onPress = {
                                        isButtonPressed = true
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        tryAwaitRelease()
                                        isButtonPressed = false
                                    },
                                    onTap = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        showAttachmentPanel = false
                                        // Stop listening if active before sending
                                        if (isListening) speechRecognizer.stopListening()
                                        handleSubmit()
                                    }
                                )
                            }
                        },
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = sendButtonContainerColor,
                    // No border
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = sendButtonContentColor,
                            modifier = Modifier
                                .size(18.dp)
                                .offset(x = 2.dp) // Optical centering for send icon
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp)) // Right padding inside container
            }
        }
    }
}
@Composable
private fun WaveformVisualizer(
    rmsLevel: Float,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Generate 5 bars with different sensitivities to simulate frequency bands
        val multipliers = listOf(0.4f, 0.7f, 1.0f, 0.6f, 0.3f)
        val speeds = listOf(200f, 300f, 400f, 250f, 150f) // Different stiffness for randomness

        multipliers.forEachIndexed { index, multiplier ->
            // Base height + RMS reaction
            // Add some noise/randomness if RMS is low to keep it "alive"
            val targetH = (rmsLevel * 2.5f * multiplier).coerceIn(4f, 24f) // Scale RMS to height
            
            val barHeight by animateDpAsState(
                targetValue = targetH.dp,
                animationSpec = spring(
                    dampingRatio = 0.6f,
                    stiffness = speeds[index]
                ),
                label = "barHeight$index"
            )

            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(barHeight)
                    .background(color, androidx.compose.foundation.shape.CircleShape)
            )
        }
    }
}
