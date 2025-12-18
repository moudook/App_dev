package com.example.smarty.ui.components

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
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Mic
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.compositeOver
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
import com.example.smarty.util.rememberSpeechToText
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
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
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
    isVoiceListening: Boolean = false,
    onToggleSearch: () -> Unit = {},
    onStartVoiceInput: () -> Unit = {},
    onStopVoiceInput: () -> Unit = {}
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }

    // Speech recognition using Google's built-in dialog
    // This launches the standard Google Speech Recognition popup
    // Shared error handler
    // Focus state
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
        targetValue = if (isFocused || value.text.isNotEmpty()) 1.1f else 1f,
        animationSpec = CogniMotion.bouncy,
        label = "basePromptScale"
    )
    // Add breathing effect on top of base scale
    val breathingScale = com.example.smarty.ui.animation.rememberBreathingScale(periodMs = 1500)
    val promptScale = if (isFocused) basePromptScale * breathingScale else basePromptScale

    // Send button animations - enabled if text or attachments present
    val buttonEnabled = value.text.isNotBlank() || attachments.isNotEmpty()
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

        // AI exclusion indicator (shows when content is present and AI excluded)
        AnimatedVisibility(
            visible = isAiExcluded && (value.text.isNotBlank() || attachments.isNotEmpty()) && !isChatMode,
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
            Box(modifier = Modifier.fillMaxWidth()) {
                // Shimmer Background Layer (Behind content)
                if (isVoiceListening) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .halftoneShimmer(true, LocalAccentColor.current)
                    )
                }

                Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Animated Mode Toggle (Write/Search) OR Microphone (Chat Mode)
                // In chat mode: Mic icon for speech-to-text (launches Google dialog)
                // In main page: Arrow/Search icon for search toggle
                val leftIcon = when {
                    isChatMode -> Icons.Default.Mic
                    isSearchMode -> Icons.Default.Search
                    else -> Icons.AutoMirrored.Filled.KeyboardArrowRight
                }

                val leftButtonColor = if (isVoiceListening) LocalAccentColor.current else LocalAccentColor.current

                // YouTube-style Shimmer Effect REMOVED - Replaced by Halftone on Surface

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .scale(promptScale)
                        .size(32.dp)
                ) {
                    // Shimmer/Pulse Effect REMOVED

                    IconButton(
                        onClick = {
                            if (isChatMode) {
                                // In chat mode, toggle listening (start or stop)
                                // Don't focus the text field - keyboard should not open
                                onStartVoiceInput()
                            } else {
                                // In main page, toggle search
                                onToggleSearch()
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    ) {
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
                                    isVoiceListening -> "Stop Listening"
                                    isChatMode -> "Voice Input"
                                    isSearchMode -> "Search Mode"
                                    else -> "Write Mode"
                                },
                                tint = if (isVoiceListening) Color.White else leftButtonColor,
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
                                // Stop voice input when user taps on text field to type
                                if (focusState.isFocused && isVoiceListening && isChatMode) {
                                    onStopVoiceInput()
                                }
                            },
                        textStyle = TextStyle(
                            fontFamily = MonoFont,
                            fontWeight = if (isVoiceListening) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
                            fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                            color = if (isVoiceListening) LocalAccentColor.current else MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(LocalAccentColor.current),
                        singleLine = false,
                        maxLines = 4
                    )

                    // Animated placeholder
                    androidx.compose.animation.AnimatedVisibility(
                        visible = value.text.isEmpty() && attachments.isEmpty(),
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
}

/**
 * Halftone Shimmer Effect Modifier
 * Applies a dynamic dotted texture animation overlay when visible.
 */
fun Modifier.halftoneShimmer(
    isVisible: Boolean,
    color: Color
): Modifier = composed {
    if (!isVisible) return@composed this

    val transition = rememberInfiniteTransition(label = "halftone")
    // Animate the wave position across the component diagonally
    val progress by transition.animateFloat(
        initialValue = -0.3f, 
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "progress"
    )

    this.drawWithContent {
        // Draw the content first (background + text)
        drawContent()
        
        // --- Halftone Configuration ---
        val dotBaseRadius = 1.2.dp.toPx()
        val spacing = 5.dp.toPx() // Tighter spacing for finer texture
        val waveWidth = 350.dp.toPx() // Wide wave for smoothness
        
        // Hexagonal grid packing (sin 60 degrees) or basic stagger
        val rowHeight = spacing * 0.866f 
        
        // Calculate max extent for the diagonal sweep (x + y)
        val maxExtent = size.width + size.height
        val wavePos = maxExtent * progress

        val cols = (size.width / spacing).toInt() + 2
        val rows = (size.height / rowHeight).toInt() + 2
        
        for (row in 0 until rows) {
            val y = row * rowHeight
            
            // Stagger every odd row by half spacing for hex grid feel
            val xOffset = if (row % 2 == 1) spacing / 2f else 0f
            
            for (col in 0 until cols) {
                val x = (col * spacing) + xOffset
                
                // Determine position in the diagonal wave scalar field (x + y)
                // This corresponds to a 45-degree linear gradient sweep
                val diagonalPos = x + y
                
                // Distance from the current wave front
                val dist = kotlin.math.abs(diagonalPos - wavePos)
                
                if (dist < waveWidth) {
                    // Normalize distance (0..1) -> 1 at center, 0 at edges
                    val rawIntensity = 1f - (dist / waveWidth)
                    
                    // Apply easing curve for visual punch
                    // Cubic or quartic ease-in makes the edge falloff sharper and center brighter
                    val intensity = rawIntensity * rawIntensity * rawIntensity
                    
                    if (intensity > 0.02f) { // Optimization cull
                        // visual parameters based on intensity
                        // Grow radius slightly at peak
                        val radius = dotBaseRadius * (0.7f + (0.6f * intensity))
                        
                        // Scale alpha: faint background presence to distinct shimmer
                        // Max alpha 0.25f ensures text remains readable
                        val alpha = (0.05f + (0.25f * intensity)).coerceIn(0f, 0.3f)
                        
                        drawCircle(
                            color = color.copy(alpha = alpha),
                            radius = radius,
                            center = androidx.compose.ui.geometry.Offset(x, y)
                        )
                    }
                }
            }
        }
    }
}