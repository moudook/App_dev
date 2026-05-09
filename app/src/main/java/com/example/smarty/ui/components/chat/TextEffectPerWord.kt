package com.example.smarty.ui.components.chat

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.unit.dp
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
) {
    var isVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isVisible = true
    }

    val blurRadius by animateDpAsState(
        targetValue = if (isVisible) 0.dp else 12.dp,
        animationSpec = tween(durationMillis = 600),
        label = "blur"
    )
    val alpha by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 600),
        label = "alpha"
    )

    Box(
        modifier = modifier
            .alpha(alpha)
            .blur(blurRadius)
    ) {
        MarkdownRenderer(
            content = text,
            isUser = false,
            normalColor = normalColor,
            boldColor = boldColor,
            linkColor = linkColor,
            codeColor = codeColor,
            codeBackgroundColor = codeBackgroundColor,
            codeBorderColor = codeBorderColor,
            isStreaming = false,
        )
    }
}
