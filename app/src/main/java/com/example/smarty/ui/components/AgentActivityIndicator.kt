package com.example.smarty.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.smarty.features.chat.domain.ChatFeatureManager.AgentActivity

/**
 * Displays the current agent activity (thinking, tool execution, searching).
 * Shows a subtle animated indicator at the bottom of the chat.
 */
@Composable
fun AgentActivityIndicator(
    activity: AgentActivity?,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = activity != null,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier
    ) {
        activity?.let { currentActivity ->
            ActivityBadge(activity = currentActivity)
        }
    }
}

@Composable
private fun ActivityBadge(
    activity: AgentActivity,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "activity_pulse")

    // Pulsing animation for the icon
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    // Alpha animation for the whole badge
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Surface(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .alpha(alpha),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            // Animated icon
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .scale(pulse)
                    .clip(CircleShape)
                    .background(getActivityColor(activity.type).copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = getActivityIcon(activity.type, activity.toolName),
                    contentDescription = null,
                    tint = getActivityColor(activity.type),
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Activity text
            Text(
                text = activity.displayText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Animated dots
            Spacer(modifier = Modifier.width(4.dp))
            AnimatedDots()
        }
    }
}

@Composable
private fun AnimatedDots() {
    val infiniteTransition = rememberInfiniteTransition(label = "dots")

    Row {
        repeat(3) { index ->
            val delay = index * 200
            val dotAlpha by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1.0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, delayMillis = delay),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot_$index"
            )

            Text(
                text = ".",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = dotAlpha)
            )
        }
    }
}

@Composable
private fun getActivityColor(type: AgentActivity.Type): Color {
    return when (type) {
        AgentActivity.Type.THINKING -> MaterialTheme.colorScheme.primary
        AgentActivity.Type.TOOL_RUNNING -> MaterialTheme.colorScheme.tertiary
        AgentActivity.Type.SEARCHING -> MaterialTheme.colorScheme.secondary
        AgentActivity.Type.ANALYZING -> MaterialTheme.colorScheme.primary
    }
}

private fun getActivityIcon(type: AgentActivity.Type, toolName: String?): ImageVector {
    // Check for specific tool names first
    toolName?.let { name ->
        return when {
            name.contains("calendar", ignoreCase = true) -> Icons.Default.CalendarMonth
            name.contains("search", ignoreCase = true) -> Icons.Default.Search
            name.contains("web", ignoreCase = true) -> Icons.Default.Search
            name.contains("note", ignoreCase = true) -> Icons.Default.AutoAwesome
            else -> getDefaultIconForType(type)
        }
    }
    return getDefaultIconForType(type)
}

private fun getDefaultIconForType(type: AgentActivity.Type): ImageVector {
    return when (type) {
        AgentActivity.Type.THINKING -> Icons.Default.Psychology
        AgentActivity.Type.TOOL_RUNNING -> Icons.Default.Settings
        AgentActivity.Type.SEARCHING -> Icons.Default.Search
        AgentActivity.Type.ANALYZING -> Icons.Default.AutoAwesome
    }
}
