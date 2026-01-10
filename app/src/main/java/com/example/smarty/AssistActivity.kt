package com.example.smarty

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.smarty.viewmodel.AssistViewModel
import com.example.smarty.viewmodel.AssistViewModelFactory
import com.example.smarty.agent.AgentCallbacks
import com.example.smarty.agent.AgentResult
import com.example.smarty.agent.JarvisAgentOptimized
import com.example.smarty.agent.JarvisAgentProvider
import com.example.smarty.agent.ImageDisplayItem
import com.example.smarty.agent.WebCitation
import com.example.smarty.data.local.JarvisDatabase
import com.example.smarty.data.local.SecurePreferences
import com.example.smarty.data.model.AudioTrack
import com.example.smarty.data.model.Category
import com.example.smarty.data.model.ChatMessage
import com.example.smarty.data.model.ChatRole
import com.example.smarty.data.model.Note
import com.example.smarty.data.model.NoteType
import com.example.smarty.data.model.ProcessingStatus
import com.example.smarty.data.remote.AIService
import com.example.smarty.data.remote.providers.TavilySearchProvider
import com.example.smarty.data.repository.ChatRepository
import com.example.smarty.data.repository.JarvisRepository
import com.example.smarty.service.AlarmScheduler
import com.example.smarty.service.AudioPlayerService
import com.example.smarty.service.CommandResult
import com.example.smarty.service.LocalCommandProcessor
import com.example.smarty.service.ScreenCaptureService
import com.example.smarty.ui.theme.JarvisTheme
import com.example.smarty.ui.theme.GeminiColors
import com.example.smarty.util.PrivacyGuard
import com.example.smarty.util.api.GroqKeyManager
import com.example.smarty.voice.VoskWakeWordManager

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import androidx.compose.ui.graphics.Color as ComposeColor

/**
 * Assistant Overlay Activity - Gemini-style Floating Bottom Pill
 *
 * Triggered by Android's assistant gesture when set as default assistant.
 * 
 * Features:
 * - Fully transparent background (previous app remains visible)
 * - Floating pill anchored to bottom
 * - Tap outside to dismiss
 * - Edge-to-edge display with FLAG_LAYOUT_NO_LIMITS
 */
class AssistActivity : ComponentActivity() {

    companion object {
        private const val TAG = "AssistActivity"
    }

    // Views
    private lateinit var touchOutsideInterceptor: View
    private lateinit var assistantPill: android.view.ViewGroup
    private lateinit var inputField: EditText
    private lateinit var micButton: ImageView
    private lateinit var sendButton: ImageView
    private lateinit var composeView: ComposeView

    // Speech Recognition
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = mutableStateOf(false)
    private var partialText = mutableStateOf("")

    // Audio focus management - critical for getting microphone access
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private val mainHandler = Handler(Looper.getMainLooper())

    // Wake word detection
    private var wakeWordManager: VoskWakeWordManager? = null
    private var isWakeWordActive = mutableStateOf(false)

    // ViewModel for message persistence (UI-005 fix)
    private val viewModel: AssistViewModel by lazy {
        ViewModelProvider(this, AssistViewModelFactory(application))[AssistViewModel::class.java]
    }

    // Chat state - now backed by ViewModel for persistence
    private val messages: List<ChatMessage>
        get() = viewModel.messages.value
    private var isProcessing = mutableStateOf(false)

    // Lazy dependencies
    private val securePreferences: SecurePreferences by lazy { SecurePreferences.getInstance(application) }
    private val aiService: AIService by lazy { AIService(securePreferences) }
    private val database: JarvisDatabase by lazy { JarvisDatabase.getDatabase(application) }
    private val repository: JarvisRepository by lazy {
        JarvisRepository(database.noteDao(), database.categoryDao(), database.calendarDao(), database.noteVersionDao())
    }
    // Note: chatRepository removed - session management is handled by AssistViewModel
    private val groqKeyManager: GroqKeyManager by lazy { GroqKeyManager.getInstance(application) }
    private val tavilySearchProvider: TavilySearchProvider by lazy {
        val httpClient = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        TavilySearchProvider(httpClient, Gson())
    }
    private val alarmScheduler: AlarmScheduler by lazy { AlarmScheduler.getInstance(application) }
    private val agentProvider: JarvisAgentProvider by lazy { JarvisAgentProvider(securePreferences, groqKeyManager) }
    
    // Notes cache
    private var cachedNotes: List<Note> = emptyList()
    private var cachedCategories: List<Category> = emptyList()

    // Tool status for UI feedback
    private var currentToolStatus = mutableStateOf<String?>(null)

    // Pending citations from web search
    private val pendingCitations = mutableListOf<WebCitation>()

    // Agent callbacks
    private val agentCallbacks = object : AgentCallbacks {
        override fun getActiveNotes(): List<Note> = PrivacyGuard.getAiVisibleNotes(cachedNotes)
        override fun getArchivedNotes(): List<Note> = emptyList()
        override fun getCategories(): List<Category> = cachedCategories
        override fun getTavilyApiKey(): String? = securePreferences.getTavilyApiKey()
        // BATCH-3C: OpenAI API key for AgentOptimizer semantic cache (embeddings)
        override fun getOpenAiApiKey(): String? = securePreferences.getProviderKeys(com.example.smarty.data.local.AIProvider.OPENAI).firstOrNull()
        // Gemini API key for AgentOptimizer semantic cache fallback
        override fun getGeminiApiKey(): String? = securePreferences.getProviderKeys(com.example.smarty.data.local.AIProvider.GEMINI).firstOrNull()
        override suspend fun processNoteWithAi(note: Note) {
            // In assistant mode, assign a category based on content type for speed
            // (skipping full AI analysis to maintain responsiveness)
            val categoryName = when {
                NoteType.isAnalyzable(note.type) -> {
                    // Use ContentTypeDetector's mock response to get a category
                    val (tag, _, _) = com.example.smarty.util.ContentTypeDetector.generateMockAiResponse(note.type, note.content)
                    tag.ifBlank { "Quick Notes" }
                }
                else -> com.example.smarty.util.ContentTypeDetector.getStorageCategoryName(note.type)
            }
            val category = repository.getOrCreateCategory(categoryName)
            repository.updateNote(note.copy(
                categoryId = category.id,
                categoryName = category.name,
                processingStatus = ProcessingStatus.COMPLETED
            ))
        }

        override suspend fun markNoteAsAnalyzedForMemory(noteId: String) {
            database.noteDao().markNoteAsReadForMemory(noteId)
        }

        override suspend fun findNoteByDescription(description: String, notes: List<Note>): Note? {
            return notes.find { it.title.contains(description, ignoreCase = true) || it.content.contains(description, ignoreCase = true) }
        }
        override fun requestAudioPlayback(track: AudioTrack) { playAudio(track) }

        override fun onToolExecutionStarted(toolName: String, toolDisplayName: String) {
            runOnUiThread {
                // Update UI to show current tool operation
                currentToolStatus.value = "$toolDisplayName..."
                Log.d(TAG, "Tool started: $toolName ($toolDisplayName)")
            }
        }

        override fun onToolExecutionCompleted(toolName: String) {
            runOnUiThread {
                // Clear status after tool completes
                currentToolStatus.value = null
                Log.d(TAG, "Tool completed: $toolName")
            }
        }

        override fun onStatusUpdate(status: String) {
            runOnUiThread {
                currentToolStatus.value = status
            }
        }

        override fun onCitationsFound(citations: List<WebCitation>) {
            runOnUiThread {
                // Store citations for display in response
                pendingCitations.addAll(citations)
                Log.d(TAG, "Citations found: ${citations.size} sources")
            }
        }

        override fun launchApp(packageName: String) {
            this@AssistActivity.launchApp(packageName)
        }

        override fun getScreenContext(): com.example.smarty.agent.tools.external.ScreenContext? {
            return capturedScreenContext
        }

        override fun onDisplayImages(images: List<ImageDisplayItem>) {
            // AssistActivity doesn't display images inline - this is handled by AssistViewModel
            Log.d(TAG, "Images found: ${images.size} (not displayed in overlay mode)")
        }

        override fun onPlanStatusChanged(status: String?) {
            // Plan progress not shown in overlay mode
        }
    }

    // Screen context captured when assistant is triggered
    private var capturedScreenContext: com.example.smarty.agent.tools.external.ScreenContext? = null

    // MediaProjection for screen capture
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var pendingSavePageHint: String? = null
    private var pendingSavePageOriginalText: String? = null
    // Deferred to signal when save operation completes (for async permission flow)
    private var pendingSaveCompletion: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    // MediaProjection permission launcher
    private val mediaProjectionLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val completion = pendingSaveCompletion
            if (result.resultCode == RESULT_OK && result.data != null) {
                Log.d(TAG, "MediaProjection permission granted")
                // Start the capture service
                ScreenCaptureService.start(this, result.resultCode, result.data!!)
                // Wait for service to be ready, then capture
                lifecycleScope.launch {
                    try {
                        // Poll for service ready (faster than fixed delay)
                        var attempts = 0
                        while (!ScreenCaptureService.isReady() && attempts < 20) {
                            kotlinx.coroutines.delay(50)
                            attempts++
                        }
                        if (ScreenCaptureService.isReady()) {
                            proceedWithScreenCapture(pendingSavePageHint, pendingSavePageOriginalText ?: "")
                        } else {
                            Log.w(TAG, "Service not ready after waiting, falling back to text-only")
                            savePageWithoutScreenshot(pendingSavePageHint, pendingSavePageOriginalText ?: "")
                        }
                    } finally {
                        // Signal completion to waiting coroutine
                        completion?.complete(Unit)
                    }
                }
            } else {
                Log.w(TAG, "MediaProjection permission denied")
                lifecycleScope.launch {
                    try {
                        savePageWithoutScreenshot(pendingSavePageHint, pendingSavePageOriginalText ?: "")
                    } finally {
                        // Signal completion to waiting coroutine
                        completion?.complete(Unit)
                    }
                }
            }
            pendingSavePageHint = null
            pendingSavePageOriginalText = null
        }

    private val jarvisAgent: JarvisAgentOptimized by lazy {
        JarvisAgentOptimized(this, agentProvider, repository, tavilySearchProvider, alarmScheduler, agentCallbacks, database.aiMemoryDao())
    }

    private val localCommandProcessor: LocalCommandProcessor by lazy {
        LocalCommandProcessor(this, { cachedNotes }, { playAudio(it) }, { launchApp(it) })
    }

    // Assist context from triggering app
    private var assistContext: AssistContext? = null

    data class AssistContext(
        val selectedText: String?,
        val referringPackage: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // CRITICAL: Setup transparent window BEFORE setContentView
        setupTransparentWindow()

        // Layout Inflation
        setContentView(R.layout.activity_assistant)

        // Initialize audio manager for audio focus handling
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Initialize MediaProjection manager for screen capture
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // Extract assist context from intent
        extractAssistContext()

        // Initialize Views
        initViews()

        // Logic Initialization
        initSpeechRecognizer()
        // NOTE: Wake word manager initialization is DELAYED until after initial speech Recognition
        // This prevents Vosk from grabbing the microphone before Google speech can start
        // initWakeWordManager() will be called after first speech Recognition completes

        // Load notes for agent context - session is created by AssistViewModel
        // REMOVED: Duplicate session creation that was deactivating ViewModel's session
        lifecycleScope.launch(Dispatchers.IO) {
            loadNotesForContext()
        }

        // Back press handler
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finishWithAnimation()
            }
        })

        // If we have selected text, pre-populate the input field
        // Speech auto-start is handled in onWindowFocusChanged for better reliability
        if (!assistContext?.selectedText.isNullOrBlank()) {
            Log.d(TAG, "Selected text found: ${assistContext?.selectedText?.take(50)}...")
            // OPTIMIZED: Reduced delay from 300ms to 50ms
            window.decorView.postDelayed({
                inputField.setText(assistContext?.selectedText)
                inputField.setSelection(inputField.text.length)
            }, 50)
        } else {
            Log.d(TAG, "No selected text - voice will auto-start in onResume")
            // Speech Recognition is now started immediately in onResume
            // No fallback timer needed since we start immediately without delay
        }
    }

    /**
     * Extract context from the assist intent (selected text, referring app)
     */
    private fun extractAssistContext() {
        try {
            // Check if launched from VoiceInteractionSession (affects focus timing)
            isFromVoiceInteraction = intent.getBooleanExtra("from_voice_interaction", false)
            Log.d(TAG, "Launched from VoiceInteraction: $isFromVoiceInteraction")

            // Extract assist bundle for selected text
            // Note: EXTRA_ASSIST_BUNDLE is "android.intent.extra.ASSIST_BUNDLE"
            val assistBundle = intent.getBundleExtra("android.intent.extra.ASSIST_BUNDLE")
            val selectedText = assistBundle?.getString(Intent.EXTRA_TEXT)
                ?: intent.getStringExtra(Intent.EXTRA_TEXT)
                ?: intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()

            // Get referring package (the app that triggered assist)
            val referringPackage = referrer?.host ?: ""

            assistContext = AssistContext(
                selectedText = selectedText,
                referringPackage = referringPackage
            )

            // Capture screen context for SaveScreenTool
            capturedScreenContext = com.example.smarty.agent.tools.external.ScreenContext(
                selectedText = selectedText,
                referringApp = if (referringPackage.isNotBlank()) {
                    try {
                        packageManager.getApplicationLabel(
                            packageManager.getApplicationInfo(referringPackage, 0)
                        ).toString()
                    } catch (e: Exception) {
                        referringPackage
                    }
                } else null,
                capturedAt = System.currentTimeMillis(),
                contextData = buildMap {
                    assistBundle?.keySet()?.forEach { key ->
                        assistBundle.getString(key)?.let { value ->
                            if (value.isNotBlank() && value.length < 500) {
                                put(key, value)
                            }
                        }
                    }
                }
            )

            if (!selectedText.isNullOrBlank()) {
                Log.d(TAG, "Assist context: selected text from $referringPackage")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting assist context: ${e.message}")
        }
    }
    
    /**
     * Setup window for true transparency - background app remains visible
     * Uses the same approach as Google Gemini assistant overlay
     * 
     * This implements the "Vertical Slice Fix" from Android transparency analysis:
     * 1. enableEdgeToEdge with transparent SystemBarStyle to bypass Android 15 scrims
     * 2. TRANSLUCENT pixel format for alpha channel support
     * 3. Clear all background drawables
     */
    private fun setupTransparentWindow() {
        // 1. CRITICAL: Edge-to-Edge with TRANSPARENT SystemBarStyle
        // This bypasses Android 15's automatic scrim protection that causes the white background
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            )
        )

        // 2. Force the window to use a format that supports transparency
        window.setFormat(android.graphics.PixelFormat.TRANSLUCENT)

        // 3. Legacy edge-to-edge for older APIs
        WindowCompat.setDecorFitsSystemWindows(window, false)

        @Suppress("DEPRECATION")
        window.apply {
            // 4. Clear any background drawables
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

            // 5. Set layout flags for full-screen overlay
            addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            
            // 6. Ensure system bars are transparent (backup for older APIs)
            statusBarColor = Color.TRANSPARENT
            navigationBarColor = Color.TRANSPARENT
            
            // 7. Force dim amount to 0 - prevents any dimming behind overlay
            setDimAmount(0f)
        }

        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        }
    }

    // Track if we've already started listening
    private var hasStartedListeningOnFocus = false
    // Track if launched from VoiceInteractionSession (needs different focus handling)
    private var isFromVoiceInteraction = false
    // Track if fallback timer has started listening
    private var hasFallbackStartedListening = false

    /**
     * Called when window focus changes - best time to start speech Recognition
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        Log.d(TAG, "onWindowFocusChanged: hasFocus=$hasFocus, hasStartedListeningOnFocus=$hasStartedListeningOnFocus, isFromVoiceInteraction=$isFromVoiceInteraction, hasFallbackStarted=$hasFallbackStartedListening")

        if (hasFocus && !hasStartedListeningOnFocus && !hasFallbackStartedListening && assistContext?.selectedText.isNullOrBlank()) {
            hasStartedListeningOnFocus = true
            Log.d(TAG, "Window has focus - starting speech Recognition")

            // When launched from VoiceInteractionSession, the session's hide() causes a brief
            // focus transition. Use a short delay for focus stabilization.
            // OPTIMIZED: Reduced from 500/300ms to 150/50ms for faster startup
            val focusDelay = if (isFromVoiceInteraction) 150L else 50L

            window.decorView.postDelayed({
                // Double-check we still have focus before starting
                if (!window.decorView.hasWindowFocus()) {
                    Log.w(TAG, "Lost focus during delay, will retry when focus returns")
                    hasStartedListeningOnFocus = false  // Reset to retry later
                    return@postDelayed
                }

                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    startListening()
                } else {
                    Log.e(TAG, "No RECORD_AUDIO permission when trying to auto-start")
                    inputField.hint = "Tap mic to speak..."
                }
            }, focusDelay)
        }
    }

    /**
     * Fallback mechanism to start speech Recognition if onWindowFocusChanged doesn't fire
     * This handles edge cases where the focus callback is missed or delayed
     */
    private fun startSpeechRecognitionFallback() {
        if (hasFallbackStartedListening || hasStartedListeningOnFocus || !assistContext?.selectedText.isNullOrBlank()) {
            return
        }

        hasFallbackStartedListening = true
        Log.d(TAG, "Fallback: Starting speech Recognition (focus callback may have been missed)")

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else {
            Log.e(TAG, "Fallback: No RECORD_AUDIO permission")
            inputField.hint = "Tap mic to speak..."
        }
    }
    
    /**
     * Wire up XML views
     */
    private fun initViews() {
        touchOutsideInterceptor = findViewById(R.id.touch_outside_interceptor)
        assistantPill = findViewById(R.id.assistant_pill)
        inputField = findViewById(R.id.input_field)
        micButton = findViewById(R.id.mic_button)
        sendButton = findViewById(R.id.send_button)
        composeView = findViewById(R.id.response_content)

        // Tap outside the pill = dismiss
        touchOutsideInterceptor.setOnClickListener {
            finishWithAnimation()
        }

        // Prevent clicks on pill from propagating to the interceptor
        assistantPill.setOnClickListener { /* consume click */ }

        micButton.setOnClickListener { toggleListening() }

        // Send button click handler
        sendButton.setOnClickListener {
            val text = inputField.text.toString()
            if (text.isNotBlank()) {
                processInput(text)
                inputField.text.clear()
            }
        }

        // Text watcher to show/hide send button based on input
        inputField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val hasText = !s.isNullOrBlank()
                sendButton.visibility = if (hasText) View.VISIBLE else View.GONE
            }
        })

        inputField.setOnEditorActionListener { v, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
               (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                val text = v.text.toString()
                if (text.isNotBlank()) {
                    processInput(text)
                    inputField.text.clear()
                }
                true
            } else false
        }
        
        // Compose content for Chat History/Responses inside the pill
        composeView.setContent {
            val isDarkTheme by securePreferences.isDarkTheme.collectAsState()
            val toolStatus by currentToolStatus
            // UI-005: Collect messages from ViewModel for reactive updates
            val messageList by viewModel.messages.collectAsState()
            JarvisTheme(darkTheme = isDarkTheme, isTransparent = true) {
                 if (messageList.isNotEmpty() || isProcessing.value) {
                     MinimalResponseList(
                         messages = messageList,
                         isProcessing = isProcessing.value,
                         toolStatus = toolStatus
                     )
                 }
            }
        }
    }

    private fun initSpeechRecognizer() {
        Log.d(TAG, "Checking speech Recognition availability...")

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e(TAG, "Speech Recognition NOT available on this device!")
            runOnUiThread {
                inputField.hint = "Voice unavailable - type here"
            }
            return
        }

        Log.d(TAG, "Speech Recognition is available on this device")
        // Note: Actual SpeechRecognizer creation is deferred to startListeningInternal()
        // This ensures a fresh instance is created when we actually need it,
        // after audio focus is acquired and other audio components have released.
    }

    /**
     * Check if a specific speech Recognition service is available.
     */
    private fun isRecognitionServiceAvailable(component: android.content.ComponentName): Boolean {
        return try {
            val pm = packageManager
            val services = pm.queryIntentServices(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH),
                PackageManager.GET_META_DATA
            )
            services.any { it.serviceInfo.packageName == component.packageName }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking Recognition service: ${e.message}")
            false
        }
    }

    /**
     * Find an external speech Recognition service (NOT our own package).
     * This is critical when Jarvis is set as the default assistant, because
     * the system's "default" recognizer would be Jarvis itself, creating a loop.
     */
    private fun findExternalSpeechService(): android.content.ComponentName? {
        val pm = packageManager
        val myPackage = packageName

        // Query all services that can handle speech Recognition
        // Use RecognitionService.SERVICE_INTERFACE ("android.speech.RecognitionService")
        val RecognizerIntent = Intent("android.speech.RecognitionService")
        val services = pm.queryIntentServices(RecognizerIntent, PackageManager.GET_META_DATA)

        Log.d(TAG, "Found ${services.size} speech Recognition services")

        var bestExternalService: android.content.ComponentName? = null

        for (resolveInfo in services) {
            val serviceInfo = resolveInfo.serviceInfo
            val servicePackage = serviceInfo.packageName
            val serviceName = serviceInfo.name

            // CRITICAL: Skip our own package to avoid the loop
            if (servicePackage == myPackage) {
                Log.d(TAG, "Skipping our own service: $servicePackage/$serviceName")
                continue
            }

            Log.d(TAG, "Found external service: $servicePackage/$serviceName")

            // Prefer Google's service if available
            if (servicePackage == "com.google.android.googlequicksearchbox") {
                Log.d(TAG, "Using Google's speech service")
                return android.content.ComponentName(servicePackage, serviceName)
            }

            // Store first external service as fallback
            if (bestExternalService == null) {
                bestExternalService = android.content.ComponentName(servicePackage, serviceName)
            }
        }

        return bestExternalService
    }

    /**
     * Create and configure a new SpeechRecognizer instance.
     * Creates a fresh instance each time to ensure clean state.
     *
     * CRITICAL: We must explicitly use an EXTERNAL speech Recognition service.
     * When Jarvis is set as the default assistant, the system's "default" recognizer
     * is Jarvis itself (AssistInteractionService), which causes a recursive loop.
     *
     * Solution: Query all available services and pick one that is NOT our package.
     */
    private fun createSpeechRecognizer(): SpeechRecognizer? {
        Log.d(TAG, "Creating fresh SpeechRecognizer instance...")

        // Destroy old recognizer if exists
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning up old recognizer: ${e.message}")
        }

        try {
            // CRITICAL: Find an external speech Recognition service (NOT our package)
            val externalService = findExternalSpeechService()

            var recognizer: SpeechRecognizer? = null

            if (externalService != null) {
                Log.d(TAG, "Using external speech service: ${externalService.packageName}/${externalService.className}")
                recognizer = SpeechRecognizer.createSpeechRecognizer(this, externalService)
            } else {
                // No external service found - this is a problem
                // Try hardcoded Google services as last resort (for older Android versions)
                Log.w(TAG, "No external speech service found, trying hardcoded Google services...")

                val googleComponents = listOf(
                    android.content.ComponentName(
                        "com.google.android.googlequicksearchbox",
                        "com.google.android.voicesearch.serviceapi.GoogleRecognitionService"
                    ),
                    android.content.ComponentName(
                        "com.google.android.googlequicksearchbox",
                        "com.google.android.apps.gsa.speechRecognition.service.GsaSpeechRecognitionService"
                    )
                )

                for (component in googleComponents) {
                    try {
                        Log.d(TAG, "Trying hardcoded: ${component.className}")
                        recognizer = SpeechRecognizer.createSpeechRecognizer(this, component)
                        Log.d(TAG, "Created recognizer with: ${component.className}")
                        break
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed: ${e.message}")
                    }
                }
            }

            // IMPORTANT: Do NOT fall back to default recognizer!
            // When Jarvis is the default assistant, "default" = Jarvis = loop
            if (recognizer == null) {
                Log.e(TAG, "NO external speech Recognition service available!")
                Log.e(TAG, "Cannot use 'default' because that would be Jarvis itself (loop)")
                return null
            }
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d(TAG, "onReadyForSpeech - recognizer is ready, audio focus acquired!")
                    // Cancel the timeout - speech Recognition started successfully
                    speechTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                    isListening.value = true
                    runOnUiThread {
                        micButton.setColorFilter(Color.parseColor("#4285F4")) // Google Blue
                        inputField.hint = "Listening..."
                    }
                }
                override fun onBeginningOfSpeech() {
                    Log.d(TAG, "onBeginningOfSpeech - user started speaking")
                }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    Log.d(TAG, "onEndOfSpeech - user stopped speaking")
                    isListening.value = false
                    runOnUiThread {
                        micButton.clearColorFilter()
                        inputField.hint = "Ask anything..."
                    }
                }
                override fun onError(error: Int) {
                    // Cancel the timeout - we got a response (even if error)
                    speechTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }

                    val errorMessage = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                        SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                        SpeechRecognizer.ERROR_NETWORK -> "Network error"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                        SpeechRecognizer.ERROR_NO_MATCH -> "No speech match"
                        SpeechRecognizer.ERROR_SERVER -> "Server error"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
                        else -> "Unknown error ($error)"
                    }
                    Log.e(TAG, "Speech Recognition error: $errorMessage (code: $error)")
                    isListening.value = false

                    // Release audio focus on error
                    releaseAudioFocus()

                    // Don't show error for no speech - that's normal
                    if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        runOnUiThread {
                            inputField.hint = errorMessage
                        }
                    }

                    startWakeWordDetection()
                    runOnUiThread {
                        micButton.clearColorFilter()
                        // Reset hint after brief display
                        mainHandler.postDelayed({ inputField.hint = "Ask anything..." }, 2000)
                    }
                }
                override fun onResults(results: Bundle?) {
                    Log.d(TAG, "onResults received")
                    // Cancel the timeout
                    speechTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                    isListening.value = false

                    // Release audio focus on success
                    releaseAudioFocus()

                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    Log.d(TAG, "Speech result: $text")
                    if (!text.isNullOrBlank()) {
                        processInput(text)
                        // Clear input field after processing (don't show speech text in input)
                        runOnUiThread { inputField.text.clear() }
                    } else {
                        startWakeWordDetection()
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!text.isNullOrBlank()) {
                        partialText.value = text
                        runOnUiThread {
                            inputField.setText(text)
                            inputField.setSelection(text.length)
                        }
                    }
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            Log.d(TAG, "Fresh SpeechRecognizer created successfully")
            return recognizer
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create speech recognizer: ${e.message}", e)
            return null
        }
    }
    
    // Timeout handler for speech Recognition
    private var speechTimeoutRunnable: Runnable? = null
    private val speechTimeoutMs = 4000L // 4 seconds to wait for onReadyForSpeech

    /**
     * Request audio focus to ensure we get exclusive access to the microphone.
     * This is critical for transparent overlay activities where the background app
     * might still be holding audio resources.
     */
    private fun requestAudioFocus(): Boolean {
        Log.d(TAG, "Requesting audio focus...")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener { focusChange ->
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            Log.d(TAG, "Audio focus GAINED")
                            hasAudioFocus = true
                        }
                        AudioManager.AUDIOFOCUS_LOSS -> {
                            // Permanent loss - another app took focus completely
                            Log.d(TAG, "Audio focus LOST permanently")
                            hasAudioFocus = false
                            if (isListening.value) {
                                stopListening()
                            }
                        }
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                            // Transient loss is EXPECTED when SpeechRecognizer service starts
                            // DO NOT stop listening - the speech service is using our audio request
                            Log.d(TAG, "Audio focus TRANSIENT loss: $focusChange (expected, continuing)")
                            // Keep hasAudioFocus true - we still have logical ownership
                        }
                    }
                }
                .build()

            val result = audioManager.requestAudioFocus(audioFocusRequest!!)
            hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            Log.d(TAG, "Audio focus request result: $result (granted: $hasAudioFocus)")
            return hasAudioFocus
        } else {
            @Suppress("DEPRECATION")
            val result = audioManager.requestAudioFocus(
                { focusChange ->
                    // Only stop on permanent loss, not transient
                    if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                        hasAudioFocus = false
                        if (isListening.value) stopListening()
                    }
                },
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            )
            hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            Log.d(TAG, "Audio focus request result (legacy): $result (granted: $hasAudioFocus)")
            return hasAudioFocus
        }
    }

    /**
     * Release audio focus when done with speech Recognition.
     */
    private fun releaseAudioFocus() {
        Log.d(TAG, "Releasing audio focus...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let {
                audioManager.abandonAudioFocusRequest(it)
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        hasAudioFocus = false
    }

    private fun startListening() {
        Log.d(TAG, "startListening() called")

        // Check permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission NOT granted!")
            runOnUiThread {
                inputField.hint = "Mic permission needed"
            }
            return
        }
        Log.d(TAG, "RECORD_AUDIO permission granted")

        if (isListening.value) {
            Log.d(TAG, "Already listening, ignoring startListening call")
            return
        }

        // Stop wake word to free up audio - this is critical
        Log.d(TAG, "Stopping wake word detection...")
        stopWakeWordDetection()
        wakeWordManager?.stopListening()

        // CRITICAL: Request audio focus FIRST to force other audio components to release
        Log.d(TAG, "Requesting audio focus...")
        if (!requestAudioFocus()) {
            Log.e(TAG, "Failed to get audio focus - another app may be using the microphone")
            runOnUiThread {
                inputField.hint = "Mic in use - tap to retry"
                micButton.clearColorFilter()
            }
            return
        }
        Log.d(TAG, "Audio focus granted!")

        // Show starting indicator
        runOnUiThread {
            inputField.hint = "Starting voice..."
            micButton.setColorFilter(Color.parseColor("#FFA500")) // Orange during startup
        }

        // Add a brief delay after audio focus to ensure:
        // 1. Other audio components (Vosk) have released the mic
        // 2. Audio focus change has propagated through the system
        // OPTIMIZED: Reduced from 800/150ms to 100/30ms for faster startup
        // The audio focus request itself forces other apps to release the mic
        val delay = if (isFromVoiceInteraction) 100L else 30L
        Log.d(TAG, "Brief delay for audio release (delay=${delay}ms)...")
        mainHandler.postDelayed({
            if (!isActivityResumed) {
                Log.w(TAG, "Activity not resumed, skipping speech start")
                releaseAudioFocus()
                return@postDelayed
            }

            startListeningInternal()
        }, delay)
    }

    /**
     * Internal method to actually start the speech recognizer.
     * Called after audio focus is acquired and a brief delay.
     */
    private fun startListeningInternal() {
        Log.d(TAG, "startListeningInternal() - creating fresh recognizer...")

        // CRITICAL: Create a fresh SpeechRecognizer instance
        // This ensures we get a clean state after audio focus is acquired
        speechRecognizer = createSpeechRecognizer()
        if (speechRecognizer == null) {
            Log.e(TAG, "Failed to create speech recognizer")
            releaseAudioFocus()
            runOnUiThread {
                inputField.hint = "Voice unavailable - tap to retry"
                micButton.clearColorFilter()
            }
            return
        }

        try {
            Log.d(TAG, "Creating speech Recognition intent...")
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                // Use online Recognition (more reliable for assistant overlay)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)

                // SILENCE TIMEOUT EXTENSION: Wait 2.5 seconds of silence before stopping
                // This allows users to take brief pauses/breaths while speaking
                putExtra("android.speech.extra.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS", 2500L)
                putExtra("android.speech.extra.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS", 2500L)
                putExtra("android.speech.extra.SPEECH_INPUT_MINIMUM_LENGTH_MILLIS", 1500L)
            }

            Log.d(TAG, "Calling SpeechRecognizer.startListening()...")

            // Cancel any existing timeout
            speechTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }

            // Set up timeout to detect if speech Recognition fails to start
            speechTimeoutRunnable = Runnable {
                if (!isListening.value && !isProcessing.value) {
                    Log.w(TAG, "Speech Recognition timeout - no callback received in ${speechTimeoutMs}ms")
                    runOnUiThread {
                        inputField.hint = "Tap mic to speak"
                        micButton.clearColorFilter()
                    }
                    // Release audio focus on timeout
                    releaseAudioFocus()
                    // Clean up recognizer
                    try {
                        speechRecognizer?.cancel()
                        speechRecognizer?.destroy()
                        speechRecognizer = null
                    } catch (e: Exception) {
                        Log.e(TAG, "Error cleaning up speech recognizer", e)
                    }
                }
            }
            mainHandler.postDelayed(speechTimeoutRunnable!!, speechTimeoutMs)

            speechRecognizer?.startListening(intent)
            Log.d(TAG, "startListening() call completed - waiting for callback...")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start listening: ${e.message}", e)
            releaseAudioFocus()
            runOnUiThread {
                // UI-004: Encourage retry instead of just showing error
                inputField.hint = "Voice unavailable - tap mic to retry"
                micButton.clearColorFilter()
            }
        }
    }

    private fun stopListening() {
        try {
            // Cancel any pending timeout
            speechTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            speechRecognizer?.stopListening()
            isListening.value = false
            // Release audio focus when we stop listening
            releaseAudioFocus()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping speech: ${e.message}")
        }
    }

    private fun toggleListening() {
        if (isListening.value) stopListening() else startListening()
    }
    
    private fun processInput(text: String) {
        if (text.isBlank()) return
        lifecycleScope.launch {
            // Clear pending citations from previous request
            pendingCitations.clear()

            val userMessage = ChatMessage(role = ChatRole.USER, content = text)
            viewModel.addMessage(userMessage)  // UI-005: Use ViewModel for persistence
            isProcessing.value = true

            try {
                val commandResult = localCommandProcessor.process(text)
                when (commandResult) {
                    is CommandResult.Handled -> {
                        val assistantMessage = ChatMessage(role = ChatRole.ASSISTANT, content = commandResult.response)
                        viewModel.addMessage(assistantMessage)  // UI-005: Use ViewModel for persistence
                    }
                    is CommandResult.HandledAndPassToLLM -> {
                        // Execute local action first (e.g., play audio)
                        val localMessage = ChatMessage(role = ChatRole.ASSISTANT, content = commandResult.response)
                        viewModel.addMessage(localMessage)

                        // Then also pass to LLM for additional tasks
                        val cleanHistory = messages.filter { it.role != ChatRole.SYSTEM }.map {
                            (if(it.role==ChatRole.USER) "USER" else "ASSISTANT") to it.content
                        }.takeLast(10)

                        val result = withContext(Dispatchers.IO) {
                            jarvisAgent.run(
                                userMessage = text,
                                conversationHistory = cleanHistory
                            )
                        }

                        val response = when (result) {
                            is AgentResult.Success -> result.response
                            is AgentResult.Error -> result.message
                            is AgentResult.NoProvider -> result.message
                        }
                        viewModel.addMessage(ChatMessage(role = ChatRole.ASSISTANT, content = response, isError = result is AgentResult.Error))
                    }
                    is CommandResult.SavePageRequest -> {
                        // Handle "save this page" command
                        handleSavePageRequest(commandResult.titleHint, text)
                    }
                    is CommandResult.PassToLLM -> {
                         val cleanHistory = messages.filter { it.role != ChatRole.SYSTEM }.map {
                             (if(it.role==ChatRole.USER) "USER" else "ASSISTANT") to it.content
                         }.takeLast(10)

                         val result = withContext(Dispatchers.IO) {
                             jarvisAgent.run(
                                 userMessage = text,
                                 conversationHistory = cleanHistory
                             )
                         }

                         val response = when (result) {
                             is AgentResult.Success -> result.response
                             is AgentResult.Error -> result.message
                             is AgentResult.NoProvider -> "Please set up an AI provider in settings."
                         }
                         viewModel.addMessage(ChatMessage(role = ChatRole.ASSISTANT, content = response, isError = result is AgentResult.Error))  // UI-005
                    }
                }
            } catch (e: Exception) {
                // UI-002: Classify exceptions and provide user-friendly error messages
                val userMessage = when (e) {
                    is java.net.UnknownHostException -> "No internet connection. Please check your network."
                    is java.net.SocketTimeoutException -> "Request timed out. Please try again."
                    is kotlinx.coroutines.TimeoutCancellationException -> "The AI took too long to respond. Try a simpler question."
                    is java.net.ConnectException -> "Couldn't connect to the server. Please check your connection."
                    is javax.net.ssl.SSLException -> "Secure connection failed. Please try again."
                    else -> "Something went wrong: ${e.localizedMessage ?: "Unknown error"}"
                }
                Log.e(TAG, "Error in processInput: ${e.javaClass.simpleName}", e)
                viewModel.addMessage(ChatMessage(role = ChatRole.ASSISTANT, content = userMessage, isError = true))
            } finally {
                isProcessing.value = false
                startWakeWordDetection()
            }
        }
    }

    /**
     * Handle "save this page" voice command.
     * Captures a screenshot and saves it as a note with AI-generated title.
     *
     * Flow:
     * 1. Check if ScreenCaptureService is ready (has MediaProjection permission)
     * 2. If yes, capture screenshot immediately
     * 3. If no, request permission and capture after granted
     * 4. Save note with screenshot attached
     *
     * @param titleHint Optional hint from user speech (e.g., "as a note to remember today's holiday")
     * @param originalText The original user input for context
     */
    private suspend fun handleSavePageRequest(titleHint: String?, originalText: String) {
        currentToolStatus.value = "Preparing to capture..."

        if (ScreenCaptureService.isReady()) {
            // Service is ready, capture immediately (direct call, no nested launch)
            Log.d(TAG, "ScreenCaptureService ready, capturing...")
            proceedWithScreenCapture(titleHint, originalText)
        } else {
            // Need to request MediaProjection permission
            Log.d(TAG, "Requesting MediaProjection permission...")
            pendingSavePageHint = titleHint
            pendingSavePageOriginalText = originalText

            // Create deferred to wait for permission callback to complete
            val completion = kotlinx.coroutines.CompletableDeferred<Unit>()
            pendingSaveCompletion = completion

            try {
                val intent = mediaProjectionManager.createScreenCaptureIntent()
                mediaProjectionLauncher.launch(intent)
                // Wait for the save operation to complete (signaled by callback)
                completion.await()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to request MediaProjection: ${e.message}", e)
                completion.complete(Unit) // Don't leave waiting
                // Fall back to text-only capture
                savePageWithoutScreenshot(titleHint, originalText)
            } finally {
                pendingSaveCompletion = null
            }
        }
    }

    /**
     * Proceed with screen capture after permission is granted.
     * Briefly hides the overlay, captures the screen, then saves as note.
     */
    private suspend fun proceedWithScreenCapture(titleHint: String?, originalText: String) {
        try {
            currentToolStatus.value = "Capturing screenshot..."

            // Briefly hide the assistant UI to capture the underlying screen
            withContext(Dispatchers.Main) {
                assistantPill.visibility = View.INVISIBLE
            }

            // Small delay to ensure UI is hidden
            kotlinx.coroutines.delay(100)

            // Capture screenshot
            val screenshotPath = ScreenCaptureService.captureScreenshot()

            // Restore UI
            withContext(Dispatchers.Main) {
                assistantPill.visibility = View.VISIBLE
            }

            if (screenshotPath != null) {
                Log.d(TAG, "Screenshot captured: $screenshotPath")
                savePageWithScreenshot(titleHint, originalText, screenshotPath)
            } else {
                Log.w(TAG, "Screenshot capture failed, falling back to text-only")
                savePageWithoutScreenshot(titleHint, originalText)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error during screen capture: ${e.message}", e)
            // Restore UI in case of error
            withContext(Dispatchers.Main) {
                assistantPill.visibility = View.VISIBLE
            }
            savePageWithoutScreenshot(titleHint, originalText)
        }
    }

    /**
     * Save page with screenshot attached.
     */
    private suspend fun savePageWithScreenshot(
        titleHint: String?,
        originalText: String,
        screenshotPath: String
    ) {
        try {
            val screenContext = capturedScreenContext
            currentToolStatus.value = "Saving..."

            // Copy screenshot to permanent storage
            val screenshotFile = java.io.File(screenshotPath)
            val permanentDir = java.io.File(filesDir, "screenshots")
            permanentDir.mkdirs()
            val permanentFile = java.io.File(permanentDir, "screenshot_${System.currentTimeMillis()}.png")

            withContext(Dispatchers.IO) {
                screenshotFile.copyTo(permanentFile, overwrite = true)
                screenshotFile.delete() // Clean up cache file
            }

            val screenshotUri = androidx.core.content.FileProvider.getUriForFile(
                this@AssistActivity,
                "${packageName}.fileprovider",
                permanentFile
            ).toString()

            // Build note content
            val content = buildScreenCaptureContent(screenContext, true)

            // Generate title - prioritize hint-based title
            val title = generateTitle(titleHint, screenContext)

            // Get or create category for screenshots
            val category = withContext(Dispatchers.IO) {
                repository.getOrCreateCategory("Screenshots")
            }

            // Generate basic tags from app name and title hint
            val appName = screenContext?.referringApp ?: "Unknown"
            val tags = mutableListOf<String>()
            if (appName != "Unknown" && appName.isNotBlank()) {
                tags.add(appName)
            }
            // Add cleaned hint words as tags if available
            titleHint?.split(" ")
                ?.filter { it.length > 3 && it.first().isUpperCase() }
                ?.take(3)
                ?.forEach { tags.add(it) }
            val tagsJson = if (tags.isNotEmpty()) {
                com.google.gson.Gson().toJson(tags)
            } else null

            // Create note with screenshot
            // Use COMPLETED status so AI doesn't reprocess and overwrite our title
            // Use NoteType.IMAGE so it displays correctly in Files tab
            val note = Note(
                id = UUID.randomUUID().toString(),
                title = title,
                content = content,
                type = NoteType.IMAGE,
                processingStatus = ProcessingStatus.COMPLETED,
                categoryId = category.id,
                categoryName = category.name,
                tagsJson = tagsJson,
                fileUri = screenshotUri,
                fileName = permanentFile.name,
                fileMimeType = "image/png",
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )

            // Debug logging
            Log.d(TAG, "Creating screenshot note:")
            Log.d(TAG, "  - id: ${note.id}")
            Log.d(TAG, "  - title: $title")
            Log.d(TAG, "  - titleHint: $titleHint")
            Log.d(TAG, "  - fileUri: $screenshotUri")
            Log.d(TAG, "  - fileName: ${permanentFile.name}")
            Log.d(TAG, "  - type: ${note.type}")
            Log.d(TAG, "  - categoryId: ${category.id}")
            Log.d(TAG, "  - categoryName: ${category.name}")
            Log.d(TAG, "  - tags: $tags")

            withContext(Dispatchers.IO) {
                repository.insertNote(note)
            }

            Log.d(TAG, "Page saved with screenshot: ${note.id}")
            currentToolStatus.value = null

            viewModel.addMessage(ChatMessage(
                role = ChatRole.ASSISTANT,
                content = " Page saved with screenshot: **$title**"
            ))

        } catch (e: Exception) {
            Log.e(TAG, "Error saving page with screenshot: ${e.message}", e)
            currentToolStatus.value = null
            viewModel.addMessage(ChatMessage(
                role = ChatRole.ASSISTANT,
                content = "Failed to save the page: ${e.localizedMessage ?: "Unknown error"}",
                isError = true
            ))
        }
    }

    /**
     * Save page without screenshot (fallback when capture fails or permission denied).
     */
    private suspend fun savePageWithoutScreenshot(titleHint: String?, originalText: String) {
        try {
            val screenContext = capturedScreenContext

            if (screenContext == null) {
                currentToolStatus.value = null
                viewModel.addMessage(ChatMessage(
                    role = ChatRole.ASSISTANT,
                    content = "I couldn't capture the screen context. Please try again.",
                    isError = true
                ))
                return
            }

            currentToolStatus.value = "Saving..."

            // Build note content
            val content = buildScreenCaptureContent(screenContext, false)

            // Generate title - prioritize hint-based title
            val title = generateTitle(titleHint, screenContext)

            // Get or create category for screen captures
            val category = withContext(Dispatchers.IO) {
                repository.getOrCreateCategory("Screen Captures")
            }

            // Generate basic tags from app name and title hint
            val appName = screenContext.referringApp ?: "Unknown"
            val tags = mutableListOf<String>()
            if (appName != "Unknown" && appName.isNotBlank()) {
                tags.add(appName)
            }
            titleHint?.split(" ")
                ?.filter { it.length > 3 && it.first().isUpperCase() }
                ?.take(3)
                ?.forEach { tags.add(it) }
            val tagsJson = if (tags.isNotEmpty()) {
                com.google.gson.Gson().toJson(tags)
            } else null

            // Create note without screenshot
            // Use COMPLETED status so AI doesn't reprocess and overwrite our title
            val note = Note(
                id = UUID.randomUUID().toString(),
                title = title,
                content = content,
                type = NoteType.BRAIN_DUMP,
                processingStatus = ProcessingStatus.COMPLETED,
                categoryId = category.id,
                categoryName = category.name,
                tagsJson = tagsJson,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )

            withContext(Dispatchers.IO) {
                repository.insertNote(note)
            }

            Log.d(TAG, "Page saved (text only): ${note.id}")
            Log.d(TAG, "  - categoryId: ${category.id}")
            Log.d(TAG, "  - categoryName: ${category.name}")
            Log.d(TAG, "  - tags: $tags")
            currentToolStatus.value = null

            viewModel.addMessage(ChatMessage(
                role = ChatRole.ASSISTANT,
                content = " Page saved: **$title**\n_(Screenshot unavailable - text context only)_"
            ))

        } catch (e: Exception) {
            Log.e(TAG, "Error saving page: ${e.message}", e)
            currentToolStatus.value = null
            viewModel.addMessage(ChatMessage(
                role = ChatRole.ASSISTANT,
                content = "Failed to save the page: ${e.localizedMessage ?: "Unknown error"}",
                isError = true
            ))
        }
    }

    /**
     * Build note content from screen context.
     */
    private fun buildScreenCaptureContent(
        screenContext: com.example.smarty.agent.tools.external.ScreenContext?,
        hasScreenshot: Boolean
    ): String {
        return buildString {
            if (hasScreenshot) {
                appendLine(" **Screenshot captured**")
                appendLine()
            }

            screenContext?.selectedText?.let {
                if (it.isNotBlank()) {
                    appendLine("**Selected Text:**")
                    appendLine(it)
                    appendLine()
                }
            }

            screenContext?.referringApp?.let {
                appendLine("**Source App:** $it")
            }

            screenContext?.capturedAt?.let {
                val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date(it))
                appendLine("**Captured:** $timestamp")
            }

            screenContext?.contextData?.let { data ->
                if (data.isNotEmpty()) {
                    appendLine()
                    appendLine("**Context:**")
                    data.forEach { (key, value) ->
                        appendLine("- $key: $value")
                    }
                }
            }
        }
    }

    /**
     * Generate a title for the saved page using AI.
     * - AI only knows there's a screenshot and the user's hint
     * - AI does NOT receive the screenshot filename or content
     */
    private suspend fun generateTitle(
        titleHint: String?,
        screenContext: com.example.smarty.agent.tools.external.ScreenContext?
    ): String {
        val appName = screenContext?.referringApp ?: "Screen"

        // If user provided a hint, use AI to generate a proper title
        if (!titleHint.isNullOrBlank()) {
            val aiTitle = generateTitleWithAI(titleHint, appName)
            if (aiTitle != null) {
                Log.d(TAG, "AI generated title: $aiTitle")
                return aiTitle
            }
            // Fallback: clean the hint manually
            val cleanedTitle = cleanHintToTitle(titleHint)
            if (cleanedTitle.isNotBlank()) {
                return cleanedTitle
            }
        }

        // No hint - just use "Screenshot [App Name]"
        return "Screenshot $appName"
    }

    /**
     * Use AI to generate a proper title from user's hint.
     * AI only knows: there's a screenshot from an app, and user's description.
     * AI does NOT receive: filename, file path, or image content.
     */
    private suspend fun generateTitleWithAI(hint: String, appName: String): String? {
        return try {
            currentToolStatus.value = "Generating title..."

            val systemPrompt = """Generate a very short title (2-4 words, max 30 characters).
RULES: Only output the title. No quotes. No punctuation. No explanation."""
            val userPrompt = "Screenshot from $appName. Description: $hint"

            val response = withContext(Dispatchers.IO) {
                aiService.simpleChat(systemPrompt, userPrompt)
            }

            // Clean up the response
            var title = response.trim()
                .removePrefix("\"").removeSuffix("\"")
                .removePrefix("Title:").removePrefix("title:")
                .removePrefix("**").removeSuffix("**")
                .lines().first()
                .trim()

            // Enforce word count limit (max 5 words)
            val words = title.split(" ").filter { it.isNotBlank() }
            if (words.size > 5) {
                title = words.take(4).joinToString(" ")
            }

            // Enforce character limit
            if (title.length > 40) {
                title = title.take(37) + "..."
            }

            if (title.isNotBlank() && title.length >= 2) {
                Log.d(TAG, "AI generated title: '$title' from hint: '$hint'")
                title
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "AI title generation failed: ${e.message}")
            null
        }
    }

    /**
     * Clean up the user's hint to create a proper title.
     * Example: "from my trip" -> "My Trip"
     * Example: "as a note to remember today's holiday" -> "Today's Holiday"
     */
    private fun cleanHintToTitle(hint: String): String {
        var cleaned = hint.trim()

        // Remove punctuation at start/end
        cleaned = cleaned.trimStart { it.isWhitespace() || it in ".,;:!?-–—" }
        cleaned = cleaned.trimEnd { it.isWhitespace() || it in ".,;:!?-–—" }

        if (cleaned.isBlank()) return ""

        // Remove common filler phrases (case-insensitive)
        // Order matters - check longer phrases first
        val prefixesToRemove = listOf(
            // Memory/reminder phrases
            "as a memory for this ",
            "as a memory for the ",
            "as a memory for my ",
            "as a memory for ",
            "as a memory of ",
            "as a reminder for this ",
            "as a reminder for the ",
            "as a reminder for my ",
            "as a reminder for ",
            "as a reminder of ",
            "as a note to remember ",
            "as a note about ",
            "as a note for ",
            "as a note to ",
            "as a note ",
            // Action phrases
            "to remember this ",
            "to remember the ",
            "to remember my ",
            "to remember ",
            "for reference ",
            "for later ",
            // Simple prefixes
            "remember ",
            "called ",
            "named ",
            "titled ",
            "about ",
            "from this ",
            "from the ",
            "from my ",
            "from ",
            "with ",
            "this ",
            "as ",
            "to ",
            "for ",
            "the ",
            "my ",
            "a "
        )

        val lowerCleaned = cleaned.lowercase(Locale.getDefault())
        for (prefix in prefixesToRemove) {
            if (lowerCleaned.startsWith(prefix)) {
                cleaned = cleaned.substring(prefix.length).trim()
                break
            }
        }

        if (cleaned.isBlank()) return ""

        // Title case: capitalize first letter of each word
        cleaned = cleaned.split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                word.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(Locale.getDefault())
                    else it.toString()
                }
            }

        // Limit length
        return if (cleaned.length > 60) cleaned.take(57) + "..." else cleaned
    }

    /**
     * Finish activity with smooth transition (no animation)
     */
    private fun finishWithAnimation() {
        stopListening()
        stopWakeWordDetection()
        // Save the chat session before closing
        viewModel.saveAndClose()
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, android.R.anim.fade_out)
    }

    /**
     * Fallback for back button press on older devices or when dispatcher doesn't work.
     * This ensures the assistant always closes on back navigation.
     */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        finishWithAnimation()
    }

    /**
     * Handle hardware key events - ensures back key always dismisses assistant
     */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            finishWithAnimation()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // Track if wake word manager has been initialized
    private var wakeWordInitialized = false

    /**
     * Initialize wake word manager - called AFTER initial speech Recognition completes
     * We delay this to prevent Vosk from grabbing the microphone before Google speech recognizer
     */
    private fun initWakeWordManager() {
        if (wakeWordInitialized) {
            Log.d(TAG, "Wake word manager already initialized")
            return
        }

        if (isListening.value || isProcessing.value) {
            Log.d(TAG, "Skipping wake word init - speech/processing active")
            return
        }

        wakeWordInitialized = true
        Log.d(TAG, "Initializing wake word manager")

        wakeWordManager = VoskWakeWordManager(this, lifecycleScope) {
            isWakeWordActive.value = false
            window.decorView.postDelayed({ startListening() }, 200)
        }
        wakeWordManager?.initialize()
    }
    
    private fun startWakeWordDetection() {
        // Don't start wake word if Google speech is active or processing
        if (isProcessing.value || isListening.value) {
            Log.d(TAG, "Skipping wake word - speech/processing active")
            return
        }

        // Initialize wake word manager if not done yet
        // This happens after first speech Recognition completes
        if (!wakeWordInitialized) {
            initWakeWordManager()
        }

        isWakeWordActive.value = true
        wakeWordManager?.restartListening()
    }
    
    private fun stopWakeWordDetection() {
        isWakeWordActive.value = false
        wakeWordManager?.stopListening()
    }
    
    private suspend fun loadNotesForContext() {
        try {
            cachedNotes = repository.getAllNotes().first()
            cachedCategories = repository.getAllCategories().first()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading notes: ${e.message}")
        }
    }
    
    private fun playAudio(track: AudioTrack) {
        try {
            AudioPlayerService.play(this, track)
        } catch (e: Exception) {
            Log.e(TAG, "Error playing audio: ${e.message}")
        }
    }
    
    private fun launchApp(pkg: String) {
        try {
            Log.d(TAG, "Attempting to launch app: $pkg")
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                Log.d(TAG, "Successfully launched app: $pkg")
                finishWithAnimation()
            } else {
                Log.w(TAG, "No launch intent found for package: $pkg")
                // App exists but doesn't have a launchable activity
                viewModel.addMessage(ChatMessage(
                    role = ChatRole.ASSISTANT,
                    content = "Couldn't launch this app - it may not have a main activity.",
                    isError = true
                ))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error launching app $pkg: ${e.message}")
            viewModel.addMessage(ChatMessage(
                role = ChatRole.ASSISTANT,
                content = "Error opening app: ${e.localizedMessage ?: "Unknown error"}",
                isError = true
            ))
        }
    }
    
    // Track if activity is in resumed state
    private var isActivityResumed = false
    // Track if we've actually tried to start speech (not just scheduled it)
    private var hasAttemptedSpeechStart = false

    override fun onResume() {
        super.onResume()
        isActivityResumed = true
        Log.d(TAG, "onResume - activity is visible")

        // CRITICAL: Pause all Vosk instances across the app to free up microphone
        // This must happen BEFORE speech Recognition starts
        com.example.smarty.voice.VoskWakeWordManager.isGloballyPaused = true
        Log.d(TAG, "Vosk globally paused for Google speech Recognition")

        // Start speech Recognition with a small delay to ensure Vosk has released the mic
        // This delay is necessary when the main app is running in the background
        if (!hasStartedListeningOnFocus && !hasFallbackStartedListening &&
            assistContext?.selectedText.isNullOrBlank() && !isListening.value && !isProcessing.value) {
            Log.d(TAG, "onResume: Scheduling speech Recognition after Vosk release")
            hasFallbackStartedListening = true
            hasAttemptedSpeechStart = true

            // Give Vosk 150ms to fully release the microphone before starting Google Speech
            // This prevents audio conflicts when the main app is running
            window.decorView.postDelayed({
                if (!isActivityResumed) {
                    Log.w(TAG, "Activity no longer resumed, skipping speech start")
                    return@postDelayed
                }
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "onResume: Starting speech Recognition after Vosk delay")
                    startListening()
                } else {
                    Log.e(TAG, "onResume: No RECORD_AUDIO permission")
                    inputField.hint = "Tap mic to speak..."
                }
            }, 150)
        }
    }

    override fun onPause() {
        super.onPause()
        isActivityResumed = false
        Log.d(TAG, "onPause - activity going to background")

        // Only stop listening if we've actually started and are listening
        // Don't cancel if we haven't had a chance to start yet
        if (hasAttemptedSpeechStart && (isListening.value || hasStartedListeningOnFocus)) {
            Log.d(TAG, "onPause: Stopping active speech Recognition")
            stopListening()
        } else {
            Log.d(TAG, "onPause: Speech not active, not stopping")
        }

        // Resume Vosk when AssistActivity goes to background
        com.example.smarty.voice.VoskWakeWordManager.isGloballyPaused = false
        Log.d(TAG, "Vosk global pause released")
    }
    
    override fun onDestroy() {
        Log.d(TAG, "onDestroy called")

        // Cancel any pending callbacks
        speechTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        mainHandler.removeCallbacksAndMessages(null)

        // Release audio focus
        releaseAudioFocus()

        // UI-003: Cancel any pending Recognition before destroying
        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
        wakeWordManager?.destroy()
        wakeWordManager = null

        // Ensure Vosk global pause is released on destroy
        com.example.smarty.voice.VoskWakeWordManager.isGloballyPaused = false
        Log.d(TAG, "onDestroy: Vosk global pause released")

        // UI-003: Call super.onDestroy() after cleanup
        super.onDestroy()
    }
}

// =============================================================================
// Composables for Chat Display
// =============================================================================

@Composable
fun MinimalResponseList(
    messages: List<ChatMessage>,
    isProcessing: Boolean,
    toolStatus: String? = null
) {
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, toolStatus) {
        if (messages.isNotEmpty() || toolStatus != null) {
            listState.animateScrollToItem(
                if (toolStatus != null) messages.size else messages.size - 1
            )
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(messages, key = { it.id }) { msg ->
            BubbleMessage(msg)
        }
        
        if (toolStatus != null) {
            item {
                Row(
                    modifier = Modifier
                        .padding(top = 4.dp, bottom = 8.dp)
                        .background(
                            color = GeminiColors.Blue.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(16.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_sparkle),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = GeminiColors.Blue
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = toolStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = GeminiColors.Blue,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        } else if (isProcessing) {
            item {
                Row(
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Thinking...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        fontStyle = FontStyle.Italic
                    )
                }
            }
        }
    }
}

@Composable
fun BubbleMessage(msg: ChatMessage) {
    val isUser = msg.role == ChatRole.USER
    val uriHandler = LocalUriHandler.current
    val isDarkTheme = isSystemInDarkTheme()

    // Theme-aware colors
    // User: Blue bubble with white text
    // AI: Theme surface with theme text color
    val userBubbleColor = GeminiColors.Blue
    val aiBubbleColor = if (isDarkTheme) {
        ComposeColor(0xFF1E1E1E) // Dark gray for dark theme
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    }

    val userTextColor = ComposeColor.White
    val aiTextColor = MaterialTheme.colorScheme.onSurface

    val normalColor = if (isUser) userTextColor else aiTextColor
    val boldColor = if (isUser) userTextColor else GeminiColors.Blue
    val linkColor = if (isDarkTheme) ComposeColor(0xFF82B1FF) else ComposeColor(0xFF1976D2)
    val codeColor = normalColor

    val annotatedText = parseMarkdownToAnnotatedString(
        content = msg.content,
        normalColor = normalColor,
        boldColor = boldColor,
        italicColor = normalColor,
        linkColor = linkColor,
        codeColor = codeColor
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        @Suppress("DEPRECATION")
        ClickableText(
            text = annotatedText,
            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
            modifier = Modifier
                .background(
                    color = if (isUser) userBubbleColor else aiBubbleColor,
                    shape = RoundedCornerShape(16.dp)
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            onClick = { offset ->
                annotatedText.getStringAnnotations(tag = "URL", start = offset, end = offset)
                    .firstOrNull()?.let { annotation ->
                        try { uriHandler.openUri(annotation.item) } catch (_: Exception) {}
                    }
            }
        )
    }
}

/**
 * Custom markdown parser - same as main chat.
 * Supports: **bold**, *italic*, `code`, [links](url), __underline__
 */
private fun parseMarkdownToAnnotatedString(
    content: String,
    normalColor: ComposeColor,
    boldColor: ComposeColor,
    italicColor: ComposeColor,
    linkColor: ComposeColor,
    codeColor: ComposeColor
): AnnotatedString {
    return buildAnnotatedString {
        var currentIndex = 0

        data class MarkdownMatch(
            val range: IntRange,
            val displayText: String,
            val style: SpanStyle,
            val isLink: Boolean = false,
            val url: String? = null
        )

        val matches = mutableListOf<MarkdownMatch>()

        // Bold: **text**
        Regex("\\*\\*(.+?)\\*\\*").findAll(content).forEach { match ->
            matches.add(MarkdownMatch(
                range = match.range,
                displayText = match.groupValues[1],
                style = SpanStyle(color = boldColor, fontWeight = FontWeight.Bold)
            ))
        }

        // Italic: *text* (but not **)
        Regex("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)").findAll(content).forEach { match ->
            val overlaps = matches.any { it.range.first <= match.range.last && it.range.last >= match.range.first }
            if (!overlaps) {
                matches.add(MarkdownMatch(
                    range = match.range,
                    displayText = match.groupValues[1],
                    style = SpanStyle(color = italicColor, fontStyle = FontStyle.Italic)
                ))
            }
        }

        // Inline code: `code`
        Regex("`([^`]+)`").findAll(content).forEach { match ->
            val overlaps = matches.any { it.range.first <= match.range.last && it.range.last >= match.range.first }
            if (!overlaps) {
                matches.add(MarkdownMatch(
                    range = match.range,
                    displayText = match.groupValues[1],
                    style = SpanStyle(
                        color = codeColor,
                        fontFamily = FontFamily.Monospace,
                        background = codeColor.copy(alpha = 0.1f)
                    )
                ))
            }
        }

        // Links: [text](url)
        Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)").findAll(content).forEach { match ->
            val overlaps = matches.any { it.range.first <= match.range.last && it.range.last >= match.range.first }
            if (!overlaps) {
                matches.add(MarkdownMatch(
                    range = match.range,
                    displayText = match.groupValues[1],
                    style = SpanStyle(
                        color = linkColor,
                        fontWeight = FontWeight.SemiBold,
                        textDecoration = TextDecoration.Underline
                    ),
                    isLink = true,
                    url = match.groupValues[2]
                ))
            }
        }

        // Underline: __text__
        Regex("__(.+?)__").findAll(content).forEach { match ->
            val overlaps = matches.any { it.range.first <= match.range.last && it.range.last >= match.range.first }
            if (!overlaps) {
                matches.add(MarkdownMatch(
                    range = match.range,
                    displayText = match.groupValues[1],
                    style = SpanStyle(color = normalColor, textDecoration = TextDecoration.Underline)
                ))
            }
        }

        val sortedMatches = matches.sortedBy { it.range.first }

        for (match in sortedMatches) {
            if (match.range.first > currentIndex) {
                withStyle(SpanStyle(color = normalColor)) {
                    append(content.substring(currentIndex, match.range.first))
                }
            }

            if (match.isLink && match.url != null) {
                pushStringAnnotation(tag = "URL", annotation = match.url)
                withStyle(match.style) { append(match.displayText) }
                pop()
            } else {
                withStyle(match.style) { append(match.displayText) }
            }

            currentIndex = match.range.last + 1
        }

        if (currentIndex < content.length) {
            withStyle(SpanStyle(color = normalColor)) {
                append(content.substring(currentIndex))
            }
        }
    }
}






