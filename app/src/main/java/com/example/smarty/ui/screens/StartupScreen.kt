package com.example.smarty.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.animation.CogniEasing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Cogni Startup Animation: "Synapse Bloom"
 *
 * Concept:
 * 1. The Spark: A single point of light appears (The Idea).
 * 2. Neural Expansion: Organic "synapses" bloom outward from the spark (The Thought Process).
 * 3. Convergence: The chaos organizes into the Cogni Logo (The Solution).
 *
 * This animation represents the core intent of Cogni: "Everything starts with an idea."
 */
@Composable
fun StartupScreen(
    onComplete: () -> Unit
) {
    val accentColor = LocalAccentColor.current
    val backgroundColor = MaterialTheme.colorScheme.background
    
    // Animation States
    val sparkScale = remember { Animatable(0f) }
    val bloomProgress = remember { Animatable(0f) }
    val logoAlpha = remember { Animatable(0f) }
    val backgroundAlpha = remember { Animatable(1f) }

    // Generative Art Setup
    // Use fixed seed for consistent "random" beauty
    val synapsePaths = remember { generateSynapsePaths(count = 12) }
    
    LaunchedEffect(Unit) {
        // Phase 1: The Spark (Idea Creation) - Quick Pop
        launch {
            sparkScale.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = 0.6f,
                    stiffness = Spring.StiffnessMedium
                )
            )
        }
        
        delay(300)

        // Phase 2: Synapse Bloom (Thought Expansion) - Elegant Flow
        launch {
            bloomProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 1500,
                    easing = CogniEasing.appleEaseOut
                )
            )
        }

        delay(1200)

        // Phase 3: Convergence (Logo Confirm)
        launch {
            logoAlpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(600)
            )
        }
        
        delay(800)
        
        // Phase 4: Exit
        backgroundAlpha.animateTo(
            targetValue = 0f,
            animationSpec = tween(300)
        )
        
        onComplete()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor.copy(alpha = backgroundAlpha.value)),
        contentAlignment = Alignment.Center
    ) {
        
        Canvas(modifier = Modifier.size(300.dp)) {
            val center = Offset(size.width / 2, size.height / 2)
            
            // Draw Synapses
            if (bloomProgress.value > 0) {
                synapsePaths.forEach { synapse ->
                    val pathProgress = bloomProgress.value
                    
                    // Dynamic path drawing
                    // We only draw a portion of the path based on progress
                    // But to make it "bloom", we can scale the path or stroke trim
                    
                    // Simple radial expansion for v1 reliability
                    val currentRadius = synapse.radius * pathProgress
                    val angleRad = Math.toRadians(synapse.angle.toDouble())
                    
                    val endX = center.x + currentRadius * cos(angleRad).toFloat()
                    val endY = center.y + currentRadius * sin(angleRad).toFloat()

                    // Draw organic curve
                    val path = Path().apply {
                        moveTo(center.x, center.y)
                        quadraticTo(
                            center.x + (endX - center.x) / 2 + synapse.controlOffsetX,
                            center.y + (endY - center.y) / 2 + synapse.controlOffsetY,
                            endX,
                            endY
                        )
                    }

                    drawPath(
                        path = path,
                        color = accentColor.copy(alpha = 0.6f * (1f - pathProgress * 0.5f)), // Fade out tips
                        style = Stroke(
                            width = 3.dp.toPx() * (1f - pathProgress * 0.8f), // Tapering
                            cap = StrokeCap.Round
                        )
                    )
                    
                    // Glowing tip
                    drawCircle(
                        color = accentColor.copy(alpha = 0.9f),
                        radius = 4.dp.toPx() * (1f - pathProgress),
                        center = Offset(endX, endY)
                    )
                }
            }

            // Draw "The Spark" (Central Core)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        accentColor,
                        accentColor.copy(alpha = 0.2f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = 40.dp.toPx() * sparkScale.value
                ),
                radius = 40.dp.toPx() * sparkScale.value,
                center = center
            )
            
            // Core solid dot
            drawCircle(
                color = accentColor,
                radius = 8.dp.toPx() * sparkScale.value,
                center = center
            )
        }
        
        // Final Logo Reveal (Standard CogniHeader but specialized)
        Box(
            modifier = Modifier
                .graphicsLayer { alpha = logoAlpha.value }
        ) {
            // Reusing existing header or custom text for cleaner look
            com.example.smarty.ui.components.CogniHeader(
                showShimmer = true
            )
        }
    }
}

private data class SynapseData(
    val angle: Float,
    val radius: Float,
    val controlOffsetX: Float,
    val controlOffsetY: Float
)

private fun generateSynapsePaths(count: Int): List<SynapseData> {
    val random = Random(42) // Fixed seed for consistent "art"
    return List(count) {
        val angle = (360f / count) * it + random.nextFloat() * 20f - 10f
        SynapseData(
            angle = angle,
            radius = 300f + random.nextFloat() * 100f, // Large radius to go off screen or far
            controlOffsetX = random.nextFloat() * 100f - 50f,
            controlOffsetY = random.nextFloat() * 100f - 50f
        )
    }
}
