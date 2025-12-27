package com.example.smarty.voice

import android.content.Context
import android.util.Log
import com.example.smarty.voice.tts.NeuralTTSManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Manages Text-to-Speech for AI responses using Neural TTS only.
 *
 * Uses Sherpa-ONNX with Piper VITS models for natural-sounding,
 * high-quality speech synthesis that runs entirely offline.
 *
 * Features:
 * - Neural TTS with near-human quality (MOS 4.2-4.5)
 * - Fast initialization with bundled model
 * - Immediate speech trigger when AI responds
 * - Configurable speech rate
 * - Clean lifecycle management
 */
class ResponseTTSManager(
    private val context: Context
) {

    companion object {
        private const val TAG = "ResponseTTSManager"
        private const val DEFAULT_SPEECH_RATE = 1.0f
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Neural TTS engine
    private var neuralTts: NeuralTTSManager? = null

    // Observable speaking state
    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    // TTS enabled state (can be toggled by user)
    private val _isEnabled = MutableStateFlow(true)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    // Currently selected voice
    private val _currentVoice = MutableStateFlow(NeuralTTSManager.VOICE_EN_LESSAC)
    val currentVoice: StateFlow<String> = _currentVoice.asStateFlow()

    // Initialization state
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    // Callbacks for speech lifecycle (for pausing wake word detection)
    private var onSpeechStart: (() -> Unit)? = null
    private var onSpeechEnd: (() -> Unit)? = null

    // Mutex to prevent concurrent initialization attempts
    private val initMutex = Mutex()
    private var initializationAttempted = false

    init {
        // Delay TTS initialization to avoid conflicts with Vosk native library
        // Vosk initializes first, TTS can safely init after a brief delay
        scheduleDelayedInitialization()
    }

    /**
     * Schedule TTS initialization with delay to avoid native library conflicts.
     */
    private fun scheduleDelayedInitialization() {
        scope.launch(Dispatchers.IO) {
            // Wait for Vosk and other native libraries to initialize
            delay(3000)
            initializeNeuralTTSInternal()
        }
    }

    /**
     * Initialize Neural TTS with bundled model.
     * Can be called manually if delayed init hasn't happened yet.
     */
    fun initializeIfNeeded() {
        if (!initializationAttempted && !_isInitialized.value) {
            scope.launch(Dispatchers.IO) {
                initializeNeuralTTSInternal()
            }
        }
    }

    /**
     * Internal TTS initialization with mutex protection.
     */
    private suspend fun initializeNeuralTTSInternal() {
        initMutex.withLock {
            if (initializationAttempted) {
                Log.d(TAG, "TTS initialization already attempted, skipping")
                return
            }
            initializationAttempted = true

            try {
                Log.d(TAG, "Starting Neural TTS initialization...")

                neuralTts = NeuralTTSManager(context)
                val initialized = neuralTts?.initialize(NeuralTTSManager.VOICE_EN_LESSAC) ?: false

                if (initialized) {
                    _currentVoice.value = NeuralTTSManager.VOICE_EN_LESSAC
                    _isInitialized.value = true

                    // Setup callbacks for neural TTS
                    neuralTts?.setSpeechLifecycleCallbacks(
                        onStart = {
                            _isSpeaking.value = true
                            onSpeechStart?.invoke()
                        },
                        onEnd = {
                            _isSpeaking.value = false
                            onSpeechEnd?.invoke()
                        }
                    )

                    Log.d(TAG, "Neural TTS initialized successfully")
                } else {
                    Log.e(TAG, "Failed to initialize Neural TTS - init returned false")
                    neuralTts = null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize Neural TTS: ${e.message}", e)
                neuralTts = null
            }
        }
    }

    /**
     * Speak AI response immediately using neural TTS.
     */
    fun speak(text: String) {
        if (!_isEnabled.value) {
            Log.d(TAG, "TTS disabled, skipping speech")
            return
        }

        if (text.isBlank()) return

        if (!_isInitialized.value || neuralTts == null) {
            Log.w(TAG, "Neural TTS not initialized")
            return
        }

        Log.d(TAG, "Speaking: ${text.take(50)}...")
        neuralTts?.speak(text)
    }

    /**
     * Speak with specific language hint (useful for bilingual content).
     * @param text Text to speak
     * @param language Language code ("en" or "hi")
     */
    fun speak(text: String, language: String) {
        if (!_isEnabled.value) return
        if (text.isBlank()) return

        if (!_isInitialized.value || neuralTts == null) {
            Log.w(TAG, "Neural TTS not initialized")
            return
        }

        neuralTts?.speak(text, language)
    }

    /**
     * Stop current speech immediately.
     * Also resumes wake word detection if TTS was speaking.
     */
    fun stop() {
        val wasSpeaking = _isSpeaking.value

        neuralTts?.stop()
        _isSpeaking.value = false

        // Resume wake word detection if we were speaking
        if (wasSpeaking) {
            try {
                onSpeechEnd?.invoke()
                Log.d(TAG, "TTS stopped manually - resuming wake word detection")
            } catch (e: Exception) {
                Log.e(TAG, "Error invoking onSpeechEnd callback: ${e.message}")
            }
        }
    }

    /**
     * Enable or disable TTS.
     */
    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
        if (!enabled) {
            stop()
        }
        Log.d(TAG, "TTS ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Set speech rate.
     * @param rate Rate multiplier (0.5 = slower, 2.0 = faster)
     */
    fun setSpeechRate(rate: Float) {
        neuralTts?.setSpeechRate(rate)
    }

    /**
     * Set callbacks for speech lifecycle events.
     * Used to pause wake word detection during speech.
     */
    fun setSpeechLifecycleCallbacks(
        onStart: () -> Unit,
        onEnd: () -> Unit
    ) {
        this.onSpeechStart = onStart
        this.onSpeechEnd = onEnd

        // Also set on neural TTS
        neuralTts?.setSpeechLifecycleCallbacks(
            onStart = {
                _isSpeaking.value = true
                onStart()
            },
            onEnd = {
                _isSpeaking.value = false
                onEnd()
            }
        )
    }

    /**
     * Check if neural TTS is ready.
     */
    fun isNeuralTtsReady(): Boolean = _isInitialized.value

    /**
     * Clean up TTS resources.
     * Call when done using TTS (e.g., in ViewModel onCleared).
     */
    fun shutdown() {
        stop()

        // Clear callbacks to prevent memory leaks
        onSpeechStart = null
        onSpeechEnd = null

        // Shutdown neural TTS
        neuralTts?.shutdown()
        neuralTts = null

        _isInitialized.value = false

        Log.d(TAG, "TTS shutdown complete")
    }
}
