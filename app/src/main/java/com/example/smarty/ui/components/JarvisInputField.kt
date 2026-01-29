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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Assistant
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Assistant
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.outlined.Psychology
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
import com.example.smarty.data.model.Attachment
import com.example.smarty.data.model.MentionState
import com.example.smarty.data.model.MentionSuggestion
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.animation.JarvisEasing
import com.example.smarty.ui.animation.JarvisMotion
import com.example.smarty.ui.theme.JarvisShadow
import com.example.smarty.ui.animation.halftoneShimmer
import com.example.smarty.ui.animation.directionalShimmer
import com.example.smarty.ui.animation.ShimmerDirection
import com.example.smarty.ui.theme.ComponentSpacing
import com.example.smarty.ui.theme.LocalShapes
import com.example.smarty.ui.theme.MonoFont
import com.example.smarty.ui.theme.softCardShadow
import com.example.smarty.ui.utils.AnimationLifecycleState
import com.example.smarty.ui.utils.rememberAnimationLifecycleState
import com.example.smarty.util.rememberSpeechToText
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

// ═══════════════════════════════════════════════════════════════════════════════
// REFINED SOFT MINIMALIST COLORS
// Calmer, less "glassy", more "paper-like" floating elements
// ═══════════════════════════════════════════════════════════════════════════════
private val InputBackgroundLight = Color(0xFFFCFCFD)  // Almost white, very subtle off-white
private val InputBackgroundDark = Color(0xFF20202A)   // Soft dark gray
private val InputBorderLight = Color(0xFFE5E5EA)      // Very subtle gray border
private val InputBorderDark = Color(0xFF2C2C2E)       // Subtle dark border

// Design constants for the redesigned input block
private val CIRCLE_SIZE = 44.dp
private val CIRCLE_ICON_SIZE = 22.dp
private val PILL_HEIGHT = 52.dp // Slightly taller for better touch target/comfort
private val PILL_CORNER_RADIUS = 26.dp
private val ELEMENT_SPACING = 12.dp
private val HORIZONTAL_PADDING = 16.dp

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * REDESIGNED INPUT BLOCK
 * 
 * Design Principles:
 * - Comfortable, calm, low Jarvistive load
 * - Intentionally minimal
 * - Clear visual hierarchy: Circles (actions) vs Pill (input)
 * - Perfect circles, horizontal pill shape
 * 
 * NORMAL MODE Layout (Left to Right):
 * [Attach Circle] [Search Circle] [Input Pill ─────────── (Send Arrow)]
 * 
 * CHAT MODE Layout (Left to Right):
 * [Voice Circle] [Input Pill ───────────────────── (Send Arrow)]
 * 
 * Send Arrow appears only when: text is present OR attachment is present
 * ═══════════════════════════════════════════════════════════════════════════════
 */
@Composable
fun JarvisInputField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "add_note_or_ask_ai",
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
    chatPlaceholder: String = "add_note_or_ask_ai",
    aiPlanStatus: String? = null, // e.g. "Jarvis is planning..."
    currentTool: String? = null,
    isProcessing: Boolean = false,
    onOpenChatHistory: () -> Unit = {},
    onNewChat: () -> Unit = {}, // New parameter
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
    onClearFilters: () -> Unit = {},
    // @Mention support (Chat mode only)
    mentionState: MentionState = MentionState(),
    onMentionSelected: (MentionSuggestion) -> Unit = {},
    // Thinking mode toggle (Chat mode only - for reasoning models like Falcon-H1R-7B)
    isThinkingModeEnabled: Boolean = true,
    onToggleThinkingMode: () -> Unit = {}
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
    val canSend = value.text.isNotBlank() || attachments.isNotEmpty()

    // Placeholder based on mode
    val currentPlaceholder = when {
        isVoiceListening -> stringResource(R.string.listening)
        isSearchMode -> stringResource(R.string.find_notes)
        !aiPlanStatus.isNullOrBlank() -> aiPlanStatus.lowercase()
        isChatMode -> stringResource(R.string.ask_jarvis)
        else -> stringResource(R.string.add_note)
    }

    // Clear focus when submitting
    val handleSubmit: () -> Unit = {
        focusManager.clearFocus()
        onSubmit()
    }

    // Show attachment panel when input is focused (Normal Mode only)
    LaunchedEffect(isFocused, isChatMode) {
        if (isFocused && !isChatMode) {
            showAttachmentPanel = true
        } else if (!isFocused) {
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

    // ═══════════════════════════════════════════════════════════════════
    // ANIMATIONS
    // ═══════════════════════════════════════════════════════════════════
    
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

    // ═══════════════════════════════════════════════════════════════════
    // UI STRUCTURE
    // ═══════════════════════════════════════════════════════════════════

    Column(modifier = modifier.fillMaxWidth()) {

        // Chat mode indicator pill (above input) - hidden when mention suggestions are showing
        AnimatedVisibility(
            visible = isChatMode && !mentionState.isActive,
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
                    modifier = Modifier.clickable {
                        if (isHistoryMode) onNewChat() else onOpenChatHistory()
                    },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f), // Calmer color
                    border = BorderStroke(1.dp, LocalAccentColor.current.copy(alpha = 0.15f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Assistant,
                            contentDescription = null,
                            tint = LocalAccentColor.current,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (isHistoryMode) stringResource(R.string.new_chat) else stringResource(R.string.assistant),
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Medium,
                                letterSpacing = 0.5.sp
                            ),
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )

                        // Separator and secondary text
                        if (!isHistoryMode) {
                            Spacer(Modifier.width(8.dp))
                            Box(Modifier.width(1.dp).height(10.dp).background(MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.2f)))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.history),
                                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.5.sp),
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f)
                            )
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


        // ═══════════════════════════════════════════════════════════════════
        // @MENTION AUTOCOMPLETE DROPDOWN (Chat mode only, above input)
        // ═══════════════════════════════════════════════════════════════════
        if (isChatMode) {
            val isDark = !MaterialTheme.colorScheme.surface.luminance().let { it > 0.5f }
            MentionDropdown(
                mentionState = mentionState,
                onSuggestionSelected = onMentionSelected,
                isDarkTheme = isDark
            )
        }

        // ═══════════════════════════════════════════════════════════════════
        // MAIN INPUT ROW
        // ═══════════════════════════════════════════════════════════════════
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HORIZONTAL_PADDING),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ELEMENT_SPACING)
        ) {
            if (isChatMode) {
                // ═══════════════════════════════════════════════════════════════════
                // CHAT MODE: [Thinking Toggle] [Voice Circle] [Input Pill]
                // ═══════════════════════════════════════════════════════════════════

                Row(
                    horizontalArrangement = Arrangement.spacedBy(ELEMENT_SPACING),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Thinking Mode Toggle Button (for reasoning models like Falcon-H1R-7B)
                    ActionCircle(
                        icon = if (isThinkingModeEnabled) Icons.Default.Psychology else Icons.Outlined.Psychology,
                        contentDescription = if (isThinkingModeEnabled)
                            stringResource(R.string.reasoning_on)
                        else
                            stringResource(R.string.reasoning_off),
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onToggleThinkingMode()
                        },
                        isActive = isThinkingModeEnabled,
                        activeColor = LocalAccentColor.current
                    )

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
                }
            } else {
                // ═══════════════════════════════════════════════════════════════════
                // NORMAL MODE: [Attach Circle] [Search Circle] [Input Pill]
                // ═══════════════════════════════════════════════════════════════════
                
                // File Attachment Circle
                ActionCircle(
                    icon = Icons.Default.AttachFile, // Metaphor: Connection
                    contentDescription = stringResource(R.string.attach),
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        // Centralized Pill UI Toggle:
                        // Matches the Search Filter behavior for consistency.
                        if (isSearchMode) {
                            // Ideally we close search mode to switch context
                            // onClearFilters/onToggleSearch handled by parent? 
                             // We just ensure visual exclusivity here if possible
                        }
                        showAttachmentPanel = !showAttachmentPanel
                        // Note: If entering attachment mode, parent logic or visual exclusivity will hide search filters if implemented above
                    },
                    badge = if (attachments.isNotEmpty()) attachments.size else null,
                    isActive = showAttachmentPanel, 
                    activeColor = LocalAccentColor.current
                )

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

            // ═══════════════════════════════════════════════════════════════════
            // INPUT PILL (Both modes)
            // ═══════════════════════════════════════════════════════════════════
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
                    if (canSend) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        scope.launch {
                            flyAnimation.snapTo(0f)
                            flyAnimation.animateTo(1f, tween(300, easing = JarvisEasing.appleEaseOut))
                            delay(50)
                            flyAnimation.snapTo(0f)
                        }
                        showAttachmentPanel = false
                        scope.launch {
                            delay(60)
                            handleSubmit()
                        }
                    } else {
                        // Plus button logic: Toggle attachment panel
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        showAttachmentPanel = !showAttachmentPanel
                    }
                },
                flyProgress = flyAnimation.value,
                isVoiceListening = isVoiceListening,
                isAgentWorking = isAgentWorking,
                autoSendActive = autoSendActive,
                // Use requiredHeight to prevent flattening by parent layout
                modifier = Modifier.weight(1f)
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// ACTION CIRCLE COMPONENT
// ═══════════════════════════════════════════════════════════════════════════════

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
    val isDark = !MaterialTheme.colorScheme.surface.luminance().let { it > 0.5f }
    val accentColor = LocalAccentColor.current

    val backgroundColor = if (isDark) InputBackgroundDark else InputBackgroundLight
    val borderColor = if (isDark) InputBorderDark else InputBorderLight

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
                    .clickable {
                         // Ripple handled by Surface or custom indication if needed
                    }
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

// ═══════════════════════════════════════════════════════════════════════════════
// INPUT PILL COMPONENT
// ═══════════════════════════════════════════════════════════════════════════════

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
    flyProgress: Float,
    isVoiceListening: Boolean,
    isAgentWorking: Boolean,
    autoSendActive: Boolean,
    modifier: Modifier = Modifier
) {
    // Soft Minimalist: Determine colors based on theme
    val isDark = !MaterialTheme.colorScheme.surface.luminance().let { it > 0.5f }
    val accentColor = LocalAccentColor.current

    val backgroundColor = if (isDark) InputBackgroundDark else InputBackgroundLight
    val borderColor = if (isDark) InputBorderDark else InputBorderLight

    // Border: Subtle normally, colored when focused
    val currentBorderColor by animateColorAsState(
        targetValue = if (isFocused) accentColor.copy(alpha = 0.5f) else borderColor,
        animationSpec = tween(200),
        label = "pillBorder"
    )

    // Elevation changes on focus
    val elevation = if (isFocused) 8.dp else 2.dp

    // Soft floating pill container
    Surface(
        modifier = modifier
            .requiredHeight(PILL_HEIGHT)
            .softCardShadow(shape = RoundedCornerShape(PILL_CORNER_RADIUS), elevation = elevation),
        shape = RoundedCornerShape(PILL_CORNER_RADIUS),
        color = backgroundColor,
        border = BorderStroke(1.dp, currentBorderColor)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Shimmer overlay for voice/agent states
            val showShimmer = autoSendActive || isVoiceListening || isAgentWorking
            if (showShimmer) {
                val shimmerDirection = when {
                    isAgentWorking && !isVoiceListening && !autoSendActive -> ShimmerDirection.RIGHT_TO_LEFT
                    else -> ShimmerDirection.LEFT_TO_RIGHT
                }
                val shimmerColor = LocalAccentColor.current.copy(alpha = 0.1f)
                val shimmerSpeed = if (autoSendActive) 3.5f else 1f

                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .directionalShimmer(
                            isVisible = showShimmer,
                            color = shimmerColor,
                            direction = shimmerDirection,
                            speed = shimmerSpeed
                        )
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 20.dp, end = 8.dp), // Increased left padding for text comfort
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Text input area
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            focusRequester.requestFocus()
                        },
                    contentAlignment = Alignment.CenterStart
                ) {
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
                            fontSize = 15.sp, // Slightly larger for readability
                            color = if (isVoiceListening) LocalAccentColor.current
                                    else MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(LocalAccentColor.current),
                        singleLine = true,
                        maxLines = 1
                    )

                    // Placeholder
                    androidx.compose.animation.AnimatedVisibility(
                        visible = value.text.isEmpty(),
                        enter = fadeIn(tween(150)),
                        exit = fadeOut(tween(50))
                    ) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = MonoFont,
                                fontSize = 15.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }

                // Action icon (Plus when empty, Rocket when has content)
                Box {
                    val haptic = LocalHapticFeedback.current

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.width(8.dp))

                        val easedProgress = FastOutSlowInEasing.transform(flyProgress)
                        // Flight path: Fly primarily RIGHT, slightly Up
                        val flyX = easedProgress * 120f  // Fly further right
                        val flyY = -easedProgress * 15f  // Slight lift up
                        val flyRotation = easedProgress * 10f  // Tilt slightly up/right
                        val flyScale = 1f - (easedProgress * 0.2f)
                        val flyAlpha = (1f - easedProgress * 1.5f).coerceIn(0f, 1f)

                        // Get density for graphicsLayer transformations
                        val density = androidx.compose.ui.platform.LocalDensity.current.density

                        var isPressed by remember { mutableStateOf(false) }
                        val buttonScale by animateFloatAsState(
                            targetValue = if (isPressed) 0.85f else 1f,
                            animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
                            label = "sendScale"
                        )

                        // Send button style
                        val sendBtnColor = if (canSend) LocalAccentColor.current else MaterialTheme.colorScheme.surfaceVariant
                        val sendIconColor = if (canSend) Color.White else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)

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
                                        onTap = { onSend() }
                                    )
                                }
                                .padding(8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            AnimatedContent(
                                targetState = canSend,
                                transitionSpec = {
                                    (scaleIn() + fadeIn()) togetherWith (scaleOut() + fadeOut())
                                },
                                label = "iconTransition"
                            ) { isSending ->
                                Icon(
                                    imageVector = if (isSending) Icons.AutoMirrored.Filled.Send else Icons.Default.Add,
                                    contentDescription = if (isSending) stringResource(R.string.share) else stringResource(R.string.add_attachment),
                                    tint = if (isSending && canSend) Color.White.copy(alpha = flyAlpha) else sendIconColor,
                                    modifier = Modifier
                                        .size(20.dp)
                                        .graphicsLayer {
                                            if (isSending && canSend) { // Only animate flight if it's the send button
                                                translationX = flyX * density
                                                translationY = flyY * density
                                                rotationZ = flyRotation
                                                scaleX = flyScale
                                                scaleY = flyScale
                                            }
                                        }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
