package com.example.smarty.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Semantic Colors — Centralized iOS-style semantic color tokens.
 *
 * These were previously hardcoded as Color(0x...) across 15+ files:
 * - SettingsComponents.kt, BackupSettingsScreen.kt, NoteCardIcons.kt,
 *   SettingsContent.kt, DigestScreen.kt, SmartyInputField.kt, etc.
 *
 * Usage:
 *   import com.example.smarty.ui.theme.SemanticColors
 *   Icon(tint = SemanticColors.success)
 *
 * These colors are theme-independent (same in light/dark) because they
 * convey meaning through color (success, error, info) rather than adapting
 * to the surface. Use MaterialTheme.colorScheme for surface-adaptive colors.
 */
object SemanticColors {
    // ─── Status Colors ───────────────────────────────────────────────
    /** Success / Active / Positive (iOS Green) */
    val success = Color(0xFF34C759)

    /** Error / Destructive / Danger (iOS Red) */
    val error = Color(0xFFFF3B30)

    /** Info / Interactive / Link (iOS Blue) */
    val info = Color(0xFF007AFF)

    /** Warning / Caution (iOS Yellow/Amber) */
    val warning = Color(0xFFEAB308)

    // ─── Content Type Colors ─────────────────────────────────────────
    /** Neutral / General file / Media (System Gray) */
    val neutral = Color(0xFF8E8E93)

    // ─── Bubble Colors ───────────────────────────────────────────────
    /** User bubble background — inverted: light on dark, dark on light */
    val userBubbleLight = Color(0xFFF5F5F5)
    val userBubbleDark = Color(0xFF1A1A1A)
}
