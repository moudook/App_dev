package com.example.smarty

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
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
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.example.smarty.agent.AgentCallbacks
import com.example.smarty.agent.AgentResult
import com.example.smarty.agent.CogniAgent
import com.example.smarty.agent.CogniAgentProvider
import com.example.smarty.agent.WebCitation
import com.example.smarty.data.local.CogniDatabase
import com.example.smarty.data.local.SecurePreferences
import com.example.smarty.data.model.AudioTrack
import com.example.smarty.data.model.Category
import com.example.smarty.data.model.ChatMessage
import com.example.smarty.data.model.ChatRole
import com.example.smarty.data.model.Note
import com.example.smarty.data.model.ProcessingStatus
import com.example.smarty.data.remote.providers.TavilySearchProvider
import com.example.smarty.data.repository.ChatRepository
import com.example.smarty.data.repository.CogniRepository
import com.example.smarty.service.AlarmScheduler
import com.example.smarty.service.AudioPlayerService
import com.example.smarty.service.CommandResult
import com.example.smarty.service.LocalCommandProcessor
import com.example.smarty.ui.theme.CogniTheme
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
    private lateinit var composeView: ComposeView

    // Speech recognition
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = mutableStateOf(false)
    private var partialText = mutableStateOf("")

    // Wake word detection
    private var wakeWordManager: VoskWakeWordManager? = null
    private var isWakeWordActive = mutableStateOf(false)

    // Chat state
    private val messages = mutableStateListOf<ChatMessage>()
    private var isProcessing = mutableStateOf(false)

    // Lazy dependencies
    private val securePreferences: SecurePreferences by lazy { SecurePreferences.getInstance(application) }
    private val database: CogniDatabase by lazy { CogniDatabase.getDatabase(application) }
    private val repository: CogniRepository by lazy {
        CogniRepository(database.noteDao(), database.categoryDao(), database.calendarDao(), database.noteVersionDao())
    }
    private val chatRepository by lazy<ChatRepository> { ChatRepository(database.chatDao()) }
    private val groqKeyManager: GroqKeyManager by lazy { GroqKeyManager.getInstance(application) }
    private val tavilySearchProvider: TavilySearchProvider by lazy {
        val httpClient = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        TavilySearchProvider(httpClient, Gson())
    }
    private val alarmScheduler: AlarmScheduler by lazy { AlarmScheduler.getInstance(application) }
    private val agentProvider: CogniAgentProvider by lazy { CogniAgentProvider(securePreferences, groqKeyManager) }
    
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
            // Mark as completed immediately - no AI processing in assistant mode for speed
            repository.updateNote(note.copy(processingStatus = ProcessingStatus.COMPLETED))
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

        override fun onCitationsFound(citations: List<WebCitation>) {
            runOnUiThread {
                // Store citations for display in response
                pendingCitations.addAll(citations)
                Log.d(TAG, "Citations found: ${citations.size} sources")
            }
        }
    }

    private val cogniAgent: CogniAgent by lazy {
        CogniAgent(agentProvider, repository, tavilySearchProvider, alarmScheduler, agentCallbacks)
    }

    private val localCommandProcessor: LocalCommandProcessor by lazy {
        LocalCommandProcessor(this, { cachedNotes }, { playAudio(it) }, { launchApp(it) })
    }

    private var sessionId: String? = null

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

        // Extract assist context from intent
        extractAssistContext()

        // Initialize Views
        initViews()

        // Logic Initialization
        initSpeechRecognizer()
        initWakeWordManager()

        // Session
        lifecycleScope.launch {
            loadNotesForContext()
            val session = chatRepository.createNewSession()
            sessionId = session.id
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
            window.decorView.postDelayed({
                inputField.setText(assistContext?.selectedText)
                inputField.setSelection(inputField.text.length)
            }, 300)
        } else {
            Log.d(TAG, "No selected text - voice will auto-start when window gains focus")
        }
    }

    /**
     * Extract context from the assist intent (selected text, referring app)
     */
    private fun extractAssistContext() {
        try {
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        }
    }

    // Track if we've already started listening
    private var hasStartedListeningOnFocus = false

    /**
     * Called when window focus changes - best time to start speech recognition
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        Log.d(TAG, "onWindowFocusChanged: hasFocus=$hasFocus, hasStartedListeningOnFocus=$hasStartedListeningOnFocus")

        if (hasFocus && !hasStartedListeningOnFocus && assistContext?.selectedText.isNullOrBlank()) {
            hasStartedListeningOnFocus = true
            Log.d(TAG, "Window has focus - starting speech recognition")
            // Small delay to ensure everything is fully ready
            window.decorView.postDelayed({
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    startListening()
                } else {
                    Log.e(TAG, "No RECORD_AUDIO permission when trying to auto-start")
                    inputField.hint = "Tap mic to speak..."
                }
            }, 300)
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
        composeView = findViewById(R.id.response_content)
        
        // Tap outside the pill = dismiss
        touchOutsideInterceptor.setOnClickListener { 
            finishWithAnimation()
        }
        
        // Prevent clicks on pill from propagating to the interceptor
        assistantPill.setOnClickListener { /* consume click */ }
        
        micButton.setOnClickListener { toggleListening() }
        
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
            CogniTheme(darkTheme = isDarkTheme, isTransparent = true) {
                 if (messages.isNotEmpty() || isProcessing.value) {
                     MinimalResponseList(
                         messages = messages,
                         isProcessing = isProcessing.value,
                         toolStatus = toolStatus
                     )
                 }
            }
        }
    }

    private fun initSpeechRecognizer() {
        Log.d(TAG, "Initializing speech recognizer...")

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e(TAG, "Speech recognition NOT available on this device!")
            runOnUiThread {
                inputField.hint = "Voice unavailable - type here"
            }
            return
        }

        Log.d(TAG, "Speech recognition is available, creating recognizer...")

        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        Log.d(TAG, "onReadyForSpeech - recognizer is ready")
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
                        val errorMessage = when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                            SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                            SpeechRecognizer.ERROR_NETWORK -> "Network error"
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                            SpeechRecognizer.ERROR_NO_MATCH -> "No speech match"
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                            SpeechRecognizer.ERROR_SERVER -> "Server error"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
                            else -> "Unknown error ($error)"
                        }
                        Log.e(TAG, "Speech recognition error: $errorMessage")
                        isListening.value = false

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
                            window.decorView.postDelayed({ inputField.hint = "Ask anything..." }, 2000)
                        }
                    }
                    override fun onResults(results: Bundle?) {
                        Log.d(TAG, "onResults received")
                        isListening.value = false
                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        Log.d(TAG, "Speech result: $text")
                        if (!text.isNullOrBlank()) {
                            runOnUiThread { inputField.setText(text) }
                            processInput(text)
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
            }
            Log.d(TAG, "Speech recognizer created successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create speech recognizer: ${e.message}", e)
            runOnUiThread {
                inputField.hint = "Voice unavailable - type here"
            }
        }
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

        // Stop wake word to free up audio
        stopWakeWordDetection()

        // Check recognizer state
        if (speechRecognizer == null) {
            Log.e(TAG, "Speech recognizer is NULL - recreating...")
            initSpeechRecognizer()
            if (speechRecognizer == null) {
                Log.e(TAG, "Failed to recreate speech recognizer")
                return
            }
        }

        if (isListening.value) {
            Log.d(TAG, "Already listening, ignoring startListening call")
            return
        }

        try {
            Log.d(TAG, "Creating speech recognition intent...")
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                // Add prefer offline if available (faster)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            }

            Log.d(TAG, "Calling speechRecognizer.startListening()...")
            runOnUiThread {
                inputField.hint = "Starting voice..."
                micButton.setColorFilter(Color.parseColor("#FFA500")) // Orange during startup
            }
            speechRecognizer?.startListening(intent)
            Log.d(TAG, "startListening() call completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start listening: ${e.message}", e)
            runOnUiThread {
                inputField.hint = "Voice error - type instead"
                micButton.clearColorFilter()
            }
        }
    }

    private fun stopListening() {
        try {
            speechRecognizer?.stopListening()
            isListening.value = false
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
            messages.add(userMessage)
            isProcessing.value = true

            try {
                val commandResult = localCommandProcessor.process(text)
                when (commandResult) {
                    is CommandResult.Handled -> {
                        val assistantMessage = ChatMessage(role = ChatRole.ASSISTANT, content = commandResult.response)
                        messages.add(assistantMessage)
                    }
                    is CommandResult.PassToLLM -> {
                         val cleanHistory = messages.filter { it.role != ChatRole.SYSTEM }.map { 
                             (if(it.role==ChatRole.USER) "USER" else "ASSISTANT") to it.content 
                         }.takeLast(10)
                         
                         val result = withContext(Dispatchers.IO) {
                             cogniAgent.run(text, cleanHistory)
                         }
                         
                         val response = when (result) {
                             is AgentResult.Success -> result.response
                             is AgentResult.Error -> result.message
                             is AgentResult.NoProvider -> "Please set up an AI provider in settings."
                         }
                         messages.add(ChatMessage(role = ChatRole.ASSISTANT, content = response, isError = result is AgentResult.Error))
                    }
                }
            } catch (e: Exception) {
                messages.add(ChatMessage(role = ChatRole.ASSISTANT, content = "Error: ${e.message}", isError = true))
            } finally {
                isProcessing.value = false
                startWakeWordDetection()
            }
        }
    }

    /**
     * Finish activity with smooth transition (no animation)
     */
    private fun finishWithAnimation() {
        stopListening()
        stopWakeWordDetection()
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

    private fun initWakeWordManager() {
        wakeWordManager = VoskWakeWordManager(this, lifecycleScope) {
            isWakeWordActive.value = false
            window.decorView.postDelayed({ startListening() }, 200)
        }
        wakeWordManager?.initialize()
    }
    
    private fun startWakeWordDetection() {
        if (isProcessing.value) return
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
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                finishWithAnimation()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error launching app: ${e.message}")
        }
    }
    
    override fun onPause() {
        super.onPause()
        stopListening()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
        speechRecognizer = null
        wakeWordManager?.destroy()
        wakeWordManager = null
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

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(messages, key = { it.id }) { msg ->
            BubbleMessage(msg)
        }
        if (isProcessing) {
            item {
                // Show tool status if available, otherwise show generic "Thinking..."
                val statusText = toolStatus ?: "Thinking..."
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

@Composable
fun BubbleMessage(msg: ChatMessage) {
    val isUser = msg.role == ChatRole.USER
    val uriHandler = LocalUriHandler.current

    // Blue color scheme for assistant messages (like main chat)
    val accentColor = GeminiColors.Blue
    val normalColor = if (isUser) ComposeColor.White else ComposeColor(
        red = (accentColor.red * 0.75f).coerceIn(0f, 1f),
        green = (accentColor.green * 0.75f).coerceIn(0f, 1f),
        blue = (accentColor.blue * 0.85f).coerceIn(0f, 1f),
        alpha = 1f
    )
    val boldColor = if (isUser) ComposeColor.White else accentColor
    val linkColor = ComposeColor(0xFF9C27B0) // Purple for links
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
        ClickableText(
            text = annotatedText,
            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
            modifier = Modifier
                .background(
                    color = if (isUser) GeminiColors.Blue else MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium
                )
                .padding(12.dp),
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
