package com.example.smarty.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarty.core.domain.model.ChatMessage
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.components.chat.CitationCards
import com.example.smarty.ui.components.chat.StepTimeline
import com.example.smarty.ui.components.chat.TextEffectPerWord
import com.example.smarty.ui.components.chat.ThinkingAccordion
import com.example.smarty.ui.components.chat.ToolCallEntryCard

@Composable
fun AgentTimelineItem(
    message: ChatMessage,
    modifier: Modifier = Modifier,
    onCopyMessage: (String) -> Unit = {},
    onRegenerateMessage: (String) -> Unit = {},
    onApproval: (String, Boolean, String?) -> Unit = { _, _, _ -> },
    onSkip: () -> Unit = {},
) {
    val isUser = message.isUser
    val accentColor = LocalAccentColor.current

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (isUser) {
            var isExpanded by remember { mutableStateOf(false) }
            var hasOverflow by remember { mutableStateOf(false) }

            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.CenterEnd,
            ) {
                val bubbleColor = accentColor.copy(alpha = 0.15f)
                Surface(
                    shape = RoundedCornerShape(32.dp),
                    color = bubbleColor,
                    modifier =
                        Modifier
                            .padding(start = 32.dp)
                            .clickable(
                                enabled = hasOverflow,
                                onClick = { isExpanded = !isExpanded },
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .padding(horizontal = 24.dp, vertical = 20.dp)
                                .animateContentSize(),
                    ) {
                        Text(
                            text = message.content,
                            maxLines = if (isExpanded) Int.MAX_VALUE else 4,
                            onTextLayout = { textLayoutResult ->
                                if (!isExpanded) {
                                    hasOverflow = textLayoutResult.hasVisualOverflow
                                }
                            },
                            overflow = TextOverflow.Ellipsis,
                            style =
                                MaterialTheme.typography.bodyLarge.copy(
                                    lineHeight = 24.sp,
                                ),
                            color = MaterialTheme.colorScheme.onSurface,
                        )

                        if (hasOverflow && !isExpanded) {
                            Box(
                                modifier =
                                    Modifier
                                        .align(Alignment.BottomEnd)
                                        .background(
                                            brush =
                                                Brush.horizontalGradient(
                                                    colors =
                                                        listOf(
                                                            Color.Transparent,
                                                            bubbleColor,
                                                            bubbleColor,
                                                        ),
                                                    startX = 0f,
                                                    endX = 40f,
                                                ),
                                        ).padding(start = 16.dp, top = 2.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ExpandMore,
                                    contentDescription = "Expand",
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // ── 1. & 2. & 3. INTERLEAVED TIMELINE (Thinking, Tools, Steps) ──
            if (message.agentSteps.isNotEmpty()) {
                var toolIdx = 0
                message.agentSteps.forEach { step ->
                    key(step.stepIndex) {
                        if (step.stepType == "thinking") {
                            ThinkingAccordion(
                                thinking = step.stepContent,
                                isStreaming = step.stepStatus == "started" || step.stepStatus == "streaming",
                                onSkip = onSkip,
                            )
                        } else if (step.stepType == "tool_call" || step.stepType == "opencode_tool") {
                            if (toolIdx < message.toolCalls.size) {
                                ToolCallEntryCard(toolCall = message.toolCalls[toolIdx])
                                toolIdx++
                            } else {
                                StepTimeline(steps = listOf(step))
                            }
                        } else {
                            StepTimeline(steps = listOf(step))
                        }
                    }
                }
            } else {
                // Fallback for older messages
                val thinking = message.thinking
                if (message.isThinking || !thinking.isNullOrBlank()) {
                    ThinkingAccordion(
                        thinking = thinking ?: "",
                        isStreaming = message.isThinking,
                        onSkip = onSkip,
                    )
                }
                if (message.toolCalls.isNotEmpty()) {
                    message.toolCalls.forEach { toolCall ->
                        ToolCallEntryCard(toolCall = toolCall)
                    }
                }
            }

            // ── 4. CITATIONS ──
            if (message.citations.isNotEmpty()) {
                CitationCards(
                    citations = message.citations,
                    accentColor = accentColor,
                )
            }

            // ── 5. RESPONSE TEXT (skeleton or clean content) ──
            if (message.isStreaming || message.content.isNotBlank()) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                ) {
                    if (message.isStreaming && message.content.isBlank()) {
                        // Skeleton: response generating but no text yet
                        Text(
                            text = "…",
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 16.sp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        )
                    } else {
                        // Show text (partial or final) with optional streaming cursor
                        TextEffectPerWord(
                            text = message.content,
                            textStyle =
                                MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 16.sp,
                                    lineHeight = 24.sp,
                                ),
                            normalColor = MaterialTheme.colorScheme.onSurface,
                            boldColor = MaterialTheme.colorScheme.onSurface,
                            linkColor = accentColor,
                            codeColor = MaterialTheme.colorScheme.onSurface,
                            codeBackgroundColor = MaterialTheme.colorScheme.surfaceVariant,
                            codeBorderColor = MaterialTheme.colorScheme.outlineVariant,
                            isStreaming = message.isStreaming,
                        )
                    }
                }
            }
        }
    }
}
