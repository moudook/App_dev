package com.example.smarty.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HighlightOff
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.material.icons.automirrored.filled.Send
import com.example.smarty.R
import androidx.core.content.ContextCompat
import com.example.smarty.data.model.ChatMessage
import com.example.smarty.data.model.ChatRole
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.viewmodel.AssistViewModel
import java.util.Locale

/**
 * Gemini-style Assistant Overlay
 *
 * - Fully transparent background (shows underlying app/screen)
 * - Bouncy entry animation with blue/purple glow
 * - Compact floating input bar at bottom
 * - Expandable response card
 */
@Composable
fun AssistOverlayScreen(
    viewModel: AssistViewModel,
    onDismiss: () -> Unit
) {
    val messages by viewModel.messages.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()
    val isListening by viewModel.isListening.collectAsState()
    val accentColor = LocalAccentColor.current
    val context = LocalContext.current

    // Entry animation state
    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { isVisible = true }

    // Expanded state
    var isExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) isExpanded = true
    }

    // Speech recognizer
    var speechRecognizerInstance by remember { mutableStateOf<SpeechRecognizer?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startSpeechRecognition(
                context = context,
                speechRecognizerInstance = speechRecognizerInstance,
                onResult = { text ->
                    viewModel.setListening(false)
                    if (text.isNotBlank()) viewModel.sendMessage(text)
                },
                onListening = { viewModel.setListening(true) },
                onError = { viewModel.setListening(false) }
            )
        }
    }

    DisposableEffect(Unit) {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizerInstance = SpeechRecognizer.createSpeechRecognizer(context)
        }
        onDispose { speechRecognizerInstance?.destroy() }
    }

    // FULLY TRANSPARENT - no background color at all
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onDismiss() }
    ) {
        // Bottom-aligned overlay with bouncy animation
        AnimatedVisibility(
            visible = isVisible,
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                )
            ) + fadeIn(animationSpec = tween(200)),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = tween(150)
            ) + fadeOut(animationSpec = tween(100)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 16.dp)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { /* Consume clicks */ },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Response card (expandable)
                AnimatedVisibility(
                    visible = isExpanded && messages.isNotEmpty(),
                    enter = expandVertically(
                        expandFrom = Alignment.Bottom,
                        animationSpec = spring(dampingRatio = 0.7f, stiffness = 300f)
                    ) + fadeIn(),
                    exit = shrinkVertically(
                        shrinkTowards = Alignment.Bottom,
                        animationSpec = tween(200)
                    ) + fadeOut()
                ) {
                    ResponseCard(
                        messages = messages,
                        isProcessing = isProcessing,
                        accentColor = accentColor,
                        onCollapse = { isExpanded = false },
                        onNewChat = {
                            viewModel.clearMessages()
                            isExpanded = false
                        }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Input bar
                GlowingInputBar(
                    onSend = { viewModel.sendMessage(it) },
                    onVoice = {
                        if (ContextCompat.checkSelfPermission(
                                context, Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            if (isListening) {
                                speechRecognizerInstance?.stopListening()
                                viewModel.setListening(false)
                            } else {
                                startSpeechRecognition(
                                    context = context,
                                    speechRecognizerInstance = speechRecognizerInstance,
                                    onResult = { text ->
                                        viewModel.setListening(false)
                                        if (text.isNotBlank()) viewModel.sendMessage(text)
                                    },
                                    onListening = { viewModel.setListening(true) },
                                    onError = { viewModel.setListening(false) }
                                )
                            }
                        } else {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onDismiss = onDismiss,
                    isProcessing = isProcessing,
                    isListening = isListening,
                    accentColor = accentColor
                )
            }
        }
    }
}

/**
 * Glowing input bar with animated border
 */
@Composable
private fun GlowingInputBar(
    onSend: (String) -> Unit,
    onVoice: () -> Unit,
    onDismiss: () -> Unit,
    isProcessing: Boolean,
    isListening: Boolean,
    accentColor: Color
) {
    var text by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current

    // Animated glow effect
    val infiniteTransition = rememberInfiniteTransition(label = "glow")
    val glowProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "glowProgress"
    )

    // Glow colors for gradient border (Calm system colors)
    val accentColorNormalized = LocalAccentColor.current
    val gradientColors = listOf(
        accentColorNormalized.copy(alpha = 0.8f),
        accentColorNormalized.copy(alpha = 0.4f),
        accentColorNormalized,
        accentColorNormalized.copy(alpha = 0.4f),
        accentColorNormalized.copy(alpha = 0.8f)
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                // Draw animated gradient border (Gemini glow effect)
                val strokeWidth = 3.dp.toPx()
                val cornerRadius = 28.dp.toPx()

                // Create rotating gradient brush for a subtle, calm glow
                val brush = Brush.sweepGradient(
                    colors = gradientColors,
                    center = Offset(
                        size.width / 2 + (size.width * 0.2f * kotlin.math.cos(glowProgress * 2 * Math.PI)).toFloat(),
                        size.height / 2 + (size.height * 0.2f * kotlin.math.sin(glowProgress * 2 * Math.PI)).toFloat()
                    )
                )

                drawRoundRect(
                    brush = brush,
                    cornerRadius = CornerRadius(cornerRadius),
                    style = Stroke(width = strokeWidth),
                    alpha = if (isListening || isProcessing) 0.6f else 0.2f
                )
            },
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 16.dp,
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Close button
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.close),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Voice button (moved to left of input)
            IconButton(
                onClick = onVoice,
                modifier = Modifier
                    .size(40.dp)
                    .then(
                        if (isListening) Modifier.background(
                            accentColor.copy(alpha = 0.15f),
                            CircleShape
                        ) else Modifier
                    )
            ) {
                Icon(
                    imageVector = if (isListening) Icons.Default.StopCircle else Icons.Default.Mic, // Standard Mic icon
                    contentDescription = if (isListening) stringResource(R.string.cancel) else stringResource(R.string.widget_voice_note),
                    tint = if (isListening) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }

            // Text input
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(accentColor),
                    enabled = !isProcessing && !isListening,
                    singleLine = true,
                    decorationBox = { innerTextField ->
                    Text(
                        text = if (isListening) stringResource(R.string.listening) else stringResource(R.string.ask_jarvis),
                        color = if (isListening) accentColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        fontSize = 16.sp,
                        letterSpacing = 0.2.sp,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                        innerTextField()
                    }
                )
            }

            // Send button (on the right)
            AnimatedContent(
                targetState = text.isNotBlank() && !isProcessing,
                transitionSpec = {
                    scaleIn(tween(100)) + fadeIn() togetherWith scaleOut(tween(100)) + fadeOut()
                },
                label = "actionButton"
            ) { showSend ->
                if (showSend) {
                    IconButton(
                        onClick = {
                            if (text.isNotBlank()) {
                                onSend(text)
                                text = ""
                                keyboardController?.hide()
                            }
                        },
                        modifier = Modifier
                            .size(40.dp)
                            .background(
                                color = LocalAccentColor.current,
                                shape = CircleShape
                            )
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.share),
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                } else if (isProcessing) {
                    com.example.smarty.ui.components.CalmThinkingDots(
                        modifier = Modifier.padding(12.dp),
                        dotSize = 4.dp
                    )
                } else {
                    // Empty space to maintain consistent layout when no send button
                    Box(modifier = Modifier.size(40.dp)) {
                        // Empty - just for spacing consistency
                    }
                }
            }
        }
    }
}

/**
 * Response card with messages
 */
@Composable
private fun ResponseCard(
    messages: List<ChatMessage>,
    isProcessing: Boolean,
    accentColor: Color,
    onCollapse: () -> Unit,
    onNewChat: () -> Unit
) {
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 350.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
        tonalElevation = 2.dp
    ) {
        Column {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // assistant icon
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(
                                color = accentColor,
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Assistant,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Text(
                        text = stringResource(R.string.assistant),
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                    IconButton(onClick = onNewChat, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.Add, stringResource(R.string.new_chat), Modifier.size(18.dp), // Standard Add icon
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onCollapse, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.ExpandMore, stringResource(R.string.minimize), Modifier.size(20.dp), // Standard ExpandMore icon
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.08f))

            // Messages
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    MessageBubble(message = message, accentColor = accentColor)
                }
            }

            // Processing indicator
            if (isProcessing) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ThinkingDots(accentColor = accentColor)
                    Text(
                        text = stringResource(R.string.thinking),
                        style = MaterialTheme.typography.bodySmall.copy(letterSpacing = 0.5.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, accentColor: Color) {
    val isUser = message.role == ChatRole.USER

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp, topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            color = if (isUser) accentColor.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Text(
                text = message.content,
                style = MaterialTheme.typography.bodyMedium,
                color = if (message.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }

        if (message.hasCitations) {
            Spacer(Modifier.height(4.dp))
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = accentColor.copy(alpha = 0.1f)
            ) {
                Text(
                    text = stringResource(R.string.sources_count, message.citations.size),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    ),
                    color = accentColor,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
    }
}

@Composable
private fun ThinkingDots(accentColor: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(3) { i ->
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.3f, targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(500, easing = FastOutSlowInEasing),
                    RepeatMode.Reverse, StartOffset(i * 150)
                ), label = "dot$i"
            )
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = alpha))
            )
        }
    }
}

private fun startSpeechRecognition(
    context: android.content.Context,
    speechRecognizerInstance: SpeechRecognizer?,
    onResult: (String) -> Unit,
    onListening: () -> Unit,
    onError: () -> Unit
) {
    if (speechRecognizerInstance == null) { onError(); return }

    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
    }

    speechRecognizerInstance.setRecognitionListener(object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { onListening() }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onError(error: Int) { Log.e("Assist", "Speech error: $error"); onError() }
        override fun onResults(results: Bundle?) {
            onResult(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: "")
        }
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    })

    try { speechRecognizerInstance.startListening(intent) }
    catch (e: Exception) { Log.e("Assist", "Speech start error: ${e.message}"); onError() }
}



