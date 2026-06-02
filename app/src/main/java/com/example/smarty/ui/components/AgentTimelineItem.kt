package com.example.smarty.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import com.example.smarty.ui.components.chat.TextEffectPerWord

@Composable
fun AgentTimelineItem(
    message: ChatMessage,
    modifier: Modifier = Modifier,
    onCopyMessage: (String) -> Unit = {},
    onRegenerateMessage: (String) -> Unit = {},
    onApproval: (String, Boolean, String?) -> Unit = { _, _, _ -> },
) {
    val isUser = message.isUser
    val accentColor = LocalAccentColor.current

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        if (isUser) {
            var isExpanded by remember { mutableStateOf(false) }
            var hasOverflow by remember { mutableStateOf(false) }

            // User message: Right-aligned bubble
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
            // Agent response: Left-aligned plain text (no bubble, no icon)
            if (message.content.isNotBlank() || message.isStreaming) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                ) {
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
