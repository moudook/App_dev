package com.example.smarty.navigation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.smarty.data.local.AIProvider
import com.example.smarty.data.local.AIProviderConfig
import com.example.smarty.data.model.Attachment
import com.example.smarty.data.model.AudioTrack
import com.example.smarty.data.model.Category
import com.example.smarty.data.model.ChatMessage
import com.example.smarty.data.model.ChatSession
import com.example.smarty.data.model.Note
import com.example.smarty.data.model.TodoItem
import com.example.smarty.ui.components.PendingShareData
import com.example.smarty.ui.screens.*
import com.example.smarty.viewmodel.BackupViewModel

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
    onAddApiKey: (AIProvider, String) -> Unit,
    onRemoveApiKey: (AIProvider, String) -> Unit,
    onUpdateApiKey: (AIProvider, String, String) -> Unit,
    onSetProviderEnabled: (AIProvider, Boolean) -> Unit,
    onSetSelectedModel: (AIProvider, String) -> Unit,
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
    onDeleteNote: (Note) -> Unit,
    onDeleteNoteById: (String) -> Unit,
    onUpdateNoteTodos: (String, List<TodoItem>) -> Unit,
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
    // Chat history management
    chatSessions: List<ChatSession> = emptyList(),
    currentSessionId: String? = null,
    onSwitchChatSession: (String) -> Unit = {},
    onNewChatSession: () -> Unit = {},
    onDeleteChatSession: (String) -> Unit = {},
    // AI exclusion management
    isAiExcluded: Boolean = false,
    onInputTextChange: (String) -> Unit = {},
    // Audio player for attachments
    onPlayAudio: (AudioTrack) -> Unit = {},
    // Theme management
    isDarkTheme: Boolean,
    onToggleTheme: (Boolean) -> Unit,
    // Cache management
    cacheSizeBytes: Long = 0L,
    onClearCache: () -> Unit = {},
    isClearingCache: Boolean = false,
    modifier: Modifier = Modifier
) {
    // Start at PIN screen if configured, otherwise go to main input stream
    val startDestination = if (isPinConfigured) Screen.Pin.route else Screen.InputStream.route

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
                    navController.popBackStack()
                }
            )
        }

        // PIN Change Screen (verify old, set new)
        composable(Screen.PinChange.route) {
            PinChangeRoute(
                onVerifyPin = onVerifyPin,
                onSetPin = onSetPin,
                onComplete = {
                    navController.popBackStack()
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
                onUpdateNoteTodos = onUpdateNoteTodos,
                onNavigateToStacks = {
                    navController.navigate(Screen.Stacks.route)
                },
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
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
                // Chat history
                chatSessions = chatSessions,
                currentSessionId = currentSessionId,
                onSwitchChatSession = onSwitchChatSession,
                onNewChatSession = onNewChatSession,
                onDeleteChatSession = onDeleteChatSession,
                // AI exclusion
                isAiExcluded = isAiExcluded,
                onInputTextChange = onInputTextChange
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
                    navController.popBackStack()
                },
                onCreateCategory = onCreateCategory,
                onDeleteCategory = onDeleteCategory
            )
        }

        composable(Screen.CategoryNotes.route) {
            selectedCategory?.let { category ->
                CategoryNotesScreen(
                    category = category,
                    notes = notes,
                    onBackClick = {
                        navController.popBackStack()
                    },
                    onNoteClick = { note ->
                        onSelectNote(note)
                        navController.navigate(Screen.KnowledgeCard.route)
                    },
                    onArchiveNote = onArchiveNote
                )
            }
        }

        composable(Screen.KnowledgeCard.route) {
            selectedNote?.let { note ->
                KnowledgeCardScreen(
                    note = note,
                    onBackClick = {
                        navController.popBackStack()
                    },
                    onArchiveClick = {
                        onArchiveNote(note.id)
                        navController.popBackStack()
                    },
                    onDeleteClick = {
                        onDeleteNote(note)
                        navController.popBackStack()
                    },
                    onPlayAudio = onPlayAudio
                )
            }
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                providerConfigs = providerConfigs,
                isPinConfigured = isPinConfigured,
                isDarkTheme = isDarkTheme,
                onBackClick = {
                    navController.popBackStack()
                },
                onAddApiKey = onAddApiKey,
                onRemoveApiKey = onRemoveApiKey,
                onUpdateApiKey = onUpdateApiKey,
                onSetProviderEnabled = onSetProviderEnabled,
                onSetSelectedModel = onSetSelectedModel,
                onTestApiKey = onTestApiKey,
                onRemovePin = onClearPin,
                onToggleTheme = onToggleTheme,
                // Cache management
                cacheSizeBytes = cacheSizeBytes,
                onClearCache = onClearCache,
                isClearingCache = isClearingCache,
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
                    navController.popBackStack()
                },
                onDeleteNote = onDeleteNoteById,
                onUnarchiveNote = onUnarchiveNote
            )
        }

        composable(Screen.BackupSettings.route) {
            BackupSettingsRoute(
                onBackClick = {
                    navController.popBackStack()
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
    val lastBackupTime by viewModel.lastBackupTime.collectAsState()
    val autoBackupEnabled by viewModel.autoBackupEnabled.collectAsState()
    val autoBackupIntervalDays by viewModel.autoBackupIntervalDays.collectAsState()

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
