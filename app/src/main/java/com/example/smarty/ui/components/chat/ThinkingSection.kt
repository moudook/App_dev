package com.example.smarty.ui.components.chat

import androidx.compose.animation.core.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.drawBehind
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.theme.*
import kotlinx.coroutines.delay

/**
 * ThinkingSection - Displays AI thinking process with expandable/collapsible view.
 * 
 * Single Responsibility: Only handles thinking visualization.
 * DRY: Extracted from ChatMessageItem to avoid duplication.
 * 
 * @param thinkingText The thinking content to display
 * @param isExpanded Whether the thinking section is expanded
 * @param isStreaming Whether the AI is still streaming the response
 * @param onExpandToggle Callback when expand/collapse is triggered
 */
@Composable
fun ThinkingSection(
    thinkingText: String,
    isExpanded: Boolean,
    isStreaming: Boolean,
    onExpandToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accentColor = LocalAccentColor.current
    val thinkingColors = MaterialTheme.thinkingColors
    
    // Optimize: Resource-efficient typewriter for thinking text
    var displayThinkingLength by remember { mutableIntStateOf(if (isStreaming) 0 else thinkingText.length) }
    
    LaunchedEffect(thinkingText, isExpanded, isStreaming) {
        if (isStreaming && isExpanded) {
            while (displayThinkingLength < thinkingText.length) {
                displayThinkingLength += minOf(3, thinkingText.length - displayThinkingLength)
                delay(20)
            }
        } else if (!isStreaming) {
            displayThinkingLength = thinkingText.length
        }
    }
    
    val visibleThinkingText by remember(displayThinkingLength, thinkingText) {
        derivedStateOf {
            if (displayThinkingLength >= thinkingText.length) thinkingText
            else thinkingText.substring(0, displayThinkingLength)
        }
    }
    
    Surface(
        shape = MaterialTheme.smartyShapes.thinkingContainer,
        color = thinkingColors.background,
        border = BorderStroke(
            width = 0.5.dp,
            color = thinkingColors.border
        ),
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = ComponentSpacing.thinkingMarginBottom)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onExpandToggle() }
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = ComponentSpacing.thinkingPaddingHorizontal,
                vertical = ComponentSpacing.thinkingPaddingVertical
            )
        ) {
            // Header with emoji and title
            ThinkingHeader(
                isStreaming = isStreaming,
                isExpanded = isExpanded,
                accentColor = accentColor,
                thinkingColors = thinkingColors
            )
            
            // Expandable thinking content
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(animationSpec = tween(300)) + fadeIn(),
                exit = shrinkVertically(animationSpec = tween(250)) + fadeOut()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(ComponentSpacing.thinkingTextGap))
                    
                    // Thinking text with accent line
                    ThinkingContent(
                        visibleThinkingText = visibleThinkingText,
                        accentColor = accentColor,
                        thinkingColors = thinkingColors
                    )
                }
            }
        }
    }
}

@Composable
private fun ThinkingHeader(
    isStreaming: Boolean,
    isExpanded: Boolean,
    accentColor: Color,
    thinkingColors: ThinkingColors
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ComponentSpacing.thinkingHeaderGap)
    ) {
        if (isStreaming) {
            // Animated emoji during streaming
            ThinkingEmojiAnimation()
            Text(
                text = "Thinking...",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                ),
                color = accentColor.copy(alpha = Alpha.prominent)
            )
        } else {
            Text(
                text = "🧠",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Thought process",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp
                ),
                color = thinkingColors.text.copy(alpha = Alpha.mostlyOpaque)
            )
        }
        
        Spacer(modifier = Modifier.weight(1f))
        
        Icon(
            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null,
            tint = thinkingColors.text.copy(alpha = Alpha.half),
            modifier = Modifier.size(ComponentSpacing.thinkingIndicatorSize)
        )
    }
}

@Composable
private fun ThinkingEmojiAnimation() {
    val infiniteTransition = rememberInfiniteTransition(label = "thinking_emojis")
    val emojiProgress by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "emoji_progress"
    )
    
    val thinkingEmojis = listOf("🧠", "👻", "🌻")
    val currentEmojiIndex = ((emojiProgress * 2.99f).toInt()).coerceIn(0, 2)
    val currentEmoji = thinkingEmojis[currentEmojiIndex]
    
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = currentEmoji,
            style = MaterialTheme.typography.titleMedium
        )
    }
}

@Composable
private fun ThinkingContent(
    visibleThinkingText: String,
    accentColor: Color,
    thinkingColors: ThinkingColors
) {
    Column(
        modifier = Modifier.padding(start = ComponentSpacing.thinkingLineGap + ComponentSpacing.thinkingLineMargin)
    ) {
        Box(
            modifier = Modifier
                .padding(start = ComponentSpacing.thinkingLineMargin)
                .drawBehind {
                    drawLine(
                        color = accentColor.copy(alpha = Alpha.moderate),
                        start = Offset(0f, 0f),
                        end = Offset(0f, this.size.height),
                        strokeWidth = ComponentSpacing.thinkingLineWidth.toPx()
                    )
                }
                .padding(start = ComponentSpacing.thinkingLineGap)
        ) {
            Text(
                text = visibleThinkingText,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 20.sp,
                    fontSize = 11.sp
                ),
                color = thinkingColors.text.copy(alpha = Alpha.nearlyOpaque)
            )
        }
    }
}
