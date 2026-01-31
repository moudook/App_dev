package com.example.smarty.navigation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.smarty.data.local.AIProvider
import com.example.smarty.data.local.AIProviderConfig
import com.example.smarty.data.model.Attachment
import com.example.smarty.ui.components.AttachmentOption
import com.example.smarty.ui.components.ConnectionStatus
import com.example.smarty.data.model.AudioTrack
import com.example.smarty.data.model.CalendarEvent
import com.example.smarty.data.model.Category
import com.example.smarty.data.model.ChatMessage
import com.example.smarty.data.model.ChatSession
import com.example.smarty.data.model.MentionState
import com.example.smarty.data.model.MentionSuggestion
import com.example.smarty.data.model.Note
import com.example.smarty.data.model.NoteAttachment
import com.example.smarty.data.model.NoteVersion
import com.example.smarty.data.model.SmartyTimer
import com.example.smarty.data.model.TodoItem
import com.example.smarty.ui.components.PendingShareData
import com.example.smarty.ui.screens.*
import com.example.smarty.viewmodel.BackupViewModel
import com.example.smarty.util.api.KeyUsageStats

/**
 * BUG-055 fix: Safe popBackStack that prevents crashes on empty back stack
 */
private fun NavHostController.safePopBackStack(): Boolean {
    return try {
        if (previousBackStackEntry != null) {
            popBackStack()
        } else {
            false
        }
    } catch (e: Exception) {
        android.util.Log.w("SmartyNavigation", "Safe pop failed: ${e.message}")
        false
    }
}

sealed class Screen(val route: String) {
    data object InputStream : Screen("input_stream")
    data object Stacks : Screen("stacks")
    data object CategoryNotes : Screen("category_notes")
    data object KnowledgeCard : Screen("knowledge_card")
    data object Settings : Screen("settings")
    data object Archive : Screen("archive")
    data object BackupSettings : Screen("backup_settings")
    data object Calendar : Screen("calendar")
    data object Login : Screen("login")
}

@Composable
fun SmartyNavHost(
    navController: NavHostController = rememberNavController(),
    notes: List<Note>,
    archivedNotes: List<Note>,
    categories: List<Category>,
    selectedNote: Note?,
    selectedCategory: Category?,
    isProcessing: Boolean,
    // API Key management
    providerConfigs: Map<AIProvider, AIProviderConfig>,
    providerPriorityOrder: List<AIProvider>,
    onAddApiKey: (AIProvider, String) -> Unit,
    onRemoveApiKey: (AIProvider, String) -> Unit,
    onUpdateApiKey: (AIProvider, String, String) -> Unit,
    onSetProviderEnabled: (AIProvider, Boolean) -> Unit,
    onSetSelectedModel: (AIProvider, String) -> Unit,
    onSetProviderPriority: (List<AIProvider>) -> Unit,
    onTestApiKey: (AIProvider, String, (Boolean) -> Unit) -> Unit,
    // Notes management
    onAddNote: (String, List<Attachment>) -> Unit,
    onSelectNote: (Note?) -> Unit,
    onSelectCategory: (Category?) -> Unit,
    onCreateCategory: (String) -> Unit,
    onRenameCategory: (Category, String) -> Unit = { _, _ -> },
    onDeleteCategory: (Category) -> Unit,
    onSyncCategoryCounts: () -> Unit = {},  // Sync category counts when entering stacks view
    onArchiveNote: (String) -> Unit,
    onUnarchiveNote: (String) -> Unit,
    onBulkArchive: (List<String>) -> Unit = {},
    onUndoArchive: () -> Unit = {},
    onRefreshNotes: () -> Unit = {},
    isRefreshing: Boolean = false,
    isNotesLoading: Boolean = false,
    isStacksLoading: Boolean = false,
    isArchiveLoading: Boolean = false,
    isChatHistoryLoading: Boolean = false,
    isCalendarLoading: Boolean = false,
    onDeleteNote: (Note) -> Unit,
    onDeleteNoteById: (String) -> Unit,
    onUpdateNoteTodos: (String, List<TodoItem>, onComplete: (() -> Unit)?) -> Unit,
    onEditNote: (String, String, String, String?, String?, List<NoteAttachment>) -> Unit = { _, _, _, _, _, _ -> },  // noteId, newTitle, newContent, newSummary, newWhySaved, newAttachments
    onMarkAsViewed: (String) -> Unit = {}, // New tracking action
    // Version history
    selectedNoteVersions: List<NoteVersion> = emptyList(),
    onLoadNoteVersions: (String) -> Unit = {},  // noteId
    onRestoreNoteVersion: (String, String) -> Unit = { _, _ -> },  // noteId, versionId
    // Pin and share management
    onPinNote: (String) -> Unit = {},
    onUnpinNote: (String) -> Unit = {},
    onShareNotes: (List<Note>) -> Unit = {},
    // Pending share management
    pendingShare: PendingShareData?,
    onConfirmShare: (String?, String) -> Unit,
    onCancelShare: () -> Unit,
    isShareFullPrivacy: Boolean = false,
    // Chat mode management
    isChatMode: Boolean = false,
    chatMessages: List<ChatMessage> = emptyList(),
    isChatProcessing: Boolean = false,
    onSendChatMessage: (String, List<Attachment>) -> Unit = { _, _ -> },
    onExitChatMode: () -> Unit = {},  // Back button handler for chat mode
    onEnterChatMode: () -> Unit = {},  // Enter chat mode when AI tab is clicked
    onEnterChatWithNoteReference: (String) -> Unit = {},  // @Mention: Enter chat with note pre-referenced
    // AI Planning Status
    aiPlanStatus: String? = null,
    currentToolName: String? = null,
    // Thinking mode toggle (Chat mode only - for reasoning models like Falcon-H1R-7B)
    isThinkingModeEnabled: Boolean = false,
    onToggleThinkingMode: () -> Unit = {},
    // Chat history management
    chatSessions: List<ChatSession> = emptyList(),
    currentSessionId: String? = null,
    onSwitchChatSession: (String) -> Unit = {},
    onNewChatSession: () -> Unit = {},
    onDeleteChatSession: (String) -> Unit = {},
    // @Mention parameters (Chat mode)
    mentionState: MentionState = MentionState(),
    onMentionSelected: (MentionSuggestion, String) -> String = { _, text -> text },
    // Pending chat text (for "Ask AI" from note card)
    pendingChatText: String? = null,
    onClearPendingChatText: () -> Unit = {},
    // Mention state update callback
    onUpdateMentionState: (String, Int) -> Unit = { _, _ -> },
    // AI exclusion management
    isAiExcluded: Boolean = false,
    onInputTextChange: (String) -> Unit = {},
    onInputAttachmentsChange: (List<Attachment>) -> Unit = {},
    // Search and Filter (Backend Integration)
    searchQuery: String = "",
    onSearchQueryChange: (String) -> Unit = {},
    selectedFilters: Set<AttachmentOption> = emptySet(),
    onFilterToggle: (AttachmentOption) -> Unit = {},
    onClearFilters: () -> Unit = {},
    // Search History (BATCH 5C)
    recentSearches: List<String> = emptyList(),
    onRecordSearch: (String) -> Unit = {},
    onClearSearchHistory: () -> Unit = {},
    // Audio player for attachments
    onPlayAudio: (AudioTrack) -> Unit = {},
    isMiniPlayerVisible: Boolean = false,  // Audio player visibility for gradient adjustment
    // Theme management
    isDarkTheme: Boolean,
    onToggleTheme: (Boolean) -> Unit,
    // Cache management
    cacheSizeBytes: Long = 0L,
    onClearCache: () -> Unit = {},
    isClearingCache: Boolean = false,
    // Tavily Web Search API
    tavilyApiKeys: List<String> = emptyList(),
    onAddTavilyApiKey: (String) -> Unit = {},
    onRemoveTavilyApiKey: (String) -> Unit = {},
    // Shake sensitivity
    shakeSensitivity: Float = 0.63f,
    onShakeSensitivityChange: (Float) -> Unit = {},
    // GROQ key usage stats
    groqKeyUsageStats: List<KeyUsageStats> = emptyList(),
    // Shake mode switch animation
    wasShakeTriggered: Boolean = false,
    // Network status (Phase 7)
    connectionStatus: ConnectionStatus = ConnectionStatus.CONNECTED,
    // Camera trigger from widget
    cameraTriggered: Boolean = false,
    onClearCameraTrigger: () -> Unit = {},
    // Calendar management
    calendarEvents: List<CalendarEvent> = emptyList(),
    activeTimers: List<com.example.smarty.data.model.SmartyTimer> = emptyList(),
    onAddCalendarEvent: (
        title: String,
        description: String?,
        startTime: Long,
        endTime: Long,
        isAllDay: Boolean,
        location: String?,
        color: Int?,
        reminderMinutes: Int?,
        isPrivate: Boolean
    ) -> Unit = { _, _, _, _, _, _, _, _, _ -> },
    onUpdateCalendarEvent: (CalendarEvent) -> Unit = {},
    onDeleteCalendarEvent: (String) -> Unit = {},
    onCancelTimer: (SmartyTimer) -> Unit = {},
    bottomContentPadding: androidx.compose.ui.unit.Dp = 0.dp,
    externalSpeechState: com.example.smarty.util.SpeechToTextState? = null,
    speechResults: kotlinx.coroutines.flow.Flow<String>? = null,

    // Dynamic Models
    onRefreshModels: (AIProvider) -> Unit = {},
    getAvailableModels: (AIProvider) -> List<Pair<String, String>> = { com.example.smarty.data.local.AIModels.getModelsForProvider(it) },
    // Screen change callback for shake detection
    onScreenChange: (String) -> Unit = {},
    // Local LLM Server (USB/WiFi)
    localServerIP: String = "",
    localServerPort: String = "1234",
    localServerUseHttps: Boolean = false,
    onSetLocalServerIP: (String) -> Unit = {},
    onSetLocalServerPort: (String) -> Unit = {},
    onSetLocalServerUseHttps: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
    // Auth State
    isLoggedIn: Boolean = false,
    onSignOut: () -> Unit = {},
    // AI Memory
    aiMemories: List<com.example.smarty.data.model.AIMemory> = emptyList(),
    onDeleteAIMemory: (com.example.smarty.data.model.AIMemory) -> Unit = {},
    onClearAllAIMemories: () -> Unit = {},
    // Memory sync
    onSyncAIMemories: () -> Unit = {},
    isMemorySyncInProgress: Boolean = false,
    memorySyncResult: String? = null,
    unreadForMemoryCount: Int = 0,
    onClearMemorySyncResult: () -> Unit = {},
    // Google Calendar Two-Way Sync
    isCalendarSyncEnabled: Boolean = false,
    onSetCalendarSyncEnabled: (Boolean) -> Unit = {},
    deviceCalendars: List<com.example.smarty.calendar.GoogleCalendarSyncManager.DeviceCalendar> = emptyList(),
    targetCalendarId: Long = -1L,
    onSetTargetCalendarId: (Long) -> Unit = {},
    onLoadDeviceCalendars: () -> Unit = {},
    // AI Navigation
    navigationRequest: String? = null,
    onClearNavigationRequest: () -> Unit = {}
) {
    // NOTE: Login is now handled in MainActivity BEFORE SmartyNavHost is rendered
    // When we get here, user is ALWAYS logged in
    val startDestination = Screen.InputStream.route

    // Handle AI-triggered navigation requests
    androidx.compose.runtime.LaunchedEffect(navigationRequest) {
        navigationRequest?.let { target ->
            val route = when (target.lowercase()) {
                "input_stream", "main", "home" -> Screen.InputStream.route
                "stacks", "categories" -> Screen.Stacks.route
                "settings", "config" -> Screen.Settings.route
                "calendar", "events" -> Screen.Calendar.route
                "archive" -> Screen.Archive.route
                "backup" -> Screen.BackupSettings.route
                else -> null
            }

            route?.let {
                android.util.Log.i("SmartyNavigation", "AI navigating to: $it")
                navController.navigate(it)
                onClearNavigationRequest()
            }
        }
    }

    // Track navigation changes and notify ViewModel
    androidx.compose.runtime.LaunchedEffect(navController) {
        navController.currentBackStackEntryFlow.collect { backStackEntry ->
            val route = backStackEntry.destination.route ?: "unknown"
            onScreenChange(route)
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier
    ) {
        composable(Screen.InputStream.route) {
            InputStreamScreen(
                notes = notes,
                categories = categories,
                isProcessing = isProcessing,
                onAddNote = onAddNote,
                onNoteClick = { note ->
                    onSelectNote(note)
                    navController.navigate(Screen.KnowledgeCard.route)
                },
                onDeleteNote = onDeleteNoteById,
                onArchiveNote = onArchiveNote,
                onUnarchiveNote = onUnarchiveNote,
                onBulkArchive = onBulkArchive,
                onUndoArchive = onUndoArchive,
                onRefreshNotes = onRefreshNotes,
                isRefreshing = isRefreshing,
                isNotesLoading = isNotesLoading,
                isChatHistoryLoading = isChatHistoryLoading,
                onUpdateNoteTodos = onUpdateNoteTodos,
                onNavigateToStacks = {
                    navController.navigate(Screen.Stacks.route) {
                        popUpTo(Screen.InputStream.route) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route) {
                        popUpTo(Screen.InputStream.route) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onNavigateToCalendar = {
                    navController.navigate(Screen.Calendar.route) {
                        popUpTo(Screen.InputStream.route) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                pendingShare = pendingShare,
                onConfirmShare = onConfirmShare,
                onCancelShare = onCancelShare,
                isShareFullPrivacy = isShareFullPrivacy,
                // Chat mode
                isChatMode = isChatMode,
                chatMessages = chatMessages,
                isChatProcessing = isChatProcessing,
                onSendChatMessage = onSendChatMessage,
                onExitChatMode = onExitChatMode,
                onEnterChatMode = onEnterChatMode,
                // Chat history
                chatSessions = chatSessions,
                currentSessionId = currentSessionId,
                onSwitchChatSession = onSwitchChatSession,
                onNewChatSession = onNewChatSession,
                onDeleteChatSession = onDeleteChatSession,
                // @Mention autocomplete
                mentionState = mentionState,
                onMentionSelected = onMentionSelected,
                // AI Planning Status
                aiPlanStatus = aiPlanStatus,
                currentToolName = currentToolName,
                // Thinking mode toggle (Chat mode only)
                isThinkingModeEnabled = isThinkingModeEnabled,
                onToggleThinkingMode = onToggleThinkingMode,
                // Pending chat text (for "Ask AI" from note card)
                pendingChatText = pendingChatText,
                onClearPendingChatText = onClearPendingChatText,
                // Mention state update
                onUpdateMentionState = onUpdateMentionState,
                // AI exclusion
                isAiExcluded = isAiExcluded,
                onInputTextChange = onInputTextChange,
                onInputAttachmentsChange = onInputAttachmentsChange,
                onPlayYouTube = {},
                // Search and Filter
                searchQuery = searchQuery,
                onSearchQueryChange = onSearchQueryChange,
                selectedFilters = selectedFilters,
                onFilterToggle = onFilterToggle,
                onClearFilters = onClearFilters,
                // Search History (BATCH 5C)
                recentSearches = recentSearches,
                onRecordSearch = onRecordSearch,
                onClearSearchHistory = onClearSearchHistory,
                bottomContentPadding = bottomContentPadding,
                isMiniPlayerVisible = isMiniPlayerVisible,
                externalSpeechState = externalSpeechState,
                speechResults = speechResults,
                wasShakeTriggered = wasShakeTriggered,
                connectionStatus = connectionStatus,
                // Camera trigger from widget
                cameraTriggered = cameraTriggered,
                onClearCameraTrigger = onClearCameraTrigger,
                // Pin and Share
                onPinNote = onPinNote,
                onUnpinNote = onUnpinNote,
                onShareNotes = onShareNotes,
                // ═══════════════════════════════════════════════════════════════════
                // CENTRALIZED UI: All features accessible from main screen
                // ═══════════════════════════════════════════════════════════════════

                // Theme toggle
                isDarkTheme = isDarkTheme,
                onToggleTheme = onToggleTheme,

                // Archive
                archivedNotes = archivedNotes,

                // Calendar
                calendarEvents = calendarEvents,
                activeTimers = activeTimers,
                // FIX: onAddCalendarEvent should be a no-op - actual creation goes through onCreateCalendarEvent
                // The previous implementation incorrectly created empty events immediately
                onAddCalendarEvent = { /* No-op - dialog shown internally by CalendarSheet/CalendarContent */ },
                onCreateCalendarEvent = { title, description, startTime, endTime, isAllDay ->
                    onAddCalendarEvent(title, description, startTime, endTime, isAllDay, null, null, null, false)
                },
                onEventClick = { event ->
                    // Event click handling - could open edit sheet
                },
                onDeleteCalendarEvent = { event -> onDeleteCalendarEvent(event.id) },

                // Categories/Stacks
                onCreateCategory = onCreateCategory,
                onDeleteCategory = onDeleteCategory,
                onRenameCategory = onRenameCategory,
                onSyncCategoryCounts = onSyncCategoryCounts,
                onCategoryClick = { category ->
                    onSelectCategory(category)
                    navController.navigate(Screen.CategoryNotes.route)
                },

                // Settings props
                providerConfigs = providerConfigs,
                providerPriorityOrder = providerPriorityOrder,
                onAddApiKey = onAddApiKey,
                onRemoveApiKey = onRemoveApiKey,
                onUpdateApiKey = onUpdateApiKey,
                onSetProviderEnabled = onSetProviderEnabled,
                onSetSelectedModel = onSetSelectedModel,
                onSetProviderPriority = onSetProviderPriority,
                onTestApiKey = onTestApiKey,
                tavilyApiKeys = tavilyApiKeys,
                onAddTavilyApiKey = onAddTavilyApiKey,
                onRemoveTavilyApiKey = onRemoveTavilyApiKey,
                cacheSizeBytes = cacheSizeBytes,
                onClearCache = onClearCache,
                isClearingCache = isClearingCache,
                shakeSensitivity = shakeSensitivity,
                onShakeSensitivityChange = onShakeSensitivityChange,
                groqKeyUsageStats = groqKeyUsageStats,
                onRefreshModels = onRefreshModels,
                getAvailableModels = getAvailableModels,
                onSignOut = onSignOut,
                // Settings sub-sheet content
                aiConfigContent = @androidx.compose.runtime.Composable { onDismiss ->
                    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
                    val aiConfigSheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
                    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
                    AIConfigBottomSheet(
                        sheetState = aiConfigSheetState,
                        providerConfigs = providerConfigs,
                        providerPriorityOrder = providerPriorityOrder,
                        onDismiss = onDismiss,
                        onAddApiKey = onAddApiKey,
                        onRemoveApiKey = onRemoveApiKey,
                        onUpdateApiKey = onUpdateApiKey,
                        onSetProviderEnabled = onSetProviderEnabled,
                        onSetSelectedModel = onSetSelectedModel,
                        onSetProviderPriority = onSetProviderPriority,
                        onTestApiKey = onTestApiKey,
                        tavilyApiKeys = tavilyApiKeys,
                        onAddTavilyApiKey = onAddTavilyApiKey,
                        onRemoveTavilyApiKey = onRemoveTavilyApiKey,
                        groqKeyUsageStats = groqKeyUsageStats,
                        onRefreshModels = onRefreshModels,
                        getAvailableModels = getAvailableModels,
                        localServerIP = localServerIP,
                        localServerPort = localServerPort,
                        localServerUseHttps = localServerUseHttps,
                        onSetLocalServerIP = onSetLocalServerIP,
                        onSetLocalServerPort = onSetLocalServerPort,
                        onSetLocalServerUseHttps = onSetLocalServerUseHttps
                    )
                },
                archiveContentForSettings = { onDismiss ->
                    ArchiveScreen(
                        archivedNotes = archivedNotes,
                        isLoading = isArchiveLoading,
                        onBackClick = onDismiss,
                        onDeleteNote = onDeleteNoteById,
                        onUnarchiveNote = onUnarchiveNote,
                        isEmbedded = true
                    )
                },
                backupContent = { onDismiss ->
                    BackupSettingsRoute(
                        onBackClick = onDismiss,
                        isEmbedded = true
                    )
                },
                // AI Memory
                aiMemories = aiMemories,
                onDeleteAIMemory = onDeleteAIMemory,
                onClearAllAIMemories = onClearAllAIMemories,
                onSyncAIMemories = onSyncAIMemories,
                isMemorySyncInProgress = isMemorySyncInProgress,
                memorySyncResult = memorySyncResult,
                unreadForMemoryCount = unreadForMemoryCount,
                onClearMemorySyncResult = onClearMemorySyncResult
            )
        }

        composable(Screen.Stacks.route) {
            StacksScreen(
                categories = categories,
                isLoading = isStacksLoading,
                onCategoryClick = { category ->
                    onSelectCategory(category)
                    navController.navigate(Screen.CategoryNotes.route)
                },
                onBackClick = {
                    navController.safePopBackStack()
                },
                onCreateCategory = onCreateCategory,
                onRenameCategory = onRenameCategory,
                onDeleteCategory = onDeleteCategory,
                bottomContentPadding = bottomContentPadding
            )
        }

        composable(Screen.CategoryNotes.route) {
            selectedCategory?.let { category ->
                CategoryNotesScreen(
                    category = category,
                    notes = notes,
                    isLoading = isNotesLoading,
                    onBackClick = {
                        onSelectCategory(null) // Clear the selected category to clear the filter
                        navController.safePopBackStack()
                    },
                    onNoteClick = { note ->
                        onSelectNote(note)
                        navController.navigate(Screen.KnowledgeCard.route)
                    },
                    onArchiveNote = onArchiveNote,
                    bottomContentPadding = bottomContentPadding
                )
            }
        }

        composable(Screen.KnowledgeCard.route) {
            selectedNote?.let { note ->
                KnowledgeCardScreen(
                    note = note,
                    onBackClick = {
                        navController.safePopBackStack()
                    },
                    onArchiveClick = {
                        onArchiveNote(note.id)
                        navController.safePopBackStack()
                    },
                    onDeleteClick = {
                        onDeleteNote(note)
                        navController.safePopBackStack()
                    },
                    onEditNote = onEditNote,
                    onPlayAudio = onPlayAudio,
                    onMarkAsViewed = { onMarkAsViewed(note.id) },
                    // Version history
                    noteVersions = selectedNoteVersions,
                    onLoadVersions = { onLoadNoteVersions(note.id) },
                    onRestoreVersion = { versionId -> onRestoreNoteVersion(note.id, versionId) },
                    bottomContentPadding = bottomContentPadding,
                    isMiniPlayerVisible = isMiniPlayerVisible,
                    // @Mention: Ask AI about this note
                    onAskSmarty = {
                        onEnterChatWithNoteReference(note.title)
                        navController.safePopBackStack()
                    }
                )
            }
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                providerConfigs = providerConfigs,
                providerPriorityOrder = providerPriorityOrder,
                isDarkTheme = isDarkTheme,
                onBackClick = {
                    navController.safePopBackStack()
                },
                onAddApiKey = onAddApiKey,
                onRemoveApiKey = onRemoveApiKey,
                onUpdateApiKey = onUpdateApiKey,
                onSetProviderEnabled = onSetProviderEnabled,
                onSetSelectedModel = onSetSelectedModel,
                onSetProviderPriority = onSetProviderPriority,
                onTestApiKey = onTestApiKey,
                onToggleTheme = onToggleTheme,
                // Tavily Web Search API
                tavilyApiKeys = tavilyApiKeys,
                onAddTavilyApiKey = onAddTavilyApiKey,
                onRemoveTavilyApiKey = onRemoveTavilyApiKey,
                // Cache management
                cacheSizeBytes = cacheSizeBytes,
                onClearCache = onClearCache,
                isClearingCache = isClearingCache,
                // Shake sensitivity
                shakeSensitivity = shakeSensitivity,
                onShakeSensitivityChange = onShakeSensitivityChange,
                // GROQ key usage stats
                groqKeyUsageStats = groqKeyUsageStats,
                // Local LLM Server (USB/WiFi)
                localServerIP = localServerIP,
                localServerPort = localServerPort,
                localServerUseHttps = localServerUseHttps,
                onSetLocalServerIP = onSetLocalServerIP,
                onSetLocalServerPort = onSetLocalServerPort,
                onSetLocalServerUseHttps = onSetLocalServerUseHttps,
                // Dynamic Models
                onRefreshModels = onRefreshModels,
                getAvailableModels = getAvailableModels,
                // Embedded Sheets
                archiveContent = { onDismiss ->
                    ArchiveScreen(
                        archivedNotes = archivedNotes,
                        onBackClick = onDismiss, // Dismiss sheet on back
                        onDeleteNote = onDeleteNoteById,
                        onUnarchiveNote = onUnarchiveNote,
                        isEmbedded = true
                    )
                },
                backupContent = { onDismiss ->
                    BackupSettingsRoute(
                        onBackClick = onDismiss,
                        isEmbedded = true
                    )
                },
                // AI Memory
                aiMemories = aiMemories,
                onDeleteAIMemory = onDeleteAIMemory,
                onClearAllAIMemories = onClearAllAIMemories,
                // Memory sync
                onSyncAIMemories = onSyncAIMemories,
                isMemorySyncInProgress = isMemorySyncInProgress,
                memorySyncResult = memorySyncResult,
                unreadForMemoryCount = unreadForMemoryCount,
                onClearMemorySyncResult = onClearMemorySyncResult,
                // Google Calendar Two-Way Sync
                isCalendarSyncEnabled = isCalendarSyncEnabled,
                onSetCalendarSyncEnabled = onSetCalendarSyncEnabled,
                deviceCalendars = deviceCalendars,
                targetCalendarId = targetCalendarId,
                onSetTargetCalendarId = onSetTargetCalendarId,
                onLoadDeviceCalendars = onLoadDeviceCalendars,
                onSignOut = onSignOut
            )
        }


        composable(Screen.Archive.route) {
            ArchiveScreen(
                archivedNotes = archivedNotes,
                isLoading = isArchiveLoading,
                onBackClick = {
                    navController.safePopBackStack()
                },
                onDeleteNote = onDeleteNoteById,
                onUnarchiveNote = onUnarchiveNote
            )
        }

        composable(Screen.BackupSettings.route) {
            BackupSettingsRoute(
                onBackClick = {
                    navController.safePopBackStack()
                }
            )
        }

        composable(Screen.Calendar.route) {
            CalendarRoute(
                calendarEvents = calendarEvents,
                activeTimers = activeTimers,
                isLoading = isCalendarLoading,
                onBackClick = { navController.safePopBackStack() },
                onAddCalendarEvent = onAddCalendarEvent,
                onUpdateCalendarEvent = onUpdateCalendarEvent,
                onDeleteCalendarEvent = onDeleteCalendarEvent,
                onCancelTimer = onCancelTimer
            )
        }

        composable(Screen.Login.route) {
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate(Screen.InputStream.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                }
            )
        }
    }
}

/**
 * Wrapper composable that provides BackupViewModel and handles Google Sign-In result.
 */
@Composable
fun BackupSettingsRoute(
    onBackClick: () -> Unit,
    isEmbedded: Boolean = false,
    viewModel: BackupViewModel = viewModel()
) {
    val isSignedIn by viewModel.isSignedIn.collectAsState()
    val backupState by viewModel.backupState.collectAsState()
    val restoreState by viewModel.restoreState.collectAsState()
    val availableBackups by viewModel.availableBackups.collectAsState()
    val isLoadingCloudBackups by viewModel.isLoadingBackups.collectAsState()
    val lastBackupTime by viewModel.lastBackupTime.collectAsState()
    val autoBackupEnabled by viewModel.autoBackupEnabled.collectAsState()
    val autoBackupIntervalDays by viewModel.autoBackupIntervalDays.collectAsState()

    // Local backup state
    val localBackupState by viewModel.localBackupState.collectAsState()
    val localBackups by viewModel.localBackups.collectAsState()
    val isLoadingLocalBackups by viewModel.isLoadingLocalBackups.collectAsState()

    // Activity result launcher for Google Sign-In
    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.handleSignInResult(result.data)
    }

    // Refresh backup list when screen appears
    LaunchedEffect(isSignedIn) {
        if (isSignedIn) {
            viewModel.loadAvailableBackups()
        }
    }

    // Refresh local backups when screen appears
    LaunchedEffect(Unit) {
        viewModel.loadLocalBackups()
    }

    BackupSettingsScreen(
        isSignedIn = isSignedIn,
        signedInEmail = viewModel.signedInEmail,
        signedInDisplayName = viewModel.signedInDisplayName,
        signedInPhotoUrl = viewModel.signedInPhotoUrl,
        backupState = backupState,
        restoreState = restoreState,
        availableBackups = availableBackups,
        isLoadingCloudBackups = isLoadingCloudBackups,
        lastBackupTime = lastBackupTime,
        autoBackupEnabled = autoBackupEnabled,
        autoBackupIntervalDays = autoBackupIntervalDays,
        localBackupState = localBackupState,
        localBackups = localBackups,
        isLoadingLocalBackups = isLoadingLocalBackups,
        onBackClick = onBackClick,
        onSignIn = {
            signInLauncher.launch(viewModel.getSignInIntent())
        },
        onSignOut = {
            viewModel.signOut()
        },
        onCreateBackup = {
            viewModel.createBackup()
        },
        onRestoreBackup = { metadata ->
            viewModel.restoreBackup(metadata)
        },
        onDeleteBackup = { metadata ->
            viewModel.deleteBackup(metadata)
        },
        onSetAutoBackupEnabled = { enabled ->
            viewModel.setAutoBackupEnabled(enabled)
        },
        onSetAutoBackupInterval = { days ->
            viewModel.setAutoBackupIntervalDays(days)
        },
        onResetBackupState = {
            viewModel.resetBackupState()
        },
        onResetRestoreState = {
            viewModel.resetRestoreState()
        },
        onCreateLocalBackup = {
            viewModel.createLocalBackup()
        },
        onDeleteLocalBackup = { metadata ->
            viewModel.deleteLocalBackup(metadata)
        },
        onShareLocalBackup = { metadata ->
            viewModel.getLocalBackupShareIntent(metadata)
        },
        onResetLocalBackupState = {
            viewModel.resetLocalBackupState()
        },
        isEmbedded = isEmbedded
    )
}

/**
 * Calendar screen wrapper with proper experimental API annotation for ModalBottomSheet.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun CalendarRoute(
    calendarEvents: List<CalendarEvent>,
    activeTimers: List<com.example.smarty.data.model.SmartyTimer> = emptyList(),
    onBackClick: () -> Unit,
    onAddCalendarEvent: (
        title: String,
        description: String?,
        startTime: Long,
        endTime: Long,
        isAllDay: Boolean,
        location: String?,
        color: Int?,
        reminderMinutes: Int?,
        isPrivate: Boolean
    ) -> Unit,
    onUpdateCalendarEvent: (CalendarEvent) -> Unit,
    onDeleteCalendarEvent: (String) -> Unit,
    onCancelTimer: (SmartyTimer) -> Unit = {},
    isLoading: Boolean = false
) {
    var showAddSheet by remember { mutableStateOf(false) }
    var selectedEventForEdit by remember { mutableStateOf<CalendarEvent?>(null) }
    var selectedDateForNewEvent by remember { mutableStateOf<java.util.Calendar?>(null) }

    CalendarScreen(
        events = calendarEvents,
        activeTimers = activeTimers,
        isLoading = isLoading,
        onBackClick = onBackClick,
        onAddEvent = { selectedDate ->
            selectedDateForNewEvent = selectedDate
            showAddSheet = true
        },
        onEventClick = { event ->
            selectedEventForEdit = event
        },
        onDeleteEvent = { event ->
            onDeleteCalendarEvent(event.id)
        },
        onCancelTimer = { timer ->
            onCancelTimer(timer)
        }
    )

    // Add Event Sheet
    if (showAddSheet) {
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { showAddSheet = false },
            sheetState = androidx.compose.material3.rememberModalBottomSheetState(
                skipPartiallyExpanded = true
            ),
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            dragHandle = null
        ) {
            com.example.smarty.ui.components.AddEventSheet(
                onDismiss = {
                    showAddSheet = false
                    selectedDateForNewEvent = null
                },
                onConfirm = { title, description, startTime, endTime, isAllDay, location, color, reminderMinutes, isPrivate ->
                    onAddCalendarEvent(title, description, startTime, endTime, isAllDay, location, color, reminderMinutes, isPrivate)
                    showAddSheet = false
                    selectedDateForNewEvent = null
                },
                initialDate = selectedDateForNewEvent ?: java.util.Calendar.getInstance()
            )
        }
    }

    // Edit Event Sheet
    selectedEventForEdit?.let { event ->
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { selectedEventForEdit = null },
            sheetState = androidx.compose.material3.rememberModalBottomSheetState(
                skipPartiallyExpanded = true
            ),
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            dragHandle = null
        ) {
            com.example.smarty.ui.components.EditEventSheet(
                event = event,
                onDismiss = { selectedEventForEdit = null },
                onSave = { updatedEvent ->
                    onUpdateCalendarEvent(updatedEvent)
                    selectedEventForEdit = null
                },
                onDelete = {
                    onDeleteCalendarEvent(event.id)
                    selectedEventForEdit = null
                }
            )
        }
    }
}
