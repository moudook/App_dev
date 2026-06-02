package com.example.smarty.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Skeleton loading placeholder for chat messages.
 * Shows animated shimmer effect while content loads.
 *
 * USAGE:
 * ```kotlin
 * SkeletonChatMessage()
 * ```
 */
@Composable
fun SkeletonChatMessage(
    modifier: Modifier = Modifier,
    isUser: Boolean = false,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        // Message bubble
        Box(
            modifier =
                Modifier
                    .fillMaxWidth(if (isUser) 0.8f else 0.9f)
                    .wrapContentWidth(if (isUser) androidx.compose.ui.Alignment.End else androidx.compose.ui.Alignment.Start)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.LightGray)
                    .heightIn(min = 40.dp, max = 120.dp),
        )

        // Timestamp placeholder
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier =
                Modifier
                    .width(60.dp)
                    .height(12.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.LightGray.copy(alpha = 0.6f)),
        )
    }
}

/**
 * Skeleton loading placeholder for note cards.
 */
@Composable
fun SkeletonNoteCard(modifier: Modifier = Modifier) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(16.dp),
    ) {
        // Title
        Box(
            modifier =
                Modifier
                    .fillMaxWidth(0.7f)
                    .height(20.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.LightGray),
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Content lines
        repeat(3) { index ->
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth(0.9f - (index * 0.1f))
                        .height(12.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.LightGray.copy(alpha = 0.6f)),
            )
            if (index < 2) {
                Spacer(modifier = Modifier.height(4.dp))
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Bottom row (date + actions)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                modifier =
                    Modifier
                        .width(80.dp)
                        .height(12.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.LightGray.copy(alpha = 0.6f)),
            )

            Row {
                repeat(2) {
                    Box(
                        modifier =
                            Modifier
                                .size(24.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.LightGray.copy(alpha = 0.4f)),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
            }
        }
    }
}

/**
 * Skeleton loading placeholder for list items.
 */
@Composable
fun SkeletonListItem(
    modifier: Modifier = Modifier,
    lines: Int = 3,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(16.dp),
    ) {
        // Icon/avatar placeholder
        Box(
            modifier =
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.LightGray),
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Title
        Box(
            modifier =
                Modifier
                    .fillMaxWidth(0.6f)
                    .height(16.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.LightGray),
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Content lines
        repeat(lines) { index ->
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth(0.8f - (index * 0.1f))
                        .height(12.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.LightGray.copy(alpha = 0.6f)),
            )
            if (index < lines - 1) {
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

/**
 * Skeleton loading placeholder for calendar events.
 */
@Composable
fun SkeletonCalendarEvent(modifier: Modifier = Modifier) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(12.dp),
    ) {
        // Time column
        Column(
            modifier = Modifier.width(60.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth(0.6f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.LightGray),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth(0.4f)
                        .height(12.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.LightGray.copy(alpha = 0.6f)),
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Event details
        Column(
            modifier = Modifier.weight(1f),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth(0.8f)
                        .height(16.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.LightGray),
            )
            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth(0.5f)
                        .height(12.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.LightGray.copy(alpha = 0.6f)),
            )
        }
    }
}

/**
 * Generic skeleton box with shimmer effect.
 */
@Composable
fun SkeletonBox(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(4.dp),
    width: Dp? = null,
    height: Dp? = null,
    fillMaxWidth: Float? = null,
    fillMaxHeight: Float? = null,
) {
    var boxModifier = modifier
    boxModifier = boxModifier.clip(shape)
    boxModifier = boxModifier.background(Color.LightGray)

    if (width != null) {
        boxModifier = boxModifier.width(width)
    }
    if (height != null) {
        boxModifier = boxModifier.height(height)
    }
    if (fillMaxWidth != null) {
        boxModifier = boxModifier.fillMaxWidth(fillMaxWidth)
    }
    if (fillMaxHeight != null) {
        boxModifier = boxModifier.fillMaxHeight(fillMaxHeight)
    }

    Box(modifier = boxModifier)
}

/**
 * Skeleton text line placeholder.
 */
@Composable
fun SkeletonTextLine(
    modifier: Modifier = Modifier,
    widthPercent: Float = 1f,
) {
    SkeletonBox(
        modifier = modifier,
        height = 12.dp,
        fillMaxWidth = widthPercent,
        shape = RoundedCornerShape(4.dp),
    )
}

/**
 * Skeleton paragraph with multiple lines.
 */
@Composable
fun SkeletonParagraph(
    modifier: Modifier = Modifier,
    lines: Int = 3,
    lineSpacing: Dp = 4.dp,
) {
    Column(modifier = modifier) {
        repeat(lines) { index ->
            SkeletonTextLine(
                widthPercent = 1f - (index * 0.1f),
            )
            if (index < lines - 1) {
                Spacer(modifier = Modifier.height(lineSpacing))
            }
        }
    }
}
