package com.example.smarty.ui.screens

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
import com.example.smarty.ui.components.SmartyButton
import com.example.smarty.ui.theme.SmartyShadow
import com.example.smarty.ui.theme.softCardShadow

/**
 * Tic-Tac-Toe Mental Break Screen
 * A calm, centralized UI for a quick mental break.
 */
@Composable
fun TicTacToeScreen(onClose: () -> Unit) {
    val board = remember { mutableStateListOf<String?>(null, null, null, null, null, null, null, null, null) }
    var isXNext by remember { mutableStateOf(true) }
    var winner by remember { mutableStateOf<String?>(null) }
    var isDraw by remember { mutableStateOf(false) }

    val accentColor = LocalAccentColor.current
    val isDark = isSystemInDarkTheme()

    // Cell styling for better visibility
    // Dark mode: Use a slightly lighter gray (0xFF1E1E1E) against the black background for minimal but clear contrast
    // Light mode: Use standard surface white
    val cellColor = if (isDark) Color(0xFF1E1E1E) else MaterialTheme.colorScheme.surface
    val cellBorder = if (isDark) BorderStroke(1.dp, Color(0xFF2C2C2E)) else null

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
        isXNext = true
        winner = null
        isDraw = false
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
                modifier = Modifier.padding(bottom = 48.dp)
            )

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

                    Surface(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .softCardShadow(
                                elevation = SmartyShadow.cardElevation,
                                shape = RoundedCornerShape(26.dp)
                            )
                            .clickable(enabled = cellValue == null && winner == null && !isDraw) {
                                board[index] = if (isXNext) "X" else "O"
                                isXNext = !isXNext

                                val win = checkWinner(board)
                                if (win != null) {
                                    winner = win
                                } else if (board.all { it != null }) {
                                    isDraw = true
                                }
                            },
                        shape = RoundedCornerShape(26.dp),
                        color = cellColor,
                        border = cellBorder
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            AnimatedContent(
                                targetState = cellValue,
                                transitionSpec = {
                                    (scaleIn(animationSpec = tween(300, easing = EaseOutBack)) + fadeIn())
                                        .togetherWith(fadeOut())
                                },
                                label = "CellAnim"
                            ) { value ->
                                if (value != null) {
                                    Text(
                                        text = value,
                                        style = MaterialTheme.typography.displaySmall.copy(
                                            fontSize = 42.sp,
                                            fontWeight = FontWeight.Light,
                                            color = if (value == "X") accentColor else MaterialTheme.colorScheme.onSurface
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

                    SmartyButton(
                        onClick = { resetGame() },
                        text = stringResource(R.string.tictactoe_play_again),
                        modifier = Modifier.width(200.dp)
                    )
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
