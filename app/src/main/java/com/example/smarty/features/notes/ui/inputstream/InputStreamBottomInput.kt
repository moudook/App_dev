package com.example.smarty.features.notes.ui.inputstream

import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.smarty.core.domain.model.Attachment
import com.example.smarty.core.domain.model.ClarificationRequest
import com.example.smarty.core.domain.model.MentionState
import com.example.smarty.core.domain.model.MentionSuggestion
import com.example.smarty.features.notes.ui.SearchSuggestionsDropdown
import com.example.smarty.features.notes.ui.SelectionPillBar
import com.example.smarty.features.notes.ui.createImageFile
import com.example.smarty.features.voice.SpeechToTextState
import com.example.smarty.features.voice.VoiceNoteRecorder
import com.example.smarty.ui.components.AttachmentOption
import com.example.smarty.ui.components.SmartyInputField
import com.example.smarty.ui.theme.ComponentSpacing
import com.example.smarty.ui.theme.SmartyBrushes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun InputStreamBottomInput(
    isDarkTheme: Boolean,
    isChatMode: Boolean,
    isSearchMode: Boolean,
    showChatHistoryInline: Boolean,
    isSelectionMode: Boolean,
    selectedNoteIds: Set<String>,
    isMiniPlayerVisible: Boolean,
    bottomContentPadding: androidx.compose.ui.unit.Dp,
    bottomGradientVerticalOffset: androidx.compose.ui.unit.Dp,
    currentInputAttachments: List<Attachment>,
    onInputAttachmentsChange: (List<Attachment>) -> Unit,
    textValue: TextFieldValue,
    chatModeTextValue: TextFieldValue,
    normalModeTextValue: TextFieldValue,
    onNormalModeTextValueChange: (TextFieldValue) -> Unit,
    onChatModeTextValueChange: (TextFieldValue) -> Unit,
    onInputTextChange: (String) -> Unit,
    recentSearches: List<String>,
    onSearchQueryChange: (String) -> Unit,
    onRecordSearch: (String) -> Unit,
    onClearSearchHistory: () -> Unit,
    chatMessages: List<com.example.smarty.core.domain.model.ChatMessage>,
    pendingClarificationRequests: List<ClarificationRequest>,
    pendingApprovalToolId: String?,
    onCallApproval: (String, Boolean, String?) -> Unit,
    onClarificationSubmit: (String, String) -> Unit,
    speechState: SpeechToTextState,
    isChatProcessing: Boolean,
    voiceRecorder: VoiceNoteRecorder,
    isRecording: Boolean,
    autoSendActive: Boolean,
    hadSpeechInput: Boolean,
    onSetHadSpeechInput: (Boolean) -> Unit,
    autoSendJob: Job?,
    onSetAutoSendActive: (Boolean) -> Unit,
    onSetAutoSendJob: (Job?) -> Unit,
    chatListState: LazyListState,
    coroutineScope: CoroutineScope,
    mentionState: MentionState,
    onUpdateMentionState: (String, Int) -> Unit,
    onMentionSelected: (MentionSuggestion, String) -> String,
    isImageGenMode: Boolean,
    onSetImageGenMode: (Boolean) -> Unit,
    isAiExcluded: Boolean,
    selectedFilters: Set<AttachmentOption>,
    onFilterToggle: (AttachmentOption) -> Unit,
    onClearFilters: () -> Unit,
    selectedModel: String,
    availableModels: List<Pair<String, String>>,
    onModelSelected: (String) -> Unit,
    modelVariantMap: Map<String, List<String>>,
    selectedVariant: String?,
    onVariantSelected: (String?) -> Unit,
    onRefreshModels: suspend () -> List<Pair<String, String>>,
    showCalendarInline: Boolean,
    showStacksInline: Boolean,
    showArchiveInline: Boolean,
    showGamesInline: Boolean,
    userIsScrolling: Boolean,
    onSendChatMessage: (String, List<Attachment>) -> Unit,
    onAddNote: (String, List<Attachment>) -> Unit,
    onGenerateImageDirect: (String) -> Unit,
    onNewChatSession: () -> Unit,
    onSetShowChatHistoryInline: (Boolean) -> Unit,
    imagePickerLauncher: androidx.activity.result.ActivityResultLauncher<PickVisualMediaRequest>,
    videoPickerLauncher: androidx.activity.result.ActivityResultLauncher<PickVisualMediaRequest>,
    documentPickerLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>,
    audioPickerLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>,
    filePickerLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>,
    cameraLauncher: androidx.activity.result.ActivityResultLauncher<Uri>,
    cameraImageUri: Uri?,
    onSetCameraImageUri: (Uri?) -> Unit,
    onPinSelected: () -> Unit,
    onShareSelected: () -> Unit,
    onArchiveSelected: () -> Unit,
    onDeleteSelected: () -> Unit,
    onCategorizeSelected: () -> Unit,
    onClearSelection: () -> Unit,
    onToggleSearchMode: () -> Unit,
    context: Context,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val isKeyboardVisible = WindowInsets.ime.getBottom(density) > 0

    val showSelectionPill = isSelectionMode && selectedNoteIds.isNotEmpty()
    val miniPlayerHeight = ComponentSpacing.miniPlayerHeight
    val miniPlayerExtraMargin = 16.dp
    val miniPlayerPadding = if (isMiniPlayerVisible && !isKeyboardVisible) miniPlayerHeight + miniPlayerExtraMargin else 0.dp
    val attachmentCount = currentInputAttachments.size
    val attachmentRowHeight = if (attachmentCount > 0) 60.dp else 0.dp
    val multiLineExtraHeight = if (textValue.text.count { it == '\n' } > 0) 40.dp else 0.dp
    val inputFieldHeight = if (showSelectionPill) 0.dp else (72.dp + attachmentRowHeight + multiLineExtraHeight)
    val inputFieldPadding = ComponentSpacing.screenPadding
    val isSearchSuggestionsVisible = isSearchMode && textValue.text.isEmpty() && recentSearches.isNotEmpty()
    val searchSuggestionsHeight =
        if (isSearchSuggestionsVisible && !showSelectionPill) {
            when {
                isKeyboardVisible -> 100.dp
                isLandscape -> 120.dp
                else -> 200.dp
            }
        } else {
            0.dp
        }
    val extraBottomCoverage =
        when {
            isKeyboardVisible -> 10.dp
            isLandscape -> 20.dp
            else -> 40.dp
        }
    val gradientOffset =
        bottomGradientVerticalOffset +
            when {
                isKeyboardVisible -> (-10).dp
                isLandscape -> (-10).dp
                else -> 0.dp
            }
    val baseGradientHeight = inputFieldHeight + inputFieldPadding + searchSuggestionsHeight + extraBottomCoverage
    val targetGradientHeight = baseGradientHeight + miniPlayerPadding

    val animatedGradientHeight by animateDpAsState(
        targetValue = targetGradientHeight,
        animationSpec =
            spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
        label = "gradientHeightAnimation",
    )

    val animatedGradientOffset by animateDpAsState(
        targetValue = gradientOffset,
        animationSpec =
            spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
        label = "gradientOffsetAnimation",
    )

    val bottomGradientBrush =
        if (isDarkTheme) {
            SmartyBrushes.bottomScrimDark
        } else {
            SmartyBrushes.bottomScrimLight
        }

    val showInputBlock = !showCalendarInline && !showStacksInline && !showArchiveInline && !showGamesInline

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        androidx.compose.animation.AnimatedVisibility(
            visible = showInputBlock,
            enter =
                slideInVertically(
                    initialOffsetY = { it / 2 },
                    animationSpec = spring(dampingRatio = 0.85f, stiffness = 300f),
                ) + fadeIn(tween(250)),
            exit =
                slideOutVertically(
                    targetOffsetY = { it / 2 },
                    animationSpec = tween(200),
                ) + fadeOut(tween(200)),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = bottomContentPadding + miniPlayerPadding)
                    .navigationBarsPadding(),
        ) {
            val bottomScrollTransition =
                updateTransition(
                    targetState = userIsScrolling,
                    label = "BottomBarScroll",
                )

            val bottomBarAlpha by bottomScrollTransition.animateFloat(
                transitionSpec = {
                    if (targetState) {
                        tween(durationMillis = 200, delayMillis = 350, easing = androidx.compose.animation.core.LinearEasing)
                    } else {
                        tween(durationMillis = 200, easing = androidx.compose.animation.core.LinearOutSlowInEasing)
                    }
                },
                label = "bottomBarAlpha",
            ) { isScrolling -> if (isScrolling) 0f else 1f }

            val bottomBarTranslationY by bottomScrollTransition.animateFloat(
                transitionSpec = {
                    if (targetState) {
                        tween(durationMillis = 400, delayMillis = 350, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                    } else {
                        spring(dampingRatio = 0.75f, stiffness = 350f)
                    }
                },
                label = "bottomBarTranslationY",
            ) { isScrolling -> if (isScrolling) 250f else 0f }

            val bottomGradientTranslationY by bottomScrollTransition.animateFloat(
                transitionSpec = { spring(dampingRatio = 0.75f, stiffness = 100f) },
                label = "bottomGradientTranslationY",
            ) { isScrolling -> if (isScrolling) 120f else 0f }

            Box(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(animatedGradientHeight)
                            .offset(y = animatedGradientOffset)
                            .align(Alignment.BottomCenter)
                            .graphicsLayer { translationY = bottomGradientTranslationY }
                            .background(brush = bottomGradientBrush)
                            .zIndex(1f),
                )

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .graphicsLayer {
                                alpha = bottomBarAlpha
                                translationY = bottomBarTranslationY
                            }.padding(
                                start = 8.dp,
                                end = 8.dp,
                                bottom = ComponentSpacing.screenPadding,
                                top = 0.dp,
                            ).zIndex(2f),
                ) {
                    AnimatedVisibility(
                        visible = isSearchMode && textValue.text.isEmpty() && recentSearches.isNotEmpty(),
                        enter = fadeIn(tween(160)) + expandVertically(),
                        exit = fadeOut(tween(120)) + shrinkVertically(),
                    ) {
                        SearchSuggestionsDropdown(
                            suggestions = recentSearches.take(5),
                            onSuggestionClick = { suggestion ->
                                normalModeTextValue.let {
                                    onNormalModeTextValueChange(TextFieldValue(suggestion, TextRange(suggestion.length)))
                                    onSearchQueryChange(suggestion)
                                    onInputTextChange(suggestion)
                                    onRecordSearch(suggestion)
                                }
                            },
                            onClearHistory = onClearSearchHistory,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }

                    if (showSelectionPill) {
                        SelectionPillBar(
                            selectedCount = selectedNoteIds.size,
                            onPin = onPinSelected,
                            onShare = onShareSelected,
                            onArchive = onArchiveSelected,
                            onDelete = onDeleteSelected,
                            onCategorize = onCategorizeSelected,
                            onClose = onClearSelection,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }

                    val activeClarificationMessage =
                        if (isChatMode) {
                            chatMessages.find {
                                it.role == com.example.smarty.core.domain.model.ChatRole.SMARTY &&
                                    it.clarificationRequest != null &&
                                    !it.isStreaming
                            }
                        } else {
                            null
                        }

                    val pendingQuestions =
                        if (pendingClarificationRequests.isNotEmpty()) {
                            pendingClarificationRequests
                        } else {
                            activeClarificationMessage?.clarificationRequest?.let { listOf(it) } ?: emptyList()
                        }

                    if (!showSelectionPill) {
                        Box(contentAlignment = Alignment.BottomCenter) {
                            SmartyInputField(
                                value = textValue,
                                onValueChange = { newTextValue ->
                                    if (autoSendActive) {
                                        onSetAutoSendActive(false)
                                        autoSendJob?.cancel()
                                    }
                                    onSetHadSpeechInput(false)
                                    if (isChatMode) {
                                        onChatModeTextValueChange(newTextValue)
                                        onUpdateMentionState(newTextValue.text, newTextValue.selection.end)
                                    } else {
                                        onNormalModeTextValueChange(newTextValue)
                                        if (isSearchMode) {
                                            onSearchQueryChange(newTextValue.text)
                                        }
                                    }
                                    onInputTextChange(newTextValue.text)
                                },
                                onSubmit = {
                                    val actualText = if (isChatMode) chatModeTextValue.text else normalModeTextValue.text
                                    if (actualText.isNotBlank() || currentInputAttachments.isNotEmpty()) {
                                        if (isChatMode && isImageGenMode) {
                                            onGenerateImageDirect(actualText)
                                            onChatModeTextValueChange(TextFieldValue(""))
                                            onSetImageGenMode(false)
                                        } else if (isChatMode) {
                                            onSendChatMessage(actualText, currentInputAttachments)
                                            onChatModeTextValueChange(TextFieldValue(""))
                                        } else if (!isSearchMode) {
                                            onAddNote(actualText, currentInputAttachments)
                                            onNormalModeTextValueChange(TextFieldValue(""))
                                        }
                                        if (!isSearchMode) {
                                            onInputTextChange("")
                                            onInputAttachmentsChange(emptyList())
                                        }
                                    }
                                },
                                attachments = currentInputAttachments,
                                onPickImage = {
                                    imagePickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                    )
                                },
                                onPickVideo = {
                                    videoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
                                    )
                                },
                                onPickDocument = {
                                    documentPickerLauncher.launch(
                                        arrayOf(
                                            "application/pdf",
                                            "application/msword",
                                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                            "application/vnd.ms-excel",
                                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                            "application/vnd.ms-powerpoint",
                                            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                                            "text/plain",
                                            "text/csv",
                                            "application/rtf",
                                        ),
                                    )
                                },
                                onPickAudio = {
                                    audioPickerLauncher.launch(
                                        arrayOf(
                                            "audio/*",
                                            "audio/mpeg",
                                            "audio/mp4",
                                            "audio/wav",
                                            "audio/ogg",
                                            "audio/flac",
                                        ),
                                    )
                                },
                                onPickFile = {
                                    filePickerLauncher.launch(arrayOf("*/*"))
                                },
                                onOpenCamera = {
                                    createImageFile(context)?.let { uri ->
                                        onSetCameraImageUri(uri)
                                        cameraLauncher.launch(uri)
                                    }
                                },
                                onRemoveAttachment = { id -> onInputAttachmentsChange(currentInputAttachments.filter { it.id != id }) },
                                pendingQuestions = pendingQuestions,
                                onQuestionAnswered = { response ->
                                    if (pendingApprovalToolId != null) {
                                        onCallApproval(pendingApprovalToolId, true, response.ifEmpty { null })
                                    } else if (activeClarificationMessage != null) {
                                        onClarificationSubmit(activeClarificationMessage.id, response)
                                    }
                                },
                                isChatMode = isChatMode,
                                isHistoryMode = showChatHistoryInline,
                                isProcessing = isChatProcessing,
                                onClearInput = {
                                    if (isChatMode) {
                                        onChatModeTextValueChange(TextFieldValue(""))
                                    } else {
                                        onNormalModeTextValueChange(TextFieldValue(""))
                                    }
                                    onInputAttachmentsChange(emptyList())
                                    onInputTextChange("")
                                    if (isSearchMode) {
                                        onSearchQueryChange("")
                                    }
                                },
                                onOpenChatHistory = { onSetShowChatHistoryInline(true) },
                                onNewChat = {
                                    onNewChatSession()
                                    onSetShowChatHistoryInline(false)
                                },
                                isAiExcluded = isAiExcluded,
                                isSearchMode = isSearchMode,
                                onToggleSearch = {
                                    if (isSearchMode && normalModeTextValue.text.isNotBlank()) {
                                        onRecordSearch(normalModeTextValue.text)
                                    }
                                    onNormalModeTextValueChange(TextFieldValue(""))
                                    onInputTextChange("")
                                    onToggleSearchMode()
                                },
                                isVoiceListening = speechState.isListening,
                                onStartVoiceInput = {
                                    if (speechState.isListening) {
                                        speechState.stopListening()
                                    } else {
                                        speechState.startListening(isChatMode = isChatMode)
                                    }
                                },
                                onStopVoiceInput = { speechState.stopListening() },
                                isAgentWorking = isChatProcessing,
                                autoSendActive = autoSendActive,
                                selectedFilters = selectedFilters,
                                onFilterToggle = onFilterToggle,
                                onClearFilters = onClearFilters,
                                onStartRecording = {
                                    if (speechState.isListening) speechState.stopListening()
                                    voiceRecorder.startRecording()
                                },
                                onStopRecording = { voiceRecorder.stopRecording() },
                                isRecording = isRecording,
                                mentionState = mentionState,
                                onMentionSelected = { suggestion ->
                                    val updatedText = onMentionSelected(suggestion, chatModeTextValue.text)
                                    onChatModeTextValueChange(
                                        TextFieldValue(
                                            text = updatedText,
                                            selection = TextRange(updatedText.length),
                                        ),
                                    )
                                },
                                isImageGenMode = isImageGenMode,
                                onToggleImageGenMode = { onSetImageGenMode(!isImageGenMode) },
                                showScrollButton = chatListState.canScrollForward || chatListState.canScrollBackward,
                                isAtLatest = !chatListState.canScrollBackward,
                                onScrollToBottom = {
                                    coroutineScope.launch {
                                        val total = chatListState.layoutInfo.totalItemsCount
                                        if (total > 0) chatListState.animateScrollToItem(total - 1, scrollOffset = 10000)
                                    }
                                },
                                onScrollToTop = {
                                    coroutineScope.launch {
                                        chatListState.animateScrollToItem(0, scrollOffset = -10000)
                                    }
                                },
                                selectedModel = selectedModel,
                                availableModels = availableModels,
                                onModelSelected = onModelSelected,
                                modelVariantMap = modelVariantMap,
                                selectedVariant = selectedVariant,
                                onVariantSelected = onVariantSelected,
                                onRefreshModels = onRefreshModels,
                            )
                        }
                    }
                }
            }
        }
    }
}
