package com.example.smarty.features.notes.ui.inputstream

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.smarty.core.domain.model.NavigationTab
import com.example.smarty.features.notes.domain.SmartyViewModel.CloudSyncState
import com.example.smarty.ui.components.CloudSyncIndicator
import com.example.smarty.ui.components.ConnectionStatus
import com.example.smarty.ui.components.ConnectionStatusIndicator
import com.example.smarty.ui.components.HorizontalActionBar
import com.example.smarty.ui.theme.SmartyBrushes

@Composable
fun InputStreamTopBar(
    isDarkTheme: Boolean,
    connectionStatus: ConnectionStatus,
    cloudSyncState: CloudSyncState,
    onSyncCloud: () -> Unit,
    selectedTab: NavigationTab,
    onTabSelected: (NavigationTab) -> Unit,
    isChatMode: Boolean,
    showChatHistoryInline: Boolean,
    showCalendarInline: Boolean,
    showStacksInline: Boolean,
    showArchiveInline: Boolean,
    showSettingsInline: Boolean,
    showGamesInline: Boolean,
    archiveCount: Int,
    userIsScrolling: Boolean,
    modifier: Modifier = Modifier
) {
    val scrollTransition = updateTransition(
        targetState = userIsScrolling,
        label = "TopBarScroll"
    )

    val topBarAlpha by scrollTransition.animateFloat(
        transitionSpec = {
            if (targetState) {
                tween(durationMillis = 200, delayMillis = 350, easing = LinearEasing)
            } else {
                tween(durationMillis = 200, easing = LinearOutSlowInEasing)
            }
        },
        label = "topBarAlpha"
    ) { isScrolling -> if (isScrolling) 0f else 1f }

    val topBarTranslationY by scrollTransition.animateFloat(
        transitionSpec = {
            if (targetState) {
                tween(durationMillis = 350, delayMillis = 350, easing = FastOutSlowInEasing)
            } else {
                spring(dampingRatio = 0.75f, stiffness = 400f)
            }
        },
        label = "topBarTranslationY"
    ) { isScrolling -> if (isScrolling) -300f else 0f }

    val topGradientTranslationY by scrollTransition.animateFloat(
        transitionSpec = { spring(dampingRatio = 0.75f, stiffness = 100f) },
        label = "topGradientTranslationY"
    ) { isScrolling -> if (isScrolling) -120f else 0f }

    val topGradientBrush = if (isDarkTheme) {
        SmartyBrushes.topScrimDark
    } else {
        SmartyBrushes.topScrimLight
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { translationY = topGradientTranslationY }
            .background(brush = topGradientBrush)
            .zIndex(2f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ConnectionStatusIndicator(status = connectionStatus)
                CloudSyncIndicator(
                    syncState = cloudSyncState,
                    onSyncClick = onSyncCloud
                )
            }
        }

        HorizontalActionBar(
            modifier = Modifier.graphicsLayer {
                alpha = topBarAlpha
                translationY = topBarTranslationY
            },
            selectedTab = when {
                showStacksInline -> NavigationTab.STACKS
                showArchiveInline -> NavigationTab.ARCHIVE
                showSettingsInline -> NavigationTab.SETTINGS
                showGamesInline -> NavigationTab.GAMES
                showCalendarInline -> NavigationTab.CALENDAR
                isChatMode -> NavigationTab.CHAT
                else -> selectedTab
            },
            onTabSelected = { tab -> onTabSelected(tab) },
            isChatMode = isChatMode,
            isHistoryMode = showChatHistoryInline,
            isCalendarMode = showCalendarInline,
            isStacksMode = showStacksInline,
            isArchiveMode = showArchiveInline,
            isSettingsMode = showSettingsInline,
            archiveCount = archiveCount,
            isScrolling = userIsScrolling
        )
    }
}
