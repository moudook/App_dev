package com.example.smarty.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarty.R
import com.example.smarty.core.domain.model.Attachment
import com.example.smarty.core.domain.model.ClarificationRequest
import com.example.smarty.core.domain.model.MentionState
import com.example.smarty.ui.LocalAccentColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val MonoFont = FontFamily.Monospace

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SmartyInputField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "I'm all ears.",
    attachments: List<Attachment> = emptyList(),
    onRemoveAttachment: (String) -> Unit = {},
    onPickImage: () -> Unit = {},
    isVoiceListening: Boolean = false,
    onStartVoiceInput: () -> Unit = {},
    onStopVoiceInput: () -> Unit = {},
    isProcessing: Boolean = false,
    isAgentWorking: Boolean = false,
    showHistoryOption: Boolean = true,
    onOpenChatHistory: () -> Unit = {},
    selectedModel: String = "GPT-4o",
    availableModels: List<Pair<String, String>> = emptyList(),
    onModelSelected: (String) -> Unit = {},
    modelVariantMap: Map<String, List<String>> = emptyMap(),
    selectedVariant: String? = null,
    onVariantSelected: (String?) -> Unit = {},
    // Re-integrated features from our previous iterations:
    isImageGenMode: Boolean = false,
    isHistoryMode: Boolean = false,
    onToggleImageGenMode: () -> Unit = {},
    pendingQuestions: List<ClarificationRequest> = emptyList(),
    onQuestionAnswered: (String) -> Unit = {},
    isChatMode: Boolean = true,
    onNewChat: () -> Unit = {},
    mentionState: MentionState = MentionState(),
    chatPlaceholder: String = "I'm all ears.",
    onStopGeneration: () -> Unit = {},
    onPickFile: () -> Unit = {},
    onOpenCamera: () -> Unit = {},
    onPickVideo: () -> Unit = {},
    onPickDocument: () -> Unit = {},
    onPickAudio: () -> Unit = {},
    onRefreshModels: suspend () -> List<Pair<String, String>> = { emptyList() },
    onClearInput: () -> Unit = {},
    isAiExcluded: Boolean = false,
    isSearchMode: Boolean = false,
    onToggleSearch: () -> Unit = {},
    autoSendActive: Boolean = false,
    selectedFilters: Set<com.example.smarty.ui.components.AttachmentOption> = emptySet(),
    onFilterToggle: (com.example.smarty.ui.components.AttachmentOption) -> Unit = {},
    onClearFilters: () -> Unit = {},
    onStartRecording: () -> Unit = {},
    onStopRecording: () -> Unit = {},
    isRecording: Boolean = false,
    onMentionSelected: (com.example.smarty.core.domain.model.MentionSuggestion) -> Unit = {},
    showScrollButton: Boolean = false,
    isAtLatest: Boolean = true,
    onScrollToBottom: () -> Unit = {},
    onScrollToTop: () -> Unit = {},
) {
    val haptic = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var isFocused by remember { mutableStateOf(false) }
    val accentColor = LocalAccentColor.current
    val isDark = MaterialTheme.colorScheme.surface.luminance() <= 0.51f
    
    val canSend = value.text.isNotEmpty() || attachments.isNotEmpty()
    val isAskingQuestion = pendingQuestions.isNotEmpty()
    val textColor = if (isDark) Color.White else Color(0xFF1D1D1F)
    val optionBg = if (isDark) Color(0xFF2A2A2A) else Color(0xFFF4F4F7)

    // S-Tier Orchestrator for Container Expansion
    val transition = updateTransition(targetState = isFocused || canSend || isAskingQuestion, label = "InputState")
    
    // Dynamic corner radius: softer when expanded, perfectly rounded pill when collapsed
    val cornerRadius by transition.animateDp(
        transitionSpec = { spring(dampingRatio = 0.7f, stiffness = 400f) },
        label = "corner"
    ) { expanded -> if (expanded) 24.dp else 28.dp }

    val elevation by transition.animateDp(
        transitionSpec = { spring(dampingRatio = 0.8f, stiffness = 300f) }, 
        label = "elevation"
    ) { expanded -> if (expanded) 8.dp else 2.dp }

    // State for Ask Tool - keyed to pendingQuestions identity so it resets properly
    val questionKey = remember(pendingQuestions.size, pendingQuestions.firstOrNull()?.question) {
        pendingQuestions.hashCode() + (pendingQuestions.firstOrNull()?.hashCode() ?: 0)
    }
    var currentQuestionIndex by remember(questionKey) { mutableIntStateOf(0) }
    val answers = remember(questionKey) { mutableStateListOf<String>() }
    var customAnswerText by remember { mutableStateOf("") }
    var isEditingCustomAnswer by remember { mutableStateOf(false) }

    val handleAskSubmit: (String) -> Unit = { answer: String ->
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

    var showAttachmentSelector by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 1. TOP: S-Tier Attachment Preview
        AnimatedVisibility(
            visible = attachments.isNotEmpty(),
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            STierAttachmentPreview(
                attachments = attachments,
                onRemove = onRemoveAttachment,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        // 2. S-Tier Ask Tool UI — now integrated into the pill, remove old separate card

        // 3. Attachment Selector (Notes only)
        if (!isChatMode) {
            AttachmentTypeSelector(
                visible = showAttachmentSelector,
                onSelectImage = { showAttachmentSelector = false; onPickImage() },
                onSelectVideo = { showAttachmentSelector = false },
                onSelectDocument = { showAttachmentSelector = false },
                onSelectAudio = { showAttachmentSelector = false },
                onSelectFile = { showAttachmentSelector = false },
                onSelectLink = { showAttachmentSelector = false },
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        // 4. MAIN PILL (Core Text Input + Action Bar — OR Question UI when asking)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .graphicsLayer {
                    shadowElevation = elevation.toPx()
                    shape = RoundedCornerShape(cornerRadius.toPx())
                    clip = true
                    ambientShadowColor = Color.Black.copy(alpha = 0.05f)
                    spotShadowColor = Color.Black.copy(alpha = 0.08f)
                }
                .background(if (isDark) Color(0xFF1E1E1E) else Color.White)
                .border(1.dp, if (isDark) Color.White.copy(0.05f) else Color.Black.copy(alpha = 0.05f), RoundedCornerShape(cornerRadius))
                .animateContentSize(animationSpec = spring(dampingRatio = 0.75f, stiffness = 350f))
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {

        if (isAskingQuestion) {
            // ── QUESTION MODE: golden ratio (φ ≈ 1.618) spacing system ──
            // φ⁰ = 5dp, φ¹ = 8dp, φ² = 13dp, φ³ = 21dp
            // Fonts: φ⁻² = 10sp, φ⁻¹ = 11sp, φ⁰ = 13sp, φ¹ = 16sp
            val safeIndex = currentQuestionIndex.coerceIn(0, maxOf(0, pendingQuestions.size - 1))
            val currentRequest = pendingQuestions.getOrNull(safeIndex)

            if (currentRequest != null) {
                // Counter — φ⁻² = 10sp, accent color for visual hierarchy
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${currentQuestionIndex + 1}/${pendingQuestions.size}",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = accentColor.copy(alpha = 0.5f),
                        letterSpacing = 0.5.sp
                    )
                }

                // Question text — φ¹ = 16sp, animated crossfade
                androidx.compose.animation.AnimatedContent(
                    targetState = currentRequest.question,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(200)) + slideInVertically(animationSpec = tween(200)) { it / 4 } togetherWith
                        fadeOut(animationSpec = tween(150)) + slideOutVertically(animationSpec = tween(150)) { -it / 4 }
                    },
                    label = "questionTransition"
                ) { question ->
                    Text(
                        text = question,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 22.sp,
                        color = textColor
                    )
                }

                // φ² = 13dp gap between question and options
                Spacer(Modifier.height(13.dp))

                // Options and custom input inside a scrollable column so the question remains sticky
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    currentRequest.options.forEachIndexed { index, option ->
                        Surface(
                            shape = RoundedCornerShape(14.dp), // Improved border radius
                            color = optionBg,
                            modifier = Modifier
                                .fillMaxWidth()
                                .squishClick {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    handleAskSubmit(option)
                                }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                            ) {
                                // Accent number badge
                                Text(
                                    text = "${index + 1}",
                                    fontWeight = FontWeight.Bold,
                                    color = accentColor,
                                    fontSize = 12.sp,
                                    modifier = Modifier.width(20.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = option,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = textColor
                                )
                            }
                        }
                    }

                    // Custom input row
                    if (currentRequest.allowCustomInput) {
                        if (isEditingCustomAnswer) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .background(optionBg, RoundedCornerShape(14.dp))
                                    .border(1.dp, accentColor.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
                                    .padding(horizontal = 16.dp)
                            ) {
                                BasicTextField(
                                    value = customAnswerText,
                                    onValueChange = { customAnswerText = it },
                                    modifier = Modifier.weight(1f),
                                    textStyle = TextStyle(fontSize = 15.sp, color = textColor),
                                    singleLine = true,
                                    cursorBrush = SolidColor(accentColor),
                                    decorationBox = { inner ->
                                        Box(contentAlignment = Alignment.CenterStart) {
                                            if (customAnswerText.isEmpty()) Text("Type your answer...", color = Color.Gray, fontSize = 15.sp)
                                            inner()
                                        }
                                    }
                                )
                                AnimatedVisibility(customAnswerText.isNotBlank(), enter = scaleIn(spring(0.7f, 400f)) + fadeIn(), exit = scaleOut() + fadeOut()) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.Send,
                                        contentDescription = "Send",
                                        tint = accentColor,
                                        modifier = Modifier
                                            .size(20.dp)
                                            .squishClick {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                handleAskSubmit(customAnswerText)
                                            }
                                    )
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .squishClick { isEditingCustomAnswer = true }
                                    .background(optionBg, RoundedCornerShape(14.dp))
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.Edit, null, tint = Color.Gray, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Type a custom answer...", color = Color.Gray, fontSize = 15.sp)
                            }
                        }
                    }
                }
            }
        } else {
            // ── NORMAL MODE: text input + action bar ──
            // Text Input — max 5 lines (120dp), scrolls internally beyond that
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 120.dp)
                    .verticalScroll(rememberScrollState())
                    .defaultMinSize(minHeight = 24.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() }, indication = null
                    ) { focusManager.clearFocus() },
                contentAlignment = Alignment.TopStart
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(
                        fontSize = 17.sp,
                        color = if (isDark) Color.White else Color(0xFF1D1D1F),
                        lineHeight = 24.sp
                    ),
                    cursorBrush = SolidColor(accentColor),
                    decorationBox = { innerTextField ->
                        Box(contentAlignment = Alignment.TopStart) {
                            if (value.text.isEmpty()) {
                                Text(placeholder, color = Color.Gray.copy(alpha = 0.7f), fontSize = 17.sp)
                            }
                            innerTextField()
                        }
                    }
                )
            }

            Spacer(Modifier.height(16.dp))

            // Action Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!isChatMode) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = "Attach",
                            tint = Color.Gray,
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .squishClick {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    showAttachmentSelector = !showAttachmentSelector
                                }
                        )
                    }

                    if (isChatMode && showHistoryOption) {
                        Icon(
                            imageVector = if (isHistoryMode) Icons.Rounded.Edit else Icons.Rounded.History,
                            contentDescription = if (isHistoryMode) "New Chat" else "History",
                            tint = Color.Gray,
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .squishClick {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    if (isHistoryMode) onNewChat() else onOpenChatHistory()
                                }
                        )

                        Spacer(Modifier.width(4.dp))

                        Icon(
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = "Image Generation",
                            tint = if (isImageGenMode) accentColor else Color.Gray,
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .squishClick {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onToggleImageGenMode()
                                }
                        )
                    }

                    if (isChatMode) {
                        Box(modifier = Modifier.height(16.dp).width(1.dp).background(Color.Gray.copy(alpha = 0.3f)))

                        var showModelMenu by remember { mutableStateOf(false) }
                        var expandedVariantModel by remember { mutableStateOf<String?>(null) }
                        val displayLabel = selectedModel.substringAfterLast("/")
                            .replace(Regex("(?i)-free\\b"), "")
                            .replace(Regex("(?i)\\bfree\\b"), "")
                            .replace(Regex("(?i)\\s*\\(free\\)"), "")
                            .trim()

                        Surface(
                            shape = RoundedCornerShape(percent = 50),
                            color = if (isDark) accentColor.copy(alpha = 0.15f) else accentColor.copy(alpha = 0.08f),
                            modifier = Modifier
                                .height(32.dp)
                                .widthIn(max = 140.dp)
                                .squishClick {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    scope.launch {
                                        val refreshed = onRefreshModels()
                                        expandedVariantModel = null
                                        showModelMenu = true
                                    }
                                }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp)
                            ) {
                                Text(
                                    text = displayLabel,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = accentColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                Spacer(Modifier.width(4.dp))
                                Icon(Icons.Rounded.KeyboardArrowDown, null, tint = accentColor, modifier = Modifier.size(16.dp))
                            }

                            DropdownMenu(
                                expanded = showModelMenu,
                                onDismissRequest = { showModelMenu = false; expandedVariantModel = null },
                                shape = RoundedCornerShape(16.dp),
                                containerColor = if (isDark) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
                            ) {
                                availableModels.forEach { (modelId, label) ->
                                    val cleanLabel = label
                                        .replace(Regex("(?i)-free\\b"), "")
                                        .replace(Regex("(?i)\\bfree\\b"), "")
                                        .replace(Regex("(?i)\\s*\\(free\\)"), "")
                                        .trim()
                                    val variants = modelVariantMap[modelId]
                                    val hasVariants = !variants.isNullOrEmpty()
                                    val isExpanded = expandedVariantModel == modelId

                                    Column {
                                        DropdownMenuItem(
                                            text = {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        cleanLabel,
                                                        fontWeight = if (selectedModel == modelId) FontWeight.Bold else FontWeight.Normal,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    if (hasVariants) {
                                                        Icon(
                                                            Icons.Rounded.KeyboardArrowDown,
                                                            contentDescription = null,
                                                            tint = Color.Gray,
                                                            modifier = Modifier
                                                                .size(16.dp)
                                                                .graphicsLayer { rotationZ = if (isExpanded) 180f else 0f }
                                                        )
                                                    }
                                                }
                                            },
                                            onClick = {
                                                if (hasVariants) {
                                                    expandedVariantModel = if (isExpanded) null else modelId
                                                } else {
                                                    onModelSelected(modelId)
                                                    showModelMenu = false
                                                }
                                            }
                                        )

                                        if (isExpanded && hasVariants) {
                                            variants.forEach { variant ->
                                                val isSelectedModel = selectedModel == modelId
                                                val isSelectedVariant = isSelectedModel && variant == selectedVariant
                                                DropdownMenuItem(
                                                    text = {
                                                        Text(
                                                            variant,
                                                            fontSize = 13.sp,
                                                            fontWeight = if (isSelectedVariant) FontWeight.Bold else FontWeight.Normal,
                                                            color = if (isSelectedVariant) accentColor else Color.Gray,
                                                            modifier = Modifier.padding(start = 16.dp)
                                                        )
                                                    },
                                                    onClick = {
                                                        onModelSelected(modelId)
                                                        onVariantSelected(if (isSelectedVariant) null else variant)
                                                        showModelMenu = false
                                                        expandedVariantModel = null
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

                // Right: Mic + Send
                Box(
                    modifier = Modifier.width(32.dp),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    val showStopIcon = isAgentWorking && isChatMode
                    val micOffsetY by animateDpAsState(
                        targetValue = if (canSend || showStopIcon) (-40).dp else 0.dp,
                        animationSpec = spring(dampingRatio = 0.7f, stiffness = 300f),
                        label = "micOffset"
                    )

                    Box(
                        modifier = Modifier
                            .offset(y = micOffsetY)
                            .size(32.dp)
                            .clip(CircleShape)
                            .squishClick {
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
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    androidx.compose.animation.AnimatedVisibility(
                        visible = canSend || showStopIcon,
                        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                        modifier = Modifier.align(Alignment.BottomCenter)
                    ) {
                        val btnColor = if (showStopIcon) Color(0xFFE53935) else (if (isDark) Color.White else Color.Black)
                        val iconTint = if (showStopIcon) Color.White else (if (isDark) Color.Black else Color.White)

                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(btnColor, CircleShape)
                                .squishClick {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    if (showStopIcon) {
                                        onStopGeneration()
                                    } else {
                                        onSubmit()
                                        if (!isChatMode) {
                                            focusManager.clearFocus()
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (showStopIcon) {
                                Icon(
                                    imageVector = Icons.Rounded.Stop,
                                    contentDescription = "Stop",
                                    tint = iconTint,
                                    modifier = Modifier.size(18.dp)
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.Send,
                                    contentDescription = "Send",
                                    tint = iconTint,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        } // end if/else isAskingQuestion
    }
}

}

// S-TIER GPU CANVAS WAVEFORM (Preserved entirely!)
// 

@Composable
fun Modifier.squishClick(
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    onClick: () -> Unit
): Modifier {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 500f),
        label = "squish"
    )
    return this
        .graphicsLayer { 
            scaleX = scale
            scaleY = scale
            clip = true
        }
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick
        )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun STierAttachmentPreview(
    attachments: List<Attachment>,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val isDark = MaterialTheme.colorScheme.surface.luminance() <= 0.51f
    val chipBg = if (isDark) Color(0xFF2A2A2A) else Color(0xFFF4F4F7)
    val textColor = if (isDark) Color.White else Color(0xFF1D1D1F)

    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        attachments.forEachIndexed { index, attachment ->
            val appleEasing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

            AnimatedVisibility(
                visible = true,
                enter = fadeIn(tween(350, delayMillis = index * 40, easing = appleEasing)) + 
                        scaleIn(
                            initialScale = 0.8f, 
                            animationSpec = spring(dampingRatio = 0.65f, stiffness = 350f)
                        ),
                exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.9f)
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = chipBg,
                    modifier = Modifier.height(36.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 10.dp, end = 6.dp)
                    ) {
                        Icon(
                            imageVector = when(attachment.getAttachmentType()) {
                                com.example.smarty.core.domain.model.AttachmentType.IMAGE -> Icons.Rounded.Image
                                com.example.smarty.core.domain.model.AttachmentType.VIDEO -> Icons.Rounded.Videocam
                                com.example.smarty.core.domain.model.AttachmentType.DOCUMENT -> Icons.Rounded.Description
                                else -> Icons.Rounded.InsertDriveFile
                            },
                            contentDescription = null,
                            tint = Color.Gray,
                            modifier = Modifier.size(16.dp)
                        )
                        
                        Spacer(Modifier.width(6.dp))
                        
                        Text(
                            text = attachment.fileName,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = textColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 140.dp)
                        )
                        
                        Spacer(Modifier.width(6.dp))
                        
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(28.dp)
                                .squishClick { 
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onRemove(attachment.id) 
                                }
                        ) {
                            Icon(
                                Icons.Rounded.Close, 
                                contentDescription = "Remove", 
                                tint = Color.Gray, 
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}


// S-TIER GPU CANVAS WAVEFORM (Preserved entirely!)
// 

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

    var isIdleFor20s by remember { mutableStateOf(false) }
 
    // Reset idle timer on any active interaction: listening, processing, focused, typing or text entry.
    LaunchedEffect(isListening, isProcessing, isAgentWorking, value.text, isFocused, isTyping) {
        isIdleFor20s = false
        if (!isListening && !isProcessing && !isAgentWorking && !isTyping) {
            delay(20000) // 20-second timeout
            isIdleFor20s = true
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
        isIdleFor20s -> WaveformState.SAD
        isTyping || isUserAnsweringTool -> WaveformState.GIGGLE
        else -> WaveformState.IDLE
    }

    val isDark = MaterialTheme.colorScheme.surface.luminance() <= 0.51f
    val accentColor = com.example.smarty.ui.LocalAccentColor.current
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
        WaveformState.GIGGLE -> {
            val p = (playTimeMs % 1800) / 1800f
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
            val p = (playTimeMs % 3600) / 3600f
            
            // Soulful slow-sleep deep breathing (sighing) cycle
            val breathRaw = Math.sin(2.0 * Math.PI * p).toFloat()
            
            // Asymmetric breathing: inhale is slightly faster/more active, exhale is a slow heavy sigh
            val breathFactor = if (breathRaw > 0f) {
                Math.pow(breathRaw.toDouble(), 0.7).toFloat()
            } else {
                breathRaw * 0.5f
            }

            // Squash & Stretch physics
            widthMultiplier = 1.0f - 0.12f * breathFactor
            
            // Slow, heavy head sway
            val slowSway = 0.04f * Math.sin(2.0 * Math.PI * (playTimeMs % 7200) / 7200.0).toFloat()
            xOffsetFraction = slowSway

            // Safe drooped base heights (ears = 0.20f, nose = 0.22f) preventing any squashed capsules.
            // Eyes (1, 3) close to slit-like heights for a sleeping expression, slightly breathing.
            heightFraction = when (index) {
                0, 4 -> {
                    val earHeight = 0.20f - 0.05f * breathFactor
                    if (index == 0) earHeight + slowSway * 0.4f else earHeight - slowSway * 0.4f
                }
                1, 3 -> 0.20f + 0.04f * breathFactor // Sleeping eyes
                else -> 0.22f + 0.05f * breathFactor  // Sleepy nose/mouth
            }

            yOffsetFraction = when (index) {
                0, 4 -> 0.08f - 0.04f * breathFactor
                1, 3 -> 0.04f - 0.03f * breathFactor
                else -> 0.15f - 0.06f * breathFactor
            }
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
    color: Color,
    state: WaveformState,
    successProgress: Float = 0f,
    typingProgress: Float = 0f
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

    // Stored exact snapshot of properties when transition is initiated to guarantee mathematically continuous, pop-free morphing
    val transitionStartProps = remember {
        mutableStateListOf<BarProperties>().apply {
            repeat(5) { add(BarProperties(0.2f, 0f, 1f, 0f)) }
        }
    }
    var useStoredStartProps by remember { mutableStateOf(false) }

    LaunchedEffect(state) {
        if (state != currentState) {
            val alpha = transitionAlpha.value
            for (i in 0 until 5) {
                val prevP = getBarProperties(prevState, i, playTimeMs, successProgress, typingProgress)
                val currP = getBarProperties(currentState, i, playTimeMs, successProgress, typingProgress)
                
                val currentHeight = prevP.heightFraction + (currP.heightFraction - prevP.heightFraction) * alpha
                val currentYOffset = prevP.yOffsetFraction + (currP.yOffsetFraction - prevP.yOffsetFraction) * alpha
                val currentWidth = prevP.widthMultiplier + (currP.widthMultiplier - prevP.widthMultiplier) * alpha
                val currentXOffset = prevP.xOffsetFraction + (currP.xOffsetFraction - prevP.xOffsetFraction) * alpha
                
                transitionStartProps[i] = BarProperties(
                    heightFraction = currentHeight,
                    yOffsetFraction = currentYOffset,
                    widthMultiplier = currentWidth,
                    xOffsetFraction = currentXOffset
                )
            }
            useStoredStartProps = true
            prevState = currentState
            currentState = state
            transitionAlpha.snapTo(0f)
            transitionAlpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(400, easing = FastOutSlowInEasing) // Smooth state morphing
            )
            useStoredStartProps = false
        }
    }

    Canvas(modifier = modifier) {
        val barCount = 5
        val barWidth = size.width / 9f
        val maxHeight = size.height * 0.8f
        val centerY = size.height / 2f
        val alpha = transitionAlpha.value

        for (index in 0 until barCount) {
            val currProps = getBarProperties(currentState, index, playTimeMs, successProgress, typingProgress)
            val startProps = if (useStoredStartProps) {
                transitionStartProps[index]
            } else {
                getBarProperties(prevState, index, playTimeMs, successProgress, typingProgress)
            }

            // Smooth linear interpolation (morphing) of vector values
            val heightFraction = startProps.heightFraction + (currProps.heightFraction - startProps.heightFraction) * alpha
            val yOffsetFraction = startProps.yOffsetFraction + (currProps.yOffsetFraction - startProps.yOffsetFraction) * alpha
            val widthMultiplier = startProps.widthMultiplier + (currProps.widthMultiplier - startProps.widthMultiplier) * alpha
            val xOffsetFraction = startProps.xOffsetFraction + (currProps.xOffsetFraction - startProps.xOffsetFraction) * alpha

            val baseLeft = index * barWidth * 2f
            val targetLeft = baseLeft + (xOffsetFraction * barWidth)
            val targetWidth = widthMultiplier * barWidth
            val targetHeight = heightFraction * maxHeight

            // Safeguard height to prevent capsule compression to circles or corner inversion (minimum 1.25x width ratio)
            val minHeight = targetWidth * 1.25f
            val finalHeight = maxOf(targetHeight, minHeight)
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

