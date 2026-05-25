package com.example.smarty.core.domain.model

/**
 * Navigation tabs for the centralized UI.
 * Icons designed with psychological metaphors to trigger creativity.
 */
enum class NavigationTab(
    val label: String,
    val opensSheet: Boolean = false,
) {
    CHAT("ai"),
    NOTES("notes"),
    CALENDAR("calendar", opensSheet = true),
    STACKS("stacks", opensSheet = true),
    ARCHIVE("archive", opensSheet = true),
    SETTINGS("settings", opensSheet = true),
    GAMES("games", opensSheet = true)
}
