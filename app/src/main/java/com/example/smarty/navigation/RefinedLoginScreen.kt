package com.example.smarty.features.auth.ui // Or appropriate navigation package

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun RefinedLoginScreen(
    onLoginSuccess: () -> Unit,
    modifier: Modifier = Modifier,
    shouldSkipSplash: Boolean = false,
) {
    // Because we unified the "Tim Cook" level design into the core LoginScreen.kt file,
    // RefinedLoginScreen simply invokes the S-Tier LoginScreen.
    // This removes code duplication across your codebase!

    LoginScreen(
        onLoginSuccess = onLoginSuccess,
        modifier = modifier,
        shouldSkipSplash = shouldSkipSplash,
    )
}
