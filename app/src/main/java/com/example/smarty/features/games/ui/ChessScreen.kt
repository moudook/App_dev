package com.example.smarty.features.games.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.example.smarty.features.games.domain.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

private val LIGHT  = Color(0xFFEECFA1)
private val DARK   = Color(0xFF8B5E3C)
private val SEL    = Color(0xCCF0F04A)
private val PREV   = Color(0x99F0F04A)
private val DOT    = Color(0x55000000)
private val CHKRED = Color(0xCCFF3333)

private fun glyph(p: Pc) = when (p.t) {
    Typ.K -> "♚"; Typ.Q -> "♛"; Typ.R -> "♜"
    Typ.B -> "♝"; Typ.N -> "♞"; Typ.P -> "♟"
}

@Composable
fun ChessScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val engine = remember { ChessEngine() }
    val ai = remember { ChessAI(context) }
    val scope = rememberCoroutineScope()
    
    var isVsMode by remember { mutableStateOf(false) }
    var isAiThinking by remember { mutableStateOf(false) }
    var selectedSq by remember { mutableStateOf<Sq?>(null) }
    var highlights by remember { mutableStateOf<List<Sq>>(emptyList()) }

    fun reset() {
        engine.reset()
        selectedSq = null
        highlights = emptyList()
    }

    // Bridge for AI: Converts Any? board to legal move pairs
    fun getMovesForAI(bd: List<Any?>, isWhite: Boolean): List<Pair<Int, Int>> {
        val color = if (isWhite) Col.W else Col.B
        val moves = mutableListOf<Pair<Int, Int>>()
        // Use a temporary engine to generate moves from the simulated board
        val tempEngine = ChessEngine()
        @Suppress("UNCHECKED_CAST")
        tempEngine.board = bd as Board
        tempEngine.turn = color
        for (r in 0..7) for (f in 0..7) {
            val p = tempEngine.board.at(r, f)
            if (p != null && p.c == color) {
                for (t in tempEngine.getLegalMoves(Sq(r, f))) {
                    moves.add((r * 8 + f) to (t.r * 8 + t.f))
                }
            }
        }
        return moves
    }

    fun executeMove(from: Sq, to: Sq) {
        if (isVsMode && engine.turn == Col.W && !isAiThinking) {
            scope.launch(Dispatchers.Default) {
                // Approximate learning from human behavior
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
                    val aiMove = withContext(Dispatchers.Default) {
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

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Chess", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { reset() }) { Icon(Icons.Default.Refresh, "Reset") }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close") }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Play vs AI", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = isVsMode, onCheckedChange = { isVsMode = it; reset() }, modifier = Modifier.padding(start = 8.dp))
            }

            val statusColor = if (engine.inCheck) CHKRED else MaterialTheme.colorScheme.onBackground
            Text(if (isAiThinking) "AI is thinking..." else engine.status, color = statusColor, modifier = Modifier.padding(vertical = 8.dp))

            BoxWithConstraints(modifier = Modifier.fillMaxWidth().aspectRatio(1f).padding(8.dp)) {
                val sz = maxWidth / 8
                Column {
                    for (r in 0..7) {
                        Row {
                            for (f in 0..7) {
                                val sq = Sq(r, f); val pc = engine.board.at(sq)
                                val isSelected = selectedSq == sq; val isHighlight = highlights.contains(sq)
                                val isLastMove = sq == engine.lastFrom || sq == engine.lastTo
                                val isKingInCheck = engine.inCheck && pc?.t == Typ.K && pc.c == engine.turn
                                
                                Box(
                                    modifier = Modifier.size(sz)
                                        .background(if ((r + f) % 2 == 0) LIGHT else DARK)
                                        .background(if (isSelected) SEL else if (isKingInCheck) CHKRED else if (isLastMove) PREV else Color.Transparent)
                                        .clickable {
                                            if (engine.gameOver || isAiThinking) return@clickable
                                            if (isSelected) { selectedSq = null; highlights = emptyList() }
                                            else if (isHighlight) executeMove(selectedSq!!, sq)
                                            else if (pc != null && pc.c == engine.turn) {
                                                selectedSq = sq; highlights = engine.getLegalMoves(sq)
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (pc != null) Text(glyph(pc), fontSize = (sz.value * 0.7).sp, color = if (pc.c == Col.W) Color.White else Color.Black)
                                    if (isHighlight && pc == null) Box(modifier = Modifier.size(sz * 0.3f).clip(CircleShape).background(DOT))
                                }
                            }
                        }
                    }
                }
            }
        }

        if (engine.gameOver) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp)) {
                    Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Game Over", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(engine.status, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 12.dp))
                        Button(onClick = { reset() }) { Text("Play Again") }
                    }
                }
            }
        }
    }
}
