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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.GraphicEq
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarty.data.model.Attachment
import com.example.smarty.data.model.MentionState
import com.example.smarty.data.model.MentionSuggestion
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
private val AttachmentRedColor = Color(0xFFF44336)
private val AttachmentGreenColor = Color(0xFF4CAF50)
private val AttachmentBlueColor = Color(0xFF2196F3)
private val AttachmentOrangeColor = Color(0xFFFF9500)
private val AttachmentGrayColor = Color(0xFF607D8B)

// Agent shimmer color (purple/blue gradient feel)
private val AgentShimmerColor = Color(0xFF7C4DFF)

// ═══════════════════════════════════════════════════════════════════════════════
// ENHANCED GLASSMORPHISM COLORS
// More transparent backgrounds with stronger blur effect for premium glass look
// ═══════════════════════════════════════════════════════════════════════════════
private val GlassBackgroundLight = Color(0xBBFFFFFF)  // White with 73% opacity (more transparent)
private val GlassBackgroundDark = Color(0xB0181822)   // Dark with 69% opacity (more transparent)
private val GlassBlueTintLight = Color(0x220066FF)    // Blue tint 13% for light (stronger)
private val GlassBlueTintDark = Color(0x332979FF)     // Blue tint 20% for dark (stronger)
private val GlassBorderLight = Color(0x550066FF)      // Blue border 33% for light (more visible)
private val GlassBorderDark = Color(0x662979FF)       // Blue border 40% for dark (more visible)
private val GlassInnerGlow = Color(0x1AFFFFFF)        // Stronger white inner glow (10%)

// Design constants for the redesigned input block
private val CIRCLE_SIZE = 44.dp
private val CIRCLE_ICON_SIZE = 22.dp
private val PILL_HEIGHT = 48.dp
private val PILL_CORNER_RADIUS = 24.dp
private val ELEMENT_SPACING = 10.dp
private val HORIZONTAL_PADDING = 12.dp

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * REDESIGNED INPUT BLOCK
 * 
 * Design Principles:
 * - Comfortable, calm, low cognitive load
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
fun CogniInputField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Share with Dot...",
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
    chatPlaceholder: String = "Ask anything...",
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
    val canSend = value.text.isNotBlank() || attachments.isNotEmpty()

    // Placeholder based on mode
    val currentPlaceholder = when {
        isVoiceListening -> "Listening..."
        isSearchMode -> "Search notes..."
        isChatMode -> chatPlaceholder
        else -> placeholder
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
                    color = LocalAccentColor.current.copy(alpha = 0.85f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isHistoryMode) Icons.Default.Add else Icons.Default.ChatBubble,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (isHistoryMode) "New Chat" else "Chat Mode",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White
                        )
                        
                        // Separator and secondary text only for normal Chat Mode (not History Mode)
                        if (!isHistoryMode) {
                            Spacer(Modifier.width(8.dp))
                            Box(Modifier.width(1.dp).height(10.dp).background(Color.White.copy(alpha = 0.3f)))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Tap for history",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.7f)
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
                            Icons.Filled.Security,
                            contentDescription = "Private",
                            tint = MaterialTheme.colorScheme.inverseOnSurface,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Private Mode",
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
                // CHAT MODE: [Input Pill] (Voice is trigger-only)
                // ═══════════════════════════════════════════════════════════════════
                // No circles, just the pill below
            } else {
                // ═══════════════════════════════════════════════════════════════════
                // NORMAL MODE: [Attach Circle] [Search Circle] [Input Pill]
                // ═══════════════════════════════════════════════════════════════════
                
                // File Attachment Circle
                ActionCircle(
                    icon = Icons.Default.Add,
                    contentDescription = "Attach File",
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
                    icon = Icons.Default.Search,
                    contentDescription = "Search",
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
                            flyAnimation.animateTo(1f, tween(300, easing = CogniEasing.appleEaseOut))
                            delay(50)
                            flyAnimation.snapTo(0f)
                        }
                        showAttachmentPanel = false
                        scope.launch {
                            delay(60)
                            handleSubmit()
                        }
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
    // Glassmorphism: Determine colors based on theme
    val isDark = !MaterialTheme.colorScheme.surface.luminance().let { it > 0.5f }
    val glassBackground = if (isDark) GlassBackgroundDark else GlassBackgroundLight
    val glassTint = if (isDark) GlassBlueTintDark else GlassBlueTintLight
    val glassBorder = if (isDark) GlassBorderDark else GlassBorderLight

    val iconColor by animateColorAsState(
        targetValue = if (isActive) activeColor else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(200),
        label = "circleIcon"
    )

    // Glassmorphism border: blue tint that intensifies when active
    val borderColor by animateColorAsState(
        targetValue = if (isActive) activeColor.copy(alpha = 0.6f) else glassBorder,
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
        // Glassmorphism container with layered backgrounds
        Box(
            modifier = Modifier
                .requiredSize(CIRCLE_SIZE)
                .scale(scale)
                .shadow(
                    elevation = if (isActive) 8.dp else 4.dp,
                    shape = CircleShape,
                    spotColor = activeColor.copy(alpha = if (isActive) 0.25f else 0.1f),
                    ambientColor = activeColor.copy(alpha = if (isActive) 0.15f else 0.05f)
                )
                // Base glass background
                .clip(CircleShape)
                .background(glassBackground)
                // Blue tint overlay
                .background(glassTint)
                // Inner glow for depth
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            GlassInnerGlow,
                            Color.Transparent
                        )
                    )
                )
                // Glass border
                .border(
                    width = if (isActive) 1.5.dp else 1.dp,
                    color = borderColor,
                    shape = CircleShape
                )
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

        // Badge
        if (badge != null && badge > 0) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-4).dp)
                    .requiredSize(18.dp),
                shape = CircleShape,
                color = LocalAccentColor.current
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = badge.toString(),
                        style = MaterialTheme.typography.labelSmall,
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
    // Glassmorphism: Determine colors based on theme
    val isDark = !MaterialTheme.colorScheme.surface.luminance().let { it > 0.5f }
    val glassBackground = if (isDark) GlassBackgroundDark else GlassBackgroundLight
    val glassTint = if (isDark) GlassBlueTintDark else GlassBlueTintLight
    val glassBorder = if (isDark) GlassBorderDark else GlassBorderLight
    val accentColor = LocalAccentColor.current

    // Glassmorphism border: blue tint that intensifies when focused
    val borderColor by animateColorAsState(
        targetValue = if (isFocused) accentColor.copy(alpha = 0.6f) else glassBorder,
        animationSpec = tween(200),
        label = "pillBorder"
    )

    // Glassmorphism container with layered backgrounds
    Box(
        modifier = modifier
            .requiredHeight(PILL_HEIGHT)
            .shadow(
                elevation = if (isFocused) 12.dp else 6.dp,
                shape = RoundedCornerShape(PILL_CORNER_RADIUS),
                spotColor = accentColor.copy(alpha = if (isFocused) 0.2f else 0.1f),
                ambientColor = accentColor.copy(alpha = if (isFocused) 0.1f else 0.05f)
            )
            // Base glass background
            .clip(RoundedCornerShape(PILL_CORNER_RADIUS))
            .background(glassBackground)
            // Blue tint overlay for glass effect
            .background(glassTint)
            // Subtle gradient for depth (top-to-bottom light gradient)
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        GlassInnerGlow,
                        Color.Transparent,
                        Color.Transparent
                    )
                )
            )
            // Glass border with blue tint
            .border(
                width = if (isFocused) 1.5.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(PILL_CORNER_RADIUS)
            )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Shimmer overlay for voice/agent states
            val showShimmer = autoSendActive || isVoiceListening || isAgentWorking
            if (showShimmer) {
                val shimmerDirection = when {
                    isAgentWorking && !isVoiceListening && !autoSendActive -> ShimmerDirection.RIGHT_TO_LEFT
                    else -> ShimmerDirection.LEFT_TO_RIGHT
                }
                val shimmerColor = LocalAccentColor.current
                val shimmerSpeed = if (autoSendActive) 3.5f else 1f

                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clip(RoundedCornerShape(PILL_CORNER_RADIUS))
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
                    .padding(start = 16.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Text input area
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
                            fontSize = MaterialTheme.typography.bodyMedium.fontSize,
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
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFont),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }

                // Send Arrow (conditional visibility) - Pointing RIGHT
                // Using Row-aware animations for horizontal expansion
                androidx.compose.animation.AnimatedVisibility(
                    visible = canSend,
                    enter = scaleIn(initialScale = 0.7f) + fadeIn(),
                    exit = scaleOut(targetScale = 0.7f) + fadeOut()
                ) {
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

                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .scale(buttonScale)
                                // Removed clip(CircleShape) and background(LocalAccentColor)
                                // to create a standalone floating icon
                                .pointerInput(canSend) {
                                    if (canSend) {
                                        detectTapGestures(
                                            onPress = {
                                                isPressed = true
                                                tryAwaitRelease()
                                                isPressed = false
                                            },
                                            onTap = { onSend() }
                                        )
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward, // Use ArrowForward (->)
                                contentDescription = "Send",
                                tint = LocalAccentColor.current.copy(alpha = flyAlpha), // Blue tint
                                modifier = Modifier
                                    .size(24.dp)
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
                }
            }
        }
    }
}