package com.example.smarty.features.notes.ui.inputstream

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.smarty.R
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.components.SmartySettingsCard
import com.example.smarty.ui.components.SmartySettingsRow
import com.example.smarty.ui.theme.SmartyIcons
import com.example.smarty.features.games.ui.TicTacToeGameContent
import com.example.smarty.features.games.ui.CoinTossGameContent
import com.example.smarty.features.games.ui.ChessScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GamesContent(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    val accentColor = LocalAccentColor.current
    
    var showTicTacToe by remember { mutableStateOf(false) }
    var showCoinToss by remember { mutableStateOf(false) }
    var showChess by remember { mutableStateOf(false) }
    
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column {
                Text(
                    text = "Games & Activities",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 20.dp, bottom = 8.dp)
                )

                SmartySettingsCard {
                    SmartySettingsRow(
                        icon = SmartyIcons.Casino,
                        label = "Coin Toss",
                        subtitle = "Quick decision making",
                        onClick = { showCoinToss = true },
                        iconColor = accentColor
                    )
                    SmartySettingsRow(
                        icon = SmartyIcons.Games,
                        label = "Chess (vs AI)",
                        subtitle = "Test your strategy against Smarty",
                        onClick = { showChess = true },
                        iconColor = accentColor
                    )
                    SmartySettingsRow(
                        icon = SmartyIcons.Games,
                        label = "Tic-Tac-Toe",
                        subtitle = "A quick mental break",
                        onClick = { showTicTacToe = true },
                        iconColor = accentColor
                    )
                }
            }
        }
        
        item {
            Spacer(modifier = Modifier.height(100.dp))
        }
    }
    
    if (showTicTacToe) {
        ModalBottomSheet(
            onDismissRequest = { showTicTacToe = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            Box(modifier = Modifier.fillMaxHeight(0.85f).fillMaxWidth()) {
                TicTacToeGameContent(onClose = { showTicTacToe = false })
            }
        }
    }

    if (showCoinToss) {
        ModalBottomSheet(
            onDismissRequest = { showCoinToss = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            Box(modifier = Modifier.fillMaxHeight(0.85f).fillMaxWidth()) {
                CoinTossGameContent(onClose = { showCoinToss = false })
            }
        }
    }

    if (showChess) {
        ModalBottomSheet(
            onDismissRequest = { showChess = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            Box(modifier = Modifier.fillMaxHeight(0.85f).fillMaxWidth()) {
                ChessScreen(onClose = { showChess = false })
            }
        }
    }
}
