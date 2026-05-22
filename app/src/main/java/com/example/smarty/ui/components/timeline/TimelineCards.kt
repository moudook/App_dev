package com.example.smarty.ui.components.timeline

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarty.features.chat.ui.thinking.OrganicThinkingIndicator
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.components.markdown.MarkdownRenderer

// ─────────────────────────────────────────────────────────────────────────────
// Tier 1 — Semantic Cards
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Collapsible reasoning / thinking card.
 * Shows a pulsing OrganicThinkingIndicator while [isStreaming].
 * Contains a left-border formatted markdown reasoning block.
 */
@Composable
fun ThinkingCard(
    node: TimelineNode.Thinking,
    modifier: Modifier = Modifier,
) {
    val accentColor = LocalAccentColor.current
    var expanded by remember(node.id) { mutableStateOf(node.isStreaming || node.text.isNotBlank()) }

    LaunchedEffect(node.isStreaming) {
        if (node.isStreaming) expanded = true
    }

    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        // Header row — always visible
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { expanded = !expanded }
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (node.isStreaming) {
                OrganicThinkingIndicator(size = 16.dp, baseColor = accentColor)
            } else {
                Icon(
                    imageVector = Icons.Default.Psychology,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier.size(16.dp),
                )
            }

            val labelAlpha = if (node.isStreaming) {
                val transition = rememberInfiniteTransition(label = "thinkingPulse")
                val a by transition.animateFloat(
                    initialValue = 0.4f, targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
                    label = "a"
                )
                a
            } else 1f

            Text(
                text = if (node.isStreaming) "Thinking…" else "Thoughts",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.5.sp,
                ),
                color = if (node.isStreaming) accentColor
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                modifier = Modifier.alpha(labelAlpha),
            )

            Spacer(Modifier.weight(1f))

            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier.size(18.dp),
            )
        }

        // Content — collapsible
        AnimatedVisibility(
            visible = expanded && node.text.isNotBlank(),
            enter = expandVertically(tween(280)) + fadeIn(tween(200)),
            exit = shrinkVertically(tween(220)) + fadeOut(tween(150)),
        ) {
            Row(
                modifier = Modifier
                    .padding(start = 8.dp, top = 10.dp, bottom = 8.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Spacer(
                    modifier = Modifier
                        .width(2.dp)
                        .heightIn(min = 16.dp)
                        .fillMaxHeight()
                        .background(accentColor.copy(alpha = 0.28f), RoundedCornerShape(2.dp))
                )
                Box(modifier = Modifier.weight(1f)) {
                    MarkdownRenderer(
                        content = node.text,
                        isUser = false,
                        normalColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        boldColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                        linkColor = accentColor.copy(alpha = 0.75f),
                        codeColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        codeBackgroundColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.4f),
                        codeBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                        isStreaming = node.isStreaming,
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────

/**
 * Tool execution card. Expandable to show input and output summaries.
 * Adapts icon, label, and color to the tool name and status.
 */
@Composable
fun ToolCallCard(
    node: TimelineNode.ToolExecution,
    modifier: Modifier = Modifier,
) {
    val accentColor = LocalAccentColor.current
    var expanded by remember(node.id) { mutableStateOf(false) }
    val (displayLabel, icon) = toolMeta(node.toolName)

    val statusColor = when (node.status) {
        TimelineNode.ToolExecution.Status.COMPLETED -> Color(0xFF4CAF50)
        TimelineNode.ToolExecution.Status.FAILED -> MaterialTheme.colorScheme.error
        TimelineNode.ToolExecution.Status.RUNNING -> accentColor
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(0.5.dp, statusColor.copy(alpha = 0.3f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { expanded = !expanded }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Tool icon chip
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = statusColor.copy(alpha = 0.1f),
                    modifier = Modifier.size(32.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            icon, null,
                            tint = statusColor,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = node.displayName.ifBlank { displayLabel },
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = when {
                            node.source == "mcp" -> "MCP · $displayLabel"
                            node.toolName.lowercase().contains("search") -> "Web research"
                            node.toolName.lowercase().contains("image") -> "Image generation"
                            else -> displayLabel
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    )
                }

                // Status indicator
                ToolStatusIndicator(status = node.status, accentColor = accentColor)

                // Duration
                node.durationMs?.let { ms ->
                    Text(
                        text = if (ms >= 1000) "${ms / 1000}s" else "${ms}ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                    )
                }

                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                    modifier = Modifier.size(18.dp),
                )
            }

            // Expandable detail section
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(tween(220)) + fadeIn(),
                exit = shrinkVertically(tween(180)) + fadeOut(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                    node.inputSummary?.takeIf { it.isNotBlank() }?.let {
                        DetailLabel("Input")
                        DetailValue(it)
                    }
                    node.outputSummary?.takeIf { it.isNotBlank() }?.let {
                        DetailLabel("Result")
                        DetailValue(it.take(1200))
                    }
                    if (node.inputSummary.isNullOrBlank() && node.outputSummary.isNullOrBlank()) {
                        Text(
                            "No details yet",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────

/**
 * Approval gate card — shows a pending/granted/denied state for tool invocations
 * that require user authorization. Supports two modes:
 *  - Boolean approve/deny buttons (default)
 *  - Free-text input + Submit (when [node.requiresText] is true, e.g. ask_user)
 */
@Composable
fun ApprovalCard(
    node: TimelineNode.ApprovalGate,
    onGrant: () -> Unit = {},
    onDeny: () -> Unit = {},
    onTextSubmit: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val accentColor = LocalAccentColor.current
    var textInput by remember { mutableStateOf("") }
    val borderColor by animateColorAsState(
        targetValue = when (node.status) {
            TimelineNode.ApprovalGate.Status.GRANTED -> Color(0xFF4CAF50)
            TimelineNode.ApprovalGate.Status.DENIED -> MaterialTheme.colorScheme.error
            TimelineNode.ApprovalGate.Status.PENDING -> accentColor
        },
        label = "approvalBorder"
    )

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, borderColor.copy(alpha = 0.4f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = borderColor,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = node.toolTitle.ifBlank { "Tool Requires Approval" },
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                when (node.status) {
                    TimelineNode.ApprovalGate.Status.GRANTED -> {
                        Icon(Icons.Outlined.CheckCircle, "Approved", tint = Color(0xFF4CAF50), modifier = Modifier.size(16.dp))
                        Text("Approved", style = MaterialTheme.typography.labelSmall, color = Color(0xFF4CAF50))
                    }
                    TimelineNode.ApprovalGate.Status.DENIED -> {
                        Icon(Icons.Default.Cancel, "Denied", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                        Text("Denied", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                    TimelineNode.ApprovalGate.Status.PENDING -> {}
                }
            }

            if (node.toolArgs.isNotBlank()) {
                Text(
                    text = node.toolArgs,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }

            when {
                node.status == TimelineNode.ApprovalGate.Status.PENDING && node.requiresText -> {
                    // Free-text input mode (ask_user)
                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        label = { Text("Your response") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(
                            onClick = onDeny,
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) {
                            Text("Cancel")
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { onTextSubmit?.invoke(textInput) },
                            enabled = textInput.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                        ) {
                            Text("Submit")
                        }
                    }
                }

                node.status == TimelineNode.ApprovalGate.Status.PENDING -> {
                    // Boolean approve/deny buttons
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = onDeny,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Deny", style = MaterialTheme.typography.labelMedium)
                        }
                        Button(
                            onClick = onGrant,
                            colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Approve", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────

/**
 * Error / aborted card. Shows the error message and an icon.
 */
@Composable
fun ErrorCard(
    node: TimelineNode.ErrorNode,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(modifier = Modifier.padding(top = 2.dp)) {
                Icon(
                    imageVector = if (node.isAborted) Icons.Default.StopCircle else Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (node.isAborted) "Session Aborted" else "Error",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = node.message,
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────

/**
 * Recovery-in-progress card.
 */
@Composable
fun RecoveryCard(
    node: TimelineNode.RecoveryNode,
    modifier: Modifier = Modifier,
) {
    val accentColor = LocalAccentColor.current
    val succeeded = node.succeeded

    val indicatorColor = when (succeeded) {
        true -> Color(0xFF4CAF50)
        false -> MaterialTheme.colorScheme.error
        null -> accentColor
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = indicatorColor.copy(alpha = 0.08f),
        border = BorderStroke(0.5.dp, indicatorColor.copy(alpha = 0.3f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (succeeded == null) {
                val pulse = rememberInfiniteTransition(label = "recoveryPulse")
                val a by pulse.animateFloat(0.3f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "a")
                Box(Modifier.size(10.dp).clip(CircleShape).background(accentColor.copy(alpha = a)))
            } else {
                Icon(
                    imageVector = if (succeeded) Icons.Outlined.CheckCircle else Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = indicatorColor,
                    modifier = Modifier.size(16.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when (succeeded) {
                        true -> "Recovery Succeeded"
                        false -> "Recovery Failed"
                        null -> "Recovering…"
                    },
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = indicatorColor,
                )
                Text(
                    text = node.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Tier 2 — System Activity Card
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Collapsed system activity chip. Expandable to show DB/cache/sync metrics.
 * Hidden unless at least one background operation has occurred.
 */
@Composable
fun SystemActivityCard(
    node: TimelineNode.SystemActivity,
    modifier: Modifier = Modifier,
) {
    if (node.totalOps == 0) return

    var expanded by remember(node.id) { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { expanded = !expanded },
    ) {
        Column {
            // Collapsed chip row
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(12.dp),
                )
                Text(
                    text = buildString {
                        append("⚙ ${node.totalOps} background op${if (node.totalOps != 1) "s" else ""}")
                        if (node.durationMs > 0L) append(" · ${node.durationMs}ms")
                        if (node.isOngoing) append(" · syncing…")
                    },
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                    modifier = Modifier.size(12.dp),
                )
            }

            // Expanded breakdown
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(tween(200)) + fadeIn(),
                exit = shrinkVertically(tween(160)) + fadeOut(),
            ) {
                Column(
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                    Spacer(Modifier.height(4.dp))
                    if (node.cacheHits > 0) SystemActivityRow("Cache hits", node.cacheHits, Icons.Default.FlashOn, Color(0xFF4CAF50))
                    if (node.cacheMisses > 0) SystemActivityRow("Cache misses", node.cacheMisses, Icons.Default.FlashOff, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                    if (node.dbReads > 0) SystemActivityRow("DB reads", node.dbReads, Icons.Default.Storage, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                    if (node.dbWrites > 0) SystemActivityRow("DB writes", node.dbWrites, Icons.Default.Save, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                    if (node.syncCount > 0) SystemActivityRow("Sync ops", node.syncCount, Icons.Default.Sync, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                }
            }
        }
    }
}

@Composable
private fun SystemActivityRow(label: String, count: Int, icon: ImageVector, tint: Color) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(11.dp))
        Text(
            text = "$label: $count",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Private helpers
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ToolStatusIndicator(status: TimelineNode.ToolExecution.Status, accentColor: Color) {
    when (status) {
        TimelineNode.ToolExecution.Status.COMPLETED ->
            Icon(Icons.Outlined.CheckCircle, "Done", tint = Color(0xFF4CAF50), modifier = Modifier.size(15.dp))
        TimelineNode.ToolExecution.Status.FAILED ->
            Icon(Icons.Default.ErrorOutline, "Failed", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(15.dp))
        TimelineNode.ToolExecution.Status.RUNNING -> {
            val pulse = rememberInfiniteTransition(label = "toolPulse")
            val a by pulse.animateFloat(0.3f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
            Box(Modifier.size(8.dp).clip(CircleShape).background(accentColor.copy(alpha = a)))
        }
    }
}

@Composable
private fun DetailLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
    )
}

@Composable
private fun DetailValue(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun toolMeta(toolName: String): Pair<String, ImageVector> {
    val lower = toolName.lowercase()
    return when {
        lower.contains("search") || lower.contains("web") || lower.contains("tavily") ->
            "Web Search" to Icons.Default.Search
        lower.contains("image") || lower.contains("generate") || lower.contains("krea") ->
            "Image Gen" to Icons.Default.AutoAwesome
        lower.contains("memory") || lower.contains("note") || lower.contains("save") ->
            "Memory" to Icons.Default.Book
        lower.contains("calendar") || lower.contains("schedule") ->
            "Calendar" to Icons.Default.CalendarMonth
        lower.contains("remind") || lower.contains("alarm") ->
            "Reminder" to Icons.Default.Alarm
        lower.contains("navigate") ->
            "Navigation" to Icons.Default.Navigation
        lower.contains("device") || lower.contains("system") ->
            "System" to Icons.Default.PhoneAndroid
        lower.contains("audio") || lower.contains("music") || lower.contains("play") ->
            "Audio" to Icons.Default.MusicNote
        lower.contains("task") ->
            "Tasks" to Icons.Default.CheckBox
        else -> "Action" to Icons.Default.Build
    }
}
