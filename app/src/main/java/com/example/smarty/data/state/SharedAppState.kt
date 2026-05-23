package com.example.smarty.data.state

import com.example.smarty.core.domain.model.NavigationTab
import com.example.smarty.core.domain.model.Note
import com.example.smarty.ui.components.ConnectionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    // Cross-cutting concerns
    val errorState = GlobalErrorState()
    val loadingState = LoadingState()
    val navigationState = NavigationState()

    // Engagement state
    private val _noteStreak = MutableStateFlow(0)
    val noteStreak: StateFlow<Int> = _noteStreak.asStateFlow()

    private val _noteOfTheDay = MutableStateFlow<Note?>(null)
    val noteOfTheDay: StateFlow<Note?> = _noteOfTheDay.asStateFlow()

    private val _smartSuggestions = MutableStateFlow<List<Note>>(emptyList())
    val smartSuggestions: StateFlow<List<Note>> = _smartSuggestions.asStateFlow()

    fun setNoteStreak(count: Int) {
        _noteStreak.value = count
    }

    fun setNoteOfTheDay(note: Note?) {
        _noteOfTheDay.value = note
    }

    fun setSmartSuggestions(notes: List<Note>) {
        _smartSuggestions.value = notes
    }

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
