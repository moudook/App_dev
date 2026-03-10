package com.example.smarty.features.chat.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.components.SmartyInputField
import com.example.smarty.features.chat.domain.ChatViewModel
import com.example.smarty.features.chat.domain.event.ChatEvent

/**
 * AI Assistant Bottom Sheet Overlay - Refactored with SDE best practices.
 *
 * Principles applied:
 * - **Global State Management**: Uses ChatViewModel with ChatState/ChatUiState
 * - **Single Responsibility**: UI only handles presentation, ViewModel handles logic
 * - **DRY**: Reuses extracted components (SmartyInputField, ThinkingSection, etc.)
 * - **Event-driven**: Uses ChatEvent sealed class for UI events
 */
@Composable
fun AssistOverlayScreen(
    viewModel: ChatViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    
    // Collect global state - single source of truth
    val chatState by viewModel.chatState.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val isListening = uiState.isVoiceListening
    val isProcessing = chatState.isProcessing
    
    // Theme-aware colors
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val backgroundColor = if (isDark) Color(0xFF1A1C1E) else Color(0xFFFFFFFF)
    val textColor = if (isDark) Color(0xFFFFFFFF) else Color(0xFF1A1A1A)
    val borderColor = if (isDark) Color(0xFF3C3C45) else Color(0xFFE0E0E0)

    // Input text state - synced with UI state
    var inputText by remember { mutableStateOf(TextFieldValue("")) }

    // Permission launcher for voice
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.onEvent(ChatEvent.VoiceInputStarted)
        }
    }

    // Auto-start voice listening when assistant activates
    LaunchedEffect(Unit) {
        viewModel.clearMessages()
        when (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)) {
            PackageManager.PERMISSION_GRANTED -> {
                viewModel.setListening(true)
            }
            else -> {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    // Bottom sheet visibility state
    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        isVisible = true
    }

    // Dismiss with cleanup - single responsibility (ViewModel handles logic)
    fun handleDismiss() {
        isVisible = false
        viewModel.setListening(false)
        viewModel.clearMessages()
        onDismiss()
    }

    // Main bottom sheet UI
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
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
                .imePadding()
        ) {
            // Card-like container with MARGINS
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                shape = RoundedCornerShape(28.dp),
                color = backgroundColor,
                border = BorderStroke(1.dp, borderColor),
                shadowElevation = 16.dp
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Header with title and close button
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "AI Assistant",
                            style = MaterialTheme.typography.titleMedium,
                            color = textColor,
                            fontWeight = FontWeight.SemiBold
                        )
                        IconButton(
                            onClick = { handleDismiss() },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = textColor.copy(alpha = 0.7f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Content area - FRESH chat every time
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp, horizontal = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = LocalAccentColor.current,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "How can I help you today?",
                            style = MaterialTheme.typography.titleLarge,
                            color = textColor,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Speak or type your question",
                            style = MaterialTheme.typography.bodyMedium,
                            color = textColor.copy(alpha = 0.6f)
                        )

                        // Voice listening indicator
                        if (isListening) {
                            Spacer(modifier = Modifier.height(24.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = LocalAccentColor.current,
                                    strokeWidth = 2.dp
                                )
                                Text(
                                    text = "Listening...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = LocalAccentColor.current,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    // Input field at bottom - uses event-driven architecture
                    SmartyInputField(
                        value = inputText,
                        onValueChange = { 
                            inputText = it
                            viewModel.onEvent(ChatEvent.InputTextChanged(it))
                        },
                        onSubmit = {
                            if (inputText.text.isNotBlank()) {
                                // Event-driven: send message through ViewModel
                                viewModel.onEvent(ChatEvent.MessageSent(inputText.text))
                                inputText = TextFieldValue("")
                            }
                        },
                        modifier = Modifier.padding(16.dp),
                        isChatMode = true,
                        chatPlaceholder = "Ask anything...",
                        isVoiceListening = isListening,
                        isProcessing = isProcessing,
                        isAgentWorking = isProcessing,
                        onStopGeneration = { viewModel.onEvent(ChatEvent.GenerationStopped) },
                        onStartVoiceInput = {
                            when (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)) {
                                PackageManager.PERMISSION_GRANTED -> {
                                    viewModel.setListening(true)
                                }
                                else -> {
                                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        },
                        onStopVoiceInput = { viewModel.setListening(false) },
                        onPickFile = { },
                        onOpenCamera = { },
                        showHistoryOption = false
                    )
                }
            }
        }
    }
}
