package com.example.smarty.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

/**
 * Modern Soft Minimalist Shape System
 * "Super-Rounded Friendly" design language
 *
 * Philosophy: No sharp corners anywhere. Heavy rounding signature.
 */
data class SmartyShapes(
    // Card shapes - largest radius for main containers (24-32dp)
    val card: RoundedCornerShape = RoundedCornerShape(26.dp), // Standard card (app default)
    val cardSmall: RoundedCornerShape = RoundedCornerShape(24.dp),
    val cardMedium: RoundedCornerShape = RoundedCornerShape(28.dp),
    val cardLarge: RoundedCornerShape = RoundedCornerShape(32.dp),
    // Inner element shapes - buttons, inputs (12-24dp)
    val button: RoundedCornerShape = RoundedCornerShape(16.dp),
    val buttonLarge: RoundedCornerShape = RoundedCornerShape(20.dp),
    val input: RoundedCornerShape = RoundedCornerShape(24.dp),
    val inputLarge: RoundedCornerShape = RoundedCornerShape(28.dp),
    // Small elements - tags, toggles, chips (pill shape)
    val pill: RoundedCornerShape = RoundedCornerShape(999.dp),
    val tag: RoundedCornerShape = RoundedCornerShape(8.dp),
    val chipSmall: RoundedCornerShape = RoundedCornerShape(6.dp),
    // Dialog/Modal shapes
    val dialog: RoundedCornerShape = RoundedCornerShape(28.dp),
    val bottomSheet: RoundedCornerShape =
        RoundedCornerShape(
            topStart = 28.dp,
            topEnd = 28.dp,
            bottomStart = 0.dp,
            bottomEnd = 0.dp,
        ),
    // FAB and icon button shapes
    val fab: RoundedCornerShape = RoundedCornerShape(16.dp),
    val iconButton: RoundedCornerShape = RoundedCornerShape(12.dp),
    // Chat bubble shapes (asymmetric "tail" corners)
    val bubbleUser: RoundedCornerShape = RoundedCornerShape(24.dp, 24.dp, 4.dp, 24.dp),
    val bubbleAi: RoundedCornerShape = RoundedCornerShape(24.dp, 24.dp, 24.dp, 4.dp),
    // Filter chip / large chip shape
    val chipLarge: RoundedCornerShape = RoundedCornerShape(20.dp),
    // Skeleton loader element shape (subtle rounding)
    val skeleton: RoundedCornerShape = RoundedCornerShape(4.dp),
)

public val LocalShapes = staticCompositionLocalOf { SmartyShapes() }

/**
 * Access shapes from any composable
 */
val MaterialTheme.smartyShapes: SmartyShapes
    @Composable
    @ReadOnlyComposable
    get() = LocalShapes.current
