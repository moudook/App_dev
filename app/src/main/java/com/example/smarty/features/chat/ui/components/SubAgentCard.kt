package com.example.smarty.features.chat.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarty.protocol.AgentEvent

/**
 * Card that surfaces sub-agent lifecycle activity emitted by the OpenCode
 * plugin v3 ([AgentEvent.SubAgentCreated] / [AgentEvent.SubAgentIdle]).
 *
 * Collapsed (default):  SmartToy icon + "Sub-agent: {title}" label +
 *                       optional duration / tool-count badge, and a chevron
 *                       (or a small spinner while the sub-agent is still
 *                       running).
 * Expanded:             the same header plus a vertical mini-timeline of
 *                       every event tagged with this sub-agent's
 *                       [AgentEvent.SubAgentCreated.sessionId].
 */
@Composable
fun SubAgentCard(
    created: AgentEvent.SubAgentCreated,
    idle: AgentEvent.SubAgentIdle?,
    subAgentEvents: List<AgentEvent>,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    val title = created.title?.takeIf { it.isNotBlank() } ?: "Task"
    val isRunning = idle == null

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        ),
        modifier =
            modifier
                .fillMaxWidth()
                .padding(start = 18.dp),
    ) {
        Column(
            modifier =
                Modifier
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.SmartToy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = "Sub-agent: $title",
                    fontSize = 13.sp,
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                IdleBadge(idle = idle)
                if (isRunning) {
                    CircularProgressIndicator(
                        strokeWidth = 1.5.dp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(14.dp),
                    )
                } else {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                ExpandedSubAgentTimeline(events = subAgentEvents)
            }
        }
    }
}

@Composable
private fun IdleBadge(idle: AgentEvent.SubAgentIdle?) {
    if (idle == null) return
    val duration = idle.durationMs
    val toolCount = idle.totalToolCalls
    if (duration == null && toolCount == null) return

    val label =
        when {
            duration != null && toolCount != null -> "${formatDuration(duration)} · $toolCount tools"
            duration != null -> formatDuration(duration)
            toolCount != null -> "$toolCount tools"
            else -> return
        }

    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                modifier = Modifier.size(10.dp),
            )
            Text(
                text = label,
                fontSize = 10.sp,
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

@Composable
private fun ExpandedSubAgentTimeline(events: List<AgentEvent>) {
    if (events.isEmpty()) {
        Text(
            text = "No activity yet",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
            modifier = Modifier.padding(start = 22.dp, top = 2.dp),
        )
        return
    }

    val scrollState = rememberScrollState()
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        events.forEach { event ->
            SubAgentEventRow(event = event)
        }
    }
}

@Composable
private fun SubAgentEventRow(event: AgentEvent) {
    when (event) {
        is AgentEvent.ReasoningBlock -> TimelineThinkingRow(content = event.content)
        is AgentEvent.ResponseBlock -> TimelineTextRow(content = event.content)
        is AgentEvent.ToolStart -> TimelineToolStartRow(name = event.name, args = event.args)
        is AgentEvent.ToolEnd -> Unit
        is AgentEvent.StepStart -> TimelineStepMarker(
            label = "Step ${event.stepNumber} started",
            title = event.title,
            isStart = true,
        )
        is AgentEvent.StepEnd -> TimelineStepMarker(
            label = if (event.success) "Step ${event.stepNumber} done" else "Step ${event.stepNumber} failed",
            title = null,
            isStart = false,
            failed = !event.success,
        )
        is AgentEvent.SubAgentCreated -> Unit
        is AgentEvent.SubAgentIdle -> Unit
        is AgentEvent.ThinkingActive -> TimelineSkeletonRow(label = "thinking…")
        is AgentEvent.StreamingActive -> TimelineSkeletonRow(label = "streaming…")
        is AgentEvent.TextDelta -> TimelineTextRow(content = event.text)
        is AgentEvent.ReasoningDelta -> TimelineThinkingRow(content = event.text)
        else -> Unit
    }
}

@Composable
private fun TimelineThinkingRow(content: String) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(start = 22.dp),
    ) {
        Text(
            text = "💭",
            fontSize = 11.sp,
        )
        Text(
            text = content,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TimelineTextRow(content: String) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(start = 22.dp),
    ) {
        Text(
            text = "💬",
            fontSize = 11.sp,
        )
        Text(
            text = content,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun TimelineToolStartRow(
    name: String,
    args: String?,
) {
    val summary =
        args
            ?.takeIf { it.isNotBlank() }
            ?.let { summarizeArgs(it) }
            .orEmpty()
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(start = 22.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Build,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
            modifier = Modifier.size(11.dp),
        )
        Text(
            text = name,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        if (summary.isNotEmpty()) {
            Text(
                text = summary,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TimelineStepMarker(
    label: String,
    title: String?,
    isStart: Boolean,
    failed: Boolean = false,
) {
    val tint =
        when {
            failed -> MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
            isStart -> MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
            else -> MaterialTheme.colorScheme.onSecondaryContainer
        }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(start = 22.dp, top = 2.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(tint),
        )
        Text(
            text =
                if (!title.isNullOrBlank()) {
                    "$label · $title"
                } else {
                    label
                },
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TimelineSkeletonRow(label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(start = 22.dp),
    ) {
        CircularProgressIndicator(
            strokeWidth = 1.5.dp,
            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f),
            modifier = Modifier.size(10.dp),
        )
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
        )
    }
}

private fun formatDuration(durationMs: Long): String {
    if (durationMs < 1000) return "${durationMs}ms"
    val totalSeconds = durationMs / 1000.0
    return if (totalSeconds < 60) {
        "%.1fs".format(totalSeconds)
    } else {
        val minutes = totalSeconds / 60
        val seconds = (totalSeconds % 60).toInt()
        if (seconds == 0) "${minutes}m" else "${minutes}m${seconds}s"
    }
}

private fun summarizeArgs(args: String): String {
    val collapsed = args.replace(Regex("\\s+"), " ").trim()
    return if (collapsed.length > 60) collapsed.take(57) + "…" else collapsed
}
