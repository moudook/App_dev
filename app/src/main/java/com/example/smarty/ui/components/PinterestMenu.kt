package com.example.smarty.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import android.graphics.Paint
import android.graphics.BlurMaskFilter
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarty.ui.animation.SmartyMotion
import com.example.smarty.ui.theme.*
import com.example.smarty.util.rememberHapticHelper
import kotlin.math.*

/**
 * Pinterest-Style Radial Selection Menu
 *
 * Provides a gestural interface for quick actions on long-pressed items.
 * Optimized for one-handed operation and high cognitive comfort.
 */

enum class PinterestAction(
    val label: String,
    val icon: ImageVector,
    val color: Color
) {
    PIN("Pin", Icons.Default.PushPin, ElectricBlue),
    SHARE("Share", Icons.Default.Share, SystemGreen),
    ARCHIVE("Archive", Icons.Default.Archive, SystemOrange),
    DELETE("Delete", Icons.Default.Delete, SystemRed)
}

@Composable
fun PinterestMenu(
    anchorPosition: Offset, // Absolute position of the touch
    onDismiss: () -> Unit,
    onAction: (PinterestAction) -> Unit
) {
    val haptic = rememberHapticHelper()
    val density = LocalDensity.current

    // Animation state for the menu appearance
    val animProgress = remember { Animatable(0f) }

    // Track current drag position relative to start
    var currentDragOffset by remember { mutableStateOf(Offset.Zero) }
    var selectedAction by remember { mutableStateOf<PinterestAction?>(null) }

    // Configuration
    val radius = with(density) { 100.dp.toPx() }
    val selectionThreshold = with(density) { 30.dp.toPx() }
    val itemSize = 56.dp

    LaunchedEffect(Unit) {
        animProgress.animateTo(
            targetValue = 1f,
            animationSpec = SmartyMotion.bouncy
        )
    }

    // Action definitions with their target angles
    val actions = remember {
        listOf(
            PinterestAction.PIN to -135f,
            PinterestAction.SHARE to -45f,
            PinterestAction.ARCHIVE to 45f,
            PinterestAction.DELETE to 135f
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull()

                        if (change == null || !change.pressed) {
                            if (selectedAction != null) {
                                onAction(selectedAction!!)
                            } else {
                                onDismiss()
                            }
                            break
                        }

                        // Calculate current drag offset relative to anchor
                        currentDragOffset = change.position - anchorPosition

                        // Calculate selected action
                        val distance = currentDragOffset.getDistance()
                        if (distance > selectionThreshold) {
                            val angle = atan2(currentDragOffset.y, currentDragOffset.x) * (180 / PI).toFloat()
                            val newSelected = actions.minByOrNull { (_, actionAngle) ->
                                var diff = abs(angle - actionAngle)
                                if (diff > 180) diff = 360 - diff
                                diff
                            }?.first

                            if (newSelected != selectedAction) {
                                selectedAction = newSelected
                                if (newSelected != null) haptic.select()
                            }
                        } else {
                            selectedAction = null
                        }

                        change.consume()
                    }
                }
            }
    ) {
        // Scrim
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.3f * animProgress.value))
        )

        // Connector Lines & Anchor
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Anchor glow
            drawCircle(
                color = CardWhite.copy(alpha = 0.4f * animProgress.value),
                radius = 20.dp.toPx(),
                center = anchorPosition
            )

            actions.forEach { (action, angle) ->
                val isSelected = selectedAction == action
                val radian = angle * (PI / 180).toFloat()

                val lineLength = radius * animProgress.value * (if (isSelected) 0.9f else 0.7f)
                val startX = anchorPosition.x
                val startY = anchorPosition.y
                val endX = anchorPosition.x + cos(radian) * lineLength
                val endY = anchorPosition.y + sin(radian) * lineLength

                val color = if (isSelected) action.color else CardWhite.copy(alpha = 0.4f * animProgress.value)
                val strokeWidth = if (isSelected) 4.dp.toPx() else 2.dp.toPx()

                drawLine(
                    color = color,
                    start = Offset(startX, startY),
                    end = Offset(endX, endY),
                    strokeWidth = strokeWidth,
                    pathEffect = if (!isSelected) PathEffect.dashPathEffect(floatArrayOf(15f, 15f), 0f) else null
                )
            }

            // Anchor center
            drawCircle(
                color = CardWhite,
                radius = 6.dp.toPx() * animProgress.value,
                center = anchorPosition
            )
        }

        // Render Action Items
        actions.forEach { (action, angle) ->
            val isSelected = selectedAction == action
            val radian = angle * (PI / 180).toFloat()

            // Calculate item position
            val itemX = cos(radian) * radius * animProgress.value
            val itemY = sin(radian) * radius * animProgress.value

            val scale by animateFloatAsState(
                targetValue = if (isSelected) 1.4f else 1.0f,
                animationSpec = SmartyMotion.snappy,
                label = "itemScale"
            )

            val elevation by animateDpAsState(
                targetValue = if (isSelected) 16.dp else 4.dp,
                animationSpec = spring(stiffness = Spring.StiffnessLow, visibilityThreshold = androidx.compose.ui.unit.Dp.VisibilityThreshold),
                label = "itemElevation"
            )

            ActionItem(
                action = action,
                isSelected = isSelected,
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (anchorPosition.x + itemX).toInt(),
                            (anchorPosition.y + itemY).toInt()
                        )
                    }
                    .offset(-(itemSize / 2), -(itemSize / 2))
                    .scale(scale * animProgress.value)
                    .softCardShadow(elevation = elevation, shape = CircleShape),
                itemSize = itemSize
            )
        }
    }
}

@Composable
private fun ActionItem(
    action: PinterestAction,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    itemSize: androidx.compose.ui.unit.Dp
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(itemSize)
                .background(
                    if (isSelected) action.color else CardWhite,
                    CircleShape
                )
        ) {
            Icon(
                imageVector = action.icon,
                contentDescription = action.label,
                tint = if (isSelected) CardWhite else action.color,
                modifier = Modifier.size(24.dp)
            )
        }

        if (isSelected) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = action.label,
                color = CardWhite,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
    }
}

/**
 * Custom gesture detector for the Pinterest-style interaction.
 * Tracks the drag and detects the final release.
 */
private suspend fun PointerInputScope.detectPinterestGestures(
    onDrag: (Offset) -> Unit,
    onRelease: () -> Unit
) {
    awaitEachGesture {
        val down = awaitFirstDown()

        var currentPosition = down.position

        while (true) {
            val event = awaitPointerEvent()
            val dragEvent = event.changes.firstOrNull()

            if (dragEvent == null || !dragEvent.pressed) {
                onRelease()
                break
            }

            currentPosition = dragEvent.position
            val offset = currentPosition - down.position
            onDrag(offset)

            dragEvent.consume()
        }
    }
}
