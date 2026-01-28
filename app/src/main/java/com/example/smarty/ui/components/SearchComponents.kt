package com.example.smarty.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExploreOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.utils.AnimationLifecycleState
import com.example.smarty.ui.utils.rememberAnimationLifecycleState

/**
 * Empty state displayed when search yields no results.
 * Shows a gentle animation with suggestions.
 */
@Composable
fun SearchEmptyState(
    searchQuery: String,
    modifier: Modifier = Modifier
) {
    val accentColor = LocalAccentColor.current

    // Breathing animation for icon - LIFECYCLE AWARE
    val lifecycleState by rememberAnimationLifecycleState()
    val shouldAnimate = lifecycleState == AnimationLifecycleState.RUNNING

    val iconScale = if (shouldAnimate) {
        val infiniteTransition = rememberInfiniteTransition(label = "searchEmpty")
        val animatedScale by infiniteTransition.animateFloat(
            initialValue = 0.9f,
            targetValue = 1.1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1500, easing = EaseInOutQuad),
                repeatMode = RepeatMode.Reverse
            ),
            label = "iconScale"
        )
        animatedScale
    } else {
        1.0f // Static mid-point value
    }

    val iconAlpha = if (shouldAnimate) {
        val infiniteTransition = rememberInfiniteTransition(label = "searchEmpty")
        val animatedAlpha by infiniteTransition.animateFloat(
            initialValue = 0.5f,
            targetValue = 0.8f,
            animationSpec = infiniteRepeatable(
                animation = tween(1500, easing = EaseInOutQuad),
                repeatMode = RepeatMode.Reverse
            ),
            label = "iconAlpha"
        )
        animatedAlpha
    } else {
        0.65f // Static mid-point value
    }
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Animated search icon
        Icon(
            imageVector = Icons.Default.ExploreOff,
            contentDescription = null,
            tint = accentColor.copy(alpha = iconAlpha),
            modifier = Modifier
                .size((48 * iconScale).dp)
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Main message
        Text(
            text = "No notes matching",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(4.dp))
        
        // Query text with quotes
        Text(
            text = "\"${searchQuery.take(30)}${if (searchQuery.length > 30) "..." else ""}\"",
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold
            ),
            color = accentColor
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Suggestions
        Text(
            text = "Try different keywords or check spelling",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

/**
 * Text component that highlights matching portions with a shimmer effect.
 * Used for search result highlighting in note cards.
 */
@Composable
fun HighlightedText(
    text: String,
    query: String?,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = MaterialTheme.colorScheme.onSurface,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip
) {
    if (query.isNullOrBlank()) {
        // No query, render plain text
        Text(
            text = text,
            modifier = modifier,
            style = style,
            color = color,
            maxLines = maxLines,
            overflow = overflow
        )
    } else {
        // Find and highlight matches with shimmer
        val annotatedString = buildHighlightedText(text, query, LocalAccentColor.current)

        // Shimmer animation for highlighted portions - LIFECYCLE AWARE
        val lifecycleState by rememberAnimationLifecycleState()
        val shouldAnimate = lifecycleState == AnimationLifecycleState.RUNNING

        val shimmerProgress = if (shouldAnimate) {
            val infiniteTransition = rememberInfiniteTransition(label = "textShimmer")
            val animatedProgress by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1500, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "shimmerProgress"
            )
            animatedProgress
        } else {
            0.5f // Static mid-point value
        }

        // Apply shimmer alpha based on progress to highlighted text
        val shimmerAlpha = (0.5f + 0.5f * kotlin.math.sin(shimmerProgress * 2 * kotlin.math.PI.toFloat()))
        
        Text(
            text = annotatedString,
            modifier = modifier.alpha(if (annotatedString.spanStyles.isNotEmpty()) shimmerAlpha.coerceIn(0.7f, 1f) else 1f),
            style = style,
            maxLines = maxLines,
            overflow = overflow
        )
    }
}

/**
 * Builds an AnnotatedString with highlighted matching portions.
 */
@Composable
private fun buildHighlightedText(
    text: String,
    query: String,
    highlightColor: Color
): AnnotatedString {
    return buildAnnotatedString {
        val lowerText = text.lowercase()
        val lowerQuery = query.lowercase()
        
        var lastEnd = 0
        var start = lowerText.indexOf(lowerQuery)
        
        while (start >= 0) {
            // Add non-matching prefix
            if (start > lastEnd) {
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface)) {
                    append(text.substring(lastEnd, start))
                }
            }
            
            // Add highlighted match
            val end = start + query.length
            withStyle(
                SpanStyle(
                    color = highlightColor,
                    fontWeight = FontWeight.Bold,
                    background = highlightColor.copy(alpha = 0.15f)
                )
            ) {
                append(text.substring(start, end))
            }
            
            lastEnd = end
            start = lowerText.indexOf(lowerQuery, lastEnd)
        }
        
        // Add remaining text
        if (lastEnd < text.length) {
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface)) {
                append(text.substring(lastEnd))
            }
        }
    }
}
