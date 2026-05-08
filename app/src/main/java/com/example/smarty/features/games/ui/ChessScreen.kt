package com.example.smarty.features.games.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarty.features.games.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

// ─────────────────────────────────────────────────────────────────────────────
// State
// ─────────────────────────────────────────────────────────────────────────────

private data class ChessUiState(
    val engineState: ChessEngine.State = ChessEngine.initStart(),
    val selected: Int = -1,
    val highlights: Set<Int> = emptySet(),
    val lastFrom: Int = -1,
    val lastTo: Int = -1,
    val status: String = "White to move",
    val inCheck: Boolean = false,
    val gameOver: Boolean = false,
    val gameResult: String = ""
)

// ─────────────────────────────────────────────────────────────────────────────
// Colors & Visuals (High-Contrast Board)
// ─────────────────────────────────────────────────────────────────────────────

private val BOARD_LIGHT = Color(0xFFE8E8E8)
private val BOARD_DARK = Color(0xFF4A4A4A)
private val SEL = Color(0x4DFF4081)
private val PREV = Color(0x33FF4081)
private val CHKRED = Color(0x99FF3333)
private val DOT = Color(0x66FF4081)

private fun glyph(p: Pc) = when (p.t) {
    Typ.K -> "♚"
    Typ.Q -> "♛"
    Typ.R -> "♜"
    Typ.B -> "♝"
    Typ.N -> "♞"
    Typ.P -> "♟"
}

// ─────────────────────────────────────────────────────────────────────────────
// Screen
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ChessScreen(onClose: () -> Unit) {
    var showIntro by remember { mutableStateOf(true) }

    if (showIntro) {
        GameIntroScreen(title = "Strategy", onPlay = { showIntro = false })
    } else {
        ChessGameContent(onClose = onClose)
    }
}

@Composable
fun ChessGameContent(onClose: () -> Unit) {
    val context = LocalContext.current
    val ai = remember { ChessAI(context) }
    val scope = rememberCoroutineScope()

    var state by remember { mutableStateOf(ChessUiState()) }
    var isVsMode by remember { mutableStateOf(false) }
    var isAiThinking by remember { mutableStateOf(false) }

    fun reset() {
        state = ChessUiState()
        ai.clearTT()
        ai.onGameEnd()
    }

    fun executeMove(from: Int, to: Int) {
        val s = state
        val es = s.engineState
        val moves = IntArray(256)
        val caps = IntArray(256)
        val moveCount = ChessEngine.legalMoves(es, moves, caps)

        var selectedMove = -1
        for (i in 0 until moveCount) {
            if (ChessEngine.moveFrom(moves[i]) == from && ChessEngine.moveTo(moves[i]) == to) {
                selectedMove = moves[i]
                break
            }
        }

        if (selectedMove == -1) return

        val newEs = es.copy()
        ChessEngine.applyMove(newEs, selectedMove, ChessEngine.UndoInfo())

        val nextTurn = newEs.turn
        val check = ChessEngine.inCheck(newEs, nextTurn)
        val hasMoves = ChessEngine.anyLegalMove(newEs)

        val turnName = if (nextTurn == 0) "White" else "Black"
        val winnerName = if (nextTurn == 0) "Black" else "White"

        val (over, result, msg) = when {
            check && !hasMoves -> Triple(true, "checkmate", "$winnerName wins by checkmate!")
            !check && !hasMoves -> Triple(true, "stalemate", "Stalemate — it's a draw!")
            check -> Triple(false, "", "$turnName is in check!")
            else -> Triple(false, "", "$turnName to move")
        }

        state = s.copy(
            engineState = newEs,
            selected = -1,
            highlights = emptySet(),
            lastFrom = from,
            lastTo = to,
            status = msg,
            inCheck = check && !over,
            gameOver = over,
            gameResult = result
        )

        if (isVsMode && !over && nextTurn == 1) {
            isAiThinking = true
            scope.launch {
                val learningState = state.engineState.copy()
                withContext(Dispatchers.Default) {
                    ai.updateLearningFromState(learningState, ai.evaluate(learningState))
                }
                delay(Random.nextLong(200, 600))
                val searchState = state.engineState.copy()
                val aiMove = withContext(Dispatchers.Default) {
                    ai.findBestMove(searchState, 3)
                }
                if (aiMove != null) {
                    executeMove(ChessEngine.moveFrom(aiMove), ChessEngine.moveTo(aiMove))
                }
                isAiThinking = false
            }
        }
    }

    fun click(sqIdx: Int) {
        if (state.gameOver || isAiThinking) return
        val s = state
        val es = s.engineState

        var clickedPcCol = -1
        for (col in 0..1) {
            var occupied = false
            for (pt in 0..5) {
                if (ChessEngine.getPcBb(es, pcBbIdx(pt, col)) and bit(sqIdx) != 0L) {
                    occupied = true
                    break
                }
            }
            if (occupied) {
                clickedPcCol = col
                break
            }
        }

        when {
            s.selected == sqIdx -> state = s.copy(selected = -1, highlights = emptySet())
            s.selected != -1 && s.highlights.contains(sqIdx) -> executeMove(s.selected, sqIdx)
            clickedPcCol == es.turn -> {
                val moves = IntArray(256)
                val caps = IntArray(256)
                val moveCount = ChessEngine.legalMoves(es, moves, caps)
                val h = mutableSetOf<Int>()
                for (i in 0 until moveCount) {
                    if (ChessEngine.moveFrom(moves[i]) == s.selected || ChessEngine.moveFrom(moves[i]) == sqIdx) {
                        if (ChessEngine.moveFrom(moves[i]) == sqIdx) h.add(ChessEngine.moveTo(moves[i]))
                    }
                }
                state = s.copy(selected = sqIdx, highlights = h)
            }
            else -> state = s.copy(selected = -1, highlights = emptySet())
        }
    }

    val es = state.engineState
    val board: Board = remember(es) { ChessEngine.toBoard(es) }
    val checkKingIdx = if (state.inCheck || (state.gameOver && state.gameResult == "checkmate")) ChessEngine.kingSq(es, es.turn) else -1

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Strategy", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Light), color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
                IconButton(onClick = { reset() }) { Icon(Icons.Default.Refresh, "New Game", tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)) }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close", tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)) }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.padding(bottom = 12.dp)) {
                Text("Play vs AI", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f), modifier = Modifier.padding(end = 12.dp))
                Switch(checked = isVsMode, onCheckedChange = { isVsMode = it; reset() })
            }

            val statusColor = if (state.inCheck) CHKRED else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)

            Box(contentAlignment = Alignment.Center, modifier = Modifier.height(24.dp)) {
                if (isAiThinking) Text("AI is thinking...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                else Text(state.status, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = statusColor)
            }

            Spacer(Modifier.height(8.dp))

            BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp).aspectRatio(1f)) {
                val sz = maxWidth / 8
                Column(modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))) {
                    for (row in 0..7) {
                        Row {
                            for (file in 0..7) {
                                val sqI = sqIdx(row, file)
                                val pc = board.at(row, file)
                                val light = (row + file) % 2 == 0
                                val base = if (light) BOARD_LIGHT else BOARD_DARK
                                val overlay = when {
                                    state.selected == sqI -> SEL
                                    sqI == state.lastFrom || sqI == state.lastTo -> PREV
                                    sqI == checkKingIdx -> CHKRED
                                    else -> Color.Transparent
                                }
                                val isTarget = state.highlights.contains(sqI)
                                Box(modifier = Modifier.size(sz).background(base).background(overlay).clickable { click(sqI) }, contentAlignment = Alignment.Center) {
                                    if (file == 0) Text("${8 - row}", fontSize = 9.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f), modifier = Modifier.align(Alignment.TopStart).padding(1.dp))
                                    if (row == 7) Text("${"abcdefgh"[file]}", fontSize = 9.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f), modifier = Modifier.align(Alignment.BottomEnd).padding(1.dp))
                                    if (isTarget) {
                                        if (pc == null) Box(Modifier.size(sz * 0.32f).clip(CircleShape).background(DOT))
                                        else Box(Modifier.fillMaxSize().border((sz.value * 0.09f).dp, DOT, RoundedCornerShape(0.dp)))
                                    }
                                    if (pc != null) {
                                        Text(
                                            glyph(pc),
                                            fontSize = (sz.value * 0.70f).sp,
                                            lineHeight = (sz.value * 0.70f).sp,
                                            textAlign = TextAlign.Center,
                                            color = if (pc.c == Col.W) Color.White else Color.Black,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = state.gameOver,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Card(
                    modifier = Modifier.padding(top = 24.dp).padding(horizontal = 32.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f))
                ) {
                    Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (state.gameResult == "checkmate") "♛ Checkmate!" else "½ Stalemate",
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(state.status, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(20.dp))
                        Button(onClick = { reset() }, modifier = Modifier.fillMaxWidth()) {
                            Text("New Game")
                        }
                    }
                }
            }
        }
    }
}