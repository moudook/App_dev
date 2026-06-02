package com.example.smarty.ui.components.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarty.core.domain.model.AgentToolCallEntry
import com.example.smarty.ui.LocalAccentColor

@Composable
fun ToolCallEntryCard(toolCall: AgentToolCallEntry) {
    val accentColor = LocalAccentColor.current
    val isDark = isSystemInDarkTheme()
    val optionBg = if (isDark) Color(0xFF2A2A2A) else Color(0xFFF4F4F7)
    var expanded by remember(toolCall.toolName) { mutableStateOf(false) }

    val statusColor =
        when (toolCall.status) {
            "completed", "success" -> Color(0xFF4CAF50)
            "failed", "error" -> MaterialTheme.colorScheme.error
            else -> accentColor
        }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = optionBg,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { expanded = !expanded }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = toolCall.displayName,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ToolCallStatusIndicator(status = toolCall.status, statusColor = statusColor)
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                    modifier = Modifier.size(18.dp),
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(animationSpec = spring(dampingRatio = 0.75f, stiffness = 350f)) + fadeIn(animationSpec = tween(200)),
                exit = shrinkVertically(animationSpec = tween(180)) + fadeOut(animationSpec = tween(150)),
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f))
                    toolCall.inputSummary?.takeIf { it.isNotBlank() }?.let { input ->
                        Text(
                            text = "INPUT",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        )
                        Text(
                            text = input,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    toolCall.outputSummary?.takeIf { it.isNotBlank() }?.let { output ->
                        Text(
                            text = "RESULT",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        )
                        Text(
                            text = output.take(1200),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (toolCall.inputSummary.isNullOrBlank() && toolCall.outputSummary.isNullOrBlank()) {
                        Text(
                            text = if (toolCall.status == "started" || toolCall.status == "running") "Running\u2026" else "Completed",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolCallStatusIndicator(
    status: String,
    statusColor: Color,
) {
    when (status) {
        "completed", "success" ->
            Icon(
                Icons.Outlined.CheckCircle,
                contentDescription = "Completed",
                tint = Color(0xFF4CAF50),
                modifier = Modifier.size(16.dp),
            )
        "failed", "error" ->
            Icon(
                Icons.Default.ErrorOutline,
                contentDescription = "Failed",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp),
            )
        else -> {
            val pulseTransition = rememberInfiniteTransition(label = "toolPulse")
            val pulseAlpha by pulseTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(700, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "pulseAlpha",
            )
            Box(
                modifier =
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusColor.copy(alpha = pulseAlpha)),
            )
        }
    }
}
