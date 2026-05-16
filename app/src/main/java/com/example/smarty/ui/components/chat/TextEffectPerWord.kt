package com.example.smarty.ui.components.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.components.markdown.MarkdownRenderer

@Composable
fun TextEffectPerWord(
    text: String,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    normalColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
    boldColor: Color = MaterialTheme.colorScheme.onSurface,
    linkColor: Color = com.example.smarty.ui.LocalAccentColor.current,
    codeColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
    codeBackgroundColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.4f),
    codeBorderColor: Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
    isStreaming: Boolean = false
) {
    Box(modifier = modifier) {
        MarkdownRenderer(
            content = text,
            isUser = false,
            normalColor = normalColor,
            boldColor = boldColor,
            linkColor = linkColor,
            codeColor = codeColor,
            codeBackgroundColor = codeBackgroundColor,
            codeBorderColor = codeBorderColor,
            isStreaming = isStreaming,
        )
    }
}
