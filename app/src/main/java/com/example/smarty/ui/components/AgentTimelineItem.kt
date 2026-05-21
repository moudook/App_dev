package com.example.smarty.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarty.core.domain.model.AgentStepEntry
import com.example.smarty.core.domain.model.ChatMessage
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.components.chat.TextEffectPerWord
import com.example.smarty.ui.components.timeline.ApprovalCard
import com.example.smarty.ui.components.timeline.ErrorCard
import com.example.smarty.ui.components.timeline.RecoveryCard
import com.example.smarty.ui.components.timeline.SystemActivityCard
import com.example.smarty.ui.components.timeline.ThinkingCard
import com.example.smarty.ui.components.timeline.TimelineNode
import com.example.smarty.ui.components.timeline.TimelineNodeAggregator
import com.example.smarty.ui.components.timeline.ToolCallCard
import com.example.smarty.ui.theme.Alpha
import com.example.smarty.ui.theme.ComponentSpacing

/**
 * Renders a full ChatMessage as a linear sequence of Timeline Nodes.
 *
 * Rendering strategy:
 * 1. If the message has [ChatMessage.agentEvents], process them via
 *    [TimelineNodeAggregator] into stable [TimelineNode]s and render
 *    the 3-tier visibility system (Tier 1: semantic, Tier 2: system activity).
 * 2. If the message only has legacy [ChatMessage.agentSteps], fall back to
 *    [LegacyAgentTimeline] for backward compatibility.
 */
@Composable
fun AgentTimelineItem(
    message: ChatMessage,
    modifier: Modifier = Modifier,
    onCopyMessage: (String) -> Unit = {},
    onRegenerateMessage: (String) -> Unit = {}
) {
    val isUser = message.isUser
    val accentColor = LocalAccentColor.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        if (isUser) {
            // ── User message ──────────────────────────────────────────────────
            TimelineNodeLayout(
                icon = {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = "User",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                isLast = false
            ) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Medium,
                        lineHeight = 24.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        } else {
            // ── Agent response ─────────────────────────────────────────────────

            val hasGranularEvents = message.agentEvents.isNotEmpty()
            val hasLegacySteps = message.agentSteps.isNotEmpty()
            val hasFinalAnswer = message.content.isNotBlank()

            if (hasGranularEvents) {
                // NEW PATH: Derive stable TimelineNodes from the raw event stream
                GranularEventTimeline(
                    message = message,
                    accentColor = accentColor,
                    hasFinalAnswer = hasFinalAnswer
                )
            } else if (hasLegacySteps) {
                // LEGACY PATH: Render old AgentStepEntry list
                LegacyAgentTimeline(
                    message = message,
                    accentColor = accentColor,
                    hasFinalAnswer = hasFinalAnswer
                )
            } else if (message.toolCalls.isNotEmpty()) {
                // LEGACY: bare ToolCall list (no steps)
                message.toolCalls.forEachIndexed { index, tool ->
                    val isLastTool = index == message.toolCalls.lastIndex && !hasFinalAnswer
                    TimelineNodeLayout(
                        icon = {
                            Icon(Icons.Default.Build, null, modifier = Modifier.size(14.dp), tint = accentColor)
                        },
                        isLast = isLastTool
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "Tool: ${tool.displayName}",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                tool.outputSummary?.takeIf { it.isNotBlank() }?.let {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = it.take(100) + "…",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Final Answer Node — always at the bottom
            if (hasFinalAnswer) {
                TimelineNodeLayout(
                    icon = {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "Smarty",
                            modifier = Modifier.size(16.dp),
                            tint = accentColor
                        )
                    },
                    isLast = true
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(modifier = Modifier.padding(16.dp)) {
                            TextEffectPerWord(
                                text = message.content,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 16.sp,
                                    lineHeight = 24.sp
                                ),
                                normalColor = MaterialTheme.colorScheme.onSurface,
                                boldColor = MaterialTheme.colorScheme.onSurface,
                                linkColor = accentColor,
                                codeColor = MaterialTheme.colorScheme.onSurface,
                                codeBackgroundColor = MaterialTheme.colorScheme.surfaceVariant,
                                codeBorderColor = MaterialTheme.colorScheme.outlineVariant,
                                isStreaming = message.isStreaming
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Granular event timeline (new canonical path)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun GranularEventTimeline(
    message: ChatMessage,
    accentColor: Color,
    hasFinalAnswer: Boolean,
) {
    // Aggregate raw events into stable TimelineNodes.
    // `remember(message.id)` scopes the aggregator to this message ID.
    val aggregator = remember(message.id) { TimelineNodeAggregator() }
    val nodes = remember(message.agentEvents.size) {
        aggregator.processAll(message.agentEvents)
    }

    // Separate semantic (Tier 1) and system (Tier 2) nodes
    val semanticNodes = nodes.filterNot { it is TimelineNode.SystemActivity }
    val systemNode = nodes.filterIsInstance<TimelineNode.SystemActivity>().firstOrNull()

    semanticNodes.forEachIndexed { index, node ->
        val isLast = index == semanticNodes.lastIndex && !hasFinalAnswer && systemNode == null
        TimelineNodeLayout(
            icon = { NodeIcon(node, accentColor) },
            isLast = isLast,
            isPulsing = node.isPulsing(),
        ) {
            NodeCard(node = node, accentColor = accentColor)
        }
    }

    // Tier 2: System activity chip — shown after semantic nodes, before final answer
    systemNode?.let { sys ->
        TimelineNodeLayout(
            icon = {
                Icon(Icons.Default.Settings, null, modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f))
            },
            isLast = !hasFinalAnswer,
            isPulsing = sys.isOngoing,
        ) {
            SystemActivityCard(node = sys)
        }
    }
}

private fun TimelineNode.isPulsing(): Boolean = when (this) {
    is TimelineNode.Thinking -> isStreaming
    is TimelineNode.ToolExecution -> status == TimelineNode.ToolExecution.Status.RUNNING
    is TimelineNode.RecoveryNode -> succeeded == null
    is TimelineNode.ApprovalGate -> status == TimelineNode.ApprovalGate.Status.PENDING
    is TimelineNode.SystemActivity -> isOngoing
    else -> false
}

@Composable
private fun NodeIcon(node: TimelineNode, accentColor: Color) {
    val (icon, tint) = when (node) {
        is TimelineNode.Thinking ->
            Icons.Default.Psychology to if (node.isStreaming) accentColor
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
        is TimelineNode.ToolExecution ->
            Icons.Default.Build to when (node.status) {
                TimelineNode.ToolExecution.Status.COMPLETED -> MaterialTheme.colorScheme.onSurfaceVariant
                TimelineNode.ToolExecution.Status.FAILED -> MaterialTheme.colorScheme.error
                else -> accentColor
            }
        is TimelineNode.ApprovalGate ->
            Icons.Default.Security to accentColor
        is TimelineNode.ErrorNode ->
            Icons.Default.ErrorOutline to MaterialTheme.colorScheme.error
        is TimelineNode.RecoveryNode ->
            Icons.Default.Refresh to accentColor
        is TimelineNode.SystemActivity ->
            Icons.Default.Settings to MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    }
    Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = tint)
}

@Composable
private fun NodeCard(node: TimelineNode, accentColor: Color) {
    when (node) {
        is TimelineNode.Thinking -> ThinkingCard(node = node)
        is TimelineNode.ToolExecution -> ToolCallCard(node = node)
        is TimelineNode.ApprovalGate -> ApprovalCard(node = node)
        is TimelineNode.ErrorNode -> ErrorCard(node = node)
        is TimelineNode.RecoveryNode -> RecoveryCard(node = node)
        is TimelineNode.SystemActivity -> SystemActivityCard(node = node)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Legacy step timeline — preserved for backward compat
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun LegacyAgentTimeline(
    message: ChatMessage,
    accentColor: Color,
    hasFinalAnswer: Boolean,
) {
    val steps = message.agentSteps
    steps.forEachIndexed { index, step ->
        val isLastStep = index == steps.lastIndex && !hasFinalAnswer
        TimelineNodeLayout(
            icon = { StepIcon(step, accentColor) },
            isLast = isLastStep,
            isPulsing = step.stepStatus == "started" || step.stepStatus == "streaming"
        ) {
            StepCard(step, accentColor)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared timeline layout primitives
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun TimelineNodeLayout(
    icon: @Composable () -> Unit,
    isLast: Boolean,
    isPulsing: Boolean = false,
    content: @Composable () -> Unit
) {
    val lineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)

    Row(modifier = Modifier.fillMaxWidth()) {
        // Timeline track
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(32.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                icon()

                if (isPulsing) {
                    val infiniteTransition = rememberInfiniteTransition(label = "nodePulse")
                    val alpha by infiniteTransition.animateFloat(
                        initialValue = 0.2f,
                        targetValue = 0.8f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1000, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "pulseAlpha"
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha), CircleShape)
                    )
                }
            }

            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .weight(1f, fill = false)
                        .heightIn(min = 20.dp)
                        .background(lineColor)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Box(modifier = Modifier.weight(1f).padding(bottom = if (isLast) 0.dp else 16.dp)) {
            content()
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Legacy step icon / card (kept for backward compat)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun StepIcon(step: AgentStepEntry, accentColor: Color) {
    val icon = when (step.stepType) {
        "thinking" -> Icons.Default.Psychology
        "tool_call", "opencode_tool" -> Icons.Default.Build
        "checkpoint" -> Icons.Default.Flag
        else -> Icons.Default.Memory
    }

    val color = when (step.stepStatus) {
        "completed" -> MaterialTheme.colorScheme.onSurfaceVariant
        "failed", "error" -> MaterialTheme.colorScheme.error
        else -> accentColor
    }

    Icon(
        imageVector = icon,
        contentDescription = step.stepTitle,
        modifier = Modifier.size(14.dp),
        tint = color
    )
}

@Composable
fun StepCard(step: AgentStepEntry, accentColor: Color) {
    var expanded by remember { mutableStateOf(false) }

    val backgroundColor = when (step.stepStatus) {
        "failed", "error" -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        "completed" -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        else -> accentColor.copy(alpha = 0.05f)
    }

    val borderColor = when (step.stepStatus) {
        "failed", "error" -> MaterialTheme.colorScheme.error.copy(alpha = 0.3f)
        "completed" -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
        else -> accentColor.copy(alpha = 0.2f)
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = backgroundColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = step.stepTitle,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )

                step.durationMs?.let {
                    Text(
                        text = "${it}ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }

            if (step.stepContent.isNotBlank()) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = expanded || step.stepStatus == "started" || step.stepStatus == "streaming",
                    enter = androidx.compose.animation.expandVertically(),
                    exit = androidx.compose.animation.shrinkVertically()
                ) {
                    Text(
                        text = step.stepContent.take(500) + if (step.stepContent.length > 500) "…" else "",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }
}
