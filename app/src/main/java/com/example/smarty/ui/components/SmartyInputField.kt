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
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.foundation.interaction.collectIsPressedAsState
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material.icons.filled.KeyboardDoubleArrowUp
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Psychology
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
import com.example.smarty.ui.components.AttachmentPreviewRow
import com.example.smarty.ui.animation.SmartyEasing
import com.example.smarty.ui.animation.SmartyMotion
import com.example.smarty.ui.theme.SmartyShadow
import com.example.smarty.ui.animation.shimmerEffect
import com.example.smarty.core.common.util.ContentTypeDetector
import com.example.smarty.ui.theme.*
import com.example.smarty.ui.utils.AnimationLifecycleState
import com.example.smarty.ui.utils.rememberAnimationLifecycleState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Color constants for attachment indicators - Technical Palette (Theme-neutral)
private val AttachmentRedColor = Color(0xFFEF9A9A)
private val AttachmentGreenColor = Color(0xFFA5D6A7)
private val AttachmentBlueColor = Color(0xFF90CAF9)
private val AttachmentOrangeColor = Color(0xFFFFCC80)
private val AttachmentGrayColor = Color(0xFFB0BEC5)

// Agent shimmer color - Mapped to tokens
private val AgentShimmerColor = ComponentColors.assistantPurple

// Design constants for the redesigned input block
// Design constants for the redesigned input block - Mapped to tokens
private val CIRCLE_SIZE = ComponentSpacing.inputCircleSize
private val CIRCLE_ICON_SIZE = ComponentSpacing.inputCircleIconSize
private val PILL_HEIGHT = ComponentSpacing.inputPillHeight
private val PILL_CORNER_RADIUS = ComponentSpacing.inputPillCornerRadius
private val ELEMENT_SPACING = 8.dp
private val HORIZONTAL_PADDING = 12.dp

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
    onPickResearch: () -> Unit = {},
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
    // Stop generation callback
    onStopGeneration: () -> Unit = {},
    // Clear input callback
    onClearInput: () -> Unit = {},
    // Search filter parameters (used when isSearchMode = true)
    selectedFilters: Set<AttachmentOption> = emptySet(),
    onFilterToggle: (AttachmentOption) -> Unit = {},
    onClearFilters: () -> Unit = {},
    // @Mention support (Chat mode only)
    mentionState: MentionState = MentionState(),
    onMentionSelected: (MentionSuggestion) -> Unit = {},
    // Research mode toggle
    isResearchMode: Boolean = false,
    onToggleResearchMode: () -> Unit = {},
    // Image Generation (Krea) mode toggle
    isImageGenMode: Boolean = false,
    onToggleImageGenMode: () -> Unit = {},
    // Scrolling support
    showScrollButton: Boolean = false,
    isAtLatest: Boolean = true,
    onScrollToBottom: () -> Unit = {},
    onScrollToTop: () -> Unit = {},
    // Dynamic Model Selection
    selectedModel: String = "opencode/deepseek-v4-flash-free",
    availableModels: List<Pair<String, String>> = emptyList(),
    onModelSelected: (String) -> Unit = {},
    onRefreshModels: suspend () -> List<Pair<String, String>> = { emptyList() }
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    // State
    var showAttachmentPanel by remember { mutableStateOf(false) }

    // Speech-to-Text: Managed by parent (InputStreamScreen) via the global SpeechToTextState.
    // Do NOT create a local SpeechToTextState here — it would create a second SpeechRecognizer
    // that conflicts with the parent's, causing Error 8 (Recognition service busy).

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

    // Send button enabled state
    // FIX: Use isNotEmpty() to ensure any input triggers send button
    val canSend = value.text.isNotEmpty() || attachments.isNotEmpty()

    // Placeholder based on mode
    val currentPlaceholder = when {
        isVoiceListening -> {
            stringResource(R.string.listening)
        }
        isImageGenMode -> "Describe an image for Krea AI..."
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
            visible = isChatMode && !mentionState.isActive,
            enter = slideInVertically(initialOffsetY = { 20 }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { 20 }) + fadeOut()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Icon Container with centered alignment
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Scroll Arrow
                    AnimatedVisibility(
                        visible = showScrollButton,
                        enter = fadeIn() + scaleIn(initialScale = 0.5f),
                        exit = fadeOut() + scaleOut(targetScale = 0.5f)
                    ) {
                        Surface(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                if (isAtLatest) onScrollToBottom() else onScrollToTop()
                            },
                            shape = CircleShape,
                            color = pillBackground,
                            border = BorderStroke(0.5.dp, pillBorder),
                            modifier = Modifier.requiredSize(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (isAtLatest) Icons.Default.KeyboardDoubleArrowDown else Icons.Default.KeyboardDoubleArrowUp,
                                    contentDescription = if (isAtLatest) "Scroll to oldest" else "Scroll to latest",
                                    tint = monochromeColor,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    // History Pill
                    if (showHistoryOption) {
                        val interactionSource = remember { MutableInteractionSource() }
                        val isPressedState by interactionSource.collectIsPressedAsState()
                        val scale by animateFloatAsState(
                            targetValue = if (isPressedState) 0.95f else 1f,
                            animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
                            label = "historyPillScale"
                        )
                        Surface(
                            modifier = Modifier
                                .scale(scale)
                                .requiredSize(36.dp)
                                .clickable(
                                    interactionSource = interactionSource,
                                    indication = null
                                ) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    if (isHistoryMode) onNewChat() else onOpenChatHistory()
                                },
                            shape = CircleShape,
                            color = pillBackground,
                            border = BorderStroke(0.5.dp, pillBorder)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Icon(
                                    imageVector = if (isHistoryMode) Icons.Default.Add else Icons.Default.History,
                                    contentDescription = if (isHistoryMode) stringResource(R.string.new_chat) else stringResource(R.string.history),
                                    tint = monochromeColor,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                    
                    // Deep Research Button
                    if (isChatMode) {
                        val researchAccentColor = LocalAccentColor.current
                        val researchInteractionSource = remember { MutableInteractionSource() }
                        val researchIsPressed by researchInteractionSource.collectIsPressedAsState()
                        val researchScale by animateFloatAsState(
                            targetValue = if (researchIsPressed) 0.88f else 1f,
                            animationSpec = spring(dampingRatio = 0.5f, stiffness = 600f),
                            label = "researchScale"
                        )
                        val researchBg by animateColorAsState(
                            targetValue = if (isResearchMode) researchAccentColor.copy(alpha = 0.18f) else pillBackground,
                            animationSpec = tween(200), label = "researchBg"
                        )
                        val researchBorder by animateColorAsState(
                            targetValue = if (isResearchMode) researchAccentColor else pillBorder,
                            animationSpec = tween(200), label = "researchBorder"
                        )
                        val researchIconTint by animateColorAsState(
                            targetValue = if (isResearchMode) researchAccentColor else monochromeColor,
                            animationSpec = tween(200), label = "researchIconTint"
                        )

                        Surface(
                            modifier = Modifier
                                .scale(researchScale)
                                .requiredSize(36.dp)
                                .clickable(
                                    interactionSource = researchInteractionSource,
                                    indication = null,
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onToggleResearchMode()
                                    }
                                ),
                            shape = CircleShape,
                            color = researchBg,
                            border = BorderStroke(0.5.dp, researchBorder)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Science,
                                    contentDescription = "Deep Research",
                                    tint = researchIconTint,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Generate Image Button — press scale + spinning star when active
                        val imageGenAccentColor = ComponentColors.assistantPurple
                        val imageGenInteractionSource = remember { MutableInteractionSource() }
                        val imageGenIsPressed by imageGenInteractionSource.collectIsPressedAsState()
                        val imageGenScale by animateFloatAsState(
                            targetValue = if (imageGenIsPressed) 0.88f else 1f,
                            animationSpec = spring(dampingRatio = 0.5f, stiffness = 600f),
                            label = "imageGenScale"
                        )
                        val imageGenBg by animateColorAsState(
                            targetValue = if (isImageGenMode) imageGenAccentColor.copy(alpha = 0.18f) else pillBackground,
                            animationSpec = tween(200), label = "imageGenBg"
                        )
                        val imageGenBorder by animateColorAsState(
                            targetValue = if (isImageGenMode) imageGenAccentColor else pillBorder,
                            animationSpec = tween(220), label = "imageGenBorder"
                        )
                        val imageGenIconTint by animateColorAsState(
                            targetValue = if (isImageGenMode) imageGenAccentColor else monochromeColor,
                            animationSpec = tween(200), label = "imageGenIconTint"
                        )
                        // Slow star spin when image gen mode is on
                        val infiniteTransition = rememberInfiniteTransition(label = "imageGenSpin")
                        val starRotation by infiniteTransition.animateFloat(
                            initialValue = 0f,
                            targetValue = 360f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(durationMillis = 3000, easing = LinearEasing)
                            ),
                            label = "starRotation"
                        )

                        Surface(
                            modifier = Modifier
                                .scale(imageGenScale)
                                .requiredSize(36.dp)
                                .clickable(
                                    interactionSource = imageGenInteractionSource,
                                    indication = null,
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onToggleImageGenMode()
                                    }
                                ),
                            shape = CircleShape,
                            color = imageGenBg,
                            border = BorderStroke(
                                width = if (isImageGenMode) 1.dp else 0.5.dp,
                                color = imageGenBorder
                            )
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = "Generate Image",
                                    tint = imageGenIconTint,
                                    modifier = Modifier
                                        .size(18.dp)
                                        .graphicsLayer {
                                            rotationZ = if (isImageGenMode) starRotation else 0f
                                        }
                                )
                            }
                        }

                        // Model Picker Badge/Pill — always visible in chat mode
                        var showModelMenu by remember { mutableStateOf(false) }
                        var isRefreshing by remember { mutableStateOf(false) }
                        val selectedModelLabel = availableModels.find { it.first == selectedModel }?.second
                            ?: selectedModel.substringAfterLast("/")

                        val modelInteractionSource = remember { MutableInteractionSource() }
                        val modelIsPressed by modelInteractionSource.collectIsPressedAsState()
                        val modelScale by animateFloatAsState(
                            targetValue = if (modelIsPressed) 0.92f else 1f,
                            animationSpec = spring(dampingRatio = 0.5f, stiffness = 600f),
                            label = "modelScale"
                        )

                        Box {
                            Surface(
                                modifier = Modifier
                                    .scale(modelScale)
                                    .requiredHeight(36.dp)
                                    .widthIn(min = 60.dp, max = 160.dp)
                                    .clickable(
                                        interactionSource = modelInteractionSource,
                                        indication = null,
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            scope.launch {
                                                isRefreshing = true
                                                val refreshed = onRefreshModels()
                                                isRefreshing = false
                                                if (refreshed.isNotEmpty()) {
                                                    showModelMenu = true
                                                } else {
                                                    showModelMenu = true
                                                }
                                            }
                                        }
                                    ),
                                shape = RoundedCornerShape(18.dp),
                                color = pillBackground,
                                border = BorderStroke(0.5.dp, pillBorder)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .padding(horizontal = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = selectedModelLabel,
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontFamily = MonoFont,
                                            fontWeight = FontWeight.Medium,
                                            fontSize = 11.sp
                                        ),
                                        color = monochromeColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (isRefreshing) {
                                        Spacer(Modifier.width(4.dp))
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(10.dp),
                                            strokeWidth = 1.5.dp,
                                            color = monochromeColor.copy(alpha = 0.6f)
                                        )
                                    } else {
                                        Spacer(Modifier.width(4.dp))
                                        Icon(
                                            imageVector = Icons.Default.KeyboardDoubleArrowDown,
                                            contentDescription = "Select model",
                                            tint = monochromeColor.copy(alpha = 0.5f),
                                            modifier = Modifier.size(10.dp)
                                        )
                                    }
                                }
                            }

                            val isDark = MaterialTheme.colorScheme.surface.luminance() <= 0.51f
                            val menuBackground = if (isDark) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f) else MaterialTheme.colorScheme.surface
                            val menuBorder = if (isDark) Color.White.copy(alpha = 0.2f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)

                            DropdownMenu(
                                expanded = showModelMenu,
                                onDismissRequest = { showModelMenu = false },
                                offset = DpOffset(x = 0.dp, y = 4.dp),
                                shape = RoundedCornerShape(16.dp),
                                containerColor = menuBackground,
                                border = BorderStroke(1.dp, menuBorder),
                                tonalElevation = 8.dp
                            ) {
                                Text(
                                    text = "Select AI Model",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                )

                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 8.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                                )

                                availableModels.forEach { (modelId, label) ->
                                    val isCurrent = modelId == selectedModel
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = label,
                                                fontSize = 14.sp,
                                                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                                color = if (isCurrent) LocalAccentColor.current else MaterialTheme.colorScheme.onSurface
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.AutoAwesome,
                                                contentDescription = null,
                                                tint = if (isCurrent) LocalAccentColor.current else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        },
                                        onClick = {
                                            showModelMenu = false
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onModelSelected(modelId)
                                        },
                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        }
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

        // Search filter selector (Normal mode + search active only)
        // Pill-type AttachmentTypeSelector removed — only dropdown menu in InputPill remains
        if (!isChatMode && isSearchMode) {
            Box(contentAlignment = Alignment.BottomCenter, modifier = Modifier.fillMaxWidth()) {
                SearchFilterTypeSelector(
                    visible = true,
                    selectedFilters = selectedFilters,
                    onFilterToggle = onFilterToggle,
                    onClearFilters = onClearFilters
                )
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
                verticalAlignment = Alignment.Bottom, // Anchors base of pill and voice button to bottom
                horizontalArrangement = Arrangement.spacedBy(ELEMENT_SPACING)
            ) {
                // Voice/Wave Circle (both modes — unified voice input)
                // Voice Button with animated waveform (ChatGPT-style)
                Box(
                    modifier = Modifier
                        .requiredSize(44.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            if (isVoiceListening) onStopVoiceInput() else onStartVoiceInput()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    VoiceWaveformIcon(
                        isListening = isVoiceListening,
                        isProcessing = isProcessing,
                        isAgentWorking = isAgentWorking,
                        value = value,
                        isFocused = isFocused,
                        mentionState = mentionState,
                        modifier = Modifier.size(28.dp)
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
                    isVoiceListening = isVoiceListening,
                    isAgentWorking = isAgentWorking,
                    autoSendActive = autoSendActive,
                    onStopGeneration = onStopGeneration,
                    isChatMode = isChatMode,
                    onPickFile = {
                        onPickFile()
                    },
                    onOpenCamera = {
                        onOpenCamera()
                    },
                    onPickImage = {
                        onPickImage()
                    },
                    onPickVideo = {
                        onPickVideo()
                    },
                    onPickDocument = {
                        onPickDocument()
                    },
                    onPickAudio = {
                        onPickAudio()
                    },
                    onPickResearch = {
                        onPickResearch()
                    },
                    onPickLink = {
                        onPickLink()
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
    isVoiceListening: Boolean,
    isAgentWorking: Boolean,
    autoSendActive: Boolean,
    onStopGeneration: () -> Unit,
    isChatMode: Boolean,
    onPickFile: () -> Unit = {},
    onOpenCamera: () -> Unit = {},
    onPickImage: () -> Unit = {},
    onPickVideo: () -> Unit = {},
    onPickDocument: () -> Unit = {},
    onPickAudio: () -> Unit = {},
    onPickResearch: () -> Unit = {},
    onPickLink: () -> Unit = {},
    modifier: Modifier = Modifier,
    // Attachment support
    attachments: List<Attachment> = emptyList(),
    onRemoveAttachment: (String) -> Unit = {}
) {
    // Attachment preview handled by parent SmartyInputField to avoid duplication
    // This prevents UI issues with double preview rows

    // Soft Minimalist: Determine colors based on theme
    val monochromeColor = rememberMonochromeAccent()
    val isDark = MaterialTheme.colorScheme.surface.luminance() <= 0.51f
    val accentColor = LocalAccentColor.current

    val backgroundColor = if (isDark) ComponentColors.inputPillBackgroundDark else PinkLight
    val borderColor = if (isDark) Color.White.copy(alpha = 0.15f) else PinkMedium

    // Border: Subtle normally, colored when focused
    val currentBorderColor by animateColorAsState(
        targetValue = if (isFocused) accentColor.copy(alpha = 0.5f) else borderColor,
        animationSpec = tween(200),
        label = "pillBorder"
    )

    // Elevation changes on focus
    val elevation = if (isFocused) 8.dp else 2.dp

    // Soft floating pill container - expandable height locked to bottom
    Surface(
        modifier = modifier
            .heightIn(min = PILL_HEIGHT, max = 200.dp)
            .softCardShadow(shape = RoundedCornerShape(PILL_CORNER_RADIUS), elevation = elevation),
        shape = RoundedCornerShape(PILL_CORNER_RADIUS),
        color = backgroundColor,
        border = BorderStroke(0.5.dp, currentBorderColor)
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().wrapContentHeight()
        ) {
            // Shimmer overlay for voice/agent states
            val showShimmer = autoSendActive || isVoiceListening || isAgentWorking
            if (showShimmer) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .shimmerEffect(
                            shimmerColor = LocalAccentColor.current.copy(alpha = 0.2f),
                            durationMs = if (autoSendActive) 600 else 1200
                        )
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp) 
                    .defaultMinSize(minHeight = PILL_HEIGHT),
                verticalAlignment = Alignment.Bottom 
            ) {
                // Text input area
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 12.dp) // Maintain consistent padding
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            focusRequester.requestFocus()
                        },
                    contentAlignment = Alignment.CenterStart // Use CenterStart for proper text centering
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically, // Center text vertically
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            var lineCount by remember { mutableIntStateOf(1) }

                            BasicTextField(
                                value = value,
                                onValueChange = onValueChange,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(focusRequester)
                                    .onFocusChanged { onFocusChange(it.isFocused) },
                                onTextLayout = { lineCount = it.lineCount },
                                textStyle = TextStyle(
                                    fontFamily = MonoFont,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Normal, // Changed from Black to Normal
                                    color = if (isVoiceListening) LocalAccentColor.current
                                            else if (isDark) Color.White else Color.Black
                                ),
                                cursorBrush = SolidColor(LocalAccentColor.current),
                                singleLine = false,
                                maxLines = 10,
                                decorationBox = { innerTextField ->
                                    Box {
                                        if (value.text.isEmpty()) {
                                            Text(
                                                text = placeholder,
                                                style = MaterialTheme.typography.bodyMedium.copy(
                                                    fontFamily = MonoFont,
                                                    fontSize = 15.sp,
                                                    fontWeight = FontWeight.Normal // Changed from Black to Normal
                                                ),
                                                color = if (isDark) Color.White else Color.Black
                                            )
                                        }
                                        innerTextField()
                                    }
                                }
                            )
                        }
                    }
                }

                // Action icon
                Box(
                    modifier = Modifier.padding(bottom = 4.dp) // Dock icon to bottom while keeping it centered in small state
                ) {
                    val haptic = LocalHapticFeedback.current
                    var showMenu by remember { mutableStateOf(false) }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.width(8.dp))

                        val density = androidx.compose.ui.platform.LocalDensity.current.density

                        var isPressed by remember { mutableStateOf(false) }
                        val buttonScale by animateFloatAsState(
                            targetValue = if (isPressed) 0.85f else 1f,
                            animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
                            label = "sendScale"
                        )

                        val isStopMode = isAgentWorking && isChatMode
                        val sendBtnColor = when {
                            isStopMode -> Color(0xFFE57373)
                            canSend -> LocalAccentColor.current
                            else -> if (isDark) MaterialTheme.colorScheme.surfaceVariant else PinkMedium
                        }
                        val sendIconColor = when {
                            isStopMode -> Color.White
                            canSend -> MaterialTheme.colorScheme.onPrimary
                            else -> if (isDark) Color.White.copy(alpha = 0.7f) else TextCoolGrey.copy(alpha = 0.5f)
                        }

                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .scale(buttonScale)
                                .clip(CircleShape)
                                .background(sendBtnColor)
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onPress = {
                                            isPressed = true
                                            tryAwaitRelease()
                                            isPressed = false
                                        },
                                        onTap = {
                                            when {
                                                isStopMode -> onStopGeneration()
                                                canSend -> onSend()
                                                // In chat mode: no attachment menu, just ignore tap
                                                isChatMode -> { /* no-op: dimmed send icon */ }
                                                else -> {
                                                    onAddOptionsClick()
                                                    showMenu = true
                                                }
                                            }
                                        }
                                    )
                                }
                                .padding(8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            // FIXED: Icon now matches action - no animation mismatch
                            when {
                                isStopMode -> Icon(
                                    imageVector = Icons.Default.StopCircle,
                                    contentDescription = stringResource(R.string.stop),
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                                canSend -> Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = stringResource(R.string.share),
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                                // In chat mode: show dimmed send icon (no action)
                                isChatMode -> Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Message field empty",
                                    tint = sendIconColor,
                                    modifier = Modifier.size(20.dp)
                                )
                                // Notes mode, empty field: show Add icon
                                else -> Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = stringResource(R.string.add_attachment),
                                    tint = sendIconColor,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    // Dropdown menu for + button (Notes mode only — hidden in chat mode)
                    if (!isChatMode) {
                    val menuBackground = if (isDark) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f) else MaterialTheme.colorScheme.surface
                    val menuBorder = if (isDark) Color.White.copy(alpha = 0.2f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        offset = DpOffset(x = 8.dp, y = -(PILL_HEIGHT + 16.dp)),
                        shape = RoundedCornerShape(16.dp),
                        containerColor = menuBackground,
                        border = BorderStroke(1.dp, menuBorder),
                        tonalElevation = 8.dp
                    ) {
                        Text(
                            text = stringResource(R.string.add_attachment),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 8.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                        )

                        Row(
                            modifier = Modifier.padding(8.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.photo), fontSize = 14.sp) },
                                    leadingIcon = {
                                        Box(
                                            modifier = Modifier
                                                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)  // Accessible touch target
                                                .size(32.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(accentColor.copy(alpha = 0.1f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Image,
                                                contentDescription = "Attach image",
                                                tint = accentColor,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    },
                                    onClick = { showMenu = false; onPickImage() },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.video), fontSize = 14.sp) },
                                    leadingIcon = {
                                        Box(
                                            modifier = Modifier
                                                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)  // Accessible touch target
                                                .size(32.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(com.example.smarty.ui.theme.VideoRed.copy(alpha = 0.1f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Videocam,
                                                contentDescription = "Record video",
                                                tint = com.example.smarty.ui.theme.VideoRed,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    },
                                    onClick = { showMenu = false; onPickVideo() },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.document), fontSize = 14.sp) },
                                    leadingIcon = {
                                        Box(
                                            modifier = Modifier
                                                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)  // Accessible touch target
                                                .size(32.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(com.example.smarty.ui.theme.DocumentBlue.copy(alpha = 0.1f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Description,
                                                contentDescription = "Attach document",
                                                tint = com.example.smarty.ui.theme.DocumentBlue,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    },
                                    onClick = { showMenu = false; onPickDocument() },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                )
                            }
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.audio_label), fontSize = 14.sp) },
                                    leadingIcon = {
                                        Box(
                                            modifier = Modifier
                                                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)  // Accessible touch target
                                                .size(32.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(com.example.smarty.ui.theme.AudioPink.copy(alpha = 0.1f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Audiotrack,
                                                contentDescription = "Attach audio",
                                                tint = com.example.smarty.ui.theme.AudioPink,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    },
                                    onClick = { showMenu = false; onPickAudio() },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                )
                                DropdownMenuItem(
                                    text = { Text("Deep Research", fontSize = 14.sp, fontWeight = FontWeight.SemiBold) },
                                    leadingIcon = {
                                        Box(
                                            modifier = Modifier
                                                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)  // Accessible touch target
                                                .size(32.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color(0xFF7C4DFF).copy(alpha = 0.15f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.School,
                                                contentDescription = "Deep Research - Comprehensive AI analysis",
                                                tint = Color(0xFF7C4DFF),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    },
                                    onClick = { showMenu = false; onPickResearch() },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.link), fontSize = 14.sp) },
                                    leadingIcon = {
                                        Box(
                                            modifier = Modifier
                                                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)  // Accessible touch target
                                                .size(32.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color(0xFF80DEEA).copy(alpha = 0.1f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Link,
                                                contentDescription = "Attach link",
                                                tint = Color(0xFF80DEEA),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    },
                                    onClick = { showMenu = false; onPickLink() },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.camera), fontSize = 14.sp) },
                                    leadingIcon = {
                                        Box(
                                            modifier = Modifier
                                                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)  // Accessible touch target
                                                .size(32.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.CameraAlt,
                                                contentDescription = "Open camera",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    },
                                    onClick = { showMenu = false; onOpenCamera() },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }
                    } // if (!isChatMode) — hide attachment menu in chat
                }
            }
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

enum class WaveformState {
    IDLE,       // Curious Idle (Standard idle, or when typing manually)
    GIGGLE,     // Jelly Giggle (When user answers a tool / typing after question)
    TALK,       // Active Talk (When user is using the mic)
    THINKING,   // Thinking Wave (When model is thinking)
    SUCCESS,    // Success Jump (When model thinking completes)
    SAD         // Sad Droop (Idle for 20 seconds)
}

@Composable
private fun VoiceWaveformIcon(
    isListening: Boolean,
    isProcessing: Boolean,
    isAgentWorking: Boolean,
    value: TextFieldValue,
    isFocused: Boolean,
    mentionState: MentionState,
    modifier: Modifier = Modifier
) {
    var isIdleFor20s by remember { mutableStateOf(false) }

    LaunchedEffect(isListening, isProcessing, isAgentWorking, value.text) {
        isIdleFor20s = false // Reset idle state on any action or text typing
        if (!isListening && !isProcessing && !isAgentWorking) {
            delay(20000) // Wait 20 seconds
            isIdleFor20s = true
        }
    }

    var isSuccessActive by remember { mutableStateOf(false) }
    var lastProcessing by remember { mutableStateOf(false) }

    LaunchedEffect(isProcessing, isAgentWorking) {
        val isAnyProcessing = isProcessing || isAgentWorking
        if (lastProcessing && !isAnyProcessing) {
            // Transitioned from working/thinking to completed!
            isSuccessActive = true
        }
        lastProcessing = isAnyProcessing
    }

    LaunchedEffect(isSuccessActive) {
        if (isSuccessActive) {
            delay(2500) // Show success jump for 2.5 seconds
            isSuccessActive = false
        }
    }

    var hasQuestionBeenAsked by remember { mutableStateOf(false) }

    // Set hasQuestionBeenAsked to true when thinking completes (success triggers)
    LaunchedEffect(isSuccessActive) {
        if (isSuccessActive) {
            hasQuestionBeenAsked = true
        }
    }

    // Reset hasQuestionBeenAsked when user submits or text is cleared
    LaunchedEffect(value.text) {
        if (value.text.isEmpty()) {
            hasQuestionBeenAsked = false
        }
    }

    val isUserAnsweringTool = (hasQuestionBeenAsked && value.text.isNotEmpty() && isFocused) || 
                              (value.text.startsWith("/") || value.text.startsWith("@")) ||
                              mentionState.isActive

    val state = when {
        isListening -> WaveformState.TALK
        isProcessing || isAgentWorking -> WaveformState.THINKING
        isSuccessActive -> WaveformState.SUCCESS
        isIdleFor20s -> WaveformState.SAD
        isUserAnsweringTool -> WaveformState.GIGGLE
        else -> WaveformState.IDLE
    }

    val isDark = MaterialTheme.colorScheme.surface.luminance() <= 0.51f
    val accentColor = ComponentColors.voiceAccent
    val idleColor = if (isDark) Color.White.copy(alpha = 0.7f) else Color.Black.copy(alpha = 0.7f)
    
    val targetColor = when (state) {
        WaveformState.SAD -> if (isDark) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.15f)
        WaveformState.TALK, WaveformState.THINKING, WaveformState.SUCCESS, WaveformState.GIGGLE -> accentColor
        WaveformState.IDLE -> idleColor
    }
    
    val animatedColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(320),
        label = "waveformColor"
    )

    val successAnimProgress = remember { Animatable(0f) }
    LaunchedEffect(isSuccessActive) {
        if (isSuccessActive) {
            successAnimProgress.snapTo(0f)
            successAnimProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(2500, easing = LinearEasing)
            )
        }
    }

    WaveformBars(
        modifier = modifier.size(24.dp),
        color = animatedColor,
        state = state,
        successProgress = successAnimProgress.value
    )
}

private data class BarProperties(
    val heightFraction: Float,
    val yOffsetFraction: Float,
    val widthMultiplier: Float,
    val xOffsetFraction: Float
)

private fun getBarProperties(
    state: WaveformState,
    index: Int,
    playTimeMs: Long,
    successProgress: Float
): BarProperties {
    var widthMultiplier = 1.0f
    var xOffsetFraction = 0f
    var heightFraction = 0.2f
    var yOffsetFraction = 0f

    val baseHeight = when (index) {
        0, 4 -> 0.3f
        1, 3 -> 0.8f
        else -> 0.2f
    }

    when (state) {
        WaveformState.TALK -> {
            val p = (playTimeMs % 1200) / 1200f
            val time = 2.0 * Math.PI * (p - index * 0.1f)
            heightFraction = 0.25f + 0.5f * (0.5f + 0.5f * Math.sin(time).toFloat())
            yOffsetFraction = 0f
            widthMultiplier = 1.0f
            xOffsetFraction = 0f
        }
        WaveformState.THINKING -> {
            val p = (playTimeMs % 1100) / 1100f
            val (h, y) = when (index) {
                2 -> {
                    val peak = 0.18f
                    val valH = if (p < peak) {
                        0.1f + 0.9f * (p / peak)
                    } else if (p < 0.45f) {
                        1.0f - 0.85f * ((p - peak) / (0.45f - peak))
                    } else {
                        // Smoothly transition from 0.15f back to 0.1f to prevent wrap jitter
                        val t = (p - 0.45f) / (1.0f - 0.45f)
                        0.15f + (0.1f - 0.15f) * t
                    }
                    valH to 0f
                }
                1, 3 -> {
                    val valH = if (p < 0.12f) {
                        0.2f
                    } else if (p < 0.30f) {
                        0.2f + 0.75f * ((p - 0.12f) / (0.30f - 0.12f))
                    } else if (p < 0.55f) {
                        0.95f - 0.75f * ((p - 0.30f) / (0.55f - 0.30f))
                    } else {
                        0.2f
                    }
                    val valY = if (p in 0.12f..0.55f) {
                        val t = (p - 0.12f) / (0.55f - 0.12f)
                        val displacement = -2f / 24f
                        displacement * (1f - 4f * (t - 0.5f) * (t - 0.5f))
                    } else 0f
                    valH to valY
                }
                else -> {
                    val valH = if (p < 0.24f) {
                        0.25f
                    } else if (p < 0.42f) {
                        0.25f + 0.63f * ((p - 0.24f) / (0.42f - 0.24f))
                    } else if (p < 0.68f) {
                        0.88f - 0.63f * ((p - 0.42f) / (0.68f - 0.42f))
                    } else {
                        0.25f
                    }
                    val valY = if (p in 0.24f..0.68f) {
                        val t = (p - 0.24f) / (0.68f - 0.24f)
                        val displacement = -4f / 24f
                        displacement * (1f - 4f * (t - 0.5f) * (t - 0.5f))
                    } else 0f
                    valH to valY
                }
            }
            heightFraction = h
            yOffsetFraction = y
            widthMultiplier = 1.0f
            xOffsetFraction = 0f
        }
        WaveformState.SUCCESS -> {
            val p = successProgress
            val (h, y) = when {
                p < 0.12f -> {
                    val t = p / 0.12f
                    val startH = baseHeight
                    val endH = when (index) {
                        0, 4 -> 0.08f
                        1, 3 -> 0.2f
                        else -> 0.06f
                    }
                    val valH = startH + (endH - startH) * t
                    val valY = 0.1f * t
                    valH to valY
                }
                p < 0.32f -> {
                    val t = (p - 0.12f) / 0.20f
                    val startH = when (index) {
                        0, 4 -> 0.08f
                        1, 3 -> 0.2f
                        else -> 0.06f
                    }
                    val endH = when (index) {
                        0, 4 -> 0.78f
                        1, 3 -> 1.15f
                        else -> 0.45f
                    }
                    val hVal = startH + (endH - startH) * t
                    val startY = 0.1f
                    val endY = -0.75f
                    val yVal = startY + (endY - startY) * t
                    hVal to yVal
                }
                p < 0.48f -> {
                    val t = (p - 0.32f) / 0.16f
                    val startH = when (index) {
                        0, 4 -> 0.78f
                        1, 3 -> 1.15f
                        else -> 0.45f
                    }
                    val endH = when (index) {
                        0, 4 -> 0.4f
                        1, 3 -> 0.9f
                        else -> 0.28f
                    }
                    val hVal = startH + (endH - startH) * t
                    val startY = -0.75f
                    val endY = -0.8f
                    val yVal = startY + (endY - startY) * t
                    hVal to yVal
                }
                p < 0.62f -> {
                    val t = (p - 0.48f) / 0.14f
                    val startH = when (index) {
                        0, 4 -> 0.4f
                        1, 3 -> 0.9f
                        else -> 0.28f
                    }
                    val endH = when (index) {
                        0, 4 -> 0.05f
                        1, 3 -> 0.05f
                        else -> 0.04f
                    }
                    val hVal = startH + (endH - startH) * t
                    val startY = -0.8f
                    val endY = 0.1f
                    val yVal = startY + (endY - startY) * t
                    hVal to yVal
                }
                p < 0.75f -> {
                    val t = (p - 0.62f) / 0.13f
                    val startH = when (index) {
                        0, 4 -> 0.05f
                        1, 3 -> 0.05f
                        else -> 0.04f
                    }
                    val endH = when (index) {
                        0, 4 -> 0.45f
                        1, 3 -> 0.95f
                        else -> 0.3f
                    }
                    val hVal = startH + (endH - startH) * t
                    val startY = 0.1f
                    val endY = -0.08f
                    val yVal = startY + (endY - startY) * t
                    hVal to yVal
                }
                p < 0.88f -> {
                    val t = (p - 0.75f) / 0.13f
                    val startH = when (index) {
                        0, 4 -> 0.45f
                        1, 3 -> 0.95f
                        else -> 0.3f
                    }
                    val endH = when (index) {
                        0, 4 -> 0.25f
                        1, 3 -> 0.75f
                        else -> 0.18f
                    }
                    val hVal = startH + (endH - startH) * t
                    val startY = -0.08f
                    val endY = 0.02f
                    val yVal = startY + (endY - startY) * t
                    hVal to yVal
                }
                else -> {
                    val t = minOf(1.0f, (p - 0.88f) / 0.12f)
                    val startH = when (index) {
                        0, 4 -> 0.25f
                        1, 3 -> 0.75f
                        else -> 0.18f
                    }
                    val endH = baseHeight
                    val hVal = startH + (endH - startH) * t
                    val startY = 0.02f
                    val endY = 0.0f
                    val yVal = startY + (endY - startY) * t
                    hVal to yVal
                }
            }
            heightFraction = h
            yOffsetFraction = y
            widthMultiplier = 1.0f
            xOffsetFraction = 0f
        }
        WaveformState.GIGGLE -> {
            val p = (playTimeMs % 1800) / 1800f
            // Premium smooth transition for Giggle merging/spreading
            val mergeFactor = if (p < 0.1f) {
                val t = p / 0.1f
                0.5f - 0.5f * Math.cos(t * Math.PI).toFloat()
            } else if (p < 0.85f) {
                1.0f
            } else if (p < 0.95f) {
                val t = (p - 0.85f) / 0.1f
                0.5f + 0.5f * Math.cos(t * Math.PI).toFloat()
            } else {
                0.0f
            }

            widthMultiplier = 1.0f + 1.7f * mergeFactor
            xOffsetFraction = when (index) {
                0 -> 0.3f * mergeFactor
                1 -> -1.7f * mergeFactor
                3 -> 0.0f * mergeFactor
                4 -> -2.0f * mergeFactor
                else -> 0.0f
            }

            // Beautiful sine envelope to make wiggle expand and decay cleanly with zero boundary jumps
            heightFraction = if (p in 0.1f..0.85f) {
                val envelope = Math.sin(((p - 0.1f) / 0.75f) * Math.PI).toFloat()
                val gigTime = 2.0 * Math.PI * ((p - 0.1f) / 0.75f) * 2.0
                val wiggle = 0.185f * Math.sin(gigTime - index * 0.2f).toFloat()
                baseHeight + (0.265f + wiggle) * envelope
            } else {
                baseHeight
            }
            yOffsetFraction = 0f
        }
        WaveformState.SAD -> {
            val p = (playTimeMs % 3200) / 3200f
            val sighFactor = if (p in 0.35f..0.55f) {
                val t = (p - 0.35f) / 0.2f
                Math.sin(t * Math.PI).toFloat()
            } else 0f

            heightFraction = when (index) {
                0, 4 -> 0.12f - 0.06f * sighFactor
                1, 3 -> 0.35f - 0.33f * sighFactor
                else -> 0.05f - 0.03f * sighFactor
            }

            yOffsetFraction = when (index) {
                0, 4 -> 0.075f + 0.015f * sighFactor
                1, 3 -> 0f
                else -> 0.125f + 0.02f * sighFactor
            }
            widthMultiplier = 1.0f
            xOffsetFraction = 0f
        }
        WaveformState.IDLE -> {
            val p = (playTimeMs % 3000) / 3000f

            // Premium continuous blink (closing and opening smoothly)
            val eyeHeight = if (p in 0.07f..0.12f) {
                val t = (p - 0.07f) / 0.05f
                if (t < 0.3f) {
                    val subT = t / 0.3f
                    baseHeight + (0.05f - baseHeight) * subT
                } else if (t < 0.7f) {
                    0.05f
                } else {
                    val subT = (t - 0.7f) / 0.3f
                    0.05f + (baseHeight - 0.05f) * subT
                }
            } else {
                baseHeight
            }

            val earLeftHeight = if (p in 0.0f..0.3f) {
                val t = p / 0.3f
                if (t < 0.5f) {
                    0.3f + 0.18f * (t / 0.5f)
                } else {
                    0.48f - 0.28f * ((t - 0.5f) / 0.5f)
                }
            } else if (p in 0.3f..0.4f) {
                val t = (p - 0.3f) / 0.1f
                0.2f + 0.1f * t
            } else {
                0.3f
            }

            val earRightHeight = if (p in 0.4f..0.7f) {
                val t = (p - 0.4f) / 0.3f
                if (t < 0.5f) {
                    0.3f + 0.18f * (t / 0.5f)
                } else {
                    0.48f - 0.28f * ((t - 0.5f) / 0.5f)
                }
            } else if (p in 0.7f..0.8f) {
                val t = (p - 0.7f) / 0.1f
                0.2f + 0.1f * t
            } else {
                0.3f
            }

            heightFraction = when (index) {
                0 -> earLeftHeight
                4 -> earRightHeight
                1, 3 -> eyeHeight
                else -> {
                    // Continuous nose/mouth height transition
                    if (p < 0.07f) {
                        0.2f
                    } else if (p < 0.09f) {
                        val t = (p - 0.07f) / 0.02f
                        0.2f + (0.12f - 0.2f) * t
                    } else if (p < 0.11f) {
                        0.12f
                    } else if (p < 0.20f) {
                        val t = (p - 0.11f) / 0.09f
                        0.12f + 0.2f * t
                    } else if (p < 0.35f) {
                        val t = (p - 0.20f) / 0.15f
                        0.32f + (0.2f - 0.32f) * t
                    } else {
                        0.2f
                    }
                }
            }

            // Continuous nose vertical transition
            val noseYOffset = if (p < 0.07f) {
                0f
            } else if (p < 0.09f) {
                val t = (p - 0.07f) / 0.02f
                (1.2f / 24f) * t
            } else if (p < 0.11f) {
                1.2f / 24f
            } else if (p < 0.20f) {
                val t = (p - 0.11f) / 0.09f
                (1.2f - 3.4f * t) / 24f
            } else if (p < 0.35f) {
                val t = (p - 0.20f) / 0.15f
                (-2.2f + 2.2f * t) / 24f
            } else {
                0f
            }

            yOffsetFraction = if (index == 2) noseYOffset else 0f

            val shiftFactor = when {
                p in 0.35f..0.55f -> {
                    val t = (p - 0.35f) / 0.2f
                    Math.sin(t * Math.PI).toFloat() * -1.8f / 24f
                }
                p in 0.65f..0.85f -> {
                    val t = (p - 0.65f) / 0.2f
                    Math.sin(t * Math.PI).toFloat() * 1.8f / 24f
                }
                else -> 0f
            }

            xOffsetFraction = shiftFactor
            widthMultiplier = 1.0f
        }
    }

    return BarProperties(heightFraction, yOffsetFraction, widthMultiplier, xOffsetFraction)
}

@Composable
private fun WaveformBars(
    modifier: Modifier = Modifier,
    color: Color,
    state: WaveformState,
    successProgress: Float = 0f
) {
    // Monotonic smooth timer driven by frame ticking
    var playTimeMs by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        val startTime = withFrameNanos { it } / 1_000_000
        while (true) {
            withFrameNanos { frameTimeNanos ->
                playTimeMs = (frameTimeNanos / 1_000_000) - startTime
            }
        }
    }

    // State transition variables to animate between old and new state perfectly
    var prevState by remember { mutableStateOf(state) }
    var currentState by remember { mutableStateOf(state) }
    val transitionAlpha = remember { Animatable(1f) }

    LaunchedEffect(state) {
        if (state != currentState) {
            prevState = currentState
            currentState = state
            transitionAlpha.snapTo(0f)
            transitionAlpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(400, easing = FastOutSlowInEasing) // Smooth state morphing
            )
        }
    }

    Canvas(modifier = modifier) {
        val barCount = 5
        val barWidth = size.width / 9f
        val maxHeight = size.height * 0.8f
        val centerY = size.height / 2f
        val alpha = transitionAlpha.value

        for (index in 0 until barCount) {
            val prevProps = getBarProperties(prevState, index, playTimeMs, successProgress)
            val currProps = getBarProperties(currentState, index, playTimeMs, successProgress)

            // Smooth linear interpolation (morphing) of vector values
            val heightFraction = prevProps.heightFraction + (currProps.heightFraction - prevProps.heightFraction) * alpha
            val yOffsetFraction = prevProps.yOffsetFraction + (currProps.yOffsetFraction - prevProps.yOffsetFraction) * alpha
            val widthMultiplier = prevProps.widthMultiplier + (currProps.widthMultiplier - prevProps.widthMultiplier) * alpha
            val xOffsetFraction = prevProps.xOffsetFraction + (currProps.xOffsetFraction - prevProps.xOffsetFraction) * alpha

            val baseLeft = index * barWidth * 2f
            val targetLeft = baseLeft + (xOffsetFraction * barWidth)
            val targetWidth = widthMultiplier * barWidth
            val targetHeight = heightFraction * maxHeight

            // Safeguard height to prevent capsule compression or corner inversion
            val finalHeight = maxOf(targetHeight, targetWidth)
            val targetTop = centerY + (yOffsetFraction * maxHeight) - (finalHeight / 2f)

            drawRoundRect(
                color = color,
                topLeft = androidx.compose.ui.geometry.Offset(targetLeft, targetTop),
                size = androidx.compose.ui.geometry.Size(targetWidth, finalHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(targetWidth / 2f, targetWidth / 2f)
            )
        }
    }
}

