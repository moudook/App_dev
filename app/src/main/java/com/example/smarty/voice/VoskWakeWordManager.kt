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
 * Vosk-based wake word manager for fully offline Hindi wake word detection.
 *
 * Architecture:
 * - Uses Vosk (open source, on-device) for wake word detection
 * - Hindi model: vosk-model-small-hi-0.22
 * - Wake word: "जादूगर" (Jaadoogar - Magician) - unique and distinctive
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
 * - Model included in app
 */
class VoskWakeWordManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onWakeWordDetected: () -> Unit
) : RecognitionListener {

    companion object {
        private const val TAG = "VoskWakeWord"

        // Hindi model for better Hindi word recognition
        private const val MODEL_PATH = "vosk-model-small-hi-0.22"
        private const val SAMPLE_RATE = 16000.0f

        // Wake word: "जादूगर" (Jaadoogar) = Magician
        // Very unique word, unlikely to trigger accidentally
        private const val WAKE_WORD = "जादूगर"

        // Audio gain multiplier for increased microphone sensitivity
        // 1.0 = normal, 2.0 = 2x louder, 3.0 = 3x louder
        // Higher values = detect from further away, but more background noise
        private const val AUDIO_GAIN = 8.0f  // 8x amplification for maximum range

        // NOTE: Grammar-restricted mode requires dynamic graph models.
        // Static graph models (like vosk-model-small-*) may not support grammar.
        // We use full vocabulary mode and filter results for wake word.
        private val GRAMMAR: String? = null  // Full vocabulary - filter in software

        // Pre-compiled wake word patterns for faster matching
        // "जादूगर" (Jaadoogar) - Magician - unique Hindi word
        // Reduced to essential patterns since Hindi model is accurate
        private val WAKE_WORD_PATTERNS = setOf(
            // Primary Hindi pattern
            "जादूगर",

            // Minor spelling variations
            "जादुगर",
            "जादू गर"
        )
    }

    // Thread-safe mutex for state operations
    private val stateMutex = Mutex()

    private var model: Model? = null
    private var speechService: HighSensitivitySpeechService? = null
    private var recognizer: Recognizer? = null

    // Flag to auto-start listening after initialization
    @Volatile
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
     * Check if the native model is still valid.
     * After process death, native objects become invalid even though references exist.
     * This safely tests model validity without crashing.
     */
    private fun isModelValid(): Boolean {
        val m = model ?: return false
        return try {
            // Try to create a recognizer - if model is invalid, this will throw
            val testRecognizer = Recognizer(m, SAMPLE_RATE)
            testRecognizer.close()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Model validity check failed: ${e.message}")
            false
        }
    }

    /**
     * Force invalidate all state - used after detecting invalid model.
     * This resets everything so re-initialization can occur.
     */
    private fun invalidateState() {
        Log.w(TAG, "Invalidating all Vosk state for re-initialization")

        // Stop any active listening
        try {
            speechService?.stop()
            speechService?.shutdown()
        } catch (_: Exception) {}
        speechService = null

        // Close recognizer
        try {
            recognizer?.close()
        } catch (_: Exception) {}
        recognizer = null

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
                invalidateState()
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
            Log.i(TAG, "Unpacking Vosk Hindi model from assets: $MODEL_PATH")

            // StorageService.unpack already runs on background thread
            // Callbacks are invoked on main thread
            StorageService.unpack(
                context,
                MODEL_PATH,
                "model-hi",  // Different folder name to avoid conflicts
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
                    Log.i(TAG, "Hindi model unpacked in ${elapsed}ms")
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

                    val errorMsg = "Failed to unpack Hindi model: ${exception.message}"
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
            val m = model
            if (m == null) {
                Log.e(TAG, "Cannot setup recognizer - model is null")
                _initError.value = "Model not loaded"
                return
            }

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
            Log.i(TAG, "Recognizer ready - listening for Hindi wake word '$WAKE_WORD' (Jaadoogar)")

            // Auto-start if startListening was called before init completed
            // But only if not destroyed
            if (shouldStartAfterInit && !isDestroyed) {
                shouldStartAfterInit = false
                Log.i(TAG, "Auto-starting listener after init")
                startListening()
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
     *
     * PROCESS DEATH SAFE: Validates model before starting.
     */
    fun startListening() {
        // CRITICAL: Check if destroyed
        if (isDestroyed) {
            Log.d(TAG, "Manager destroyed - not starting listener")
            return
        }

        // Check if model needs re-initialization after process death
        if (_isInitialized.value && !isModelValid()) {
            Log.w(TAG, "Model invalid - triggering re-initialization before listening")
            invalidateState()
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

        val rec = recognizer
        if (rec == null) {
            Log.e(TAG, "Cannot start - recognizer is null, triggering re-init")
            invalidateState()
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
            speechService?.startListening(this)
            _isListening.value = true
            Log.i(TAG, "Started listening for Hindi wake word '$WAKE_WORD' with ${AUDIO_GAIN}x gain")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start speech service: ${e.message}", e)
            _initError.value = "Failed to start: ${e.message}"

            // Might be invalid AudioRecord - try re-init
            if (e is IllegalStateException) {
                Log.w(TAG, "IllegalStateException - attempting recovery")
                invalidateState()
                shouldStartAfterInit = true
                initialize()
            }
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
            // Force cleanup even on error
            speechService = null
            _isListening.value = false
        }
    }

    /**
     * Restart listening after Google STT completes.
     * This re-enables wake word detection.
     *
     * PROCESS DEATH SAFE: Validates model and re-initializes if needed.
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

        // Check model validity - might be invalid after process death
        if (!isModelValid()) {
            Log.w(TAG, "Model invalid - triggering full re-initialization")
            invalidateState()
            shouldStartAfterInit = true
            initialize()
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

            val m = model
            if (m != null) {
                recognizer = if (GRAMMAR != null) {
                    Recognizer(m, SAMPLE_RATE, GRAMMAR)
                } else {
                    Recognizer(m, SAMPLE_RATE)
                }
                startListening()
            } else {
                // Model is null - need to reinitialize
                Log.w(TAG, "Model is null - triggering re-initialization")
                invalidateState()
                shouldStartAfterInit = true
                initialize()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart: ${e.message}", e)
            // Attempt recovery through full re-init
            invalidateState()
            shouldStartAfterInit = true
            initialize()
        }
    }

    /**
     * Check if wake word detection is ready and operational.
     * Use this before relying on wake word functionality.
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

        // Check if error indicates invalid state - might need re-init
        if (exception is IllegalStateException) {
            Log.w(TAG, "IllegalStateException in recognition - may need re-init")
        }
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
                wakeWordTriggered = true

                Log.w(TAG, "============================================")
                Log.w(TAG, ">>> WAKE WORD DETECTED: '$text' <<<")
                Log.w(TAG, "============================================")

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
        } catch (e: Exception) {
            // Log parse errors for debugging
            Log.w(TAG, "VOSK_SPEECH [PARSE_ERROR]: ${e.message}")
        }
    }
}
