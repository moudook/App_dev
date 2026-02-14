package com.example.smarty.data.state

import com.example.smarty.ui.components.ConnectionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton holder for shared app state to decouple ViewModels.
 * Used by ChatFeatureManager and other components that need global context.
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
}

