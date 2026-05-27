package com.example.smarty.ui.components // S-Tier Orchestration Integrated

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
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
import androidx.compose.animation.SizeTransform
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
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Adjust
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowDown
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Psychology
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
import com.example.smarty.core.common.util.ContentTypeDetector
import com.example.smarty.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    // Ask Question support
    pendingQuestions: List<com.example.smarty.core.domain.model.ClarificationRequest> = emptyList(),
    onQuestionAnswered: (String) -> Unit = {},
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
    // Do NOT create a local SpeechToTextState here â€” it would create a second SpeechRecognizer
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
        val pillBackground = if (isDarkForPill) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
        val pillBorder = if (isDarkForPill) Color.White.copy(alpha = 0.15f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
        // (Action row removed â€” all controls now live inside the unified InputPill)     }

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
                            imageVector = Icons.Rounded.Security,
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
        // Pill-type AttachmentTypeSelector removed â€” only dropdown menu in InputPill remains
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

        Box(contentAlignment = Alignment.BottomCenter, modifier = Modifier.fillMaxWidth()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // FLOATING SCROLL BUTTON
                AnimatedVisibility(
                    visible = showScrollButton,
                    enter = scaleIn() + fadeIn(),
                    exit = scaleOut() + fadeOut(),
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    val isDark = MaterialTheme.colorScheme.surface.luminance() <= 0.51f
                    val bgColor = if (isDark) Color(0xFF333333) else Color.White
                    val borderColor = if (isDark) Color.White.copy(0.1f) else Color.Black.copy(0.1f)
                    
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(bgColor)
                            .border(1.dp, borderColor, CircleShape)
                            .clickable {
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                onScrollToBottom()
                            }
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.KeyboardDoubleArrowDown,
                            contentDescription = "Scroll to latest",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // 
                // UNIFIED INPUT PILL â€” All controls fused inside
                // 
                InputPill(
                value = value,
                onValueChange = onValueChange,
                placeholder = currentPlaceholder,
                isFocused = isFocused,
                onFocusChange = { focused ->
                    isFocused = focused
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
                isVoiceListening = isVoiceListening,
                onStartVoiceInput = onStartVoiceInput,
                onStopVoiceInput = onStopVoiceInput,
                isProcessing = isProcessing,
                isAgentWorking = isAgentWorking,
                autoSendActive = autoSendActive,
                onStopGeneration = onStopGeneration,
                isChatMode = isChatMode,
                pendingQuestions = pendingQuestions,
                onQuestionAnswered = onQuestionAnswered,
                mentionState = mentionState,
                onPickFile = onPickFile,
                onOpenCamera = onOpenCamera,
                onPickImage = onPickImage,
                onPickVideo = onPickVideo,
                onPickDocument = onPickDocument,
                onPickAudio = onPickAudio,
                onPickResearch = onPickResearch,
                onPickLink = onPickLink,
                attachments = attachments,
                onRemoveAttachment = onRemoveAttachment,
                // Pass action-bar controls (now fused inside pill)
                showHistoryOption = showHistoryOption,
                isHistoryMode = isHistoryMode,
                onOpenChatHistory = onOpenChatHistory,
                onNewChat = onNewChat,
                isImageGenMode = isImageGenMode,
                onToggleImageGenMode = onToggleImageGenMode,
                selectedModel = selectedModel,
                availableModels = availableModels,
                onModelSelected = onModelSelected,
                onRefreshModels = onRefreshModels,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = HORIZONTAL_PADDING)
            )
            }
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

    val backgroundColor = if (isDark) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
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
                .graphicsLayer { // <-- Forces GPU rendering for parallel scaling!
                    scaleX = scale
                    scaleY = scale
                    clip = true
                    shape = CircleShape
                }
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

private enum class InputState {
    Collapsed,
    Expanded,
    AskTool
} 

@OptIn(ExperimentalLayoutApi::class)
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
    isVoiceListening: Boolean,
    onStartVoiceInput: () -> Unit,
    onStopVoiceInput: () -> Unit,
    isProcessing: Boolean,
    isAgentWorking: Boolean,
    autoSendActive: Boolean,
    onStopGeneration: () -> Unit,
    isChatMode: Boolean,
    pendingQuestions: List<com.example.smarty.core.domain.model.ClarificationRequest>,
    onQuestionAnswered: (String) -> Unit = {},
    mentionState: MentionState,
    onPickFile: () -> Unit = {},
    onOpenCamera: () -> Unit = {},
    onPickImage: () -> Unit = {},
    onPickVideo: () -> Unit = {},
    onPickDocument: () -> Unit = {},
    onPickAudio: () -> Unit = {},
    onPickResearch: () -> Unit = {},
    onPickLink: () -> Unit = {},
    attachments: List<Attachment> = emptyList(),
    onRemoveAttachment: (String) -> Unit = {},
    // Fused action-bar controls (chat mode only)
    showHistoryOption: Boolean = false,
    isHistoryMode: Boolean = false,
    onOpenChatHistory: () -> Unit = {},
    onNewChat: () -> Unit = {},
    isImageGenMode: Boolean = false,
    onToggleImageGenMode: () -> Unit = {},
    selectedModel: String = "",
    availableModels: List<Pair<String, String>> = emptyList(),
    onModelSelected: (String) -> Unit = {},
    onRefreshModels: suspend () -> List<Pair<String, String>> = { emptyList() },
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val isDark = MaterialTheme.colorScheme.surface.luminance() <= 0.51f
    val accentColor = LocalAccentColor.current
    val monochromeColor = rememberMonochromeAccent()

    // Whether the pill is "expanded" â€” focused or has text (prototype behavior)
    val isExpanded = isFocused || value.text.isNotEmpty() || attachments.isNotEmpty()
    val isAskingQuestion = pendingQuestions.isNotEmpty()
    val isStopMode = isAgentWorking && isChatMode

    // Ask-tool state
    var currentQuestionIndex by remember { mutableIntStateOf(0) }
    val answers = remember { mutableStateListOf<String>() }
    var customAnswerText by remember { mutableStateOf("") }
    var isEditingCustomAnswer by remember { mutableStateOf(false) }

    // Notes-mode attachment tray
    var showTray by remember { mutableStateOf(false) }

    // Model dropdown state (chat mode, lives in expanded toolbar)
    var showModelMenu by remember { mutableStateOf(false) }
    var isRefreshingModels by remember { mutableStateOf(false) }
    val selectedModelLabel = availableModels.find { it.first == selectedModel }?.second
        ?: selectedModel.substringAfterLast("/")

    // --- Style tokens ---
    val gradientColors = if (isDark) listOf(Color(0xFF2A2A2A), Color(0xFF1E1E1E))
                         else listOf(Color(0xFFF4F4F4), Color(0xFFFFFFFF))
    val innerStrokeColor = if (isDark) Color.White.copy(alpha = 0.05f) else Color.White
    val outerStrokeColor = if (isDark) Color.Black.copy(alpha = 0.5f) else Color(0xFFE7E7E7)
    val textPrimaryColor = if (isDark) Color.White else Color(0xFF1D1D1F)
    val textSecondaryColor = if (isDark) Color.White.copy(alpha = 0.5f) else Color(0xFF1D1D1F).copy(alpha = 0.45f)
    val dividerColor = if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.07f)
    val toolbarBgColor = if (isDark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.03f)
    val chipBgColor = if (isDark) Color.White.copy(alpha = 0.1f) else Color(0xFFE8E8ED)
    val chipBorderColor = if (isDark) Color.White.copy(alpha = 0.12f) else Color(0xFFD0D0D8)

    val askCorner = 24.dp
    val normalCorner = PILL_CORNER_RADIUS
    
    val inputState = when {
        isAskingQuestion -> InputState.AskTool
        isExpanded -> InputState.Expanded
        else -> InputState.Collapsed
    }

    val transition = updateTransition(targetState = inputState, label = "InputTransition")

    val targetCorner by transition.animateDp(
        transitionSpec = { spring(dampingRatio = 0.7f, stiffness = 400f) },
        label = "CornerRadius"
    ) { state ->
        when (state) {
            InputState.AskTool -> askCorner
            else -> normalCorner
        }
    }

    val elevation by transition.animateDp(
        transitionSpec = { spring(dampingRatio = 0.8f, stiffness = 300f) },
        label = "Elevation"
    ) { state ->
        when (state) {
            InputState.AskTool, InputState.Expanded -> 8.dp
            else -> 4.dp
        }
    }

    // ImageGen star rotation
    val infiniteTransition = rememberInfiniteTransition(label = "starSpin")
    val starRotation by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(3000, easing = LinearEasing)),
        label = "starRotation"
    )

    val handleAskSubmit = { answer: String ->
        answers.add(answer)
        customAnswerText = ""
        isEditingCustomAnswer = false
        if (currentQuestionIndex < pendingQuestions.size - 1) {
            currentQuestionIndex++
        } else {
            val finalResponse = answers.joinToString("\n") { it }
            onQuestionAnswered(finalResponse)
            currentQuestionIndex = 0
            answers.clear()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .graphicsLayer { shadowElevation = elevation.toPx(); shape = RoundedCornerShape(targetCorner.toPx()); clip = true; ambientShadowColor = Color.Black.copy(alpha = 0.12f); spotShadowColor = Color.Black.copy(alpha = 0.12f) }.border(1.dp, outerStrokeColor, RoundedCornerShape(targetCorner)).padding(1.dp).border(2.dp, innerStrokeColor, RoundedCornerShape(targetCorner - 1.dp))
            .background(Brush.verticalGradient(gradientColors))
            .animateContentSize(animationSpec = spring(dampingRatio = 0.75f, stiffness = 350f))
    ) {
        // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        // ASK TOOL UI â€” expands from top, matches pill energy
        // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        AnimatedVisibility(
            visible = isAskingQuestion,
            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()
        ) {
            val currentRequest = pendingQuestions.getOrNull(currentQuestionIndex)
            if (currentRequest != null) {
                Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp, start = 16.dp, end = 16.dp, bottom = 4.dp)) {

                    // Header row: question label + pagination + dismiss
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Small "QUESTION" label
                        Text(
                            text = if (pendingQuestions.size > 1) "QUESTION ${currentQuestionIndex + 1}/${pendingQuestions.size}" else "QUESTION",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = MonoFont,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 1.2.sp
                            ),
                            color = accentColor.copy(alpha = 0.9f),
                            fontSize = 10.sp
                        )
                        // X dismiss
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(if (isDark) Color.White.copy(alpha = 0.1f) else Color.Black.copy(alpha = 0.07f))
                                .clickable { onQuestionAnswered("") }
                        ) {
                            Icon(Icons.Rounded.Close, contentDescription = "Dismiss", tint = textSecondaryColor, modifier = Modifier.size(12.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Question text
                    Text(
                        text = currentRequest.question,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium, fontSize = 15.sp),
                        color = textPrimaryColor,
                        lineHeight = 22.sp
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Options list â€” pill-shaped chips matching the pill energy
                    currentRequest.options.forEachIndexed { index, option ->
                        val optionBg by animateColorAsState(
                            targetValue = chipBgColor,
                            animationSpec = tween(180), label = "optBg$index"
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .border(1.dp, chipBorderColor, RoundedCornerShape(14.dp))
                                .background(optionBg)
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    handleAskSubmit(option)
                                }
                                .padding(horizontal = 14.dp, vertical = 11.dp)
                        ) {
                            // Number badge
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(22.dp)
                                    .background(
                                        if (isDark) accentColor.copy(alpha = 0.25f) else accentColor.copy(alpha = 0.15f),
                                        RoundedCornerShape(6.dp)
                                    )
                            ) {
                                Text(
                                    text = "${index + 1}",
                                    color = accentColor,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 11.sp
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = option,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Normal, fontSize = 14.sp),
                                color = textPrimaryColor,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    // "Something else" custom input row
                    if (currentRequest.allowCustomInput) {
                        if (isEditingCustomAnswer) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .border(1.dp, accentColor.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                                    .background(if (isDark) accentColor.copy(alpha = 0.08f) else accentColor.copy(alpha = 0.05f))
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                BasicTextField(
                                    value = customAnswerText,
                                    onValueChange = { customAnswerText = it },
                                    modifier = Modifier.weight(1f),
                                    textStyle = TextStyle(
                                        color = textPrimaryColor,
                                        fontSize = 14.sp,
                                        fontFamily = MonoFont
                                    ),
                                    singleLine = true,
                                    cursorBrush = SolidColor(accentColor),
                                    decorationBox = { inner ->
                                        Box(contentAlignment = Alignment.CenterStart) {
                                            if (customAnswerText.isEmpty()) Text("Type your answerâ€¦", color = textSecondaryColor, fontSize = 14.sp)
                                            inner()
                                        }
                                    }
                                )
                                Spacer(Modifier.width(8.dp))
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(CircleShape)
                                        .background(if (customAnswerText.isNotBlank()) accentColor else accentColor.copy(alpha = 0.3f))
                                        .clickable { if (customAnswerText.isNotBlank()) handleAskSubmit(customAnswerText) }
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Color.White, modifier = Modifier.size(14.dp))
                                }
                            }
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .border(1.dp, chipBorderColor.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                                    .clickable { isEditingCustomAnswer = true }
                                    .padding(horizontal = 14.dp, vertical = 10.dp)
                            ) {
                                Icon(Icons.Rounded.Edit, contentDescription = null, tint = textSecondaryColor, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = "Something elseâ€¦",
                                    color = textSecondaryColor,
                                    fontSize = 14.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "Skip",
                                    color = accentColor.copy(alpha = 0.7f),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.clickable { onQuestionAnswered("") }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            }
        }

        // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        // NORMAL INPUT â€” hidden when asking question
        // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        AnimatedVisibility(
            visible = !isAskingQuestion,
            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()
        ) {
            Column {
                // â”€â”€ TOP ROW: [Waveform] [TextField] [Mic] â”€â”€â”€â”€â”€â”€â”€â”€â”€
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = PILL_HEIGHT)
                        .padding(start = 12.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Center â€” TextField (takes all available space)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { focusRequester.requestFocus() },
                        contentAlignment = Alignment.CenterStart
                    ) {
                        BasicTextField(
                            value = value,
                            onValueChange = onValueChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                                .onFocusChanged { onFocusChange(it.isFocused) },
                            textStyle = TextStyle(
                                fontFamily = MonoFont,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Normal,
                                color = if (isVoiceListening) accentColor else textPrimaryColor
                            ),
                            cursorBrush = SolidColor(accentColor),
                            maxLines = 8,
                            decorationBox = { innerTextField ->
                                Box {
                                    if (value.text.isEmpty()) {
                                        Text(
                                            text = placeholder,
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontFamily = MonoFont,
                                                fontSize = 15.sp
                                            ),
                                            color = textSecondaryColor
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )
                    }

                    Spacer(Modifier.width(4.dp))

                    val isStopBtn = isStopMode
                    val isAddBtn = !isChatMode && !canSend && !isStopMode
                    val actionBtnVisible = canSend || isStopBtn || isAddBtn

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        // 1. Voice Mic (Always visible in chat mode, except when generating)
                        if (isChatMode && !isStopMode) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .padding(end = if (actionBtnVisible) 4.dp else 0.dp)
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        if (isVoiceListening) onStopVoiceInput() else onStartVoiceInput()
                                    }
                            ) {
                                VoiceWaveformIcon(
                                    isListening = isVoiceListening,
                                    isProcessing = isProcessing,
                                    isAgentWorking = isAgentWorking,
                                    value = value,
                                    isFocused = isFocused,
                                    mentionState = mentionState,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        // 2. Action Button (Send / Stop / Add)
                        AnimatedVisibility(
                            visible = actionBtnVisible,
                            enter = scaleIn(initialScale = 0.6f, animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f)) + fadeIn(animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f)),
                            exit = scaleOut(targetScale = 0.6f, animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f)) + fadeOut(animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f))
                        ) {
                            val btnColor = when {
                                isStopBtn -> com.example.smarty.ui.theme.SemanticColors.error
                                isAddBtn -> if (isDark) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.09f)
                                else -> if (isDark) Color.White else Color.Black
                            }
                            
                            val btnState = when {
                                isStopBtn -> "stop"
                                canSend -> "send"
                                else -> "add"
                            }
                            
                            AnimatedContent(
                                targetState = btnState,
                                transitionSpec = {
                                    (scaleIn(initialScale = 0.6f, animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f)) + fadeIn(animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f))) togetherWith 
                                    (scaleOut(targetScale = 0.6f, animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f)) + fadeOut(animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f))) using SizeTransform { _, _ -> 
                                        spring(dampingRatio = 0.7f, stiffness = 400f)
                                    }
                                },
                                label = "actionBtn"
                            ) { state ->
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(38.dp)
                                        .softCardShadow(elevation = if (state == "add") 0.dp else 4.dp, shape = CircleShape)
                                        .clip(CircleShape)
                                        .background(btnColor)
                                        .clickable {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            when (state) {
                                                "stop" -> onStopGeneration()
                                                "send" -> onSend()
                                                "add" -> showTray = !showTray
                                            }
                                        }
                                ) {
                                    when (state) {
                                        "stop" -> Icon(Icons.Rounded.StopCircle, "Stop", tint = Color.White, modifier = Modifier.size(24.dp))
                                        "send" -> Icon(Icons.Rounded.ArrowUpward, "Send", tint = if (isDark) Color.Black else Color.White, modifier = Modifier.size(20.dp))
                                        "add" -> Icon(Icons.Rounded.Add, "Add", tint = textSecondaryColor, modifier = Modifier.size(24.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                // â”€â”€ CHAT MODE EXPANDED TOOLBAR â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                // Only visible in chat mode when expanded, hidden in notes mode
                AnimatedVisibility(
                    visible = isChatMode && isExpanded,
                    enter = expandVertically(expandFrom = Alignment.Top, animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f)) + fadeIn(animationSpec = tween(180)),
                    exit = shrinkVertically(shrinkTowards = Alignment.Top, animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f)) + fadeOut(animationSpec = tween(150))
                ) {
                    Column {
                        HorizontalDivider(color = dividerColor, thickness = 0.5.dp)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(toolbarBgColor)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // Model selector pill (LEFT)
                            Box {
                                val displayLabel = selectedModelLabel.replace(Regex("(?i)\\s*free\\b"), "").trim()
                                Surface(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        scope.launch {
                                            isRefreshingModels = true
                                            onRefreshModels()
                                            isRefreshingModels = false
                                            showModelMenu = true
                                        }
                                    },
                                    shape = RoundedCornerShape(percent = 50),
                                    color = if (isDark) Color.White.copy(alpha = 0.1f) else Color.Black.copy(alpha = 0.05f),
                                    modifier = Modifier.height(32.dp).widthIn(min = 70.dp, max = 150.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxHeight().padding(horizontal = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.RadioButtonChecked,
                                            contentDescription = null,
                                            tint = if (isDark) Color.White else Color.Black,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = displayLabel,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontFamily = MonoFont,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 12.sp
                                            ),
                                            color = if (isDark) Color.White else Color.Black,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        if (isRefreshingModels) {
                                            CircularProgressIndicator(Modifier.size(8.dp), strokeWidth = 1.dp, color = textSecondaryColor)
                                        } else {
                                            Icon(Icons.Rounded.KeyboardArrowDown, null, tint = textSecondaryColor.copy(alpha = 0.6f), modifier = Modifier.size(14.dp))
                                        }
                                    }
                                }

                                val menuBg = if (isDark) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
                                val menuBorder = if (isDark) Color.White.copy(0.2f) else MaterialTheme.colorScheme.outlineVariant.copy(0.4f)
                                DropdownMenu(
                                    expanded = showModelMenu,
                                    onDismissRequest = { showModelMenu = false },
                                    offset = DpOffset(0.dp, 4.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    containerColor = menuBg,
                                    border = BorderStroke(1.dp, menuBorder),
                                    tonalElevation = 8.dp
                                ) {
                                    Text("Select AI Model", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                                    HorizontalDivider(modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(0.3f))
                                    for ((modelId, label) in availableModels) {
                                        val cleanLabel = label.replace(Regex("(?i)\\s*free\\b"), "").trim()
                                        val isCurrent = modelId == selectedModel
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 8.dp, vertical = 2.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(if (isCurrent) accentColor.copy(alpha = 0.1f) else Color.Transparent)
                                                .clickable {
                                                    showModelMenu = false
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    onModelSelected(modelId)
                                                }
                                                .padding(horizontal = 12.dp, vertical = 10.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (isCurrent) Icons.Rounded.CheckCircle else Icons.Rounded.Adjust,
                                                contentDescription = null,
                                                tint = if (isCurrent) accentColor else MaterialTheme.colorScheme.onSurface.copy(0.4f),
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Text(
                                                text = cleanLabel,
                                                fontSize = 14.sp,
                                                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium,
                                                color = if (isCurrent) accentColor else MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                            }

                            // Spacer to push everything else to the right
                            Spacer(modifier = Modifier.weight(1f))

                            // History / New Chat (RIGHT)
                            if (showHistoryOption) {
                                ToolbarChip(onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    if (isHistoryMode) onNewChat() else onOpenChatHistory()
                                }) {
                                    Icon(
                                        imageVector = if (isHistoryMode) Icons.Rounded.Add else Icons.Rounded.History,
                                        contentDescription = if (isHistoryMode) "New chat" else "History",
                                        tint = textSecondaryColor,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }

                            // ImageGen toggle (RIGHT)
                            val imageGenColor by animateColorAsState(
                                targetValue = if (isImageGenMode) ComponentColors.assistantPurple else textSecondaryColor,
                                animationSpec = tween(200), label = "imgGenTint"
                            )
                            val imageGenBg by animateColorAsState(
                                targetValue = if (isImageGenMode) ComponentColors.assistantPurple.copy(alpha = 0.15f) else Color.Transparent,
                                animationSpec = tween(200), label = "imgGenBg"
                            )
                            ToolbarChip(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onToggleImageGenMode()
                                },
                                bgColor = imageGenBg
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Image, // Changed to Image icon as requested
                                    contentDescription = "Image Gen",
                                    tint = imageGenColor,
                                    modifier = Modifier
                                        .size(24.dp)
                                )
                            }
                            
                        }
                    }
                }

                // â”€â”€ NOTES MODE: attachment tray (expanded when + tapped) â”€
                // Only shown in notes mode (not chat)
                AnimatedVisibility(
                    visible = !isChatMode && showTray,
                    enter = expandVertically(expandFrom = Alignment.Top, animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f)) + fadeIn(animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f)),
                    exit = shrinkVertically(shrinkTowards = Alignment.Top, animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f)) + fadeOut(animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f))
                ) {
                    Column {
                        HorizontalDivider(color = dividerColor, thickness = 0.5.dp)
                        FlowRow(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            val defaultIconColor = if (isDark) Color.White else Color.Black
                            val items = listOf(
                                Pair(Icons.Rounded.Image, "Photo") to (defaultIconColor to { showTray = false; onPickImage() }),
                                Pair(Icons.Rounded.Videocam, "Video") to (Color(0xFFE57373) to { showTray = false; onPickVideo() }),
                                Pair(Icons.Rounded.Description, "Doc") to (Color(0xFF64B5F6) to { showTray = false; onPickDocument() }),
                                Pair(Icons.Rounded.MusicNote, "Audio") to (Color(0xFFF06292) to { showTray = false; onPickAudio() }),
                                Pair(Icons.Rounded.School, "Research") to (Color(0xFF9575CD) to { showTray = false; onPickResearch() }),
                                Pair(Icons.Rounded.Link, "Link") to (Color(0xFF4DD0E1) to { showTray = false; onPickLink() }),
                                Pair(Icons.Rounded.CameraAlt, "Camera") to (defaultIconColor to { showTray = false; onOpenCamera() })
                            )
                            
                            val AppleEasing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
                            
                            items.forEachIndexed { index, pair ->
                                val icon = pair.first.first
                                val label = pair.first.second
                                val color = pair.second.first
                                val onClick = pair.second.second
                                Box(
                                    modifier = Modifier.animateEnterExit(
                                        enter = fadeIn(animationSpec = tween(durationMillis = 350, delayMillis = index * 30, easing = AppleEasing)) + scaleIn(initialScale = 0.85f, animationSpec = spring(dampingRatio = 0.65f, stiffness = 350f)),
                                        exit = fadeOut(animationSpec = tween(100))
                                    )
                                ) {
                                    AttachmentTrayChip(icon, label, color, onClick)
                                }
                            }
                        }
                    }
                }


            }
        }
    }
}

/** Small square/rounded chip used inside the expanded toolbar */
@Composable
private fun ToolbarChip(
    onClick: () -> Unit,
    bgColor: Color = Color.Transparent,
    content: @Composable () -> Unit
) {
    val isDark = MaterialTheme.colorScheme.surface.luminance() <= 0.51f
    val hoverBg = if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.05f)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .clickable { onClick() }
    ) {
        content()
    }
}



@Composable
private fun AttachmentTrayChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    color: Color,
    onClick: () -> Unit
) {
    val isDark = MaterialTheme.colorScheme.surface.luminance() <= 0.51f
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (isDark) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.03f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.2f)),
        modifier = Modifier.wrapContentWidth().height(42.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(imageVector = icon, contentDescription = label, tint = color, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = label, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium), color = if (isDark) Color.White.copy(alpha = 0.9f) else Color.Black.copy(alpha = 0.8f))
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
    TALK,       // Active Talk (When user is using the mic)
    THINKING,   // Thinking Wave (When model is thinking)
    SUCCESS     // Success Jump (When model thinking completes)
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
    // Detect manual typing (writing) and compute smooth typingProgress first (declaring before reference)
    var isTyping by remember { mutableStateOf(false) }
    var lastText by remember { mutableStateOf(value.text) }
 
    LaunchedEffect(value.text, isFocused) {
        if (isFocused) {
            if (value.text != lastText) {
                isTyping = true
                lastText = value.text
                delay(1500) // Keep typing state for 1.5 seconds after last change
                isTyping = false
            }
        } else {
            isTyping = false
            lastText = value.text
        }
    }

var isSuccessActive by remember { mutableStateOf(false) }
    var lastProcessing by remember { mutableStateOf(false) }
 
    LaunchedEffect(isProcessing, isAgentWorking) {
        val isAnyProcessing = isProcessing || isAgentWorking
        if (lastProcessing && !isAnyProcessing) {
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
 
    LaunchedEffect(isSuccessActive) {
        if (isSuccessActive) {
            hasQuestionBeenAsked = true
        }
    }
 
    LaunchedEffect(value.text) {
        if (value.text.isEmpty()) {
            hasQuestionBeenAsked = false
        }
    }
 
    val typingProgress by animateFloatAsState(
        targetValue = if (isTyping) 1f else 0f,
        animationSpec = tween(400, easing = FastOutSlowInEasing),
        label = "typingProgress"
    )
 
    val isUserAnsweringTool = (hasQuestionBeenAsked && value.text.isNotEmpty() && isFocused) || 
                              (value.text.startsWith("/") || value.text.startsWith("@")) ||
                              mentionState.isActive
 
    val state = when {
        isListening -> WaveformState.TALK
        isProcessing || isAgentWorking -> WaveformState.THINKING
        isSuccessActive -> WaveformState.SUCCESS
        isTyping || isUserAnsweringTool -> WaveformState.TALK
        else -> WaveformState.IDLE
    }

    val isDark = MaterialTheme.colorScheme.surface.luminance() <= 0.51f
    val accentColor = ComponentColors.voiceAccent
    val idleColor = if (isDark) Color.White.copy(alpha = 0.7f) else Color.Black.copy(alpha = 0.7f)
    
    val targetColor = when (state) {
        WaveformState.TALK, WaveformState.THINKING, WaveformState.SUCCESS -> accentColor
        WaveformState.IDLE -> idleColor
    }

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
        targetColor = targetColor,
        state = state,
        successProgress = successAnimProgress.value,
        typingProgress = typingProgress
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
    successProgress: Float,
    typingProgress: Float
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
            yOffsetFraction = -0.03f * Math.cos(time).toFloat() // subtle vertical bounce
            widthMultiplier = 1.0f + 0.05f * Math.sin(time).toFloat() // subtle squash & stretch
            xOffsetFraction = 0.04f * Math.sin(time).toFloat() // subtle head sway
        }
        WaveformState.THINKING -> {
            val waveSpeed = 0.006f
            val phaseOffset = index * 0.5f
            val wave = Math.sin((playTimeMs * waveSpeed - phaseOffset).toDouble()).toFloat()
            heightFraction = 0.35f + 0.35f * wave
            yOffsetFraction = -0.1f * wave // subtle vertical bobbing
            widthMultiplier = 1.0f + 0.1f * Math.cos((playTimeMs * waveSpeed - phaseOffset).toDouble()).toFloat() // organic squash/stretch
            xOffsetFraction = 0.03f * wave // organic head sway
        }
        WaveformState.SUCCESS -> {
            val p = successProgress
            val smoothStep = { t: Float -> t * t * (3f - 2f * t) }
            val (h, y) = when {
                p < 0.12f -> {
                    val t = p / 0.12f
                    val st = smoothStep(t)
                    val startH = baseHeight
                    val endH = when (index) {
                        0, 4 -> 0.08f
                        1, 3 -> 0.2f
                        else -> 0.06f
                    }
                    val valH = startH + (endH - startH) * st
                    val valY = 0.1f * st
                    valH to valY
                }
                p < 0.32f -> {
                    val t = (p - 0.12f) / 0.20f
                    val st = smoothStep(t)
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
                    val hVal = startH + (endH - startH) * st
                    val startY = 0.1f
                    val endY = -0.75f
                    val yVal = startY + (endY - startY) * st
                    hVal to yVal
                }
                p < 0.48f -> {
                    val t = (p - 0.32f) / 0.16f
                    val st = smoothStep(t)
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
                    val hVal = startH + (endH - startH) * st
                    val startY = -0.75f
                    val endY = -0.8f
                    val yVal = startY + (endY - startY) * st
                    hVal to yVal
                }
                p < 0.62f -> {
                    val t = (p - 0.48f) / 0.14f
                    val st = smoothStep(t)
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
                    val hVal = startH + (endH - startH) * st
                    val startY = -0.8f
                    val endY = 0.1f
                    val yVal = startY + (endY - startY) * st
                    hVal to yVal
                }
                p < 0.75f -> {
                    val t = (p - 0.62f) / 0.13f
                    val st = smoothStep(t)
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
                    val hVal = startH + (endH - startH) * st
                    val startY = 0.1f
                    val endY = -0.08f
                    val yVal = startY + (endY - startY) * st
                    hVal to yVal
                }
                p < 0.88f -> {
                    val t = (p - 0.75f) / 0.13f
                    val st = smoothStep(t)
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
                    val hVal = startH + (endH - startH) * st
                    val startY = -0.08f
                    val endY = 0.02f
                    val yVal = startY + (endY - startY) * st
                    hVal to yVal
                }
                else -> {
                    val t = minOf(1.0f, (p - 0.88f) / 0.12f)
                    val st = smoothStep(t)
                    val startH = when (index) {
                        0, 4 -> 0.25f
                        1, 3 -> 0.75f
                        else -> 0.18f
                    }
                    val endH = baseHeight
                    val hVal = startH + (endH - startH) * st
                    val startY = 0.02f
                    val endY = 0.0f
                    val yVal = startY + (endY - startY) * st
                    hVal to yVal
                }
            }
            heightFraction = h
            yOffsetFraction = y
            widthMultiplier = 1.0f
            xOffsetFraction = 0f
        }
        WaveformState.IDLE -> {
            val p = (playTimeMs % 3000) / 3000f

            // 1. Organic Breathing Pulse (ALERT but alertly tuned when typing)
            val breathingAmplitude = 0.04f + 0.02f * typingProgress
            val breathing = breathingAmplitude * Math.sin(2.0 * Math.PI * (playTimeMs % 1500) / 1500.0).toFloat()

            // 2. Playful springy ear wiggles with alert perk ears (base height grows from 0.3f to 0.42f when typing)
            val baseEarHeight = 0.3f + 0.12f * typingProgress

            val earLeftHeight = if (p in 0.35f..0.60f) {
                val t = (p - 0.35f) / 0.25f
                val envelope = Math.sin(t * Math.PI).toFloat()
                val vibration = 0.12f * Math.sin(t * 3.5 * 2.0 * Math.PI).toFloat()
                baseEarHeight + vibration * envelope
            } else {
                baseEarHeight
            }

            val earRightHeight = if (p in 0.65f..0.90f) {
                val t = (p - 0.65f) / 0.25f
                val envelope = Math.sin(t * Math.PI).toFloat()
                val vibration = 0.12f * Math.sin(t * 3.5 * 2.0 * Math.PI).toFloat()
                baseEarHeight + vibration * envelope
            } else {
                baseEarHeight
            }

            // 3. Soulful double-blink (wider windows and ease-in-ease-out curve to prevent clamping jitter)
            val eyeHeight = when {
                p in 0.10f..0.20f -> {
                    val t = (p - 0.10f) / 0.10f
                    val smoothT = Math.sin(t * Math.PI).toFloat()
                    baseHeight - (baseHeight - 0.20f) * smoothT
                }
                p in 0.23f..0.30f -> {
                    val t = (p - 0.23f) / 0.07f
                    val smoothT = Math.sin(t * Math.PI).toFloat()
                    baseHeight - (baseHeight - 0.22f) * smoothT
                }
                else -> baseHeight
            }

            // 4. Squeeze dip for the nose synced with blink
            val dip = if (p in 0.10f..0.30f) {
                val t = (p - 0.10f) / 0.20f
                0.05f * Math.sin(t * Math.PI).toFloat()
            } else {
                0f
            }

            // 5. Safe height fractions (nose base at 0.22f)
            heightFraction = when (index) {
                0 -> earLeftHeight + breathing
                4 -> earRightHeight + breathing
                1, 3 -> eyeHeight + breathing
                else -> 0.22f - dip + breathing // Nose/mouth
            }

            // Nose vertical motion synced with blink squeeze
            yOffsetFraction = if (index == 2) dip * 0.8f else 0f

            // 6. Playful head sway - scaled down by 70% when typing to look focused
            val swayX = 1.8f / 24f * Math.sin(2.0 * Math.PI * p).toFloat() * (1f - 0.7f * typingProgress)
            xOffsetFraction = swayX

            // Secondary squish/stretch on ears during sways
            if (index == 0) {
                heightFraction += swayX * 0.6f
            } else if (index == 4) {
                heightFraction -= swayX * 0.6f
            }

            widthMultiplier = 1.0f
        }
    }

    return BarProperties(heightFraction, yOffsetFraction, widthMultiplier, xOffsetFraction)
}

@Composable
private fun WaveformBars(
    modifier: Modifier = Modifier,
    targetColor: Color,
    state: WaveformState,
    successProgress: Float = 0f,
    typingProgress: Float = 0f
) {
    var playTimeMs by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        val startTime = withFrameNanos { it } / 1_000_000
        while (true) {
            withFrameNanos { playTimeMs = (it / 1_000_000) - startTime }
        }
    }

    var prevState by remember { mutableStateOf(state) }
    var currentState by remember { mutableStateOf(state) }
    val transitionAlpha = remember { Animatable(1f) }
    
    var prevColor by remember { mutableStateOf(targetColor) }
    var currentColor by remember { mutableStateOf(targetColor) }

    // No SnapshotStateList! Only triggers on state change.
    LaunchedEffect(state, targetColor) {
        if (state != currentState || targetColor != currentColor) {
            prevColor = androidx.compose.ui.graphics.lerp(prevColor, currentColor, transitionAlpha.value)
            currentColor = targetColor
            prevState = currentState
            currentState = state
            transitionAlpha.snapTo(0f)
            transitionAlpha.animateTo(1f, tween(400, easing = FastOutSlowInEasing))
        }
    }

    // Pure mathematical draw loop (Runs on GPU, Zero Recomposition Overhead)
    Canvas(modifier = modifier.fillMaxSize()) {
        val alpha = transitionAlpha.value
        val blendedColor = androidx.compose.ui.graphics.lerp(prevColor, currentColor, alpha)
        
        val barWidth = size.width / 9f
        val maxHeight = size.height * 0.8f
        val centerY = size.height / 2f

        for (index in 0 until 5) {
            val pOld = getBarProperties(prevState, index, playTimeMs, successProgress, typingProgress)
            val pNew = getBarProperties(currentState, index, playTimeMs, successProgress, typingProgress)

            // Direct inline interpolation
            val h = pOld.heightFraction + (pNew.heightFraction - pOld.heightFraction) * alpha
            val y = pOld.yOffsetFraction + (pNew.yOffsetFraction - pOld.yOffsetFraction) * alpha
            val w = pOld.widthMultiplier + (pNew.widthMultiplier - pOld.widthMultiplier) * alpha
            val x = pOld.xOffsetFraction + (pNew.xOffsetFraction - pOld.xOffsetFraction) * alpha

            val targetWidth = w * barWidth
            val finalHeight = maxOf(h * maxHeight, targetWidth * 1.25f)
            val targetLeft = (index * barWidth * 2f) + (x * barWidth)
            val targetTop = centerY + (y * maxHeight) - (finalHeight / 2f)

            drawRoundRect(
                color = blendedColor,
                topLeft = androidx.compose.ui.geometry.Offset(targetLeft, targetTop),
                size = androidx.compose.ui.geometry.Size(targetWidth, finalHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(targetWidth / 2f)
            )
        }
    }
}








