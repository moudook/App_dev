package com.example.smarty.features.games.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarty.features.games.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

@Composable
fun KingShape(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // 6 layers, 5 gaps
        // Let's divide total height h into 17 units
        // Gaps take 1 unit, Layers take 2 units
        val unit = h / 17f
        val lh = unit * 2f
        val gh = unit

        val cols = 14
        val cw = w / cols

        // Define layers from bottom (index 0) to top (index 5)
        // Each layer is represented by a list of column ranges (IntRange)
        val layers =
            listOf(
                // Layer 0 (Bottom-most, fully solid)
                listOf(0..13),
                // Layer 1 (Smaller, centered)
                listOf(2..11),
                // Layer 2 (Same size as Layer 1)
                listOf(2..11),
                // Layer 3 (Same size as Layer 0)
                listOf(0..13),
                // Layer 4 (Splitted in two)
                listOf(1..4, 9..12),
                // Layer 5 (Splitted into four chunks)
                listOf(0..1, 3..4, 9..10, 12..13),
            )

        for (i in 0 until 6) {
            // Layer 0 is at the bottom, Layer 5 is at the top.
            // Calculate y position from bottom:
            val y = h - (i + 1) * lh - i * gh

            val ranges = layers[i]
            for (range in ranges) {
                val startX = range.first * cw
                val rectW = (range.last - range.first + 1) * cw
                drawRect(
                    color = color,
                    topLeft =
                        androidx.compose.ui.geometry
                            .Offset(startX, y),
                    size =
                        androidx.compose.ui.geometry
                            .Size(rectW, lh),
                )
            }
        }
    }
}

@Composable
fun QueenShape(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // 6 layers, 5 gaps
        val unit = h / 17f
        val lh = unit * 2f
        val gh = unit

        val cols = 14
        val cw = w / cols

        // Define layers from bottom (index 0) to top (index 5)
        val layers =
            listOf(
                // Layer 0 (Bottom-most, fully solid)
                listOf(0..13),
                // Layer 1 (Fully solid)
                listOf(0..13),
                // Layer 2 (Left, Middle, Right)
                listOf(0..3, 6..7, 10..13),
                // Layer 3 (Left, Middle, Right - smaller outer)
                listOf(0..1, 6..7, 12..13),
                // Layer 4 (Center medium)
                listOf(4..9),
                // Layer 5 (Top-most, center small)
                listOf(6..7),
            )

        for (i in 0 until 6) {
            val y = h - (i + 1) * lh - i * gh

            val ranges = layers[i]
            for (range in ranges) {
                val startX = range.first * cw
                val rectW = (range.last - range.first + 1) * cw
                drawRect(
                    color = color,
                    topLeft =
                        androidx.compose.ui.geometry
                            .Offset(startX, y),
                    size =
                        androidx.compose.ui.geometry
                            .Size(rectW, lh),
                )
            }
        }
    }
}

@Composable
fun BishopShape(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // 6 layers, 5 gaps
        val unit = h / 17f
        val lh = unit * 2f
        val gh = unit

        val cols = 14
        val cw = w / cols

        // Define layers from bottom (index 0) to top (index 5)
        val layers =
            listOf(
                // Layer 0 (Bottom-most, fully solid)
                listOf(0..13),
                // Layer 1 (Medium centered)
                listOf(3..10),
                // Layer 2 (Same size as Layer 1)
                listOf(3..10),
                // Layer 3 (Same size as Layer 0)
                listOf(0..13),
                // Layer 4 (Medium wide centered - slimmer for pyramid effect)
                listOf(4..9),
                // Layer 5 (Top-most, center small)
                listOf(6..7),
            )

        for (i in 0 until 6) {
            val y = h - (i + 1) * lh - i * gh

            val ranges = layers[i]
            for (range in ranges) {
                val startX = range.first * cw
                val rectW = (range.last - range.first + 1) * cw
                drawRect(
                    color = color,
                    topLeft =
                        androidx.compose.ui.geometry
                            .Offset(startX, y),
                    size =
                        androidx.compose.ui.geometry
                            .Size(rectW, lh),
                )
            }
        }
    }
}

@Composable
fun KnightShape(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // 6 layers, 5 gaps
        val unit = h / 17f
        val lh = unit * 2f
        val gh = unit

        val cols = 14
        val cw = w / cols

        // Define layers from bottom (index 0) to top (index 5)
        val layers =
            listOf(
                // Layer 0 (Bottom-most, base)
                listOf(1..12),
                // Layer 1 (Lower body, left-aligned flat back)
                listOf(2..10),
                // Layer 2 (Mid body)
                listOf(2..9),
                // Layer 3 (Lower head and snout extending right)
                listOf(2..3, 5..13),
                // Layer 4 (Upper head and eye gap)
                listOf(2..4, 6..10),
                // Layer 5 (Top-most, ears)
                listOf(3..4, 7..8),
            )

        for (i in 0 until 6) {
            val y = h - (i + 1) * lh - i * gh

            val ranges = layers[i]
            for (range in ranges) {
                val startX = range.first * cw
                val rectW = (range.last - range.first + 1) * cw
                drawRect(
                    color = color,
                    topLeft =
                        androidx.compose.ui.geometry
                            .Offset(startX, y),
                    size =
                        androidx.compose.ui.geometry
                            .Size(rectW, lh),
                )
            }
        }
    }
}

@Composable
fun RookShape(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // 6 layers, 5 gaps
        val unit = h / 17f
        val lh = unit * 2f
        val gh = unit

        val cols = 14
        val cw = w / cols

        // Define layers from bottom (index 0) to top (index 5)
        val layers =
            listOf(
                // Layer 0 (Bottom-most, fully solid)
                listOf(0..13),
                // Layer 1 (Fully solid)
                listOf(0..13),
                // Layer 2 (Fully solid)
                listOf(0..13),
                // Layer 3 (Fully solid)
                listOf(0..13),
                // Layer 4 (Three chunks)
                listOf(0..2, 5..8, 11..13),
                // Layer 5 (Top-most, three chunks)
                listOf(0..2, 5..8, 11..13),
            )

        for (i in 0 until 6) {
            val y = h - (i + 1) * lh - i * gh

            val ranges = layers[i]
            for (range in ranges) {
                val startX = range.first * cw
                val rectW = (range.last - range.first + 1) * cw
                drawRect(
                    color = color,
                    topLeft =
                        androidx.compose.ui.geometry
                            .Offset(startX, y),
                    size =
                        androidx.compose.ui.geometry
                            .Size(rectW, lh),
                )
            }
        }
    }
}

@Composable
fun PawnShape(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // Use the same scale as other pieces so Pawns are naturally shorter (4 layers vs 6)
        val unit = h / 17f
        val lh = unit * 2f
        val gh = unit

        val cols = 14
        val cw = w / cols

        // 4 layers from bottom to top
        val layers =
            listOf(
                // Layer 0 (Bottom-most, wide base)
                listOf(2..11),
                // Layer 1 (Narrow neck)
                listOf(5..8),
                // Layer 2 (Wide collar/head)
                listOf(2..11),
                // Layer 3 (Top-most, small knob)
                listOf(5..8),
            )

        for (i in 0 until 4) {
            val y = h - (i + 1) * lh - i * gh

            val ranges = layers[i]
            for (range in ranges) {
                val startX = range.first * cw
                val rectW = (range.last - range.first + 1) * cw
                drawRect(
                    color = color,
                    topLeft =
                        androidx.compose.ui.geometry
                            .Offset(startX, y),
                    size =
                        androidx.compose.ui.geometry
                            .Size(rectW, lh),
                )
            }
        }
    }
}

@Composable
fun ChessScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val engine = remember { ChessEngine() }
    val ai = remember { ChessAI(context) }
    val scope = rememberCoroutineScope()
    val isDark = isSystemInDarkTheme()

    var isVsMode by remember { mutableStateOf(true) }
    var isAiThinking by remember { mutableStateOf(false) }
    var selectedSq by remember { mutableStateOf<Sq?>(null) }
    var highlights by remember { mutableStateOf<List<Sq>>(emptyList()) }

    fun reset() {
        engine.reset()
        selectedSq = null
        highlights = emptyList()
    }

    fun getMovesForAI(
        bd: List<Any?>,
        isWhite: Boolean,
    ): List<Pair<Int, Int>> {
        val color = if (isWhite) Col.W else Col.B
        val moves = mutableListOf<Pair<Int, Int>>()
        val tempEngine = ChessEngine()
        @Suppress("UNCHECKED_CAST")
        tempEngine.board = bd as Board
        tempEngine.turn = color
        for (r in 0..7) {
            for (f in 0..7) {
                val p = tempEngine.board.at(r, f)
                if (p != null && p.c == color) {
                    for (t in tempEngine.getLegalMoves(Sq(r, f))) {
                        moves.add((r * 8 + f) to (t.r * 8 + t.f))
                    }
                }
            }
        }
        return moves
    }

    fun executeMove(
        from: Sq,
        to: Sq,
    ) {
        if (isVsMode && engine.turn == Col.W && !isAiThinking) {
            scope.launch(Dispatchers.Default) {
                val currentBoard = engine.board
                val eval = ai.evaluate(currentBoard as List<Any?>)
                ai.updateLearning(currentBoard as List<Any?>, eval)
            }
        }
        if (engine.executeMove(from, to)) {
            selectedSq = null
            highlights = emptyList()
            if (isVsMode && !engine.gameOver && engine.turn == Col.B) {
                isAiThinking = true
                scope.launch {
                    delay(Random.nextLong(300, 700))
                    val aiMove =
                        withContext(Dispatchers.Default) {
                            ai.findBestMove(engine.board as List<Any?>, 3, false) { bd, isW ->
                                getMovesForAI(bd, isW)
                            }
                        }
                    if (aiMove != null) {
                        executeMove(Sq(aiMove.first / 8, aiMove.first % 8), Sq(aiMove.second / 8, aiMove.second % 8))
                    }
                    isAiThinking = false
                }
            }
        }
    }

    // ── Board colors — using MaterialTheme ──────────────────────
    val lightSq = MaterialTheme.colorScheme.surface
    val darkSq = MaterialTheme.colorScheme.surfaceVariant
    val selColor = MaterialTheme.colorScheme.primaryContainer
    val prevColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
    val dotColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
    val checkColor = MaterialTheme.colorScheme.errorContainer

    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background),
    ) {
        val totalWidth = maxWidth
        val totalHeight = maxHeight
        val boardPadding = 20.dp
        val boardSize = totalWidth - boardPadding * 2

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = boardPadding)
                    .padding(top = 4.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ── Header ────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Status
                Text(
                    text =
                        when {
                            isAiThinking -> "AI thinking…"
                            engine.inCheck -> "Check!"
                            engine.gameOver -> engine.status
                            else -> engine.status
                        },
                    style =
                        MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp,
                        ),
                    color =
                        when {
                            engine.inCheck && !engine.gameOver -> checkColor
                            engine.gameOver -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                        },
                    modifier = Modifier.weight(1f),
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Reset icon — subtle
                    Box(
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { reset() }
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                    ) {
                        Text(
                            text = "Reset",
                            style =
                                MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Normal,
                                    fontSize = 11.sp,
                                ),
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.38f),
                        )
                    }

                    // Mode pill
                    ChessModeTogglePill(
                        isVsAi = isVsMode,
                        onToggle = {
                            isVsMode = !isVsMode
                            reset()
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ── Board ─────────────────────────────────────────────────────
            Box(
                modifier =
                    Modifier
                        .size(boardSize)
                        .clip(RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                val sz = boardSize / 8
                Column {
                    for (r in 0..7) {
                        Row {
                            for (f in 0..7) {
                                val sq = Sq(r, f)
                                val pc = engine.board.at(sq)
                                val isSelected = selectedSq == sq
                                val isHighlight = highlights.contains(sq)
                                val isLastMove = sq == engine.lastFrom || sq == engine.lastTo
                                val isKingInCheck = engine.inCheck && pc?.t == Typ.K && pc.c == engine.turn

                                Box(
                                    modifier =
                                        Modifier
                                            .size(sz)
                                            .background(if ((r + f) % 2 == 0) lightSq else darkSq)
                                            .background(
                                                when {
                                                    isSelected -> selColor
                                                    isKingInCheck -> checkColor
                                                    isLastMove -> prevColor
                                                    else -> Color.Transparent
                                                },
                                            ).clickable {
                                                if (engine.gameOver || isAiThinking) return@clickable
                                                if (isSelected) {
                                                    selectedSq = null
                                                    highlights = emptyList()
                                                } else if (isHighlight) {
                                                    executeMove(selectedSq!!, sq)
                                                } else if (pc != null && pc.c == engine.turn) {
                                                    selectedSq = sq
                                                    highlights = engine.getLegalMoves(sq)
                                                }
                                            },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (pc != null) {
                                        when (pc.t) {
                                            Typ.K ->
                                                KingShape(
                                                    color = if (pc.c == Col.W) Color.White else Color.Black,
                                                    modifier = Modifier.size(sz * 0.62f),
                                                )
                                            Typ.Q ->
                                                QueenShape(
                                                    color = if (pc.c == Col.W) Color.White else Color.Black,
                                                    modifier = Modifier.size(sz * 0.62f),
                                                )
                                            Typ.B ->
                                                BishopShape(
                                                    color = if (pc.c == Col.W) Color.White else Color.Black,
                                                    modifier = Modifier.size(sz * 0.62f),
                                                )
                                            Typ.N ->
                                                KnightShape(
                                                    color = if (pc.c == Col.W) Color.White else Color.Black,
                                                    modifier = Modifier.size(sz * 0.62f),
                                                )
                                            Typ.R ->
                                                RookShape(
                                                    color = if (pc.c == Col.W) Color.White else Color.Black,
                                                    modifier = Modifier.size(sz * 0.62f),
                                                )
                                            Typ.P ->
                                                PawnShape(
                                                    color = if (pc.c == Col.W) Color.White else Color.Black,
                                                    modifier = Modifier.size(sz * 0.62f),
                                                )
                                        }
                                    }
                                    // Move target dot
                                    if (isHighlight && pc == null) {
                                        Box(
                                            modifier =
                                                Modifier
                                                    .size(sz * 0.28f)
                                                    .clip(CircleShape)
                                                    .background(dotColor),
                                        )
                                    }
                                    // Capture ring
                                    if (isHighlight && pc != null) {
                                        Box(
                                            modifier =
                                                Modifier
                                                    .size(sz * 0.9f)
                                                    .clip(CircleShape)
                                                    .border(2.dp, dotColor, CircleShape),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Game Over Overlay ──────────────────────────────────────────────
        if (engine.gameOver) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .clickable(enabled = false) {},
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 4.dp,
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = engine.status,
                            style =
                                MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.SemiBold,
                                ),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        // Play again — minimal pill button
                        Box(
                            modifier =
                                Modifier
                                    .clip(RoundedCornerShape(24.dp))
                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
                                    .clickable { reset() }
                                    .padding(horizontal = 24.dp, vertical = 10.dp),
                        ) {
                            Text(
                                text = "Play Again",
                                style =
                                    MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = FontWeight.Medium,
                                    ),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Compact mode toggle pill for Chess — same Anthropic style.
 */
@Composable
private fun ChessModeTogglePill(
    isVsAi: Boolean,
    onToggle: () -> Unit,
) {
    val pillBg = MaterialTheme.colorScheme.surfaceVariant
    val activeBg = MaterialTheme.colorScheme.primaryContainer
    val activeText = MaterialTheme.colorScheme.onPrimaryContainer
    val inactiveText = MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier =
            Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(pillBg)
                .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf("AI" to true, "2P" to false).forEach { (label, aiSide) ->
            val isActive = isVsAi == aiSide
            Box(
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isActive) activeBg else Color.Transparent)
                        .clickable { if (!isActive) onToggle() }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style =
                        MaterialTheme.typography.labelSmall.copy(
                            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                            fontSize = 11.sp,
                        ),
                    color = if (isActive) activeText else inactiveText,
                )
            }
        }
    }
}
