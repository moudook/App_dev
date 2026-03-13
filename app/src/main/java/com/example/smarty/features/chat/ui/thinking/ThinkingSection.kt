package com.example.smarty.features.chat.ui.thinking

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assistant
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import com.example.smarty.R
import com.example.smarty.data.model.ReasoningTrace
import com.example.smarty.features.chat.domain.ReasoningViewModel
import androidx.compose.runtime.collectAsState

/**
 * Displays the thinking/reasoning process from AI models.
 *
 * Shows a collapsible card with the model's step-by-step reasoning.
 * Used for responses from thinking-enabled models.
 *
 * Design: "Soft Tech" - Clean surface with subtle border, distinct from chat bubbles.
 *
 * @param thinkingContent The reasoning process text (from <think> tags)
 * @param modifier Optional modifier for the card
 * @param initiallyExpanded Whether the section starts expanded (default: false)
 */
@Composable
fun ThinkingSection(
    thinkingContent: String,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // Header row with icon, title, and expand/collapse button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Assistant,
                        contentDescription = stringResource(R.string.thinking),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = stringResource(R.string.reasoning),
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.5.sp
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Icon(
                    imageVector = if (expanded) {
                        Icons.Default.ExpandLess
                    } else {
                        Icons.Default.ExpandMore
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }

            // Collapsible thinking content
            AnimatedVisibility(visible = expanded) {
                Text(
                    text = thinkingContent,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontStyle = FontStyle.Italic
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

/**
 * Enhanced thinking section with progressive disclosure levels.
 * Fetches reasoning traces from API and displays with 3 disclosure levels.
 *
 * @param viewModel ReasoningViewModel instance
 * @param sessionId Chat session ID to fetch reasoning for
 * @param modifier Optional modifier
 */
@Composable
fun ThinkingSectionWithProgressiveDisclosure(
    viewModel: ReasoningViewModel,
    sessionId: String,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Assistant,
                        contentDescription = stringResource(R.string.thinking),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = stringResource(R.string.reasoning),
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.5.sp
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Disclosure level toggle
                    IconButton(onClick = { viewModel.toggleDisclosureLevel() }) {
                        Icon(
                            imageVector = when (uiState.disclosureLevel) {
                                ReasoningViewModel.DisclosureLevel.ONE_LINER -> Icons.Default.ExpandLess
                                ReasoningViewModel.DisclosureLevel.BRIEF -> Icons.Default.ExpandMore
                                ReasoningViewModel.DisclosureLevel.DETAILED -> Icons.Default.Refresh
                                else -> Icons.Default.Refresh
                            },
                            contentDescription = "Toggle disclosure level",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    // Reload button
                    IconButton(onClick = { viewModel.loadDisclosure(sessionId) }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Reload",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Disclosure level indicator
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DisclosureLevelChip(
                    label = "One-Liner",
                    isSelected = uiState.disclosureLevel == ReasoningViewModel.DisclosureLevel.ONE_LINER,
                    onClick = { viewModel.setDisclosureLevel(ReasoningViewModel.DisclosureLevel.ONE_LINER) }
                )
                DisclosureLevelChip(
                    label = "Brief",
                    isSelected = uiState.disclosureLevel == ReasoningViewModel.DisclosureLevel.BRIEF,
                    onClick = { viewModel.setDisclosureLevel(ReasoningViewModel.DisclosureLevel.BRIEF) }
                )
                DisclosureLevelChip(
                    label = "Detailed",
                    isSelected = uiState.disclosureLevel == ReasoningViewModel.DisclosureLevel.DETAILED,
                    onClick = { viewModel.setDisclosureLevel(ReasoningViewModel.DisclosureLevel.DETAILED) }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Content area
            when {
                uiState.isLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                uiState.error != null -> {
                    Text(
                        text = uiState.error!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(8.dp)
                    )
                    Button(
                        onClick = { viewModel.loadDisclosure(sessionId) },
                        modifier = Modifier.padding(top = 8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text("Retry")
                    }
                }
                uiState.disclosure != null -> {
                    val content = viewModel.getDisplayContent()
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(content) { item ->
                            ReasoningStepItem(content = item)
                        }
                    }
                }
                else -> {
                    Text(
                        text = "No reasoning traces available",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        }
    }
}

/**
 * Disclosure level chip button
 */
@Composable
private fun DisclosureLevelChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = BorderStroke(
            1.dp,
            if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
        )
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (isSelected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

/**
 * Individual reasoning step item
 */
@Composable
private fun ReasoningStepItem(
    content: String
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
    ) {
        Text(
            text = content,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(12.dp)
        )
    }
}

/**
 * Display reasoning traces as a timeline with step type indicators.
 *
 * @param traces List of reasoning traces to display
 * @param modifier Optional modifier
 */
@Composable
fun ReasoningTimelineDisplay(
    traces: List<ReasoningTrace>,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Assistant,
                        contentDescription = stringResource(R.string.thinking),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Reasoning Timeline",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.5.sp
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = "${traces.size} steps",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(traces) { trace ->
                    ReasoningTraceItem(trace = trace)
                }
            }
        }
    }
}

/**
 * Get step type display info for a trace
 */
private fun getStepTypeDisplayForTrace(stepType: String): StepTypeDisplayInfo {
    return when (stepType.uppercase()) {
        "ANALYSIS" -> StepTypeDisplayInfo.Analysis
        "PLANNING" -> StepTypeDisplayInfo.Planning
        "HYPOTHESIS" -> StepTypeDisplayInfo.Hypothesis
        "RESEARCH" -> StepTypeDisplayInfo.Research
        "VERIFICATION" -> StepTypeDisplayInfo.Verification
        "SYNTHESIS" -> StepTypeDisplayInfo.Synthesis
        "REFLECTION" -> StepTypeDisplayInfo.Reflection
        "CORRECTION" -> StepTypeDisplayInfo.Correction
        else -> StepTypeDisplayInfo.Unknown(stepType)
    }
}

/**
 * Individual reasoning trace item with step type indicator
 */
@Composable
private fun ReasoningTraceItem(
    trace: ReasoningTrace
) {
    val stepTypeDisplay = getStepTypeDisplayForTrace(trace.stepType)
    
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.1f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Step type indicator with color
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center
                    ) {
                        // Generic number indicator instead of emoji
                        Text(
                            text = "${trace.stepIndex + 1}",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                
                // Title and metadata
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = trace.title,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = stepTypeDisplay.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${trace.durationMs}ms",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (trace.isFinal) {
                            Text(
                                text = "✓ Final",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                
                // Confidence indicator
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(6.dp)
                        .padding(top = 4.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(3.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                        )
                    }
                    Surface(
                        color = when {
                            trace.confidenceScore >= 0.7 -> MaterialTheme.colorScheme.primary
                            trace.confidenceScore >= 0.4 -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.error
                        },
                        shape = RoundedCornerShape(3.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .width((40 * trace.confidenceScore).dp)
                                .height(6.dp)
                        )
                    }
                }
            }
            
            // Content
            Text(
                text = trace.content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

/**
 * Step type display information with colors and labels
 */
sealed class StepTypeDisplayInfo {
    object Analysis : StepTypeDisplayInfo()
    object Planning : StepTypeDisplayInfo()
    object Hypothesis : StepTypeDisplayInfo()
    object Research : StepTypeDisplayInfo()
    object Verification : StepTypeDisplayInfo()
    object Synthesis : StepTypeDisplayInfo()
    object Reflection : StepTypeDisplayInfo()
    object Correction : StepTypeDisplayInfo()
    data class Unknown(val type: String) : StepTypeDisplayInfo()

    val label: String
        get() = when (this) {
            is Analysis -> "Analysis"
            is Planning -> "Planning"
            is Hypothesis -> "Hypothesis"
            is Research -> "Research"
            is Verification -> "Verification"
            is Synthesis -> "Synthesis"
            is Reflection -> "Reflection"
            is Correction -> "Correction"
            is Unknown -> type
        }

    val color: Long
        get() = when (this) {
            is Analysis -> 0xFF2196F3  // Blue
            is Planning -> 0xFF9C27B0  // Purple
            is Hypothesis -> 0xFFFFC107 // Amber
            is Research -> 0xFF4CAF50  // Green
            is Verification -> 0xFF00BCD4 // Cyan
            is Synthesis -> 0xFFFF9800  // Orange
            is Reflection -> 0xFF607D8B // Blue Grey
            is Correction -> 0xFFF44336 // Red
            is Unknown -> 0xFF9E9E9E    // Grey
        }
}
