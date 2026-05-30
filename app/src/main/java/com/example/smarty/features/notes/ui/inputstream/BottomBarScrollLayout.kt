package com.example.smarty.features.notes.ui.inputstream

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import android.content.res.Configuration
import com.example.smarty.ui.theme.ComponentSpacing
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.zIndex
import com.example.smarty.ui.theme.SmartyBrushes

@Composable
fun BottomBarScrollLayout(
    visible: Boolean,
    isDarkTheme: Boolean,
    isSearchMode: Boolean,
    textValue: TextFieldValue,
    recentSearches: List<String>,
    showSelectionPill: Boolean,
    selectedNoteIds: Set<String>,
    bottomContentPadding: Dp,
    bottomContent: @Composable (Modifier) -> Unit
) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val isKeyboardVisible = WindowInsets.ime.getBottom(density) > 0

    val attachmentCount = 0
    val attachmentRowHeight = if (attachmentCount > 0) 60.dp else 0.dp
    val multiLineExtraHeight = 0.dp

    val inputFieldHeight = if (showSelectionPill) 0.dp else (72.dp + attachmentRowHeight + multiLineExtraHeight)
    val inputFieldPadding = ComponentSpacing.screenPadding
    val isSearchSuggestionsVisible = isSearchMode && textValue.text.isEmpty() && recentSearches.isNotEmpty()
    val searchSuggestionsHeight = if (isSearchSuggestionsVisible && !showSelectionPill) {
        when {
            isKeyboardVisible -> 100.dp
            isLandscape -> 120.dp
            else -> 200.dp
        }
    } else { 0.dp }

    val extraBottomCoverage = when {
        isKeyboardVisible -> 10.dp
        isLandscape -> 20.dp
        else -> 40.dp
    }
    val gradientOffset = 0.dp + when {
        isKeyboardVisible -> (-10).dp
        isLandscape -> (-10).dp
        else -> 0.dp
    }

    val baseGradientHeight = inputFieldHeight + inputFieldPadding + searchSuggestionsHeight + extraBottomCoverage
    val targetGradientHeight = baseGradientHeight

    val animatedGradientHeight by animateDpAsState(
        targetValue = targetGradientHeight,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label = "gradientHeightAnimation"
    )

    val animatedGradientOffset by animateDpAsState(
        targetValue = gradientOffset,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label = "gradientOffsetAnimation"
    )

    val bottomGradientBrush = if (isDarkTheme) SmartyBrushes.bottomScrimDark else SmartyBrushes.bottomScrimLight

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(
                initialOffsetY = { it / 2 },
                animationSpec = spring(dampingRatio = 0.85f, stiffness = 300f)
            ) + fadeIn(tween(250)),
            exit = slideOutVertically(
                targetOffsetY = { it / 2 },
                animationSpec = tween(200)
            ) + fadeOut(tween(200)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = bottomContentPadding)
                .navigationBarsPadding()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clipToBounds()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(animatedGradientHeight)
                        .offset(y = animatedGradientOffset)
                        .align(Alignment.BottomCenter)
                        .background(brush = bottomGradientBrush)
                        .zIndex(1f)
                )

                bottomContent(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(
                            start = 8.dp,
                            end = 8.dp,
                            bottom = ComponentSpacing.screenPadding,
                            top = 0.dp
                        )
                        .zIndex(2f)
                )
            }
        }
    }
}
