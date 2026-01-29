package com.example.smarty.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Assistant
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material.icons.filled.NoAccounts
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.res.stringResource
import com.example.smarty.R
import com.example.smarty.data.local.AIModels
import com.example.smarty.data.local.AIProvider
import com.example.smarty.data.local.AIProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.screens.settings.ProviderSection
import com.example.smarty.ui.screens.settings.maskApiKey
import com.example.smarty.ui.screens.settings.DataManagementSection
import com.example.smarty.ui.screens.settings.formatCacheSize
import com.example.smarty.util.api.ApiMetrics
import com.example.smarty.util.api.KeyUsageStats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.smarty.ui.components.ShakeSensitivityControl
import com.example.smarty.ui.theme.ComponentSpacing
import com.example.smarty.ui.theme.LocalShapes
import com.example.smarty.ui.theme.SystemGreen
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Duolingo-style Settings Screen
 * Clean, simple, minimal scrolling required
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    providerConfigs: Map<AIProvider, AIProviderConfig>,
    providerPriorityOrder: List<AIProvider>,
    isDarkTheme: Boolean,
    onBackClick: () -> Unit,
    onAddApiKey: (AIProvider, String) -> Unit,
    onRemoveApiKey: (AIProvider, String) -> Unit,
    onUpdateApiKey: (AIProvider, String, String) -> Unit,
    onSetProviderEnabled: (AIProvider, Boolean) -> Unit,
    onSetSelectedModel: (AIProvider, String) -> Unit,
    onSetProviderPriority: (List<AIProvider>) -> Unit,
    onTestApiKey: (AIProvider, String, (Boolean) -> Unit) -> Unit,
    onToggleTheme: (Boolean) -> Unit,
    // Tavily Web Search API
    tavilyApiKeys: List<String> = emptyList(),
    onAddTavilyApiKey: (String) -> Unit = {},
    onRemoveTavilyApiKey: (String) -> Unit = {},
    // Embedded Content Slots
    archiveContent: @Composable (() -> Unit) -> Unit,
    backupContent: @Composable (() -> Unit) -> Unit,
    lastBackupTime: Long = 0L,
    cacheSizeBytes: Long = 0L,
    onClearCache: () -> Unit = {},
    isClearingCache: Boolean = false,
    // Google Calendar Sync
    lastCalendarSyncTime: Long = 0L,
    onCalendarSync: () -> Unit = {},
    // Shake sensitivity
    shakeSensitivity: Float = 0.5f,
    onShakeSensitivityChange: (Float) -> Unit = {},
    // GROQ key usage stats
    groqKeyUsageStats: List<KeyUsageStats> = emptyList(),
    // Local LLM Server (USB/WiFi)
    localServerIP: String = "",
    localServerPort: String = "8000",
    localServerUseHttps: Boolean = false,
    onSetLocalServerIP: (String) -> Unit = {},
    onSetLocalServerPort: (String) -> Unit = {},
    onSetLocalServerUseHttps: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
    onRefreshModels: (AIProvider) -> Unit = {},
    getAvailableModels: (AIProvider) -> List<Pair<String, String>> = { AIModels.getModelsForProvider(it) },
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
    onLoadDeviceCalendars: () -> Unit = {}
) {
    var showAIConfigSheet by remember { mutableStateOf(false) }
    val aiConfigSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Bottom Sheets for Sub-settings
    var showArchiveSheet by remember { mutableStateOf(false) }
    var showBackupSheet by remember { mutableStateOf(false) }
    var showAboutSheet by remember { mutableStateOf(false) }  // Newly added state
    var showShakeSensitivitySheet by remember { mutableStateOf(false) }
    var showAIMemorySheet by remember { mutableStateOf(false) }
    var showCalendarSelectorSheet by remember { mutableStateOf(false) }
    val subSettingSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Local LLM connection state
    var localPCTestStatus by remember { mutableStateOf<String?>(null) }
    var isTestingLocalPC by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    val isSystemDark = isSystemInDarkTheme()

    // Intercept system back button
    // If any sheet is open, close it. Otherwise, navigate back.
    androidx.activity.compose.BackHandler(onBack = {
        if (showArchiveSheet || showBackupSheet || showAboutSheet || showShakeSensitivitySheet || showAIConfigSheet || showAIMemorySheet || showCalendarSelectorSheet) {
            showArchiveSheet = false
            showBackupSheet = false
            showAboutSheet = false
            showShakeSensitivitySheet = false
            showAIConfigSheet = false
            showAIMemorySheet = false
            showCalendarSelectorSheet = false
        } else {
            onBackClick()
        }
    })

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        // Grouped section expand states
        var expandedSection by remember { mutableStateOf<String?>("ai") } // AI section open by default

        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // ═══════════════════════════════════════════════════════════════════
            // SECTION 1: AI & VOICE
            // ═══════════════════════════════════════════════════════════════════
            SettingsSection(
                title = stringResource(R.string.ai_voice),
                icon = Icons.Default.Assistant,
                isExpanded = expandedSection == "ai",
                onToggle = { expandedSection = if (expandedSection == "ai") null else "ai" }
            ) {
                SettingsRow(
                    title = stringResource(R.string.ai_providers),
                    icon = Icons.Default.Assistant,
                    subtitle = stringResource(R.string.models_and_api_keys),
                    onClick = { showAIConfigSheet = true },
                    iconColor = LocalAccentColor.current,
                    containerColor = LocalAccentColor.current.copy(alpha = 0.1f)
                )
                SettingsRow(
                    title = stringResource(R.string.ai_memory),
                    icon = Icons.Default.Assistant,
                    subtitle = if (aiMemories.isEmpty()) stringResource(R.string.no_memories) else "${aiMemories.size} ${stringResource(R.string.memories)}",
                    onClick = { showAIMemorySheet = true },
                    iconColor = LocalAccentColor.current,
                    containerColor = LocalAccentColor.current.copy(alpha = 0.1f)
                )
            }

            // ═══════════════════════════════════════════════════════════════════
            // SECTION 2: CALENDAR INTEGRATION
            // ═══════════════════════════════════════════════════════════════════
            SettingsSection(
                title = stringResource(R.string.calendar),
                icon = Icons.Default.Event,
                isExpanded = expandedSection == "calendar",
                onToggle = {
                    expandedSection = if (expandedSection == "calendar") null else "calendar"
                    if (expandedSection == "calendar") onLoadDeviceCalendars()
                }
            ) {
                SettingsToggleRow(
                    title = stringResource(R.string.sync_to_google_calendar),
                    icon = Icons.Default.Sync,
                    isChecked = isCalendarSyncEnabled,
                    onCheckedChange = onSetCalendarSyncEnabled,
                    iconColor = LocalAccentColor.current,
                    containerColor = LocalAccentColor.current.copy(alpha = 0.1f)
                )

                if (isCalendarSyncEnabled) {
                    val selectedCalendar = deviceCalendars.find { it.id == targetCalendarId }
                    SettingsRow(
                        title = stringResource(R.string.default_calendar),
                        icon = Icons.Default.Event,
                        subtitle = selectedCalendar?.displayName?.lowercase() ?: stringResource(R.string.select_calendar),
                        onClick = { showCalendarSelectorSheet = true },
                        iconColor = LocalAccentColor.current,
                        containerColor = LocalAccentColor.current.copy(alpha = 0.1f)
                    )
                }
            }

            // ═══════════════════════════════════════════════════════════════════
            // SECTION 3: DATA
            // ═══════════════════════════════════════════════════════════════════
            SettingsSection(
                title = stringResource(R.string.data_vault),
                icon = Icons.Default.Storage,
                isExpanded = expandedSection == "data",
                onToggle = { expandedSection = if (expandedSection == "data") null else "data" }
            ) {
                SettingsRow(
                    title = stringResource(R.string.backup_sync),
                    icon = Icons.Default.CloudSync,
                    subtitle = if (lastBackupTime > 0) {
                        val sdf = java.text.SimpleDateFormat("MMM dd", java.util.Locale.getDefault())
                        "${stringResource(R.string.last_)} ${sdf.format(java.util.Date(lastBackupTime))}"
                    } else stringResource(R.string.not_backed_up),
                    onClick = { showBackupSheet = true },
                    iconColor = LocalAccentColor.current,
                    containerColor = LocalAccentColor.current.copy(alpha = 0.1f)
                )
                SettingsRow(
                    title = stringResource(R.string.google_calendar_sync),
                    icon = Icons.Default.Sync,
                    subtitle = if (lastCalendarSyncTime > 0) {
                        val sdf = java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault())
                        "${stringResource(R.string.last_sync_)} ${sdf.format(java.util.Date(lastCalendarSyncTime))}"
                    } else stringResource(R.string.not_synced),
                    onClick = onCalendarSync,
                    iconColor = LocalAccentColor.current,
                    containerColor = LocalAccentColor.current.copy(alpha = 0.1f)
                )
                SettingsRow(
                    title = stringResource(R.string.archive),
                    icon = Icons.Default.Archive,
                    subtitle = stringResource(R.string.view_archived_notes),
                    onClick = { showArchiveSheet = true },
                    iconColor = LocalAccentColor.current,
                    containerColor = LocalAccentColor.current.copy(alpha = 0.1f)
                )
                SettingsRow(
                    title = stringResource(R.string.clear_cache),
                    icon = Icons.Filled.DeleteOutline,
                    subtitle = formatCacheSize(cacheSizeBytes),
                    onClick = onClearCache,
                    enabled = !isClearingCache && cacheSizeBytes > 0,
                    iconColor = LocalAccentColor.current,
                    containerColor = LocalAccentColor.current.copy(alpha = 0.1f)
                )
            }

            // ═══════════════════════════════════════════════════════════════════
            // SECTION 4: PREFERENCES
            // ═══════════════════════════════════════════════════════════════════
            SettingsSection(
                title = stringResource(R.string.preferences),
                icon = Icons.Default.Settings,
                isExpanded = expandedSection == "prefs",
                onToggle = { expandedSection = if (expandedSection == "prefs") null else "prefs" }
            ) {
                SettingsRow(
                    title = stringResource(R.string.shake_sensitivity),
                    icon = Icons.Filled.Waves,
                    subtitle = when {
                        shakeSensitivity < 0.3f -> stringResource(R.string.low)
                        shakeSensitivity < 0.7f -> stringResource(R.string.medium)
                        else -> stringResource(R.string.high)
                    },
                    onClick = { showShakeSensitivitySheet = true },
                    iconColor = LocalAccentColor.current,
                    containerColor = LocalAccentColor.current.copy(alpha = 0.1f)
                )
                // Assistant Settings
                val context = LocalContext.current
                SettingsRow(
                    title = stringResource(R.string.default_assistant),
                    icon = Icons.Default.Build,
                    subtitle = stringResource(R.string.set_as_device_assistant),
                    onClick = {
                        try {
                            // Open Android's assistant settings
                            val intent = android.content.Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS)
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            // Fallback to general settings if voice input settings not available
                            try {
                                val intent = android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
                                context.startActivity(intent)
                            } catch (e2: Exception) {
                                android.util.Log.e("Settings", "Could not open settings: ${e2.message}")
                            }
                        }
                    },
                    iconColor = LocalAccentColor.current,
                    containerColor = LocalAccentColor.current.copy(alpha = 0.1f)
                )
            }

            // ═══════════════════════════════════════════════════════════════════
            // SECTION 5: ACCOUNT (No expand - direct actions)
            // ═══════════════════════════════════════════════════════════════════
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                onClick = onSignOut
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.NoAccounts,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = stringResource(R.string.sign_out),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            // ═══════════════════════════════════════════════════════════════════
            // FOOTER
            // ═══════════════════════════════════════════════════════════════════
            Spacer(modifier = Modifier.height(16.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "jarvis v1.1.0",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "made_with_intelligence",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // AI Configuration Bottom Sheet
    if (showAIConfigSheet) {
        AIConfigBottomSheet(
            sheetState = aiConfigSheetState,
            providerConfigs = providerConfigs,
            providerPriorityOrder = providerPriorityOrder,
            onDismiss = { showAIConfigSheet = false },
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
    }

    // Archive Bottom Sheet
    if (showArchiveSheet) {
        ModalBottomSheet(
            onDismissRequest = { showArchiveSheet = false },
            sheetState = subSettingSheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = LocalShapes.current.bottomSheet,
            dragHandle = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .width(32.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    )
                }
            }
        ) {
            HideSystemBars()
            Box(
                modifier = Modifier
                    .fillMaxHeight(0.5f) // Restrict height
                    .fillMaxWidth()
                    .padding(bottom = 32.dp) // Nav bar padding
            ) {
                archiveContent { showArchiveSheet = false }
            }
        }
    }

    // Backup Bottom Sheet
    if (showBackupSheet) {
        ModalBottomSheet(
            onDismissRequest = { showBackupSheet = false },
            sheetState = subSettingSheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = LocalShapes.current.bottomSheet,
            dragHandle = {
                 Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .width(32.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    )
                }
            }
        ) {
            HideSystemBars()
            Box(
                modifier = Modifier
                    .fillMaxHeight(0.5f)
                    .fillMaxWidth()
                    .padding(bottom = 32.dp)
            ) {
                backupContent { showBackupSheet = false }
            }
        }
    }

    // Note: Delete Voice Fingerprint Dialog is now handled inside VoiceFingerprintSheetContent


    // About Bottom Sheet
    if (showAboutSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAboutSheet = false },
            sheetState = subSettingSheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = LocalShapes.current.bottomSheet,
            dragHandle = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .width(32.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    )
                }
            }
        ) {
            HideSystemBars()
            Column(
                modifier = Modifier
                    .fillMaxHeight(0.85f)
                    .fillMaxWidth()
            ) {
                // Fixed Header
                Text(
                    text = "about_jarvis",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 16.dp)
                )

                // Scrollable Content
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 32.dp)
                ) {
                    Text(
                    text =  "hello,_i_am_moudook.\n\n" +
                                "jarvis_is_an_ai-powered_personal_knowledge_management_app._i_made_this_mainly_for_myself_so_i_do_not_get_fussed_managing_my_notes_and_content._the_idea_is_simple._capture_anything,_and_let_the_ai_help_later_with_organizing,_searching,_and_recalling_things_when_needed.\n\n" +
                                "you_can_add_many_types_of_content._text_notes_for_brain_dumps._images._videos,_both_youtube_and_local._documents_like_pdf,_docx,_xlsx,_and_pptx._website_links_with_metadata._audio_files_and_voice_notes._code_snippets._twitter_or_x_posts,_instagram_posts,_apk_files,_and_archive_files.\n\n" +
                                "for_organization,_i_added_basic_but_useful_things._you_can_pin_notes_so_important_ones_stay_on_top._notes_get_smart_categories_automatically._ai_also_generates_tags_for_every_note._if_you_do_not_want_something_but_also_do_not_want_to_delete_it,_you_can_archive_it._there_is_also_bulk_selection_so_you_can_operate_on_many_notes_at_once.\n\n" +
                                "i_also_added_note_versioning._every_note_has_a_git-like_history._up_to_10_versions_are_saved_automatically._you_can_restore_any_older_version_instantly._you_can_also_see_what_changed_and_when_it_changed.\n\n" +
                                "search_was_one_of_the_main_focus_areas.\n\n" +
                                "i_added_full-text_search_using_fts5._it_searches_across_all_notes_instantly._it_uses_bm25_ranking_so_relevant_notes_come_first._it_searches_titles,_content,_and_summaries._it_also_supports_prefix_matching_for_partial_words.\n\n" +
                                "along_with_that,_i_added_semantic_search._it_can_handle_typos._it_uses_fuzzy_matching,_jaro-winkler_similarity,_phonetic_matching_using_soundex,_and_n-gram_token_overlap_so_search_still_works_even_if_you_do_not_type_things_perfectly.\n\n" +
                                "the_ai_assistant_is_agentic.\n\n" +
                                "it_can_search_and_retrieve_notes_based_on_context._it_can_create_new_notes_directly_from_conversation._it_can_edit_and_update_existing_notes._it_can_delete_notes_when_asked._it_can_manage_to-do_lists_inside_notes._it_can_also_set_smart_reminders_on_cards.\n\n" +
                                "every_ai_response_shows_citations._you_can_tap_a_citation_and_jump_directly_to_the_source_note._so_you_always_know_where_the_information_is_coming_from.\n\n" +
                                "i_also_added_smart_reminders._ai_can_highlight_important_notes._a_shimmer_animation_is_used_to_draw_attention._you_can_also_set_expiration_on_reminders_so_temporary_things_do_not_stay_forever.\n\n" +
                                "for_the_agentic_ai,_i_use_koog_by_jetbrains._koog_stands_for_kotlin_object-oriented_graphs._it_lets_the_ai_work_with_structured_tool_calls_and_function_execution._the_ai_can_decide_which_tools_to_use_and_chain_them_together._this_is_what_makes_the_assistant_truly_agentic_instead_of_just_a_chatbot._koog_handles_the_execution_graph_and_makes_sure_everything_runs_in_the_right_order.\n\n" +
                                "multiple_ai_providers_are_supported._google_gemini_is_the_default._i_also_added_support_for_openai,_anthropic,_groq,_deepseek,_cerebras,_cohere,_openrouter,_and_huggingface._you_can_manage_multiple_api_keys_per_provider._you_can_reorder_provider_priority_by_drag_and_drop._automatic_key_rotation_and_rate_limit_handling_are_also_there._you_can_choose_models_per_provider.\n\n" +
                                "for_web_search,_i_integrated_tavily._it_gives_real-time_information._there_are_1000_free_requests_per_month._ai_summarizes_the_results_so_you_do_not_need_to_read_everything_manually.\n\n" +
                                "i_also_added_a_daily_digest._it_sends_a_notification_at_6:30_am._it_summarizes_what_happened_in_the_last_24_hours._new_notes_and_important_updates_show_up_there.\n\n" +
                                "voice_and_audio_were_also_important.\n\n" +
                                "you_can_record_voice_notes_instantly_by_tapping_the_mic._there_is_a_real-time_amplitude_visualizer._audio_is_saved_in_m4a_format._you_can_record_up_to_10_minutes._you_always_get_a_confirmation_before_saving_or_canceling.\n\n" +
                                "speech-to-text_uses_google_speech_recognition._a_halftone_shimmer_shows_when_it_is_actively_listening._continuous_recognition_mode_is_also_supported.\n\n" +
                                "the_music_player_has_a_living_orb_visualizer._it_reacts_to_audio_amplitude_and_frequency_bands._there_is_a_mini_player_and_a_full-screen_mode._i_tried_to_make_it_feel_alive.\n\n" +
                                "calendar_support_is_also_there.\n\n" +
                                "you_can_import_events_from_google_calendar._exchange_and_other_providers_are_supported._it_syncs_past_30_days_and_next_90_days._events_update_automatically.\n\n" +
                                "ai_can_access_your_calendar,_but_only_non-private_events._it_can_create_events_from_conversation._you_can_also_link_notes_to_calendar_events._private_events_stay_hidden_from_ai.\n\n" +
                                "privacy_was_non-negotiable.\n\n" +
                                "there_are_multiple_privacy_modes._full_privacy_means_ai_cannot_see_the_note_at_all._exclude_from_chat_means_the_note_stays_hidden_from_ai_context._private_calendar_events_are_also_invisible_to_ai.\n\n" +
                                "i_added_shake-to-private_mode._you_can_shake_the_device_to_toggle_privacy._sensitivity_can_be_adjusted._there_is_visual_feedback_so_you_know_when_the_mode_changes.\n\n" +
                                "i_also_added_prompt_injection_protection._content_is_sanitized_across_multiple_languages._this_runs_on_device._it_prevents_malicious_note_content_from_affecting_the_ai.\n\n" +
                                "all_data_is_stored_locally_on_the_device._i_use_a_room_database_for_persistence._api_keys_are_stored_using_android's_encryptedsharedpreferences._only_api_calls_go_out_of_the_device.\n\n" +
                                "for_user_experience,_i_added_widgets._you_can_capture_notes_directly_from_the_home_screen._one_tap_and_you_are_inside_a_new_note.\n\n" +
                                "app_shortcuts_are_also_there._long_press_the_app_icon_for_quick_actions_like_new_note,_search,_or_voice_note._recent_notes_also_show_up_dynamically.\n\n" +
                                "sharing_is_deeply_integrated._you_can_share_content_from_any_app_into_jarvis._content_type_is_detected_automatically._url_metadata_is_extracted._you_can_also_bulk_select_and_share_multiple_notes.\n\n" +
                                "for_ui,_i_avoided_popups._i_use_slide-in_panels_instead._animations_are_smooth_and_spring-based._entry_animations_are_staggered._dark_and_light_themes_are_supported._you_can_also_customize_accent_colors.\n\n" +
                                "there_are_many_animations._cloud-like_startup_animation._living_orb_on_the_main_screen._chat_personality_animations._shimmer_effects_for_reminders._halftone_indicator_for_speech.\n\n" +
                                "performance_was_a_big_focus.\n\n" +
                                "i_optimized_memory_usage_using_shared_http_clients._resources_are_cleaned_automatically._coroutines_are_scoped_properly_so_there_are_no_leaks._cache_size_is_managed.\n\n" +
                                "cpu_usage_is_optimized_by_merging_animation_transitions,_using_derivedstateof_where_needed,_pre-compiling_regex_patterns,_early_terminating_searches,_and_using_fast_math_approximations.\n\n" +
                                "the_database_uses_room_with_sqlite._queries_are_indexed._fts5_is_used_for_search._migrations_are_automatic._paging3_is_used_for_infinite_scrolling.\n\n" +
                                "the_app_is_built_to_work_on_edge_and_low-end_devices._rendering_quality_adapts._memory_usage_stays_low._background_work_is_battery_conscious.\n\n" +
                                "backup_is_simple.\n\n" +
                                "you_can_export_a_full_zip_backup._it_contains_notes,_categories,_settings,_chat_history,_ai_memories,_calendar_events,_and_attachments._you_can_restore_anytime._you_can_choose_to_merge_or_replace_data._integrity_is_preserved.\n\n" +
                                "the_app_is_built_using_kotlin_and_jetpack_compose._it_follows_material_design_3._room_database_version_is_19._min_sdk_is_26._target_sdk_is_36._workmanager_is_used_for_background_tasks._okhttp_handles_networking._exoplayer_handles_media.\n\n" +
                                "your_data_stays_on_your_device._only_ai_and_speech_recognition_apis_need_internet.\n\n" +
                                "i_am_still_working_on_the_ui_and_the_agentic_part._if_you_find_any_issues,_please_mention_them_on_github.\n\n" +
                                "thank_you_for_using_jarvis.",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            lineHeight = androidx.compose.ui.unit.TextUnit(24f, androidx.compose.ui.unit.TextUnitType.Sp)
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }

    // Shake Sensitivity Bottom Sheet
    if (showShakeSensitivitySheet) {
        val accentColor = LocalAccentColor.current
        
        ModalBottomSheet(
            onDismissRequest = { showShakeSensitivitySheet = false },
            sheetState = subSettingSheetState,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = LocalShapes.current.bottomSheet,
            dragHandle = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .width(32.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    )
                }
            }
        ) {
            HideSystemBars()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Modern Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "shake_gesture",
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                letterSpacing = (-0.5).sp
                            ),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "configure_chat_mode_activation",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                        )
                    }
                    
                    Surface(
                        shape = CircleShape,
                        color = accentColor.copy(alpha = 0.1f),
                        modifier = Modifier.size(48.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Sensors,
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }

                // Main Card with Control - matching notecard aesthetic
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    elevation = CardDefaults.cardElevation(0.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        ShakeSensitivityControl(
                            sensitivity = shakeSensitivity,
                            onSensitivityChange = onShakeSensitivityChange
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Labels row matching notecard style
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(horizontalAlignment = Alignment.Start) {
                        Text(
                            text = "low",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "requires_stronger_shake",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "high",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "light_shake_triggers",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    }

    // AI Memory Bottom Sheet
    if (showAIMemorySheet) {
        ModalBottomSheet(
            onDismissRequest = { showAIMemorySheet = false },
            sheetState = subSettingSheetState,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = LocalShapes.current.bottomSheet,
            dragHandle = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .width(32.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    )
                }
            }
        ) {
            HideSystemBars()
            Box(
                modifier = Modifier
                    .fillMaxHeight(0.7f)
                    .fillMaxWidth()
                    .padding(bottom = 32.dp)
            ) {
                com.example.smarty.ui.screens.settings.AIMemorySettingsContent(
                    memories = aiMemories,
                    onDeleteMemory = onDeleteAIMemory,
                    onClearAllMemories = onClearAllAIMemories,
                    onDismiss = { showAIMemorySheet = false },
                    // Sync functionality
                    onSyncMemories = onSyncAIMemories,
                    isSyncing = isMemorySyncInProgress,
                    syncResult = memorySyncResult,
                    unreadNotesCount = unreadForMemoryCount,
                    onClearSyncResult = onClearMemorySyncResult
                )
            }
        }
    }

    // Calendar Selector Bottom Sheet
    if (showCalendarSelectorSheet) {
        ModalBottomSheet(
            onDismissRequest = { showCalendarSelectorSheet = false },
            sheetState = subSettingSheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = LocalShapes.current.bottomSheet,
            dragHandle = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .width(32.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    )
                }
            }
        ) {
            HideSystemBars()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 48.dp)
            ) {
                Text(
                    text = "select_default_calendar",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(deviceCalendars.size) { index ->
                        val calendar = deviceCalendars[index]
                        val isSelected = calendar.id == targetCalendarId

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSetTargetCalendarId(calendar.id)
                                    showCalendarSelectorSheet = false
                                },
                            shape = RoundedCornerShape(16.dp),
                            color = if (isSelected) LocalAccentColor.current.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            border = if (isSelected) BorderStroke(1.dp, LocalAccentColor.current) else null
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(calendar.color?.let { Color(it) } ?: Color.Gray)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = calendar.displayName,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = if (isSelected) LocalAccentColor.current else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = calendar.accountName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "selected",
                                        tint = LocalAccentColor.current,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                if (deviceCalendars.isEmpty()) {
                    com.example.smarty.ui.components.CompactEmptyState(
                        title = "calendars",
                        subtitle = "no_calendars_found_or_permission_denied",
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}



// Redundant SettingsGroup removed

@Composable
private fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconColor: Color = LocalAccentColor.current,
    isDestructive: Boolean = false,
    isLoading: Boolean = false,
    enabled: Boolean = true,
    showArrow: Boolean = true
) {
    // Matching CategoryCard style
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon
            Box(
                 modifier = Modifier
                     .size(40.dp)
                     .clip(CircleShape)
                     .background(iconColor.copy(alpha = 0.15f)),
                 contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Text
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                    color = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }

            // Action
            if (isLoading) {
                com.example.smarty.ui.components.CalmThinkingDots(
                    dotSize = 3.dp
                )
            } else if (showArrow) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.3f),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

/**
 * Settings item with a toggle switch instead of arrow
 */
@Composable
private fun SettingsToggleItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    iconColor: Color = LocalAccentColor.current,
    enabled: Boolean = true
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .clickable(enabled = enabled) { onCheckedChange(!isChecked) },
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(iconColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Text
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }

            // Toggle Switch
            Switch(
                checked = isChecked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = iconColor,
                    checkedTrackColor = iconColor.copy(alpha = 0.3f)
                )
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AIConfigBottomSheet(
    sheetState: SheetState,
    providerConfigs: Map<AIProvider, AIProviderConfig>,
    providerPriorityOrder: List<AIProvider>,
    onDismiss: () -> Unit,
    onAddApiKey: (AIProvider, String) -> Unit,
    onRemoveApiKey: (AIProvider, String) -> Unit,
    onUpdateApiKey: (AIProvider, String, String) -> Unit,
    onSetProviderEnabled: (AIProvider, Boolean) -> Unit,
    onSetSelectedModel: (AIProvider, String) -> Unit,
    onSetProviderPriority: (List<AIProvider>) -> Unit,
    onTestApiKey: (AIProvider, String, (Boolean) -> Unit) -> Unit,
    tavilyApiKeys: List<String> = emptyList(),
    onAddTavilyApiKey: (String) -> Unit = {},
    onRemoveTavilyApiKey: (String) -> Unit = {},
    groqKeyUsageStats: List<KeyUsageStats> = emptyList(),
    onRefreshModels: (AIProvider) -> Unit,
    getAvailableModels: (AIProvider) -> List<Pair<String, String>>,
    localServerIP: String = "",
    localServerPort: String = "8000",
    localServerUseHttps: Boolean = false,
    onSetLocalServerIP: (String) -> Unit = {},
    onSetLocalServerPort: (String) -> Unit = {},
    onSetLocalServerUseHttps: (Boolean) -> Unit = {}
) {
    val shapes = LocalShapes.current

    // Local state for drag-and-drop reordering
    // Filter out LOCAL_PC - it has its own section and doesn't need API keys
    var localProviderOrder by remember {
        mutableStateOf(providerPriorityOrder.filter { it != AIProvider.LOCAL_PC })
    }
    var draggedItemIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }

    // Sync with external state when it changes
    LaunchedEffect(providerPriorityOrder) {
        localProviderOrder = providerPriorityOrder.filter { it != AIProvider.LOCAL_PC }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = shapes.bottomSheet,
        containerColor = MaterialTheme.colorScheme.background // Contrast with Surface cards
    ) {
        HideSystemBars()
        Column(
            modifier = Modifier
                .fillMaxHeight(0.92f) // Taller sheet
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            // Header
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 24.dp)
            ) {
                 Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "ai_intelligence",
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                letterSpacing = (-0.5).sp
                            ),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "configure_ai_providers_and_models",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                        )
                    }

                    // Optional: Add a "Done" button or icon if needed, but the drag handle is standard.
                    // Could add a small icon/illustration here.
                    Surface(
                         shape = androidx.compose.foundation.shape.CircleShape,
                         color = LocalAccentColor.current.copy(alpha = 0.1f),
                         modifier = Modifier.size(48.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Assistant, // AI Icon
                                contentDescription = null,
                                tint = LocalAccentColor.current,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }
            
            HorizontalDivider(
                modifier = Modifier.padding(bottom = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp) // Less horizontal padding so cards are wider
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Intro/Help text moved to just a small hint if needed, or removed as redundant.
                // Keeping the drag hint but making it subtle.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 8.dp, start = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DragIndicator,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                     Text(
                        text = "drag_providers_to_reorder_priority",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                    )
                }

                val providerInfo = mapOf(
                    AIProvider.GEMINI to Triple("gemini", "google's_fastest_ai", "https://aistudio.google.com/apikey"),
                    AIProvider.DEEPSEEK to Triple("deepseek", "cost-effective", "https://platform.deepseek.com"),
                    AIProvider.GROQ to Triple("groq", "ultra-fast", "https://console.groq.com"),
                    AIProvider.CEREBRAS to Triple("cerebras", "2000+_tokens/sec", "https://cloud.cerebras.ai"),
                    AIProvider.COHERE to Triple("cohere", "command_models", "https://dashboard.cohere.com/api-keys"),
                    AIProvider.OPENAI to Triple("openai", "gpt-4o", "https://platform.openai.com/api-keys"),
                    AIProvider.ANTHROPIC to Triple("anthropic", "claude_models", "https://console.anthropic.com/settings/keys"),
                    AIProvider.OPENROUTER to Triple("openrouter", "multi-model", "https://openrouter.ai/keys"),
                    AIProvider.HUGGINGFACE to Triple("huggingface", "open_source", "https://huggingface.co/settings/tokens"),
                    AIProvider.GITHUB to Triple("github_models", "free_with_github", "https://github.com/settings/tokens"),
                    AIProvider.LOCAL_PC to Triple("local_llm", "run_ai_locally", "")
                )

            // Iterate through providers with drag-and-drop reordering
            localProviderOrder.forEachIndexed { index, provider ->
                val (name, description, _) = providerInfo[provider] ?: Triple("unknown", "", "")
                val config = providerConfigs[provider]
                val isDragging = draggedItemIndex == index

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            if (isDragging) {
                                translationY = dragOffsetY
                                scaleX = 1.02f
                                scaleY = 1.02f
                            }
                        }
                        .zIndex(if (isDragging) 1f else 0f)
                        .then(
                            if (isDragging) Modifier.shadow(8.dp, RoundedCornerShape(12.dp))
                            else Modifier
                        )
                        .background(
                            if (isDragging) MaterialTheme.colorScheme.surfaceContainerHigh
                            else Color.Transparent,
                            RoundedCornerShape(12.dp)
                        )
                ) {
                    // Drag Handle with gesture detection
                    Box(
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .pointerInput(index) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        draggedItemIndex = index
                                        dragOffsetY = 0f
                                    },
                                    onDragEnd = {
                                        // Calculate target position based on drag offset
                                        // Use a more accurate height calculation - items are likely around 80-100dp
                                        val itemHeight = 90f // Better estimate of item height in pixels
                                        val moveBy = (dragOffsetY / itemHeight).toInt()

                                        // Calculate the target index based on how many positions the item was moved
                                        val targetIndex = (index + moveBy).coerceIn(0, localProviderOrder.size - 1)

                                        if (targetIndex != index) {
                                            val newList = localProviderOrder.toMutableList()
                                            val item = newList.removeAt(index)
                                            newList.add(targetIndex, item)
                                            localProviderOrder = newList
                                            onSetProviderPriority(newList)
                                        }

                                        draggedItemIndex = null
                                        dragOffsetY = 0f
                                    },
                                    onDragCancel = {
                                        draggedItemIndex = null
                                        dragOffsetY = 0f
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        dragOffsetY += dragAmount.y
                                    }
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Drag indicator icon
                            Icon(
                                imageVector = Icons.Default.DragHandle,
                                contentDescription = "drag_to_reorder",
                                tint = if (isDragging) LocalAccentColor.current
                                       else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(24.dp)
                            )
                            // Priority number badge
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isDragging) LocalAccentColor.current
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Box(modifier = Modifier.weight(1f)) {
                        // Build usage stats map for GROQ keys
                        val keyUsageStatsMap = if (provider == AIProvider.GROQ) {
                            groqKeyUsageStats.associateBy { it.key }
                        } else {
                            emptyMap()
                        }

                        ProviderSection(
                            provider = provider,
                            providerName = name,
                            providerDescription = description,
                            apiKeys = config?.apiKeys ?: emptyList(),
                            isEnabled = config?.isEnabled ?: true,
                            selectedModel = config?.selectedModel ?: AIModels.getDefaultModel(provider),
                            availableModels = getAvailableModels(provider),
                            onAddKey = { onAddApiKey(provider, it) },
                            onRemoveKey = { onRemoveApiKey(provider, it) },
                            onUpdateKey = { old, new -> onUpdateApiKey(provider, old, new) },
                            onToggleEnabled = { onSetProviderEnabled(provider, it) },
                            onSelectModel = { onSetSelectedModel(provider, it) },
                            onTestKey = { key, callback -> onTestApiKey(provider, key, callback) },
                            keyUsageStats = keyUsageStatsMap,
                            onRefreshModels = if (provider == AIProvider.GROQ) { { onRefreshModels(provider) } } else null
                        )
                    }
                }

                if (index < localProviderOrder.size - 1) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 12.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Tavily Web Search Section
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
            )

            TavilyApiSection(
                apiKeys = tavilyApiKeys,
                onAddKey = onAddTavilyApiKey,
                onRemoveKey = onRemoveTavilyApiKey
            )

            // Local LLM Server Section
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
            )

            LocalServerSection(
                serverIP = localServerIP,
                serverPort = localServerPort,
                useHttps = localServerUseHttps,
                onSetServerIP = onSetLocalServerIP,
                onSetServerPort = onSetLocalServerPort,
                onSetUseHttps = onSetLocalServerUseHttps
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Status
            val hasAnyKeys = providerConfigs.values.any { it.apiKeys.isNotEmpty() }
            val configuredCount = providerConfigs.values.count { it.apiKeys.isNotEmpty() }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                Icon(
                    imageVector = if (hasAnyKeys) Icons.Default.Verified else Icons.Default.ReportProblem,
                    contentDescription = null,
                    tint = if (hasAnyKeys) LocalAccentColor.current else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (hasAnyKeys) "$configuredCount provider${if (configuredCount > 1) "s" else ""} configured" else "no_api_keys_-_using_demo_mode",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (hasAnyKeys) LocalAccentColor.current else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
}

/**
 * Tavily Web Search API configuration section.
 * Separate from AI providers as it's a search tool, not a chat model.
 */
@Composable
private fun TavilyApiSection(
    apiKeys: List<String>,
    onAddKey: (String) -> Unit,
    onRemoveKey: (String) -> Unit
) {
    var showKeyInput by remember { mutableStateOf(false) }
    var keyInput by remember { mutableStateOf("") }
    // Map to track visibility state for each key
    val showKeyMap = remember { mutableStateMapOf<String, Boolean>() }
    var showKeyWhileTyping by remember { mutableStateOf(false) }  // For masking input
    var isExpanded by remember { mutableStateOf(false) }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { isExpanded = !isExpanded }
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "collapse" else "expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp).padding(end = 8.dp)
                )

                Column {
                    Text(
                        text = "tavily_web_search",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (apiKeys.isNotEmpty()) LocalAccentColor.current else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "enable_ai_web_search_capabilities",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Status indicator
            if (apiKeys.isNotEmpty()) {
                Icon(
                    imageVector = Icons.Default.Verified,
                    contentDescription = "configured",
                    tint = LocalAccentColor.current,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Expanded content
        androidx.compose.animation.AnimatedVisibility(
            visible = isExpanded,
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically()
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Info text
                Text(
                    text = "tavily_provides_real-time_web_search_for_ai_get_your_free_api_key_from_tavily_com",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )

                // List of keys
                if (apiKeys.isNotEmpty()) {
                    apiKeys.forEach { key ->
                        val showKey = showKeyMap[key] ?: false
                        
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(
                                    1.dp,
                                    LocalAccentColor.current,
                                    RoundedCornerShape(ComponentSpacing.inputCornerRadius)
                                ),
                            shape = RoundedCornerShape(ComponentSpacing.inputCornerRadius),
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (showKey) key else maskApiKey(key),
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = com.example.smarty.ui.theme.MonoFont
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )

                                IconButton(onClick = { showKeyMap[key] = !showKey }) {
                                    Icon(
                                        imageVector = if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (showKey) "hide" else "show",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                IconButton(onClick = { onRemoveKey(key) }) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = "remove",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                
                // Add new key input
                if (showKeyInput) {
                    // Input for new key
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                1.dp,
                                LocalAccentColor.current,
                                RoundedCornerShape(ComponentSpacing.inputCornerRadius)
                            ),
                        shape = RoundedCornerShape(ComponentSpacing.inputCornerRadius),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(modifier = Modifier.weight(1f)) {
                                    androidx.compose.foundation.text.BasicTextField(
                                        value = keyInput,
                                        onValueChange = { keyInput = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        textStyle = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = com.example.smarty.ui.theme.MonoFont,
                                            color = MaterialTheme.colorScheme.onSurface
                                        ),
                                        cursorBrush = androidx.compose.ui.graphics.SolidColor(LocalAccentColor.current),
                                        singleLine = true,
                                        // Mask the key while typing for security
                                        visualTransformation = if (showKeyWhileTyping) {
                                            androidx.compose.ui.text.input.VisualTransformation.None
                                        } else {
                                            androidx.compose.ui.text.input.PasswordVisualTransformation()
                                        }
                                    )
                                    if (keyInput.isEmpty()) {
                                        Text(
                                            text = "tvly-xxxxx",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = com.example.smarty.ui.theme.MonoFont
                                            ),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                        )
                                    }
                                }
                                // Toggle to show/hide key while typing
                                IconButton(
                                    onClick = { showKeyWhileTyping = !showKeyWhileTyping },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = if (showKeyWhileTyping) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (showKeyWhileTyping) "hide_key" else "show_key",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = {
                                    showKeyInput = false
                                    keyInput = ""
                                }) {
                                    Text("cancel")
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        if (keyInput.isNotBlank()) {
                                            onAddKey(keyInput.trim())
                                            keyInput = ""
                                            showKeyInput = false
                                        }
                                    },
                                    enabled = keyInput.isNotBlank()
                                ) {
                                    Text("add_key")
                                }
                            }
                        }
                    }
                } else {
                    // Add Key Button
                    OutlinedButton(
                        onClick = { showKeyInput = true },
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Assistant,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("add_tavily_api_key")
                    }
                }
            }
        }
    }
}

/**
 * Result of testing connection to local LLM server
 */
private sealed class TestResult {
    data object Success : TestResult()
    data class Failure(val message: String) : TestResult()
}

/**
 * Test connection to local LLM server by pinging the health endpoint
 * Uses OkHttp for reliable SSL handling with self-signed certificates
 */
private suspend fun testLocalServer(ip: String, port: String, useHttps: Boolean): TestResult {
    return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val protocol = if (useHttps) "https" else "http"
            val testUrl = "$protocol://$ip:$port/health"
            
            // Build appropriate OkHttp client
            val client = if (useHttps) {
                // Create trust-all SSL configuration for self-signed certs
                val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(
                    object : javax.net.ssl.X509TrustManager {
                        @Throws(java.security.cert.CertificateException::class)
                        override fun checkClientTrusted(
                            chain: Array<java.security.cert.X509Certificate>,
                            authType: String
                        ) {
                            // Trust all client certs
                        }

                        @Throws(java.security.cert.CertificateException::class)
                        override fun checkServerTrusted(
                            chain: Array<java.security.cert.X509Certificate>,
                            authType: String
                        ) {
                            // Trust all server certs - ONLY safe for local LAN!
                        }

                        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                    }
                )

                // Use TLSv1.2 and TLSv1.3 for maximum compatibility
                val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
                sslContext.init(null, trustAllCerts, java.security.SecureRandom())
                
                // Create socket factory that enables all TLS versions
                val sslSocketFactory = sslContext.socketFactory

                okhttp3.OkHttpClient.Builder()
                    .sslSocketFactory(sslSocketFactory, trustAllCerts[0] as javax.net.ssl.X509TrustManager)
                    .hostnameVerifier { _, _ -> true } // Accept any hostname
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
            } else {
                // Plain HTTP - no SSL needed
                okhttp3.OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
            }

            val request = okhttp3.Request.Builder()
                .url(testUrl)
                .get()
                .build()

            val response = client.newCall(request).execute()
            val code = response.code
            response.close()

            // Any response means server is reachable
            if (code in 200..299 || code in 400..499) {
                TestResult.Success
            } else {
                TestResult.Failure("server_returned:_$code")
            }
        } catch (e: java.net.ConnectException) {
            TestResult.Failure("connection_refused_-_is_server_running?")
        } catch (e: java.net.SocketTimeoutException) {
            TestResult.Failure("timeout_-_check_ip_and_firewall")
        } catch (e: java.net.UnknownHostException) {
            TestResult.Failure("invalid_ip_address")
        } catch (e: javax.net.ssl.SSLHandshakeException) {
            // Log the actual error for debugging
            android.util.Log.e("LocalServerTest", "SSL Handshake failed", e)
            TestResult.Failure("ssl_failed_-_ensure_caddy_is_running")
        } catch (e: javax.net.ssl.SSLException) {
            android.util.Log.e("LocalServerTest", "SSL Exception", e)
            TestResult.Failure("ssl_error_-_check_port_(8443_for_https)")
        } catch (e: Exception) {
            android.util.Log.e("LocalServerTest", "connection_error", e)
            val msg = e.message?.lowercase() ?: ""
            when {
                msg.contains("ssl") || msg.contains("tls") -> 
                    TestResult.Failure("ssl/tls_error_-_try_http_mode")
                msg.contains("certificate") -> 
                    TestResult.Failure("cert_error_-_is_caddy_running?")
                msg.contains("reset") || msg.contains("closed") ->
                    TestResult.Failure("connection_reset_-_wrong_port?")
                else -> 
                    TestResult.Failure("error:_${e.message?.take(50) ?: "unknown"}")
            }
        }
    }
}

/**
 * Local LLM Server configuration section.
 * Allows users to connect to local AI server via USB tethering or WiFi.
 * Supports both HTTP (default) and HTTPS (encrypted) connections.
 */
@Composable
private fun LocalServerSection(
    serverIP: String,
    serverPort: String,
    useHttps: Boolean,
    onSetServerIP: (String) -> Unit,
    onSetServerPort: (String) -> Unit,
    onSetUseHttps: (Boolean) -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }
    var ipInput by remember { mutableStateOf(serverIP) }
    var portInput by remember { mutableStateOf(serverPort.ifBlank { "8000" }) }
    var httpsEnabled by remember { mutableStateOf(useHttps) }
    var isEditing by remember { mutableStateOf(false) }
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<TestResult?>(null) }
    val scope = rememberCoroutineScope()

    // Detect connection type based on IP range
    val connectionType = when {
        ipInput.startsWith("10.") -> "usb/wifi"
        ipInput.startsWith("192.168.") -> "wifi"
        ipInput.startsWith("172.") -> "ethernet"
        else -> "network"
    }

    // Sync with external state
    LaunchedEffect(serverIP, serverPort, useHttps) {
        if (!isEditing) {
            ipInput = serverIP
            portInput = serverPort.ifBlank { "8000" }
            httpsEnabled = useHttps
        }
    }

    // Clear test result after 5 seconds
    LaunchedEffect(testResult) {
        if (testResult != null) {
            kotlinx.coroutines.delay(5000)
            testResult = null
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { isExpanded = !isExpanded }
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "collapse" else "expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp).padding(end = 8.dp)
                )

                Column {
                    Text(
                        text = "local_llm_server",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (serverIP.isNotBlank()) LocalAccentColor.current else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "connect_via_usb_tethering_or_wifi",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Status indicator with connection type
            if (serverIP.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = connectionType,
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalAccentColor.current
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.Lan,
                        contentDescription = "configured",
                        tint = LocalAccentColor.current,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // Expanded content
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically() + androidx.compose.animation.fadeIn(),
            exit = shrinkVertically() + androidx.compose.animation.fadeOut()
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Info text
                Text(
                    text = "connect_to_your_local_llm_server_use_usb_ip_for_usb_tethering_or_wifi_ip_for_wireless",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )

                // IP and Port Input Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // IP Input
                    Surface(
                        modifier = Modifier
                            .weight(2f)
                            .border(
                                1.dp,
                                if (ipInput.isNotBlank()) LocalAccentColor.current else MaterialTheme.colorScheme.outline,
                                RoundedCornerShape(ComponentSpacing.inputCornerRadius)
                            ),
                        shape = RoundedCornerShape(ComponentSpacing.inputCornerRadius),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "ip_address",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Box {
                                androidx.compose.foundation.text.BasicTextField(
                                    value = ipInput,
                                    onValueChange = {
                                        ipInput = it
                                        isEditing = true
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    textStyle = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = com.example.smarty.ui.theme.MonoFont,
                                        color = MaterialTheme.colorScheme.onSurface
                                    ),
                                    cursorBrush = androidx.compose.ui.graphics.SolidColor(LocalAccentColor.current),
                                    singleLine = true
                                )
                                if (ipInput.isEmpty()) {
                                    Text(
                                        text = "192.168.1.100",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = com.example.smarty.ui.theme.MonoFont
                                        ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    )
                                }
                            }
                        }
                    }

                    // Port Input
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .border(
                                1.dp,
                                if (portInput.isNotBlank()) LocalAccentColor.current else MaterialTheme.colorScheme.outline,
                                RoundedCornerShape(ComponentSpacing.inputCornerRadius)
                            ),
                        shape = RoundedCornerShape(ComponentSpacing.inputCornerRadius),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "port",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Box {
                                androidx.compose.foundation.text.BasicTextField(
                                    value = portInput,
                                    onValueChange = { newValue ->
                                        // Only allow numeric input
                                        if (newValue.all { it.isDigit() }) {
                                            portInput = newValue
                                            isEditing = true
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    textStyle = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = com.example.smarty.ui.theme.MonoFont,
                                        color = MaterialTheme.colorScheme.onSurface
                                    ),
                                    cursorBrush = androidx.compose.ui.graphics.SolidColor(LocalAccentColor.current),
                                    singleLine = true
                                )
                                if (portInput.isEmpty()) {
                                    Text(
                                        text = "8000",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = com.example.smarty.ui.theme.MonoFont
                                        ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    )
                                }
                            }
                        }
                    }
                }

                // HTTPS Toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "use_https_(encrypted)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (httpsEnabled) "traffic_is_encrypted" else "traffic_is_unencrypted",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (httpsEnabled) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                    Switch(
                        checked = httpsEnabled,
                        onCheckedChange = {
                            httpsEnabled = it
                            isEditing = true
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.surface,
                            checkedTrackColor = LocalAccentColor.current,
                            uncheckedThumbColor = MaterialTheme.colorScheme.surface,
                            uncheckedTrackColor = MaterialTheme.colorScheme.outlineVariant
                        )
                    )
                }

                // Test result message
                AnimatedVisibility(
                    visible = testResult != null || isTesting,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        when {
                            isTesting -> {
                                com.example.smarty.ui.components.CalmThinkingDots(
                                    color = LocalAccentColor.current,
                                    dotSize = 3.dp
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "testing_connection",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            testResult is TestResult.Success -> {
                                Icon(
                                    imageVector = Icons.Default.Verified,
                                    contentDescription = "success",
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "connection_successful_settings_saved",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                            testResult is TestResult.Failure -> {
                                Icon(
                                    imageVector = Icons.Default.ReportProblem,
                                    contentDescription = "failed",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = (testResult as TestResult.Failure).message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (serverIP.isNotBlank()) {
                        TextButton(
                            onClick = {
                                onSetServerIP("")
                                onSetServerPort("8000")
                                ipInput = ""
                                portInput = "8000"
                                isEditing = false
                                testResult = null
                            },
                            enabled = !isTesting
                        ) {
                            Text("clear", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (ipInput.isNotBlank()) {
                                scope.launch {
                                    isTesting = true
                                    testResult = null
                                    val port = portInput.ifBlank { "8000" }
                                    val result = testLocalServer(ipInput.trim(), port, httpsEnabled)
                                    isTesting = false
                                    testResult = result

                                    if (result is TestResult.Success) {
                                        onSetServerIP(ipInput.trim())
                                        onSetServerPort(port)
                                        onSetUseHttps(httpsEnabled)
                                        isEditing = false
                                    }
                                }
                            }
                        },
                        enabled = ipInput.isNotBlank() && !isTesting,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LocalAccentColor.current,
                            contentColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        if (isTesting) {
                            com.example.smarty.ui.components.CalmThinkingDots(
                                color = MaterialTheme.colorScheme.surface,
                                dotSize = 3.dp
                            )
                        } else {
                            Text("test_and_save")
                        }
                    }
                }

                // Current URL display if configured
                if (serverIP.isNotBlank()) {
                    val displayPort = serverPort.ifBlank { "8000" }
                    Text(
                        text = "URL: http://$serverIP:$displayPort/v1/chat/completions",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = com.example.smarty.ui.theme.MonoFont
                        ),
                        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// CENTRALIZED SETTINGS COMPONENTS - Minimal, Grouped, Expandable
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Collapsible settings section with header and expandable content.
 * Only shows items when expanded - reduces visual clutter.
 */
@Composable
private fun SettingsSection(
    title: String,
    icon: ImageVector,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val accentColor = LocalAccentColor.current // Use dynamic accent for settings sections
    val rotationAngle by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = tween(200),
        label = "rotation"
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = if (isExpanded)
            accentColor.copy(alpha = 0.08f)
        else
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isExpanded) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = if (isExpanded) androidx.compose.ui.text.font.FontWeight.SemiBold
                        else androidx.compose.ui.text.font.FontWeight.Medium
                    ),
                    color = if (isExpanded) accentColor else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "collapse" else "expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier
                        .size(24.dp)
                        .graphicsLayer { rotationZ = rotationAngle }
                )
            }

            // Expandable content
            androidx.compose.animation.AnimatedVisibility(
                visible = isExpanded,
                enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    content()
                }
            }
        }
    }
}

/**
 * Simple settings row inside a section - minimal, tappable.
 */
@Composable
private fun SettingsRow(
    title: String,
    icon: ImageVector,
    subtitle: String? = null,
    onClick: () -> Unit,
    enabled: Boolean = true,
    isDestructive: Boolean = false,
    iconColor: Color = LocalAccentColor.current,
    containerColor: Color = Color.Transparent
) {
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        isDestructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent,
        onClick = onClick,
        enabled = enabled
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // Leading Icon with Squircle shape
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (isDestructive) MaterialTheme.colorScheme.error.copy(alpha = 0.1f)
                            else if (containerColor != Color.Transparent) containerColor
                            else iconColor.copy(alpha = 0.1f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isDestructive) MaterialTheme.colorScheme.error else iconColor,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.Medium
                        ),
                        color = contentColor
                    )
                    subtitle?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                alpha = if (enabled) 0.7f else 0.4f
                            )
                        )
                    }
                }
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.TrendingFlat,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * Settings row with toggle switch - inline control.
 */
@Composable
private fun SettingsToggleRow(
    title: String,
    icon: ImageVector,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    iconColor: Color = LocalAccentColor.current,
    containerColor: Color = Color.Transparent
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // Leading Icon with Squircle shape
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (containerColor != Color.Transparent) containerColor else iconColor.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Medium
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Switch(
                checked = isChecked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = iconColor,
                    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    uncheckedBorderColor = Color.Transparent
                ),
                modifier = Modifier.scale(0.8f)
            )
        }
    }
}

// formatCacheSize is now imported from DataManagementSection

@Composable
private fun HideSystemBars() {
    val view = LocalView.current
    LaunchedEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        window?.let {
            WindowCompat.getInsetsController(it, view).apply {
                hide(WindowInsetsCompat.Type.statusBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }
}

