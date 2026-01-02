package com.example.smarty.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarty.data.model.Note
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Inline note preview component for displaying referenced/recommended notes in chat messages.
 * Similar to InlineImagePreview, it allows swiping through notes.
 */
@Composable
fun InlineNotePreview(
    notes: List<Note>,
    onNoteClick: (Note) -> Unit,
    modifier: Modifier = Modifier
) {
    if (notes.isEmpty()) return

    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()

    // State for current note index
    var currentIndex by rememberSaveable { mutableIntStateOf(0) }

    // Swipe gesture state
    val swipeOffset = remember { Animatable(0f) }
    var accumulatedDrag by remember { mutableFloatStateOf(0f) }
    var swipeActivated by remember { mutableStateOf(false) }

    // Calculate swipe threshold in pixels
    val swipeThreshold = with(density) { 60.dp.toPx() }
    val swipeActivationThreshold = with(density) { 15.dp.toPx() }

    // Spring animation spec
    val snapBackSpec = spring<Float>(
        dampingRatio = 0.7f,
        stiffness = 400f
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(notes.size) {
                if (notes.size <= 1) return@pointerInput

                detectHorizontalDragGestures(
                    onDragStart = {
                        accumulatedDrag = 0f
                        swipeActivated = false
                    },
                    onDragEnd = {
                        coroutineScope.launch {
                            if (swipeActivated && abs(swipeOffset.value) > swipeThreshold) {
                                if (swipeOffset.value < 0 && currentIndex < notes.lastIndex) {
                                    currentIndex++
                                } else if (swipeOffset.value > 0 && currentIndex > 0) {
                                    currentIndex--
                                }
                            }
                            swipeOffset.animateTo(0f, snapBackSpec)
                            swipeActivated = false
                            accumulatedDrag = 0f
                        }
                    },
                    onDragCancel = {
                        coroutineScope.launch {
                            swipeOffset.animateTo(0f, snapBackSpec)
                        }
                        swipeActivated = false
                        accumulatedDrag = 0f
                    },
                    onHorizontalDrag = { _, dragAmount ->
                        accumulatedDrag += dragAmount
                        if (!swipeActivated && abs(accumulatedDrag) > swipeActivationThreshold) {
                            swipeActivated = true
                        }
                        if (swipeActivated) {
                            coroutineScope.launch {
                                val limitedDrag = when {
                                    currentIndex == 0 && accumulatedDrag > 0 -> accumulatedDrag * 0.3f
                                    currentIndex == notes.lastIndex && accumulatedDrag < 0 -> accumulatedDrag * 0.3f
                                    else -> accumulatedDrag
                                }
                                swipeOffset.snapTo(limitedDrag)
                            }
                        }
                    }
                )
            }
    ) {
        val currentNote = notes.getOrNull(currentIndex) ?: notes.first()

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    translationX = swipeOffset.value
                    alpha = 1f - (abs(swipeOffset.value) / size.width * 0.3f).coerceIn(0f, 0.3f)
                }
        ) {
            // Use NoteCard with selection mode enabled to disable internal swipe gestures
            NoteCard(
                note = currentNote,
                onClick = { if (!swipeActivated) onNoteClick(currentNote) },
                onDelete = {},
                onOpenTodo = {},
                isSelectionMode = true,
                isSelected = false,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Count badge (top-right) if multiple notes
        if (notes.size > 1) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
                shape = RoundedCornerShape(12.dp),
                color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f)
            ) {
                Text(
                    text = "${currentIndex + 1}/${notes.size}",
                    color = androidx.compose.ui.graphics.Color.White,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp
                    ),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        // Swipe indicators (dots at bottom center)
        if (notes.size > 1) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                notes.forEachIndexed { index, _ ->
                    val isActive = index == currentIndex
                    Box(
                        modifier = Modifier
                            .size(if (isActive) 8.dp else 6.dp)
                            .clip(CircleShape)
                            .background(
                                if (isActive) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                            )
                    )
                }
            }
        }
    }
}
