package com.example.smarty.features.notes.ui.inputstream

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarty.features.breathing.GuidedBreathingContent
import com.example.smarty.features.games.ui.ChessScreen
import com.example.smarty.features.games.ui.CoinTossGameContent
import com.example.smarty.features.games.ui.TicTacToeGameContent
import com.example.smarty.ui.components.UnifiedDragHandle
import com.example.smarty.ui.theme.ComponentColors
import com.example.smarty.ui.theme.LocalShapes

/**
 * Games Hub — Anthropic-aesthetic redesign.
 *
 * Four game cards replace the old settings-list layout.
 * Each card opens the game directly in a refined bottom sheet.
 * The tab is the source of truth — no redundant navigation needed.
 */

private data class GameCardData(
    val title: String,
    val subtitle: String,
    val emoji: String,
)

private val gameCards =
    listOf(
        GameCardData("Tic-Tac-Toe", "Challenge the AI or a friend", "✕ ○"),
        GameCardData("Chess", "Strategic play, your pace", "♟"),
        GameCardData("Coin Toss", "Let fate decide", "⊙"),
        GameCardData("Guided Breathing", "Calm your mind", "◎"),
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GamesContent(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val isDark = isSystemInDarkTheme()
    val shapes = LocalShapes.current

    var showTicTacToe by remember { mutableStateOf(false) }
    var showCoinToss by remember { mutableStateOf(false) }
    var showChess by remember { mutableStateOf(false) }
    var showGuidedBreathing by remember { mutableStateOf(false) }

    // Use skipPartiallyExpanded = true so the sheet fully expands to content height
    val ticTacToeSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coinTossSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val chessSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val breathingSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val iOSSheetShape =
        androidx.compose.foundation.shape
            .RoundedCornerShape(topStart = 38.dp, topEnd = 38.dp)

    // ── Main layout ────────────────────────────────────────────────────
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Tiny section label — Anthropic style
        Text(
            text = "GAMES",
            style =
                MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 10.sp,
                    letterSpacing = 1.5.sp,
                ),
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f),
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        )

        val accentColors =
            listOf(
                MaterialTheme.colorScheme.primaryContainer,
                MaterialTheme.colorScheme.secondaryContainer,
                MaterialTheme.colorScheme.tertiaryContainer,
                ComponentColors.breathingAccent,
            )
        val onClicks =
            listOf(
                { showTicTacToe = true },
                { showChess = true },
                { showCoinToss = true },
                { showGuidedBreathing = true },
            )

        gameCards.forEachIndexed { index, game ->
            GameHubCard(
                title = game.title,
                subtitle = game.subtitle,
                emoji = game.emoji,
                accentBg = accentColors[index],
                isDark = isDark,
                onClick = onClicks[index],
            )
        }

        Spacer(modifier = Modifier.height(120.dp))
    }

    // ── Tic-Tac-Toe bottom sheet ───────────────────────────────────────
    if (showTicTacToe) {
        ModalBottomSheet(
            onDismissRequest = { showTicTacToe = false },
            sheetState = ticTacToeSheetState,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
            dragHandle = { UnifiedDragHandle() },
            shape = iOSSheetShape,
        ) {
            TicTacToeGameContent(onClose = { showTicTacToe = false })
        }
    }

    // ── Chess bottom sheet ─────────────────────────────────────────────
    if (showChess) {
        ModalBottomSheet(
            onDismissRequest = { showChess = false },
            sheetState = chessSheetState,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
            dragHandle = { UnifiedDragHandle() },
            shape = iOSSheetShape,
        ) {
            ChessScreen(onClose = { showChess = false })
        }
    }

    // ── Coin Toss bottom sheet ─────────────────────────────────────────
    if (showCoinToss) {
        ModalBottomSheet(
            onDismissRequest = { showCoinToss = false },
            sheetState = coinTossSheetState,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
            dragHandle = { UnifiedDragHandle() },
            shape = iOSSheetShape,
        ) {
            CoinTossGameContent(onClose = { showCoinToss = false })
        }
    }

    // ── Guided Breathing bottom sheet ──────────────────────────────────
    if (showGuidedBreathing) {
        ModalBottomSheet(
            onDismissRequest = { showGuidedBreathing = false },
            sheetState = breathingSheetState,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
            dragHandle = { UnifiedDragHandle() },
            shape = iOSSheetShape,
        ) {
            GuidedBreathingContent(onClose = { showGuidedBreathing = false })
        }
    }
}

/**
 * Anthropic-style game hub card.
 * Large, breathable, warm — not a settings row.
 */
@Composable
private fun GameHubCard(
    title: String,
    subtitle: String,
    emoji: String,
    accentBg: Color,
    isDark: Boolean,
    onClick: () -> Unit,
) {
    val shapes = LocalShapes.current
    val borderColor =
        if (isDark) {
            Color.White.copy(alpha = 0.06f)
        } else {
            Color.Black.copy(alpha = 0.05f)
        }

    val cardBg =
        if (isDark) {
            Color.White.copy(alpha = 0.03f)
        } else {
            Color.White.copy(alpha = 0.70f)
        }

    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "cardScale",
    )

    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .graphicsLayerScale(scale)
                .clip(shapes.card)
                .clickable(
                    onClick = onClick,
                    onClickLabel = "Open $title",
                ),
        shape = shapes.card,
        color = cardBg,
        border = androidx.compose.foundation.BorderStroke(0.5.dp, borderColor),
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style =
                        MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                        ),
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
                )
            }

            // Accent blob with emoji
            Box(
                modifier =
                    Modifier
                        .size(52.dp)
                        .clip(shapes.cardSmall)
                        .background(accentBg),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = emoji,
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                )
            }
        }
    }
}

// Helper extension to apply scale
private fun Modifier.graphicsLayerScale(scale: Float): Modifier =
    this.graphicsLayer(
        scaleX = scale,
        scaleY = scale,
    )
