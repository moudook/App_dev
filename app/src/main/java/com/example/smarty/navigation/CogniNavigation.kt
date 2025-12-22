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
import com.example.smarty.data.model.Note
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
        android.util.Log.w("CogniNavigation", "Safe pop failed: ${e.message}")
        false
    }
}

sealed class Screen(val route: String) {
    data object Pin : Screen("pin")
    data object PinSetup : Screen("pin_setup")
    data object PinChange : Screen("pin_change")
    data object InputStream : Screen("input_stream")
    data object Stacks : Screen("stacks")
    data object CategoryNotes : Screen("category_notes")
    data object KnowledgeCard : Screen("knowledge_card")
    data object Settings : Screen("settings")
    data object Archive : Screen("archive")
    data object BackupSettings : Screen("backup_settings")
    data object Calendar : Screen("calendar")
}

@Composable
fun CogniNavHost(
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
    // PIN management
    isPinConfigured: Boolean,
    onSetPin: (String) -> Unit,
    onVerifyPin: (String) -> Boolean,
    onChangePin: (String, String) -> Boolean,
    onClearPin: () -> Unit,
    // Notes management
    onAddNote: (String, List<Attachment>) -> Unit,
    onSelectNote: (Note?) -> Unit,
    onSelectCategory: (Category?) -> Unit,
    onCreateCategory: (String) -> Unit,
    onDeleteCategory: (Category) -> Unit,
    onArchiveNote: (String) -> Unit,
    onUnarchiveNote: (String) -> Unit,
    onBulkArchive: (List<String>) -> Unit = {},
    onUndoArchive: () -> Unit = {},
    onRefreshNotes: () -> Unit = {},
    isRefreshing: Boolean = false,
    isNotesLoading: Boolean = false,
    onDeleteNote: (Note) -> Unit,
    onDeleteNoteById: (String) -> Unit,
    onUpdateNoteTodos: (String, List<TodoItem>, onComplete: (() -> Unit)?) -> Unit,
    onEditNote: (String, String, String) -> Unit = { _, _, _ -> },  // noteId, newTitle, newContent
    onMarkAsViewed: (String) -> Unit = {}, // New tracking action
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
    // Chat history management
    chatSessions: List<ChatSession> = emptyList(),
    currentSessionId: String? = null,
    onSwitchChatSession: (String) -> Unit = {},
    onNewChatSession: () -> Unit = {},
    onDeleteChatSession: (String) -> Unit = {},
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
    // Audio player for attachments
    onPlayAudio: (AudioTrack) -> Unit = {},
    // Theme management
    isDarkTheme: Boolean,
    onToggleTheme: (Boolean) -> Unit,
    // Cache management
    cacheSizeBytes: Long = 0L,
    onClearCache: () -> Unit = {},
    isClearingCache: Boolean = false,
    // Tavily Web Search API
    tavilyApiKey: String? = null,
    onSetTavilyApiKey: (String?) -> Unit = {},
    // Shake sensitivity
    shakeSensitivity: Float = 0.63f,
    onShakeSensitivityChange: (Float) -> Unit = {},
    // GROQ key usage stats
    groqKeyUsageStats: List<KeyUsageStats> = emptyList(),
    // Shake mode switch animation
    wasShakeTriggered: Boolean = false,
    // Network status (Phase 7)
    connectionStatus: ConnectionStatus = ConnectionStatus.CONNECTED,
    // Calendar management
    calendarEvents: List<CalendarEvent> = emptyList(),
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
    bottomContentPadding: androidx.compose.ui.unit.Dp = 0.dp,
    externalSpeechState: com.example.smarty.util.SpeechToTextState? = null,
    speechResults: kotlinx.coroutines.flow.Flow<String>? = null,

    // Dynamic Models
    onRefreshModels: (AIProvider) -> Unit = {},
    getAvailableModels: (AIProvider) -> List<Pair<String, String>> = { com.example.smarty.data.local.AIModels.getModelsForProvider(it) },
    // Screen change callback for shake detection
    onScreenChange: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Start at PIN screen if configured, otherwise go to main input stream
    val startDestination = if (isPinConfigured) Screen.Pin.route else Screen.InputStream.route

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
        // PIN Entry Screen (for existing PIN)
        composable(Screen.Pin.route) {
            PinScreen(
                isSetup = false,
                onPinComplete = { pin ->
                    val success = onVerifyPin(pin)
                    if (success) {
                        navController.navigate(Screen.InputStream.route) {
                            popUpTo(Screen.Pin.route) { inclusive = true }
                        }
                    }
                    success
                }
            )
        }

        // PIN Setup Screen (new PIN)
        composable(Screen.PinSetup.route) {
            PinSetupRoute(
                onSetupComplete = { pin ->
                    onSetPin(pin)
                    navController.safePopBackStack()
                }
            )
        }

        // PIN Change Screen (verify old, set new)
        composable(Screen.PinChange.route) {
            PinChangeRoute(
                onVerifyPin = onVerifyPin,
                onSetPin = onSetPin,
                onComplete = {
                    navController.safePopBackStack()
                }
            )
        }

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
                onUpdateNoteTodos = onUpdateNoteTodos,
                onNavigateToStacks = {
                    navController.navigate(Screen.Stacks.route)
                },
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
                },
                onNavigateToCalendar = {
                    navController.navigate(Screen.Calendar.route)
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
                // Chat history
                chatSessions = chatSessions,
                currentSessionId = currentSessionId,
                onSwitchChatSession = onSwitchChatSession,
                onNewChatSession = onNewChatSession,
                onDeleteChatSession = onDeleteChatSession,
                // AI exclusion
                isAiExcluded = isAiExcluded,
                onInputTextChange = onInputTextChange,
                onInputAttachmentsChange = onInputAttachmentsChange,
                // Search and Filter
                searchQuery = searchQuery,
                onSearchQueryChange = onSearchQueryChange,
                selectedFilters = selectedFilters,
                onFilterToggle = onFilterToggle,
                onClearFilters = onClearFilters,
                bottomContentPadding = bottomContentPadding,
                externalSpeechState = externalSpeechState,
                speechResults = speechResults,
                wasShakeTriggered = wasShakeTriggered,
                connectionStatus = connectionStatus
            )
        }

        composable(Screen.Stacks.route) {
            StacksScreen(
                categories = categories,
                onCategoryClick = { category ->
                    onSelectCategory(category)
                    navController.navigate(Screen.CategoryNotes.route)
                },
                onBackClick = {
                    navController.safePopBackStack()
                },
                onCreateCategory = onCreateCategory,
                onDeleteCategory = onDeleteCategory,
                bottomContentPadding = bottomContentPadding
            )
        }

        composable(Screen.CategoryNotes.route) {
            selectedCategory?.let { category ->
                CategoryNotesScreen(
                    category = category,
                    notes = notes,
                    onBackClick = {
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
                    bottomContentPadding = bottomContentPadding
                )
            }
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                providerConfigs = providerConfigs,
                providerPriorityOrder = providerPriorityOrder,
                isPinConfigured = isPinConfigured,
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
                onRemovePin = onClearPin,
                onToggleTheme = onToggleTheme,
                // Tavily Web Search API
                tavilyApiKey = tavilyApiKey,
                onSetTavilyApiKey = onSetTavilyApiKey,
                // Cache management
                cacheSizeBytes = cacheSizeBytes,
                onClearCache = onClearCache,
                isClearingCache = isClearingCache,
                // Shake sensitivity
                shakeSensitivity = shakeSensitivity,
                onShakeSensitivityChange = onShakeSensitivityChange,
                // GROQ key usage stats
                groqKeyUsageStats = groqKeyUsageStats,
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
                pinSetupContent = { onDismiss ->
                     PinSetupRoute(
                         onSetupComplete = { pin ->
                             onSetPin(pin)
                             onDismiss()
                         },
                         isEmbedded = true
                     )
                },
                pinChangeContent = { onDismiss ->
                    PinChangeRoute(
                        onVerifyPin = onVerifyPin,
                        onSetPin = onSetPin,
                        onComplete = onDismiss,
                        isEmbedded = true
                    )
                }
            )
        }


        composable(Screen.Archive.route) {
            ArchiveScreen(
                archivedNotes = archivedNotes,
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
                onBackClick = { navController.safePopBackStack() },
                onAddCalendarEvent = onAddCalendarEvent,
                onUpdateCalendarEvent = onUpdateCalendarEvent,
                onDeleteCalendarEvent = onDeleteCalendarEvent
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
    val lastBackupTime by viewModel.lastBackupTime.collectAsState()
    val autoBackupEnabled by viewModel.autoBackupEnabled.collectAsState()
    val autoBackupIntervalDays by viewModel.autoBackupIntervalDays.collectAsState()

    // Local backup state
    val localBackupState by viewModel.localBackupState.collectAsState()
    val localBackups by viewModel.localBackups.collectAsState()

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
        lastBackupTime = lastBackupTime,
        autoBackupEnabled = autoBackupEnabled,
        autoBackupIntervalDays = autoBackupIntervalDays,
        localBackupState = localBackupState,
        localBackups = localBackups,
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

@Composable
fun PinSetupRoute(
    onSetupComplete: (String) -> Unit,
    isEmbedded: Boolean = false
) {
    PinScreen(
        isSetup = true,
        onPinComplete = { true }, // Not used in setup mode
        onSetupComplete = onSetupComplete,
        isEmbedded = isEmbedded
    )
}

@Composable
fun PinChangeRoute(
    onVerifyPin: (String) -> Boolean,
    onSetPin: (String) -> Unit,
    onComplete: () -> Unit,
    isEmbedded: Boolean = false
) {
    var stage by remember { mutableStateOf(0) } // 0 = verify old, 1 = set new
    var oldPinVerified by remember { mutableStateOf(false) }

    if (!oldPinVerified) {
        // First, verify old PIN
        PinScreen(
            isSetup = false,
            onPinComplete = { oldPin ->
                val success = onVerifyPin(oldPin)
                if (success) {
                    oldPinVerified = true
                    stage = 1
                }
                success
            },
            isEmbedded = isEmbedded
        )
    } else {
        // Then, set new PIN
        PinScreen(
            isSetup = true,
            onPinComplete = { true },
            onSetupComplete = { newPin ->
                onSetPin(newPin)
                onComplete()
            },
            isEmbedded = isEmbedded
        )
    }
}

/**
 * Calendar screen wrapper with proper experimental API annotation for ModalBottomSheet.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun CalendarRoute(
    calendarEvents: List<CalendarEvent>,
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
    onDeleteCalendarEvent: (String) -> Unit
) {
    var showAddSheet by remember { mutableStateOf(false) }
    var selectedEventForEdit by remember { mutableStateOf<CalendarEvent?>(null) }

    CalendarScreen(
        events = calendarEvents,
        onBackClick = onBackClick,
        onAddEvent = { showAddSheet = true },
        onEventClick = { event ->
            selectedEventForEdit = event
        },
        onDeleteEvent = { event ->
            onDeleteCalendarEvent(event.id)
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
                onDismiss = { showAddSheet = false },
                onConfirm = { title, description, startTime, endTime, isAllDay, location, color, reminderMinutes, isPrivate ->
                    onAddCalendarEvent(title, description, startTime, endTime, isAllDay, location, color, reminderMinutes, isPrivate)
                    showAddSheet = false
                }
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
