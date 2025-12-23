package com.example.smarty.voice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.StorageService

/**
 * Vosk-based wake word manager for fully offline "Terminator" detection.
 *
 * Architecture:
 * - Uses Vosk (open source, on-device) for wake word detection
 * - When "terminator" is detected, stops listening and triggers callback
 * - Caller should then launch Google Speech Recognizer for full STT
 * - After STT completes, call restartListening() to resume wake word detection
 *
 * Benefits over Porcupine:
 * - 100% open source (Apache 2.0)
 * - No API key required
 * - All processing on-device
 * - Model included in app
 */
class VoskWakeWordManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onWakeWordDetected: () -> Unit
) : RecognitionListener {

    companion object {
        private const val TAG = "VoskWakeWord"
        private const val MODEL_PATH = "vosk-model-small-en-in-0.4"
        private const val SAMPLE_RATE = 16000.0f
        private const val WAKE_WORD = "hello reddit"

        // Audio gain multiplier for increased microphone sensitivity
        // 1.0 = normal, 2.0 = 2x louder, 3.0 = 3x louder
        // Higher values = detect from further away, but more background noise
        private const val AUDIO_GAIN = 3.0f  // 3x amplification for long-range detection

        // NOTE: Grammar-restricted mode requires dynamic graph models.
        // Static graph models (like vosk-model-small-*) may not support grammar.
        // We use full vocabulary mode and filter results for wake word.
        private val GRAMMAR: String? = null  // Full vocabulary - filter in software

        // Pre-compiled wake word patterns for faster matching
        // Expanded set for better detection at distance (more fuzzy matches)
        private val WAKE_WORD_PATTERNS = setOf(
            // Primary patterns
            "hello reddit", "hello read it", "hello red it", "hallo reddit",
            "hollow reddit", "hello redditt", "hello readit", "hello reddit's",
            // Variations for distance/quiet speech
            "hello ready", "hello red", "ello reddit", "yellow reddit",
            "jello reddit", "fellow reddit", "hello credit", "hello edit",
            "lo reddit", "hello reading", "hello redid", "hello rabbit",
            // Additional fuzzy patterns for long-range detection
            "hello read", "hello rea", "ello read", "helo reddit",
            "hell reddit", "hello redd", "hello redit", "allo reddit",
            "hellow reddit", "hullo reddit", "halo reddit", "hello reddit it",
            "hello read itt", "hello reddit you", "hello reddits"
        )
    }

    private var model: Model? = null
    private var speechService: HighSensitivitySpeechService? = null  // Custom high-gain audio service
    private var recognizer: Recognizer? = null

    // Flag to auto-start listening after initialization
    private var shouldStartAfterInit = false

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

    /**
     * Initialize Vosk model asynchronously.
     * Call this once during app startup.
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
        if (_isInitialized.value) {
            Log.d(TAG, "Already initialized")
            return
        }
        if (isInitializing) {
            Log.d(TAG, "Initialization already in progress")
            return
        }

        isInitializing = true

        try {
            // Enable Vosk logging for debugging
            LibVosk.setLogLevel(LogLevel.INFO)

            val startTime = System.currentTimeMillis()
            Log.i(TAG, "Unpacking Vosk model from assets: $MODEL_PATH")

            // StorageService.unpack already runs on background thread
            // Callbacks are invoked on main thread
            StorageService.unpack(
                context,
                MODEL_PATH,
                "model",
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
                    Log.i(TAG, "Model unpacked in ${elapsed}ms")
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

                    val errorMsg = "Failed to unpack model: ${exception.message}"
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
     * Set up the recognizer.
     * Uses full vocabulary mode (no grammar restriction) for compatibility
     * with static graph models. Wake word filtering done in software.
     */
    private fun setupRecognizer() {
        // CRITICAL: Check if destroyed before setting up
        if (isDestroyed) {
            Log.d(TAG, "Manager destroyed - aborting recognizer setup")
            return
        }

        try {
            model?.let { m ->
                // Use full vocabulary mode for maximum compatibility
                // Static graph models don't support grammar restriction
                recognizer = if (GRAMMAR != null) {
                    Log.i(TAG, "Creating recognizer with grammar: $GRAMMAR")
                    Recognizer(m, SAMPLE_RATE, GRAMMAR)
                } else {
                    Log.i(TAG, "Creating recognizer with full vocabulary (no grammar)")
                    Recognizer(m, SAMPLE_RATE)
                }

                _isInitialized.value = true
                _initError.value = null
                Log.i(TAG, "Recognizer ready - listening for '$WAKE_WORD'")

                // Auto-start if startListening was called before init completed
                // But only if not destroyed
                if (shouldStartAfterInit && !isDestroyed) {
                    shouldStartAfterInit = false
                    Log.i(TAG, "Auto-starting listener after init")
                    startListening()
                }
            } ?: run {
                Log.e(TAG, "Cannot setup recognizer - model is null")
                _initError.value = "Model not loaded"
            }
        } catch (e: Exception) {
            val errorMsg = "Failed to create recognizer: ${e.message}"
            Log.e(TAG, errorMsg, e)
            _initError.value = errorMsg
        }
    }

    /**
     * Start listening for the wake word.
     * Call this when app comes to foreground.
     * If model is still loading, will auto-start when ready.
     */
    fun startListening() {
        // CRITICAL: Check if destroyed
        if (isDestroyed) {
            Log.d(TAG, "Manager destroyed - not starting listener")
            return
        }
        if (!_isInitialized.value) {
            Log.w(TAG, "Not initialized yet - will start after init completes")
            shouldStartAfterInit = true
            return
        }
        if (_isListening.value) {
            Log.d(TAG, "Already listening")
            return
        }

        val rec = recognizer
        if (rec == null) {
            Log.e(TAG, "Cannot start - recognizer is null")
            return
        }

        try {
            // Reset wake word flag for new listening session
            wakeWordTriggered = false

            // Use high-sensitivity speech service with audio gain amplification
            // This allows wake word detection from further away
            speechService = HighSensitivitySpeechService(rec, SAMPLE_RATE, AUDIO_GAIN)
            speechService?.startListening(this)
            _isListening.value = true
            Log.i(TAG, "Started listening for wake word '$WAKE_WORD' with ${AUDIO_GAIN}x gain")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start speech service: ${e.message}", e)
            _initError.value = "Failed to start: ${e.message}"
        }
    }

    /**
     * Stop listening for the wake word.
     * Call this when app goes to background or when wake word detected.
     */
    fun stopListening() {
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
        }
    }

    /**
     * Restart listening after Google STT completes.
     * This re-enables wake word detection.
     */
    fun restartListening() {
        // CRITICAL: Check if destroyed
        if (isDestroyed) {
            Log.d(TAG, "Manager destroyed - not restarting")
            return
        }

        Log.d(TAG, "Restarting wake word detection")

        // Reset wake word triggered flag for new detection cycle
        wakeWordTriggered = false

        // If model is still loading, queue restart for after init
        if (isInitializing) {
            Log.d(TAG, "Model still loading - will start after init completes")
            shouldStartAfterInit = true
            return
        }

        // Need to recreate recognizer after it's been used
        try {
            // Close old recognizer to prevent resource leak
            try {
                recognizer?.close()
            } catch (_: Exception) {
                // Ignore close errors
            }
            recognizer = null

            model?.let { m ->
                recognizer = if (GRAMMAR != null) {
                    Recognizer(m, SAMPLE_RATE, GRAMMAR)
                } else {
                    Recognizer(m, SAMPLE_RATE)
                }
                startListening()
            } ?: run {
                // Model is null and not initializing - need to reinitialize
                Log.w(TAG, "Model is null - triggering re-initialization")
                shouldStartAfterInit = true
                initialize()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart: ${e.message}", e)
        }
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

        // CRITICAL: Set destroyed flag FIRST - this signals all callbacks to abort
        // This is essential for graceful shutdown when app closes during init
        isDestroyed = true

        // Cancel any pending auto-start
        shouldStartAfterInit = false

        // Stop listening and release audio resources
        stopListening()

        // Clean up Vosk resources
        try {
            recognizer?.close()
            model?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup: ${e.message}", e)
        }

        // Clear all references
        recognizer = null
        model = null

        // Reset all state
        _isInitialized.value = false
        _isListening.value = false
        _initError.value = null
        wakeWordTriggered = false
        isInitializing = false

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
        Log.e(TAG, "Recognition error: ${exception?.message}", exception)
        _initError.value = "Recognition error: ${exception?.message}"
    }

    override fun onTimeout() {
        if (isDestroyed) return  // Ignore timeout after destruction
        Log.d(TAG, "Recognition timeout - will restart")
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

            // Check for wake word using pre-compiled patterns (faster than multiple contains)
            val lowerText = text.lowercase()
            val detected = WAKE_WORD_PATTERNS.any { pattern -> lowerText.contains(pattern) }

            if (detected) {
                // Atomically set flag to prevent duplicate triggers
                if (wakeWordTriggered || isDestroyed) return
                wakeWordTriggered = true

                Log.i(TAG, ">>> WAKE WORD DETECTED: '$text' <<<")

                // Stop listening to free the mic for Google STT
                stopListening()

                // Notify callback on main thread (only if not destroyed)
                if (!isDestroyed) {
                    scope.launch(Dispatchers.Main) {
                        // Double-check destroyed flag inside coroutine
                        if (!isDestroyed) {
                            onWakeWordDetected()
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Silently ignore parse errors - not critical
        }
    }
}
