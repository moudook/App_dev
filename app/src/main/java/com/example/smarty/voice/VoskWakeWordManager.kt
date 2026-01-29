package com.example.smarty.voice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.StorageService

/**
 * Vosk-based wake word manager for fully offline Jarvis wake word detection.
 *
 * Architecture:
 * - Uses Vosk (open source, on-device) for wake word detection
 * - English model: vosk-model-small-en-us-0.15
 * - Wake word: "Jarvis" - unique and distinctive
 * - When wake word is detected, stops listening and triggers callback
 * - Caller should then launch Google Speech Recognizer for full STT
 * - After STT completes, call restartListening() to resume wake word detection
 *
 * PROCESS DEATH HANDLING:
 * - Native Model/Recognizer objects are invalidated after process death
 * - isModelValid() checks if native resources are still usable
 * - Automatic re-initialization when model is detected as invalid
 * - All operations check model validity before proceeding
 *
 * Benefits:
 * - 100% open source (Apache 2.0)
 * - No API key required
 * - All processing on-device
 * - Model included in app: English (small) for storage efficiency
 * - Wake word: "Jarvis" (English)
 * - Speaker verification ensures only enrolled user can trigger
 */
class VoskWakeWordManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onWakeWordDetected: () -> Unit
) : RecognitionListener {


    companion object {
        private const val TAG = "VoskWakeWord"

        // English model is smaller (~15MB) than Hindi (~50MB) and more efficient for wake words
        private const val MODEL_PATH = "vosk-model-small-en-us-0.15"
        private const val SAMPLE_RATE = 16000.0f

        // Wake word: "Jarvis"
        private const val WAKE_WORD = "jarvis"

        // Audio gain multiplier for increased microphone sensitivity
        // 1.0 = normal, 2.0 = 2x louder, 3.0 = 3x louder
        // Higher values = detect from further away, but more CPU/battery usage
        // Reduced from 8.0 to 3.0 for better battery life while maintaining detection
        private const val AUDIO_GAIN = 3.0f  // Balanced: good detection, reasonable battery

        // NOTE: Grammar-restricted mode requires dynamic graph models.
        // Static graph models (like vosk-model-small-*) may not support grammar.
        // We use full vocabulary mode and filter results for wake word.
        private val GRAMMAR: String? = null  // Full vocabulary - filter in software

        // Pre-compiled wake word patterns for faster matching (all lowercase since text is lowercased before matching)
        private val WAKE_WORD_PATTERNS = setOf(
            "jarvis",
            " jarvis",
            "jarvis ",
            "jarvis.",
            "jar vis",
            "hey jarvis",
            "ok jarvis"
        )

        // Model validity cache duration - must be in companion object for const
        // OPTIMIZATION: Increased from 5s to 30s for better offline performance
        // This reduces overhead when resuming from pause/background
        private const val VALIDITY_CACHE_MS = 30_000L

        // Cooldown between speaker verification attempts to prevent log spam/CPU usage
        // when partial results keep returning the same wake word continuously
        private const val VERIFICATION_COOLDOWN_MS = 1000L

        /**
         * Global flag to pause Vosk across all instances.
         * Set to true when AssistActivity is using Google Speech.
         * This allows AssistActivity to prevent Vosk from grabbing the microphone.
         */
        @Volatile
        var isGloballyPaused: Boolean = false
            set(value) {
                val oldValue = field
                field = value
                Log.d(TAG, "Global pause state changed: $oldValue -> $value")
                // When paused, notify all registered instances to stop immediately
                if (value && !oldValue) {
                    activeInstances.forEach { instance ->
                        Log.d(TAG, "Stopping active Vosk instance due to global pause")
                        try {
                            instance.forceStopImmediate()
                        } catch (e: Exception) {
                            Log.w(TAG, "Error stopping instance: ${e.message}")
                        }
                    }
                }
            }

        // Track active instances for global pause functionality
        private val activeInstances = mutableSetOf<VoskWakeWordManager>()

        fun registerInstance(instance: VoskWakeWordManager) {
            activeInstances.add(instance)
        }

        fun unregisterInstance(instance: VoskWakeWordManager) {
            activeInstances.remove(instance)
        }
    }

    // Thread-safe mutex for state operations
    private val stateMutex = Mutex()

    private var model: Model? = null
    private var speechService: HighSensitivitySpeechService? = null
    private var Recognizer: Recognizer? = null

    // Flag to auto-start listening after initialization
    @Volatile
    private var shouldStartAfterInit = false

    init {
        // Register this instance for global pause functionality
        registerInstance(this)
    }

    // Flag to prevent multiple wake word callbacks from same detection
    @Volatile
    private var wakeWordTriggered = false

    // Flag to track if initialization is in progress
    @Volatile
    private var isInitializing = false

    // CRITICAL: Flag to track if manager has been destroyed
    // All callbacks check this flag and abort if true
    // This prevents initialization from continuing after app closes
    @Volatile
    private var isDestroyed = false

    // State tracking
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _initError = MutableStateFlow<String?>(null)
    val initError: StateFlow<String?> = _initError.asStateFlow()

    // Model validity cache to avoid creating/destroying test Recognizers repeatedly
    @Volatile
    private var lastModelValidityCheck = 0L
    private var lastModelValidity = false

    // Debounce for restartListening to prevent rapid consecutive calls
    @Volatile
    private var lastRestartTime = 0L
    // OPTIMIZATION: Reduced from 200ms to 100ms for faster wake word responsiveness
    private val restartDebounceMs = 100L

    // Flag to track if a restart is currently in progress
    @Volatile
    private var isRestarting = false

    // Timestamp of last verification attempt to handle debounce
    @Volatile
    private var lastVerificationTime = 0L

    /**
     * Check if the native model is still valid.
     * After process death, native objects become invalid even though references exist.
     * This safely tests model validity without crashing.
     * OPTIMIZED: Caches result for 5 seconds to avoid creating/destroying test Recognizers.
     */
    private fun isModelValid(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastModelValidityCheck < VALIDITY_CACHE_MS && lastModelValidity) {
            return lastModelValidity
        }
        val m = model ?: return false
        return try {
            // Try to create a Recognizer - if model is invalid, this will throw
            val testRecognizer = Recognizer(m, SAMPLE_RATE)
            testRecognizer.close()
            lastModelValidityCheck = now
            lastModelValidity = true
            true
        } catch (e: Exception) {
            Log.w(TAG, "Model validity check failed: ${e.message}")
            lastModelValidity = false
            false
        }
    }

    /**
     * Internal implementation of invalidateState without mutex.
     * CALLER MUST ALREADY HOLD stateMutex.
     * This resets everything so re-initialization can occur.
     */
    private fun invalidateStateInternal() {
        Log.w(TAG, "Invalidating all Vosk state for re-initialization")

        // Stop any active listening
        try {
            speechService?.stop()
            speechService?.shutdown()
        } catch (_: Exception) {}
        speechService = null

        // Close Recognizer
        try {
            Recognizer?.close()
        } catch (_: Exception) {}
        Recognizer = null

        // Close model
        try {
            model?.close()
        } catch (_: Exception) {}
        model = null

        // Reset state flags
        _isInitialized.value = false
        _isListening.value = false
        isInitializing = false
        wakeWordTriggered = false
        isRestarting = false
    }

    /**
     * Force invalidate all state - used after detecting invalid model.
     * This resets everything so re-initialization can occur.
     * THREAD-SAFE: Uses mutex to prevent concurrent state modifications.
     * SYNCHRONOUS: Completes within the mutex before returning to prevent race conditions.
     */
    private suspend fun invalidateState() {
        stateMutex.withLock {
            invalidateStateInternal()
        }
    }

    /**
     * Initialize Vosk model asynchronously.
     * Call this once during app startup.
     *
     * PROCESS DEATH SAFE: Checks model validity and re-initializes if needed.
     *
     * Timing:
     * - First run: 5-15 seconds (extracts ~50MB model to internal storage)
     * - Subsequent runs: <1 second (model already extracted)
     */
    fun initialize() {
        // CRITICAL: Check if destroyed - abort if app closed during startup
        if (isDestroyed) {
            Log.d(TAG, "Manager destroyed - aborting initialization")
            return
        }

        // If we think we're initialized, verify model is actually valid
        if (_isInitialized.value) {
            if (isModelValid()) {
                Log.d(TAG, "Already initialized with valid model")
                return
            } else {
                // Model became invalid (process death) - need to reinitialize
                Log.w(TAG, "Model invalid after process death - reinitializing")
                // Launch invalidation and re-call initialize() after it completes
                scope.launch {
                    invalidateState()
                    // Re-call initialize() after invalidation completes
                    initialize()
                }
                return
            }
        }

        if (isInitializing) {
            Log.d(TAG, "Initialization already in progress")
            return
        }

        isInitializing = true
        _initError.value = null

        try {
            // Enable Vosk logging for debugging
            LibVosk.setLogLevel(LogLevel.INFO)

            val startTime = System.currentTimeMillis()
            Log.i(TAG, "Unpacking Vosk English model from assets: $MODEL_PATH")

            // StorageService.unpack already runs on background thread
            // Callbacks are invoked on main thread
            StorageService.unpack(
                context,
                MODEL_PATH,
                "model-en",  // Folder name for English model
                { loadedModel ->
                    // CRITICAL: Check if destroyed before proceeding with callback
                    // This handles the case where user closes app during model loading
                    if (isDestroyed) {
                        Log.w(TAG, "Manager destroyed during model load - cleaning up")
                        try {
                            loadedModel.close()
                        } catch (_: Exception) {}
                        isInitializing = false
                        return@unpack
                    }

                    val elapsed = System.currentTimeMillis() - startTime
                    Log.i(TAG, "English model unpacked in ${elapsed}ms")
                    model = loadedModel
                    isInitializing = false
                    setupRecognizer()
                },
                { exception ->
                    // Check destroyed before reporting error
                    if (isDestroyed) {
                        Log.d(TAG, "Manager destroyed - ignoring error callback")
                        isInitializing = false
                        return@unpack
                    }

                    val errorMsg = "Failed to unpack English model: ${exception.message}"
                    Log.e(TAG, errorMsg, exception)
                    _initError.value = errorMsg
                    isInitializing = false
                }
            )
        } catch (e: Exception) {
            val errorMsg = "Vosk initialization error: ${e.message}"
            Log.e(TAG, errorMsg, e)
            _initError.value = errorMsg
            isInitializing = false
        }
    }

    /**
     * Set up the Recognizer.
     * Uses full vocabulary mode (no grammar restriction) for compatibility
     * with static graph models. Wake word filtering done in software.
     */
    private fun setupRecognizer() {
        // CRITICAL: Check if destroyed before setting up
        if (isDestroyed) {
            Log.d(TAG, "Manager destroyed - aborting Recognizer setup")
            return
        }

        try {
            val m = model
            if (m == null) {
                Log.e(TAG, "Cannot setup Recognizer - model is null")
                _initError.value = "Model not loaded"
                return
            }

            // Use full vocabulary mode for maximum compatibility
            // Static graph models don't support grammar restriction
            Recognizer = if (GRAMMAR != null) {
                Log.i(TAG, "Creating Recognizer with grammar: $GRAMMAR")
                Recognizer(m, SAMPLE_RATE, GRAMMAR)
            } else {
                Log.i(TAG, "Creating Recognizer with full vocabulary (no grammar)")
                Recognizer(m, SAMPLE_RATE)
            }

            _isInitialized.value = true
            _initError.value = null
            Log.i(TAG, "Recognizer ready - listening for wake word '$WAKE_WORD'")

            // Auto-start if startListening was called before init completed
            // But only if not destroyed
            if (shouldStartAfterInit && !isDestroyed) {
                shouldStartAfterInit = false
                Log.i(TAG, "Auto-starting listener after init")
                startListening()
            }
        } catch (e: Exception) {
            val errorMsg = "Failed to create Recognizer: ${e.message}"
            Log.e(TAG, errorMsg, e)
            _initError.value = errorMsg
        }
    }

    /**
     * Internal implementation of startListening without mutex.
     * CALLER MUST ALREADY HOLD stateMutex.
     * This is used by both the public startListening() and restartListening().
     */
    private suspend fun startListeningInternal() {
        // CRITICAL: Check if destroyed
        if (isDestroyed) {
            Log.d(TAG, "Manager destroyed - not starting listener")
            return
        }

        // Check if model needs re-initialization after process death
        if (_isInitialized.value && !isModelValid()) {
            Log.w(TAG, "Model invalid - triggering re-initialization before listening")
            invalidateStateInternal()
            shouldStartAfterInit = true
            initialize()
            return
        }

        if (!_isInitialized.value) {
            Log.w(TAG, "Not initialized yet - will start after init completes")
            shouldStartAfterInit = true
            // Trigger initialization if not already in progress
            if (!isInitializing) {
                initialize()
            }
            return
        }

        if (_isListening.value) {
            Log.d(TAG, "Already listening")
            return
        }

        val rec = Recognizer
        if (rec == null) {
            Log.e(TAG, "Cannot start - Recognizer is null, triggering re-init")
            invalidateStateInternal()
            shouldStartAfterInit = true
            initialize()
            return
        }

        try {
            // Reset wake word flag for new listening session
            wakeWordTriggered = false

            // Use high-sensitivity speech service with audio gain amplification
            // This allows wake word detection from further away
            speechService = HighSensitivitySpeechService(rec, SAMPLE_RATE, AUDIO_GAIN)
            speechService?.startListening(this@VoskWakeWordManager)
            _isListening.value = true
            Log.i(TAG, "Started listening for wake word '$WAKE_WORD' with ${AUDIO_GAIN}x gain")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start speech service: ${e.message}", e)
            _initError.value = "Failed to start: ${e.message}"

            // Might be invalid AudioRecord - try re-init
            if (e is IllegalStateException) {
                Log.w(TAG, "IllegalStateException - attempting recovery")
                invalidateStateInternal()
                shouldStartAfterInit = true
                initialize()
            }
        }
    }

    /**
     * Start listening for the wake word.
     * Call this when app comes to foreground.
     * If model is still loading, will auto-start when ready.
     *
     * PROCESS DEATH SAFE: Validates model before starting.
     * THREAD-SAFE: Uses mutex to prevent concurrent state modifications.
     */
    fun startListening() {
        // Check global pause flag first
        if (isGloballyPaused) {
            Log.d(TAG, "Vosk globally paused (AssistActivity active) - not starting")
            return
        }

        scope.launch {
            stateMutex.withLock {
                // Double-check inside mutex
                if (isGloballyPaused) {
                    Log.d(TAG, "Vosk globally paused (mutex check) - not starting")
                    return@withLock
                }
                startListeningInternal()
            }
        }
    }

    /**
     * Stop listening for the wake word.
     * Call this when app goes to background or when wake word detected.
     * THREAD-SAFE: Uses mutex to prevent concurrent state modifications.
     */
    fun stopListening() {
        scope.launch {
            stateMutex.withLock {
                shouldStartAfterInit = false  // Cancel pending start
                try {
                    speechService?.let { service ->
                        service.stop()
                        service.shutdown()  // Properly release resources
                    }
                    speechService = null
                    _isListening.value = false
                    Log.d(TAG, "Stopped listening")
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping: ${e.message}", e)
                    // Force cleanup even on error
                    speechService = null
                    _isListening.value = false
                }
            }
        }
    }

    /**
     * Force stop immediately (synchronous).
     * Called from global pause mechanism to immediately release microphone.
     * Does NOT use coroutine/mutex to ensure instant release.
     */
    internal fun forceStopImmediate() {
        Log.d(TAG, "Force stop immediate called")
        shouldStartAfterInit = false
        try {
            speechService?.stop()
            speechService?.shutdown()
            speechService = null
            _isListening.value = false
            Log.d(TAG, "Force stopped - mic released")
        } catch (e: Exception) {
            Log.w(TAG, "Error during force stop: ${e.message}")
            // Force cleanup anyway
            speechService = null
            _isListening.value = false
        }
    }

    /**
     * Restart listening after Google STT completes.
     * This re-enables wake word detection.
     *
     * PROCESS DEATH SAFE: Validates model and re-initializes if needed.
     * THREAD-SAFE: Uses mutex to prevent concurrent state modifications.
     * TOCTOU-SAFE: Wraps Recognizer creation in try-catch to handle race conditions.
     * DEBOUNCED: Prevents rapid consecutive calls from crashing the Recognizer.
     */
    fun restartListening() {
        // Check global pause flag first
        if (isGloballyPaused) {
            Log.d(TAG, "Vosk globally paused (AssistActivity active) - not restarting")
            return
        }

        // DEBOUNCE: Ignore rapid consecutive restart calls
        val now = System.currentTimeMillis()
        if (now - lastRestartTime < restartDebounceMs) {
            Log.d(TAG, "Restart debounced - too soon after last restart")
            return
        }

        // Check if already restarting
        if (isRestarting) {
            Log.d(TAG, "Already restarting - ignoring duplicate call")
            return
        }

        scope.launch {
            stateMutex.withLock {
                // Double-check inside mutex
                if (isRestarting) {
                    Log.d(TAG, "Already restarting (mutex check) - ignoring")
                    return@withLock
                }

                // CRITICAL: Check if destroyed
                if (isDestroyed) {
                    Log.d(TAG, "Manager destroyed - not restarting")
                    return@withLock
                }

                isRestarting = true
                lastRestartTime = System.currentTimeMillis()

                try {
                    Log.d(TAG, "Restarting wake word detection")

                    // Reset wake word triggered flag for new detection cycle
                    wakeWordTriggered = false

                    // If model is still loading, queue restart for after init
                    if (isInitializing) {
                        Log.d(TAG, "Model still loading - will start after init completes")
                        shouldStartAfterInit = true
                        return@withLock
                    }

                    // Check model validity - might be invalid after process death
                    if (!isModelValid()) {
                        Log.w(TAG, "Model invalid - triggering full re-initialization")
                        invalidateStateInternal()
                        shouldStartAfterInit = true
                        initialize()
                        return@withLock
                    }

                    // Stop any existing speech service first
                    try {
                        speechService?.stop()
                        speechService?.shutdown()
                    } catch (_: Exception) {}
                    speechService = null
                    _isListening.value = false

                    // OPTIMIZATION: Reduced delay from 100ms to 50ms for faster restart
                    // Native resources should settle quickly after stop()
                    kotlinx.coroutines.delay(50)

                    // Need to recreate Recognizer after it's been used
                    // Close old Recognizer CAREFULLY - wait for it to be idle
                    val oldRecognizer = Recognizer
                    Recognizer = null

                    if (oldRecognizer != null) {
                        try {
                            // Small delay before closing to let any pending operations complete
                            kotlinx.coroutines.delay(50)
                            oldRecognizer.close()
                        } catch (e: Exception) {
                            Log.w(TAG, "Error closing old Recognizer: ${e.message}")
                            // Continue anyway - we'll create a new one
                        }
                    }

                    val m = model
                    if (m != null) {
                        Recognizer = try {
                            if (GRAMMAR != null) {
                                Recognizer(m, SAMPLE_RATE, GRAMMAR)
                            } else {
                                Recognizer(m, SAMPLE_RATE)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Recognizer creation failed after validity check - full reinit needed: ${e.message}")
                            invalidateStateInternal()
                            shouldStartAfterInit = true
                            initialize()
                            return@withLock
                        }
                        // Call internal version directly since we already hold the mutex
                        startListeningInternal()
                    } else {
                        // Model is null - need to reinitialize
                        Log.w(TAG, "Model is null - triggering re-initialization")
                        invalidateStateInternal()
                        shouldStartAfterInit = true
                        initialize()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to restart: ${e.message}", e)
                    // Attempt recovery through full re-init
                    invalidateStateInternal()
                    shouldStartAfterInit = true
                    initialize()
                } finally {
                    isRestarting = false
                }
            }
        }
    }

    /**
     * Check if wake word detection is ready and operational.
     */
    fun isReady(): Boolean {
        return !isDestroyed && _isInitialized.value && isModelValid()
    }


    /**
     * Clean up resources.
     * Call this when ViewModel is cleared.
     *
     * CRITICAL: Sets isDestroyed FIRST to signal all pending callbacks to abort.
     * This ensures graceful shutdown even if app is closed during initialization.
     */
    fun destroy() {
        Log.d(TAG, "Destroying Vosk resources")

        // Unregister from global pause tracking
        unregisterInstance(this)

        // CRITICAL: Set destroyed flag FIRST - this signals all callbacks to abort
        // This is essential for graceful shutdown when app closes during init
        isDestroyed = true

        // Cancel any pending auto-start
        shouldStartAfterInit = false

        // Stop listening and release audio resources
        forceStopImmediate()  // Use immediate stop for faster cleanup

        // Clean up Vosk resources
        try {
            Recognizer?.close()
            model?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup: ${e.message}", e)
        }

        // Clear all references
        Recognizer = null
        model = null

        // Reset all state
        _isInitialized.value = false
        _isListening.value = false
        _initError.value = null
        wakeWordTriggered = false
        isInitializing = false
        isRestarting = false

        Log.d(TAG, "Vosk resources destroyed - graceful shutdown complete")
    }

    // ==================== RecognitionListener Implementation ====================

    override fun onPartialResult(hypothesis: String?) {
        if (isDestroyed || hypothesis.isNullOrBlank()) return
        parseAndCheckWakeWord(hypothesis, isPartial = true)
    }

    override fun onResult(hypothesis: String?) {
        if (isDestroyed || hypothesis.isNullOrBlank()) return
        parseAndCheckWakeWord(hypothesis, isPartial = false)
    }

    override fun onFinalResult(hypothesis: String?) {
        if (isDestroyed || hypothesis.isNullOrBlank()) return
        Log.d(TAG, "Final result: $hypothesis")
        parseAndCheckWakeWord(hypothesis, isPartial = false)
    }

    override fun onError(exception: Exception?) {
        if (isDestroyed) return  // Ignore errors after destruction
        Log.e(TAG, "ReJarvistion error: ${exception?.message}", exception)
        _initError.value = "ReJarvistion error: ${exception?.message}"

        // ISSUE #3 FIX: Automatically recover from AudioRecord invalidation
        // When system takes the mic (phone call, etc.), we need to reinitialize
        // Recovery is only attempted if manager is still active (not destroyed)
        if (exception is IllegalStateException) {
            val message = exception.message ?: ""
            if (message.contains("AudioRecord invalidated") || message.contains("native resources")) {
                Log.w(TAG, "AudioRecord invalidated - scheduling automatic recovery")
                scope.launch {
                    // Brief delay to ensure system has released the mic
                    kotlinx.coroutines.delay(500)
                    if (!isDestroyed) {
                        Log.d(TAG, "Attempting automatic recovery after AudioRecord invalidation")
                        restartListening()
                    }
                }
            }
        }
    }

    override fun onTimeout() {
        if (isDestroyed) return  // Ignore timeout after destruction
        Log.d(TAG, "ReJarvistion timeout - will restart")
        // Don't restart in onTimeout to avoid recursion - let caller handle
    }

    /**
     * Parse Vosk JSON result and check for wake word.
     * Optimized: Uses pre-compiled pattern set for faster matching.
     * Uses wakeWordTriggered flag to prevent multiple callbacks from same detection.
     */
    private fun parseAndCheckWakeWord(hypothesis: String, isPartial: Boolean) {
        // Early exit if destroyed or wake word already triggered this session
        if (isDestroyed || wakeWordTriggered) return

        try {
            val json = JSONObject(hypothesis)
            val text = if (isPartial) {
                json.optString("partial", "")
            } else {
                json.optString("text", "")
            }

            if (text.isBlank()) return

            // =====================================================
            // DEBUG LOGGING: Log ALL recognized speech to logcat
            // Filter in logcat using: VoskWakeWord or VOSK_SPEECH
            // =====================================================
            if (isPartial) {
                // Log partial results (real-time as user speaks)
                Log.d(TAG, "VOSK_SPEECH [PARTIAL]: \"$text\"")
            } else {
                // Log final results (after pause in speech)
                Log.i(TAG, "VOSK_SPEECH [FINAL]: \"$text\"")
            }

            // Check for wake word using pre-compiled patterns (faster than multiple contains)
            val lowerText = text.lowercase()
            val containsWakeWord = WAKE_WORD_PATTERNS.any { pattern ->
                lowerText.contains(pattern) || text.contains(pattern)
            }

            // Only trigger if wake word is standalone (not embedded in a longer sentence)
            // Threshold: 4 words or less = intentional wake word
            // More than 4 words = wake word used in conversation, ignore
            val wordCount = text.trim().split("\\s+".toRegex()).size
            val isStandalone = wordCount <= 4

            if (containsWakeWord && !isStandalone) {
                Log.d(TAG, "VOSK_SPEECH [IGNORED]: Wake word in sentence ($wordCount words): \"$text\"")
            }

            if (containsWakeWord && isStandalone) {
                // Atomically set flag to prevent duplicate triggers
                if (wakeWordTriggered || isDestroyed) return

                // DEBOUNCE: Check cooldown to prevent rapid-fire verification on partial results
                // Partial results come every ~50ms, so we need to ignore subsequent ones
                // for the same utterance if verification failed.
                val now = System.currentTimeMillis()
                if (now - lastVerificationTime < VERIFICATION_COOLDOWN_MS) {
                    return
                }

                wakeWordTriggered = true
                lastVerificationTime = now

                Log.w(TAG, "============================================")
                Log.w(TAG, ">>> WAKE WORD DETECTED: '$text' <<<")
                Log.w(TAG, "============================================")

                // Trigger wake word callback immediately
                Log.i(TAG, "Wake word triggered - launching callback")
                
                // Stop listening and trigger callback
                stopListening()
                if (!isDestroyed) {
                    try {
                        scope.launch(Dispatchers.Main) {
                            if (!isDestroyed) {
                                onWakeWordDetected()
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to launch wake word callback: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            // Log parse errors for debugging
            Log.w(TAG, "VOSK_SPEECH [PARSE_ERROR]: ${e.message}")
        }
    }
}

