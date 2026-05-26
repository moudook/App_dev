package com.example.smarty.features.games.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarty.R
import com.example.smarty.ui.theme.SmartyShadow
import com.example.smarty.ui.theme.softCardShadow
import com.example.smarty.ui.theme.LocalShapes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

import androidx.compose.ui.platform.LocalContext
import com.example.smarty.features.games.domain.TicTacToeAI

/**
 * Tic-Tac-Toe — Anthropic-aesthetic redesign.
 * Clean header row: status · mode toggle · reset.
 * No bottom chrome. Board is the focus.
 */
@Composable
fun TicTacToeScreen(onClose: () -> Unit) {
    TicTacToeGameContent(onClose = onClose)
}

@Composable
fun TicTacToeGameContent(onClose: () -> Unit) {
    val context = LocalContext.current
    val ai = remember { TicTacToeAI(context) }
    var isAiReady by remember { mutableStateOf(false) }

    val aiHistory = remember { mutableStateListOf<Pair<List<String?>, Int>>() }
    val userHistory = remember { mutableStateListOf<Pair<List<String?>, Int>>() }

    val board = remember { mutableStateListOf<String?>(null, null, null, null, null, null, null, null, null) }
    var isXNext by remember { mutableStateOf(true) }
    var winner by remember { mutableStateOf<String?>(null) }
    var isDraw by remember { mutableStateOf(false) }

    var isVsMode by remember { mutableStateOf(true) }
    var isComputerThinking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val isDark = isSystemInDarkTheme()

    // Board square colors — visible in both themes
    // Light: surfaceContainerLow gives a warm off-white card look on the stone canvas
    // Dark: subtle white tint on black
    val squareColor = if (isDark)
        Color.White.copy(alpha = 0.07f)
    else
        MaterialTheme.colorScheme.surfaceContainerLow

    val squareBorder = BorderStroke(
        1.dp,
        if (isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.09f)
    )

    // X color — primary accent; O color — clearly readable but secondary
    val xColor = MaterialTheme.colorScheme.primary
    val oColor = MaterialTheme.colorScheme.onBackground.copy(alpha = if (isDark) 0.45f else 0.55f)

    // Thinking pulse
    val infiniteTransition = rememberInfiniteTransition(label = "ThinkingPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.03f,
        targetValue = 0.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseAlpha"
    )

    fun checkWinner(b: List<String?>): String? {
        val lines = listOf(
            listOf(0,1,2), listOf(3,4,5), listOf(6,7,8),
            listOf(0,3,6), listOf(1,4,7), listOf(2,5,8),
            listOf(0,4,8), listOf(2,4,6)
        )
        for (line in lines) {
            val (a, b2, c) = line
            if (b[a] != null && b[a] == b[b2] && b[a] == b[c]) return b[a]
        }
        return null
    }

    fun resetGame() {
        for (i in 0 until 9) board[i] = null
        aiHistory.clear(); userHistory.clear()
        isXNext = true; winner = null; isDraw = false; isComputerThinking = false
    }

    LaunchedEffect(winner, isDraw) {
        if (winner != null || isDraw) {
            val reward = when (winner) {
                "O" -> 2.0f; "X" -> -2.0f; else -> -0.5f
            }
            if (aiHistory.isNotEmpty() || userHistory.isNotEmpty()) {
                ai.updateModel(aiHistory.toList(), userHistory.toList(), reward)
            }
        }
    }

    LaunchedEffect(ai, isVsMode) {
        if (isVsMode) { ai.activate(); isAiReady = true }
        else { ai.deactivate(); isAiReady = false }
    }

    DisposableEffect(ai) { onDispose { ai.deactivate() } }

    val gameOver = winner != null || isDraw

    // ── Root container — no top padding, tight bottom ──────────────────────
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 4.dp, bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        // ── Header row ─────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status label
            AnimatedContent(
                targetState = when {
                    gameOver && winner != null -> "🏆 ${winner!!} wins"
                    gameOver -> "Draw"
                    isComputerThinking -> "AI thinking…"
                    isXNext -> "X's turn"
                    else -> "O's turn"
                },
                transitionSpec = {
                    fadeIn(tween(200)) togetherWith fadeOut(tween(150))
                },
                label = "StatusAnim"
            ) { statusText ->
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp
                    ),
                    color = when {
                        gameOver && winner != null -> MaterialTheme.colorScheme.primary
                        gameOver -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                        else -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                    }
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Reset — only visible when game is over
                AnimatedVisibility(
                    visible = gameOver,
                    enter = fadeIn() + scaleIn(initialScale = 0.85f),
                    exit = fadeOut() + scaleOut(targetScale = 0.85f)
                ) {
                    // Compact text-style reset
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { resetGame() }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = "Again",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 11.sp
                            ),
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
                        )
                    }
                }

                // Pill mode toggle
                ModeTogglePill(
                    isVsAi = isVsMode,
                    onToggle = { isVsMode = !isVsMode; resetGame() }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ── 3×3 Board ──────────────────────────────────────────────────────
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            userScrollEnabled = false
        ) {
            items(9) { index ->
                val cellValue = board[index]
                val isThinkingCell = isComputerThinking && cellValue == null

                Surface(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .softCardShadow(
                            elevation = 3.dp,
                            shape = RoundedCornerShape(14.dp),
                        )
                        .clickable(
                            enabled = cellValue == null && !gameOver && !isComputerThinking
                        ) {
                            val stateBeforeMove = board.toList()
                            board[index] = if (isXNext) "X" else "O"
                            if (isVsMode) userHistory.add(stateBeforeMove to index)
                            isXNext = !isXNext

                            val win = checkWinner(board)
                            when {
                                win != null -> winner = win
                                board.all { it != null } -> isDraw = true
                                isVsMode && !isXNext && winner == null -> {
                                    isComputerThinking = true
                                    scope.launch {
                                        delay(Random.nextLong(400, 1100))
                                        val stateBeforeAi = board.toList()
                                        val aiIdx = ai.getBestMove(board)
                                        if (aiIdx != -1) {
                                            board[aiIdx] = "O"
                                            aiHistory.add(stateBeforeAi to aiIdx)
                                            isXNext = true
                                            val aiWin = checkWinner(board)
                                            when {
                                                aiWin != null -> winner = aiWin
                                                board.all { it != null } -> isDraw = true
                                            }
                                        }
                                        isComputerThinking = false
                                    }
                                }
                            }
                        },
                    shape = RoundedCornerShape(14.dp),
                    color = if (isThinkingCell)
                        squareColor.copy(alpha = squareColor.alpha + pulseAlpha)
                    else squareColor,
                    border = squareBorder
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        AnimatedContent(
                            targetState = cellValue,
                            transitionSpec = {
                                (scaleIn(animationSpec = tween(300, easing = EaseOutBack)) + fadeIn())
                                    .togetherWith(fadeOut(tween(100)))
                            },
                            label = "CellAnim"
                        ) { value ->
                            if (value != null) {
                                Text(
                                    text = value,
                                    style = MaterialTheme.typography.displaySmall.copy(
                                        fontSize = 44.sp,
                                        fontWeight = if (value == "X") FontWeight.SemiBold else FontWeight.ExtraLight,
                                        color = if (value == "X") xColor else oColor
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Compact pill-shaped mode toggle — Anthropic style.
 * Two tiny labels inside a small rounded pill background.
 */
@Composable
private fun ModeTogglePill(isVsAi: Boolean, onToggle: () -> Unit) {
    val isDark = isSystemInDarkTheme()
    val pillBg = if (isDark) Color.White.copy(alpha = 0.07f) else Color.Black.copy(alpha = 0.05f)
    val activeBg = if (isDark) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.10f)
    val activeText = MaterialTheme.colorScheme.onBackground
    val inactiveText = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.38f)

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(pillBg)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        listOf("AI" to true, "2P" to false).forEach { (label, aiSide) ->
            val isActive = isVsAi == aiSide
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (isActive) activeBg else Color.Transparent)
                    .clickable { if (!isActive) onToggle() }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = 11.sp
                    ),
                    color = if (isActive) activeText else inactiveText
                )
            }
        }
    }
}
