package com.example.smarty.ui.components.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.components.markdown.MarkdownRenderer

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

@Composable
fun TextEffectPerWord(
    text: String,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    normalColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
    boldColor: Color = MaterialTheme.colorScheme.onSurface,
    linkColor: Color = com.example.smarty.ui.LocalAccentColor.current,
    codeColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
    codeBackgroundColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.4f),
    codeBorderColor: Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
    isStreaming: Boolean = false
) {
    val currentTypography = MaterialTheme.typography
    val mavenProTypography = remember(currentTypography) {
        androidx.compose.material3.Typography(
            displayLarge = currentTypography.displayLarge.copy(fontFamily = com.example.smarty.ui.theme.MavenProFont),
            displayMedium = currentTypography.displayMedium.copy(fontFamily = com.example.smarty.ui.theme.MavenProFont),
            displaySmall = currentTypography.displaySmall.copy(fontFamily = com.example.smarty.ui.theme.MavenProFont),
            headlineLarge = currentTypography.headlineLarge.copy(fontFamily = com.example.smarty.ui.theme.MavenProFont),
            headlineMedium = currentTypography.headlineMedium.copy(fontFamily = com.example.smarty.ui.theme.MavenProFont),
            headlineSmall = currentTypography.headlineSmall.copy(fontFamily = com.example.smarty.ui.theme.MavenProFont),
            titleLarge = currentTypography.titleLarge.copy(fontFamily = com.example.smarty.ui.theme.MavenProFont),
            titleMedium = currentTypography.titleMedium.copy(fontFamily = com.example.smarty.ui.theme.MavenProFont),
            titleSmall = currentTypography.titleSmall.copy(fontFamily = com.example.smarty.ui.theme.MavenProFont),
            bodyLarge = currentTypography.bodyLarge.copy(fontFamily = com.example.smarty.ui.theme.MavenProFont),
            bodyMedium = currentTypography.bodyMedium.copy(fontFamily = com.example.smarty.ui.theme.MavenProFont),
            bodySmall = currentTypography.bodySmall.copy(fontFamily = com.example.smarty.ui.theme.MavenProFont),
            labelLarge = currentTypography.labelLarge.copy(fontFamily = com.example.smarty.ui.theme.MavenProFont),
            labelMedium = currentTypography.labelMedium.copy(fontFamily = com.example.smarty.ui.theme.MavenProFont),
            labelSmall = currentTypography.labelSmall.copy(fontFamily = com.example.smarty.ui.theme.MavenProFont)
        )
    }

    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme,
        shapes = MaterialTheme.shapes,
        typography = mavenProTypography
    ) {
        Box(modifier = modifier) {
            val currentDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = currentDensity.density,
                    fontScale = currentDensity.fontScale * (1.6180339f / 1.5f)
                )
            ) {
                MarkdownRenderer(
                    content = text,
                    isUser = false,
                    normalColor = normalColor,
                    boldColor = boldColor,
                    linkColor = linkColor,
                    codeColor = codeColor,
                    codeBackgroundColor = codeBackgroundColor,
                    codeBorderColor = codeBorderColor,
                    isStreaming = isStreaming,
                )
            }
        }
    }
}
