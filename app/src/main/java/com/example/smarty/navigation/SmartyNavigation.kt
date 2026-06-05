package com.example.smarty.navigation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.smarty.core.domain.model.Attachment
import com.example.smarty.core.domain.model.AudioPlayerUiState
import com.example.smarty.core.domain.model.AudioTrack
import com.example.smarty.core.domain.model.CalendarEvent
import com.example.smarty.core.domain.model.Category
import com.example.smarty.core.domain.model.ChatMessage
import com.example.smarty.core.domain.model.ChatSession
import com.example.smarty.core.domain.model.MentionState
import com.example.smarty.core.domain.model.MentionSuggestion
import com.example.smarty.core.domain.model.Note
import com.example.smarty.core.domain.model.NoteAttachment
import com.example.smarty.core.domain.model.NoteVersion
import com.example.smarty.core.domain.model.SmartyTimer
import com.example.smarty.core.domain.model.TodoItem
import com.example.smarty.data.local.SecurePreferences
import com.example.smarty.features.auth.ui.OnboardingScreen
import com.example.smarty.features.auth.ui.RefinedLoginScreen
import com.example.smarty.features.breathing.GuidedBreathingScreen
import com.example.smarty.features.calendar.ui.CalendarScreen
import com.example.smarty.features.digest.ui.DigestDetailScreen
import com.example.smarty.features.digest.ui.DigestScreen
import com.example.smarty.features.games.ui.ChessScreen
import com.example.smarty.features.games.ui.CoinTossScreen
import com.example.smarty.features.games.ui.TicTacToeScreen
import com.example.smarty.features.notes.domain.SmartyViewModel
import com.example.smarty.features.notes.ui.ArchiveScreen
import com.example.smarty.features.notes.ui.CategoryNotesScreen
import com.example.smarty.features.notes.ui.InputStreamScreen
import com.example.smarty.features.notes.ui.KnowledgeCardScreen
import com.example.smarty.features.notes.ui.StacksScreen
import com.example.smarty.features.notifications.ui.NotificationsScreen
import com.example.smarty.features.settings.domain.BackupViewModel
import com.example.smarty.features.settings.ui.BackupSettingsScreen
import com.example.smarty.features.settings.ui.SettingsScreen
import com.example.smarty.features.tags.ui.TagNotesScreen
import com.example.smarty.features.tags.ui.TagsScreen
import com.example.smarty.features.voice.SpeechToTextState
import com.example.smarty.ui.components.AttachmentOption
import com.example.smarty.ui.components.ConnectionStatus
import com.example.smarty.ui.components.PendingShareData
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Safe popBackStack that prevents crashes on empty back stack.
 * Uses direct popBackStack() without previousBackStackEntry guard,
 * which can be null even with entries on the stack when saveState/restoreState is active.
 */
private fun NavHostController.safePopBackStack(): Boolean =
    try {
        popBackStack()
    } catch (e: Exception) {
        android.util.Log.w("SmartyNavigation", "Safe pop failed: ${e.message}")
        false
    }

sealed class Screen(
    val route: String,
) {
    data object InputStream : Screen("input_stream")

    data object Stacks : Screen("stacks")

    data object CategoryNotes : Screen("category_notes")

    data object KnowledgeCard : Screen("knowledge_card")

    data object Settings : Screen("settings")

    data object Archive : Screen("archive")

    data object BackupSettings : Screen("backup_settings")

    data object Calendar : Screen("calendar")

    data object Login : Screen("login")

    data object Onboarding : Screen("onboarding")

    data object TicTacToe : Screen("tic_tac_toe")

    data object CoinToss : Screen("coin_toss")

    data object Chess : Screen("chess")

    data object Digest : Screen("digest")

    data object GuidedBreathing : Screen("guided_breathing")

    data object Tags : Screen("tags")

    data object TagNotes : Screen("tag_notes")

    data object Notifications : Screen("notifications")

    data object ChatFolders : Screen("chat_folders")
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
    // Notes management
    onAddNote: (String, List<Attachment>) -> Unit,
    onSelectNote: (Note?) -> Unit,
    onSelectCategory: (Category?) -> Unit,
    onCreateCategory: (String) -> Unit,
    onRenameCategory: (Category, String) -> Unit = { _, _ -> },
    onDeleteCategory: (Category) -> Unit,
    onSyncCategoryCounts: () -> Unit = {}, // Sync category counts when entering stacks view
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
    onEditNote: (String, String, String, String?, String?, List<NoteAttachment>) -> Unit = { _, _, _, _, _, _ -> }, // noteId, newTitle, newContent, newSummary, newWhySaved, newAttachments
    onMarkAsViewed: (String) -> Unit = {}, // New tracking action
    // Version history
    selectedNoteVersions: List<NoteVersion> = emptyList(),
    onLoadNoteVersions: (String) -> Unit = {}, // noteId
    onRestoreNoteVersion: (String, String) -> Unit = { _, _ -> }, // noteId, versionId
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
    agentActivity: com.example.smarty.features.chat.domain.ChatFeatureManager.AgentActivity? = null,
    onSendChatMessage: (String, List<Attachment>) -> Unit = { _, _ -> },
    onGenerateImageDirect: (String) -> Unit = {}, // Direct image generation via Krea API
    onExitChatMode: () -> Unit = {}, // Back button handler for chat mode
    onEnterChatMode: () -> Unit = {}, // Enter chat mode when AI tab is clicked
    onEnterChatWithNoteReference: (String) -> Unit = {}, // @Mention: Enter chat with note pre-referenced
    pendingClarificationRequests: List<com.example.smarty.core.domain.model.ClarificationRequest> = emptyList(),
    pendingApprovalToolId: String? = null,
    onCallApproval: (String, Boolean, String?) -> Unit = { _, _, _ -> },
    // Chat history management
    chatSessions: List<ChatSession> = emptyList(),
    currentSessionId: String? = null,
    onSwitchChatSession: (String) -> Unit = {},
    onNewChatSession: () -> Unit = {},
    onDeleteChatSession: (String) -> Unit = {},
    onDeleteChatMessage: (String) -> Unit = {},
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
    // HOISTED STATE: Input attachments from ViewModel (Persistence Fix)
    currentInputAttachments: List<Attachment> = emptyList(),
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
    audioUiState: AudioPlayerUiState = AudioPlayerUiState(),
    onPlayAudio: (AudioTrack) -> Unit = {},
    onPauseAudio: () -> Unit = {},
    onSeekAudio: (Float) -> Unit = {},
    isMiniPlayerVisible: Boolean = false, // Audio player visibility for gradient adjustment
    // Theme management
    isDarkTheme: Boolean,
    onToggleTheme: (Boolean) -> Unit,
    // Cache management
    cacheSizeBytes: Long = 0L,
    onClearCache: () -> Unit = {},
    isClearingCache: Boolean = false,
    // Shake sensitivity
    shakeSensitivity: Float = 0.63f,
    onShakeSensitivityChange: (Float) -> Unit = {},
    // Shake mode switch animation
    wasShakeTriggered: Boolean = false,
    // Network status (Phase 7)
    connectionStatus: ConnectionStatus = ConnectionStatus.CONNECTED,
    // Cloud sync
    cloudSyncState: com.example.smarty.features.notes.domain.SmartyViewModel.CloudSyncState = com.example.smarty.features.notes.domain.SmartyViewModel.CloudSyncState.Idle,
    onSyncCloud: () -> Unit = {},
    onSyncCloudSilent: () -> Unit = {},
    syncSnackbarMessage: kotlinx.coroutines.flow.SharedFlow<String> = kotlinx.coroutines.flow.MutableSharedFlow(),
    // Camera trigger from widget
    cameraTriggered: Boolean = false,
    onClearCameraTrigger: () -> Unit = {},
    // Calendar management
    calendarEvents: List<CalendarEvent> = emptyList(),
    activeTimers: List<com.example.smarty.core.domain.model.SmartyTimer> = emptyList(),
    onAddCalendarEvent: (
        title: String,
        description: String?,
        startTime: Long,
        endTime: Long,
        isAllDay: Boolean,
        location: String?,
        color: Int?,
        reminderMinutes: Int?,
        isPrivate: Boolean,
    ) -> Unit = { _, _, _, _, _, _, _, _, _ -> },
    onUpdateCalendarEvent: (CalendarEvent) -> Unit = {},
    onDeleteCalendarEvent: (String) -> Unit = {},
    onCancelTimer: (SmartyTimer) -> Unit = {},
    onNavigateToTags: () -> Unit = {},
    onNavigateToNotifications: () -> Unit = {},
    onNavigateToChatFolders: () -> Unit = {},
    bottomContentPadding: androidx.compose.ui.unit.Dp = 0.dp,
    externalSpeechState: com.example.smarty.features.voice.SpeechToTextState? = null,
    speechResults: kotlinx.coroutines.flow.Flow<String>? = null,
    // Screen change callback for shake detection
    onScreenChange: (String) -> Unit = {},
    // Shake blocking control
    onSetShakeBlocked: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
    // Auth State
    isLoggedIn: Boolean = false,
    onSignOut: () -> Unit = {},
    // Google Calendar Two-Way Sync
    isCalendarSyncEnabled: Boolean = false,
    onSetCalendarSyncEnabled: (Boolean) -> Unit = {},
    deviceCalendars: List<com.example.smarty.features.calendar.domain.GoogleCalendarSyncManager.DeviceCalendar> = emptyList(),
    targetCalendarId: Long = -1L,
    onSetTargetCalendarId: (Long) -> Unit = {},
    onLoadDeviceCalendars: () -> Unit = {},
    // AI Navigation
    navigationRequest: String? = null,
    onClearNavigationRequest: () -> Unit = {},
) {
    val securePreferences = SecurePreferences.getInstance(androidx.compose.ui.platform.LocalContext.current)
    // Decide start destination based on onboarding state
    val startDestination = if (securePreferences.isOnboarded()) Screen.InputStream.route else Screen.Onboarding.route

    val viewModel: com.example.smarty.features.notes.domain.SmartyViewModel = viewModel()
    val selectedTab by viewModel.selectedTab.collectAsState()
    val isSettingsLoading by viewModel.isSettingsLoading.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    val availableModels by viewModel.availableModels.collectAsState()
    val modelVariantMap by viewModel.modelVariantMap.collectAsState()
    val selectedVariant by viewModel.selectedVariant.collectAsState()

    // Handle AI-triggered navigation requests
    androidx.compose.runtime.LaunchedEffect(navigationRequest) {
        navigationRequest?.let { target ->
            val route =
                when (target.lowercase()) {
                    "input_stream", "main", "home" -> Screen.InputStream.route
                    "stacks", "categories" -> Screen.Stacks.route
                    "settings", "config" -> Screen.Settings.route
                    "calendar", "events" -> Screen.Calendar.route
                    "archive" -> Screen.Archive.route
                    "backup" -> Screen.BackupSettings.route
                    "tictactoe", "game", "play" -> Screen.TicTacToe.route
                    "cointoss", "flip", "decision", "coin" -> Screen.CoinToss.route
                    "chess" -> Screen.Chess.route
                    "guided_breathing", "breathing", "breathe" -> Screen.GuidedBreathing.route
                    else -> null
                }

            route?.let {
                android.util.Log.i("SmartyNavigation", "AI navigating to: $it")
                when (route) {
                    Screen.InputStream.route, Screen.Stacks.route, Screen.Settings.route, Screen.Calendar.route, Screen.Archive.route -> {
                        // Ensure we are on the main InputStream host to utilize its inline view queue
                        if (navController.currentBackStackEntry?.destination?.route != Screen.InputStream.route) {
                            navController.popBackStack(Screen.InputStream.route, inclusive = false)
                        }

                        // Update selected tab to trigger the inline view via LaunchedEffect in InputStreamScreen
                        when (route) {
                            Screen.InputStream.route -> viewModel.setSelectedTab(com.example.smarty.core.domain.model.NavigationTab.NOTES)
                            Screen.Stacks.route -> viewModel.setSelectedTab(com.example.smarty.core.domain.model.NavigationTab.STACKS)
                            Screen.Settings.route -> viewModel.setSelectedTab(com.example.smarty.core.domain.model.NavigationTab.SETTINGS)
                            Screen.Calendar.route -> viewModel.setSelectedTab(com.example.smarty.core.domain.model.NavigationTab.CALENDAR)
                            Screen.Archive.route -> viewModel.setSelectedTab(com.example.smarty.core.domain.model.NavigationTab.ARCHIVE)
                        }
                    }
                    else -> {
                        navController.navigate(it) {
                            launchSingleTop = true
                        }
                    }
                }
                onClearNavigationRequest()
            }
        }
    }

    // Track navigation changes and notify ViewModel
    androidx.compose.runtime.LaunchedEffect(navController) {
        navController.currentBackStackEntryFlow.collect { backStackEntry ->
            val route = backStackEntry.destination.route ?: "unknown"
            onScreenChange(route)
            // Sync selected tab with current route
            when (route) {
                Screen.InputStream.route -> viewModel.setSelectedTab(com.example.smarty.core.domain.model.NavigationTab.NOTES)
                Screen.Stacks.route -> viewModel.setSelectedTab(com.example.smarty.core.domain.model.NavigationTab.STACKS)
                Screen.Settings.route -> viewModel.setSelectedTab(com.example.smarty.core.domain.model.NavigationTab.SETTINGS)
                Screen.Calendar.route -> viewModel.setSelectedTab(com.example.smarty.core.domain.model.NavigationTab.CALENDAR)
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
        enterTransition = {
            androidx.compose.animation.slideInVertically(
                animationSpec =
                    androidx.compose.animation.core.spring(
                        dampingRatio = 0.8f,
                        stiffness = 400f,
                    ),
                initialOffsetY = { it / 10 },
            ) +
                androidx.compose.animation.fadeIn(
                    animationSpec =
                        androidx.compose.animation.core
                            .tween(200),
                )
        },
        exitTransition = {
            androidx.compose.animation.slideOutVertically(
                animationSpec =
                    androidx.compose.animation.core.spring(
                        dampingRatio = 0.8f,
                        stiffness = 400f,
                    ),
                targetOffsetY = { -it / 10 },
            ) +
                androidx.compose.animation.fadeOut(
                    animationSpec =
                        androidx.compose.animation.core
                            .tween(200),
                )
        },
        popEnterTransition = {
            androidx.compose.animation.slideInVertically(
                animationSpec =
                    androidx.compose.animation.core.spring(
                        dampingRatio = 0.8f,
                        stiffness = 400f,
                    ),
                initialOffsetY = { -it / 10 },
            ) +
                androidx.compose.animation.fadeIn(
                    animationSpec =
                        androidx.compose.animation.core
                            .tween(200),
                )
        },
        popExitTransition = {
            androidx.compose.animation.slideOutVertically(
                animationSpec =
                    androidx.compose.animation.core.spring(
                        dampingRatio = 0.8f,
                        stiffness = 400f,
                    ),
                targetOffsetY = { it / 10 },
            ) +
                androidx.compose.animation.fadeOut(
                    animationSpec =
                        androidx.compose.animation.core
                            .tween(200),
                )
        },
    ) {
        composable(Screen.InputStream.route) { _ ->
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
                        popUpTo(navController.graph.startDestinationId) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route) {
                        popUpTo(navController.graph.startDestinationId) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onNavigateToCalendar = {
                    navController.navigate(Screen.Calendar.route) {
                        popUpTo(navController.graph.startDestinationId) {
                            saveState = true
                        }
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
                agentActivity = agentActivity,
                onSendChatMessage = onSendChatMessage,
                onGenerateImageDirect = onGenerateImageDirect,
                onStopGeneration = viewModel::stopGeneration,
                onExitChatMode = onExitChatMode,
                onEnterChatMode = onEnterChatMode,
                onClarificationSubmit = viewModel::submitClarification,
                pendingClarificationRequests = pendingClarificationRequests,
                pendingApprovalToolId = pendingApprovalToolId,
                onCallApproval = viewModel::callApproval,
                // Chat history
                chatSessions = chatSessions,
                currentSessionId = currentSessionId,
                onSwitchChatSession = onSwitchChatSession,
                onNewChatSession = onNewChatSession,
                onDeleteChatSession = onDeleteChatSession,
                onDeleteChatMessage = onDeleteChatMessage,
                onNoteClickById = { noteId ->
                    // Navigate to note detail - for now just select it
                    notes.find { it.id == noteId }?.let { note ->
                        onSelectNote(note)
                    }
                },
                onEventClickById = { eventId ->
                    // Reuse Note detail sheet for Calendar Event as requested
                    calendarEvents.find { it.id == eventId }?.let { event ->
                        val tempNote =
                            com.example.smarty.core.domain.model.Note(
                                id = event.id,
                                title = "Event: ${event.title}",
                                content = "Time: ${java.util.Date(event.startTime)}\n\n${event.description ?: "No description provided."}",
                                createdAt = event.startTime,
                                updatedAt = event.startTime,
                                type = com.example.smarty.core.domain.model.NoteType.BRAIN_DUMP,
                            )
                        onSelectNote(tempNote)
                    }
                },
                // @Mention autocomplete
                mentionState = mentionState,
                onMentionSelected = onMentionSelected,
                // Pending chat text (for "Ask AI" from note card)
                pendingChatText = pendingChatText,
                onClearPendingChatText = onClearPendingChatText,
                // Mention state update
                onUpdateMentionState = onUpdateMentionState,
                // AI exclusion
                isAiExcluded = isAiExcluded,
                onInputTextChange = onInputTextChange,
                currentInputAttachments = currentInputAttachments,
                onInputAttachmentsChange = onInputAttachmentsChange,
                selectedCategory = selectedCategory,
                onSelectCategory = onSelectCategory,
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
                cloudSyncState = cloudSyncState,
                onSyncCloud = onSyncCloud,
                onSyncCloudSilent = onSyncCloudSilent,
                syncSnackbarMessage = syncSnackbarMessage,
                // Camera trigger from widget
                cameraTriggered = cameraTriggered,
                onClearCameraTrigger = onClearCameraTrigger,
                // Pin and Share
                onPinNote = onPinNote,
                onUnpinNote = onUnpinNote,
                onShareNotes = onShareNotes,
                //
                // CENTRALIZED UI: All features accessible from main screen
                //
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
                cacheSizeBytes = cacheSizeBytes,
                onClearCache = onClearCache,
                isClearingCache = isClearingCache,
                shakeSensitivity = shakeSensitivity,
                onShakeSensitivityChange = onShakeSensitivityChange,
                onSignOut = onSignOut,
                selectedTab = selectedTab,
                onSelectedTabChange = { viewModel.setSelectedTab(it) },
                backupContent = { onDismiss ->
                    BackupSettingsRoute(
                        onBackClick = onDismiss,
                        isEmbedded = true,
                    )
                },
                selectedModel = selectedModel,
                availableModels = availableModels,
                onModelSelected = { viewModel.selectModel(it) },
                modelVariantMap = modelVariantMap,
                selectedVariant = selectedVariant,
                onVariantSelected = { viewModel.selectVariant(it) },
                onRefreshModels = { viewModel.refreshModels() },
            )
        }

        composable(Screen.Stacks.route) { _ ->
            // Clear selected category when viewing Stacks to ensure clean state
            LaunchedEffect(Unit) {
                onSelectCategory(null)
            }

            StacksScreen(
                categories = categories,
                isLoading = isStacksLoading,
                onCategoryClick = { category ->
                    // Use route argument instead of updating global selection state
                    navController.navigate("${Screen.CategoryNotes.route}/${category.id}")
                },
                onBackClick = {
                    navController.safePopBackStack()
                },
                onCreateCategory = onCreateCategory,
                onRenameCategory = onRenameCategory,
                onDeleteCategory = onDeleteCategory,
                bottomContentPadding = bottomContentPadding,
            )
        }

        composable(
            route = "${Screen.CategoryNotes.route}/{categoryId}",
            arguments = listOf(navArgument("categoryId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val categoryId = backStackEntry.arguments?.getString("categoryId")
            val category = categories.find { it.id == categoryId }

            if (category != null) {
                CategoryNotesScreen(
                    category = category,
                    notes = notes,
                    isLoading = isNotesLoading,
                    onBackClick = {
                        // Just pop back to Stacks
                        navController.safePopBackStack()
                    },
                    onNoteClick = { note ->
                        onSelectNote(note)
                        navController.navigate(Screen.KnowledgeCard.route)
                    },
                    onArchiveNote = onArchiveNote,
                    bottomContentPadding = bottomContentPadding,
                )
            }
        }

        composable(Screen.KnowledgeCard.route) { _ ->
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
                    onPauseAudio = onPauseAudio,
                    onSeekAudio = onSeekAudio,
                    audioUiState = audioUiState,
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
                    },
                )
            }
        }

        composable(Screen.Settings.route) { _ ->
            val backupViewModel: BackupViewModel = viewModel()

            SettingsScreen(
                isDarkTheme = isDarkTheme,
                onBackClick = {
                    navController.safePopBackStack()
                },
                onToggleTheme = onToggleTheme,
                isLoading = isSettingsLoading,
                // Cache management
                cacheSizeBytes = cacheSizeBytes,
                onClearCache = onClearCache,
                isClearingCache = isClearingCache,
                onExportData = { backupViewModel.createLocalBackup() },
                // Shake sensitivity
                shakeSensitivity = shakeSensitivity,
                onShakeSensitivityChange = onShakeSensitivityChange,
                // Embedded Sheets
                backupContent = { onDismiss ->
                    BackupSettingsRoute(
                        onBackClick = onDismiss,
                        isEmbedded = true,
                    )
                },
                // Google Calendar Two-Way Sync
                isCalendarSyncEnabled = isCalendarSyncEnabled,
                onSetCalendarSyncEnabled = onSetCalendarSyncEnabled,
                deviceCalendars = deviceCalendars,
                targetCalendarId = targetCalendarId,
                onSetTargetCalendarId = onSetTargetCalendarId,
                onLoadDeviceCalendars = onLoadDeviceCalendars,
                onSignOut = onSignOut,
                onNavigateToTags = {
                    navController.navigate(Screen.Tags.route)
                },
                onNavigateToNotifications = {
                    navController.navigate(Screen.Notifications.route)
                },
                onNavigateToChatFolders = {
                    navController.navigate(Screen.ChatFolders.route)
                },
            )
        }

        composable(Screen.Archive.route) { _ ->
            ArchiveScreen(
                archivedNotes = archivedNotes,
                isLoading = isArchiveLoading,
                onBackClick = {
                    navController.safePopBackStack()
                },
                onDeleteNote = onDeleteNoteById,
                onUnarchiveNote = onUnarchiveNote,
            )
        }

        composable(Screen.BackupSettings.route) { _ ->
            BackupSettingsRoute(
                onBackClick = {
                    navController.safePopBackStack()
                },
            )
        }

        composable(Screen.Calendar.route) { _ ->
            CalendarRoute(
                calendarEvents = calendarEvents,
                activeTimers = activeTimers,
                isLoading = isCalendarLoading,
                onBackClick = { navController.safePopBackStack() },
                onAddCalendarEvent = onAddCalendarEvent,
                onUpdateCalendarEvent = onUpdateCalendarEvent,
                onDeleteCalendarEvent = onDeleteCalendarEvent,
                onCancelTimer = onCancelTimer,
            )
        }

        composable(Screen.Login.route) { _ ->
            RefinedLoginScreen(
                onLoginSuccess = {
                    navController.navigate(Screen.InputStream.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                },
            )
        }

        composable(Screen.Onboarding.route) { _ ->
            OnboardingScreen(
                securePreferences = securePreferences,
                onFinish = {
                    navController.navigate(Screen.InputStream.route) {
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                    }
                },
            )
        }

        composable(Screen.TicTacToe.route) { _ ->
            TicTacToeScreen(
                onClose = { navController.safePopBackStack() },
            )
        }

        composable(Screen.CoinToss.route) { _ ->
            CoinTossScreen(
                onClose = { navController.safePopBackStack() },
            )
        }

        composable(Screen.Chess.route) { _ ->
            ChessScreen(
                onClose = { navController.safePopBackStack() },
            )
        }

        composable(Screen.Digest.route) { _ ->
            DigestRoute(
                onBackClick = { navController.safePopBackStack() },
                onDigestClick = { digest ->
                    navController.navigate("digest_detail/${digest.id}")
                },
            )
        }

        composable(
            route = "digest_detail/{digestId}",
            arguments = listOf(navArgument("digestId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val digestId = backStackEntry.arguments?.getString("digestId") ?: ""
            DigestDetailRoute(
                digestId = digestId,
                onBackClick = { navController.safePopBackStack() },
            )
        }

        composable(Screen.GuidedBreathing.route) { _ ->
            GuidedBreathingScreen(
                onDismiss = { navController.safePopBackStack() },
            )
        }

        composable(Screen.Tags.route) { _ ->
            TagsScreen(
                onNavigateBack = { navController.safePopBackStack() },
                onTagClick = { tagId ->
                    navController.navigate("tag_notes/$tagId")
                },
            )
        }

        composable(
            route = "tag_notes/{tagId}",
            arguments = listOf(navArgument("tagId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val tagId = backStackEntry.arguments?.getString("tagId") ?: ""
            val tagName = backStackEntry.arguments?.getString("tagName") ?: ""
            val tagColor = backStackEntry.arguments?.getString("tagColor") ?: "#6200EE"

            TagNotesScreen(
                tagId = tagId,
                tagName = tagName,
                tagColor = tagColor,
                onNavigateBack = { navController.safePopBackStack() },
            )
        }

        composable(Screen.Notifications.route) { _ ->
            NotificationsScreen(
                onNavigateBack = { navController.safePopBackStack() },
            )
        }

        composable(Screen.ChatFolders.route) { _ ->
            com.example.smarty.features.chatfolders.ui.ChatFoldersScreen(
                onNavigateBack = { navController.safePopBackStack() },
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
    viewModel: BackupViewModel = viewModel(),
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
    val signInLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult(),
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
        isEmbedded = isEmbedded,
    )
}

/**
 * Digest screen wrapper with ViewModel and data fetching.
 */
@Composable
fun DigestRoute(
    onBackClick: () -> Unit,
    onDigestClick: (com.example.smarty.features.digest.domain.DigestResult) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val application = context.applicationContext as? android.app.Application

    val digestFeatureManager =
        remember(application) {
            application?.let {
                com.example.smarty.di.ServiceLocator
                    .provideDigestFeatureManager(it)
            }
        }

    val digests by digestFeatureManager?.digests?.collectAsState() ?: remember { mutableStateOf(emptyList()) }
    val isLoading by digestFeatureManager?.isLoading?.collectAsState() ?: remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        digestFeatureManager?.fetchDigests()
    }

    DigestScreen(
        digests = digests,
        isLoading = isLoading,
        onNavigateBack = onBackClick,
        onDigestClick = onDigestClick,
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DigestDetailRoute(
    digestId: String,
    onBackClick: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val application = context.applicationContext as? android.app.Application

    val digestFeatureManager =
        remember(application) {
            application?.let {
                com.example.smarty.di.ServiceLocator
                    .provideDigestFeatureManager(it)
            }
        }

    var digest by remember { mutableStateOf<com.example.smarty.features.digest.domain.DigestResult?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(digestId) {
        isLoading = true
        digest = digestFeatureManager?.fetchDigestById(digestId)
        isLoading = false
    }

    if (isLoading) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.CircularProgressIndicator()
        }
    } else if (digest != null) {
        DigestDetailScreen(
            digest = digest!!,
            onNavigateBack = onBackClick,
        )
    } else {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Text("Digest not found")
        }
    }
}

/**
 * Calendar screen wrapper with proper experimental API annotation for ModalBottomSheet.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun CalendarRoute(
    calendarEvents: List<CalendarEvent>,
    activeTimers: List<com.example.smarty.core.domain.model.SmartyTimer> = emptyList(),
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
        isPrivate: Boolean,
    ) -> Unit,
    onUpdateCalendarEvent: (CalendarEvent) -> Unit,
    onDeleteCalendarEvent: (String) -> Unit,
    onCancelTimer: (SmartyTimer) -> Unit = {},
    isLoading: Boolean = false,
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
        },
    )

    // Add Event/Timer Sheet (Updated to use new TimeEditor)
    if (showAddSheet) {
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { showAddSheet = false },
            sheetState =
                androidx.compose.material3.rememberModalBottomSheetState(
                    skipPartiallyExpanded = true,
                ),
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            dragHandle = null,
        ) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                com.example.smarty.ui.components.TimeEditor(
                    onSave = { h, m, t ->
                        val durationMs = (h * 60 + m) * 60 * 1000L
                        val startTime = selectedDateForNewEvent?.timeInMillis ?: System.currentTimeMillis()
                        val endTime = startTime + durationMs
                        onAddCalendarEvent(
                            t,
                            "Created via quick sheet",
                            startTime,
                            endTime,
                            false,
                            null,
                            null,
                            null,
                            false,
                        )
                        showAddSheet = false
                    },
                )
            }
        }
    }

    // Edit Event Sheet
    selectedEventForEdit?.let { event ->
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { selectedEventForEdit = null },
            sheetState =
                androidx.compose.material3.rememberModalBottomSheetState(
                    skipPartiallyExpanded = true,
                ),
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            dragHandle = null,
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
                },
            )
        }
    }
}
