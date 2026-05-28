package com.example.smarty.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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
    onApproval: (String, Boolean, String?) -> Unit = { _, _, _ -> }
) {
    val isUser = message.isUser
    val accentColor = LocalAccentColor.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        if (isUser) {
            // User message: Right-aligned bubble
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.CenterEnd
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = accentColor.copy(alpha = 0.15f),
                    modifier = Modifier.padding(start = 32.dp)
                ) {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            lineHeight = 24.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            }
        } else {
            // Agent response: Left-aligned plain text (no bubble, no icon)
            if (message.content.isNotBlank() || message.isStreaming) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                ) {
                    TextEffectPerWord(
                        text = message.content,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 16.sp,
                            lineHeight = 24.sp
                        ),
                        normalColor = MaterialTheme.colorScheme.onSurface,
                        boldColor = MaterialTheme.colorScheme.onSurface,
                        linkColor = accentColor,
                        codeColor = MaterialTheme.colorScheme.onSurface,
                        codeBackgroundColor = MaterialTheme.colorScheme.surfaceVariant,
                        codeBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        isStreaming = message.isStreaming
                    )
                }
            }
        }
    }
}
