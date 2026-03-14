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
    onScrollToTop: () -> Unit = {}
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
                    
                    // Deep Research Button (Icon + Text)
                    if (isChatMode) {
                        val researchAccentColor = LocalAccentColor.current
                        val researchPillBackground = if (isResearchMode)
                            researchAccentColor.copy(alpha = 0.15f)
                        else
                            pillBackground
                        val researchPillBorder = if (isResearchMode)
                            researchAccentColor
                        else
                            pillBorder

                        // Deep Research Button - Standard 48dp touch target
                        Box(
                            modifier = Modifier
                                .height(48.dp)
                                .clip(RoundedCornerShape(24.dp))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onToggleResearchMode()
                                    }
                                )
                                .background(researchPillBackground)
                                .border(0.5.dp, researchPillBorder, RoundedCornerShape(24.dp))
                                .padding(horizontal = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Science,
                                    contentDescription = "Deep Research",
                                    tint = if (isResearchMode) researchAccentColor else monochromeColor,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "Deep Research",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = if (isResearchMode) researchAccentColor else monochromeColor,
                                    maxLines = 1
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.width(12.dp))

                        // Direct Image Generation Button - Standard 48dp touch target
                        val imageGenAccentColor = ComponentColors.assistantPurple
                        val imageGenPillBackground = if (isImageGenMode)
                            imageGenAccentColor.copy(alpha = 0.15f)
                        else
                            pillBackground
                        val imageGenPillBorder = if (isImageGenMode)
                            imageGenAccentColor
                        else
                            pillBorder

                        Box(
                            modifier = Modifier
                                .height(48.dp)
                                .clip(RoundedCornerShape(24.dp))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onToggleImageGenMode()
                                    }
                                )
                                .background(imageGenPillBackground)
                                .border(0.5.dp, imageGenPillBorder, RoundedCornerShape(24.dp))
                                .padding(horizontal = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = "Generate Image",
                                    tint = if (isImageGenMode) imageGenAccentColor else monochromeColor,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "Generate",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = if (isImageGenMode) imageGenAccentColor else monochromeColor,
                                    maxLines = 1
                                )
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

@Composable
private fun VoiceWaveformIcon(
    isListening: Boolean,
    modifier: Modifier = Modifier
) {
    val isDark = MaterialTheme.colorScheme.surface.luminance() <= 0.51f
    val idleColor = if (isDark) Color.White else Color.Black
    val activeColor = ComponentColors.voiceAccent

    val animatedColor by animateColorAsState(
        targetValue = if (isListening) activeColor else idleColor,
        animationSpec = tween(200),
        label = "waveformColor"
    )

    WaveformBars(
        modifier = modifier.size(24.dp),
        color = animatedColor,
        isListening = isListening
    )
}

@Composable
private fun WaveformBars(
    modifier: Modifier = Modifier,
    color: Color,
    isListening: Boolean = false,
    barCount: Int = 5
) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveform")

    val animations = (0 until barCount).map { index ->
        val duration = if (isListening) 300 + (index * 60) else 800 + (index * 100)
        val minHeight = if (isListening) 0.3f else 0.5f

        infiniteTransition.animateFloat(
            initialValue = minHeight,
            targetValue = if (isListening) 1f else 0.7f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = duration,
                    easing = FastOutSlowInEasing
                ),
                repeatMode = RepeatMode.Reverse
            ),
            label = "bar_$index"
        )
    }

    Canvas(modifier = modifier) {
        val barWidth = size.width / (barCount * 2 - 1)
        val maxHeight = size.height * 0.8f
        val centerY = size.height / 2

        animations.forEachIndexed { index, anim ->
            val barHeight = maxHeight * anim.value
            val x = barWidth / 2 + (index * barWidth * 2) - (barWidth / 2)

            drawRoundRect(
                color = color,
                topLeft = androidx.compose.ui.geometry.Offset(x, centerY - barHeight / 2),
                size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2, barWidth / 2)
            )
        }
    }
}
