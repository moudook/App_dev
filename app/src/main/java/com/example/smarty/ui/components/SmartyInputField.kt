package com.example.smarty.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.DpOffset
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.AutoAwesome
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
import androidx.compose.ui.graphics.luminance
import com.example.smarty.ui.theme.rememberMonochromeAccent
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.example.smarty.R
import com.example.smarty.core.domain.model.Attachment
import com.example.smarty.core.domain.model.AttachmentType
import com.example.smarty.core.domain.model.MentionState
import com.example.smarty.core.domain.model.MentionSuggestion
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.animation.SmartyEasing
import com.example.smarty.ui.animation.SmartyMotion
import com.example.smarty.ui.theme.SmartyShadow
import com.example.smarty.ui.animation.halftoneShimmer
import com.example.smarty.ui.animation.directionalShimmer
import com.example.smarty.ui.animation.ShimmerDirection
import com.example.smarty.core.common.util.ContentTypeDetector
import com.example.smarty.ui.theme.*
import com.example.smarty.ui.utils.AnimationLifecycleState
import com.example.smarty.ui.utils.rememberAnimationLifecycleState
import com.example.smarty.features.voice.rememberSpeechToText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Color constants for attachment indicators - Technical Palette (Theme-neutral)
private val AttachmentRedColor = Color(0xFFEF9A9A)
private val AttachmentGreenColor = Color(0xFFA5D6A7)
private val AttachmentBlueColor = Color(0xFF90CAF9)
private val AttachmentOrangeColor = Color(0xFFFFCC80)
private val AttachmentGrayColor = Color(0xFFB0BEC5)

// Agent shimmer color (Standardized Assistant Purple - Technical Palette)
private val AgentShimmerColor = Color(0xFFB39DDB)

// Design constants for the redesigned input block
private val CIRCLE_SIZE = 44.dp
private val CIRCLE_ICON_SIZE = 22.dp
private val PILL_HEIGHT = 52.dp // Slightly taller for better touch target/comfort
private val PILL_CORNER_RADIUS = 26.dp
private val ELEMENT_SPACING = 12.dp
private val HORIZONTAL_PADDING = 16.dp

/**
 * 
 * REDESIGNED INPUT BLOCK
 * 
 * Design Principles:
 * - Comfortable, calm, low cognitive load
 * - Intentionally minimal
 * - Clear visual hierarchy: Circles (actions) vs Pill (input)
 * - Perfect circles, horizontal pill shape
 *
 * NORMAL MODE Layout (Left to Right):
 * [Search Circle] [Input Pill  (Send Arrow)]
 *
 * CHAT MODE Layout (Left to Right):
 * [Voice Circle] [Input Pill  (Send Arrow)]
 *
 * Send Arrow appears only when: text is present OR attachment is present
 * 
 */
@Composable
fun SmartyInputField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = stringResource(R.string.add_note),
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
    isHistoryMode: Boolean = false, // New parameter
    chatPlaceholder: String = stringResource(R.string.add_note_or_ask_ai),
    isProcessing: Boolean = false,
    onOpenChatHistory: () -> Unit = {},
    showHistoryOption: Boolean = true, // New parameter to control history visibility
    onNewChat: () -> Unit = {}, // New parameter
    // AI exclusion support
    isAiExcluded: Boolean = false,
    // Search mode support
    isSearchMode: Boolean = false,
    isVoiceListening: Boolean = false,
    onToggleSearch: () -> Unit = {},
    onStartVoiceInput: () -> Unit = {},
    onStopVoiceInput: () -> Unit = {},
    onOpenCamera: () -> Unit = {},
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
    onClearFilters: () -> Unit = {},
    // @Mention support (Chat mode only)
    mentionState: MentionState = MentionState(),
    onMentionSelected: (MentionSuggestion) -> Unit = {}
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    // State
    var showAttachmentPanel by remember { mutableStateOf(false) }

    // STRICT MUTUAL EXCLUSIVITY: If Search Mode is active, Attachment Panel MUST be closed.
    // This handles cases where search is toggled externally or simply ensures consistent state.
    LaunchedEffect(isSearchMode) {
        if (isSearchMode) {
            showAttachmentPanel = false
        }
    }

    var isFocused by remember { mutableStateOf(false) }
    var showAttachmentPreview by remember { mutableStateOf(false) }
    var isVoiceFocusRequested by remember { mutableStateOf(false) }

    // Animation for send button flight
    val flyAnimation = remember { Animatable(0f) }

    // Send button enabled state
    // FIX: Use isNotEmpty() to ensure any input triggers send button
    val canSend = value.text.isNotEmpty() || attachments.isNotEmpty()

    // Placeholder based on mode
    val currentPlaceholder = when {
        isVoiceListening -> stringResource(R.string.listening)
        isSearchMode -> stringResource(R.string.find_notes)
        isChatMode -> stringResource(R.string.ask_smarty)
        else -> stringResource(R.string.add_note)
    }

    // Clear focus when submitting
    val handleSubmit: () -> Unit = {
        focusManager.clearFocus()
        onSubmit()
    }

    // Show attachment panel logic (Removed auto-show on focus as per new UI requirements)
    LaunchedEffect(isFocused, isChatMode) {
        if (!isFocused) {
            delay(150)
            if (!isFocused) showAttachmentPanel = false
        }
    }

    // Focus input field when voice listening starts
    LaunchedEffect(isVoiceListening) {
        if (isVoiceListening) {
            isVoiceFocusRequested = true
            focusRequester.requestFocus()
        } else {
            isVoiceFocusRequested = false
        }
    }

    // Reset preview state when attachments cleared
    LaunchedEffect(attachments.isEmpty()) {
        if (attachments.isEmpty()) showAttachmentPreview = false
    }

    // Auto-show attachment preview when attachments are added
    LaunchedEffect(attachments.isNotEmpty()) {
        if (attachments.isNotEmpty()) showAttachmentPreview = true
    }

    // 
    // ANIMATIONS
    // 
    
    val pillBorderColor by animateColorAsState(
        targetValue = if (isFocused) LocalAccentColor.current else Color.Transparent,
        animationSpec = tween(200),
        label = "pillBorder"
    )

    val sendButtonScale by animateFloatAsState(
        targetValue = if (canSend) 1f else 0.8f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f),
        label = "sendScale"
    )

    // 
    // UI STRUCTURE
    // 

    Column(modifier = modifier.fillMaxWidth()) {

        // Chat mode indicator pill (above input) - hidden when mention suggestions are showing
        // Styled to match input block aesthetics
        val monochromeColor = rememberMonochromeAccent()
        val isDarkForPill = MaterialTheme.colorScheme.surface.luminance() <= 0.51f
        val pillBackground = if (isDarkForPill) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f) else MaterialTheme.colorScheme.surface
        val pillBorder = if (isDarkForPill) Color.White.copy(alpha = 0.15f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)

        AnimatedVisibility(
            visible = isChatMode && !mentionState.isActive && showHistoryOption,
            enter = slideInVertically(initialOffsetY = { 20 }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { 20 }) + fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                var isPressed by remember { mutableStateOf(false) }
                val scale by animateFloatAsState(
                    targetValue = if (isPressed) 0.95f else 1f,
                    animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
                    label = "historyPillScale"
                )

                Surface(
                    modifier = Modifier
                        .scale(scale)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    isPressed = true
                                    tryAwaitRelease()
                                    isPressed = false
                                },
                                onTap = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    if (isHistoryMode) onNewChat() else onOpenChatHistory()
                                }
                            )
                        },
                    shape = RoundedCornerShape(PILL_CORNER_RADIUS),
                    color = pillBackground,
                    border = BorderStroke(0.5.dp, pillBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isHistoryMode) Icons.Default.Add else Icons.Default.History,
                            contentDescription = null,
                            tint = monochromeColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (isHistoryMode) stringResource(R.string.new_chat) else stringResource(R.string.history),
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            ),
                            color = monochromeColor
                        )
                    }
                }
            }
        }

        // AI Exclusion / Privacy indicator
        AnimatedVisibility(
            visible = isAiExcluded && (value.text.isNotBlank() || attachments.isNotEmpty()) && !isChatMode,
            enter = slideInVertically(initialOffsetY = { 40 }) + fadeIn() + scaleIn(initialScale = 0.8f),
            exit = slideOutVertically(targetOffsetY = { 20 }) + fadeOut() + scaleOut(targetScale = 0.9f)
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.inverseSurface,
                    shadowElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Security,
                            contentDescription = stringResource(R.string.private_note),
                            tint = MaterialTheme.colorScheme.inverseOnSurface,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.private_note),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.inverseOnSurface
                        )
                    }
                }
            }
        }

        // Attachment preview row
        AnimatedVisibility(
            visible = attachments.isNotEmpty() && showAttachmentPreview,
            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()
        ) {
            AttachmentPreviewRow(
                attachments = attachments,
                onRemoveAttachment = { id ->
                    onRemoveAttachment(id)
                    if (attachments.size == 1) showAttachmentPreview = false
                },
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
            )
        }

        // Attachment/Filter type selector (Normal mode only, above input)
        // Centralized UI: Same "Pill" design for both Search Filters and Attachment Types
        if (!isChatMode) {
            Box(contentAlignment = Alignment.BottomCenter, modifier = Modifier.fillMaxWidth()) {
                if (isSearchMode) {
                    SearchFilterTypeSelector(
                        visible = true,
                        selectedFilters = selectedFilters,
                        onFilterToggle = onFilterToggle,
                        onClearFilters = onClearFilters
                    )
                } else if (showAttachmentPanel) {
                     AttachmentTypeSelector(
                        visible = true,
                        onSelectImage = { 
                            showAttachmentPanel = false
                            onPickImage() 
                        },
                        onSelectVideo = { 
                            showAttachmentPanel = false
                            onPickVideo() 
                        },
                        onSelectDocument = { 
                            showAttachmentPanel = false
                            onPickDocument() 
                        },
                        onSelectAudio = { 
                            showAttachmentPanel = false
                            onPickAudio() 
                        },
                        onSelectFile = { 
                            showAttachmentPanel = false
                            onPickFile() 
                        },
                        onSelectLink = { 
                            showAttachmentPanel = false
                            onPickLink() 
                        }
                     )
                }
            }
        }


        // 
        // @MENTION AUTOCOMPLETE DROPDOWN (Chat mode only, above input)
        // 
        if (isChatMode) {
            val isDark = MaterialTheme.colorScheme.surface.luminance() <= 0.51f
            MentionDropdown(
                mentionState = mentionState,
                onSuggestionSelected = onMentionSelected,
                isDarkTheme = isDark
            )
        }

            // 
            // MAIN INPUT ROW
            // 
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = HORIZONTAL_PADDING),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ELEMENT_SPACING)
            ) {
                if (isChatMode) {
                    // 
                    // CHAT MODE: [Voice Circle] [Input Pill]
                    // 

                    // Voice Circle (Chat Mode only - triggers STT)
                    ActionCircle(
                        icon = if (isVoiceListening) Icons.Default.StopCircle else Icons.Default.Mic,
                        contentDescription = if (isVoiceListening) stringResource(R.string.stop_listening) else stringResource(R.string.voice_input),
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            if (isVoiceListening) onStopVoiceInput() else onStartVoiceInput()
                        },
                        isActive = isVoiceListening,
                        activeColor = LocalAccentColor.current
                    )
                } else {
                    // 
                    // NORMAL MODE: [Search Circle] [Input Pill]
                    // 

                    // Search Circle
                    ActionCircle(
                        icon = Icons.Default.Search, // Metaphor: Compass
                        contentDescription = stringResource(R.string.search),
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)

                            // Ensure attachment panel is closed when entering search
                            showAttachmentPanel = false

                            // Reset filters if exiting search mode
                            if (isSearchMode) {
                                onClearFilters()
                            }
                            onToggleSearch()
                        },
                        isActive = isSearchMode && !showAttachmentPanel,
                        activeColor = LocalAccentColor.current
                    )
                }

                // 
                // INPUT PILL (Both modes)
                // 
                InputPill(
                    value = value,
                    onValueChange = onValueChange,
                    placeholder = currentPlaceholder,
                    isFocused = isFocused,
                    onFocusChange = { focused ->
                        isFocused = focused
                        if (focused && !isChatMode) {
                             // Auto-show panel on focus if desired
                        }
                        if (focused && isVoiceListening && !isVoiceFocusRequested) {
                            onStopVoiceInput()
                        }
                        if (focused && isVoiceFocusRequested) {
                            isVoiceFocusRequested = false
                        }
                    },
                    focusRequester = focusRequester,
                    canSend = canSend,
                    onSend = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        scope.launch {
                            flyAnimation.snapTo(0f)
                            flyAnimation.animateTo(1f, tween(300, easing = SmartyEasing.appleEaseOut))
                            delay(50)
                            flyAnimation.snapTo(0f)
                        }
                        showAttachmentPanel = false
                        scope.launch {
                            delay(60)
                            handleSubmit()
                        }
                    },
                    onAddOptionsClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        showAttachmentPanel = true
                    },
                    flyProgress = flyAnimation.value,
                    isVoiceListening = isVoiceListening,
                    isAgentWorking = isAgentWorking,
                    autoSendActive = autoSendActive,
                    isChatMode = isChatMode,
                    onPickFile = {
                        onPickFile()
                    },
                    onOpenCamera = {
                        onOpenCamera()
                    },
                    attachments = attachments,
                    onRemoveAttachment = onRemoveAttachment,
                    // Use requiredHeight to prevent flattening by parent layout
                    modifier = Modifier.weight(1f)
                )
            }
    }
}

// 
// ACTION CIRCLE COMPONENT
// 

@Composable
private fun ActionCircle(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isActive: Boolean = false,
    activeColor: Color = LocalAccentColor.current,
    badge: Int? = null
) {
    // Soft Minimalist: Determine colors based on theme
    val monochromeColor = rememberMonochromeAccent()
    val isDark = MaterialTheme.colorScheme.surface.luminance() <= 0.51f
    val accentColor = LocalAccentColor.current

    val backgroundColor = if (isDark) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f) else MaterialTheme.colorScheme.surface
    val borderColor = if (isDark) Color.White.copy(alpha = 0.15f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)

    val iconColor by animateColorAsState(
        targetValue = if (isActive) activeColor else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(200),
        label = "circleIcon"
    )

    // Border: Subtle normally, colored when active
    val currentBorderColor by animateColorAsState(
        targetValue = if (isActive) activeColor.copy(alpha = 0.5f) else borderColor,
        animationSpec = tween(200),
        label = "circleBorder"
    )

    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.9f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
        label = "circleScale"
    )

    Box(modifier = modifier) {
        // Soft floating circle
        Surface(
            modifier = Modifier
                .requiredSize(CIRCLE_SIZE)
                .scale(scale)
                .softCardShadow(shape = CircleShape, elevation = if (isActive) 6.dp else 2.dp),
            shape = CircleShape,
            color = backgroundColor,
            border = BorderStroke(1.dp, currentBorderColor)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                isPressed = true
                                tryAwaitRelease()
                                isPressed = false
                            },
                            onTap = { onClick() }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                 Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = iconColor,
                    modifier = Modifier.requiredSize(CIRCLE_ICON_SIZE)
                )
            }
        }

        // Badge
        if (badge != null && badge > 0) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-4).dp)
                    .requiredSize(18.dp),
                shape = CircleShape,
                color = LocalAccentColor.current,
                shadowElevation = 2.dp
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = badge.toString(),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = Color.White
                    )
                }
            }
        }
    }
}

// 
// INPUT PILL COMPONENT
// 

@Composable
private fun InputPill(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    isFocused: Boolean,
    onFocusChange: (Boolean) -> Unit,
    focusRequester: FocusRequester,
    canSend: Boolean,
    onSend: () -> Unit,
    onAddOptionsClick: () -> Unit,
    flyProgress: Float,
    isVoiceListening: Boolean,
    isAgentWorking: Boolean,
    autoSendActive: Boolean,
    isChatMode: Boolean,
    onPickFile: () -> Unit = {},
    onOpenCamera: () -> Unit = {},
    modifier: Modifier = Modifier,
    // Pass attachments for inline display
    attachments: List<Attachment> = emptyList(),
    onRemoveAttachment: (String) -> Unit = {}
) {
    // Dynamic height calculation based on content
    var textLineCount by remember { mutableIntStateOf(1) }
    val hasAttachments = attachments.isNotEmpty()
    
    // ChatGPT-style: Input expands with content, max ~5 lines
    val baseHeight = 52.dp
    val expandedHeight = ((textLineCount.coerceAtMost(5)) * 24 + 16).dp
    val withAttachmentsHeight = expandedHeight + 40.dp // Extra row for attachment chips
    val dynamicHeight = if (hasAttachments) withAttachmentsHeight.coerceAtMost(180.dp) else expandedHeight.coerceIn(baseHeight, 150.dp)
    
    // Soft Minimalist: Determine colors based on theme
    val monochromeColor = rememberMonochromeAccent()
    val isDark = MaterialTheme.colorScheme.surface.luminance() <= 0.51f
    val accentColor = LocalAccentColor.current

    // ChatGPT-style colors - more subtle
    val chatBackgroundColor = if (isDark) Color(0xFF2C2C30) else Color(0xFFF5F5F5)
    val chatBorderColor = if (isFocused) accentColor.copy(alpha = 0.4f) else {
        if (isDark) Color.White.copy(alpha = 0.1f) else Color.Black.copy(alpha = 0.1f)
    }

    // ChatGPT-style floating input container
    Surface(
        modifier = modifier.height(dynamicHeight),
        shape = RoundedCornerShape(24.dp), // ChatGPT uses rounded corners
        color = chatBackgroundColor,
        border = BorderStroke(
            1.dp,
            if (isFocused) accentColor.copy(alpha = 0.5f) else chatBorderColor
        ),
        tonalElevation = if (isFocused) 4.dp else 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // Inline Attachment Chips Row (ChatGPT style - compact chips above text)
            if (hasAttachments) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    attachments.take(4).forEach { attachment ->
                        InlineAttachmentChip(
                            attachment = attachment,
                            onRemove = { onRemoveAttachment(attachment.id) }
                        )
                    }
                    if (attachments.size > 4) {
                        MoreAttachmentsChip(count = attachments.size - 4)
                    }
                }
            }
            
            // Main input row with text and action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                // Text input area
                BasicTextField(
                    value = value,
                    onValueChange = { newValue ->
                        textLineCount = newValue.text.split("\n").size.coerceAtLeast(1)
                        onValueChange(newValue)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
                        .onFocusChanged { onFocusChange(it.isFocused) },
                    onTextLayout = { textLayoutResult ->
                        textLineCount = textLayoutResult.lineCount.coerceAtLeast(1)
                    },
                    textStyle = TextStyle(
                        fontFamily = MonoFont,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Normal,
                        color = if (isDark) Color.White else Color.Black
                    ),
                    cursorBrush = SolidColor(accentColor),
                    decorationBox = { innerTextField ->
                        Box {
                            if (value.text.isEmpty()) {
                                Text(
                                    text = placeholder,
                                    style = TextStyle(
                                        fontFamily = MonoFont,
                                        fontSize = 16.sp,
                                        color = if (isDark) Color.White.copy(alpha = 0.4f) else Color.Black.copy(alpha = 0.4f)
                                    )
                                )
                            }
                            innerTextField()
                        }
                    }
                )
                
                // Action buttons (+ and send)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Plus button
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(
                                if (canSend) Color.Transparent else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            )
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = {
                                        if (canSend) {
                                            onSend()
                                        } else {
                                            onAddOptionsClick()
                                        }
                                    }
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (canSend) Icons.AutoMirrored.Filled.Send else Icons.Default.Add,
                            contentDescription = if (canSend) stringResource(R.string.share) else stringResource(R.string.add_attachment),
                            tint = if (canSend) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

// 
// INLINE ATTACHMENT CHIPS (ChatGPT-style)
// 

@Composable
private fun InlineAttachmentChip(
    attachment: Attachment,
    onRemove: () -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }
    var showRemove by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "chipScale"
    )
    
    val attachmentType = attachment.getAttachmentType()
    val isImage = attachmentType == AttachmentType.IMAGE || attachmentType == AttachmentType.VIDEO
    val isDark = MaterialTheme.colorScheme.surface.luminance() <= 0.51f
    val accentColor = LocalAccentColor.current
    
    Surface(
        modifier = Modifier
            .scale(scale)
            .height(28.dp)
            .widthIn(max = 110.dp),
        shape = RoundedCornerShape(14.dp),
        color = if (isDark) Color(0xFF3A3A40) else Color(0xFFE8E8ED),
        onClick = { onRemove() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (isImage) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(attachment.uri)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(16.dp)
                        .clip(RoundedCornerShape(3.dp))
                )
            } else {
                Icon(
                    imageVector = when (attachmentType) {
                        AttachmentType.AUDIO -> Icons.Default.Audiotrack
                        AttachmentType.DOCUMENT -> Icons.Default.Description
                        AttachmentType.FILE -> Icons.Default.AttachFile
                        else -> Icons.Default.InsertDriveFile
                    },
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(12.dp)
                )
            }
            
            Text(
                text = attachment.fileName.take(10) + if (attachment.fileName.length > 10) "..." else "",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = if (isDark) Color.White else Color.Black,
                maxLines = 1
            )
            
            // Compact remove X
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.remove_attachment),
                tint = if (isDark) Color.White.copy(alpha = 0.6f) else Color.Black.copy(alpha = 0.5f),
                modifier = Modifier
                    .size(12.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { onRemove() })
                    }
            )
        }
    }
}

@Composable
private fun MoreAttachmentsChip(count: Int) {
    val isDark = MaterialTheme.colorScheme.surface.luminance() <= 0.51f
    
    Surface(
        modifier = Modifier.height(28.dp),
        shape = RoundedCornerShape(14.dp),
        color = if (isDark) Color(0xFF3A3A40) else Color(0xFFE8E8ED)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "+$count",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = if (isDark) Color.White.copy(alpha = 0.7f) else Color.Black.copy(alpha = 0.5f)
            )
        }
    }
}
