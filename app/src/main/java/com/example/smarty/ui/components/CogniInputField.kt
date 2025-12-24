package com.example.smarty.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import kotlinx.coroutines.withTimeoutOrNull
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
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Security
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
import androidx.compose.ui.draw.shadow
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarty.data.model.Attachment
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.animation.CogniEasing
import com.example.smarty.ui.animation.CogniMotion
import com.example.smarty.ui.theme.CogniShadow
import com.example.smarty.ui.animation.halftoneShimmer
import com.example.smarty.ui.animation.directionalShimmer
import com.example.smarty.ui.animation.ShimmerDirection
import com.example.smarty.ui.theme.ComponentSpacing
import com.example.smarty.ui.theme.LocalShapes
import com.example.smarty.ui.theme.MonoFont
import com.example.smarty.ui.theme.SafetyOrange
import com.example.smarty.ui.theme.softCardShadow
import com.example.smarty.ui.utils.AnimationLifecycleState
import com.example.smarty.ui.utils.rememberAnimationLifecycleState
import com.example.smarty.util.rememberSpeechToText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Color constants for attachment indicators
private val AttachmentRedColor = androidx.compose.ui.graphics.Color(0xFFF44336)
private val AttachmentGreenColor = androidx.compose.ui.graphics.Color(0xFF4CAF50)
private val AttachmentBlueColor = androidx.compose.ui.graphics.Color(0xFF2196F3)
private val AttachmentOrangeColor = androidx.compose.ui.graphics.Color(0xFFFF9500)
private val AttachmentGrayColor = androidx.compose.ui.graphics.Color(0xFF607D8B)

// Agent shimmer color (purple/blue gradient feel)
private val AgentShimmerColor = androidx.compose.ui.graphics.Color(0xFF7C4DFF)

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
    onStopVoiceInput: () -> Unit = {},
    // Voice recording (hold mic button to record, release to stop)
    onStartRecording: () -> Unit = {},
    onStopRecording: () -> Unit = {},
    isRecording: Boolean = false,
    // Agent working state (for shimmer direction)
    isAgentWorking: Boolean = false,
    // Auto-send countdown active (for fast shimmer)
    autoSendActive: Boolean = false,
    // Clear input callback
    onClearInput: () -> Unit = {},
    // Search filter parameters (used when isSearchMode = true)
    selectedFilters: Set<AttachmentOption> = emptySet(),
    onFilterToggle: (AttachmentOption) -> Unit = {},
    onClearFilters: () -> Unit = {}
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
    var showAttachmentPreview by remember { mutableStateOf(false) } // New state for preview panel

    // Local scope for animations
    val scope = rememberCoroutineScope()
    // Animation value for the flying plane (0f -> 1f)
    val flyAnimation = remember { Animatable(0f) }


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

    // Reset preview state when all attachments are cleared
    LaunchedEffect(attachments.isEmpty()) {
        if (attachments.isEmpty()) {
            showAttachmentPreview = false
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
    // Add breathing effect on top of base scale - only runs when focused (static when not)
    val breathingScale = com.example.smarty.ui.animation.rememberBreathingScale(
        periodMs = 1500,
        isActive = isFocused  // Only animate when focused - saves CPU/GPU when idle
    )
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
    // Priority: Listening > Search > Chat > Default
    val currentPlaceholder = when {
        isVoiceListening -> "Listening..."
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

        // AI exclusion indicator (Stealth Mode / Privacy Pill)
        // Shows when content is present and AI excluded
        AnimatedVisibility(
            visible = isAiExcluded && (value.text.isNotBlank() || attachments.isNotEmpty()) && !isChatMode,
            enter = slideInVertically(
                initialOffsetY = { 40 },
                animationSpec = CogniMotion.offsetBouncy
            ) + fadeIn(animationSpec = tween(200)) + scaleIn(
                initialScale = 0.8f,
                animationSpec = CogniMotion.bouncy
            ),
            exit = slideOutVertically(
                targetOffsetY = { 20 },
                animationSpec = CogniMotion.offsetQuick
            ) + fadeOut(animationSpec = tween(150)) + scaleOut(
                targetScale = 0.9f,
                animationSpec = CogniMotion.quick
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier
                        .clip(androidx.compose.foundation.shape.CircleShape)  // Clip shimmer to pill bounds
                        .halftoneShimmer(true, MaterialTheme.colorScheme.inverseOnSurface),
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = MaterialTheme.colorScheme.inverseSurface, // "Stealth" look (Dark in light mode, Light in dark mode)
                    shadowElevation = 4.dp,
                    border = androidx.compose.foundation.BorderStroke(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        // Animated lock icon pulse - LIFECYCLE AWARE
                        val lifecycleState by rememberAnimationLifecycleState()
                        val shouldAnimate = lifecycleState == AnimationLifecycleState.RUNNING

                        // Always create transition unconditionally (Compose rule)
                        val infiniteTransition = rememberInfiniteTransition(label = "privacy_pulse")
                        val animatedAlpha by infiniteTransition.animateFloat(
                            initialValue = 0.7f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1500),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "alpha"
                        )
                        // Only use animated value when animation should run
                        val alpha = if (shouldAnimate) animatedAlpha else 0.85f

                        Icon(
                            imageVector = Icons.Filled.Security,
                            contentDescription = "Hidden",
                            tint = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = alpha),
                            modifier = Modifier.size(14.dp)
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        Text(
                            text = "Private Mode",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            ),
                            color = MaterialTheme.colorScheme.inverseOnSurface
                        )
                        
                        // Vertical divider
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 8.dp)
                                .width(1.dp)
                                .height(12.dp)
                                .background(MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.3f))
                        )

                        Text(
                            text = "AI Blind",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }

        // Attachment Preview Panel (shows when circles are clicked)
        AnimatedVisibility(
            visible = attachments.isNotEmpty() && showAttachmentPreview,
            enter = expandVertically(
                animationSpec = spring(
                    dampingRatio = 0.7f,
                    stiffness = 300f
                ),
                expandFrom = Alignment.Top
            ) + fadeIn(animationSpec = tween(200)),
            exit = shrinkVertically(
                animationSpec = tween(200, easing = CogniEasing.appleEaseOut),
                shrinkTowards = Alignment.Top
            ) + fadeOut(animationSpec = tween(150))
        ) {
            AttachmentPreviewRow(
                attachments = attachments,
                onRemoveAttachment = { id ->
                    onRemoveAttachment(id)
                    // Close preview if this is the last attachment being removed
                    if (attachments.size == 1) {
                        showAttachmentPreview = false
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            )
        }

        // Attachment/Filter selector panel (above input)
        // Shows filters when in search mode, attachments otherwise
        // Panel stays open while focused - doesn't close when selecting attachments
        if (isSearchMode) {
            // Show filter selector in search mode
            SearchFilterTypeSelector(
                visible = showAttachmentPanel && !isChatMode,
                selectedFilters = selectedFilters,
                onFilterToggle = onFilterToggle,
                onClearFilters = onClearFilters
            )
        } else {
            // Show attachment type selector in normal mode
            AttachmentTypeSelector(
                visible = showAttachmentPanel && !isChatMode,
                onSelectImage = {
                    onPickImage()
                    // Don't close panel - let it stay open for multiple attachments
                },
                onSelectVideo = {
                    onPickVideo()
                    // Don't close panel
                },
                onSelectDocument = {
                    onPickDocument()
                    // Don't close panel
                },
                onSelectAudio = {
                    onPickAudio()
                    // Don't close panel
                },
                onSelectFile = {
                    onPickFile()
                    // Don't close panel
                },
                onSelectLink = {
                    onPickLink()
                    // Don't close panel
                }
            )
        }

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

        // Blue Shadow Config (Large, Bleeding Outer Shadow)
        val shadowColor = Color(0xFF0066FF) // Strong Blue
        val shadowElevation = if (isFocused) 32.dp else 16.dp // Large elevation for "bleed" effect
        val shadowAlpha = if (isFocused) 1f else 0.5f

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                // Large blue outer shadow that "bleeds"
                .shadow(
                    elevation = shadowElevation,
                    shape = RoundedCornerShape(32.dp),
                    spotColor = shadowColor.copy(alpha = shadowAlpha),
                    ambientColor = shadowColor.copy(alpha = shadowAlpha),
                    clip = false // Allow shadow to paint outside bounds
                )
                .border(
                    width = if (isFocused) 1.5.dp else 1.dp,
                    color = currentBorderColor,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(32.dp)
                ),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(32.dp),
            color = inputBackgroundColor,
            shadowElevation = 0.dp // Handled by custom shadow modifier
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                // Shimmer Background Layer (Behind content)
                // Priority: autoSend > voiceListening > agentWorking
                val showShimmer = autoSendActive || isVoiceListening || isAgentWorking
                if (showShimmer) {
                    val shimmerDirection = when {
                        // Agent working: right to left (purple shimmer)
                        isAgentWorking && !isVoiceListening && !autoSendActive -> ShimmerDirection.RIGHT_TO_LEFT
                        // Voice/auto-send: left to right (accent color)
                        else -> ShimmerDirection.LEFT_TO_RIGHT
                    }
                    val shimmerColor = when {
                        isAgentWorking && !isVoiceListening && !autoSendActive -> AgentShimmerColor
                        else -> LocalAccentColor.current
                    }
                    val shimmerSpeed = when {
                        autoSendActive -> 3.5f  // Fast blinking for auto-send countdown
                        else -> 1f
                    }
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .directionalShimmer(
                                isVisible = true,
                                color = shimmerColor,
                                direction = shimmerDirection,
                                speed = shimmerSpeed
                            )
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
                // In main page: Arrow/Search icon OR Cancel when text exists
                val hasText = value.text.isNotEmpty()
                val leftIcon = when {
                    isChatMode -> Icons.Default.Mic
                    isSearchMode -> Icons.Default.Search
                    hasText -> Icons.Default.Close  // Cancel button when there's text
                    else -> Icons.AutoMirrored.Filled.KeyboardArrowRight
                }

                val leftButtonColor = LocalAccentColor.current

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .scale(promptScale)
                        .size(32.dp)
                ) {
                    // Show stacked colored circles if attachments exist, otherwise show icon
                    if (attachments.isNotEmpty()) {
                        // Group attachments by type and show max 3 unique circles
                        val uniqueTypes = attachments
                            .map { it.getAttachmentType() }
                            .distinct()
                            .take(3) // Maximum 3 circles
                        
                        // Press state for circles
                        var isCirclesPressed by remember { mutableStateOf(false) }
                        val circlesScale by animateFloatAsState(
                            targetValue = if (isCirclesPressed) 0.85f else 1f,
                            animationSpec = CogniMotion.quick,
                            label = "circlesScale"
                        )
                        
                        // Stacked colored circles representing attachment types (not individual files)
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .scale(circlesScale)
                                .semantics {
                                    contentDescription = "View ${attachments.size} attachments"
                                    role = Role.Button
                                }
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onPress = {
                                            isCirclesPressed = true
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            tryAwaitRelease()
                                            isCirclesPressed = false
                                        },
                                        onTap = {
                                            showAttachmentPreview = !showAttachmentPreview
                                        }
                                    )
                                }
                        ) {
                            uniqueTypes.forEachIndexed { index, attachmentType ->
                                val attachmentColor = when (attachmentType) {
                                    com.example.smarty.data.model.AttachmentType.IMAGE -> AttachmentGreenColor
                                    com.example.smarty.data.model.AttachmentType.VIDEO -> AttachmentRedColor
                                    com.example.smarty.data.model.AttachmentType.DOCUMENT,
                                    com.example.smarty.data.model.AttachmentType.SPREADSHEET,
                                    com.example.smarty.data.model.AttachmentType.PRESENTATION -> AttachmentBlueColor
                                    com.example.smarty.data.model.AttachmentType.AUDIO -> AttachmentOrangeColor
                                    else -> AttachmentGrayColor
                                }
                                
                                // Offset each circle slightly to create stacked effect
                                val offsetX = (index * 3).dp
                                val offsetY = (index * 3).dp
                                
                                Surface(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .offset(x = offsetX, y = offsetY),
                                    shape = androidx.compose.foundation.shape.CircleShape,
                                    color = attachmentColor,
                                    border = androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.surface)
                                ) {}
                            }
                        }
                    } else {
                        // Original icon button when no attachments
                        IconButton(
                            onClick = {
                                if (isChatMode) {
                                    focusRequester.requestFocus()
                                    onStartVoiceInput()
                                } else if (hasText && !isSearchMode) {
                                    // Clear text when cancel button is shown
                                    onValueChange(TextFieldValue(""))
                                } else {
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
                                        hasText -> "Clear Text"
                                        else -> "Write Mode"
                                    },
                                    tint = if (isVoiceListening) Color.White else leftButtonColor,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }


                // Input field with focus tracking
                var lineCount by remember { mutableIntStateOf(1) }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = if (lineCount > 1) 12.dp else 16.dp) // Reduced padding when expanded
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
                                if (focusState.isFocused && isVoiceListening) {
                                    onStopVoiceInput()
                                }
                            },
                        onTextLayout = { textLayoutResult ->
                            lineCount = textLayoutResult.lineCount
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

                    // Animated placeholder - shows when input is empty
                    // CRITICAL: When voice listening is active, NEVER show placeholder if there's any text
                    // This prevents overlap between "Listening..." and incoming speech transcription
                    val showPlaceholder = when {
                        // Voice listening: only show "Listening..." if completely empty
                        isVoiceListening -> value.text.isEmpty()
                        // Normal mode: show placeholder if empty and no attachments
                        else -> value.text.isEmpty() && attachments.isEmpty()
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = showPlaceholder,
                        enter = fadeIn(tween(150)),
                        exit = fadeOut(tween(50))  // Faster exit to prevent overlap
                    ) {
                        Text(
                            text = currentPlaceholder,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = MonoFont,
                                fontWeight = if (isVoiceListening) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal
                            ),
                            color = if (isVoiceListening) LocalAccentColor.current else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }


                    // This prevents visual overlap when agent is thinking
                }



                Spacer(modifier = Modifier.width(4.dp))

                // In main mode: Show BOTH mic and send buttons
                // - Mic button: always visible for voice input
                // - Send button: appears when there's text/attachments
                // In chat mode: mic is on left, send is on right (existing behavior)
                
                if (!isChatMode) {
                    // MICROPHONE BUTTON (always visible in main mode)
                    // TAP = Speech-to-text (Google recognizer) - disabled during recording
                    // HOLD = Record audio (starts on hold, stops on release)
                    val micButtonScale by animateFloatAsState(
                        targetValue = if (isButtonPressed && !buttonEnabled) 0.85f else 1f,
                        animationSpec = CogniMotion.quick,
                        label = "micScale"
                    )

                    // Recording indicator animation
                    val recordingPulse by rememberInfiniteTransition(label = "recording").animateFloat(
                        initialValue = 1f,
                        targetValue = 1.15f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(600),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "recordingPulse"
                    )
                    val micScale = if (isRecording) recordingPulse else 1f

                    // Track if recording was started during this press (for hold-to-record)
                    var recordingStartedThisPress by remember { mutableStateOf(false) }

                    // Capture current state/lambdas to allow pointerInput(Unit) to use latest values
                    val currentOnStartRecording by rememberUpdatedState(onStartRecording)
                    val currentOnStopRecording by rememberUpdatedState(onStopRecording)
                    val currentOnStartVoiceInput by rememberUpdatedState(onStartVoiceInput)
                    val currentIsRecording by rememberUpdatedState(isRecording)
                    val currentIsVoiceListening by rememberUpdatedState(isVoiceListening)

                    Surface(
                        modifier = Modifier
                            .size(36.dp)
                            .scale(micButtonScale * micScale)
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    val down = awaitFirstDown()
                                    down.consume()
                                    isButtonPressed = true
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    recordingStartedThisPress = false
                                    val downTime = System.currentTimeMillis()

                                    // Long press threshold (300ms)
                                    val longPressThreshold = 300L

                                    // Wait for release with timeout to check for long press
                                    val up = withTimeoutOrNull(longPressThreshold) {
                                        waitForUpOrCancellation()
                                    }

                                    if (up == null) {
                                        // Timeout occurred - long press detected, start recording
                                        recordingStartedThisPress = true
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        currentOnStartRecording()

                                        // Now wait for the actual release
                                        val finalUp = waitForUpOrCancellation()
                                        finalUp?.consume()

                                        // Stop recording on release
                                        isButtonPressed = false
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        currentOnStopRecording()
                                    } else {
                                        // Released before timeout - short tap
                                        up.consume()
                                        isButtonPressed = false

                                        // Trigger speech-to-text (only if not already recording)
                                        if (!currentIsRecording && !currentIsVoiceListening) {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            currentOnStartVoiceInput()
                                        }
                                    }
                                }
                            },
                        shape = androidx.compose.foundation.shape.CircleShape,
                        color = if (isRecording) MaterialTheme.colorScheme.error else LocalAccentColor.current,
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Icon(
                                imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                                contentDescription = if (isRecording) "Recording... Release to stop" else "Tap for voice, Hold to record",
                                tint = androidx.compose.ui.graphics.Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Small spacer between mic and send
                    Spacer(modifier = Modifier.width(8.dp))
                }

                // SEND BUTTON (appears when has content in main mode, always visible in chat mode)
                AnimatedVisibility(
                    visible = buttonEnabled || isChatMode,
                    enter = scaleIn(initialScale = 0.8f, animationSpec = CogniMotion.bouncy) + fadeIn(),
                    exit = scaleOut(targetScale = 0.8f, animationSpec = CogniMotion.quick) + fadeOut()
                ) {
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

                    // Send Button with Flight Animation
                    Box(
                        modifier = Modifier
                            .size(36.dp)
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

                                            // Trigger Flight Animation - plane flies away!
                                            scope.launch {
                                                flyAnimation.snapTo(0f)
                                                flyAnimation.animateTo(
                                                    targetValue = 1f,
                                                    animationSpec = tween(
                                                        durationMillis = 350,
                                                        easing = CogniEasing.appleEaseOut
                                                    )
                                                )
                                                // Brief pause at destination
                                                delay(100)
                                                flyAnimation.snapTo(0f)
                                            }

                                            showAttachmentPanel = false

                                            // Submit after animation starts
                                            scope.launch {
                                                delay(80)
                                                handleSubmit()
                                            }
                                        }
                                    )
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        // Background Circle
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(sendButtonContainerColor, androidx.compose.foundation.shape.CircleShape)
                        )

                        // Flying Plane Icon with dramatic takeoff animation
                        val flightProgress = flyAnimation.value

                        // Eased curve for more natural flight path
                        val easedProgress = FastOutSlowInEasing.transform(flightProgress)

                        // Flight path: starts slow, accelerates, lifts up diagonally
                        val flyX = easedProgress * 80f  // Fly 80dp to the right
                        val flyY = -easedProgress * 40f  // Lift 40dp upward (negative = up)
                        val flyRotation = -easedProgress * 25f  // Tilt nose up as it flies
                        val flyScale = 1f - (easedProgress * 0.3f)  // Shrink slightly as it flies away
                        val flyAlpha = (1f - easedProgress * 1.2f).coerceIn(0f, 1f)  // Fade out

                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = sendButtonContentColor.copy(alpha = flyAlpha),
                            modifier = Modifier
                                .size(18.dp)
                                .graphicsLayer {
                                    translationX = flyX * density
                                    translationY = flyY * density
                                    rotationZ = flyRotation
                                    scaleX = flyScale
                                    scaleY = flyScale
                                }
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp)) // Right padding inside container
            }
        }
    }
    }
}