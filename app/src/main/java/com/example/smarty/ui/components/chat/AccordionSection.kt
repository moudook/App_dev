package com.example.smarty.ui.components.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.components.markdown.MarkdownRenderer

/**
 * Accordion section component with individual expandable items.
 * Each accordion section can be toggled independently.
 * Content renders as Markdown.
 */
@Composable
fun AccordionSection(
    title: String,
    content: String,
    expanded: Boolean,
    onExpandChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = LocalAccentColor.current,
    depth: Int = 0,
) {
    val displayTitle = if (title.isBlank()) "Details" else title

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border =
                androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                ),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        onExpandChange(!expanded)
                    },
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = displayTitle,
                    style =
                        MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                        ),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(250)) + fadeIn(tween(200)),
            exit = shrinkVertically(tween(200)) + fadeOut(tween(150)),
        ) {
            if (content.isNotBlank()) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border =
                            androidx.compose.foundation.BorderStroke(
                                1.dp,
                                accentColor.copy(alpha = 0.15f),
                            ),
                        tonalElevation = 0.dp,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Box(modifier = Modifier.padding(14.dp)) {
                            MarkdownRenderer(
                                content = content,
                                isUser = false,
                                normalColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                                boldColor = MaterialTheme.colorScheme.onSurface,
                                linkColor = accentColor,
                                codeColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                                codeBackgroundColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.4f),
                                codeBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                                depth = depth + 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Multiple accordion sections container.
 * Supports multiple independent sections expanded simultaneously.
 */
@Composable
fun AccordionGroup(
    sections: List<AccordionParser.AccordionSection>,
    modifier: Modifier = Modifier,
    initiallyExpandedIndex: Int = -1,
    depth: Int = 0,
) {
    if (sections.isEmpty()) return

    val safeInitialIndex = if (initiallyExpandedIndex in sections.indices) initiallyExpandedIndex else -1

    var expandedIndices by remember {
        mutableStateOf(
            if (safeInitialIndex >= 0) setOf(safeInitialIndex) else emptySet<Int>(),
        )
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        sections.forEachIndexed { index, section ->
            AccordionSection(
                title = section.title,
                content = section.content,
                expanded = expandedIndices.contains(index),
                onExpandChange = { isExpanded ->
                    expandedIndices =
                        if (isExpanded) {
                            expandedIndices + index
                        } else {
                            expandedIndices - index
                        }
                },
                modifier = Modifier.fillMaxWidth(),
                depth = depth,
            )
        }
    }
}

/**
 * Response with optional intro text followed by accordion sections.
 */
@Composable
fun AccordionResponse(
    parsedContent: AccordionParser.ParsedContent,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (parsedContent.introText.isNotBlank()) {
            MarkdownRenderer(
                content = parsedContent.introText,
                isUser = false,
                normalColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                boldColor = MaterialTheme.colorScheme.onSurface,
                linkColor = LocalAccentColor.current,
                codeColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                codeBackgroundColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.4f),
                codeBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        AccordionGroup(
            sections = parsedContent.accordions,
            initiallyExpandedIndex = -1,
        )
    }
}
