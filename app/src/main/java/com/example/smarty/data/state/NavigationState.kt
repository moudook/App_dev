package com.example.smarty.data.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Global Navigation State Management.
 *
 * Single Responsibility: Centralized navigation tracking.
 * DRY: Replaces individual navigation callbacks across screens.
 * Global State: Shared across all features.
 *
 * Usage:
 * ```
 * // Navigate to a screen
 * navigationState.navigate("notes/detail/123")
 *
 * // Go back
 * navigationState.popBack()
 *
 * // Observe current screen
 * val currentScreen by navigationState.currentScreen.collectAsState()
 *
 * // Observe navigation stack
 * val backStack by navigationState.backStack.collectAsState()
 * ```
 */
class NavigationState {
    private val _navStack = MutableStateFlow<List<String>>(emptyList())
    val navStack: StateFlow<List<String>> = _navStack.asStateFlow()

    private val _currentScreen = MutableStateFlow<String>("")
    val currentScreen: StateFlow<String> = _currentScreen.asStateFlow()

    /**
     * Navigate to a new screen.
     * @param route The route to navigate to (e.g., "notes/detail/123")
     * @param addToBackStack Whether to add current screen to back stack
     */
    fun navigate(
        route: String,
        addToBackStack: Boolean = true,
    ) {
        if (addToBackStack && _currentScreen.value.isNotEmpty()) {
            _navStack.value = _navStack.value + _currentScreen.value
        }
        _currentScreen.value = route
    }

    /**
     * Navigate and clear back stack.
     */
    fun navigateAndClearStack(route: String) {
        _navStack.value = emptyList()
        _currentScreen.value = route
    }

    /**
     * Go back to previous screen.
     * @return The previous screen route, or null if at root
     */
    fun popBack(): String? {
        if (_navStack.value.isEmpty()) {
            return null
        }

        val previousScreen = _navStack.value.last()
        _navStack.value = _navStack.value.dropLast(1)
        _currentScreen.value = previousScreen
        return previousScreen
    }

    /**
     * Go back multiple screens.
     * @param count Number of screens to pop
     */
    fun popBack(count: Int) {
        repeat(count) {
            popBack()
        }
    }

    /**
     * Go back to a specific screen in the stack.
     * @param route The route to go back to
     * @return True if the screen was found and navigated to
     */
    fun popBackTo(route: String): Boolean {
        val index = _navStack.value.lastIndexOf(route)
        if (index == -1) {
            return false
        }

        _navStack.value = _navStack.value.take(index + 1)
        _currentScreen.value = route
        return true
    }

    /**
     * Replace current screen without affecting back stack.
     */
    fun replaceCurrent(route: String) {
        _currentScreen.value = route
    }

    /**
     * Clear navigation stack.
     */
    fun clearStack() {
        _navStack.value = emptyList()
    }

    /**
     * Check if back navigation is possible.
     */
    val canNavigateBack: Boolean
        get() = _navStack.value.isNotEmpty()

    /**
     * Get the size of the back stack.
     */
    val backStackSize: Int
        get() = _navStack.value.size

    /**
     * Navigate with arguments.
     * @param route Base route
     * @param args Arguments to append to route
     */
    fun navigate(
        route: String,
        vararg args: String,
    ): String {
        val fullRoute = buildRoute(route, *args)
        navigate(fullRoute)
        return fullRoute
    }

    /**
     * Build a route with arguments.
     */
    fun buildRoute(
        baseRoute: String,
        vararg args: String,
    ): String {
        return if (args.isEmpty()) {
            baseRoute
        } else {
            "$baseRoute/${args.joinToString("/")}"
        }
    }

    /**
     * Parse route arguments.
     * @param route Full route with arguments
     * @return List of arguments
     */
    fun parseRouteArgs(route: String): List<String> {
        val parts = route.split("/")
        return if (parts.size > 1) parts.drop(1) else emptyList()
    }
}

/**
 * Common navigation routes for consistency.
 */
object Routes {
    // Root
    const val HOME = "home"
    const val STARTUP = "startup"

    // Notes
    const val NOTES_LIST = "notes/list"
    const val NOTES_DETAIL = "notes/detail"
    const val NOTES_ARCHIVE = "notes/archive"
    const val NOTES_CATEGORY = "notes/category"
    const val NOTES_STACKS = "notes/stacks"
    const val NOTES_INPUT_STREAM = "notes/inputstream"

    // Chat
    const val CHAT = "chat"
    const val CHAT_HISTORY = "chat/history"
    const val CHAT_ASSIST = "chat/assist"

    // Calendar
    const val CALENDAR = "calendar"
    const val CALENDAR_EVENT_DETAIL = "calendar/event"

    // Audio
    const val AUDIO_PLAYER = "audio/player"
    const val AUDIO_LIBRARY = "audio/library"

    // Settings
    const val SETTINGS = "settings"
    const val SETTINGS_BACKUP = "settings/backup"
    const val SETTINGS_ABOUT = "settings/about"
    const val SETTINGS_THEME = "settings/theme"

    // Auth
    const val AUTH_LOGIN = "auth/login"
    const val AUTH_REGISTER = "auth/register"

    // Search
    const val SEARCH = "search"
    const val SEARCH_RESULTS = "search/results"

    // Digest
    const val DIGEST = "digest"
    const val DIGEST_DAILY = "digest/daily"
    const val DIGEST_WEEKLY = "digest/weekly"
}
