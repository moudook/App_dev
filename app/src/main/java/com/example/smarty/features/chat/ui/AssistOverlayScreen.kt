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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.smarty.R
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.components.SmartyInputField
import com.example.smarty.features.notes.ui.inputstream.ChatModeContent
import com.example.smarty.ui.theme.softCardShadow
import com.example.smarty.features.chat.domain.AssistViewModel

/**
 * Modern "Soft Tech" Assistant Overlay.
 *
 * Design:
 * - Transparent background (scrim) that dismisses on tap
 * - Floating "Pill" container at the bottom
 * - Matches the aesthetic of the main Chat UI
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
    var inputText by remember { mutableStateOf(TextFieldValue("")) }
    var isVisible by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Animation entry
    LaunchedEffect(Unit) {
        isVisible = true
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
        onDismiss()
    }

    // Transparent Scrim
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
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
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            // Floating Card Container
            SmartyContainer(
                modifier = Modifier
                    .clickable(enabled = false) {} // Consume clicks
            ) {
                // 1. Chat Content (if messages exist)
                if (messages.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .heightIn(max = 450.dp) // Limit height so it doesn't cover screen
                            .fillMaxWidth()
                    ) {
                        ChatModeContent(
                            chatMessages = messages,
                            chatListState = listState,
                            notes = emptyList(), // Overlay doesn't access full note db
                            onNoteClick = {},
                            onSendChatMessage = { text, _ -> viewModel.sendMessage(text) },
                            contentPadding = PaddingValues(16.dp),
                            isChatProcessing = isProcessing,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else {
                    // 2. Empty State
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (isProcessing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(48.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = stringResource(R.string.processing),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.how_can_i_help_you),
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                // 3. Input Field
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
                    chatPlaceholder = stringResource(R.string.ask_anything),
                    isVoiceListening = isListening,
                    isProcessing = isProcessing,
                    isAgentWorking = isProcessing,
                    onStartVoiceInput = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            viewModel.setListening(true)
                        } else {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onStopVoiceInput = {
                        viewModel.setListening(false)
                    },
                    onPickFile = {
                        // Overlay file picker support
                        // Not fully implemented for overlay in this scope, but needed for compilation
                    },
                    onOpenCamera = {
                        // Overlay camera support
                    },
                    showHistoryOption = false
                )
            }
        }
    }
}

@Composable
private fun SmartyContainer(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val density = LocalDensity.current
    // Handle keyboard/nav bar insets
    val bottomPadding = WindowInsets.ime.getBottom(density).dp.coerceAtLeast(WindowInsets.navigationBars.getBottom(density).dp)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp) // Slight floating margin
            .padding(bottom = bottomPadding + 4.dp) // Lift above keyboard - reduced for lower position
            .softCardShadow(shape = RoundedCornerShape(32.dp), elevation = 16.dp),
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surface, // Matches "Soft Tech" aesthetic
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
        ),
        tonalElevation = 8.dp
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            content = content
        )
    }
}
