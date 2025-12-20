package com.example.smarty.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hearing
import com.example.smarty.ui.LocalAccentColor
import kotlin.math.max
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed interface DynamicIslandState {
    object Contracted : DynamicIslandState
    object Processing : DynamicIslandState
    data class Info(val label: String, val secondaryLabel: String, val icon: ImageVector? = null) : DynamicIslandState
    data class Listening(val rmsDb: Float) : DynamicIslandState
    object Success : DynamicIslandState // New state for completion feedback

    // ============================================================================
    // AGENT TOOL STATES - Show which tool the AI agent is executing
    // ============================================================================

    /**
     * Agent is thinking/reasoning - calling LLM
     */
    data class AgentThinking(
        val iteration: Int = 1,
        val message: String = "Thinking..."
    ) : DynamicIslandState

    /**
     * Agent is executing a specific tool
     */
    data class AgentExecutingTool(
        val toolName: String,
        val toolDisplayName: String,
        val elapsedSeconds: Int = 0
    ) : DynamicIslandState

    /**
     * Agent is waiting for tool result (e.g., web search in progress)
     */
    data class AgentWaitingForResult(
        val toolDisplayName: String,
        val elapsedSeconds: Int = 0
    ) : DynamicIslandState

    /**
     * Agent completed all tasks
     */
    data class AgentCompleted(
        val toolsUsed: Int = 0,
        val totalSeconds: Int = 0
    ) : DynamicIslandState

    /**
     * Agent encountered an error
     */
    data class AgentError(
        val message: String
    ) : DynamicIslandState
}

@Composable
fun DynamicIsland(
    modifier: Modifier = Modifier,
    state: DynamicIslandState = DynamicIslandState.Contracted
) {
    // ============================================================
    // SEQUENCED ANIMATION USING ANIMATABLE
    // Expansion: Width first (300ms) → Height second (300ms)
    // Contraction: Height first (300ms) → Width second (300ms)
    // ============================================================
    
    // Animation constants
    val contractedHeight = 30f
    val expandedHeight = 44f
    val contractedGap = 20f
    val expandedGap = 36f
    
    // Animatable values (in float for dp conversion later)
    val heightAnim = remember { Animatable(contractedHeight) }
    val gapAnim = remember { Animatable(contractedGap) }
    val contentAlphaAnim = remember { Animatable(0f) }
    val visibilityAnim = remember { Animatable(0f) }
    
    // Display state for content
    val displayState = remember { mutableStateOf(state) }
    
    // Track previous state for smooth transitions (BUG-012 fix)
    val previousState = remember { mutableStateOf<DynamicIslandState>(DynamicIslandState.Contracted) }

    // Main animation sequencer
    LaunchedEffect(state) {
        val isExpanding = state !is DynamicIslandState.Contracted
        val wasExpanded = previousState.value !is DynamicIslandState.Contracted

        // Bouncy spring for VERTICAL animations only - Jelly-like feel
        val bouncySpring = spring<Float>(
            dampingRatio = Spring.DampingRatioMediumBouncy, // Creates the bouncy overshoot
            stiffness = Spring.StiffnessMediumLow // Faster but still elastic feel
        )

        val quickBouncySpring = spring<Float>(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium // Slightly faster but still bouncy
        )

        // Fast, smooth tween for HORIZONTAL animations - No bounce
        val smoothHorizontal = tween<Float>(
            durationMillis = 200, // Fast horizontal expansion
            easing = FastOutSlowInEasing
        )

        if (isExpanding) {
            // ========== EXPANSION SEQUENCE ==========

            // BUG-012 fix: Crossfade when transitioning between expanded states
            if (wasExpanded && previousState.value != state) {
                // Quick fade out old content
                contentAlphaAnim.animateTo(0f, animationSpec = tween(80))
            }

            // Update content
            displayState.value = state
            previousState.value = state

            // Animate in (or back to full alpha if already expanded)
            launch {
                gapAnim.animateTo(expandedGap, animationSpec = smoothHorizontal)
            }
            launch {
                heightAnim.animateTo(expandedHeight, animationSpec = bouncySpring)
            }
            launch {
                visibilityAnim.animateTo(1f, animationSpec = quickBouncySpring)
            }
            launch {
                contentAlphaAnim.animateTo(1f, animationSpec = tween(100))
            }

        } else {
            // ========== CONTRACTION SEQUENCE ==========
            // Step 1: Fade out content first
            contentAlphaAnim.animateTo(0f, animationSpec = tween(100))

            // Step 2 & 3: Contract HEIGHT (bouncy) and WIDTH (smooth) SIMULTANEOUSLY
            launch {
                heightAnim.animateTo(contractedHeight, animationSpec = bouncySpring)
            }
            launch {
                gapAnim.animateTo(contractedGap, animationSpec = smoothHorizontal)
            }

            // Step 4: Remove content and fade out visibility
            displayState.value = state
            previousState.value = state
            visibilityAnim.animateTo(0f, animationSpec = tween(150))
        }
    }
    
    // Convert animated floats to Dp
    val height = heightAnim.value.dp
    val gapWidth = gapAnim.value.dp
    val contentAlpha = contentAlphaAnim.value
    val visibility = visibilityAnim.value
    
    // Don't render when fully invisible and contracted
    if (state is DynamicIslandState.Contracted && visibility == 0f && displayState.value is DynamicIslandState.Contracted) {
        return
    }

    // Pulse Animation for processing state
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f, 
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    
    // REACTIVE BOUNCE - Entire island bounces on ANY state change
    val bounceScale = remember { Animatable(1f) }
    LaunchedEffect(state) {
        // Don't bounce for Contracted or Listening states
        // Listening updates very frequently (RMS db changes), so we avoid bouncing there
        if (state !is DynamicIslandState.Contracted && state !is DynamicIslandState.Listening) {
            // Quick bounce reaction when content changes
            bounceScale.animateTo(
                targetValue = 1.08f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessHigh
                )
            )
            bounceScale.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            )
        }
    }
    
    val cornerRadius = height / 2
    val safeScale = visibility.coerceAtLeast(0.01f) * bounceScale.value

    Surface(
        modifier = modifier
            .graphicsLayer {
                scaleX = safeScale
                scaleY = safeScale
                alpha = visibility
            }
            .wrapContentSize(Alignment.TopCenter)
            .height(height),
        shape = RoundedCornerShape(cornerRadius),
        color = Color.Black,
        shadowElevation = 8.dp
    ) {
        // Use custom symmetrical layout
        SymmetricalIslandLayout(
            gapWidth = gapWidth,
            modifier = Modifier
        ) {
            // LEFT WING
            Box(
                modifier = Modifier
                    .alpha(contentAlpha)
                    .padding(start = 20.dp, end = 4.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                when (val currentState = displayState.value) {
                    is DynamicIslandState.Processing -> {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    LocalAccentColor.current.copy(alpha = pulseAlpha),
                                    androidx.compose.foundation.shape.CircleShape
                                )
                        )
                    }
                    is DynamicIslandState.Info -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (currentState.icon != null) {
                                Icon(
                                    imageVector = currentState.icon,
                                    contentDescription = null,
                                    tint = LocalAccentColor.current,
                                    modifier = Modifier.size(20.dp)
                                )
                                if (currentState.secondaryLabel.isNotEmpty()) {
                                    Spacer(Modifier.width(8.dp))
                                }
                            }
                            if (currentState.secondaryLabel.isNotEmpty()) {
                                Text(
                                    text = currentState.secondaryLabel,
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = (-0.5).sp
                                    ),
                                    color = LocalAccentColor.current,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                    is DynamicIslandState.Listening -> {
                        // Voice-reactive orb
                        val db = currentState.rmsDb.coerceIn(-2f, 10f)
                        val targetScale = 1f + ((db + 2f) / 12f) * 0.8f
                        val orbScale by animateFloatAsState(
                            targetValue = targetScale,
                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                            label = "voiceOrb"
                        )
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .scale(orbScale)
                                .background(LocalAccentColor.current, androidx.compose.foundation.shape.CircleShape)
                        )
                    }
                    // ========== AGENT STATES - LEFT WING ==========
                    is DynamicIslandState.AgentThinking -> {
                        // Pulsing brain/thinking indicator
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .scale(1f + (pulseAlpha - 0.7f) * 0.3f)
                                .background(
                                    LocalAccentColor.current.copy(alpha = pulseAlpha),
                                    androidx.compose.foundation.shape.CircleShape
                                )
                        )
                    }
                    is DynamicIslandState.AgentExecutingTool,
                    is DynamicIslandState.AgentWaitingForResult -> {
                        // Spinning/pulsing tool indicator
                        val rotation by rememberInfiniteTransition(label = "toolSpin").animateFloat(
                            initialValue = 0f,
                            targetValue = 360f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1500, easing = LinearEasing),
                                repeatMode = RepeatMode.Restart
                            ),
                            label = "toolRotation"
                        )
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .graphicsLayer { rotationZ = rotation }
                                .background(
                                    LocalAccentColor.current,
                                    RoundedCornerShape(2.dp)
                                )
                        )
                    }
                    is DynamicIslandState.AgentCompleted -> {
                        // Success checkmark indicator
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(
                                    Color(0xFF4CAF50), // Green
                                    androidx.compose.foundation.shape.CircleShape
                                )
                        )
                    }
                    is DynamicIslandState.AgentError -> {
                        // Error indicator
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(
                                    Color(0xFFE53935), // Red
                                    androidx.compose.foundation.shape.CircleShape
                                )
                        )
                    }
                    else -> {}
                }
            }

            // RIGHT WING (Content aligned to start, near gap)
            Box(
                modifier = Modifier
                    .alpha(contentAlpha)
                    .padding(end = 20.dp, start = 4.dp), // More generous padding
                contentAlignment = Alignment.CenterStart
            ) {
                when (val currentState = displayState.value) {
                    is DynamicIslandState.Processing -> {
                        Text(
                            text = "Thinking...",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                            color = Color.White.copy(alpha = 0.9f),
                            maxLines = 1
                        )
                    }
                    is DynamicIslandState.Info -> {
                        Text(
                            text = currentState.label,
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    is DynamicIslandState.Listening -> {
                        Icon(
                            imageVector = Icons.Default.Hearing,
                            contentDescription = "Listening",
                            tint = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    // ========== AGENT STATES - RIGHT WING ==========
                    is DynamicIslandState.AgentThinking -> {
                        Text(
                            text = if (currentState.iteration > 1)
                                "Step ${currentState.iteration}..."
                            else
                                currentState.message,
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                            color = Color.White.copy(alpha = 0.9f),
                            maxLines = 1
                        )
                    }
                    is DynamicIslandState.AgentExecutingTool -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = currentState.toolDisplayName,
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                color = LocalAccentColor.current,
                                maxLines = 1
                            )
                            if (currentState.elapsedSeconds > 0) {
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = "${currentState.elapsedSeconds}s",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                    is DynamicIslandState.AgentWaitingForResult -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Waiting: ${currentState.toolDisplayName}",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                                color = Color.White.copy(alpha = 0.9f),
                                maxLines = 1
                            )
                            if (currentState.elapsedSeconds > 0) {
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = "${currentState.elapsedSeconds}s",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                    is DynamicIslandState.AgentCompleted -> {
                        Text(
                            text = if (currentState.toolsUsed > 0)
                                "Done (${currentState.toolsUsed} tools)"
                            else
                                "Done",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                            color = Color(0xFF4CAF50),
                            maxLines = 1
                        )
                    }
                    is DynamicIslandState.AgentError -> {
                        Text(
                            text = currentState.message.take(20),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                            color = Color(0xFFE53935),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    else -> {}
                }
            }
        }
    }
}

/**
 * A custom layout that places two measurables (Left and Right) separated by a fixed Gap.
 * The layout enforces symmetry: the total width is (MaxWingWidth * 2) + Gap.
 * Left content is aligned to the End of the left wing.
 * Right content is aligned to the Start of the right wing.
 */
@Composable
private fun SymmetricalIslandLayout(
    gapWidth: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Layout(
        content = content,
        modifier = modifier
    ) { measurables, constraints ->
        // We expect exactly 2 items: Left Wing, Right Wing.
        // Even if empty, they are added as Box { ... } in the Composable above.
        
        val gapPx = gapWidth.roundToPx()
        val looseConstraints = constraints.copy(minWidth = 0, minHeight = 0)

        // Measure children
        val placables = measurables.map { it.measure(looseConstraints) }
        val leftPlaceable = placables.getOrNull(0)
        val rightPlaceable = placables.getOrNull(1)

        val leftW = leftPlaceable?.width ?: 0
        val rightW = rightPlaceable?.width ?: 0
        val leftH = leftPlaceable?.height ?: 0
        val rightH = rightPlaceable?.height ?: 0

        // Determine the maximum width of a wing to ensure symmetry
        val maxWingWidth = max(leftW, rightW)
        
        // Calculate total measurements
        // Always enforce sufficient height for the pill shape
        val minHeight = 30.dp.roundToPx()
        val totalHeight = max(minHeight, max(leftH, rightH))
        
        // Ensure minimum total width is at least height for perfect circle in contracted state
        val totalWidth = max(minHeight, (maxWingWidth * 2) + gapPx)

        layout(totalWidth, totalHeight) {
            // Place Left Wing: End aligned -> x = (maxWingWidth - leftWidth)
            // Center vertically
            if (leftPlaceable != null) {
                val leftX = maxWingWidth - leftW
                val leftY = (totalHeight - leftH) / 2
                leftPlaceable.placeRelative(x = leftX, y = leftY)
            }

            // Place Right Wing: Start aligned -> x = maxWingWidth + gap
            // Center vertically
            if (rightPlaceable != null) {
                val rightX = maxWingWidth + gapPx
                val rightY = (totalHeight - rightH) / 2
                rightPlaceable.placeRelative(x = rightX, y = rightY)
            }
        }
    }
}
