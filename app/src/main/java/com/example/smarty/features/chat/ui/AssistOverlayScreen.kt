package com.example.smarty.features.chat.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.smarty.R
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.components.SmartyInputField
import com.example.smarty.features.notes.ui.inputstream.ChatModeContent
import com.example.smarty.ui.theme.softCardShadow
import com.example.smarty.ui.theme.LocalShapes
import com.example.smarty.ui.components.TechnicalSurface
import com.example.smarty.features.chat.domain.AssistViewModel
import kotlinx.coroutines.launch

/**
 * Modern "Soft Tech" AI Assistant Overlay.
 *
 * Design:
 * - OPAQUE background (no transparency issues)
 * - Floating "Pill" container at the bottom
 * - Proper keyboard inset handling
 * - Auto-starts voice listening on activation
 * - Matches main Chat UI aesthetic
 */
@Composable
fun AssistOverlayScreen(
    viewModel: AssistViewModel,
    onDismiss: () -> Unit
) {
    // State from ViewModel
    val messages by viewModel.messages.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()
    val isListening by viewModel.isListening.collectAsState()

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var inputText by remember { mutableStateOf(TextFieldValue("")) }
    var isVisible by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Auto-start voice listening when assistant activates
    LaunchedEffect(Unit) {
        isVisible = true
        // Auto-request permission and start listening
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) 
            == PackageManager.PERMISSION_GRANTED) {
            viewModel.setListening(true)
        }
    }

    // Permission launcher for voice
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.setListening(true)
        }
    }

    // Dismiss logic with animation
    fun handleDismiss() {
        isVisible = false
        // Stop listening when dismissing
        viewModel.setListening(false)
        onDismiss()
    }

    // Transparent Scrim
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))  // Dim background
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { handleDismiss() }
    ) {
        AnimatedVisibility(
            visible = isVisible,
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = spring(dampingRatio = 0.8f, stiffness = 300f)
            ),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = tween(200)
            ),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .imePadding()  // Proper keyboard inset handling
        ) {
            // Floating Card Container - OPAQUE background
            TechnicalSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = false) {} // Consume clicks
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // 1. Chat Content (if messages exist)
                    if (messages.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .heightIn(max = 300.dp) // Reduced height for better keyboard clearance
                                .fillMaxWidth()
                        ) {
                            ChatModeContent(
                                chatMessages = messages,
                                chatListState = listState,
                                notes = emptyList(),
                                onNoteClick = {},
                                onSendChatMessage = { text, _ -> viewModel.sendMessage(text) },
                                contentPadding = PaddingValues(16.dp),
                                isChatProcessing = isProcessing,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        HorizontalDivider(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                        )
                    } else {
                        // 2. Empty State with larger padding
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp, horizontal = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (isProcessing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(48.dp),
                                    color = LocalAccentColor.current
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "Processing...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            } else {
                                Text(
                                    text = "How can I help you today?",
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                                Text(
                                    text = "Tap microphone or type to start",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // 3. Input Field with proper keyboard handling
                    SmartyInputField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        onSubmit = {
                            if (inputText.text.isNotBlank()) {
                                viewModel.sendMessage(inputText.text)
                                inputText = TextFieldValue("")
                            }
                        },
                        modifier = Modifier.padding(16.dp),
                        isChatMode = true,
                        chatPlaceholder = "Ask anything...",
                        isVoiceListening = isListening,
                        isProcessing = isProcessing,
                        isAgentWorking = isProcessing,
                        onStopGeneration = { viewModel.stopGeneration() },
                        onStartVoiceInput = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) 
                                == PackageManager.PERMISSION_GRANTED) {
                                viewModel.setListening(true)
                            } else {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        onStopVoiceInput = {
                            viewModel.setListening(false)
                        },
                        onPickFile = { },
                        onOpenCamera = { },
                        showHistoryOption = false
                    )
                }
            }
        }
    }
}
