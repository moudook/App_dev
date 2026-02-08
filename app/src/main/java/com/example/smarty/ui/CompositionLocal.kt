package com.example.smarty.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * CompositionLocal for providing a dynamic accent color.
 * This allows components deep in the hierarchy to use the primary theme color
 * without having to pass it down manually.
 */
val LocalAccentColor = compositionLocalOf {
    // Default to a noticeable color to make it obvious if it's not provided.
    Color.Magenta
}
