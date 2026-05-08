package com.example.smarty.ui.components.chat

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.TextStyle

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TextEffectPerWord(
    text: String,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium
) {
    val words = text.split(" ")
    var isVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isVisible = true
    }

    FlowRow(modifier = modifier) {
        words.forEachIndexed { index, word ->
            val blurRadius by animateDpAsState(
                targetValue = if (isVisible) 0.dp else 12.dp,
                animationSpec = tween(durationMillis = 600, delayMillis = index * 100),
                label = "blur_$index"
            )
            val alpha by animateFloatAsState(
                targetValue = if (isVisible) 1f else 0f,
                animationSpec = tween(durationMillis = 600, delayMillis = index * 100),
                label = "alpha_$index"
            )

            Text(
                text = word,
                style = textStyle,
                modifier = Modifier
                    .alpha(alpha)
                    .blur(blurRadius)
                    .padding(end = 8.dp)
            )
        }
    }
}
