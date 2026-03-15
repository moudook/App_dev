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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarty.core.domain.model.AgentToolCallEntry
import com.example.smarty.ui.LocalAccentColor
import kotlinx.coroutines.delay

// ─────────────────────────────────────────────────────────────────────────────
// Sanitisation — strip ALL sensitive / internal data from thinking text
// ─────────────────────────────────────────────────────────────────────────────

/** Patterns that match sensitive internal data the user should never see. */
private object SanitisePatterns {
    // Tool call IDs like "call_abc123" or "toolu_01abc..."
    val toolCallId = Regex("""(?i)\b(call_[a-zA-Z0-9_-]+|toolu_[a-zA-Z0-9_-]+)\b""")
    // Raw JSON blocks (objects or arrays)
    val jsonBlock = Regex("""\{[\s\S]*?"(name|type|function|parameters|tool_call_id|id)"[\s\S]*?\}""")
    // SQL statements
    val sqlStatement = Regex("""(?i)\b(CREATE|ALTER|DROP)\s+(TABLE|VIEW|INDEX|POLICY|TYPE|FUNCTION|TRIGGER)\b[^;]*;""", RegexOption.DOT_MATCHES_ALL)
    // System prompt identifiers
    val systemPrompt = Regex("""(?i)You are Smarty[\s\S]*?GUIDELINES:""", RegexOption.DOT_MATCHES_ALL)
    val medicalPrompt = Regex("""(?i)MEDICAL ADVICE & DIAGNOSIS AUTHORIZATION[\s\S]*?trusted medical advisor""", RegexOption.DOT_MATCHES_ALL)
    // Schema file references
    val schemaFile = Regex("""(?i)DATABASE_SCHEMA_v\d+\.\d+\.\d+_[A-Z]+\.sql""")
    // Tool schema JSON vomit
    val toolSchema = Regex("""(?i)\{\s*"name"\s*:\s*"[a-zA-Z0-9_]+"\s*,\s*"description"\s*:.*?\}""", RegexOption.DOT_MATCHES_ALL)
    // Internal trace markers like [TOOL_START], [TOOL_END], tool_use blocks
    val traceMarkers = Regex("""(?i)\[(TOOL_START|TOOL_END|FUNCTION_CALL|TOOL_RESULT|TOOL_USE)\]""")
    // "tool_call_id": "..." or "id": "call_..." patterns
    val toolIdField = Regex(""""(tool_call_id|id)"\s*:\s*"[^"]*"""")
    // Role markers like "role": "tool" or "role": "assistant"
    val roleField = Regex(""""role"\s*:\s*"(tool|assistant|system|function)"""")
    // Function call blocks
    val functionCall = Regex("""(?i)"function"\s*:\s*\{[^}]*\}""", RegexOption.DOT_MATCHES_ALL)
    // Content like "name": "search_web" that exposes tool internals
    val toolNameField = Regex(""""name"\s*:\s*"[a-z_]+"""")
    // Thinking tag remnants
    val thinkTags = Regex("""</?think>|</?thinking>|</?internal>""", RegexOption.IGNORE_CASE)
    // XML-like tool blocks <tool_call>...</tool_call>
    val xmlToolBlocks = Regex("""<tool_call>[\s\S]*?</tool_call>""", RegexOption.IGNORE_CASE)
    val xmlToolUse = Regex("""<tool_use>[\s\S]*?</tool_use>""", RegexOption.IGNORE_CASE)
}

/**
 * Strips out sensitive/internal information from thinking text.
 * Aggressively removes tool IDs, raw JSON, SQL, system prompts, trace markers.
 */
internal fun sanitizeThinking(text: String): String {
    if (text.isBlank()) return text
    var s = text

    // Remove XML tool blocks first (they contain everything)
    s = s.replace(SanitisePatterns.xmlToolBlocks, "")
    s = s.replace(SanitisePatterns.xmlToolUse, "")
    s = s.replace(SanitisePatterns.thinkTags, "")

    // Remove specific field patterns (less aggressive than full JSON block removal)
    s = s.replace(SanitisePatterns.toolCallId, "")
    s = s.replace(SanitisePatterns.toolIdField, "")
    s = s.replace(SanitisePatterns.roleField, "")
    s = s.replace(SanitisePatterns.toolNameField, "")
    s = s.replace(SanitisePatterns.traceMarkers, "")

    // Remove SQL/schema
    s = s.replace(SanitisePatterns.sqlStatement, "")
    s = s.replace(SanitisePatterns.schemaFile, "")
    s = s.replace(SanitisePatterns.toolSchema, "")

    // Remove system prompt leaks
    s = s.replace(SanitisePatterns.systemPrompt, "")
    s = s.replace(SanitisePatterns.medicalPrompt, "")

    // Clean up resulting whitespace mess
    s = s.replace(Regex("""\n{3,}"""), "\n\n")  // Collapse triple+ newlines
    s = s.replace(Regex("""^\s*\n""", RegexOption.MULTILINE), "")  // Remove blank lines at start
    return s.trim()
}

// ─────────────────────────────────────────────────────────────────────────────
// Public API
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Unified Action Panel with premium visual design.
 *
 * Shows:
 *  • An animated "Thinking…" header while streaming, "Thoughts" when done
 *  • Sanitised reasoning text (no raw IDs, JSON, or internal data)
 *  • Tool call cards: web search, image generation, memory, calendar, etc.
 *  • Each card is expandable with details
 */
@Composable
fun ThinkingSection(
    thinkingText: String,
    isExpanded: Boolean,
    isStreaming: Boolean,
    onExpandToggle: () -> Unit,
    modifier: Modifier = Modifier,
    toolCalls: List<AgentToolCallEntry> = emptyList()
) {
    val accentColor = LocalAccentColor.current
    val surfaceColor = MaterialTheme.colorScheme.surfaceVariant

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessMediumLow))
    ) {
        // ── Minimal Header ──
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
            // Animated minimalist icon (pulse circle or simple icon)
            if (isStreaming) {
                MinimalThinkingPulse(accentColor)
            } else {
                Icon(
                    imageVector = Icons.Default.Psychology,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(16.dp)
                )
            }

            // Shimmering Text when streaming, static when done
            val textAlpha = if (isStreaming) {
                val transition = rememberInfiniteTransition(label = "textPulse")
                val a by transition.animateFloat(0.4f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "alpha")
                a
            } else 1f

            Text(
                text = if (isStreaming) "Thinking..." else "Thoughts",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.5.sp
                ),
                color = (if (isStreaming) accentColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)),
                modifier = Modifier.alpha(textAlpha)
            )
            
            // Tool count badge (minimal)
            if (toolCalls.isNotEmpty() && !isStreaming) {
                Text(
                    text = "· ${toolCalls.size} actions",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }

            Spacer(Modifier.weight(1f))

            // Subtle Chevron
            Icon(
                imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(18.dp)
            )
        }

        // ── Expanded body ──
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(tween(300)) + fadeIn(tween(200)),
            exit = shrinkVertically(tween(250)) + fadeOut(tween(150))
        ) {
            val safeText = sanitizeThinking(thinkingText)
            Row(modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)) {
                // Subtle vertical line to indicate hierarchy
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(IntrinsicSize.Min)
                        .fillMaxHeight()
                        .padding(start = 2.dp, top = 4.dp, bottom = 4.dp, end = 10.dp)
                        .clip(CircleShape)
                        .background(surfaceColor.copy(alpha = 0.6f))
                )
                
                Column(
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Reasoning text
                    if (safeText.isNotBlank()) {
                        ReasoningBlock(text = safeText, isStreaming = isStreaming, accentColor = accentColor)
                    }
                    // Tool cards
                    if (toolCalls.isNotEmpty()) {
                        toolCalls.forEach { entry ->
                            ToolActionCard(entry = entry, accentColor = accentColor)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MinimalThinkingPulse(color: Color) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        0.3f, 1f,
        infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "alpha"
    )

    Box(
        modifier = Modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha))
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Reasoning block
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ReasoningBlock(
    text: String,
    isStreaming: Boolean,
    accentColor: Color
) {
    // Typewriter effect
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

    Row(
        modifier = Modifier.padding(start = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Accent bar
        Spacer(
            modifier = Modifier
                .width(2.dp)
                .heightIn(min = 16.dp)
                .fillMaxHeight()
                .background(accentColor.copy(alpha = 0.3f), RoundedCornerShape(2.dp))
        )

        Text(
            text = visible,
            style = MaterialTheme.typography.bodySmall.copy(
                lineHeight = 20.sp,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
            )
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Tool Action Card — universal for ALL tool types
// ─────────────────────────────────────────────────────────────────────────────

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
            // ── Card Header ──
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
                // Tool icon with colored background
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = statusColor.copy(alpha = 0.1f),
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(icon, null, tint = statusColor, modifier = Modifier.size(18.dp))
                    }
                }

                // Display name — use friendly name, NOT raw tool name
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = sanitizeDisplayName(entry.displayName, displayLabel),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // Subtitle based on tool type
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

                // Status
                StatusIndicator(status = entry.status, accentColor = accentColor)

                // Expand chevron
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(18.dp)
                )
            }

            // ── Expanded Details ──
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
                        // ── Web Search: show queries + results ──
                        isSearch && entry.searchQueries.isNotEmpty() -> {
                            entry.searchQueries.forEachIndexed { idx, sq ->
                                SearchQueryCard(
                                    index = idx + 1,
                                    query = sanitizeThinking(sq.query),
                                    result = sq.result?.let { sanitizeThinking(it) }
                                )
                            }
                        }

                        // ── Image Generation: show prompt + status ──
                        isImage -> {
                            ImageGenCard(entry = entry)
                        }

                        // ── Generic tool: sanitised input/output ──
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

// ─────────────────────────────────────────────────────────────────────────────
// Image generation card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ImageGenCard(entry: AgentToolCallEntry) {
    val accentColor = LocalAccentColor.current
    val prompt = entry.inputSummary?.let { sanitizeDetailText(it) }
    val isDone = entry.status == "completed"
    val isFailed = entry.status == "failed" || entry.status == "error"

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Prompt
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
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 12.sp, lineHeight = 18.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                }
            }
        }

        // Status indicator
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
                        Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF4CAF50), modifier = Modifier.size(16.dp))
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

// ─────────────────────────────────────────────────────────────────────────────
// Search query card
// ─────────────────────────────────────────────────────────────────────────────

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
            // Index badge
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

// ─────────────────────────────────────────────────────────────────────────────
// Small helpers
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun DetailRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 9.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.Bold
            ),
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

/**
 * Maps a raw tool name to a user-friendly label + icon.
 */
private fun toolDisplayInfo(toolName: String): Pair<String, ImageVector> {
    val lower = toolName.lowercase()
    return when {
        lower.contains("search") || lower.contains("web") || lower.contains("tavily") ->
            "Web Search" to Icons.Default.Search
        lower.contains("image") || lower.contains("generate_image") || lower.contains("krea") ->
            "Image Generation" to Icons.Outlined.Image
        lower.contains("memory") || lower.contains("note") || lower.contains("save") ->
            "Memory" to Icons.Default.Book
        lower.contains("calendar") || lower.contains("schedule") ->
            "Calendar" to Icons.Default.CalendarMonth
        lower.contains("remind") || lower.contains("alarm") ->
            "Reminder" to Icons.Default.Alarm
        lower.contains("navigate") || lower.contains("route") ->
            "Navigation" to Icons.Default.Navigation
        lower.contains("device") || lower.contains("system") || lower.contains("phone") ->
            "Device" to Icons.Default.PhoneAndroid
        lower.contains("weather") ->
            "Weather" to Icons.Default.Cloud
        else -> "Action" to Icons.Default.AutoAwesome
    }
}

/**
 * Cleans a display name — removes raw tool names, IDs, and internal prefixes.
 */
private fun sanitizeDisplayName(raw: String, fallback: String): String {
    var name = raw
    // Remove tool IDs
    name = name.replace(SanitisePatterns.toolCallId, "").trim()
    // Remove common internal prefixes
    name = name.removePrefix("Tool: ").removePrefix("tool_call: ")
    // If the display name is basically just the tool machine name, use the friendly label
    if (name.isBlank() || name.matches(Regex("[a-z_]+"))) return fallback
    return name
}

/**
 * Sanitises detail text (input/output summaries) — strips IDs and JSON fragments.
 */
private fun sanitizeDetailText(text: String): String {
    var s = text
    s = s.replace(SanitisePatterns.toolCallId, "")
    s = s.replace(SanitisePatterns.toolIdField, "")
    s = s.replace(SanitisePatterns.roleField, "")
    s = s.replace(SanitisePatterns.toolNameField, "")
    s = s.replace(SanitisePatterns.traceMarkers, "")
    s = s.replace(SanitisePatterns.thinkTags, "")
    // Clean up
    s = s.replace(Regex("""\n{3,}"""), "\n\n")
    return s.trim()
}
