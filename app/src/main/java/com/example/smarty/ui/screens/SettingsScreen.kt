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
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
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
import com.example.smarty.ui.screens.settings.VoiceFingerprintSheetContent
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
import com.example.smarty.ui.theme.SafetyOrange
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
    // Voice fingerprint management
    isVoiceEnrolled: Boolean = false,
    onDeleteVoiceFingerprint: () -> Unit = {},
    onRetrainVoice: () -> Unit = {},
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
    onClearMemorySyncResult: () -> Unit = {}
) {
    var showAIConfigSheet by remember { mutableStateOf(false) }
    val aiConfigSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Bottom Sheets for Sub-settings
    var showArchiveSheet by remember { mutableStateOf(false) }
    var showBackupSheet by remember { mutableStateOf(false) }
    var showAboutSheet by remember { mutableStateOf(false) }  // Newly added state
    var showShakeSensitivitySheet by remember { mutableStateOf(false) }
    var showVoiceFingerprintSheet by remember { mutableStateOf(false) }
    var showAIMemorySheet by remember { mutableStateOf(false) }
    val subSettingSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Local LLM connection state
    var localPCTestStatus by remember { mutableStateOf<String?>(null) }
    var isTestingLocalPC by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    val isSystemDark = isSystemInDarkTheme()

    // Intercept system back button
    // If any sheet is open, close it. Otherwise, navigate back.
    androidx.activity.compose.BackHandler(onBack = {
        if (showArchiveSheet || showBackupSheet || showAboutSheet || showShakeSensitivitySheet || showVoiceFingerprintSheet || showAIConfigSheet || showAIMemorySheet) {
            showArchiveSheet = false
            showBackupSheet = false
            showAboutSheet = false
            showShakeSensitivitySheet = false
            showVoiceFingerprintSheet = false
            showAIConfigSheet = false
            showAIMemorySheet = false
        } else {
            onBackClick()
        }
    })

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
                title = "AI & Voice",
                icon = Icons.Default.Psychology,
                isExpanded = expandedSection == "ai",
                onToggle = { expandedSection = if (expandedSection == "ai") null else "ai" }
            ) {
                SettingsRow(
                    title = "AI Providers",
                    subtitle = "Models & API keys",
                    onClick = { showAIConfigSheet = true }
                )
                SettingsRow(
                    title = if (isVoiceEnrolled) "Voice ID" else "Set Up Voice ID",
                    subtitle = if (isVoiceEnrolled) "Active" else "Not configured",
                    onClick = {
                        if (isVoiceEnrolled) showVoiceFingerprintSheet = true
                        else onRetrainVoice()
                    }
                )
                SettingsRow(
                    title = "AI Memory",
                    subtitle = if (aiMemories.isEmpty()) "No memories" else "${aiMemories.size} memories",
                    onClick = { showAIMemorySheet = true }
                )
            }

            // ═══════════════════════════════════════════════════════════════════
            // SECTION 3: DATA
            // ═══════════════════════════════════════════════════════════════════
            SettingsSection(
                title = "Data",
                icon = Icons.Default.Storage,
                isExpanded = expandedSection == "data",
                onToggle = { expandedSection = if (expandedSection == "data") null else "data" }
            ) {
                SettingsRow(
                    title = "Backup & Sync",
                    subtitle = if (lastBackupTime > 0) {
                        val sdf = java.text.SimpleDateFormat("MMM dd", java.util.Locale.getDefault())
                        "Last: ${sdf.format(java.util.Date(lastBackupTime))}"
                    } else "Not backed up",
                    onClick = { showBackupSheet = true }
                )
                SettingsRow(
                    title = "Google Calendar Sync",
                    subtitle = if (lastCalendarSyncTime > 0) {
                        val sdf = java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault())
                        "Last sync: ${sdf.format(java.util.Date(lastCalendarSyncTime))}"
                    } else "Not synced",
                    onClick = onCalendarSync
                )
                SettingsRow(
                    title = "Archive",
                    subtitle = "View archived notes",
                    onClick = { showArchiveSheet = true }
                )
                SettingsRow(
                    title = "Clear Cache",
                    subtitle = formatCacheSize(cacheSizeBytes),
                    onClick = onClearCache,
                    enabled = !isClearingCache && cacheSizeBytes > 0
                )
            }

            // ═══════════════════════════════════════════════════════════════════
            // SECTION 4: PREFERENCES
            // ═══════════════════════════════════════════════════════════════════
            SettingsSection(
                title = "Preferences",
                icon = Icons.Default.Tune,
                isExpanded = expandedSection == "prefs",
                onToggle = { expandedSection = if (expandedSection == "prefs") null else "prefs" }
            ) {
                SettingsRow(
                    title = "Shake Sensitivity",
                    subtitle = when {
                        shakeSensitivity < 0.3f -> "Low"
                        shakeSensitivity < 0.7f -> "Medium"
                        else -> "High"
                    },
                    onClick = { showShakeSensitivitySheet = true }
                )
                // Assistant Settings
                val context = LocalContext.current
                SettingsRow(
                    title = "Default Assistant",
                    subtitle = "Set as device assistant",
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
                    }
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
                        imageVector = Icons.AutoMirrored.Filled.Logout,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = "Sign Out",
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
                    text = "Loum v1.1.0",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Made with intelligence",
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

    // Voice Fingerprint Management Bottom Sheet
    if (showVoiceFingerprintSheet) {
        ModalBottomSheet(
            onDismissRequest = { showVoiceFingerprintSheet = false },
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
            VoiceFingerprintSheetContent(
                isVoiceEnrolled = isVoiceEnrolled,
                onRetrainVoice = onRetrainVoice,
                onDeleteVoice = onDeleteVoiceFingerprint,
                onDismiss = { showVoiceFingerprintSheet = false }
            )
        }
    }

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
                    text = "About Loum",
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
                        text =  "Hello, I am Moudook.\n\n" +
                                "Loum is an AI-powered personal knowledge management app. I made this mainly for myself so I do not get fussed managing my notes and content. The idea is simple. Capture anything, and let the AI help later with organizing, searching, and recalling things when needed.\n\n" +
                                "You can add many types of content. Text notes for brain dumps. Images. Videos, both YouTube and local. Documents like PDF, DOCX, XLSX, and PPTX. Website links with metadata. Audio files and voice notes. Code snippets. Twitter or X posts, Instagram posts, APK files, and archive files.\n\n" +
                                "For organization, I added basic but useful things. You can pin notes so important ones stay on top. Notes get smart categories automatically. AI also generates tags for every note. If you do not want something but also do not want to delete it, you can archive it. There is also bulk selection so you can operate on many notes at once.\n\n" +
                                "I also added note versioning. Every note has a Git-like history. Up to 10 versions are saved automatically. You can restore any older version instantly. You can also see what changed and when it changed.\n\n" +
                                "Search was one of the main focus areas.\n\n" +
                                "I added full-text search using FTS5. It searches across all notes instantly. It uses BM25 ranking so relevant notes come first. It searches titles, content, and summaries. It also supports prefix matching for partial words.\n\n" +
                                "Along with that, I added semantic search. It can handle typos. It uses fuzzy matching, Jaro-Winkler similarity, phonetic matching using Soundex, and n-gram token overlap so search still works even if you do not type things perfectly.\n\n" +
                                "The AI assistant is agentic.\n\n" +
                                "It can search and retrieve notes based on context. It can create new notes directly from conversation. It can edit and update existing notes. It can delete notes when asked. It can manage to-do lists inside notes. It can also set smart reminders on cards.\n\n" +
                                "Every AI response shows citations. You can tap a citation and jump directly to the source note. So you always know where the information is coming from.\n\n" +
                                "I also added smart reminders. AI can highlight important notes. A shimmer animation is used to draw attention. You can also set expiration on reminders so temporary things do not stay forever.\n\n" +
                                "For the agentic AI, I use KOOG by JetBrains. KOOG stands for Kotlin Object-Oriented Graphs. It lets the AI work with structured tool calls and function execution. The AI can decide which tools to use and chain them together. This is what makes the assistant truly agentic instead of just a chatbot. KOOG handles the execution graph and makes sure everything runs in the right order.\n\n" +
                                "Multiple AI providers are supported. Google Gemini is the default. I also added support for OpenAI, Anthropic, Groq, DeepSeek, Cerebras, Cohere, OpenRouter, and HuggingFace. You can manage multiple API keys per provider. You can reorder provider priority by drag and drop. Automatic key rotation and rate limit handling are also there. You can choose models per provider.\n\n" +
                                "For web search, I integrated Tavily. It gives real-time information. There are 1000 free requests per month. AI summarizes the results so you do not need to read everything manually.\n\n" +
                                "I also added a daily digest. It sends a notification at 6:30 AM. It summarizes what happened in the last 24 hours. New notes and important updates show up there.\n\n" +
                                "Voice and audio were also important.\n\n" +
                                "You can record voice notes instantly by tapping the mic. There is a real-time amplitude visualizer. Audio is saved in M4A format. You can record up to 10 minutes. You always get a confirmation before saving or canceling.\n\n" +
                                "Speech-to-text uses Google Speech Recognition. A halftone shimmer shows when it is actively listening. Continuous recognition mode is also supported.\n\n" +
                                "I also added wake word detection using Vosk. It runs on device. It works without internet. There is also a high sensitivity mode.\n\n" +
                                "The music player has a living orb visualizer. It reacts to audio amplitude and frequency bands. There is a mini player and a full-screen mode. I tried to make it feel alive.\n\n" +
                                "Calendar support is also there.\n\n" +
                                "You can import events from Google Calendar. Exchange and other providers are supported. It syncs past 30 days and next 90 days. Events update automatically.\n\n" +
                                "AI can access your calendar, but only non-private events. It can create events from conversation. You can also link notes to calendar events. Private events stay hidden from AI.\n\n" +
                                "Privacy was non-negotiable.\n\n" +
                                "There are multiple privacy modes. Full privacy means AI cannot see the note at all. Exclude from chat means the note stays hidden from AI context. Private calendar events are also invisible to AI.\n\n" +
                                "I added shake-to-private mode. You can shake the device to toggle privacy. Sensitivity can be adjusted. There is visual feedback so you know when the mode changes.\n\n" +
                                "I also added prompt injection protection. Content is sanitized across multiple languages. This runs on device. It prevents malicious note content from affecting the AI.\n\n" +
                                "All data is stored locally on the device. I use a Room database for persistence. API keys are stored using Android's EncryptedSharedPreferences. Only API calls go out of the device.\n\n" +
                                "For user experience, I added widgets. You can capture notes directly from the home screen. One tap and you are inside a new note.\n\n" +
                                "App shortcuts are also there. Long press the app icon for quick actions like new note, search, or voice note. Recent notes also show up dynamically.\n\n" +
                                "Sharing is deeply integrated. You can share content from any app into Loum. Content type is detected automatically. URL metadata is extracted. You can also bulk select and share multiple notes.\n\n" +
                                "For UI, I avoided popups. I use slide-in panels instead. Animations are smooth and spring-based. Entry animations are staggered. Dark and light themes are supported. You can also customize accent colors.\n\n" +
                                "There are many animations. Cloud-like startup animation. Living orb on the main screen. Chat personality animations. Shimmer effects for reminders. Halftone indicator for speech.\n\n" +
                                "Performance was a big focus.\n\n" +
                                "I optimized memory usage using shared HTTP clients. Resources are cleaned automatically. Coroutines are scoped properly so there are no leaks. Cache size is managed.\n\n" +
                                "CPU usage is optimized by merging animation transitions, using derivedStateOf where needed, pre-compiling regex patterns, early terminating searches, and using fast math approximations.\n\n" +
                                "The database uses Room with SQLite. Queries are indexed. FTS5 is used for search. Migrations are automatic. Paging3 is used for infinite scrolling.\n\n" +
                                "The app is built to work on edge and low-end devices. Rendering quality adapts. Memory usage stays low. Background work is battery conscious.\n\n" +
                                "Backup is simple.\n\n" +
                                "You can export a full ZIP backup. It contains notes, categories, settings, chat history, AI memories, calendar events, and attachments. You can restore anytime. You can choose to merge or replace data. Integrity is preserved.\n\n" +
                                "The app is built using Kotlin and Jetpack Compose. It follows Material Design 3. Room Database version is 19. Min SDK is 26. Target SDK is 36. WorkManager is used for background tasks. OkHttp handles networking. Vosk is used for offline speech. ExoPlayer handles media.\n\n" +
                                "Your data stays on your device. Only AI and speech recognition APIs need internet.\n\n" +
                                "I am still working on the UI and the agentic part. If you find any issues, please mention them on GitHub.\n\n" +
                                "Thank you for using Loum.",
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
                            text = "Shake Gesture",
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                letterSpacing = (-0.5).sp
                            ),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Configure chat mode activation",
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
                                imageVector = Icons.Default.Vibration,
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
                            text = "Low",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Requires stronger shake",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "High",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Light shake triggers",
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
                    color = if (isDestructive) SafetyOrange else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }

            // Action
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = iconColor
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
                            text = "AI Intelligence",
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                letterSpacing = (-0.5).sp
                            ),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Configure your AI providers and models",
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
                                imageVector = Icons.Default.SmartToy, // AI Icon
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
                        text = "Drag providers to reorder priority",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                    )
                }

            val providerInfo = mapOf(
                AIProvider.GEMINI to Triple("Gemini", "Google's fastest AI", "https://aistudio.google.com/apikey"),
                AIProvider.DEEPSEEK to Triple("DeepSeek", "Cost-effective", "https://platform.deepseek.com"),
                AIProvider.GROQ to Triple("Groq", "Ultra-fast", "https://console.groq.com"),
                AIProvider.CEREBRAS to Triple("Cerebras", "2000+ tokens/sec", "https://cloud.cerebras.ai"),
                AIProvider.COHERE to Triple("Cohere", "Command models", "https://dashboard.cohere.com/api-keys"),
                AIProvider.OPENAI to Triple("OpenAI", "GPT-4o", "https://platform.openai.com/api-keys"),
                AIProvider.ANTHROPIC to Triple("Anthropic", "Claude models", "https://console.anthropic.com/settings/keys"),
                AIProvider.OPENROUTER to Triple("OpenRouter", "Multi-model", "https://openrouter.ai/keys"),
                AIProvider.HUGGINGFACE to Triple("HuggingFace", "Open source", "https://huggingface.co/settings/tokens"),
                AIProvider.GITHUB to Triple("GitHub Models", "Free with GitHub", "https://github.com/settings/tokens"),
                AIProvider.LOCAL_PC to Triple("Local LLM", "Run AI locally", "")
            )

            // Iterate through providers with drag-and-drop reordering
            localProviderOrder.forEachIndexed { index, provider ->
                val (name, description, _) = providerInfo[provider] ?: Triple("Unknown", "", "")
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
                                contentDescription = "Drag to reorder",
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
                    imageVector = if (hasAnyKeys) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (hasAnyKeys) LocalAccentColor.current else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (hasAnyKeys) "$configuredCount provider${if (configuredCount > 1) "s" else ""} configured" else "No API keys - using demo mode",
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
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp).padding(end = 8.dp)
                )

                Column {
                    Text(
                        text = "Tavily Web Search",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (apiKeys.isNotEmpty()) LocalAccentColor.current else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Enable AI web search capabilities",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Status indicator
            if (apiKeys.isNotEmpty()) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Configured",
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
                    text = "Tavily provides real-time web search for AI. Get your free API key (1,000 requests/month) from tavily.com. Add multiple keys for rotation.",
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
                                        contentDescription = if (showKey) "Hide" else "Show",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                IconButton(onClick = { onRemoveKey(key) }) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Remove",
                                        tint = SafetyOrange,
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
                                            text = "tvly-XXXXX...",
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
                                        contentDescription = if (showKeyWhileTyping) "Hide key" else "Show key",
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
                                    Text("Cancel")
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
                                    Text("Add Key")
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
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Add Tavily API Key")
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
                TestResult.Failure("Server returned: $code")
            }
        } catch (e: java.net.ConnectException) {
            TestResult.Failure("Connection refused - is server running?")
        } catch (e: java.net.SocketTimeoutException) {
            TestResult.Failure("Timeout - check IP and firewall")
        } catch (e: java.net.UnknownHostException) {
            TestResult.Failure("Invalid IP address")
        } catch (e: javax.net.ssl.SSLHandshakeException) {
            // Log the actual error for debugging
            android.util.Log.e("LocalServerTest", "SSL Handshake failed", e)
            TestResult.Failure("SSL failed - ensure Caddy is running")
        } catch (e: javax.net.ssl.SSLException) {
            android.util.Log.e("LocalServerTest", "SSL Exception", e)
            TestResult.Failure("SSL error - check port (8443 for HTTPS)")
        } catch (e: Exception) {
            android.util.Log.e("LocalServerTest", "Connection error", e)
            val msg = e.message?.lowercase() ?: ""
            when {
                msg.contains("ssl") || msg.contains("tls") -> 
                    TestResult.Failure("SSL/TLS error - try HTTP mode")
                msg.contains("certificate") -> 
                    TestResult.Failure("Cert error - is Caddy running?")
                msg.contains("reset") || msg.contains("closed") ->
                    TestResult.Failure("Connection reset - wrong port?")
                else -> 
                    TestResult.Failure("Error: ${e.message?.take(50) ?: "Unknown"}")
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
        ipInput.startsWith("10.") -> "USB/WiFi"
        ipInput.startsWith("192.168.") -> "WiFi"
        ipInput.startsWith("172.") -> "Ethernet"
        else -> "Network"
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
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp).padding(end = 8.dp)
                )

                Column {
                    Text(
                        text = "Local LLM Server",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (serverIP.isNotBlank()) LocalAccentColor.current else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Connect via USB tethering or WiFi",
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
                        imageVector = Icons.Default.Dns,
                        contentDescription = "Configured",
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
                    text = "Connect to your local LLM server. Use USB IP (10.x.x.x) for USB tethering or WiFi IP (192.168.x.x) for wireless. Run 'start_local_llm.bat' on your PC to see available IPs.",
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
                                text = "IP Address",
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
                                text = "Port",
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
                            text = "Use HTTPS (Encrypted)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (httpsEnabled) "Traffic is encrypted" else "Traffic is unencrypted",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (httpsEnabled) SystemGreen else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
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
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = LocalAccentColor.current
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Testing connection...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            testResult is TestResult.Success -> {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Success",
                                    tint = SystemGreen,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Connection successful! Settings saved.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SystemGreen
                                )
                            }
                            testResult is TestResult.Failure -> {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = "Failed",
                                    tint = SafetyOrange,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = (testResult as TestResult.Failure).message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SafetyOrange
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
                            Text("Clear", color = SafetyOrange)
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
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.surface
                            )
                        } else {
                            Text("Test & Save")
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
                        color = SystemGreen.copy(alpha = 0.8f)
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
    val accentColor = LocalAccentColor.current
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
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
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
    subtitle: String? = null,
    onClick: () -> Unit,
    enabled: Boolean = true,
    isDestructive: Boolean = false
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
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
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
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
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit
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
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Switch(
                checked = isChecked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = LocalAccentColor.current,
                    checkedTrackColor = LocalAccentColor.current.copy(alpha = 0.3f)
                )
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
