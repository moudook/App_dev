package com.example.smarty.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarty.core.domain.model.Note
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.animation.FisheyeAnimations
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Alphabetical Fast Scroller with Fisheye Wave Effect
 *
 * A touch-activated sidebar that allows quick alphabetical navigation
 * through a list of notes. Initially invisible, appears when user
 * touches the screen edges.
 *
 * Features:
 * - Fisheye lens wave animation on touch/drag
 * - A-Z letter display with scaling effect
 * - Haptic feedback on letter changes
 * - Auto-hide after 2 seconds of inactivity
 *
 * @param notes List of notes to build letter-to-index mapping from
 * @param lazyListState LazyListState to control scrolling
 * @param modifier Modifier for positioning
 * @param alignment Which side of screen (Start = left, End = right)
 */
@Composable
fun AlphabetFastScroller(
    notes: List<Note>,
    lazyListState: LazyListState,
    modifier: Modifier = Modifier,
    alignment: Alignment.Horizontal = Alignment.End,
) {
    val coroutineScope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    // Visibility and selection state
    var isVisible by remember { mutableStateOf(false) }
    var selectedIndex by remember { mutableIntStateOf(-1) }
    var lastSelectedIndex by remember { mutableIntStateOf(-1) }

    // Build letter-to-index mapping (memoized)
    val letterIndexMap by remember(notes) {
        derivedStateOf { buildLetterIndexMap(notes) }
    }

    val availableLetters by remember(letterIndexMap) {
        derivedStateOf { letterIndexMap.keys }
    }

    // Auto-hide timer
    LaunchedEffect(isVisible, selectedIndex) {
        if (isVisible && selectedIndex == -1) {
            delay(2000L) // Hide after 2 seconds of inactivity
            isVisible = false
        }
    }

    // Calculate letter index from Y position
    fun calculateLetterIndex(
        touchY: Float,
        totalHeight: Float,
    ): Int {
        val padding = with(density) { 40.dp.toPx() }
        val effectiveHeight = totalHeight - (2 * padding)
        if (effectiveHeight <= 0) return 0
        val letterHeight = effectiveHeight / 26f
        val adjustedY = (touchY - padding).coerceIn(0f, effectiveHeight)
        return (adjustedY / letterHeight).toInt().coerceIn(0, 25)
    }

    // Perform scroll to the selected letter
    fun scrollToLetter(letterIdx: Int) {
        val letter = ('A' + letterIdx)
        letterIndexMap[letter]?.let { noteIndex ->
            coroutineScope.launch {
                lazyListState.animateScrollToItem(noteIndex)
            }
        }
    }

    Box(
        modifier = modifier.fillMaxHeight(),
        contentAlignment = if (alignment == Alignment.End) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        // Touch detection zone (invisible, covers screen edge)
        Box(
            modifier =
                Modifier
                    .fillMaxHeight()
                    .width(40.dp)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                isVisible = true
                                selectedIndex = calculateLetterIndex(offset.y, size.height.toFloat())
                                if (selectedIndex != lastSelectedIndex) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    lastSelectedIndex = selectedIndex
                                }
                            },
                            onDrag = { change, _ ->
                                val newIndex = calculateLetterIndex(change.position.y, size.height.toFloat())
                                if (newIndex != selectedIndex) {
                                    selectedIndex = newIndex
                                    if (selectedIndex != lastSelectedIndex) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        lastSelectedIndex = selectedIndex
                                    }
                                    // Scroll as user drags
                                    scrollToLetter(selectedIndex)
                                }
                            },
                            onDragEnd = {
                                // Final scroll on release
                                scrollToLetter(selectedIndex)
                                selectedIndex = -1
                            },
                            onDragCancel = {
                                selectedIndex = -1
                            },
                        )
                    }.pointerInput(Unit) {
                        detectTapGestures(
                            onPress = { offset ->
                                isVisible = true
                                selectedIndex = calculateLetterIndex(offset.y, size.height.toFloat())
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                tryAwaitRelease()
                                scrollToLetter(selectedIndex)
                                selectedIndex = -1
                            },
                        )
                    },
        )

        // Animated visibility for the scroller strip
        AnimatedVisibility(
            visible = isVisible,
            enter =
                fadeIn(tween(150)) +
                    slideInHorizontally(
                        initialOffsetX = { if (alignment == Alignment.End) it else -it },
                        animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f),
                    ),
            exit =
                fadeOut(tween(200)) +
                    slideOutHorizontally(
                        targetOffsetX = { if (alignment == Alignment.End) it else -it },
                    ),
        ) {
            // Letter column with fisheye effect
            Column(
                modifier =
                    Modifier
                        .fillMaxHeight()
                        .padding(vertical = 40.dp)
                        .padding(
                            start = if (alignment == Alignment.End) 0.dp else 4.dp,
                            end = if (alignment == Alignment.End) 4.dp else 0.dp,
                        ).background(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                            shape = RoundedCornerShape(16.dp),
                        ).padding(horizontal = 6.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.SpaceEvenly,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ('A'..'Z').forEachIndexed { index, letter ->
                    FisheyeLetter(
                        letter = letter,
                        index = index,
                        selectedIndex = selectedIndex,
                        isAvailable = letter in availableLetters,
                        alignment = alignment,
                    )
                }
            }
        }
    }
}

/**
 * Individual letter in the fisheye scroller with animated scaling and offset.
 */
@Composable
private fun FisheyeLetter(
    letter: Char,
    index: Int,
    selectedIndex: Int,
    isAvailable: Boolean,
    alignment: Alignment.Horizontal,
) {
    // Animated scale with fisheye effect
    val targetScale = FisheyeAnimations.calculateFisheyeScale(index, selectedIndex)
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f),
        label = "letterScale",
    )

    // Animated horizontal offset (wave bulge)
    val targetOffset = FisheyeAnimations.calculateFisheyeOffset(index, selectedIndex)
    val offsetX by animateFloatAsState(
        targetValue = if (alignment == Alignment.End) -targetOffset else targetOffset,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f),
        label = "letterOffset",
    )

    // Animated alpha
    val targetAlpha =
        if (isAvailable) {
            FisheyeAnimations.calculateFisheyeAlpha(index, selectedIndex)
        } else {
            0.2f
        }
    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(100),
        label = "letterAlpha",
    )

    // Font weight for selected letter
    val fontWeight = if (index == selectedIndex) FontWeight.Bold else FontWeight.Normal

    Text(
        text = letter.toString(),
        style =
            MaterialTheme.typography.labelSmall.copy(
                fontSize = 14.sp,
                fontWeight = fontWeight,
            ),
        color =
            when {
                index == selectedIndex -> LocalAccentColor.current
                isAvailable -> MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
                else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
            },
        modifier =
            Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = with(this) { offsetX }
                },
    )
}

/**
 * Builds a mapping from letters A-Z to the index of the first note
 * whose title starts with that letter.
 *
 * Since notes are sorted by createdAt DESC (not alphabetically),
 * we scan the entire list to find the first occurrence of each letter.
 *
 * Time Complexity: O(n) where n = number of notes
 * Space Complexity: O(26) = O(1) for the map
 */
private fun buildLetterIndexMap(notes: List<Note>): Map<Char, Int> {
    val letterMap = mutableMapOf<Char, Int>()

    notes.forEachIndexed { index, note ->
        val firstChar = note.title.firstOrNull()?.uppercaseChar() ?: return@forEachIndexed
        if (firstChar in 'A'..'Z' && firstChar !in letterMap) {
            // First occurrence of this letter in the list
            letterMap[firstChar] = index
        }
    }

    return letterMap
}
