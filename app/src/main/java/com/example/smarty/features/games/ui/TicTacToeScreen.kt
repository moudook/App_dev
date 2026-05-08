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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarty.R
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.theme.SmartyShadow
import com.example.smarty.ui.theme.softCardShadow
import com.example.smarty.ui.theme.LocalShapes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

import androidx.compose.ui.platform.LocalContext
import com.example.smarty.features.games.domain.TicTacToeAI

/**
 * Tic-Tac-Toe Mental Break Screen
 * A calm, centralized UI for a quick mental break.
 */
@Composable
fun TicTacToeScreen(onClose: () -> Unit) {
    var showIntro by remember { mutableStateOf(true) }

    if (showIntro) {
        GameIntroScreen(title = "Tic Tac Toe", onPlay = { showIntro = false })
    } else {
        TicTacToeGameContent(onClose = onClose)
    }
}

@Composable
fun TicTacToeGameContent(onClose: () -> Unit) {
    val context = LocalContext.current
    val ai = remember { TicTacToeAI(context) }
    var isAiReady by remember { mutableStateOf(false) }
    
    // Tracks AI's moves (State before move -> Index chosen)
    val aiHistory = remember { mutableStateListOf<Pair<List<String?>, Int>>() }
    // Tracks User's moves (State before move -> Index chosen)
    val userHistory = remember { mutableStateListOf<Pair<List<String?>, Int>>() }

    val board = remember { mutableStateListOf<String?>(null, null, null, null, null, null, null, null, null) }
    var isXNext by remember { mutableStateOf(true) }
    var winner by remember { mutableStateOf<String?>(null) }
    var isDraw by remember { mutableStateOf(false) }

    var isVsMode by remember { mutableStateOf(false) }
    var isComputerThinking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val accentColor = LocalAccentColor.current
    val isDark = isSystemInDarkTheme()

    // Cell styling for better visibility
    val cellColor = if (isDark) Color.White.copy(alpha = 0.05f) else Color.White.copy(alpha = 0.7f)
    val cellBorder = BorderStroke(1.dp, if (isDark) Color.White.copy(alpha = 0.1f) else Color.Black.copy(alpha = 0.05f))

    // Thinking animation state for "Organic Breath"
    val infiniteTransition = rememberInfiniteTransition(label = "ThinkingPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.05f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseAlpha"
    )

    // Win checking logic
    fun checkWinner(currentBoard: List<String?>): String? {
        val lines = listOf(
            listOf(0, 1, 2), listOf(3, 4, 5), listOf(6, 7, 8), // Rows
            listOf(0, 3, 6), listOf(1, 4, 7), listOf(2, 5, 8), // Cols
            listOf(0, 4, 8), listOf(2, 4, 6)             // Diagonals
        )
        for (line in lines) {
            val (a, b, c) = line
            if (currentBoard[a] != null && currentBoard[a] == currentBoard[b] && currentBoard[a] == currentBoard[c]) {
                return currentBoard[a]
            }
        }
        return null
    }

    fun resetGame() {
        for (i in 0 until 9) board[i] = null
        aiHistory.clear()
        userHistory.clear()
        isXNext = true
        winner = null
        isDraw = false
        isComputerThinking = false
    }

    // High-Stakes Reward System
    LaunchedEffect(winner, isDraw) {
        if (winner != null || isDraw) {
            val reward = when (winner) {
                "O" -> 2.0f // AI won (Aggressive Reward)
                "X" -> -2.0f // AI lost (Aggressive Penalty)
                else -> -0.5f // Draw (Penalty - AI must avoid stalemates)
            }
            if (aiHistory.isNotEmpty() || userHistory.isNotEmpty()) {
                ai.updateModel(aiHistory.toList(), userHistory.toList(), reward)
            }
        }
    }

    LaunchedEffect(ai, isVsMode) {
        if (isVsMode) {
            ai.activate()
            isAiReady = true
        } else {
            ai.deactivate()
            isAiReady = false
        }
    }

    DisposableEffect(ai) {
        onDispose {
            ai.deactivate()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        // Close button at top-right
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.close),
                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(max = 400.dp)
        ) {
            Text(
                text = stringResource(R.string.tictactoe_header),
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Light,
                    color = MaterialTheme.colorScheme.onBackground
                ),
                modifier = Modifier.padding(bottom = 24.dp)
            )

            // VS Mode Toggle
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .padding(bottom = 24.dp)
                    .clickable {
                        isVsMode = !isVsMode
                        resetGame()
                    }
            ) {
                Text(
                    text = stringResource(R.string.tictactoe_vs_mode),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                    modifier = Modifier.padding(end = 12.dp)
                )
                Switch(
                    checked = isVsMode,
                    onCheckedChange = {
                        isVsMode = it
                        resetGame()
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                        uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                )
            }

            // Thinking indicator
             AnimatedVisibility(
                 visible = isComputerThinking,
                 enter = fadeIn() + expandVertically(),
                 exit = fadeOut() + shrinkVertically()
             ) {
                Text(
                    text = "AI is thinking...",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }

            // 3x3 Grid
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier
                    .aspectRatio(1f)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                userScrollEnabled = false
            ) {
                items(9) { index ->
                    val cellValue = board[index]
                    val isThinkingCell = isComputerThinking && cellValue == null

                    Surface(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .softCardShadow(
                                elevation = if (isThinkingCell) (4.dp + (8.dp * pulseAlpha)) else 4.dp,
                                shape = RoundedCornerShape(16.dp),
                            )
                            .clickable(enabled = cellValue == null && winner == null && !isDraw && !isComputerThinking) {
                                // Record state for learning (only if VS AI mode)
                                val stateBeforeMove = board.toList()
                                
                                // Make the move
                                board[index] = if (isXNext) "X" else "O"
                                
                                if (isVsMode) {
                                    // In AI mode, humans are always X, so this tracks user moves
                                    userHistory.add(stateBeforeMove to index)
                                }
                                
                                isXNext = !isXNext

                                // Check for win/draw after the move
                                val win = checkWinner(board)
                                if (win != null) {
                                    winner = win
                                } else if (board.all { it != null }) {
                                    isDraw = true
                                } else if (isVsMode && !isXNext && winner == null) {
                                    // AI's turn triggered only in VS Mode
                                    isComputerThinking = true
                                    scope.launch {
                                        delay(Random.nextLong(500, 1500))

                                        val stateBeforeAiMove = board.toList()
                                        val computerMoveIndex = ai.getBestMove(board)
                                        
                                        if (computerMoveIndex != -1) {
                                            board[computerMoveIndex] = "O"
                                            aiHistory.add(stateBeforeAiMove to computerMoveIndex)
                                            isXNext = true // Back to Human (X)

                                            val aiWin = checkWinner(board)
                                            if (aiWin != null) {
                                                winner = aiWin
                                            } else if (board.all { it != null }) {
                                                isDraw = true
                                            }
                                        }
                                        isComputerThinking = false
                                    }
                                }
                            },
                        shape = RoundedCornerShape(16.dp),
                        color = if (isThinkingCell) cellColor.copy(alpha = cellColor.alpha + pulseAlpha) else cellColor,
                        border = cellBorder
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            AnimatedContent(
                                targetState = cellValue,
                                transitionSpec = {
                                    (scaleIn(animationSpec = tween(400, easing = EaseOutBack)) + fadeIn())
                                        .togetherWith(fadeOut())
                                },
                                label = "CellAnim"
                            ) { value ->
                                if (value != null) {
                                    Text(
                                        text = value,
                                        style = MaterialTheme.typography.displaySmall.copy(
                                            fontSize = 48.sp,
                                            fontWeight = if (value == "X") FontWeight.SemiBold else FontWeight.Light,
                                            color = if (value == "X") accentColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(48.dp))

            // Game Status / Actions
            AnimatedVisibility(
                visible = winner != null || isDraw,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut()
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val statusText = when {
                        winner != null -> stringResource(R.string.tictactoe_winner, winner!!)
                        else -> stringResource(R.string.tictactoe_draw)
                    }

                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onBackground
                        ),
                        modifier = Modifier.padding(bottom = 24.dp)
                    )

                    Button(
                        onClick = { resetGame() },
                        modifier = Modifier.width(200.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LocalAccentColor.current,
                            contentColor = Color.White
                        )
                    ) {
                        Text(stringResource(R.string.tictactoe_play_again))
                    }
                }
            }

            if (winner == null && !isDraw) {
                Text(
                    text = if (isXNext) stringResource(R.string.tictactoe_x_turn) else stringResource(R.string.tictactoe_o_turn),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                    )
                )
            }
        }
    }
}
