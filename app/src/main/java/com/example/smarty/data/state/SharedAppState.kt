package com.example.smarty.data.state

import com.example.smarty.ui.components.ConnectionStatus
import com.example.smarty.core.domain.model.NavigationTab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton holder for shared app state to decouple ViewModels.
 * Now includes global error, loading, and navigation state.
 * 
 * Principles:
 * - Single source of truth for cross-feature state
 * - Separated concerns (domain state vs UI state)
 * - Immutable state flows
 */
class SharedAppState {
    private val _currentScreen = MutableStateFlow("startup")
    val currentScreen: StateFlow<String> = _currentScreen.asStateFlow()

    private val _activeNoteId = MutableStateFlow<String?>(null)
    val activeNoteId: StateFlow<String?> = _activeNoteId.asStateFlow()

    private val _isDarkTheme = MutableStateFlow(false)
    val isDarkTheme: StateFlow<Boolean> = _isDarkTheme.asStateFlow()

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.CONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _navigationRequest = MutableStateFlow<String?>(null)
    val navigationRequest: StateFlow<String?> = _navigationRequest.asStateFlow()

    private val _cacheSizeBytes = MutableStateFlow(0L)
    val cacheSizeBytes: StateFlow<Long> = _cacheSizeBytes.asStateFlow()

    private val _selectedTab = MutableStateFlow<NavigationTab>(NavigationTab.NOTES)
    val selectedTab: StateFlow<NavigationTab> = _selectedTab.asStateFlow()
    
    // Global state managers - Single Responsibility for cross-cutting concerns
    val errorState = GlobalErrorState()
    val loadingState = LoadingState()
    val navigationState = NavigationState()

    fun setCurrentScreen(screen: String) {
        _currentScreen.value = screen
    }

    fun setActiveNoteId(id: String?) {
        _activeNoteId.value = id
    }

    fun setDarkTheme(isDark: Boolean) {
        _isDarkTheme.value = isDark
    }

    fun setConnectionStatus(status: ConnectionStatus) {
        _connectionStatus.value = status
    }

    fun setNavigationRequest(screen: String?) {
        _navigationRequest.value = screen
    }

    fun setCacheSizeBytes(size: Long) {
        _cacheSizeBytes.value = size
    }

    fun setSelectedTab(tab: NavigationTab) {
        _selectedTab.value = tab
    }
}
