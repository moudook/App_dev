package com.example.smarty.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

// =============================================================================
// APPLE HIG CONCENTRIC RADII (Outer = Inner + Padding)
// =============================================================================
data class AppleShapes(
    val small: RoundedCornerShape = RoundedCornerShape(8.dp),
    val medium: RoundedCornerShape = RoundedCornerShape(16.dp),
    val large: RoundedCornerShape = RoundedCornerShape(24.dp), // Outer card (16 inner + 8 padding = 24)
    val extraLarge: RoundedCornerShape = RoundedCornerShape(32.dp),
    val pill: RoundedCornerShape = RoundedCornerShape(999.dp), // For standard pills/inputs
    val bubbleUser: RoundedCornerShape = RoundedCornerShape(24.dp, 24.dp, 4.dp, 24.dp),
    val bubbleAi: RoundedCornerShape = RoundedCornerShape(24.dp, 24.dp, 24.dp, 4.dp)
)

val LocalAppleShapes = staticCompositionLocalOf { AppleShapes() }

val MaterialTheme.appleShapes: AppleShapes
    @Composable
    @ReadOnlyComposable
    get() = LocalAppleShapes.current


/**
 * Modern Soft Minimalist Shape System
 * "Super-Rounded Friendly" design language
 *
 * Philosophy: No sharp corners anywhere. Heavy rounding signature.
 */
data class SmartyShapes(
    // Card shapes - largest radius for main containers (24-32dp)
    @Deprecated("Migrating to Apple HIG Concentric Radii")
    val card: RoundedCornerShape = RoundedCornerShape(26.dp), // Standard card (app default)
    @Deprecated("Migrating to Apple HIG Concentric Radii")
    val cardSmall: RoundedCornerShape = RoundedCornerShape(24.dp),
    @Deprecated("Migrating to Apple HIG Concentric Radii")
    val cardMedium: RoundedCornerShape = RoundedCornerShape(28.dp),
    @Deprecated("Migrating to Apple HIG Concentric Radii")
    val cardLarge: RoundedCornerShape = RoundedCornerShape(32.dp),
    // Inner element shapes - buttons, inputs (12-24dp)
    @Deprecated("Migrating to Apple HIG Concentric Radii")
    val button: RoundedCornerShape = RoundedCornerShape(16.dp),
    @Deprecated("Migrating to Apple HIG Concentric Radii")
    val buttonLarge: RoundedCornerShape = RoundedCornerShape(20.dp),
    @Deprecated("Migrating to Apple HIG Concentric Radii")
    val input: RoundedCornerShape = RoundedCornerShape(24.dp),
    @Deprecated("Migrating to Apple HIG Concentric Radii")
    val inputLarge: RoundedCornerShape = RoundedCornerShape(28.dp),
    // Small elements - tags, toggles, chips (pill shape)
    @Deprecated("Migrating to Apple HIG Concentric Radii")
    val pill: RoundedCornerShape = RoundedCornerShape(999.dp),
    @Deprecated("Migrating to Apple HIG Concentric Radii")
    val tag: RoundedCornerShape = RoundedCornerShape(8.dp),
    @Deprecated("Migrating to Apple HIG Concentric Radii")
    val chipSmall: RoundedCornerShape = RoundedCornerShape(6.dp),
    // Dialog/Modal shapes
    @Deprecated("Migrating to Apple HIG Concentric Radii")
    val dialog: RoundedCornerShape = RoundedCornerShape(28.dp),
    @Deprecated("Migrating to Apple HIG Concentric Radii")
    val bottomSheet: RoundedCornerShape =
        RoundedCornerShape(
            topStart = 28.dp,
            topEnd = 28.dp,
            bottomStart = 0.dp,
            bottomEnd = 0.dp,
        ),
    // FAB and icon button shapes
    @Deprecated("Migrating to Apple HIG Concentric Radii")
    val fab: RoundedCornerShape = RoundedCornerShape(16.dp),
    @Deprecated("Migrating to Apple HIG Concentric Radii")
    val iconButton: RoundedCornerShape = RoundedCornerShape(12.dp),
    // Chat bubble shapes (asymmetric "tail" corners)
    @Deprecated("Migrating to Apple HIG Concentric Radii")
    val bubbleUser: RoundedCornerShape = RoundedCornerShape(24.dp, 24.dp, 4.dp, 24.dp),
    @Deprecated("Migrating to Apple HIG Concentric Radii")
    val bubbleAi: RoundedCornerShape = RoundedCornerShape(24.dp, 24.dp, 24.dp, 4.dp),
    // Filter chip / large chip shape
    @Deprecated("Migrating to Apple HIG Concentric Radii")
    val chipLarge: RoundedCornerShape = RoundedCornerShape(20.dp),
    // Skeleton loader element shape (subtle rounding)
    @Deprecated("Migrating to Apple HIG Concentric Radii")
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

