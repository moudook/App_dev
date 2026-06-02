package com.example.smarty.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.utils.AnimationLifecycleState
import com.example.smarty.ui.utils.rememberAnimationLifecycleState

// SearchEmptyState moved to ChatEmptyState.kt to share EmptyStateContainer infrastructure

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
    overflow: TextOverflow = TextOverflow.Clip,
) {
    if (query.isNullOrBlank()) {
        // No query, render plain text
        Text(
            text = text,
            modifier = modifier,
            style = style,
            color = color,
            maxLines = maxLines,
            overflow = overflow,
        )
    } else {
        // Find and highlight matches with shimmer
        val annotatedString = buildHighlightedText(text, query, LocalAccentColor.current)

        // Shimmer animation for highlighted portions - LIFECYCLE AWARE
        val lifecycleState by rememberAnimationLifecycleState()
        val shouldAnimate = lifecycleState == AnimationLifecycleState.RUNNING

        val shimmerProgress =
            if (shouldAnimate) {
                val infiniteTransition = rememberInfiniteTransition(label = "textShimmer")
                val animatedProgress by infiniteTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec =
                        infiniteRepeatable(
                            animation = tween(1500, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart,
                        ),
                    label = "shimmerProgress",
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
            overflow = overflow,
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
    highlightColor: Color,
): AnnotatedString =
    buildAnnotatedString {
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
                    background = highlightColor.copy(alpha = 0.15f),
                ),
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
