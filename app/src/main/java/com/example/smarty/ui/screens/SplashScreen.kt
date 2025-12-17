package com.example.smarty.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.smarty.R
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.theme.NeonPurple
import kotlinx.coroutines.delay

/**
 * Apple-quality animated splash screen
 * Features:
 * - Smooth logo reveal with scale + blur animation
 * - Elegant gradient background with subtle movement
 * - Professional timing and easing curves
 */
@Composable
fun SplashScreen(
    onSplashComplete: () -> Unit
) {
    // Consistent Dark Background (ignoring theme)
    val backgroundColor = Color(0xFF030305) // Rich dark/black
    val brandColor = Color(0xFF2979FF) // Electric Blue

    // Typing Animation State
    val textToType = "cogni_"
    var displayedText by remember { mutableStateOf("") }
    var showCursor by remember { mutableStateOf(true) }
    var isTypingFinished by remember { mutableStateOf(false) }

    // Phase control
    // 0: Start delay
    // 1: Typing
    // 2: Cursor blink / hold
    // 3: Exit (fade out)
    var animationPhase by remember { mutableStateOf(0) }

    // Exit animation (alpha dissolve)
    val contentAlpha by animateFloatAsState(
        targetValue = if (animationPhase == 3) 0f else 1f,
        animationSpec = tween(400, easing = LinearEasing),
        label = "contentExit"
    )

    // Cursor pulsing
    LaunchedEffect(Unit) {
        while (true) {
            showCursor = !showCursor
            delay(500)
        }
    }

    // Main Sequence
    LaunchedEffect(Unit) {
        delay(300) // Initial silence
        animationPhase = 1
        
        // Typing loop
        textToType.forEachIndexed { index, char ->
            displayedText = textToType.substring(0, index + 1)
            // Variable typing speed for human feel
            delay(if (char == '_') 400L else 120L) 
        }
        
        isTypingFinished = true
        animationPhase = 2
        delay(800) // Hold briefly to let user read
        
        animationPhase = 3 // Fade out start
        delay(400) // Wait for fade
        
        onSplashComplete()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor), // Always dark
        contentAlignment = Alignment.Center
    ) {
        // Optional: Subtle background glow
        Box(
            modifier = Modifier
                .size(300.dp)
                .graphicsLayer { alpha = 0.05f }
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(brandColor, Color.Transparent)
                    )
                )
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.alpha(contentAlpha) // Apply exit fade
        ) {
            // Main Typographic Logo
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = displayedText,
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        letterSpacing = (-2).sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    ),
                    color = brandColor
                )
                
                // Cursor
                if (showCursor && !isTypingFinished) {
                    Box(
                        modifier = Modifier
                            .width(4.dp)
                            .height(48.dp)
                            .background(brandColor)
                            .offset(x = 4.dp)
                    )
                }
            }
        }
        
        // Footer: "brain dump, organized" (Appears after typing)
        androidx.compose.animation.AnimatedVisibility(
            visible = isTypingFinished && animationPhase < 3,
            enter = androidx.compose.animation.fadeIn(tween(500)) + androidx.compose.animation.slideInVertically(
                initialOffsetY = { 20 }
            ),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 64.dp)
                .alpha(contentAlpha)
        ) {
            Text(
                text = "brain dump, organized",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.4f)
            )
        }
    }
}
