package com.example.smarty.features.chat.ui

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.smarty.core.domain.model.ClarificationRequest
import com.example.smarty.features.breathing.GuidedBreathingContent
import com.example.smarty.features.chat.domain.AssistViewModel
import com.example.smarty.features.voice.rememberSpeechToText
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.components.SmartyInputField
import com.example.smarty.ui.components.UnifiedDragHandle
import kotlinx.coroutines.launch

/**
 * AI Assistant Bottom Sheet Overlay.
 *
 * Design:
 * - Card-like bottom sheet with margins (left, right, bottom)
 * - Adapts to app theme (light/dark)
 * - Starts FRESH every time (no previous chats)
 * - Auto-starts Google Speech Recognizer
 * - Proper contrast for both light and dark themes
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistOverlayScreen(
    viewModel: AssistViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    // Theme-aware colors
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val backgroundColor = if (isDark) Color(0xFF1A1C1E) else Color(0xFFFFFFFF)
    val textColor = if (isDark) Color(0xFFFFFFFF) else Color(0xFF1A1C1E)
    val borderColor = if (isDark) Color(0xFF3C3C45) else Color(0xFFE0E0E0)

    // Input text state
    var inputText by remember { mutableStateOf(TextFieldValue("")) }

    // Voice Input tracking states
    var lastPartialText by remember { mutableStateOf("") }
    var partialTextStartIndex by remember { mutableIntStateOf(0) }
    var hadSpeechInput by remember { mutableStateOf(false) }

    // Voice Input State (Speech-to-Text)
    val speechState =
        rememberSpeechToText(
            onResult = { result ->
                if (result.isNotBlank()) {
                    hadSpeechInput = true
                    val currentText = inputText.text
                    val baseText =
                        if (lastPartialText.isNotEmpty()) {
                            val safeIndex = partialTextStartIndex.coerceAtMost(currentText.length)
                            currentText.take(safeIndex)
                        } else {
                            val spacer = if (currentText.isNotEmpty() && !currentText.endsWith(" ")) " " else ""
                            currentText + spacer
                        }

                    val newText = baseText + result
                    inputText = TextFieldValue(newText, TextRange(newText.length))
                    lastPartialText = ""
                    partialTextStartIndex = 0
                }
            },
            onError = { error ->
                Log.e("AssistOverlay", "Speech error: $error")
                viewModel.setListening(false)
            },
        )

    // Handle partial results for progressive text append
    LaunchedEffect(speechState) {
        speechState.onPartialResult = { partialText ->
            if (partialText.isNotBlank()) {
                hadSpeechInput = true
                val currentText = inputText.text

                if (lastPartialText.isEmpty()) {
                    partialTextStartIndex = currentText.length
                    if (currentText.isNotEmpty() && !currentText.endsWith(" ")) {
                        val spacedText = "$currentText "
                        partialTextStartIndex = spacedText.length
                        inputText = TextFieldValue(spacedText, TextRange(spacedText.length))
                    }
                }

                val safeStartIndex = partialTextStartIndex.coerceAtMost(inputText.text.length)
                val baseText = inputText.text.take(safeStartIndex)
                val newText = baseText + partialText
                inputText = TextFieldValue(newText, TextRange(newText.length))
                lastPartialText = partialText
            }
        }
    }

    var autoSendActive by remember { mutableStateOf(false) }
    var autoSendJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    // Auto-send 0.7s after speech stops
    LaunchedEffect(speechState.isListening) {
        if (!speechState.isListening && hadSpeechInput) {
            autoSendActive = true
            autoSendJob?.cancel()
            autoSendJob = scope.launch {
                kotlinx.coroutines.delay(700)
                if (autoSendActive) {
                    val finalChatText = inputText.text
                    if (finalChatText.isNotBlank()) {
                        Log.d("AssistOverlay", "Speech result received, sending message")
                        if (viewModel.isImageGenMode.value) {
                            viewModel.generateImageDirect(finalChatText)
                        } else {
                            viewModel.sendMessage(finalChatText)
                        }
                        inputText = TextFieldValue("")
                        autoSendActive = false
                        hadSpeechInput = false
                        lastPartialText = ""
                        partialTextStartIndex = 0
                    }
                }
            }
        } else if (speechState.isListening) {
            autoSendActive = false
            autoSendJob?.cancel()
        }
    }

    // Sync ViewModel listening state with SpeechToTextState
    LaunchedEffect(speechState.isListening) {
        viewModel.setListening(speechState.isListening)
    }

    // Preserve message history between overlay opens — don't clear on open.
    // Removed: LaunchedEffect(Unit) { viewModel.clearMessages() } — was destroying multi-turn context.

    // Permission launcher for voice
    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { isGranted ->
            if (isGranted) {
                // Start listening immediately after permission granted
                speechState.startListening(isChatMode = true)
            }
        }

    // Auto-start voice listening when assistant activates
    LaunchedEffect(Unit) {
        // Prevent keyboard from automatically showing up
        focusManager.clearFocus()

        // Request permission and start listening automatically
        when (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)) {
            PackageManager.PERMISSION_GRANTED -> {
                speechState.startListening(isChatMode = true)
            }
            else -> {
                // Auto-request permission
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    val showBreathing by viewModel.showBreathingOverlay.collectAsState()

    // Bottom sheet state
    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        isVisible = true
    }

    // Dismiss with cleanup
    fun handleDismiss() {
        isVisible = false
        onDismiss()
    }

    // Main bottom sheet UI
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) { handleDismiss() },
    ) {
        AnimatedVisibility(
            visible = isVisible,
            enter =
                slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = spring(dampingRatio = 0.8f, stiffness = 300f),
                ),
            exit =
                slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(200),
                ),
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .imePadding()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { /* Consume */ },
        ) {
            // Collect ViewModel states
            val vmImageGenMode by viewModel.isImageGenMode.collectAsState()
            val selectedModel by viewModel.selectedModel.collectAsState()
            val availableModels by viewModel.availableModels.collectAsState()
            val modelVariantMap by viewModel.modelVariantMap.collectAsState()
            val selectedVariant by viewModel.selectedVariant.collectAsState()
            val pendingApproval by viewModel.pendingApprovalState.collectAsState()

            // Card-like container
            Surface(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                shape = RoundedCornerShape(28.dp),
                color = backgroundColor,
                border = BorderStroke(1.dp, borderColor),
                shadowElevation = 16.dp,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    // Header with title and close button
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "AI Assistant",
                            style = MaterialTheme.typography.titleMedium,
                            color = textColor,
                            fontWeight = FontWeight.SemiBold,
                        )
                        IconButton(
                            onClick = { handleDismiss() },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = textColor.copy(alpha = 0.7f),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }

                    // Content area - FRESH chat every time
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp, horizontal = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = LocalAccentColor.current,
                            modifier = Modifier.size(32.dp),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "How can I help you?",
                            style = MaterialTheme.typography.titleMedium,
                            color = textColor,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Speak or type your question",
                            style = MaterialTheme.typography.bodySmall,
                            color = textColor.copy(alpha = 0.6f),
                        )

                        // Voice listening indicator
                        if (speechState.isListening) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = LocalAccentColor.current,
                                    strokeWidth = 2.dp,
                                )
                                Text(
                                    text = "Listening...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = LocalAccentColor.current,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }

                    // Check for active Ask User approval
                    val approval = pendingApproval
                    val parsedRequests = mutableListOf<ClarificationRequest>()
                    var activeApprovalId: String? = null

                    if (approval != null && (approval.toolName == "ask_user" || approval.toolName == "askuser")) {
                        activeApprovalId = approval.toolId
                        try {
                            val json = org.json.JSONObject(approval.toolArgs)
                            val questionsArray = json.optJSONArray("questions")
                            if (questionsArray != null && questionsArray.length() > 0) {
                                for (i in 0 until questionsArray.length()) {
                                    val qObj = questionsArray.getJSONObject(i)
                                    val qText = qObj.optString("question", "Please provide input:")
                                    val qAllowCustom = qObj.optBoolean("allow_custom", true)
                                    val qOptions = mutableListOf<String>()
                                    val optArr = qObj.optJSONArray("options")
                                    if (optArr != null) {
                                        for (j in 0 until optArr.length()) {
                                            qOptions.add(optArr.getString(j))
                                        }
                                    }
                                    parsedRequests.add(ClarificationRequest(qText, qOptions, qAllowCustom))
                                }
                            } else {
                                val qText = json.optString("question", "Please provide input:")
                                val qAllowCustom = json.optBoolean("allow_custom", true)
                                val qOptions = mutableListOf<String>()
                                val optArr = json.optJSONArray("options")
                                if (optArr != null) {
                                    for (j in 0 until optArr.length()) {
                                        qOptions.add(optArr.getString(j))
                                    }
                                }
                                parsedRequests.add(ClarificationRequest(qText, qOptions, qAllowCustom))
                            }
                        } catch (e: Exception) {
                            if (parsedRequests.isEmpty()) {
                                parsedRequests.add(ClarificationRequest("Please provide input:", emptyList(), true))
                            }
                        }
                    }

                    SmartyInputField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            onSubmit = {
                                if (inputText.text.isNotBlank()) {
                                    if (vmImageGenMode) {
                                        viewModel.generateImageDirect(inputText.text)
                                    } else {
                                        viewModel.sendMessage(inputText.text)
                                    }
                                    inputText = TextFieldValue("")
                                    focusManager.clearFocus()
                                }
                            },
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                            pendingQuestions = parsedRequests,
                            onQuestionAnswered = { response ->
                                if (activeApprovalId != null) {
                                    viewModel.callApproval(activeApprovalId, response.isNotEmpty(), response.ifEmpty { null })
                                }
                            },
                            isChatMode = true,
                            chatPlaceholder = "Ask anything...",
                            isVoiceListening = speechState.isListening,
                            isProcessing = viewModel.isProcessing.collectAsState().value,
                            isAgentWorking = viewModel.isProcessing.collectAsState().value,
                            onStopGeneration = { viewModel.stopGeneration() },
                            onStartVoiceInput = {
                                if (androidx.core.content.ContextCompat
                                        .checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                                    PackageManager.PERMISSION_GRANTED
                                ) {
                                    speechState.startListening(isChatMode = true)
                                } else {
                                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                            onStopVoiceInput = { speechState.stopListening() },
                            isImageGenMode = vmImageGenMode,
                            onToggleImageGenMode = { viewModel.toggleImageGenMode() },
                            onPickFile = { },
                            onOpenCamera = { },
                            showHistoryOption = false,
                            selectedModel = selectedModel,
                            availableModels = availableModels,
                            onModelSelected = { viewModel.selectModel(it) },
                            modelVariantMap = modelVariantMap,
                            selectedVariant = selectedVariant,
                            onVariantSelected = { viewModel.selectVariant(it) },
                            onRefreshModels = { viewModel.refreshModelsNow() },
                        )
                }
            }
        }

        // Breathing overlay
        if (showBreathing) {
            ModalBottomSheet(
                onDismissRequest = { viewModel.dismissBreathing() },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground,
                dragHandle = { UnifiedDragHandle() },
                shape = RoundedCornerShape(topStart = 38.dp, topEnd = 38.dp),
            ) {
                GuidedBreathingContent(onClose = { viewModel.dismissBreathing() })
            }
        }
    }
}
