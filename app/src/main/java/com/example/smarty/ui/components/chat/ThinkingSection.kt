package com.example.smarty.ui.components.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
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
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarty.core.domain.model.AgentStepEntry
import com.example.smarty.core.domain.model.AgentToolCallEntry
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.components.markdown.MarkdownRenderer
import com.example.smarty.features.chat.ui.thinking.OrganicThinkingIndicator
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.delay
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset

// ─────────────────────────────────────────────────────────────────────────────
// Sanitisation — strip ONLY sensitive data, keep all reasoning/features
// ─────────────────────────────────────────────────────────────────────────────

/** Patterns for ONLY sensitive internal data. */
private object SanitisePatterns {
    val toolCallId = Regex("""(?i)\b(call_[a-zA-Z0-9_-]+|toolu_[a-zA-Z0-9_-]+)\b""")
    val sqlStatement = Regex("""(?i)\b(CREATE|ALTER|DROP)\s+(TABLE|VIEW|INDEX|POLICY|TYPE|FUNCTION|TRIGGER)\b[^;]*;""", RegexOption.DOT_MATCHES_ALL)
    val systemPrompt = Regex("""(?i)You are Smarty[\s\S]*?GUIDELINES:""", RegexOption.DOT_MATCHES_ALL)
    val medicalPrompt = Regex("""(?i)MEDICAL ADVICE & DIAGNOSIS AUTHORIZATION[\s\S]*?trusted medical advisor""", RegexOption.DOT_MATCHES_ALL)
    val schemaFile = Regex("""(?i)DATABASE_SCHEMA_v\d+\.\d+\.\d+_[A-Z]+\.sql""")
    val traceMarkers = Regex("""(?i)\[(TOOL_START|TOOL_END|FUNCTION_CALL|TOOL_RESULT|TOOL_USE|DEBUG|TRACE)\]""")
    val toolIdField = Regex("""tool_call_id"\s*:\s*"[^"]*"""")
    val functionCall = Regex("""(?i)"function"\s*:\s*\{[^}]*\}""", RegexOption.DOT_MATCHES_ALL)
    val thinkTags = Regex("""</?think>|</?thinking>|</?internal>|</?final>""", RegexOption.IGNORE_CASE)
    val xmlToolBlocks = Regex("""<tool_call>[\s\S]*?</tool_call>""", RegexOption.IGNORE_CASE)
    val xmlToolUse = Regex("""<tool_use>[\s\S]*?</tool_use>""", RegexOption.IGNORE_CASE)
    val jsonOnlyLines = Regex("""(?m)^\s*[\{\[\}\],:"]+\s*$""")
}

/** Extracts reasoning text from AI streaming JSON format. */
internal fun extractReasoningText(raw: String): String {
    if (raw.isBlank()) return ""
    val trimmed = raw.trim()
    if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
        return try {
            val type = object : TypeToken<List<Map<String, String>>>() {}.type
            val list: List<Map<String, String>> = Gson().fromJson(trimmed, type)
            list.filter { it["type"] == "reasoning" }
                .map { it["text"] ?: "" }
                .filter { it.isNotBlank() }
                .joinToString("\n\n")
        } catch (e: Exception) {
            Regex("\"text\"\\s*:\\s*\"([^\"]+)\"").find(trimmed)?.groupValues?.get(1) ?: raw
        }
    }
    if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
        return try {
            val map: Map<String, String> = Gson().fromJson(trimmed, object : TypeToken<Map<String, String>>() {}.type)
            map["text"] ?: raw
        } catch (e: Exception) { raw }
    }
    return raw
}

/** Strips ONLY sensitive data, keeps all reasoning/features. */
internal fun sanitizeThinking(text: String): String {
    if (text.isBlank()) return text
    var s = extractReasoningText(text)
    if (s.isBlank()) return ""
    s = s.replace(SanitisePatterns.toolCallId, "")
    s = s.replace(SanitisePatterns.traceMarkers, "")
    s = s.replace(SanitisePatterns.sqlStatement, "")
    s = s.replace(SanitisePatterns.schemaFile, "")
    s = s.replace(SanitisePatterns.systemPrompt, "")
    s = s.replace(SanitisePatterns.medicalPrompt, "")
    s = s.replace(SanitisePatterns.thinkTags, "")
    s = s.replace(Regex("""\n{3,}"""), "\n\n")
    s = s.replace(Regex("""^\s*\n""", RegexOption.MULTILINE), "")
    return s.trim()
}

// ─────────────────────────────────────────────────────────────────────────────
// Public API
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ThinkingSection(
    thinkingText: String,
    agentSteps: List<AgentStepEntry> = emptyList(),
    isExpanded: Boolean,
    isStreaming: Boolean,
    onExpandToggle: () -> Unit,
    modifier: Modifier = Modifier,
    toolCalls: List<AgentToolCallEntry> = emptyList()
) {
    val accentColor = LocalAccentColor.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onExpandToggle() }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isStreaming) {
                    OrganicThinkingIndicator(
                        size = 18.dp,
                        baseColor = accentColor
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Psychology,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(16.dp)
                    )
                }
                val textAlpha = if (isStreaming) {
                    val transition = rememberInfiniteTransition(label = "textPulse")
                    val a by transition.animateFloat(0.4f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "alpha")
                    a
                } else 1f
                Text(
                    text = when {
                        isStreaming && toolCalls.isNotEmpty() -> {
                            val lastTool = toolCalls.last()
                            val name = lastTool.displayName.ifBlank { lastTool.toolName }
                            if (name.length > 30) "Thinking" else name
                        }
                        isStreaming -> "Thinking"
                        else -> "Thoughts"
                    },
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp),
                    color = (if (isStreaming) accentColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)),
                    modifier = Modifier.alpha(textAlpha),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                if (toolCalls.isNotEmpty()) {
                    Text(
                        text = if (isStreaming) "step ${toolCalls.size}" else "· ${toolCalls.size} actions",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isStreaming) accentColor.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
                Spacer(Modifier.weight(1f))
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(18.dp)
                )
            }
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(tween(300)) + fadeIn(tween(200)),
                exit = shrinkVertically(tween(250)) + fadeOut(tween(150))
            ) {
                val safeText = sanitizeThinking(thinkingText)
                val hasContent = agentSteps.isNotEmpty() || safeText.isNotBlank() || toolCalls.isNotEmpty()
                if (hasContent) {
                    Column(
                        modifier = Modifier
                            .padding(top = 12.dp, bottom = 8.dp, start = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        if (agentSteps.isNotEmpty()) {
                            agentSteps.forEach { step ->
                                TimelineNodeItem(status = step.stepStatus, accentColor = accentColor) {
                                    if (step.stepType == "thinking" && step.stepContent.isNotBlank()) {
                                        val safeStep = sanitizeThinking(step.stepContent)
                                        if (safeStep.isNotBlank()) {
                                            ReasoningBlock(text = safeStep, isStreaming = step.stepStatus == "streaming", accentColor = accentColor)
                                        }
                                    } else if (step.stepType == "tool_call" || step.stepType == "opencode_tool" || step.stepType == "tool_result") {
                                        val toolEntry = toolCalls.find { it.toolName == step.toolName }?.copy(status = step.stepStatus)
                                            ?: AgentToolCallEntry(
                                                toolName = step.toolName ?: "action",
                                                displayName = step.stepTitle,
                                                status = step.stepStatus,
                                                inputSummary = step.stepContent
                                            )
                                        ToolActionCard(entry = toolEntry, accentColor = accentColor)
                                    } else {
                                        Text(
                                            text = step.stepTitle,
                                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        } else {
                            if (safeText.isNotBlank()) {
                                TimelineNodeItem(status = if (isStreaming) "streaming" else "completed", accentColor = accentColor) {
                                    ReasoningBlock(text = safeText, isStreaming = isStreaming, accentColor = accentColor)
                                }
                            }
                            if (toolCalls.isNotEmpty()) {
                                toolCalls.forEach { entry ->
                                    TimelineNodeItem(status = entry.status, accentColor = accentColor) {
                                        ToolActionCard(entry = entry, accentColor = accentColor)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineNodeItem(
    status: String,
    accentColor: Color,
    content: @Composable () -> Unit
) {
    val nodeColor = when (status) {
        "completed" -> Color(0xFF4CAF50)
        "failed", "error" -> MaterialTheme.colorScheme.error
        else -> accentColor
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top
    ) {
        if (status == "started" || status == "streaming") {
            Box(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .size(12.dp),
                contentAlignment = Alignment.Center
            ) {
                OrganicThinkingIndicator(
                    size = 12.dp,
                    baseColor = nodeColor
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(nodeColor.copy(alpha = 0.8f))
            )
        }
        Box(modifier = Modifier.weight(1f)) {
            content()
        }
    }
}

@Composable
private fun ReasoningBlock(
    text: String,
    isStreaming: Boolean,
    accentColor: Color
) {
    var displayLen by remember { mutableIntStateOf(if (isStreaming) 0 else text.length) }
    LaunchedEffect(text, isStreaming) {
        if (isStreaming) {
            while (displayLen < text.length) {
                displayLen += minOf(4, text.length - displayLen)
                delay(18)
            }
        } else {
            displayLen = text.length
        }
    }
    val visible = remember(displayLen, text) {
        if (displayLen >= text.length) text else text.substring(0, displayLen)
    }
    Column(modifier = Modifier.padding(start = 4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(
                modifier = Modifier
                    .width(2.dp)
                    .heightIn(min = 16.dp)
                    .fillMaxHeight()
                    .background(accentColor.copy(alpha = 0.3f), RoundedCornerShape(2.dp))
            )
            Column(modifier = Modifier.weight(1f)) {
                MarkdownRenderer(
                    content = visible,
                    isUser = false,
                    normalColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    boldColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                    linkColor = accentColor.copy(alpha = 0.8f),
                    codeColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    codeBackgroundColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.4f),
                    codeBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                    isStreaming = isStreaming && displayLen < text.length
                )
            }
        }
    }
}

@Composable
private fun ToolActionCard(
    entry: AgentToolCallEntry,
    accentColor: Color
) {
    var expanded by remember { mutableStateOf(false) }
    val (displayLabel, icon) = toolDisplayInfo(entry.toolName)
    val isSearch = entry.toolName.lowercase().let {
        it.contains("search") || it.contains("web") || it.contains("tavily")
    }
    val isImage = entry.toolName.lowercase().let {
        it.contains("image") || it.contains("generate_image") || it.contains("krea")
    }
    val statusColor = when (entry.status) {
        "failed", "error" -> MaterialTheme.colorScheme.error
        "started" -> accentColor
        else -> Color(0xFF4CAF50)
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(0.5.dp, statusColor.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { expanded = !expanded }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = statusColor.copy(alpha = 0.1f),
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(icon, null, tint = statusColor, modifier = Modifier.size(18.dp))
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = sanitizeDisplayName(entry.displayName, displayLabel),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val subtitle = when {
                        isImage -> "Image generation"
                        isSearch -> "Web research"
                        else -> displayLabel
                    }
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
                StatusIndicator(status = entry.status, accentColor = accentColor)
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(18.dp)
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(tween(220)) + fadeIn(),
                exit = shrinkVertically(tween(180)) + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                    when {
                        isSearch && entry.searchQueries.isNotEmpty() -> {
                            entry.searchQueries.forEachIndexed { idx, sq ->
                                SearchQueryCard(
                                    index = idx + 1,
                                    query = sanitizeThinking(sq.query),
                                    result = sq.result?.let { sanitizeThinking(it) }
                                )
                            }
                        }
                        isImage -> {
                            ImageGenCard(entry = entry)
                        }
                        else -> {
                            val input = entry.inputSummary?.let { sanitizeDetailText(it) }
                            val output = entry.outputSummary?.let { sanitizeDetailText(it) }
                            if (!input.isNullOrBlank()) DetailRow("Input", input)
                            if (!output.isNullOrBlank()) DetailRow("Result", output)
                            if (input.isNullOrBlank() && output.isNullOrBlank()) {
                                Text(
                                    "No details available",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageGenCard(entry: AgentToolCallEntry) {
    val accentColor = LocalAccentColor.current
    val prompt = entry.inputSummary?.let { sanitizeDetailText(it) }
    val isDone = entry.status == "completed"
    val isFailed = entry.status == "failed" || entry.status == "error"
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!prompt.isNullOrBlank()) {
            Surface(
                color = accentColor.copy(alpha = 0.06f),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        Icons.Default.AutoAwesome, null,
                        tint = accentColor.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = prompt,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                }
            }
        }
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = when {
                isDone -> Color(0xFF4CAF50).copy(alpha = 0.1f)
                isFailed -> MaterialTheme.colorScheme.error.copy(alpha = 0.1f)
                else -> accentColor.copy(alpha = 0.1f)
            }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when {
                    isDone -> {
                        Icon(Icons.Outlined.CheckCircle, null, tint = Color(0xFF4CAF50), modifier = Modifier.size(16.dp))
                        Text("Image generated", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium), color = Color(0xFF4CAF50))
                    }
                    isFailed -> {
                        Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                        Text("Generation failed", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.error)
                    }
                    else -> {
                        val pulse = rememberInfiniteTransition(label = "imgPulse")
                        val a by pulse.animateFloat(0.3f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
                        Box(Modifier.size(8.dp).clip(CircleShape).background(accentColor.copy(alpha = a)))
                        Text("Generating…", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium), color = accentColor)
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchQueryCard(index: Int, query: String, result: String?) {
    val accentColor = LocalAccentColor.current
    var showResult by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showResult = !showResult }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = accentColor.copy(alpha = 0.12f),
                modifier = Modifier.size(20.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("$index", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold), color = accentColor)
                }
            }
            Text(
                text = query,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (result != null) {
                Icon(
                    if (showResult) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    "Toggle result",
                    modifier = Modifier.size(16.dp).alpha(0.5f),
                    tint = accentColor
                )
            }
        }
        AnimatedVisibility(visible = showResult && result != null) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = result ?: "",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 17.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StatusIndicator(status: String, accentColor: Color) {
    when (status) {
        "completed" -> Icon(Icons.Outlined.CheckCircle, "Done", tint = Color(0xFF4CAF50), modifier = Modifier.size(16.dp))
        "failed", "error" -> Icon(Icons.Default.ErrorOutline, "Failed", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
        else -> {
            val pulse = rememberInfiniteTransition(label = "statusPulse")
            val alpha by pulse.animateFloat(0.3f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
            Box(Modifier.size(8.dp).clip(CircleShape).background(accentColor.copy(alpha = alpha)))
        }
    }
}

private fun toolDisplayInfo(toolName: String): Pair<String, ImageVector> {
    val lower = toolName.lowercase()
    return when {
        lower.contains("search") || lower.contains("web") || lower.contains("tavily") -> "Web Search" to Icons.Default.Search
        lower.contains("image") || lower.contains("generate_image") || lower.contains("krea") -> "Image Generation" to Icons.Outlined.Image
        lower.contains("memory") || lower.contains("note") || lower.contains("save") -> "Memory" to Icons.Default.Book
        lower.contains("calendar") || lower.contains("schedule") -> "Calendar" to Icons.Default.CalendarMonth
        lower.contains("remind") || lower.contains("alarm") -> "Reminder" to Icons.Default.Alarm
        lower.contains("navigate") || lower.contains("route") -> "Navigation" to Icons.Default.Navigation
        lower.contains("device") || lower.contains("system") || lower.contains("phone") -> "Device" to Icons.Default.PhoneAndroid
        lower.contains("weather") -> "Weather" to Icons.Default.Cloud
        else -> "Action" to Icons.Default.AutoAwesome
    }
}

private fun sanitizeDisplayName(raw: String, fallback: String): String {
    var name = raw
    name = name.replace(SanitisePatterns.toolCallId, "").trim()
    name = name.removePrefix("Tool: ").removePrefix("tool_call: ")
    if (name.isBlank() || name.matches(Regex("[a-z_]+"))) return fallback
    return name
}

private fun sanitizeDetailText(text: String): String {
    var s = text
    s = s.replace(SanitisePatterns.toolCallId, "")
    s = s.replace(SanitisePatterns.traceMarkers, "")
    s = s.replace(SanitisePatterns.thinkTags, "")
    s = s.replace(Regex("""\n{3,}"""), "\n\n")
    return s.trim()
}