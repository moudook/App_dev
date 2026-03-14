package com.example.smarty.ui.components.chat

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarty.core.domain.model.AgentToolCallEntry
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.theme.ComponentSpacing
import com.example.smarty.ui.theme.smartyShapes
import kotlinx.coroutines.delay

private data class ThinkingColors(
    val background: Color,
    val border: Color,
    val text: Color
)

// ─────────────────────────────────────────────────────────────────────────────
// Public API
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Unified Action Panel — replaces the old plain-text ThinkingSection.
 *
 * Shows a collapsible panel with:
 *  • A "Thinking…" header (animated while streaming, "Thoughts" when done)
 *  • Interleaved reasoning text blocks and tool-call action cards
 *  • Expandable web-search result detail per action
 *
 * This is the Gemini/ChatGPT-style action panel the user requested.
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
    val thinkingColors = ThinkingColors(
        background = if (isStreaming) {
            accentColor.copy(alpha = 0.08f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        },
        border = if (isStreaming) {
            accentColor.copy(alpha = 0.3f)
        } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        },
        text = MaterialTheme.colorScheme.onSurface
    )

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = thinkingColors.background,
        border = BorderStroke(1.dp, thinkingColors.border),
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onExpandToggle() }
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            // ── Header ─────────────────────────────────────────────────────
            // Always show tool count, even when collapsed
            ActionPanelHeader(
                isStreaming  = isStreaming,
                isExpanded   = isExpanded,
                toolCount    = toolCalls.size,
                accentColor  = accentColor,
                thinkingColors = thinkingColors
            )

            // ── Body (expanded) ────────────────────────────────────────────
            AnimatedVisibility(
                visible = isExpanded,
                enter   = expandVertically(animationSpec = tween(300)) + fadeIn(),
                exit    = shrinkVertically(animationSpec = tween(250)) + fadeOut()
            ) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    // If there are tool calls, render interleaved blocks
                    if (toolCalls.isNotEmpty()) {
                        InterleavedContent(
                            reasoningText = thinkingText,
                            toolCalls     = toolCalls,
                            isStreaming   = isStreaming,
                            accentColor   = accentColor,
                            thinkingColors = thinkingColors
                        )
                    } else if (thinkingText.isNotBlank()) {
                        // Plain reasoning only (old messages / no tool calls)
                        ReasoningBlock(
                            text           = thinkingText,
                            isStreaming    = isStreaming,
                            accentColor    = accentColor,
                            thinkingColors = thinkingColors
                        )
                    }
                }
            }
            
            // Show tool summary when collapsed and there are tools
            if (!isExpanded && toolCalls.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                ToolSummaryRow(toolCalls = toolCalls, accentColor = accentColor)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Header
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ActionPanelHeader(
    isStreaming: Boolean,
    isExpanded: Boolean,
    toolCount: Int,
    accentColor: Color,
    thinkingColors: ThinkingColors
) {
    val headerAlpha by animateFloatAsState(
        targetValue = if (isExpanded) 1f else 0.8f,
        animationSpec = tween(200),
        label = "headerAlpha"
    )
    
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Icon with animation
        if (isStreaming) {
            ThinkingEmojiAnimation()
        } else {
            Icon(
                imageVector = Icons.Default.Psychology,
                contentDescription = null,
                tint = accentColor.copy(alpha = 0.9f),
                modifier = Modifier.size(20.dp)
            )
        }
        
        // Text label
        if (isStreaming) {
            Text(
                text  = "Thinking…",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight    = FontWeight.SemiBold,
                    letterSpacing = 0.3.sp
                ),
                color = accentColor
            )
        } else {
            Text(
                text  = "Thoughts",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight    = FontWeight.SemiBold,
                    letterSpacing = 0.3.sp
                ),
                color = thinkingColors.text.copy(alpha = 0.9f * headerAlpha)
            )
            if (toolCount > 0) {
                ActionCountBadge(count = toolCount, color = accentColor)
            }
        }

        Spacer(Modifier.weight(1f))

        // Expand/collapse chevron
        Icon(
            imageVector        = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = if (isExpanded) "Collapse" else "Expand",
            tint               = thinkingColors.text.copy(alpha = 0.6f),
            modifier           = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun ActionCountBadge(count: Int, color: Color) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.15f),
        modifier = Modifier.padding(horizontal = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Build,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text  = "$count",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize   = 11.sp
                ),
                color = color
            )
        }
    }
}

/**
 * Shows a summary row of tools used when thinking section is collapsed.
 */
@Composable
private fun ToolSummaryRow(
    toolCalls: List<AgentToolCallEntry>,
    accentColor: Color
) {
    val uniqueTools = toolCalls.map { it.toolName }.distinct()
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                accentColor.copy(alpha = 0.08f),
                RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Build,
            contentDescription = null,
            tint = accentColor.copy(alpha = 0.7f),
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = "Tools used: ",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
        )
        uniqueTools.forEachIndexed { index, toolName ->
            if (index > 0) {
                Text(
                    text = "•",
                    style = MaterialTheme.typography.labelSmall,
                    color = accentColor.copy(alpha = 0.5f)
                )
            }
            Text(
                text = toolName.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                color = accentColor
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Interleaved content
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Renders reasoning text + tool-call action cards interleaved.
 *
 * Strategy: show reasoning chunks split by "search" insertions.
 * Because we don't have byte-level positions from the server right now,
 * we show reasoning first (if any), then action cards in order below.
 * Future: SMARTY_TRACE_V2 ordered blocks can be rendered positionally.
 */
@Composable
private fun InterleavedContent(
    reasoningText: String,
    toolCalls: List<AgentToolCallEntry>,
    isStreaming: Boolean,
    accentColor: Color,
    thinkingColors: ThinkingColors
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Reasoning block (if any)
        if (reasoningText.isNotBlank()) {
            ReasoningBlock(
                text           = reasoningText,
                isStreaming    = isStreaming,
                accentColor    = accentColor,
                thinkingColors = thinkingColors
            )
            Spacer(Modifier.height(2.dp))
        }

        // Action cards
        toolCalls.forEach { entry ->
            ActionCard(
                entry      = entry,
                accentColor = accentColor
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Reasoning text block
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ReasoningBlock(
    text: String,
    isStreaming: Boolean,
    accentColor: Color,
    thinkingColors: ThinkingColors
) {
    // Typewriter effect
    var displayLength by remember { mutableIntStateOf(if (isStreaming) 0 else text.length) }
    LaunchedEffect(text, isStreaming) {
        if (isStreaming) {
            while (displayLength < text.length) {
                displayLength += minOf(4, text.length - displayLength)
                delay(18)
            }
        } else {
            displayLength = text.length
        }
    }
    val visible = remember(displayLength, text) {
        if (displayLength >= text.length) text else text.substring(0, displayLength)
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
                .background(
                    color = accentColor.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(2.dp)
                )
        )
        Text(
            text  = visible,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily  = FontFamily.Monospace,
                lineHeight  = 20.sp,
                fontSize    = 11.sp
            ),
            color = thinkingColors.text.copy(alpha = 0.85f)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Action card
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A single tool-call card — similar to the "search pill" in Gemini.
 *
 * Shows:
 *  • Icon + display name
 *  • Status indicator (check / spinner / cross)
 *  • Chevron to expand details
 *  • Expandable section: for web search shows query + truncated result,
 *    for other tools shows input + output summary.
 */
@Composable
private fun ActionCard(
    entry: AgentToolCallEntry,
    accentColor: Color
) {
    var expanded by remember { mutableStateOf(false) }
    val isSearch = entry.toolName.lowercase().let {
        it.contains("search") || it.contains("web") || it.contains("tavily")
    }

    val cardBg = MaterialTheme.colorScheme.surfaceContainerLow
    val borderColor = when (entry.status) {
        "failed"  -> MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
        "started" -> accentColor.copy(alpha = 0.25f)
        else      -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    }

    Surface(
        shape  = RoundedCornerShape(10.dp),
        color  = cardBg,
        border = BorderStroke(0.5.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            // ── Card header ─────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Tool icon
                ToolIcon(toolName = entry.toolName, status = entry.status)

                // Display name
                Text(
                    text     = entry.displayName,
                    style    = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color    = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                // Status + expand icon
                StatusBadge(status = entry.status)
                Icon(
                    imageVector        = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint               = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier           = Modifier.size(16.dp)
                )
            }

            // ── Expandable detail ────────────────────────────────────────────
            AnimatedVisibility(
                visible = expanded,
                enter   = expandVertically(tween(220)) + fadeIn(),
                exit    = shrinkVertically(tween(180)) + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                    if (isSearch && entry.searchQueries.isNotEmpty()) {
                        // ── Web search: show each query + result ──────────────
                        entry.searchQueries.forEachIndexed { idx, sq ->
                            SearchQueryCard(
                                index  = idx + 1,
                                query  = sq.query,
                                result = sq.result
                            )
                        }
                    } else {
                        // ── Generic tool: input then output ──────────────────
                        val inSum = entry.inputSummary
                        val outSum = entry.outputSummary
                        if (!inSum.isNullOrBlank()) {
                            DetailRow(label = "Input", value = inSum)
                        }
                        if (!outSum.isNullOrBlank()) {
                            DetailRow(label = "Result", value = outSum)
                        }
                        if (inSum.isNullOrBlank() && outSum.isNullOrBlank()) {
                            Text(
                                text  = "No details available",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Web search query card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SearchQueryCard(index: Int, query: String, result: String?) {
    val accentColor = LocalAccentColor.current
    var showResult by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Query row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showResult = !showResult }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text  = "$index",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = accentColor
                )
            }
            Text(
                text  = query,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (result != null) {
                Icon(
                    imageVector = if (showResult) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "Toggle result",
                    modifier = Modifier
                        .size(16.dp)
                        .alpha(0.6f),
                    tint = accentColor
                )
            }
        }

        // Result (expandable)
        AnimatedVisibility(visible = showResult && result != null) {
            Surface(
                color  = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape  = RoundedCornerShape(6.dp)
            ) {
                Text(
                    text     = result ?: "",
                    style    = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize   = 10.sp,
                        lineHeight = 16.sp
                    ),
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp)
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
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text  = label.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize      = 9.sp,
                letterSpacing = 0.8.sp,
                fontWeight    = FontWeight.Bold
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
        Text(
            text  = value,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize   = 11.sp,
                lineHeight = 16.sp
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ToolIcon(toolName: String, status: String) {
    val icon: ImageVector = when {
        toolName.contains("search", ignoreCase = true) ||
        toolName.contains("web",    ignoreCase = true) ->
            Icons.Default.Search
        toolName.contains("memory", ignoreCase = true) ||
        toolName.contains("note",   ignoreCase = true) ->
            Icons.Default.Book
        toolName.contains("calendar", ignoreCase = true) ||
        toolName.contains("schedule", ignoreCase = true) ->
            Icons.Default.CalendarMonth
        toolName.contains("remind", ignoreCase = true) ->
            Icons.Default.Alarm
        toolName.contains("navigate", ignoreCase = true) ->
            Icons.Default.Navigation
        toolName.contains("device", ignoreCase = true) ||
        toolName.contains("system", ignoreCase = true) ->
            Icons.Default.PhoneAndroid
        else -> Icons.Default.AutoAwesome
    }
    val tint = when (status) {
        "failed"  -> MaterialTheme.colorScheme.error
        "started" -> LocalAccentColor.current
        else      -> MaterialTheme.colorScheme.primary
    }
    Icon(
        imageVector        = icon,
        contentDescription = null,
        tint               = tint,
        modifier           = Modifier.size(16.dp)
    )
}

@Composable
private fun StatusBadge(status: String) {
    when (status) {
        "completed" -> Icon(
            Icons.Outlined.CheckCircle,
            contentDescription = "Completed",
            tint     = Color(0xFF4CAF50),
            modifier = Modifier.size(14.dp)
        )
        "failed" -> Icon(
            Icons.Default.ErrorOutline,
            contentDescription = "Failed",
            tint     = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(14.dp)
        )
        else -> {
            // "started" — spinning indicator
            val infiniteTransition = rememberInfiniteTransition(label = "spin")
            val rotation by infiniteTransition.animateFloat(
                0f, 360f,
                infiniteRepeatable(tween(1000, easing = LinearEasing)),
                label = "rotation"
            )
            // Simple pulsing dot for "in progress"
            val alpha by infiniteTransition.animateFloat(
                0.3f, 1f,
                infiniteRepeatable(tween(700), RepeatMode.Reverse),
                label = "alpha"
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(LocalAccentColor.current.copy(alpha = alpha))
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Animated emoji (reused from original)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ThinkingEmojiAnimation() {
    val infiniteTransition = rememberInfiniteTransition(label = "thinking_emojis")
    val progress by infiniteTransition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
        label = "emoji_progress"
    )
    val emojis  = listOf("🧠", "🐦‍🔥", "⚡")
    val current = emojis[((progress * 2.99f).toInt()).coerceIn(0, 2)]
    Text(text = current, style = MaterialTheme.typography.titleMedium)
}
