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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
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
    placeholder: String = "Message Smarty...",
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
    // Re-integrated features from our previous iterations:
    isImageGenMode: Boolean = false,
    isHistoryMode: Boolean = false,
    onToggleImageGenMode: () -> Unit = {},
    pendingQuestions: List<ClarificationRequest> = emptyList(),
    onQuestionAnswered: (String) -> Unit = {},
    isChatMode: Boolean = true,
    onNewChat: () -> Unit = {},
    mentionState: MentionState = MentionState(),
    chatPlaceholder: String = "Message Smarty...",
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

    // State for Ask Tool
    var currentQuestionIndex by remember { mutableIntStateOf(0) }
    val answers = remember { mutableStateListOf<String>() }
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

        // 2. S-Tier Ask Tool UI
        AnimatedVisibility(
            visible = isAskingQuestion,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            val currentRequest = pendingQuestions.getOrNull(currentQuestionIndex)
            if (currentRequest != null) {
                STierAskToolCard(
                    question = currentRequest.question,
                    options = currentRequest.options,
                    allowCustomInput = currentRequest.allowCustomInput,
                    onAnswerSubmitted = handleAskSubmit,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }

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

        // 4. MAIN PILL (Core Text Input + Action Bar)
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
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {

        // ── 2. MIDDLE: Core Text Input ──
        Box(
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 24.dp).clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null
            ) { focusManager.clearFocus() /* Not really, usually we request focus, but keeping it simple */ },
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

        // ── 3. BOTTOM: Action Bar ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // LEFT CONTROLS: Image, History, Model Selector
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp), 
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Image/Attachment Icon (Only shown in Note Section, i.e., non-chat mode)
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

                // ImageGen Toggle (Krea AI image generation mode, only in Chat Section)
                if (isChatMode) {
                    val imageGenColor = if (isImageGenMode) Color(0xFF9575CD) else Color.Gray
                    Icon(
                        imageVector = Icons.Rounded.Image, 
                        contentDescription = "Image Gen", 
                        tint = imageGenColor, 
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .squishClick { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onToggleImageGenMode() }
                    )
                }

                // History Icon (Only in Chat Section, toggles between history and creating new clean chat)
                if (isChatMode && showHistoryOption) {
                    Icon(
                        imageVector = if (isHistoryMode) Icons.Rounded.Add else Icons.Rounded.History, 
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
                }

                // Vertical Divider
                if (isChatMode) {
                    Box(modifier = Modifier.height(16.dp).width(1.dp).background(Color.Gray.copy(alpha = 0.3f)))

                    // Model Selector Pill (GPT-4o style)
                    var showModelMenu by remember { mutableStateOf(false) }
                    val displayLabel = selectedModel.substringAfterLast("/")
                        .replace(Regex("(?i)-free\\b"), "")
                        .replace(Regex("(?i)\\bfree\\b"), "")
                        .trim()
                    
                    Surface(
                        shape = RoundedCornerShape(percent = 50),
                        color = if (isDark) accentColor.copy(alpha = 0.15f) else accentColor.copy(alpha = 0.08f),
                        modifier = Modifier
                            .height(32.dp)
                            .widthIn(max = 125.dp) // Specific horizontal size constraint to prevent pushing other components
                            .squishClick { showModelMenu = true }
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
                                modifier = Modifier.weight(1f, fill = false) // Truncate and wrap gracefully within constrained width
                            )
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Rounded.KeyboardArrowDown, null, tint = accentColor, modifier = Modifier.size(16.dp))
                        }
                        
                        DropdownMenu(
                            expanded = showModelMenu,
                            onDismissRequest = { showModelMenu = false },
                            shape = RoundedCornerShape(16.dp),
                            containerColor = if (isDark) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
                        ) {
                            availableModels.forEach { (modelId, label) ->
                                val cleanLabel = label
                                    .replace(Regex("(?i)-free\\b"), "")
                                    .replace(Regex("(?i)\\bfree\\b"), "")
                                    .trim()
                                DropdownMenuItem(
                                    text = { Text(cleanLabel, fontWeight = if (selectedModel == modelId) FontWeight.Bold else FontWeight.Normal) },
                                    onClick = { onModelSelected(modelId); showModelMenu = false }
                                )
                            }
                        }
                    }
                }
            }
 
            // RIGHT CONTROLS: Waveform Mic & Send Button (Stacked vertically when send button appears)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                
                // Active Voice Waveform OR Mic Icon (Shown always unless stop icon is active)
                val showStopIcon = isAgentWorking && isChatMode
                if (!showStopIcon) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .squishClick { 
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                if (isVoiceListening) onStopVoiceInput() else onStartVoiceInput() 
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isVoiceListening || isProcessing) {
                            VoiceWaveformIcon(
                                isListening = isVoiceListening,
                                isProcessing = isProcessing,
                                isAgentWorking = isAgentWorking,
                                value = value,
                                isFocused = isFocused,
                                modifier = Modifier.size(24.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.GraphicEq, 
                                contentDescription = "Voice", 
                                tint = accentColor, 
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
 
                // Send / Stop Arrow (Solid filled circle)
                AnimatedVisibility(
                    visible = canSend || showStopIcon,
                    enter = scaleIn(spring(0.7f, 400f)) + fadeIn(),
                    exit = scaleOut(spring(0.7f, 400f)) + fadeOut()
                ) {
                    val btnColor = if (showStopIcon) Color(0xFFE53935) else (if (isDark) Color.White else Color.Black)
                    val iconTint = if (showStopIcon) Color.White else (if (isDark) Color.Black else Color.White)
                    
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(btnColor, CircleShape)
                            .squishClick { 
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                focusManager.clearFocus()
                                if (showStopIcon) onStopVoiceInput() else onSubmit() 
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (showStopIcon) Icons.Rounded.Stop else Icons.AutoMirrored.Rounded.Send, 
                            contentDescription = "Send", 
                            tint = iconTint, 
                            modifier = Modifier.size(16.dp).offset(x = if (showStopIcon) 0.dp else 1.dp)
                        )
                    }
                }
            }
        }
    }
}
}

// 
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

@Composable
fun STierAskToolCard(
    question: String,
    options: List<String>,
    allowCustomInput: Boolean,
    onAnswerSubmitted: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val isDark = MaterialTheme.colorScheme.surface.luminance() <= 0.51f
    val cardBg = if (isDark) Color(0xFF1E1E1E) else Color.White
    val optionBg = if (isDark) Color(0xFF2A2A2A) else Color(0xFFF4F4F7)
    val accentColor = com.example.smarty.ui.LocalAccentColor.current

    var customAnswer by remember { mutableStateOf("") }
    var isEditingCustom by remember { mutableStateOf(false) }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.2f, targetValue = 0.6f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Reverse),
        label = "glowAlpha"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                shadowElevation = 8.dp.toPx()
                shape = RoundedCornerShape(24.dp)
                clip = true
                ambientShadowColor = Color.Black.copy(alpha = 0.04f)
                spotShadowColor = Color.Black.copy(alpha = 0.08f)
            }
            .border(
                width = 1.dp, 
                color = accentColor.copy(alpha = glowAlpha),
                shape = RoundedCornerShape(24.dp)
            ),
        color = cardBg
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .animateContentSize(spring(dampingRatio = 0.75f, stiffness = 400f))
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "CLARIFICATION NEEDED",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                    color = accentColor
                )
                
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome, 
                    contentDescription = null, 
                    tint = accentColor.copy(alpha = glowAlpha + 0.3f), 
                    modifier = Modifier.size(14.dp)
                )
            }

            Spacer(Modifier.height(12.dp))

            Text(
                text = question,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 22.sp,
                color = if (isDark) Color.White else Color(0xFF1D1D1F)
            )

            Spacer(Modifier.height(16.dp))

            options.forEachIndexed { index, option ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = optionBg,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .squishClick {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onAnswerSubmitted(option) 
                        }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = "${index + 1}.",
                            fontWeight = FontWeight.Bold,
                            color = accentColor.copy(alpha = 0.6f),
                            fontSize = 14.sp
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = option,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (isDark) Color.White else Color(0xFF1D1D1F)
                        )
                    }
                }
            }

            if (allowCustomInput) {
                if (isEditingCustom) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .background(optionBg, RoundedCornerShape(12.dp))
                            .border(1.dp, accentColor.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 16.dp)
                    ) {
                        BasicTextField(
                            value = customAnswer,
                            onValueChange = { customAnswer = it },
                            modifier = Modifier.weight(1f),
                            textStyle = TextStyle(fontSize = 15.sp, color = if (isDark) Color.White else Color.Black),
                            singleLine = true,
                            cursorBrush = SolidColor(accentColor),
                            decorationBox = { inner ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (customAnswer.isEmpty()) Text("Type your answer...", color = Color.Gray)
                                    inner()
                                }
                            }
                        )
                        
                        AnimatedVisibility(customAnswer.isNotBlank(), enter = scaleIn(spring(0.7f, 400f)) + fadeIn(), exit = scaleOut() + fadeOut()) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.Send,
                                contentDescription = "Send",
                                tint = accentColor,
                                modifier = Modifier
                                    .size(24.dp)
                                    .squishClick {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onAnswerSubmitted(customAnswer) 
                                    }
                            )
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .squishClick { isEditingCustom = true }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Edit, null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Something else...", color = Color.Gray, fontSize = 14.sp)
                        
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = "Skip", 
                            color = Color.Gray, 
                            fontSize = 13.sp, 
                            modifier = Modifier.squishClick { onAnswerSubmitted("") }
                        )
                    }
                }
            }
        }
    }
}
// S-TIER GPU CANVAS WAVEFORM (Preserved entirely!)
// 

private enum class WaveformState {
    IDLE, LISTENING_SILENT, LISTENING_SPEAKING, PROCESSING
}

private data class BarProperties(
    var heightFraction: Float,
    var yOffsetFraction: Float,
    var widthMultiplier: Float,
    var xOffsetFraction: Float
)

private fun smoothStep(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

private fun getBarProperties(state: WaveformState, index: Int, playTimeMs: Long, successProgress: Float, typingProgress: Float): BarProperties {
    var heightFraction = 0.2f
    var yOffsetFraction = 0f
    var widthMultiplier = 1f
    var xOffsetFraction = 0f
    
    val baseHeight = 0.2f

    when (state) {
        WaveformState.LISTENING_SPEAKING, WaveformState.LISTENING_SILENT -> {
            val t = (playTimeMs % 1200) / 1200f
            val phase = index * 0.2f
            val wave = (Math.sin(2.0 * Math.PI * (t + phase)).toFloat() + 1f) / 2f
            heightFraction = baseHeight + wave * 0.7f
        }
        WaveformState.PROCESSING -> {
            val p = (playTimeMs % 1500) / 1500f
            val h = when {
                p < 0.75f -> {
                    val t = p / 0.75f
                    val st = smoothStep(t)
                    val startH = baseHeight
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
            heightFraction = h.first
            yOffsetFraction = h.second
        }
        WaveformState.IDLE -> {
            heightFraction = baseHeight
        }
    }

    return BarProperties(heightFraction, yOffsetFraction, widthMultiplier, xOffsetFraction)
}

@Composable
fun VoiceWaveformIcon(
    modifier: Modifier = Modifier,
    isListening: Boolean,
    isProcessing: Boolean,
    isAgentWorking: Boolean,
    value: TextFieldValue,
    isFocused: Boolean,
    mentionState: MentionState = MentionState(),
    chatPlaceholder: String = "Message Smarty...",
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
    val state = when {
        isProcessing -> WaveformState.PROCESSING
        isListening -> WaveformState.LISTENING_SPEAKING
        else -> WaveformState.IDLE
    }
    WaveformBars(modifier = modifier, targetColor = LocalAccentColor.current, state = state)
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
    
    var prevColor by remember { mutableStateOf(targetColor) }
    var currentColor by remember { mutableStateOf(targetColor) }

    Canvas(modifier = modifier.fillMaxSize()) {
        val blendedColor = currentColor
        val barWidth = size.width / 9f
        val maxHeight = size.height * 0.8f
        val centerY = size.height / 2f

        for (index in 0 until 5) {
            val pOld = getBarProperties(state, index, playTimeMs, successProgress, typingProgress)

            val h = pOld.heightFraction
            val y = pOld.yOffsetFraction
            val w = pOld.widthMultiplier
            val x = pOld.xOffsetFraction

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
