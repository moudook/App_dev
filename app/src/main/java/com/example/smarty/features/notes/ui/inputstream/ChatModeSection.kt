package com.example.smarty.features.notes.ui.inputstream

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import com.example.smarty.core.domain.model.Attachment
import com.example.smarty.core.domain.model.ChatSession
import com.example.smarty.core.domain.model.Note
import com.example.smarty.features.chat.domain.ChatFeatureManager

@Composable
fun ChatModeSection(
    showChatHistoryInline: Boolean,
    chatSessions: List<ChatSession>,
    currentSessionId: String?,
    isChatHistoryLoading: Boolean,
    chatMessages: List<com.example.smarty.core.domain.model.ChatMessage>,
    chatListState: LazyListState,
    notes: List<Note>,
    contentPaddingWithTop: PaddingValues,
    isChatProcessing: Boolean,
    agentActivity: ChatFeatureManager.AgentActivity?,
    onSwitchChatSession: (String) -> Unit,
    onNewChatSession: () -> Unit,
    onDeleteChatSession: (String) -> Unit,
    onNoteClick: (Note) -> Unit,
    onNoteClickById: (String) -> Unit,
    onEventClickById: (String) -> Unit,
    onSendChatMessage: (String, List<Attachment>) -> Unit,
    onDeleteChatMessage: (String) -> Unit,
    onSetChatHistory: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = showChatHistoryInline,
        transitionSpec = {
            if (targetState) {
                scaleIn(
                    initialScale = 1.1f,
                    animationSpec = tween(350, easing = LinearOutSlowInEasing),
                ) + fadeIn(tween(200)) togetherWith
                    scaleOut(
                        targetScale = 0.95f,
                        animationSpec = tween(350),
                    ) + fadeOut(tween(200))
            } else {
                scaleIn(
                    initialScale = 0.95f,
                    animationSpec = tween(350),
                ) + fadeIn(tween(200)) togetherWith
                    scaleOut(
                        targetScale = 1.1f,
                        animationSpec = tween(350),
                    ) + fadeOut(tween(200))
            }
        },
        label = "chatHistoryTransition",
        modifier = modifier,
    ) { showHistory ->
        if (showHistory) {
            ChatHistoryContent(
                sessions = chatSessions,
                currentSessionId = currentSessionId,
                isLoading = isChatHistoryLoading,
                onSelectSession = onSwitchChatSession,
                onNewChat = onNewChatSession,
                onDeleteSession = onDeleteChatSession,
                onBackToChat = { onSetChatHistory(false) },
                contentPadding = contentPaddingWithTop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            var cumulativeScale by remember { mutableFloatStateOf(1f) }
            var pointerCount by remember { mutableIntStateOf(0) }

            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)

                                do {
                                    val event = awaitPointerEvent()

                                    pointerCount = event.changes.count { it.pressed }

                                    if (pointerCount >= 2) {
                                        val zoom = event.calculateZoom()

                                        cumulativeScale *= zoom

                                        if (cumulativeScale < 0.70f) {
                                            onSetChatHistory(true)
                                            cumulativeScale = 1f
                                        }

                                        if (zoom > 1f && cumulativeScale > 1f) {
                                            cumulativeScale = 1f
                                        }

                                        event.changes.forEach { it.consume() }
                                    }
                                } while (event.changes.any { it.pressed })

                                cumulativeScale = 1f
                            }
                        },
            ) {
                ChatModeContent(
                    chatMessages = chatMessages,
                    chatListState = chatListState,
                    notes = notes,
                    onNoteClick = onNoteClick,
                    onNoteClickById = onNoteClickById,
                    onEventClickById = onEventClickById,
                    onSendChatMessage = onSendChatMessage,
                    onDeleteMessage = onDeleteChatMessage,
                    contentPadding = contentPaddingWithTop,
                    isChatProcessing = isChatProcessing,
                    agentActivity = agentActivity,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
