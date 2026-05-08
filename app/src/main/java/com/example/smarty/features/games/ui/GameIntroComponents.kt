package com.example.smarty.features.games.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TextRollAnimation(
    modifier: Modifier = Modifier,
    text: String,
    color: Color = MaterialTheme.colorScheme.onBackground
) {
    var isVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isVisible = true
    }

    Row(modifier = modifier.clipToBounds()) {
        text.forEachIndexed { index, char ->
            val yOffset by animateFloatAsState(
                targetValue = if (isVisible) 0f else 60f, // Start below, roll up
                animationSpec = tween(
                    durationMillis = 600,
                    delayMillis = index * 40,
                    easing = CubicBezierEasing(0.175f, 0.885f, 0.32f, 1.1f)
                ),
                label = "textRollY"
            )

            val alpha by animateFloatAsState(
                targetValue = if (isVisible) 1f else 0f,
                animationSpec = tween(400, delayMillis = index * 40),
                label = "textAlpha"
            )

            Text(
                text = if (char == ' ') "\u00A0" else char.toString(),
                style = MaterialTheme.typography.displayMedium.copy(
                    fontWeight = FontWeight.ExtraLight,
                    letterSpacing = (-2).sp
                ),
                color = color,
                modifier = Modifier.graphicsLayer {
                    translationY = yOffset
                    this.alpha = alpha
                }
            )
        }
    }
}

@Composable
fun GameIntroScreen(
    title: String,
    onPlay: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            TextRollAnimation(
                text = title,
                modifier = Modifier.padding(bottom = 64.dp)
            )

            var showButton by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(800)
                showButton = true
            }

            AnimatedVisibility(
                visible = showButton,
                enter = fadeIn(tween(600)) + slideInVertically(tween(600)) { it / 2 },
                exit = fadeOut()
            ) {
                Button(
                    onClick = onPlay,
                    modifier = Modifier.width(180.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "PLAY",
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
            }
        }
    }
}
