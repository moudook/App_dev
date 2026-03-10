package com.example.smarty.ui.components.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FabPosition
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Standard Screen Scaffold - Provides consistent screen structure across the app.
 * 
 * Single Responsibility: Only handles screen scaffold layout.
 * DRY: Replaces repeated Scaffold patterns in 10+ screens.
 * 
 * @param topAppBar Top app bar composable (use StandardTopAppBar)
 * @param bottomBar Bottom bar composable
 * @param floatingActionButton Floating action button
 * @param floatingActionButtonPosition FAB position
 * @param snackbarHost Snackbar host state
 * @param containerColor Background color
 * @param contentColor Content color
 * @param content Screen content with padding values
 */
@Composable
fun StandardScreenScaffold(
    topAppBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    floatingActionButtonPosition: FabPosition = FabPosition.End,
    snackbarHostState: SnackbarHostState? = null,
    containerColor: Color = MaterialTheme.colorScheme.background,
    contentColor: Color = MaterialTheme.colorScheme.onBackground,
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        topBar = topAppBar,
        bottomBar = bottomBar,
        floatingActionButton = floatingActionButton,
        floatingActionButtonPosition = floatingActionButtonPosition,
        snackbarHost = snackbarHostState?.let { { SnackbarHost(it) } },
        containerColor = containerColor,
        contentColor = contentColor
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            content(paddingValues)
        }
    }
}

/**
 * Standard Screen Scaffold with back button.
 * 
 * @param title Screen title
 * @param onBackClick Back button click handler
 * @param actions Top bar actions
 * @param bottomBar Bottom bar composable
 * @param floatingActionButton Floating action button
 * @param content Screen content with padding values
 */
@Composable
fun StandardScreenWithBack(
    title: String,
    onBackClick: () -> Unit,
    actions: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    StandardScreenScaffold(
        topAppBar = {
            StandardTopAppBar(
                title = title,
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = actions
            )
        },
        bottomBar = bottomBar,
        floatingActionButton = floatingActionButton,
        content = content
    )
}
