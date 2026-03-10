package com.example.smarty.data.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Global Loading State Management.
 * 
 * Single Responsibility: Centralized loading state tracking.
 * DRY: Replaces individual isLoading StateFlows in multiple ViewModels.
 * Global State: Shared across all features.
 * 
 * Usage:
 * ```
 * // Set loading state
 * loadingState.setLoading("save_note", true)
 * 
 * // Check if loading
 * val isLoading by loadingState.isLoading("save_note").collectAsState()
 * 
 * // Or use directly
 * if (loadingState.isLoading("save_note")) { ... }
 * ```
 */
class LoadingState {
    
    private val _loadingOperations = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val loadingOperations: StateFlow<Map<String, Boolean>> = _loadingOperations.asStateFlow()
    
    /**
     * Set loading state for an operation.
     * @param operationId Unique identifier for the operation
     * @param isLoading Whether the operation is currently loading
     */
    fun setLoading(operationId: String, isLoading: Boolean) {
        _loadingOperations.value = _loadingOperations.value + (operationId to isLoading)
    }
    
    /**
     * Start a loading operation.
     */
    fun startLoading(operationId: String) {
        setLoading(operationId, true)
    }
    
    /**
     * Stop a loading operation.
     */
    fun stopLoading(operationId: String) {
        setLoading(operationId, false)
    }
    
    /**
     * Check if a specific operation is loading.
     */
    fun isLoading(operationId: String): Boolean {
        return _loadingOperations.value[operationId] == true
    }
    
    /**
     * Get StateFlow for a specific operation's loading state.
     */
    fun isLoadingFlow(operationId: String): StateFlow<Boolean> {
        return LoadingStateFlow(operationId)
    }
    
    /**
     * Check if any operation is loading.
     */
    val isAnyLoading: Boolean
        get() = _loadingOperations.value.any { it.value }
    
    /**
     * Get count of active loading operations.
     */
    val loadingCount: Int
        get() = _loadingOperations.value.count { it.value }
    
    /**
     * Clear all loading states.
     */
    fun clearAll() {
        _loadingOperations.value = emptyMap()
    }
    
    /**
     * Stop all loading operations.
     */
    fun stopAll() {
        _loadingOperations.value = _loadingOperations.value.mapValues { false }
    }
    
    /**
     * Inner class to provide StateFlow for individual operations.
     */
    private inner class LoadingStateFlow(
        private val operationId: String
    ) : StateFlow<Boolean> {
        override val value: Boolean
            get() = this@LoadingState.isLoading(operationId)
        
        override val replayCache: List<Boolean>
            get() = listOf(value)
        
        override suspend fun collect(collector: kotlinx.coroutines.flow.FlowCollector<Boolean>) {
            kotlinx.coroutines.flow.flow { emit(value) }.collect(collector)
        }
    }
}

/**
 * Common operation IDs for consistency.
 */
object LoadingOperations {
    // Notes
    const val SAVE_NOTE = "save_note"
    const val DELETE_NOTE = "delete_note"
    const val UPDATE_NOTE = "update_note"
    const val ARCHIVE_NOTE = "archive_note"
    const val LOAD_NOTES = "load_notes"
    const val SEARCH_NOTES = "search_notes"
    
    // Chat
    const val SEND_MESSAGE = "send_message"
    const val GENERATE_RESPONSE = "generate_response"
    const val LOAD_CHAT_HISTORY = "load_chat_history"
    
    // Calendar
    const val ADD_EVENT = "add_event"
    const val DELETE_EVENT = "delete_event"
    const val LOAD_EVENTS = "load_events"
    
    // Audio
    const val PLAY_AUDIO = "play_audio"
    const val RECORD_AUDIO = "record_audio"
    const val LOAD_AUDIO = "load_audio"
    
    // Settings
    const val SAVE_SETTINGS = "save_settings"
    const val BACKUP_DATA = "backup_data"
    const val RESTORE_DATA = "restore_data"
    
    // Auth
    const val LOGIN = "login"
    const val LOGOUT = "logout"
    const val REGISTER = "register"
    
    // Sync
    const val SYNC_DATA = "sync_data"
    const val UPLOAD_DATA = "upload_data"
    const val DOWNLOAD_DATA = "download_data"
}
