package com.example.smarty.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarty.ui.LocalAccentColor

sealed interface DynamicIslandState {
    object Contracted : DynamicIslandState
    object Processing : DynamicIslandState
    data class Info(val label: String, val secondaryLabel: String, val icon: ImageVector? = null) : DynamicIslandState
}

@Composable
fun DynamicIsland(
    modifier: Modifier = Modifier,
    state: DynamicIslandState = DynamicIslandState.Contracted
) {
    // Spring physics configuration for "Apple-like" fluid motion
    val islandSpring = spring<Dp>(
        dampingRatio = 0.75f, // Slightly under-damped for a subtle bounce
        stiffness = 350f
    )

    // Determine target dimensions based on state
    val targetWidth = when (state) {
        DynamicIslandState.Contracted -> 24.dp // Matches punch hole
        DynamicIslandState.Processing -> 170.dp
        is DynamicIslandState.Info -> 200.dp
    }

    val targetHeight = when (state) {
        DynamicIslandState.Contracted -> 24.dp
        DynamicIslandState.Processing -> 36.dp
        is DynamicIslandState.Info -> 36.dp
    }

    val targetCornerRadius = when (state) {
        DynamicIslandState.Contracted -> 12.dp
        else -> 18.dp
    }

    // Animate dimensions
    val width by animateDpAsState(targetValue = targetWidth, animationSpec = islandSpring, label = "width")
    val height by animateDpAsState(targetValue = targetHeight, animationSpec = islandSpring, label = "height")
    val cornerRadius by animateDpAsState(targetValue = targetCornerRadius, animationSpec = islandSpring, label = "corner")

    // Pulse for processing
    val infiniteTransition = rememberInfiniteTransition(label = "processing_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Surface(
        modifier = modifier.size(width = width, height = height),
        shape = RoundedCornerShape(cornerRadius),
        color = Color.Black,
        shadowElevation = 0.dp
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            // Content Transition
            AnimatedContent(
                targetState = state,
                transitionSpec = {
                    fadeIn(tween(300)) + scaleIn(initialScale = 0.8f) togetherWith
                            fadeOut(tween(200)) + scaleOut(targetScale = 0.8f)
                },
                label = "islandContent"
            ) { targetState ->
                when (targetState) {
                    DynamicIslandState.Contracted -> {
                        // Empty box for contracted state (just black pill)
                        Box(Modifier.fillMaxSize())
                    }
                    DynamicIslandState.Processing -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp) // Push to edges, clearing center
                        ) {
                            // Pulsing Dot (Left)
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(LocalAccentColor.current.copy(alpha = pulseAlpha), androidx.compose.foundation.shape.CircleShape)
                            )
                            
                            // Text (Right)
                            Text(
                                text = "Processing",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = Color.White,
                                maxLines = 1
                            )
                        }
                    }
                    is DynamicIslandState.Info -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp) // Push to edges, clearing center
                        ) {
                            // Left Side: Icon + Count
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                 if (targetState.icon != null) {
                                     Icon(
                                         imageVector = targetState.icon,
                                         contentDescription = null,
                                         tint = LocalAccentColor.current,
                                         modifier = Modifier.size(14.dp)
                                     )
                                     Spacer(Modifier.width(6.dp))
                                 }
                                Text(
                                    text = targetState.secondaryLabel,
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = LocalAccentColor.current,
                                    maxLines = 1
                                )
                            }
                            
                            // Right Side: Label
                            Text(
                                text = targetState.label,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}
