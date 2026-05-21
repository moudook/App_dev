package com.example.smarty.ui.components.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarty.core.domain.model.AgentStepEntry
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.components.markdown.MarkdownRenderer

/**
 * Renders the agentic step timeline — a vertical sequence of discrete steps
 * the agent took: thinking, tool calls, checkpoints, etc.
 *
 * Designed to match the reference images: each step shows an icon, title,
 * status indicator, and expandable content.
 */
@Composable
fun AgentStepTimeline(
    steps: List<AgentStepEntry>,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
) {
    if (steps.isEmpty()) return

    val accentColor = LocalAccentColor.current

    Column(modifier = modifier.fillMaxWidth()) {
        steps.forEachIndexed { index, step ->
            AgentStepItem(
                step = step,
                isLast = index == steps.lastIndex,
                isStreaming = isStreaming,
                accentColor = accentColor,
            )
            if (index < steps.lastIndex) {
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun AgentStepItem(
    step: AgentStepEntry,
    isLast: Boolean,
    isStreaming: Boolean,
    accentColor: Color,
) {
    var expanded by remember { mutableStateOf(false) }
    val (icon, tint) = stepIcon(step)
    val statusColor = stepStatusColor(step.stepStatus, accentColor)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        // Timeline line + icon
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(statusColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(16.dp),
                )
            }
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(24.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Content
        Column(modifier = Modifier.weight(1f).padding(top = 4.dp, bottom = 8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { expanded = !expanded }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = step.stepTitle,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                // Status
                when (step.stepStatus) {
                    "completed" -> {
                        Icon(
                            Icons.Default.CheckCircle,
                            "Done",
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(14.dp),
                        )
                        step.durationMs?.let { ms ->
                            Text(
                                text = "${ms / 1000}s",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            )
                        }
                    }
                    "failed", "error" -> {
                        Icon(
                            Icons.Default.ErrorOutline,
                            "Failed",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    "streaming" -> {
                        val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "stepPulse")
                        val a by pulse.animateFloat(
                            0.3f, 1f,
                            infiniteRepeatable(tween(700), RepeatMode.Reverse),
                            label = "a",
                        )
                        Box(
                            Modifier.size(8.dp).clip(CircleShape).background(accentColor.copy(alpha = a)),
                        )
                    }
                    else -> {
                        val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "stepStart")
                        val a by pulse.animateFloat(
                            0.3f, 1f,
                            infiniteRepeatable(tween(700), RepeatMode.Reverse),
                            label = "a",
                        )
                        Box(
                            Modifier.size(8.dp).clip(CircleShape).background(accentColor.copy(alpha = a)),
                        )
                    }
                }
            }

            // Expandable content
            AnimatedVisibility(
                visible = expanded && step.stepContent.isNotBlank(),
                enter = expandVertically(tween(200)) + fadeIn(tween(150)),
                exit = shrinkVertically(tween(150)) + fadeOut(tween(100)),
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                ) {
                    Box(modifier = Modifier.padding(12.dp)) {
                        MarkdownRenderer(
                            content = step.stepContent,
                            isUser = false,
                            normalColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                            boldColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                            linkColor = accentColor.copy(alpha = 0.8f),
                            codeColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            codeBackgroundColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.4f),
                            codeBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                            isStreaming = step.stepStatus == "streaming",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun stepStatusColor(status: String, accentColor: Color): Color = when (status) {
    "completed" -> Color(0xFF4CAF50)
    "failed", "error" -> MaterialTheme.colorScheme.error
    else -> accentColor
}

@Composable
private fun stepIcon(step: AgentStepEntry): Pair<ImageVector, Color> {
    val accentColor = LocalAccentColor.current
    val statusColor = stepStatusColor(step.stepStatus, accentColor)
    val icon = when (step.stepType) {
        "thinking" -> Icons.Default.Psychology
        "tool_call", "opencode_tool" -> toolIcon(step.toolName ?: "")
        "checkpoint" -> Icons.Default.AutoAwesome
        else -> Icons.Default.AutoAwesome
    }
    return icon to statusColor
}

private fun toolIcon(toolName: String): ImageVector {
    val lower = toolName.lowercase()
    return when {
        lower.contains("search") || lower.contains("web") || lower.contains("tavily") -> Icons.Default.Search
        lower.contains("image") || lower.contains("generate") -> Icons.Outlined.Image
        lower.contains("memory") || lower.contains("note") || lower.contains("save") -> Icons.Default.Book
        lower.contains("calendar") || lower.contains("schedule") -> Icons.Default.CalendarMonth
        lower.contains("remind") || lower.contains("alarm") -> Icons.Default.Alarm
        lower.contains("navigate") || lower.contains("route") -> Icons.Default.Navigation
        lower.contains("device") || lower.contains("system") || lower.contains("phone") -> Icons.Default.PhoneAndroid
        lower.contains("weather") -> Icons.Default.Cloud
        else -> Icons.Default.AutoAwesome
    }
}
